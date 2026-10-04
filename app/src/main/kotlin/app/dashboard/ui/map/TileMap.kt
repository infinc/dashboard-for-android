package app.dashboard.ui.map

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Web メルカトルの座標の計算。ズーム z のタイル 1 枚 = 256 とした「世界の座標」と緯度経度を行き来する。
 * 画面に依らない計算だけを置く（単体テストする）。
 */
object MapMath {
    const val TILE = 256

    fun worldSize(zoom: Int): Double = (1 shl zoom) * TILE.toDouble()

    fun x(lon: Double, zoom: Int): Double = (lon + 180) / 360 * worldSize(zoom)

    fun y(lat: Double, zoom: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * worldSize(zoom)
    }

    fun lon(x: Double, zoom: Int): Double = x / worldSize(zoom) * 360 - 180

    fun lat(y: Double, zoom: Int): Double {
        val n = PI - 2 * PI * y / worldSize(zoom)
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }

    /** 経度を -180〜180 に戻す（東西に何周ドラッグしても）。 */
    fun wrapLon(lon: Double): Double = ((lon + 180) % 360 + 360) % 360 - 180

    /** 地図の 1 単位（dp）が何メートルか。 */
    fun metersPerUnit(lat: Double, zoom: Int): Double = 156_543.034 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)
}

/**
 * 地図 1 枚ぶんの表示。座標はズーム [zoom] のタイル 1 枚 = 256 とした値（1 = 1dp で描く）。
 * [centerX] / [centerY] は表示の中心（ドラッグで動く）、[homeX] / [homeY] は「場所」の地点。
 * [old] は「＋」「−」を押す前のズーム [oldZoom] の地図。新しい地図が揃うまで引き伸ばして下に敷く。
 */
data class MapFrame(
    val zoom: Int = 0,
    val centerX: Double = 0.0,
    val centerY: Double = 0.0,
    val homeX: Double = 0.0,
    val homeY: Double = 0.0,
    val dark: Boolean = true,
    val base: Map<Pair<Int, Int>, ImageBitmap> = emptyMap(),
    val oldZoom: Int = 0,
    val old: Map<Pair<Int, Int>, ImageBitmap> = emptyMap(),
    val failed: Boolean = false,
    val minZoom: Int = 0,
    val maxZoom: Int = 0,
) {
    val ready: Boolean get() = zoom != 0
    val panned: Boolean get() = abs(centerX - homeX) > 1 || abs(centerY - homeY) > 1
    val canZoomIn: Boolean get() = ready && zoom < maxZoom
    val canZoomOut: Boolean get() = ready && zoom > minZoom
    val centerLat: Double get() = MapMath.lat(centerY, zoom)
    val centerLon: Double get() = MapMath.wrapLon(MapMath.lon(centerX, zoom))
}

/**
 * ドラッグ・「＋」「−」・「現在地に戻る」で動かせるタイルの地図（飛行機・船舶のカード）。雨雲レーダーと同じ動き:
 * 足りないタイルを中心に近い順に 1 枚ずつ取り、取っている途中に動かされても、その時点で要るタイルから取る。離れたタイルは捨てる。
 * ドラッグで動かしたまま [RETURN_MS] 触られなければ地点へ戻す。ズームは [zoomKey] で覚える（次に開いたときも同じ）。
 *
 * タイルの取得は [load] に任せる（null = サーバーが無いと答えた、例外 = 通信の失敗）。画面に依らないので単体テストできる。
 */
class TileMapController(
    private val scope: CoroutineScope,
    private val load: suspend (String) -> ImageBitmap?,
    private val url: (dark: Boolean, zoom: Int, x: Int, y: Int) -> String,
    private val savedZoom: () -> Int,
    private val saveZoom: (Int) -> Unit,
    val minZoom: Int,
    val maxZoom: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _frame = MutableStateFlow(MapFrame(minZoom = minZoom, maxZoom = maxZoom))
    val frame: StateFlow<MapFrame> = _frame.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Mutex()
    private val missing = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var panAt = 0L
    @Volatile private var wide = false
    @Volatile private var running = false

    /** 地図を出している間だけ動かす（[stop] で止める）。 */
    fun start() {
        if (running) return
        running = true
        scope.launch(Dispatchers.IO) {
            while (running) {
                try {
                    returnHomeIfIdle()
                    loadTiles()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                withTimeoutOrNull(CHECK_MS) {
                    wake.receive()
                    delay(150)
                }
            }
        }
    }

    fun stop() {
        running = false
        wake.trySend(Unit)
    }

    /** 地点とテーマ。変わったら地点から取り直す（ズームはそのまま）。 */
    fun setHome(lat: Double, lon: Double, dark: Boolean) {
        val f = _frame.value
        val zoom = if (f.ready) f.zoom else savedZoom().coerceIn(minZoom, maxZoom)
        val hx = MapMath.x(lon, zoom)
        val hy = MapMath.y(lat, zoom)
        if (f.ready && abs(f.homeX - hx) < 0.5 && abs(f.homeY - hy) < 0.5 && f.dark == dark) return
        _frame.value = MapFrame(zoom, hx, hy, hx, hy, dark, minZoom = minZoom, maxZoom = maxZoom)
        missing.clear()
        wake.trySend(Unit)
    }

    /** 全画面の間は画面の端まで埋まるよう広く取る。 */
    fun setWide(on: Boolean) {
        wide = on
        wake.trySend(Unit)
    }

    /** ドラッグ。[dx] / [dy] は表示の中心を動かす量（dp）。東西はつながっていて、どこまでも動かせる。 */
    fun pan(dx: Float, dy: Float) {
        _frame.update {
            if (!it.ready) return@update it
            it.copy(centerX = it.centerX + dx, centerY = (it.centerY + dy).coerceIn(0.0, MapMath.worldSize(it.zoom)))
        }
        panAt = clock()
        wake.trySend(Unit)
    }

    /** 「＋」（1）・「−」（-1）。表示の中心はそのまま。 */
    fun zoom(step: Int) {
        var changed = false
        _frame.update {
            if (!it.ready) return@update it
            val z = (it.zoom + step).coerceIn(minZoom, maxZoom)
            if (z == it.zoom) return@update it
            changed = true
            val k = 2.0.pow(z - it.zoom)
            val useBase = it.old.isEmpty() || keys(it).all { key -> key in it.base }
            it.copy(
                zoom = z,
                centerX = it.centerX * k, centerY = it.centerY * k,
                homeX = it.homeX * k, homeY = it.homeY * k,
                base = emptyMap(),
                oldZoom = if (useBase) it.zoom else it.oldZoom,
                old = if (useBase) it.base else it.old,
                failed = false,
            )
        }
        if (!changed) return
        saveZoom(_frame.value.zoom)
        panAt = clock()
        wake.trySend(Unit)
    }

    /** 「現在地に戻る」（ズームはそのまま）。 */
    fun recenter() {
        _frame.update { if (!it.ready) it else it.copy(centerX = it.homeX, centerY = it.homeY) }
        wake.trySend(Unit)
    }

    private fun returnHomeIfIdle() {
        val f = _frame.value
        if (f.panned && clock() - panAt > RETURN_MS) _frame.update { it.copy(centerX = it.homeX, centerY = it.homeY) }
    }

    /** 見えている範囲で足りないタイルを 1 枚ずつ取る。通信の失敗は、この回ではもう取りに行かない。 */
    internal suspend fun loadTiles() = lock.withLock {
        val failed = HashSet<String>()
        while (running) {
            val f = _frame.value
            if (!f.ready) return@withLock
            val need = keys(f).map { it to url(f.dark, f.zoom, Math.floorMod(it.first, 1 shl f.zoom), it.second) }
                .firstOrNull { (k, u) -> k !in f.base && u !in missing && u !in failed } ?: break
            val (k, u) = need
            val image = try {
                load(u)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += u
                continue
            }
            if (image == null) {
                missing += u
                continue
            }
            _frame.update { if (it.zoom == f.zoom && it.dark == f.dark) it.copy(base = it.base + (k to image), failed = false) else it }
        }
        _frame.update { cur ->
            if (!cur.ready) return@update cur
            val tx = floor(cur.centerX / MapMath.TILE).toInt()
            val ty = floor(cur.centerY / MapMath.TILE).toInt()
            val done = keys(cur).all { it in cur.base || url(cur.dark, cur.zoom, Math.floorMod(it.first, 1 shl cur.zoom), it.second) in missing }
            cur.copy(
                base = cur.base.filterKeys { abs(it.first - tx) <= 5 && abs(it.second - ty) <= 4 },
                old = if (done) emptyMap() else cur.old,
                oldZoom = if (done) 0 else cur.oldZoom,
                failed = cur.base.isEmpty() && cur.old.isEmpty(),
            )
        }
    }

    /** 表示に要るタイル（中心から外へ向かう順）。東西は折り返すので x は範囲の外でもよい。 */
    internal fun keys(f: MapFrame): List<Pair<Int, Int>> {
        val n = 1 shl f.zoom
        val tx = floor(f.centerX / MapMath.TILE).toInt()
        val ty = floor(f.centerY / MapMath.TILE).toInt()
        val (wx, wy) = if (wide) 3 to 2 else 2 to 1
        return (-wx..wx).flatMap { dx -> (-wy..wy).map { dy -> Pair(tx + dx, ty + dy) } }
            .filter { it.second in 0 until n }
            .sortedBy { abs(it.first - tx) + abs(it.second - ty) }
    }

    companion object {
        private const val CHECK_MS = 60_000L
        const val RETURN_MS = 180_000L

        /** Esri の灰色の地図（雨雲レーダーと同じ）。URL は {z}/{y}/{x} の順。 */
        fun esriGray(dark: Boolean, zoom: Int, x: Int, y: Int) =
            "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_${if (dark) "Dark" else "Light"}_Gray_Base/MapServer/tile/$zoom/$y/$x"
    }
}
