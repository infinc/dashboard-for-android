package app.walldash.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.walldash.data.DeviceStats
import app.walldash.data.WeatherState
import app.walldash.ui.common.EmptyText
import app.walldash.ui.common.Hairline
import app.walldash.ui.common.Tabular
import app.walldash.ui.common.WdCard
import app.walldash.ui.theme.LocalAccent
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.lighten
import app.walldash.ui.theme.tu
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------- 時間別予報

@Composable
fun HourlyCard(w: WeatherState?, mode: String, modifier: Modifier) {
    val accent = LocalAccent.current
    val wantTemp = mode != "precip"
    val wantPop = mode != "temp"
    WdCard("時間別予報（12 時間）", modifier, headerEnd = { Legend(wantTemp, wantPop) }) {
        val hours = w?.hourly.orEmpty()
        val temps = hours.mapNotNull { it.temperature }
        if (hours.isEmpty() || (wantTemp && temps.isEmpty())) {
            EmptyText("予報データがありません。")
            return@WdCard
        }
        val measurer = rememberTextMeasurer()
        val labelTime = TextStyle(color = Wd.Axis, fontSize = 12.tu)
        val labelTemp = TextStyle(color = Wd.Text, fontSize = 12.5f.tu, fontWeight = FontWeight.SemiBold)
        val labelPop = TextStyle(color = accent.lighten(), fontSize = 12.5f.tu, fontWeight = FontWeight.SemiBold)
        val labelAxis = TextStyle(color = Wd.Text3, fontSize = 11.tu)
        Canvas(Modifier.fillMaxSize()) {
            val u = density
            val padX = 38 * u
            val padTop = 36 * u
            val padBottom = 32 * u
            val pctY = 13 * u
            val lo = temps.minOrNull() ?: 0.0
            val hi = (temps.maxOrNull() ?: 1.0).let { if (it - lo < 1) lo + 1 else it }
            val step = (size.width - padX - 26 * u) / max(1, hours.size - 1)
            val pctEvery = if (step < 40 * u) 2 else 1
            val plotH = size.height - padTop - padBottom
            fun x(i: Int) = padX + step * i
            fun y(t: Double) = (padTop + plotH * (1 - (t - lo) / (hi - lo))).toFloat()

            val guide = Color.White.copy(alpha = 0.055f)
            drawLine(guide, Offset(padX, padTop), Offset(size.width - 6 * u, padTop), 1f)
            drawLine(guide, Offset(padX, size.height - padBottom), Offset(size.width - 6 * u, size.height - padBottom), 1f)
            label(measurer, if (wantTemp) "${hi.roundToInt()}°" else "100%", labelAxis, 2 * u, padTop + 4 * u, center = false)
            label(measurer, if (wantTemp) "${lo.roundToInt()}°" else "0%", labelAxis, 2 * u, size.height - padBottom + 4 * u, center = false)

            val barScale = if (wantTemp) 0.55f else 1f
            hours.forEachIndexed { j, h ->
                val p = h.precipitationProbability ?: 0
                if (wantPop && p > 0) {
                    val bh = plotH * (p / 100f) * barScale
                    drawRoundRect(
                        accent.copy(alpha = 0.2f),
                        Offset(x(j) - 7 * u, size.height - padBottom - bh),
                        Size(14 * u, bh),
                        CornerRadius(3 * u),
                    )
                    if (p >= 30 && j % pctEvery == 0) label(measurer, "$p%", labelPop, x(j), pctY)
                }
            }

            if (wantTemp) {
                val line = Path()
                var started = false
                hours.forEachIndexed { j, h ->
                    val t = h.temperature ?: return@forEachIndexed
                    if (!started) { line.moveTo(x(j), y(t)); started = true } else line.lineTo(x(j), y(t))
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(x(hours.size - 1), size.height - padBottom)
                    lineTo(padX, size.height - padBottom)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0f))))
                drawPath(line, accent, style = Stroke(2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            hours.forEachIndexed { j, h ->
                if (j % 2 != 0) return@forEachIndexed
                label(measurer, hhmm(h.time), labelTime, x(j), size.height - 10 * u)
                if (wantTemp) h.temperature?.let { t -> label(measurer, "${t.roundToInt()}°", labelTemp, x(j), y(t) - 8 * u) }
            }
        }
    }
}

/** SVG の <text> と同じく、[y] を文字のベースラインとして描く。 */
private fun DrawScope.label(m: TextMeasurer, text: String, style: TextStyle, x: Float, y: Float, center: Boolean = true) {
    val r = m.measure(text, style)
    drawText(r, topLeft = Offset(if (center) x - r.size.width / 2f else x, y - r.firstBaseline))
}

@Composable
private fun Legend(temp: Boolean, pop: Boolean) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (temp) {
            Box(Modifier.size(14.dp, 2.5.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Text(" 気温", color = Wd.Text3, fontSize = 12.tu)
        }
        if (temp && pop) Text("  /  ", color = Wd.Border, fontSize = 12.tu)
        if (pop) {
            Box(Modifier.size(8.dp, 10.dp).clip(RoundedCornerShape(2.dp)).background(accent.copy(alpha = 0.35f)))
            Text(" 降水確率", color = Wd.Text3, fontSize = 12.tu)
        }
    }
}

// ---------------------------------------------------------------- 週間予報

private val RANGE = Brush.horizontalGradient(listOf(Color(0xFF4DD4FF), Color(0xFFFFB347)))

@Composable
fun DailyCard(w: WeatherState?, modifier: Modifier) {
    val accent = LocalAccent.current
    WdCard(
        "週間予報",
        modifier,
        headerEnd = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(RANGE))
                Text(" 気温の範囲（最低→最高）  /  降水量 mm ・ 降水確率", color = Wd.Text3, fontSize = 12.tu, maxLines = 1)
            }
        },
    ) {
        val days = w?.daily.orEmpty()
        if (days.isEmpty()) {
            EmptyText("週間予報がありません。")
            return@WdCard
        }
        val lows = days.mapNotNull { it.tempMin }
        val highs = days.mapNotNull { it.tempMax }
        val gmin = lows.minOrNull() ?: 0.0
        val gmax = (highs.maxOrNull() ?: 1.0).let { if (it - gmin < 1) gmin + 1 else it }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                days.forEachIndexed { i, day ->
                    val date = runCatching { LocalDate.parse(day.date.take(10)) }.getOrNull()
                    val wd = date?.let { listOf("月", "火", "水", "木", "金", "土", "日")[it.dayOfWeek.value - 1] }.orEmpty()
                    val color = when {
                        i == 0 -> accent
                        wd == "日" -> Wd.Red
                        wd == "土" -> Wd.Violet
                        else -> Wd.Text2
                    }
                    Column(
                        Modifier.width(92.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(if (i == 0) "今日" else "${date?.monthValue}/${date?.dayOfMonth} $wd", color = color, fontSize = 12.5f.tu)
                        WeatherIcon(day.weatherCode, true, Modifier.size(26.dp))
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(fmtTemp(day.tempMax)) }
                                withStyle(SpanStyle(color = Wd.Text3)) { append(" " + fmtTemp(day.tempMin)) }
                            },
                            fontSize = 13.5f.tu,
                            style = Tabular,
                        )
                        RangeBar(day.tempMin, day.tempMax, gmin, gmax)
                        Text(
                            buildAnnotatedString {
                                val sum = day.precipitationSum
                                if (sum != null && sum > 0) {
                                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append("%.1f".format(sum)) }
                                    append("mm")
                                } else {
                                    append("0mm")
                                }
                                day.precipitationProbabilityMax?.let { append(" $it%") }
                            },
                            color = Wd.Text3,
                            fontSize = 12.tu,
                            maxLines = 1,
                            style = Tabular,
                        )
                    }
                }
            }
            Hairline(Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 4.dp)) {
                Text("${gmin.roundToInt()}°", color = Wd.Text3, fontSize = 11.5f.tu)
                Spacer(Modifier.weight(1f))
                Text("${gmax.roundToInt()}°", color = Wd.Text3, fontSize = 11.5f.tu)
            }
        }
    }
}

@Composable
private fun RangeBar(lo: Double?, hi: Double?, gmin: Double, gmax: Double) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Wd.BorderSoft)) {
        if (lo == null || hi == null) return@BoxWithConstraints
        val left = ((lo - gmin) / (gmax - gmin)).toFloat()
        val width = max(0.04f, ((hi - lo) / (gmax - gmin)).toFloat())
        Box(
            Modifier.offset(x = maxWidth * left).width(maxWidth * min(width, 1 - left)).fillMaxHeight()
                .clip(RoundedCornerShape(3.dp)).background(RANGE),
        )
    }
}

// ---------------------------------------------------------------- 端末

private val CPU_MARKS = listOf(20, 40, 60, 80, 100)

@Composable
fun StatsCard(s: DeviceStats?, cpuHistory: List<Int>, modifier: Modifier) {
    WdCard("端末", modifier) {
        if (s == null) return@WdCard
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                MeterTop("CPU 使用率", s.cpuPercent?.let { "$it%" } ?: "—")
                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 3.dp)) { CpuChart(cpuHistory) }
            }
            Column(Modifier.weight(1f)) {
                s.batteryPercent?.let { pct ->
                    val color = when {
                        s.charging -> Wd.Green
                        pct < 20 -> Wd.Red
                        pct < 40 -> Wd.Amber
                        else -> null
                    }
                    Meter(if (s.charging) "バッテリー（充電中）" else "バッテリー", if (s.charging) null else "（放電中）", "$pct%", pct / 100f, color)
                }
                s.batteryTemperatureC?.let { c ->
                    val color = when {
                        c >= 45 -> Wd.Red
                        c >= 40 -> Wd.Amber
                        c >= 35 -> null
                        else -> Wd.Green
                    }
                    Meter("温度（バッテリー）", null, "%.1f ℃".format(c), (c / 60).toFloat(), color)
                }
                if (s.storageTotalBytes > 0) {
                    val used = s.storageTotalBytes - s.storageFreeBytes
                    Meter(
                        "ストレージ", null, "%.1f GB 空き".format(s.storageFreeBytes / GB),
                        used.toFloat() / s.storageTotalBytes,
                        if (s.storageFreeBytes.toFloat() / s.storageTotalBytes < 0.1f) Wd.Amber else null,
                    )
                }
                if (s.memoryTotalBytes > 0) {
                    val used = s.memoryTotalBytes - s.memoryAvailableBytes
                    Meter("メモリ", null, "%.1f GB 空き".format(s.memoryAvailableBytes / GB), used.toFloat() / s.memoryTotalBytes, null)
                }
            }
        }
    }
}

private const val GB = 1_073_741_824.0

@Composable
private fun MeterTop(label: String, value: String, warn: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
        Text(
            buildAnnotatedString {
                append(label)
                warn?.let { withStyle(SpanStyle(color = Wd.Red)) { append(it) } }
            },
            color = Wd.Text3,
            fontSize = 12.tu,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(value, fontSize = 12.tu, fontWeight = FontWeight.SemiBold, style = Tabular)
    }
}

@Composable
private fun Meter(label: String, warn: String?, value: String, fraction: Float, color: Color?) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "meter")
    Column(Modifier.padding(bottom = 3.dp)) {
        MeterTop(label, value, warn)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Wd.BorderSoft)) {
            Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color ?: LocalAccent.current))
        }
    }
}

@Composable
private fun CpuChart(history: List<Int>) {
    if (history.size < 2) {
        Text("計測中…", color = Wd.Text3, fontSize = 11.tu)
        return
    }
    val accent = LocalAccent.current
    Row(Modifier.fillMaxSize()) {
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val w = size.width
            val h = size.height
            fun yOf(v: Int) = h - (h - 2) * (v / 100f)
            CPU_MARKS.forEach { m -> drawLine(Color.White.copy(alpha = 0.10f), Offset(0f, yOf(m)), Offset(w, yOf(m)), 1f) }
            val step = w / (history.size - 1)
            val line = Path().apply {
                history.forEachIndexed { i, v -> if (i == 0) moveTo(0f, yOf(v)) else lineTo(i * step, yOf(v)) }
            }
            val area = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
            drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0f))))
            drawPath(line, accent, style = Stroke(2 * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        BoxWithConstraints(Modifier.width(30.dp).fillMaxHeight()) {
            CPU_MARKS.forEach { m ->
                Text(
                    "$m%",
                    color = Wd.Text3,
                    fontSize = 9.tu,
                    style = Tabular,
                    modifier = Modifier.padding(start = 4.dp).offset(y = maxHeight * ((100 - 98 * m / 100f) / 100f) - 5.dp),
                )
            }
        }
    }
}
