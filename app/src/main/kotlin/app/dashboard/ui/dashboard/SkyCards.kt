package app.dashboard.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.dashboard.data.Astro
import app.dashboard.data.WeatherState
import app.dashboard.ui.common.EmptyText
import app.dashboard.ui.common.Tabular
import app.dashboard.ui.common.WdCard
import app.dashboard.ui.common.WdIcons
import app.dashboard.ui.theme.LocalAccent
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import app.dashboard.ui.theme.vhText
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------- 雨雲レーダー

/** 気象庁の降水強度の色（凡例用。タイルの色と同じ）。 */
private val RAIN_SCALE = listOf(
    "1" to Color(0xFFF2F2FF), "5" to Color(0xFFA0D2FF), "10" to Color(0xFF218CFF), "20" to Color(0xFF0041FF),
    "30" to Color(0xFFFAF500), "50" to Color(0xFFFF9900), "80" to Color(0xFFFF2800), "" to Color(0xFFB40068),
)

/**
 * 地図（Esri の灰色の地図。テーマに合わせて明暗を選ぶ）に雨雲を重ねる。ドラッグで別の場所へ動かせる
 * （しばらく触らなければ地点へ戻る）。右上の「＋」「−」で拡大・縮小、その下のボタンでズームはそのままで地点へ戻る。
 * 見出しの右のボタンで画面いっぱいに出す（[RadarScreen]）。
 */
@Composable
fun RadarCard(
    frame: DashboardViewModel.RadarFrame,
    place: String,
    onPan: (Float, Float) -> Unit,
    onZoom: (Int) -> Unit,
    onRecenter: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier,
) {
    val note = listOf(if (frame.panned) "" else place, frame.label).filter { it.isNotEmpty() }.joinToString(" ・ ")
    WdCard("雨雲レーダー", modifier, note = note, titleAction = { ExpandButton(onExpand, Wd.Text3) }) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Wd.Bg.copy(alpha = 0.6f))) {
            RadarMap(frame, onPan, Modifier.fillMaxSize())
            if (frame.base.isEmpty() && frame.old.isEmpty()) {
                Box(Modifier.padding(8.dp)) { EmptyText(if (frame.failed) "地図を取得できません" else "取得中…", if (frame.failed) Wd.Red else Wd.Text3) }
            } else {
                RainLegend(Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
            if (frame.zoom != 0) ZoomButtons(frame, onZoom, onRecenter, 30.dp, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }
}

/** 地図と雨雲と天気の地点。ドラッグで動かす（指と同じ向きに地図が動く）。カードと全画面の両方で使う。 */
@Composable
internal fun RadarMap(frame: DashboardViewModel.RadarFrame, onPan: (Float, Float) -> Unit, modifier: Modifier) {
    if (frame.zoom == 0) return
    val empty = frame.base.isEmpty() && frame.old.isEmpty()
    val accent = LocalAccent.current
    val density = LocalDensity.current.density
    val pan by rememberUpdatedState(onPan)
    // 地図が一時的に空になってもドラッグを受け続けるよう、Canvas は出したままにする
    Canvas(
        modifier.clipToBounds().pointerInput(Unit) {
            detectDragGestures { change, drag ->
                change.consume()
                // 指と同じ向きに地図が動くよう、表示の中心は逆へ動かす
                pan(-drag.x / density, -drag.y / density)
            }
        },
    ) {
        val unit = 1.dp.toPx()
        // ズーム [z] のタイルを、いまのズームの座標に引き伸ばして（縮めて）描く。端は丸めて隙間を作らない
        fun tiles(map: Map<Pair<Int, Int>, ImageBitmap>, z: Int, alpha: Float) {
            if (map.isEmpty()) return
            val side = 256f * 2f.pow(frame.zoom - z)
            map.forEach { (key, img) ->
                val l = (size.width / 2 + (key.first * side - frame.centerX) * unit).roundToInt()
                val t = (size.height / 2 + (key.second * side - frame.centerY) * unit).roundToInt()
                val r = (size.width / 2 + ((key.first + 1) * side - frame.centerX) * unit).roundToInt()
                val b = (size.height / 2 + ((key.second + 1) * side - frame.centerY) * unit).roundToInt()
                if (r < 0 || b < 0 || l > size.width || t > size.height) return@forEach
                drawImage(img, dstOffset = IntOffset(l, t), dstSize = IntSize(r - l, b - t), alpha = alpha, filterQuality = FilterQuality.Low)
            }
        }
        tiles(frame.old, frame.oldZoom, 1f)
        tiles(frame.base, frame.zoom, 1f)
        // 新しいズームの雨雲が揃うまでは前の雨雲を敷く
        tiles(frame.oldRain, frame.oldRainZoom, 0.85f)
        tiles(frame.rain, frame.rainZoom, 0.85f)
        if (empty) return@Canvas
        // 天気の地点
        val c = Offset(
            size.width / 2 + (frame.homeX - frame.centerX) * unit,
            size.height / 2 + (frame.homeY - frame.centerY) * unit,
        )
        drawCircle(accent.copy(alpha = 0.3f), 7.dp.toPx(), c)
        drawCircle(accent, 3.dp.toPx(), c)
        drawCircle(Color.White, 3.dp.toPx(), c, style = Stroke(1.dp.toPx()))
    }
}

/**
 * 地図アプリと同じく、上に「＋」、下に「−」、その下に「現在地に戻る」（ズームはそのまま）。
 * 端まで来たほうは薄くして押せなくする。地点から動かしていなければ「現在地に戻る」も薄くする。
 */
@Composable
internal fun ZoomButtons(frame: DashboardViewModel.RadarFrame, onZoom: (Int) -> Unit, onRecenter: () -> Unit, side: Dp, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(side / 5)).background(Wd.Surface.copy(alpha = 0.85f))) {
        ZoomButton("拡大", frame.canZoomIn, side, { onZoom(1) }) { c -> Text("＋", color = c, fontSize = (side.value * 0.53f).tu, lineHeight = (side.value * 0.53f).tu) }
        Box(Modifier.width(side).height(1.dp).background(Wd.BorderSoft))
        ZoomButton("縮小", frame.canZoomOut, side, { onZoom(-1) }) { c -> Text("−", color = c, fontSize = (side.value * 0.53f).tu, lineHeight = (side.value * 0.53f).tu) }
        Box(Modifier.width(side).height(1.dp).background(Wd.BorderSoft))
        ZoomButton("現在地に戻る", frame.panned, side, onRecenter) { c -> Icon(WdIcons.Locate, null, tint = c, modifier = Modifier.size(side * 0.52f)) }
    }
}

@Composable
private fun ZoomButton(description: String, enabled: Boolean, side: Dp, onClick: () -> Unit, content: @Composable (Color) -> Unit) {
    Box(
        Modifier.size(side).clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(if (enabled) Wd.Text else Wd.Text3.copy(alpha = 0.5f))
    }
}

@Composable
internal fun RainLegend(modifier: Modifier) {
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
    // 円盤を高さだけで決めると、幅の狭いときに右の文字の列が潰れて縦書きのように折り返す。文字の列に要る幅を先に残す
    BoxWithConstraints(modifier) {
        val disc = minOf(maxHeight * 0.78f, maxWidth - 10.dp - MOON_TEXT_WIDTH).coerceAtLeast(36.dp)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(disc)) { moonDisc(m.phase, dark, lit) }
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
}

/** 月の右の文字の列（「満月 10/26 13:13」が 1 行に収まる幅）。 */
private val MOON_TEXT_WIDTH = 104.dp

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
