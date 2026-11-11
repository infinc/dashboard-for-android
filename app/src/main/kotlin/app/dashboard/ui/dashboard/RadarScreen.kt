package app.dashboard.ui.dashboard

import app.dashboard.i18n.L
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.dashboard.ui.common.WdIcons
import app.dashboard.ui.theme.LocalAccent
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
    anim: DashboardViewModel.RainAnim?,
    onAnimStart: () -> Unit,
    onAnimToggle: () -> Unit,
    onAnimSeek: (Int) -> Unit,
    onAnimStop: () -> Unit,
    onBack: () -> Unit,
) {
    DisposableEffect(Unit) {
        onWide(true)
        onDispose { onWide(false) }
    }
    FullscreenFrame(Wd.Bg, keepAwake, onKeepAwake, onBack, backTint = Wd.Text) { _, _ ->
        val shown = anim?.takeIf { it.ready }?.frames?.getOrNull(anim.index)
        RadarMap(frame, onPan, Modifier.fillMaxSize(), animRain = shown?.tiles)
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
                listOf(if (frame.panned) "" else place, shown?.label(anim.startedAt) ?: frame.label).filter { it.isNotEmpty() }.joinToString(L(" ・ ", " · ")).ifEmpty { L("雨雲レーダー", "Rain radar") },
                color = Wd.Text, fontSize = 16.tu, fontWeight = FontWeight.SemiBold,
            )
            RainLegend(Modifier.padding(top = 8.dp))
        }
        if (frame.zoom != 0 && frame.base.isNotEmpty()) {
            AnimControls(anim, onAnimStart, onAnimToggle, onAnimSeek, onAnimStop, Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }
}

/** 雨雲の再生の操作。始める前は 1 つのボタン、集めている間は進み具合、集まったら ▶／⏸・コマの帯（押すとそのコマへ）・閉じる。 */
@Composable
private fun AnimControls(
    anim: DashboardViewModel.RainAnim?,
    onStart: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier,
) {
    val accent = LocalAccent.current
    val pill = Modifier.clip(RoundedCornerShape(12.dp)).background(Wd.Surface.copy(alpha = 0.85f))
    when {
        anim == null -> Row(
            modifier.then(pill).clickable(onClickLabel = L("雨雲の動きを見る", "Play rain movement"), onClick = onStart)
                .semantics(mergeDescendants = true) { contentDescription = L("雨雲の動きを見る", "Play rain movement") }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(WdIcons.Play, null, tint = accent, modifier = Modifier.size(22.dp))
            Text(L("雨雲の動き", "Rain movement"), color = Wd.Text, fontSize = 15.tu, modifier = Modifier.padding(start = 6.dp))
        }
        anim.failed -> Row(modifier.then(pill).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(L("雨雲の動きを取得できません", "Couldn't load the rain movement"), color = Wd.Red, fontSize = 14.tu)
            CloseButton(onStop)
        }
        !anim.ready -> Row(modifier.then(pill).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                L("雨雲を集めています… ${anim.loaded}/${anim.total.takeIf { it > 0 } ?: "?"}", "Loading rain… ${anim.loaded}/${anim.total.takeIf { it > 0 } ?: "?"}"),
                color = Wd.Text2, fontSize = 14.tu,
            )
            CloseButton(onStop)
        }
        else -> Row(modifier.then(pill).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clickable(
                    onClickLabel = if (anim.playing) L("一時停止", "Pause") else L("再生", "Play"), onClick = onToggle,
                ).semantics(mergeDescendants = true) { contentDescription = if (anim.playing) L("一時停止", "Pause") else L("再生", "Play") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (anim.playing) WdIcons.Pause else WdIcons.Play, null, tint = Wd.Text, modifier = Modifier.size(24.dp))
            }
            Row(Modifier.width((anim.frames.size * 26).dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                anim.frames.forEachIndexed { i, f ->
                    val on = i == anim.index
                    val color = if (f.forecast) Wd.Amber else accent
                    Box(
                        Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(3.dp))
                            .background(color.copy(alpha = if (on) 1f else 0.35f))
                            .clickable(onClickLabel = f.label(anim.startedAt)) { onSeek(i) },
                    )
                }
            }
            CloseButton(onStop)
        }
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clickable(onClickLabel = L("閉じる", "Close"), onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = L("雨雲の動きを閉じる", "Close rain movement") },
        contentAlignment = Alignment.Center,
    ) {
        Icon(WdIcons.Close, null, tint = Wd.Text3, modifier = Modifier.size(20.dp))
    }
}