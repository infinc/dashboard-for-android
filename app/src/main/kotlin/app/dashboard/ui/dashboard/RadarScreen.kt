package app.dashboard.ui.dashboard

import app.dashboard.i18n.L
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dashboard.ui.common.EmptyText
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu

/**
 * 雨雲レーダーを画面いっぱいに出す（カードの見出しの右のボタンから開く）。
 * 地図はカードと同じもの（ドラッグで動かす）。右に大きな「＋」「−」「現在地に戻る」、左下に地点・観測時刻・凡例。
 * 開いている間は画面の端まで埋まるよう広い範囲のタイルを取る（[DashboardViewModel.setRadarFullscreen]）。
 */
@Composable
fun RadarScreen(
    frame: DashboardViewModel.RadarFrame,
    place: String,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    onPan: (Float, Float) -> Unit,
    onZoom: (Int) -> Unit,
    onRecenter: () -> Unit,
    onWide: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    DisposableEffect(Unit) {
        onWide(true)
        onDispose { onWide(false) }
    }
    FullscreenFrame(Wd.Bg, keepAwake, onKeepAwake, onBack, backTint = Wd.Text) { _, _ ->
        RadarMap(frame, onPan, Modifier.fillMaxSize())
        if (frame.base.isEmpty() && frame.old.isEmpty()) {
            Column(Modifier.align(Alignment.Center)) {
                EmptyText(if (frame.failed) L("地図を取得できません", "Couldn't load the map") else L("取得中…", "Loading…"), if (frame.failed) Wd.Red else Wd.Text3)
            }
        }
        if (frame.zoom != 0) {
            ZoomButtons(frame, onZoom, onRecenter, 52.dp, Modifier.align(Alignment.CenterEnd).padding(end = 20.dp))
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(16.dp)
                .clip(RoundedCornerShape(12.dp)).background(Wd.Surface.copy(alpha = 0.85f)).padding(12.dp),
        ) {
            Text(
                listOf(if (frame.panned) "" else place, frame.label).filter { it.isNotEmpty() }.joinToString(L(" ・ ", " · ")).ifEmpty { L("雨雲レーダー", "Rain radar") },
                color = Wd.Text, fontSize = 16.tu, fontWeight = FontWeight.SemiBold,
            )
            RainLegend(Modifier.padding(top = 8.dp))
        }
    }
}
