package app.dashboard.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.dashboard.ui.common.WdIcons
import kotlinx.coroutines.delay

/**
 * 全画面の共通の枠（雨雲レーダー・暗号通貨）。時刻・Spotify の全画面と同じく、
 * 左上の「<」と端末の戻る操作で閉じ、右上に「暗くしない」の切り替え（[IDLE_HIDE_MS] 触られなければ溶けるように消え、触れると戻る）。
 * [content] には右上のボタンが見えているか（[shown]）と、その溶け具合（0〜1、[Modifier.melt] に渡す）を渡す。
 */
@Composable
internal fun FullscreenFrame(
    background: Color,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    onBack: () -> Unit,
    backTint: Color = Color.White,
    content: @Composable BoxScope.(shown: Boolean, melt: Float) -> Unit,
) {
    BackHandler(onBack = onBack)
    var touches by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(touches) {
        shown = true
        delay(IDLE_HIDE_MS)
        shown = false
    }
    val melt by animateFloatAsState(
        if (shown) 1f else 0f,
        if (shown) tween(280, easing = FastOutSlowInEasing) else tween(1100, easing = LinearOutSlowInEasing),
        label = "melt",
    )
    Box(
        Modifier.fillMaxSize().background(background)
            // 画面のどこに触れても（ボタンの上でも）右上のボタンを戻す。触れた操作はそのまま下にも届く
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed && !it.previousPressed }) touches++
                    }
                }
            }
            // 下のダッシュボードに触れさせない
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        content(shown, melt)
        Box(
            Modifier.align(Alignment.TopStart).padding(14.dp).size(52.dp)
                .clip(RoundedCornerShape(26.dp)).background(Color.Black.copy(alpha = 0.25f))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WdIcons.Back, "戻る", tint = backTint, modifier = Modifier.size(30.dp))
        }
        AwakeToggle(
            keepAwake,
            enabled = shown,
            onToggle = { onKeepAwake(!keepAwake) },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 20.dp).melt(melt),
        )
    }
}
