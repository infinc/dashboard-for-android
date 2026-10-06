package app.dashboard.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.dashboard.AppGraph
import app.dashboard.i18n.L
import app.dashboard.server.Auth
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 設定画面を開く前に PIN を聞く（設定の「設定の PIN」がオンのときだけ出す）。 */
@Composable
fun SettingsPinDialog(graph: AppGraph, onUnlock: () -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun submit() {
        if (busy || pin.isEmpty()) return
        val value = pin
        busy = true
        scope.launch {
            // PBKDF2 は古い端末で 1 秒近くかかるので、画面のスレッドでは回さない
            val result = withContext(Dispatchers.Default) { graph.auth.unlockSettings(value) }
            busy = false
            pin = ""
            when (result) {
                Auth.UnlockResult.Success -> {
                    // キーボードを出したままダイアログを閉じると、下の画面にキーボードの分の余白が残って設定画面が縮むので、先に閉じて待つ
                    focusManager.clearFocus()
                    keyboard?.hide()
                    delay(250)
                    onUnlock()
                }
                is Auth.UnlockResult.Failed -> message = L("PIN が違います（あと ${result.remaining} 回）", "Wrong PIN (${result.remaining} tries left)")
                is Auth.UnlockResult.Locked -> message = L("続けてまちがえたため、${result.retryAfterSeconds} 秒は入力できません", "Too many tries. Try again in ${result.retryAfterSeconds} seconds")
            }
        }
    }

    // 別のウィンドウ（AlertDialog）にすると、閉じたあとも下の画面にキーボードの分の余白が残って設定画面が縮むので、同じウィンドウに重ねる
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .imePadding(),
        // キーボードが出ても「開く」が隠れないよう、上寄せにする
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.padding(top = 56.dp).widthIn(max = 460.dp).fillMaxWidth(0.9f).clip(RoundedCornerShape(24.dp)).background(Wd.Surface2)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(24.dp),
        ) {
            Text(L("設定の PIN", "Settings PIN"), fontSize = 22.tu)
            Text(L("設定を開くには PIN を入れてください。", "Enter the PIN to open Settings."), color = Wd.Text2, fontSize = 13.tu, modifier = Modifier.padding(top = 14.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(12) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).focusRequester(focus),
            )
            if (message.isNotEmpty()) Text(message, color = Wd.Red, fontSize = 12.tu, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(L("キャンセル", "Cancel")) }
                TextButton(onClick = ::submit, enabled = !busy) { Text(L("開く", "Open")) }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
