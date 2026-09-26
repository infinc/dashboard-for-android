package app.walldash.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import app.walldash.ui.common.WdCard
import app.walldash.ui.theme.LocalAccent
import app.walldash.ui.theme.Wd
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 細かい目盛りのアナログ時計。
 * 外周に 1/5 秒刻みの細い目盛り、分の目盛り、5 分ごとの太い目盛りと数字、3 時の位置に日付窓、
 * 影つきの時針・分針と、カウンターウェイト付きの秒針。
 * [sweep] なら秒針をなめらかに動かす（約 30 コマ/秒）。切ると 1 秒ごとに刻む（描き直しが 1 秒に 1 回で済む）。
 */
@Composable
fun AnalogClockCard(now: Long, sweep: Boolean, numerals: Boolean, modifier: Modifier) {
    var smooth by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (sweep) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameMillis {
                    val t = System.currentTimeMillis()
                    if (t - last >= 33) {
                        smooth = t
                        last = t
                    }
                }
            }
        }
    }
    val t = if (sweep) smooth else now
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(now).atZone(zone)
    val measurer = rememberTextMeasurer()
    val accent = LocalAccent.current
    WdCard("アナログ時計", modifier, note = "${date.monthValue}/${date.dayOfMonth}（${wday(date)}）") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().aspectRatio(1f, matchHeightConstraintsFirst = true)) {
                dial(measurer, numerals, date.dayOfMonth, accent)
                hands(t, zone, accent)
            }
        }
    }
}

private fun DrawScope.dial(measurer: TextMeasurer, numerals: Boolean, day: Int, accent: Color) {
    val r = size.minDimension / 2
    val c = center
    val ink = Wd.Ink
    val light = Wd.palette.light

    // 文字盤: 中心がわずかに明るい面と、二重の縁
    drawCircle(
        Brush.radialGradient(
            listOf(if (light) Color.White else Color(0xFF1B222D), if (light) Color(0xFFE6EBF1) else Color(0xFF0C1016)),
            center = c + Offset(-r * 0.2f, -r * 0.25f),
            radius = r * 1.2f,
        ),
        r * 0.985f,
        c,
    )
    drawCircle(ink.copy(alpha = 0.28f), r * 0.985f, c, style = Stroke(r * 0.018f))
    drawCircle(ink.copy(alpha = 0.10f), r * 0.93f, c, style = Stroke(r * 0.006f))

    // 1/5 秒刻み（300 本）・分（60 本）・5 分（12 本）の目盛り
    for (i in 0 until 300) {
        if (i % 5 == 0) continue
        tick(c, r, i / 300f, r * 0.945f, r * 0.965f, ink.copy(alpha = 0.22f), r * 0.004f)
    }
    for (i in 0 until 60) {
        if (i % 5 == 0) continue
        tick(c, r, i / 60f, r * 0.895f, r * 0.965f, ink.copy(alpha = 0.55f), r * 0.009f)
    }
    for (i in 0 until 12) {
        val main = i % 3 == 0
        val color = if (i == 0) accent else ink.copy(alpha = 0.9f)
        tick(c, r, i / 12f, if (main) r * 0.80f else r * 0.84f, r * 0.965f, color, if (main) r * 0.030f else r * 0.020f)
    }

    // 数字（12・3・6・9 は少し大きく）。3 時は日付窓にするので出さない
    if (numerals) {
        for (h in 1..12) {
            if (h == 3) continue
            val a = h / 12.0 * 2 * PI
            val big = h % 3 == 0
            val style = TextStyle(
                color = ink.copy(alpha = if (big) 0.92f else 0.62f),
                fontSize = (r * (if (big) 0.15f else 0.11f) / density / fontScale).sp,
                fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal,
            )
            val layout = measurer.measure(h.toString(), style)
            val pr = r * 0.66f
            val p = Offset(c.x + (pr * sin(a)).toFloat(), c.y - (pr * cos(a)).toFloat())
            drawText(layout, topLeft = p - Offset(layout.size.width / 2f, layout.size.height / 2f))
        }
    }

    // 3 時の日付窓
    val winW = r * 0.22f
    val winH = r * 0.15f
    val winCenter = Offset(c.x + r * 0.62f, c.y)
    drawRoundRect(
        if (light) Color.White else Color(0xFF080B10),
        topLeft = winCenter - Offset(winW / 2, winH / 2),
        size = Size(winW, winH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.02f),
    )
    drawRoundRect(
        ink.copy(alpha = 0.35f),
        topLeft = winCenter - Offset(winW / 2, winH / 2),
        size = Size(winW, winH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.02f),
        style = Stroke(r * 0.008f),
    )
    val dayLayout = measurer.measure(
        day.toString(),
        TextStyle(color = if (light) Color(0xFF111821) else Color(0xFFE8EEF5), fontSize = (r * 0.10f / density / fontScale).sp, fontWeight = FontWeight.SemiBold),
    )
    drawText(dayLayout, topLeft = winCenter - Offset(dayLayout.size.width / 2f, dayLayout.size.height / 2f))

    // 銘
    val brand = measurer.measure(
        "WALLDASH",
        TextStyle(color = ink.copy(alpha = 0.45f), fontSize = (r * 0.055f / density / fontScale).sp, letterSpacing = (r * 0.012f / density / fontScale).sp),
    )
    drawText(brand, topLeft = Offset(c.x - brand.size.width / 2f, c.y - r * 0.38f))
}

private fun DrawScope.tick(c: Offset, r: Float, turn: Float, from: Float, to: Float, color: Color, width: Float) {
    val a = turn * 2 * PI
    val dx = sin(a).toFloat()
    val dy = -cos(a).toFloat()
    drawLine(color, Offset(c.x + dx * from, c.y + dy * from), Offset(c.x + dx * to, c.y + dy * to), width, StrokeCap.Butt)
}

private fun DrawScope.hands(t: Long, zone: ZoneId, accent: Color) {
    val r = size.minDimension / 2
    val c = center
    val z = Instant.ofEpochMilli(t).atZone(zone)
    val sec = z.second + z.nano / 1e9f
    val min = z.minute + sec / 60f
    val hour = z.hour % 12 + min / 60f
    val light = Wd.palette.light
    val metal = if (light) Color(0xFF1C2430) else Color(0xFFDCE3EC)
    val lume = if (light) Color(0xFFF4F7FA) else Color(0xFF9FB3C8)
    val shadow = Color.Black.copy(alpha = if (light) 0.18f else 0.45f)
    val offset = Offset(r * 0.012f, r * 0.02f)

    fun hand(angle: Float, length: Float, tail: Float, width: Float, withLume: Boolean) {
        val path = Path().apply {
            moveTo(c.x - width * 0.5f, c.y + tail)
            lineTo(c.x - width * 0.62f, c.y - length * 0.18f)
            lineTo(c.x - width * 0.34f, c.y - length * 0.94f)
            lineTo(c.x, c.y - length)
            lineTo(c.x + width * 0.34f, c.y - length * 0.94f)
            lineTo(c.x + width * 0.62f, c.y - length * 0.18f)
            lineTo(c.x + width * 0.5f, c.y + tail)
            close()
        }
        rotate(angle, c) {
            translate(offset.x, offset.y) { drawPath(path, shadow) }
            drawPath(path, metal)
            if (withLume) {
                drawLine(lume, Offset(c.x, c.y - length * 0.28f), Offset(c.x, c.y - length * 0.86f), width * 0.34f, StrokeCap.Round)
            }
        }
    }

    hand(hour * 30f, r * 0.50f, r * 0.10f, r * 0.075f, true)
    hand(min * 6f, r * 0.76f, r * 0.12f, r * 0.055f, true)

    // 秒針: 細い針・尾の丸いおもり・先端近くの輪
    rotate(sec * 6f, c) {
        translate(offset.x, offset.y) {
            drawLine(shadow, Offset(c.x, c.y + r * 0.22f), Offset(c.x, c.y - r * 0.88f), r * 0.012f, StrokeCap.Round)
        }
        drawLine(accent, Offset(c.x, c.y + r * 0.22f), Offset(c.x, c.y - r * 0.88f), r * 0.012f, StrokeCap.Round)
        drawCircle(accent, r * 0.045f, Offset(c.x, c.y + r * 0.18f))
        drawCircle(accent, r * 0.035f, Offset(c.x, c.y - r * 0.70f), style = Stroke(r * 0.010f))
    }
    drawCircle(metal, r * 0.05f, c)
    drawCircle(accent, r * 0.028f, c)
    drawCircle(if (light) Color.White else Color(0xFF0C1016), r * 0.010f, c)
}
