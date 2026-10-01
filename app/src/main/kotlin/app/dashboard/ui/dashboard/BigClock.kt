package app.dashboard.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import app.dashboard.data.DisplayConfig
import app.dashboard.data.UnitsConfig
import app.dashboard.ui.common.Tabular
import app.dashboard.ui.common.WdIcons
import app.dashboard.ui.theme.LocalAccent
import app.dashboard.ui.theme.tu
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * 時刻を画面いっぱいに出す（時刻カードの見出しの右のボタンから開く）。
 * 暗い背景の真ん中に大きく時刻、その下に日付。時刻の書き方（24 時間制・秒・日付の形）は時刻カードと同じ設定に従う。
 * 左上の「<」か端末の戻る操作で閉じる。右上の小さなボタンで「画面を暗くしない」を切り替える（Spotify の全画面と同じ設定）。
 * 右上のボタンは [IDLE_HIDE_MS] 触られなければ溶けるように消え、どこかに触れると戻る。
 * 同じ画素を光らせ続けないよう、時刻と日付は 1 分ごとに少しずつ位置をずらす。
 */
@Composable
fun BigClockScreen(
    now: Long,
    units: UnitsConfig,
    display: DisplayConfig,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    onBack: () -> Unit,
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
    val accent = LocalAccent.current
    val (hour, suffix, date) = clockParts(now, units, display)
    val t = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    val step = (now / 60_000L % DRIFT.size).toInt()
    val dx by animateDpAsState(DRIFT[step].first.dp, tween(4000), label = "driftX")
    val dy by animateDpAsState(DRIFT[step].second.dp, tween(4000), label = "driftY")

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF10141B), Color(0xFF07090D))))
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent), Offset(size.width / 2, size.height * 0.45f), size.minDimension * 0.7f),
                    size.minDimension * 0.7f,
                    Offset(size.width / 2, size.height * 0.45f),
                )
            }
            // 画面のどこに触れても（ボタンの上でも）右上のボタンを戻す。触れた操作はそのままボタンにも届く
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
        // 「88:88」が横幅の 8 割ほど、縦は画面の 4 割ほどに収まる大きさ
        val big = min(maxWidth * 0.26f, maxHeight * 0.42f).value
        Column(
            Modifier.align(Alignment.Center).offset(dx, dy),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                buildAnnotatedString {
                    append("$hour:%02d".format(t.minute))
                    withStyle(SpanStyle(fontSize = (big * 0.26f).tu, color = accent, fontWeight = FontWeight.SemiBold, letterSpacing = 0.em)) {
                        if (units.showSeconds) append(" %02d".format(t.second))
                        if (suffix.isNotEmpty()) append(" $suffix")
                    }
                },
                color = Color.White,
                fontSize = big.tu,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.03).em,
                lineHeight = 1.em,
                style = Tabular,
                maxLines = 1,
            )
            Text(
                date,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = (big * 0.16f).coerceIn(18f, 40f).tu,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // 戻る: 「<」だけ（押せる範囲は指の大きさにする）
        Box(
            Modifier.align(Alignment.TopStart).padding(14.dp).size(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WdIcons.Back, "戻る", tint = Color.White, modifier = Modifier.size(30.dp))
        }

        // 画面を暗くしない（右上に小さく）。消えている間は押せない
        AwakeToggle(
            keepAwake,
            enabled = shown,
            onToggle = { onKeepAwake(!keepAwake) },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 20.dp).melt(melt),
        )
    }
}

/** 焼き付き防止のずらし（dp）。1 分ごとに次へ進む。 */
private val DRIFT = listOf(0 to 0, 10 to 4, 4 to 12, -8 to 8, -12 to -2, -4 to -12, 8 to -8)
