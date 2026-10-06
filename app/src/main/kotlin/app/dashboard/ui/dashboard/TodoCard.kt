package app.dashboard.ui.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dashboard.data.TodoItem
import app.dashboard.i18n.L
import app.dashboard.ui.common.EmptyText
import app.dashboard.ui.common.WdCard
import app.dashboard.ui.common.WdIcons
import app.dashboard.ui.theme.LocalAccent
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu

/**
 * Todo カード。下の入力欄で足し、左の丸（チェック）を押すとその場で消える。
 * 多いときは縦にスクロールする。
 */
@Composable
fun TodoCard(items: List<TodoItem>, onAdd: (String) -> Unit, onDone: (Long) -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf("") }

    fun submit() {
        if (text.isBlank()) {
            focus.clearFocus()
            return
        }
        onAdd(text)
        text = ""
    }

    WdCard(L("Todo", "To-do"), modifier, note = if (items.isEmpty()) null else L("${items.size} 件", "${items.size}")) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                EmptyText(L("やることはありません。下の欄に書いて追加できます。", "Nothing to do. Type below to add one."))
            } else {
                LazyColumn(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(items, key = { it.id }) { item -> TodoRow(item, accent) { onDone(item.id) } }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Wd.Bg.copy(alpha = 0.55f))
                .border(1.dp, Wd.BorderSoft, RoundedCornerShape(10.dp)).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 9.dp)) {
                if (text.isEmpty()) Text(L("やることを追加", "Add a to-do"), color = Wd.Text3, fontSize = 14.tu, maxLines = 1)
                BasicTextField(
                    text,
                    { text = it.replace("\n", "") },
                    singleLine = true,
                    textStyle = TextStyle(color = Wd.Text, fontSize = 14.tu),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = ::submit),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = if (text.isBlank()) Wd.Text3 else accent, fontSize = 22.tu)
            }
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem, accent: Color, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onDone)
                .semantics { contentDescription = L("完了", "Done") },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).border(1.6.dp, accent.copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(WdIcons.Check, null, tint = accent.copy(alpha = 0.35f), modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(item.text, fontSize = 14.5f.tu, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
