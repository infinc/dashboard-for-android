package app.dashboard.ui.dashboard

import app.dashboard.data.Aircraft
import app.dashboard.data.FlightRepository
import app.dashboard.data.ShipStream
import app.dashboard.i18n.L
import app.dashboard.ui.map.MapFrame
import app.dashboard.ui.map.MapMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/** 地図の表示から、見えている（と見込む）範囲の半径（dp）。カードの大きさは分からないので、取るタイルの範囲に合わせる。 */
internal fun viewRadiusUnits(wide: Boolean): Double = if (wide) 900.0 else 560.0

/**
 * 飛行機のカード。地図の中心のまわりの機体を [FlightRepository] から [INTERVAL_MS] ごとに取る。
 * 地図を大きく動かした・ズームを変えたときは、[MOVE_GAP_MS] 空けてすぐ取り直す。カードが見えている間だけ動く（[setActive]）。
 */
class FlightTracker(
    private val scope: CoroutineScope,
    private val repo: FlightRepository,
    private val frame: () -> MapFrame,
    private val wide: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        val aircraft: List<Aircraft> = emptyList(),
        val fetchedAt: Long = 0,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var lastAt = 0L
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastZoom = 0

    @Synchronized
    fun setActive(on: Boolean) {
        if (on == (job?.isActive == true)) return
        job?.cancel()
        job = if (on) scope.launch(dispatcher) { run() } else null
    }

    private suspend fun run() {
        while (scope.isActive) {
            val f = frame()
            if (f.ready && due(f)) fetch(f)
            delay(1_000)
        }
    }

    /** 取り直すときか。前の取得から [INTERVAL_MS] 経った、または地図を半径の 3 割より動かした・ズームを変えた。 */
    internal fun due(f: MapFrame): Boolean {
        // まだ 1 度も取っていない
        if (lastZoom == 0) return true
        val now = clock()
        if (now - lastAt >= (if (_state.value.error != null) RETRY_MS else INTERVAL_MS)) return true
        if (now - lastAt < MOVE_GAP_MS) return false
        if (f.zoom != lastZoom) return true
        val radiusM = viewRadiusUnits(wide()) * MapMath.metersPerUnit(f.centerLat, f.zoom)
        val movedM = distanceM(lastLat, lastLon, f.centerLat, f.centerLon)
        return movedM > radiusM * 0.3
    }

    private suspend fun fetch(f: MapFrame) {
        lastAt = clock()
        lastLat = f.centerLat
        lastLon = f.centerLon
        lastZoom = f.zoom
        val nm = radiusNm(f, wide())
        try {
            val list = repo.around(f.centerLat, f.centerLon, nm)
            _state.value = State(list, clock(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: L("取得できません", "Unavailable")) }
        }
    }

    companion object {
        const val INTERVAL_MS = 15_000L
        private const val RETRY_MS = 30_000L
        private const val MOVE_GAP_MS = 1_500L

        /** 地図の見えている範囲を覆う半径（海里。API の上限まで）。 */
        fun radiusNm(f: MapFrame, wide: Boolean): Int {
            val m = viewRadiusUnits(wide) * MapMath.metersPerUnit(f.centerLat, f.zoom)
            return (m / 1852).toInt().coerceIn(5, FlightRepository.MAX_RADIUS_NM)
        }

        /** 2 点のおおよその距離（メートル。正距円筒の近似で十分）。 */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            if (lat1.isNaN()) return Double.MAX_VALUE
            val dLat = Math.toRadians(lat2 - lat1)
            var dLon = abs(lon2 - lon1)
            if (dLon > 180) dLon = 360 - dLon
            val x = Math.toRadians(dLon) * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2))
            return hypot(x, dLat) * 6_371_000
        }

        /**
         * いまの位置の見込み。取得してから [elapsedSec] 秒のあいだ、同じ向き・速さで進んだとする（地図の上でなめらかに動かす）。
         * 地上の機体と、速さ・向きの分からない機体は動かさない。60 秒より先は見込まない。
         */
        fun extrapolate(a: Aircraft, elapsedSec: Double): Pair<Double, Double> {
            val track = a.track ?: return a.lat to a.lon
            val kt = a.speedKt ?: return a.lat to a.lon
            if (a.onGround || kt < 30) return a.lat to a.lon
            val t = (elapsedSec + a.seenSec).coerceIn(0.0, 60.0)
            val d = kt * 1852 / 3600 * t
            val r = Math.toRadians(track)
            val lat = a.lat + Math.toDegrees(d * kotlin.math.cos(r) / 6_371_000)
            val lon = a.lon + Math.toDegrees(d * kotlin.math.sin(r) / (6_371_000 * kotlin.math.cos(Math.toRadians(a.lat))))
            return lat to lon
        }
    }
}

/** 地図の表示から、船の購読に使う範囲（度）。 */
internal fun shipBox(f: MapFrame, wide: Boolean): ShipStream.Box {
    val rx = if (wide) 900.0 else 640.0
    val ry = if (wide) 560.0 else 400.0
    val north = MapMath.lat((f.centerY - ry).coerceAtLeast(0.0), f.zoom)
    val south = MapMath.lat((f.centerY + ry).coerceAtMost(MapMath.worldSize(f.zoom)), f.zoom)
    val west = MapMath.lon(f.centerX - rx, f.zoom)
    val east = MapMath.lon(f.centerX + rx, f.zoom)
    // 東西に何周ドラッグしていても、-180〜180 の中に戻す（範囲の幅は保つ）
    val shift = MapMath.wrapLon(west) - west
    return ShipStream.Box(south, west + shift, north, east + shift)
}
