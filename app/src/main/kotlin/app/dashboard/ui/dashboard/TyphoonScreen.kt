package app.dashboard.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import app.dashboard.data.LatLon
import app.dashboard.data.TyphoonInfo
import app.dashboard.data.TyphoonPoint
import app.dashboard.data.TyphoonTrack
import app.dashboard.ui.common.WdIcons
import app.dashboard.ui.theme.LocalAccent
import app.dashboard.ui.theme.tu
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * 台風の進路図を画面いっぱいに出す（防災カードの台風の名前を押して開く）。
 * 左に地図（暗い灰色の地図に、これまでの経路・強風域・暴風域・予報円・暴風警戒域・各予報の時刻）、
 * 右に時刻ごとの位置・気圧・風速などの一覧。左上の「<」か端末の戻る操作で閉じる。
 * 気象庁が発表を更新したら（[TyphoonInfo.reportedAt] が変わったら）取り直す。
 */
@Composable
fun TyphoonScreen(
    info: TyphoonInfo,
    home: LatLon?,
    loadTrack: suspend (String) -> Result<TyphoonTrack>,
    loadTile: suspend (Int, Int, Int) -> ImageBitmap?,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val result by produceState<Result<TyphoonTrack>?>(null, info.id, info.reportedAt) {
        value = info.id?.let { loadTrack(it) } ?: Result.failure(IllegalStateException("台風の識別子がありません"))
    }
    val track = result?.getOrNull()

    Box(
        Modifier.fillMaxSize().background(BG)
            // 下のダッシュボードに触れさせない
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (track != null) {
                    TrackMap(track, home, loadTile, Modifier.fillMaxSize())
                    Legend(Modifier.align(Alignment.BottomStart).padding(14.dp))
                    Text(
                        "気象庁 ／ 地図: Esri, HERE, Garmin, © OpenStreetMap",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 10.tu,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    )
                } else {
                    Text(
                        result?.exceptionOrNull()?.let { "進路図を取得できません: ${it.message ?: "通信エラー"}" } ?: "進路図を取得しています…",
                        color = if (result?.isFailure == true) RED else Color.White.copy(alpha = 0.7f),
                        fontSize = 15.tu,
                        modifier = Modifier.align(Alignment.Center).padding(40.dp),
                    )
                }
            }
            Panel(info, track, Modifier.width(360.dp).fillMaxHeight())
        }

        // 戻る: 「<」だけ（押せる範囲は指の大きさにする）。地図の上でも見えるよう、うすい丸を敷く
        Box(
            Modifier.align(Alignment.TopStart).padding(14.dp).size(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WdIcons.Back, "戻る", tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

private val BG = Color(0xFF0B0E13)
private val RED = Color(0xFFFF4B4B)
private val YELLOW = Color(0xFFFFD84D)
private val ENVELOPE = Color(0xFFFF6B6B)

// ------------------------------------------------------------ 地図

/** 緯度・経度 → ズーム 0 の世界座標（地球 1 周 = 256）。 */
private fun world(p: LatLon): Offset {
    val lat = Math.toRadians(p.lat.coerceIn(-85.0, 85.0))
    val x = (p.lon + 180) / 360 * 256
    val y = (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2 * 256
    return Offset(x.toFloat(), y.toFloat())
}

/** 半径 [km] が、緯度 [lat] のところでズーム 0 の世界座標のいくつぶんか。 */
private fun kmToWorld(km: Double, lat: Double): Float = (km * 1000 / (156_543.034 * cos(Math.toRadians(lat)))).toFloat()

/** 地図の表示範囲（ズーム 0 の座標）と、1 単位あたりの画素数。 */
private data class View(val left: Float, val top: Float, val scale: Float, val zoom: Int)

@Composable
private fun TrackMap(track: TyphoonTrack, home: LatLon?, loadTile: suspend (Int, Int, Int) -> ImageBitmap?, modifier: Modifier) {
    val accent = LocalAccent.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val view = remember(track, home, w, h) { fit(track, home, w, h, density) }
        val tiles = remember(view.zoom) { mutableStateMapOf<Pair<Int, Int>, ImageBitmap>() }
        val n = 1 shl view.zoom
        val tileSide = 256f / n
        val keys = remember(view) {
            val x0 = floor(view.left / tileSide).toInt()
            val x1 = floor((view.left + w / view.scale) / tileSide).toInt()
            val y0 = floor(view.top / tileSide).toInt().coerceAtLeast(0)
            val y1 = floor((view.top + h / view.scale) / tileSide).toInt().coerceAtMost(n - 1)
            (y0..y1).flatMap { y -> (x0..x1).map { x -> x to y } }
        }
        LaunchedEffect(keys) {
            keys.filter { it !in tiles }.forEach { k -> loadTile(view.zoom, k.first, k.second)?.let { tiles[k] = it } }
        }
        Canvas(Modifier.fillMaxSize()) {
            fun at(p: LatLon): Offset = (world(p) - Offset(view.left, view.top)) * view.scale
            fun radius(c: LatLon, km: Double) = kmToWorld(km, c.lat) * view.scale

            // 地図のタイル（端は丸めて隙間を作らない）
            tiles.forEach { (k, img) ->
                val l = ((k.first * tileSide - view.left) * view.scale).roundToInt()
                val t = ((k.second * tileSide - view.top) * view.scale).roundToInt()
                val r = (((k.first + 1) * tileSide - view.left) * view.scale).roundToInt()
                val b = (((k.second + 1) * tileSide - view.top) * view.scale).roundToInt()
                drawImage(img, dstOffset = IntOffset(l, t), dstSize = IntSize(r - l, b - t), filterQuality = FilterQuality.Low)
            }
            val unit = 1.dp.toPx()
            val analysis = track.points.firstOrNull { it.hours == 0 }
            val forecasts = track.points.filter { it.hours > 0 }

            // 暴風警戒域: 各予報の暴風警戒域の円と、実況の暴風域の円を、隣どうしの接線でつないだ形
            val stormCircles = buildList {
                track.storm?.let { add(at(it.center) to radius(it.center, it.radiusKm)) }
                forecasts.filter { it.stormKm != null }.forEach { add(at(it.center) to radius(it.center, it.stormKm!!)) }
            }
            if (forecasts.any { it.stormKm != null }) {
                val envelope = envelope(stormCircles)
                drawPath(envelope, ENVELOPE.copy(alpha = 0.10f))
                drawPath(envelope, ENVELOPE, style = Stroke(2 * unit, join = StrokeJoin.Round))
            }

            // 経路（台風になる前は点線）
            polyline(track.preTrack.map(::at), Color.White.copy(alpha = 0.45f), 1.6f * unit, PathEffect.dashPathEffect(floatArrayOf(2 * unit, 4 * unit)))
            polyline(track.track.map(::at), Color.White.copy(alpha = 0.85f), 2.2f * unit, null)

            // 実況の強風域・暴風域
            track.gale?.let { drawCircle(YELLOW.copy(alpha = 0.22f), radius(it.center, it.radiusKm), at(it.center)) }
            track.gale?.let { drawCircle(YELLOW.copy(alpha = 0.8f), radius(it.center, it.radiusKm), at(it.center), style = Stroke(1.4f * unit)) }
            track.storm?.let { drawCircle(RED.copy(alpha = 0.5f), radius(it.center, it.radiusKm), at(it.center)) }

            // 予報円（白の点線）と、中心を結ぶ点線
            val dash = PathEffect.dashPathEffect(floatArrayOf(5 * unit, 4 * unit))
            analysis?.let { a -> polyline((listOf(a) + forecasts).map { at(it.center) }, Color.White.copy(alpha = 0.7f), 1.3f * unit, dash) }
            forecasts.forEach { p ->
                p.circleKm?.let { drawCircle(Color.White.copy(alpha = 0.9f), radius(p.center, it), at(p.center), style = Stroke(1.6f * unit, pathEffect = dash)) }
                drawCircle(Color.White, 2.6f * unit, at(p.center))
            }

            // 天気の地点
            home?.let {
                val c = at(it)
                drawCircle(accent.copy(alpha = 0.3f), 8 * unit, c)
                drawCircle(accent, 3.5f * unit, c)
                drawCircle(Color.White, 3.5f * unit, c, style = Stroke(1.2f * unit))
            }

            // いまの中心（台風の記号）
            analysis?.let { mark(at(it.center), unit) }

            // 各予報の時刻（予報円の右上に）
            forecasts.forEach { p ->
                val label = shortTime(p.validTime) ?: "${p.hours}時間後"
                val layout = measurer.measure(label, TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                val c = at(p.center)
                val r = (p.circleKm?.let { radius(p.center, it) } ?: 0f) * 0.7071f
                val topLeft = Offset(c.x + r + 3 * unit, c.y - r - layout.size.height - 1 * unit)
                drawRoundRect(
                    Color.Black.copy(alpha = 0.55f),
                    topLeft - Offset(4 * unit, 1.5f * unit),
                    Size(layout.size.width + 8 * unit, layout.size.height + 3 * unit),
                    androidx.compose.ui.geometry.CornerRadius(4 * unit),
                )
                drawText(layout, topLeft = topLeft)
            }
        }
    }
}

/**
 * 経路・円・天気の地点がすべて入る範囲に合わせる。ズームはタイルを取るための整数に丸め、はみ出す分は引き伸ばす。
 * 左上の「<」と下の凡例のぶん、上下左右に少し余白を取る。
 */
private fun fit(track: TyphoonTrack, home: LatLon?, w: Float, h: Float, density: Float): View {
    val pts = mutableListOf<Offset>()
    fun addCircle(c: LatLon, km: Double) {
        val p = world(c)
        val r = kmToWorld(km, c.lat)
        pts += Offset(p.x - r, p.y - r)
        pts += Offset(p.x + r, p.y + r)
    }
    (track.track + track.preTrack).forEach { pts += world(it) }
    track.points.forEach { p ->
        pts += world(p.center)
        p.circleKm?.let { addCircle(p.center, it) }
        p.stormKm?.let { addCircle(p.center, it) }
    }
    track.gale?.let { addCircle(it.center, it.radiusKm) }
    home?.let { pts += world(it) }
    val minX = pts.minOf { it.x }
    val maxX = pts.maxOf { it.x }
    val minY = pts.minOf { it.y }
    val maxY = pts.maxOf { it.y }
    val pad = 64 * density
    val bw = (maxX - minX).coerceAtLeast(0.5f)
    val bh = (maxY - minY).coerceAtLeast(0.5f)
    val scale = minOf((w - pad * 2) / bw, (h - pad * 2) / bh).coerceIn(8f, 256f * 256)
    // タイルは 1 枚 256 画素なので、引き伸ばしが 1〜2 倍になるズームを選ぶ（端末の画素の密度も入れる）
    val zoom = floor(log2(scale / density)).toInt().coerceIn(2, 10)
    val cx = (minX + maxX) / 2
    val cy = (minY + maxY) / 2
    return View(cx - w / 2 / scale, cy - h / 2 / scale, scale, zoom)
}

private fun DrawScope.polyline(points: List<Offset>, color: Color, width: Float, effect: PathEffect?) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
}

/** 台風の中心の記号（赤い丸に白の ×）。 */
private fun DrawScope.mark(c: Offset, unit: Float) {
    drawCircle(Color.White, 8 * unit, c)
    drawCircle(RED, 6.5f * unit, c)
    val d = 3.2f * unit
    drawLine(Color.White, c + Offset(-d, -d), c + Offset(d, d), 1.8f * unit, StrokeCap.Round)
    drawLine(Color.White, c + Offset(-d, d), c + Offset(d, -d), 1.8f * unit, StrokeCap.Round)
}

/** 円を多角形にする。 */
private fun polygon(c: Offset, r: Float, steps: Int = 72): List<Offset> =
    (0 until steps).map { i -> val a = 2 * PI * i / steps; Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()) }

/** 点の凸包（Andrew の方法）。 */
private fun hull(points: List<Offset>): List<Offset> {
    val p = points.sortedWith(compareBy({ it.x }, { it.y }))
    if (p.size < 3) return p
    fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = mutableListOf<Offset>()
    p.forEach { q -> while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), q) <= 0) lower.removeAt(lower.lastIndex); lower += q }
    val upper = mutableListOf<Offset>()
    p.asReversed().forEach { q -> while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), q) <= 0) upper.removeAt(upper.lastIndex); upper += q }
    return lower.dropLast(1) + upper.dropLast(1)
}

/** 円の並びを、隣どうしの凸包（2 つの円に接線を引いた形）でつないで 1 つの形にする。 */
private fun envelope(circles: List<Pair<Offset, Float>>): Path {
    fun shape(points: List<Offset>) = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    val parts = if (circles.size == 1) listOf(polygon(circles[0].first, circles[0].second))
    else circles.zipWithNext { a, b -> hull(polygon(a.first, a.second) + polygon(b.first, b.second)) }
    var result = Path()
    parts.forEach { part ->
        val next = Path()
        next.op(result, shape(part), PathOperation.Union)
        result = next
    }
    return result
}

@Composable
private fun Legend(modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        LegendRow("暴風域（25 m/s 以上）") { Box(Modifier.size(14.dp).clip(CircleShape).background(RED.copy(alpha = 0.7f))) }
        LegendRow("強風域（15 m/s 以上）") { Box(Modifier.size(14.dp).clip(CircleShape).background(YELLOW.copy(alpha = 0.45f)).border(1.dp, YELLOW, CircleShape)) }
        LegendRow("予報円（70% の確率で中心が入る）") { Box(Modifier.size(14.dp).border(1.5.dp, Color.White, CircleShape)) }
        LegendRow("暴風警戒域") { Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).border(2.dp, ENVELOPE, RoundedCornerShape(3.dp))) }
    }
}

@Composable
private fun LegendRow(label: String, swatch: @Composable () -> Unit) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        swatch()
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 11.tu)
    }
}

// ------------------------------------------------------------ 右の一覧

@Composable
private fun Panel(info: TyphoonInfo, track: TyphoonTrack?, modifier: Modifier) {
    val kind = listOfNotNull(info.scale, info.intensity).joinToString("・")
    Column(
        modifier.background(Color(0xFF12161D)).padding(start = 20.dp, end = 18.dp, top = 22.dp, bottom = 12.dp),
    ) {
        Text(
            listOfNotNull(track?.number ?: info.number, track?.name ?: info.name).joinToString(" ").ifEmpty { "台風" },
            color = Color.White, fontSize = 24.tu, fontWeight = FontWeight.Bold,
        )
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (kind.isNotEmpty()) {
                Text(
                    kind, color = Color(0xFF1B1F27), fontSize = 12.tu, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFFFFB347)).padding(horizontal = 7.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text("発表 " + (reported(track?.reportedAt ?: info.reportedAt) ?: "—"), color = Color.White.copy(alpha = 0.55f), fontSize = 12.tu)
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            track?.points?.forEach { PointRows(it) }
        }
    }
}

@Composable
private fun PointRows(p: TyphoonPoint) {
    val white2 = Color.White.copy(alpha = 0.72f)
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
        Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (p.hours == 0) "実況" else "${p.hours}時間後",
                color = if (p.hours == 0) RED else Color.White,
                fontSize = 13.tu, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(longTime(p.validTime).orEmpty(), color = white2, fontSize = 13.tu)
            Spacer(Modifier.weight(1f))
            listOfNotNull(p.scale, p.intensity).joinToString("・").takeIf { it.isNotEmpty() }?.let {
                Text(it, color = Color(0xFFFFB347), fontSize = 12.tu)
            }
        }
        p.location?.let { Text(it, color = Color.White, fontSize = 14.tu, lineHeight = 1.4.em, modifier = Modifier.padding(top = 2.dp)) }
        val wind = listOfNotNull(
            p.pressureHpa?.let { "$it hPa" },
            p.maxWindMps?.let { "最大 $it m/s" + (p.gustMps?.let { g -> "（瞬間 $g）" } ?: "") },
        ).joinToString(" ・ ")
        if (wind.isNotEmpty()) Text(wind, color = white2, fontSize = 12.5f.tu, lineHeight = 1.4.em)
        p.course?.let { Text(it + "へ" + (p.speedKmh?.let { s -> " $s km/h" } ?: ""), color = white2, fontSize = 12.5f.tu) }
        val areas = if (p.hours == 0) listOfNotNull(
            p.stormKm?.let { "暴風域 ${it.toInt()} km" },
            p.galeText?.let { "強風域 $it" },
        ) else listOfNotNull(
            p.circleKm?.let { "予報円 ${it.roundToInt()} km" },
            p.stormKm?.let { "暴風警戒域 ${it.toInt()} km" },
        )
        if (areas.isNotEmpty()) Text(areas.joinToString(" ・ "), color = Color.White.copy(alpha = 0.55f), fontSize = 12.tu, lineHeight = 1.4.em)
    }
}

private fun zoned(s: String?) = s?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull() }

/** 地図に書く時刻（"29日 3時"）。 */
private fun shortTime(s: String?) = zoned(s)?.let { "${it.dayOfMonth}日 ${it.hour}時" }

/** 一覧に書く時刻（"9/29 3:00"）。 */
private fun longTime(s: String?) = zoned(s)?.let { "${it.monthValue}/${it.dayOfMonth} ${it.hour}:%02d".format(it.minute) }

private fun reported(s: String?) = longTime(s)
