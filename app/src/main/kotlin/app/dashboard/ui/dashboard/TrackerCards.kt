package app.dashboard.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.dashboard.data.Aircraft
import app.dashboard.data.Ship
import app.dashboard.data.ShipCategory
import app.dashboard.data.ShipStream
import app.dashboard.i18n.L
import app.dashboard.i18n.SEP
import app.dashboard.ui.common.EmptyText
import app.dashboard.ui.common.WdCard
import app.dashboard.ui.map.MapFrame
import app.dashboard.ui.map.MapMath
import app.dashboard.ui.theme.LocalAccent
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

// ---------------------------------------------------------------- 地図（飛行機・船舶で共通）

/** 地図の座標（dp）→ 画面の位置（px）。東西に何周ドラッグしていても、表示の中心にいちばん近い周に置く。 */
internal class Projector(private val frame: MapFrame, private val width: Float, private val height: Float, private val unit: Float) {
    fun at(lat: Double, lon: Double): Offset {
        val world = MapMath.worldSize(frame.zoom)
        var x = MapMath.x(lon, frame.zoom)
        x += Math.round((frame.centerX - x) / world) * world
        val y = MapMath.y(lat, frame.zoom)
        return Offset(width / 2 + ((x - frame.centerX) * unit).toFloat(), height / 2 + ((y - frame.centerY) * unit).toFloat())
    }

    fun visible(p: Offset, margin: Float) = p.x > -margin && p.y > -margin && p.x < width + margin && p.y < height + margin
}

/**
 * タイルの地図に印を重ねる。ドラッグで動かし（指と同じ向きに地図が動く）、タップでいちばん近い印を選ぶ（何も無い所なら選択を外す）。
 * [items] の位置は [positionOf]、描き方は [drawItem]（選んでいる印は最後に、強調して描く）。
 */
@Composable
internal fun <T> TrackerMap(
    frame: MapFrame,
    items: List<T>,
    keyOf: (T) -> Any,
    positionOf: (T) -> Pair<Double, Double>,
    labelOf: (T) -> String?,
    showLabels: Boolean,
    selected: Any?,
    onSelect: (Any?) -> Unit,
    onPan: (Float, Float) -> Unit,
    modifier: Modifier,
    drawItem: DrawScope.(item: T, at: Offset, selected: Boolean) -> Unit,
) {
    if (!frame.ready) return
    val accent = LocalAccent.current
    val density = LocalDensity.current.density
    val pan by rememberUpdatedState(onPan)
    val select by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(Triple(frame, items, positionOf))
    var size by remember { mutableStateOf(IntSize.Zero) }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Wd.Text, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    val labelBg = Wd.Surface.copy(alpha = 0.75f)
    Canvas(
        modifier.clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    pan(-drag.x / density, -drag.y / density)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val (f, list, pos) = current
                    val p = Projector(f, size.width.toFloat(), size.height.toFloat(), density)
                    val hit = list.map { it to p.at(pos(it).first, pos(it).second) }
                        .minByOrNull { (_, o) -> (o - tap).getDistance() }
                        ?.takeIf { (_, o) -> (o - tap).getDistance() < 26 * density }
                    select(hit?.let { keyOf(it.first) })
                }
            },
    ) {
        val unit = 1.dp.toPx()
        fun tiles(map: Map<Pair<Int, Int>, ImageBitmap>, z: Int) {
            if (map.isEmpty()) return
            val side = 256.0 * 2.0.pow(frame.zoom - z)
            map.forEach { (key, img) ->
                val l = (this.size.width / 2 + (key.first * side - frame.centerX) * unit).roundToInt()
                val t = (this.size.height / 2 + (key.second * side - frame.centerY) * unit).roundToInt()
                val r = (this.size.width / 2 + ((key.first + 1) * side - frame.centerX) * unit).roundToInt()
                val b = (this.size.height / 2 + ((key.second + 1) * side - frame.centerY) * unit).roundToInt()
                if (r < 0 || b < 0 || l > this.size.width || t > this.size.height) return@forEach
                drawImage(img, dstOffset = IntOffset(l, t), dstSize = IntSize(r - l, b - t), filterQuality = FilterQuality.Low)
            }
        }
        tiles(frame.old, frame.oldZoom)
        tiles(frame.base, frame.zoom)
        val p = Projector(frame, this.size.width, this.size.height, unit)
        // 「場所」の地点
        val home = Offset(
            this.size.width / 2 + ((frame.homeX - frame.centerX) * unit).toFloat(),
            this.size.height / 2 + ((frame.homeY - frame.centerY) * unit).toFloat(),
        )
        drawCircle(accent.copy(alpha = 0.25f), 6.dp.toPx(), home)
        drawCircle(accent, 2.5f.dp.toPx(), home)
        var picked: Pair<T, Offset>? = null
        items.forEach { item ->
            val (lat, lon) = positionOf(item)
            val at = p.at(lat, lon)
            if (!p.visible(at, 20 * unit)) return@forEach
            if (selected != null && keyOf(item) == selected) {
                picked = item to at
                return@forEach
            }
            drawItem(item, at, false)
            if (showLabels) labelOf(item)?.let { label(measurer, it, at, labelStyle, labelBg) }
        }
        picked?.let { (item, at) ->
            drawCircle(Color.White.copy(alpha = 0.9f), 14.dp.toPx(), at, style = Stroke(1.5f.dp.toPx()))
            drawItem(item, at, true)
            labelOf(item)?.let { label(measurer, it, at, labelStyle, labelBg) }
        }
    }
}

private fun DrawScope.label(measurer: androidx.compose.ui.text.TextMeasurer, text: String, at: Offset, style: TextStyle, bg: Color) {
    val layout = measurer.measure(text, style)
    val pad = 2.dp.toPx()
    val tl = Offset(at.x + 9.dp.toPx(), at.y - layout.size.height / 2f)
    drawRoundRect(bg, tl - Offset(pad, pad / 2), androidx.compose.ui.geometry.Size(layout.size.width + pad * 2, layout.size.height + pad), androidx.compose.ui.geometry.CornerRadius(pad * 2))
    drawText(layout, topLeft = tl)
}

/** 地図の右上の「＋」「−」「現在地に戻る」と、取得中・取れないときの案内。カードと全画面で共通。 */
@Composable
internal fun MapChrome(frame: MapFrame, onZoom: (Int) -> Unit, onRecenter: () -> Unit, big: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        if (frame.base.isEmpty() && frame.old.isEmpty()) {
            Box(Modifier.align(if (big) Alignment.Center else Alignment.TopStart).padding(8.dp)) {
                EmptyText(if (frame.failed) L("地図を取得できません", "Couldn't load the map") else L("取得中…", "Loading…"), if (frame.failed) Wd.Red else Wd.Text3)
            }
        }
        if (frame.ready) {
            ZoomButtons(
                frame.canZoomIn, frame.canZoomOut, frame.panned, onZoom, onRecenter,
                if (big) 52.dp else 30.dp,
                if (big) Modifier.align(Alignment.CenterEnd).padding(end = 20.dp) else Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }
    }
}

/** 選んだ印の詳しい情報（地図の左下）。 */
@Composable
internal fun InfoBox(title: String, lines: List<String>, big: Boolean, modifier: Modifier) {
    Column(
        modifier.widthIn(max = if (big) 360.dp else 230.dp)
            .clip(RoundedCornerShape(10.dp)).background(Wd.Surface.copy(alpha = 0.9f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(title, color = Wd.Text, fontSize = (if (big) 17 else 13).tu, fontWeight = FontWeight.SemiBold, maxLines = 1)
        lines.forEach { Text(it, color = Wd.Text2, fontSize = (if (big) 14 else 11).tu, lineHeight = 1.35.em, maxLines = 1) }
    }
}

// ---------------------------------------------------------------- 飛行機

/** 機首を上にした飛行機の形（中心が原点、幅 16dp ほど）。 */
private val PLANE: List<Offset> = listOf(
    0f to -8f, 1.2f to -6.2f, 1.2f to -2f, 8f to 2.2f, 8f to 3.6f, 1.2f to 1.6f, 1f to 5.4f, 3.2f to 7f, 3.2f to 8.2f,
    0f to 7.4f, -3.2f to 8.2f, -3.2f to 7f, -1f to 5.4f, -1.2f to 1.6f, -8f to 3.6f, -8f to 2.2f, -1.2f to -2f, -1.2f to -6.2f,
).map { Offset(it.first, it.second) }

/** 高度の色（地上は灰色、低い所の橙から高い所の紫へ）。Flightradar24 と同じ考え方。 */
internal fun altitudeColor(a: Aircraft): Color {
    if (a.onGround || a.altitudeFt == null) return Color(0xFF9AA5B1)
    val t = (a.altitudeFt / 40_000f).coerceIn(0f, 1f)
    return Color.hsv(28f + t * 272f, 0.8f, 1f)
}

private fun DrawScope.shape(points: List<Offset>, at: Offset, degrees: Float, k: Float, fill: Color, outline: Color) {
    val path = Path().apply {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }
    translate(at.x, at.y) {
        rotate(degrees, pivot = Offset.Zero) {
            scale(k, pivot = Offset.Zero) {
                drawPath(path, outline, style = Stroke(2.2f))
                drawPath(path, fill)
            }
        }
    }
}

internal fun aircraftLines(a: Aircraft): List<String> = buildList {
    listOfNotNull(a.type, a.registration).joinToString(L(" ・ ", " · ")).takeIf { it.isNotEmpty() }?.let(::add)
    add(
        if (a.onGround) L("地上", "On ground")
        else a.altitudeFt?.let { L("高度 %,d ft（%,d m）", "Alt %,d ft (%,d m)").format(Locale.US, it, (it * 0.3048).roundToInt()) }
            ?: L("高度 不明", "Alt unknown"),
    )
    a.speedKt?.let { add(L("速さ %d kt（%d km/h）", "Speed %d kt (%d km/h)").format(it.roundToInt(), (it * 1.852).roundToInt()) + (a.track?.let { t -> L(" ・ 向き ", " · heading ") + "${t.roundToInt()}°" } ?: "")) }
}

/**
 * 世界の飛行機の位置（ADS-B）。地図をドラッグで動かし、右上の「＋」「−」で拡大・縮小、その下で地点へ戻る（雨雲レーダーと同じ）。
 * 色は高度。機体を押すと便名・機種・高度・速さを出す。見出しの右のボタンで全画面（[FlightScreen]）。
 */
@Composable
fun FlightCard(
    frame: MapFrame,
    flights: FlightTracker.State,
    now: Long,
    onPan: (Float, Float) -> Unit,
    onZoom: (Int) -> Unit,
    onRecenter: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf<Any?>(null) }
    val note = when {
        flights.fetchedAt == 0L && flights.error != null -> flights.error
        flights.fetchedAt == 0L -> ""
        else -> L("${flights.aircraft.size} 機", "${flights.aircraft.size} aircraft") + L(" ・ ", " · ") + clockText(flights.fetchedAt)
    }
    WdCard(L("飛行機", "Flights"), modifier, note = note, titleAction = { ExpandButton(onExpand, Wd.Text3) }) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Wd.Bg.copy(alpha = 0.6f))) {
            FlightMap(frame, flights, now, selected, { selected = it }, onPan, false, Modifier.fillMaxSize())
            MapChrome(frame, onZoom, onRecenter, big = false)
            flights.aircraft.firstOrNull { it.hex == selected }?.let { a ->
                InfoBox(a.callsign ?: a.registration ?: a.hex.uppercase(), aircraftLines(a), false, Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
        }
    }
}

@Composable
internal fun FlightMap(
    frame: MapFrame,
    flights: FlightTracker.State,
    now: Long,
    selected: Any?,
    onSelect: (Any?) -> Unit,
    onPan: (Float, Float) -> Unit,
    big: Boolean,
    modifier: Modifier,
) {
    val elapsed = if (flights.fetchedAt > 0) (now - flights.fetchedAt) / 1000.0 else 0.0
    val outline = Wd.Bg
    TrackerMap(
        frame, flights.aircraft, { it.hex }, { FlightTracker.extrapolate(it, elapsed) }, { it.callsign },
        showLabels = frame.zoom >= 9 || (big && frame.zoom >= 8), selected, onSelect, onPan, modifier,
    ) { a, at, on ->
        val k = (if (big) 1.25f else 1f) * (if (on) 1.3f else 1f) * 1.dp.toPx()
        shape(PLANE, at, (a.track ?: 0.0).toFloat(), k, altitudeColor(a), outline)
    }
}

/** 高度の凡例（全画面）。 */
@Composable
internal fun AltitudeLegend(modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(Wd.Surface.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(0 to L("地上", "Gnd"), 2_000 to "2k", 10_000 to "10k", 20_000 to "20k", 30_000 to "30k", 40_000 to "40k ft").forEach { (ft, label) ->
            val c = altitudeColor(Aircraft("", null, null, null, 0.0, 0.0, ft, ft == 0, null, null))
            Box(Modifier.size(10.dp, 7.dp).background(c))
            Text(label, color = Wd.Text2, fontSize = 9.tu, modifier = Modifier.padding(start = 2.dp, end = 5.dp))
        }
    }
}

// ---------------------------------------------------------------- 船舶

/** 船首を上にした船の形。 */
private val HULL: List<Offset> = listOf(0f to -8f, 3.6f to -2.5f, 3.6f to 6.5f, -3.6f to 6.5f, -3.6f to -2.5f).map { Offset(it.first, it.second) }

internal fun ShipCategory.color(): Color = when (this) {
    ShipCategory.CARGO -> Color(0xFF7BC950)
    ShipCategory.TANKER -> Color(0xFFE5534B)
    ShipCategory.PASSENGER -> Color(0xFF4C8DF6)
    ShipCategory.FISHING -> Color(0xFFF2A07B)
    ShipCategory.TUG -> Color(0xFF3CC8C8)
    ShipCategory.HIGH_SPEED -> Color(0xFFF5D547)
    ShipCategory.PLEASURE -> Color(0xFFD86FE0)
    ShipCategory.SPECIAL -> Color(0xFF2FB5A0)
    ShipCategory.OTHER -> Color(0xFFA9B4C2)
}

internal fun shipLines(s: Ship, now: Long): List<String> = buildList {
    add(ShipStream.category(s.shipType).label + SEP + "MMSI ${s.mmsi}")
    val motion = listOfNotNull(
        s.sog?.let { L("速さ ", "Speed ") + "%.1f kn".format(Locale.US, it) },
        (s.heading ?: s.cog)?.let { L("向き ", "Heading ") + "${it.roundToInt()}°" },
    )
    if (motion.isNotEmpty()) add(motion.joinToString(L(" ・ ", " · ")))
    s.destination?.let { add(L("行き先 ", "To ") + it) }
    val ago = ((now - s.seenAt) / 60_000).toInt()
    add(if (ago <= 0) L("たった今の位置", "Position just now") else L("$ago 分前の位置", "Position $ago min ago"))
}

/**
 * 世界の船舶の位置（AIS）。地図の動かし方は飛行機・雨雲レーダーと同じ。色は船種（貨物船は緑、タンカーは赤…）。
 * 船を押すと名前・船種・速さ・行き先を出す。aisstream.io の API キーが要る（設定の「船舶」）。
 */
@Composable
fun ShipCard(
    frame: MapFrame,
    ships: List<Ship>,
    status: ShipStream.Status,
    now: Long,
    onPan: (Float, Float) -> Unit,
    onZoom: (Int) -> Unit,
    onRecenter: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf<Any?>(null) }
    val note = when (status) {
        ShipStream.Status.NoKey -> ""
        is ShipStream.Status.Error -> ""
        else -> L("${ships.size} 隻", "${ships.size} ships")
    }
    WdCard(L("船舶", "Ships"), modifier, note = note, titleAction = { ExpandButton(onExpand, Wd.Text3) }) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Wd.Bg.copy(alpha = 0.6f))) {
            ShipMap(frame, ships, selected, { selected = it }, onPan, false, Modifier.fillMaxSize())
            MapChrome(frame, onZoom, onRecenter, big = false)
            ShipStatusText(status, ships.isEmpty(), false, Modifier.align(Alignment.BottomStart).padding(6.dp))
            ships.firstOrNull { it.mmsi == selected }?.let { s ->
                InfoBox(s.name ?: "MMSI ${s.mmsi}", shipLines(s, now), false, Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
        }
    }
}

@Composable
internal fun ShipMap(frame: MapFrame, ships: List<Ship>, selected: Any?, onSelect: (Any?) -> Unit, onPan: (Float, Float) -> Unit, big: Boolean, modifier: Modifier) {
    val outline = Wd.Bg
    TrackerMap(
        frame, ships, { it.mmsi }, { it.lat to it.lon }, { it.name },
        showLabels = frame.zoom >= 12, selected, onSelect, onPan, modifier,
    ) { s, at, on ->
        val color = ShipStream.category(s.shipType).color()
        val k = (if (big) 1.2f else 1f) * (if (on) 1.3f else 1f) * 1.dp.toPx()
        val course = s.heading ?: s.cog
        if (course == null || (s.sog ?: 0.0) < 0.5 && s.heading == null) {
            // 止まっている船・向きの分からない船は丸
            drawCircle(outline, 4.6f * k, at)
            drawCircle(color, 3.6f * k, at)
        } else {
            shape(HULL, at, course.toFloat(), k, color, outline)
        }
    }
}

/** API キーが無い・つながらない・まだ届いていないときの案内。 */
@Composable
internal fun ShipStatusText(status: ShipStream.Status, empty: Boolean, big: Boolean, modifier: Modifier) {
    val (text, color) = when (status) {
        ShipStream.Status.NoKey -> L("設定の「船舶」で aisstream.io の API キーを入れてください", "Enter an aisstream.io API key in Settings → Ships") to Wd.Amber
        is ShipStream.Status.Error -> status.message to Wd.Red
        ShipStream.Status.Connecting -> L("接続中…", "Connecting…") to Wd.Text3
        else -> if (empty) L("受信待ち…（船は数秒〜数分おきに位置を送ります）", "Waiting for ships… (they report every few seconds to minutes)") to Wd.Text3 else return
    }
    Text(
        text, color = color, fontSize = (if (big) 15 else 12).tu, lineHeight = 1.4.em,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Wd.Surface.copy(alpha = 0.85f)).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

/** 船種の凡例（全画面）。 */
@Composable
internal fun ShipLegend(modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(Wd.Surface.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShipCategory.entries.forEach { c ->
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c.color()))
            Text(c.label, color = Wd.Text2, fontSize = 10.tu, modifier = Modifier.padding(start = 3.dp, end = 7.dp))
        }
    }
}

// ---------------------------------------------------------------- 全画面

/** 飛行機・船舶の地図を画面いっぱいに出す（雨雲レーダーの全画面と同じ枠）。開いている間は広い範囲のタイルを取る（[onWide]）。 */
@Composable
fun TrackerScreen(
    title: String,
    frame: MapFrame,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    onZoom: (Int) -> Unit,
    onRecenter: () -> Unit,
    onWide: (Boolean) -> Unit,
    onBack: () -> Unit,
    map: @Composable (Modifier) -> Unit,
    footer: @Composable () -> Unit,
    info: @Composable (Modifier) -> Unit,
) {
    DisposableEffect(Unit) {
        onWide(true)
        onDispose { onWide(false) }
    }
    FullscreenFrame(Wd.Bg, keepAwake, onKeepAwake, onBack, backTint = Wd.Text) { _, _ ->
        map(Modifier.fillMaxSize())
        MapChrome(frame, onZoom, onRecenter, big = true)
        Column(
            Modifier.align(Alignment.BottomStart).padding(16.dp)
                .clip(RoundedCornerShape(12.dp)).background(Wd.Surface.copy(alpha = 0.85f)).padding(12.dp),
        ) {
            Text(title, color = Wd.Text, fontSize = 16.tu, fontWeight = FontWeight.SemiBold)
            Box(Modifier.padding(top = 8.dp)) { footer() }
        }
        info(Modifier.align(Alignment.TopStart).padding(start = 80.dp, top = 18.dp))
    }
}

private fun clockText(ms: Long): String {
    val t = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
    return "%d:%02d:%02d".format(t.hour, t.minute, t.second)
}
