package app.dashboard.ui.dashboard

import app.dashboard.i18n.L
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dashboard.data.PhotoConfig
import app.dashboard.data.PhotoState
import app.dashboard.ui.common.EmptyText
import app.dashboard.ui.common.Tabular
import app.dashboard.ui.common.WdCard
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import java.time.Instant
import java.time.ZoneId

/**
 * iCloud の共有アルバムの写真を、設定の間隔で切り替えて出す。写真はカードいっぱいに（はみ出す分は切る）。
 * 押すと待たずに次の写真へ、長押しでスクリーンセーバー（[PhotoScreen]）。左下に撮った日と説明（あれば）。
 */
@Composable
fun PhotoCard(
    frame: DashboardViewModel.PhotoFrame?,
    state: PhotoState?,
    config: PhotoConfig,
    onNext: () -> Unit,
    onScreensaver: () -> Unit,
    modifier: Modifier,
) {
    if (frame == null) {
        WdCard(L("写真", "Photos"), modifier, note = state?.albumName) {
            when {
                !config.enabled || config.albumUrl.isNullOrBlank() ->
                    EmptyText(L("写真は未設定です。設定画面の「写真」で iCloud の共有アルバムの URL を入れてください。", "Photos aren't set up. Enter an iCloud Shared Album URL under \"Photos\" in Settings."))
                state?.lastError != null -> EmptyText(L("取得できません: ${state.lastError}", "Unavailable: ${state.lastError}"), Wd.Red)
                else -> EmptyText(L("読み込み中…", "Loading…"))
            }
        }
        return
    }
    val shape = app.dashboard.ui.theme.cardShape()
    Box(
        modifier.clip(shape).background(Wd.Surface).border(1.dp, Wd.Border, shape)
            .pointerInput(Unit) { detectTapGestures(onTap = { onNext() }, onLongPress = { onScreensaver() }) },
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

/**
 * 写真のスクリーンセーバー（写真カードの長押し）。写真を画面いっぱいに（余白なし）、カードと同じ間隔（設定の「写真を変える間隔」）で切り替える。
 * 左上の「<」と戻る操作で閉じる。「暗くしない」は無い（無操作の減光は設定どおり）。
 */
@Composable
fun PhotoScreen(frame: DashboardViewModel.PhotoFrame?, onBack: () -> Unit) {
    FullscreenFrame(Color.Black, keepAwake = false, onKeepAwake = {}, onBack = onBack, awakeToggle = false) { _, _ ->
        if (frame == null) {
            Text(L("読み込み中…", "Loading…"), color = Color.White.copy(alpha = 0.6f), fontSize = 16.tu, modifier = Modifier.align(Alignment.Center))
            return@FullscreenFrame
        }
        Crossfade(frame, Modifier.fillMaxSize(), animationSpec = tween(1400), label = "screensaver") { f ->
            // 画面いっぱいに（縦横の比が画面と違う写真は、はみ出す分を切る。左右・上下に余白を出さない）
            Image(f.image, f.caption, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        val label = listOfNotNull(takenDate(frame.takenAt), frame.caption?.trim()?.takeIf { it.isNotEmpty() }).joinToString("  ")
        if (label.isNotEmpty()) {
            Text(
                label, color = Color.White.copy(alpha = 0.75f), fontSize = 14.tu, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 18.dp, end = 24.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** 共有アルバムの dateCreated（"2024-05-03T12:34:56Z"）→ "2024/5/3"。 */
private fun takenDate(s: String?): String? = s?.let {
    runCatching {
        val d = Instant.parse(it).atZone(ZoneId.systemDefault())
        "${d.year}/${d.monthValue}/${d.dayOfMonth}"
    }.getOrNull()
}
