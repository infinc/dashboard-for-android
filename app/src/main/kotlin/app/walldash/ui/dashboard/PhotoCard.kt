package app.walldash.ui.dashboard

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.walldash.data.PhotoConfig
import app.walldash.data.PhotoState
import app.walldash.ui.common.EmptyText
import app.walldash.ui.common.Tabular
import app.walldash.ui.common.WdCard
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.tu
import java.time.Instant
import java.time.ZoneId

/**
 * iCloud の共有アルバムの写真を、設定の間隔で切り替えて出す。写真はカードいっぱいに（はみ出す分は切る）。
 * 押すと待たずに次の写真へ。左下に撮った日と説明（あれば）。
 */
@Composable
fun PhotoCard(
    frame: DashboardViewModel.PhotoFrame?,
    state: PhotoState?,
    config: PhotoConfig,
    onNext: () -> Unit,
    modifier: Modifier,
) {
    if (frame == null) {
        WdCard("写真", modifier, note = state?.albumName) {
            when {
                !config.enabled || config.albumUrl.isNullOrBlank() ->
                    EmptyText("写真は未設定です。設定画面の「写真」で iCloud の共有アルバムの URL を入れてください。")
                state?.lastError != null -> EmptyText("取得できません: ${state.lastError}", Wd.Red)
                else -> EmptyText("読み込み中…")
            }
        }
        return
    }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.clip(shape).background(Wd.Surface).border(1.dp, Wd.Border, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onNext),
    ) {
        Crossfade(frame, Modifier.fillMaxSize(), animationSpec = tween(900), label = "photo") { f ->
            Image(f.image, f.caption, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        val date = takenDate(frame.takenAt)
        val label = listOfNotNull(date, frame.caption?.trim()?.takeIf { it.isNotEmpty() }).joinToString("  ")
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                .padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 9.dp),
        ) {
            if (label.isNotEmpty()) {
                Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 12.tu, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            "${frame.index} / ${frame.count}",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 10.5f.tu,
            style = Tabular,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 共有アルバムの dateCreated（"2024-05-03T12:34:56Z"）→ "2024/5/3"。 */
private fun takenDate(s: String?): String? = s?.let {
    runCatching {
        val d = Instant.parse(it).atZone(ZoneId.systemDefault())
        "${d.year}/${d.monthValue}/${d.dayOfMonth}"
    }.getOrNull()
}
