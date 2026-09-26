package app.walldash.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.walldash.data.Astro
import app.walldash.data.WeatherState
import app.walldash.ui.common.EmptyText
import app.walldash.ui.common.Tabular
import app.walldash.ui.common.WdCard
import app.walldash.ui.theme.LocalAccent
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.tu
import app.walldash.ui.theme.vhText
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------- 雨雲レーダー

/** 気象庁の降水強度の色（凡例用。タイルの色と同じ）。 */
private val RAIN_SCALE = listOf(
    "1" to Color(0xFFF2F2FF), "5" to Color(0xFFA0D2FF), "10" to Color(0xFF218CFF), "20" to Color(0xFF0041FF),
    "30" to Color(0xFFFAF500), "50" to Color(0xFFFF9900), "80" to Color(0xFFFF2800), "" to Color(0xFFB40068),
)

/** 地理院タイル（淡色地図）は白地なので、ダークのテーマでは反転して暗い地図にする（強震モニタと同じ変換）。 */
private val MAP_DARK_FILTER = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.85f, 0f, 0f, 0f, 225f,
            0f, -0.85f, 0f, 0f, 228f,
            0f, 0f, -0.85f, 0f, 235f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

@Composable
fun RadarCard(frame: DashboardViewModel.RadarFrame, place: String, modifier: Modifier) {
    WdCard("雨雲レーダー", modifier, note = listOf(place, frame.label).filter { it.isNotEmpty() }.joinToString(" ・ ")) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Wd.Bg.copy(alpha = 0.6f))) {
            if (frame.base.isEmpty()) {
                Box(Modifier.padding(8.dp)) { EmptyText(if (frame.failed) "地図を取得できません" else "取得中…", if (frame.failed) Wd.Red else Wd.Text3) }
            } else {
                val light = Wd.palette.light
                val accent = LocalAccent.current
                Canvas(Modifier.fillMaxSize().clipToBounds()) {
                    val unit = 1.dp.toPx()
                    val tilePx = (256 * unit).roundToInt()
                    fun origin(tx: Int, ty: Int) = IntOffset(
                        (size.width / 2 + (tx * 256 - frame.centerX) * unit).roundToInt(),
                        (size.height / 2 + (ty * 256 - frame.centerY) * unit).roundToInt(),
                    )
                    frame.base.forEach { (key, img) ->
                        drawImage(img, dstOffset = origin(key.first, key.second), dstSize = IntSize(tilePx, tilePx), colorFilter = if (light) null else MAP_DARK_FILTER)
                    }
                    frame.rain.forEach { (key, img) ->
                        drawImage(img, dstOffset = origin(key.first, key.second), dstSize = IntSize(tilePx, tilePx), alpha = 0.85f)
                    }
                    // 天気の地点
                    val c = Offset(size.width / 2, size.height / 2)
                    drawCircle(accent.copy(alpha = 0.3f), 7.dp.toPx(), c)
                    drawCircle(accent, 3.dp.toPx(), c)
                    drawCircle(Color.White, 3.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                }
                Legend(Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
        }
    }
}

@Composable
private fun Legend(modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(Wd.Surface.copy(alpha = 0.8f)).padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RAIN_SCALE.forEach { (label, color) ->
            Box(Modifier.size(9.dp, 7.dp).background(color))
            if (label.isNotEmpty()) Text(label, color = Wd.Text2, fontSize = 8.5f.tu, modifier = Modifier.padding(horizontal = 2.dp))
        }
        Text(" mm/h", color = Wd.Text3, fontSize = 8.5f.tu)
    }
}

// ---------------------------------------------------------------- 日の出・日の入りと月

@Composable
fun SunMoonCard(w: WeatherState?, now: Long, modifier: Modifier) {
    val moon = remember(now / 60_000L) { Astro.moon(now) }
    WdCard("日の出・日の入り ／ 月", modifier) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Sun(w, now, Modifier.weight(1.15f).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(Wd.BorderSoft))
            Moon(moon, now, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun Sun(w: WeatherState?, now: Long, modifier: Modifier) {
    val zone = w?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
    val day = w?.daily?.firstOrNull { it.date.take(10) == today } ?: w?.daily?.firstOrNull()
    fun ms(s: String?) = s?.let { runCatching { LocalDateTime.parse(it.take(16)).atZone(zone).toInstant().toEpochMilli() }.getOrNull() }
    val rise = ms(day?.sunrise)
    val set = ms(day?.sunset)
    Column(modifier) {
        if (rise == null || set == null) {
            EmptyText("日の出・日の入りは天気の取得後に出ます。")
            return@Column
        }
        val t = ((now - rise).toDouble() / (set - rise)).toFloat()
        val amber = Wd.Amber
        val ink = Wd.Ink
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val r = minOf(size.width / 2 * 0.86f, size.height * 0.82f)
            val c = Offset(size.width / 2, size.height * 0.92f)
            drawLine(ink.copy(alpha = 0.18f), Offset(c.x - r * 1.12f, c.y), Offset(c.x + r * 1.12f, c.y), 1f)
            drawArc(ink.copy(alpha = 0.22f), 180f, 180f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(1.2f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))))
            if (t in 0f..1f) {
                drawArc(amber.copy(alpha = 0.6f), 180f, 180f * t, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(2f * density, cap = StrokeCap.Round))
                val a = PI * (1 - t)
                val p = Offset(c.x + (r * cos(a)).toFloat(), c.y - (r * sin(a)).toFloat())
                drawCircle(amber.copy(alpha = 0.25f), 11.dp.toPx(), p)
                drawCircle(amber, 6.dp.toPx(), p)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            TimeLabel("日の出", rise, zone, Modifier.weight(1f))
            TimeLabel("日の入り", set, zone, Modifier.weight(1f), end = true)
        }
        val len = Duration.ofMillis(set - rise)
        Text("昼の長さ ${len.toHours()}時間${len.toMinutes() % 60}分", color = Wd.Text3, fontSize = 11.tu, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun TimeLabel(label: String, ms: Long, zone: ZoneId, modifier: Modifier, end: Boolean = false) {
    val t = Instant.ofEpochMilli(ms).atZone(zone)
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(label, color = Wd.Text3, fontSize = 11.tu)
        Text("%d:%02d".format(t.hour, t.minute), fontSize = vhText(2.6f, 16f, 22f), fontWeight = FontWeight.SemiBold, style = Tabular)
    }
}

@Composable
private fun Moon(m: Astro.MoonPhase, now: Long, modifier: Modifier) {
    val zone = ZoneId.systemDefault()
    fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).let { "${it.monthValue}/${it.dayOfMonth} %02d:%02d".format(it.hour, it.minute) }
    val dark = if (Wd.palette.light) Color(0xFF2A3342) else Color(0xFF1E2530)
    val lit = Color(0xFFF3EFD9)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.fillMaxHeight(0.78f).aspectRatio(1f)) { moonDisc(m.phase, dark, lit) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, fontSize = vhText(2.3f, 15f, 19f), fontWeight = FontWeight.SemiBold, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                Text("月齢 ", color = Wd.Text3, fontSize = 11.tu, modifier = Modifier.padding(bottom = 2.dp))
                Text("%.1f".format(Locale.US, m.age), fontSize = vhText(2.6f, 16f, 22f), fontWeight = FontWeight.SemiBold, style = Tabular)
            }
            Text("輝いている面 ${(m.illumination * 100).roundToInt()}%", color = Wd.Text2, fontSize = 11.5f.tu)
            Spacer(Modifier.height(4.dp))
            Text("満月 ${date(m.nextFull)}", color = Wd.Text3, fontSize = 11.tu, style = Tabular, maxLines = 1)
            Text("新月 ${date(m.nextNew)}", color = Wd.Text3, fontSize = 11.tu, style = Tabular, maxLines = 1)
        }
    }
}

/**
 * 月の円盤。[phase] 0 = 新月、0.25 = 上弦、0.5 = 満月、0.75 = 下弦。
 * 北半球で見た向き（満ちていくときは右から光る）。明暗の境目は楕円の半分で描く。
 */
private fun DrawScope.moonDisc(phase: Double, dark: Color, lit: Color) {
    val r = size.minDimension / 2 * 0.94f
    val c = Offset(size.width / 2, size.height / 2)
    drawCircle(lit.copy(alpha = 0.10f), r * 1.08f, c)
    drawCircle(dark, r, c)
    val rx = (r * abs(cos(2 * PI * phase))).toFloat()
    val circle = Rect(c, r)
    val ellipse = Rect(c.x - rx, c.y - r, c.x + rx, c.y + r)
    val path = Path().apply {
        moveTo(c.x, c.y - r)
        val waxing = phase < 0.5
        // 光っている側の縁（半円）
        arcTo(circle, -90f, if (waxing) 180f else -180f, false)
        // 境目: 三日月なら光る側へ、半月より太ければ反対側へふくらむ
        val crescent = phase < 0.25 || phase > 0.75
        val towardLitSide = if (waxing) -180f else 180f
        arcTo(ellipse, 90f, if (crescent) towardLitSide else -towardLitSide, false)
        close()
    }
    drawPath(path, lit)
    drawCircle(Color.Black.copy(alpha = 0.18f), r, c, style = Stroke(1f))
}
