package app.dashboard.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dashboard.data.WidgetData
import app.dashboard.data.WidgetDef
import app.dashboard.data.WidgetFormat
import app.dashboard.data.WidgetLevel
import app.dashboard.data.WidgetPoint
import app.dashboard.i18n.L
import app.dashboard.i18n.SEP
import app.dashboard.ui.common.Tabular
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

/**
 * 自作カードの全画面（数値・メーターの 24 時間の履歴）。上にいまの値・最高・最低・平均・しきい値、下に大きな折れ線
 * （右に値の目盛り、下に時刻、触れている間は縦線と値）。枠は雨雲レーダー・暗号通貨と同じ [FullscreenFrame]。
 */
@Composable
fun WidgetScreen(
    def: WidgetDef,
    data: WidgetData?,
    now: Long,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    FullscreenFrame(Wd.Bg, keepAwake, onKeepAwake, onBack, backTint = Wd.Text) { _, _ ->
        Column(Modifier.fillMaxSize().padding(start = 84.dp, end = 24.dp, top = 18.dp, bottom = 16.dp)) {
            val d = data?.takeIf { it.key == def.dataKey }
            val value = d?.number
            val level = WidgetLevel.of(value, def)
            Row(Modifier.fillMaxWidth().padding(end = 190.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(def.label, color = Wd.Text2, fontSize = 16.tu, maxLines = 1)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            value?.let { WidgetFormat.number(it, def.decimals) } ?: "—",
                            color = levelColor(level), fontSize = 54.tu, fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1,
                        )
                        d?.unit?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Wd.Text2, fontSize = 22.tu, modifier = Modifier.padding(start = 6.dp, bottom = 9.dp))
                        }
                    }
                }
                val history = d?.history.orEmpty()
                if (history.isNotEmpty()) {
                    Column(horizontalAlignment = Alignment.End) {
                        val avg = history.sumOf { it.v } / history.size
                        Text(
                            L("24 時間の最高 ", "24 h high ") + WidgetFormat.number(history.maxOf { it.v }, def.decimals) + SEP +
                                L("最低 ", "low ") + WidgetFormat.number(history.minOf { it.v }, def.decimals) + SEP +
                                L("平均 ", "avg ") + WidgetFormat.number(avg, def.decimals),
                            color = Wd.Text2, fontSize = 14.tu, style = Tabular, maxLines = 1,
                        )
                        Text(
                            listOfNotNull(thresholdLine(def, d?.unit), d?.updatedAt?.takeIf { it > 0 }?.let { L("更新 ", "Updated ") + relative(it, now) }).joinToString(SEP),
                            color = Wd.Text3, fontSize = 12.tu, maxLines = 1, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            HistoryChart(def, d?.history.orEmpty(), Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun HistoryChart(def: WidgetDef, points: List<WidgetPoint>, modifier: Modifier) {
    if (points.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(L("まだ履歴がありません（5 分ごとに 1 点たまります）", "No history yet (one point every 5 minutes)"), color = Wd.Text3, fontSize = 16.tu)
        }
        return
    }
    var touchX by remember { mutableStateOf<Float?>(null) }
    var plotWidth by remember { mutableFloatStateOf(1f) }
    val t0 = points.first().t
    val t1 = max(points.last().t, t0 + 1)
    var lo = points.minOf { it.v }
    var hi = points.maxOf { it.v }
    listOfNotNull(def.warnAt, def.alertAt).forEach { at ->
        val span = max(hi - lo, 1e-9)
        if (at >= lo - span && at <= hi + span) {
            lo = minOf(lo, at)
            hi = maxOf(hi, at)
        }
    }
    if (hi - lo < 1e-9) { hi += 0.5; lo -= 0.5 }
    val ticks = List(5) { i -> lo + (hi - lo) * i / 4 }
    val gutter = 80.dp
    val gutterPx = with(LocalDensity.current) { gutter.toPx() }
    val color = levelColor(WidgetLevel.of(points.last().v, def), Wd.Cyan)
    val grid = Wd.Ink.copy(alpha = 0.08f)
    val guide = Wd.Ink.copy(alpha = 0.22f)
    val amber = Wd.Amber
    val red = Wd.Red
    val zone = ZoneId.systemDefault()

    fun nearest(x: Float): WidgetPoint {
        val t = t0 + ((x.coerceIn(0f, plotWidth) / plotWidth) * (t1 - t0)).toLong()
        return points.minBy { abs(it.t - t) }
    }

    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { plotWidth = (it.width - gutterPx).coerceAtLeast(1f) }) {
                Canvas(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            touchX = awaitFirstDown().position.x
                            while (true) {
                                val c = awaitPointerEvent().changes.firstOrNull() ?: break
                                if (!c.pressed) break
                                touchX = c.position.x
                                c.consume()
                            }
                            touchX = null
                        }
                    },
                ) {
                    val w = size.width - gutter.toPx()
                    val h = size.height
                    val pad = 6.dp.toPx()
                    fun x(t: Long) = ((t - t0).toDouble() / (t1 - t0) * w).toFloat()
                    fun y(v: Double) = pad + ((h - pad * 2) * (1 - (v - lo) / (hi - lo))).toFloat()
                    ticks.forEach { v -> drawLine(grid, Offset(0f, y(v)), Offset(w, y(v)), 1f) }
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                    def.warnAt?.takeIf { it in lo..hi }?.let { drawLine(amber.copy(alpha = 0.7f), Offset(0f, y(it)), Offset(w, y(it)), 1.5f, pathEffect = dash) }
                    def.alertAt?.takeIf { it in lo..hi }?.let { drawLine(red.copy(alpha = 0.7f), Offset(0f, y(it)), Offset(w, y(it)), 1.5f, pathEffect = dash) }
                    // 15 分より空いたところ（電源が落ちていた・届かなかった）は線をつながない
                    val line = Path()
                    points.forEachIndexed { i, p ->
                        if (i == 0 || p.t - points[i - 1].t > GAP_MS) line.moveTo(x(p.t), y(p.v)) else line.lineTo(x(p.t), y(p.v))
                    }
                    val fill = Path().apply {
                        addPath(line)
                        lineTo(x(points.last().t), h)
                        lineTo(x(points.first().t), h)
                        close()
                    }
                    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.24f), color.copy(alpha = 0f))))
                    drawPath(line, color, style = Stroke(2.2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    touchX?.let { tx ->
                        val cx = tx.coerceIn(0f, w)
                        drawLine(guide, Offset(cx, 0f), Offset(cx, h), 1.2f * density)
                        val p = nearest(cx)
                        drawCircle(color, 4.dp.toPx(), Offset(x(p.t), y(p.v)))
                    }
                }
                ticks.forEach { v ->
                    val frac = ((v - lo) / (hi - lo)).toFloat()
                    Box(Modifier.fillMaxSize()) {
                        Text(
                            WidgetFormat.number(v, def.decimals), color = Wd.Text3, fontSize = 11.tu, style = Tabular, maxLines = 1,
                            modifier = Modifier.align(BiasAlignment(1f, 1f - 2f * frac)).padding(start = 8.dp),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(end = gutter, top = 4.dp)) {
                List(5) { i -> t0 + (t1 - t0) * i / 4 }.forEachIndexed { i, t ->
                    Text(
                        clock(t, zone), color = Wd.Text3, fontSize = 11.tu, maxLines = 1,
                        textAlign = when (i) { 0 -> TextAlign.Start; 4 -> TextAlign.End; else -> TextAlign.Center },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        touchX?.let { tx ->
            val p = nearest(tx)
            Column(
                Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Wd.Surface.copy(alpha = 0.92f))
                    .border(1.dp, Wd.Border, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(clock(p.t, zone), color = Wd.Text3, fontSize = 12.tu)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(WidgetFormat.number(p.v, def.decimals), color = Wd.Text, fontSize = 18.tu, fontWeight = FontWeight.SemiBold, style = Tabular)
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
    }
}

private const val GAP_MS = 15 * 60_000L

private fun clock(t: Long, zone: ZoneId): String {
    val z = Instant.ofEpochMilli(t).atZone(zone)
    return "%d/%d %02d:%02d".format(z.monthValue, z.dayOfMonth, z.hour, z.minute)
}
