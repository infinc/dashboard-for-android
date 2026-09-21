package app.walldash.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 緯度経度 → 気象庁の市町村区分（area.json の class20s）。
 *
 * 気象庁の警報ページにある「現在地」ボタンと同じ手順で決める:
 *
 * 1. `class20relm.json`（市町村ごとの外接矩形、約 140KB）で、点を含む候補に絞る
 * 2. 候補の境界 `geojson/class20s/{コード}.json`（1 件数 KB）で、点が内側にあるかを判定する
 * 3. どれの内側にもなければ（海岸線や湖の上の点など）、矩形の中心がいちばん近い市町村にする
 *
 * 3 は国外の地点でも日本のどこかを返してしまうので、[MAX_FALLBACK_KM] より遠ければ
 * 見つからなかったことにする。
 */
class JmaAreaLocator(private val client: HttpClient) {

    data class Area(val code: String, val name: String)

    /** 一覧はプロセスが生きている間は変わらないので一度だけ取る。 */
    @Volatile
    private var relm: Map<String, RelmDto>? = null

    suspend fun locate(latitude: Double, longitude: Double): Area? {
        val all = relm ?: client.get("$BASE/common/const/class20relm.json")
            .body<Map<String, RelmDto>>().also { relm = it }

        for ((code, r) in all) {
            if (!r.contains(latitude, longitude)) continue
            val geo: GeoJsonDto = client.get("$BASE/common/const/geojson/class20s/$code.json").body()
            val rings = mutableListOf<List<DoubleArray>>()
            geo.features.firstOrNull()?.geometry?.coordinates?.let { collectRings(it, rings) }
            if (insideEvenOdd(rings, longitude, latitude)) return Area(code, r.name)
        }

        val nearest = all.minByOrNull { (_, r) -> distanceKm(latitude, longitude, r.centerLat, r.centerLon) }
            ?: return null
        val km = distanceKm(latitude, longitude, nearest.value.centerLat, nearest.value.centerLon)
        return if (km <= MAX_FALLBACK_KM) Area(nearest.key, nearest.value.name) else null
    }

    /**
     * 気象庁の境界は Polygon で届くが、MultiPolygon でも同じに扱えるよう、
     * 「点（数値の配列）の配列」になっている階層をすべて輪として集める。
     */
    private fun collectRings(e: JsonElement, out: MutableList<List<DoubleArray>>) {
        val arr = e as? JsonArray ?: return
        val first = arr.firstOrNull() as? JsonArray ?: return
        if (first.firstOrNull() is JsonPrimitive) {
            out += arr.mapNotNull { p ->
                val xy = p as? JsonArray ?: return@mapNotNull null
                val x = (xy.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val y = (xy.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                doubleArrayOf(x, y)
            }
        } else {
            arr.forEach { collectRings(it, out) }
        }
    }

    /**
     * 全部の輪をまとめて数える偶奇判定。飛び地（輪が複数）と穴（内側の輪）を
     * 区別せずに正しく扱える。気象庁のページの判定と同じ方式。
     */
    private fun insideEvenOdd(rings: List<List<DoubleArray>>, x: Double, y: Double): Boolean {
        var crossings = 0
        for (ring in rings) {
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[(i + 1) % ring.size]
                if ((a[1] <= y && b[1] > y) || (a[1] > y && b[1] <= y)) {
                    val cx = a[0] + (y - a[1]) * (b[0] - a[0]) / (b[1] - a[1])
                    if (x < cx) crossings++
                }
            }
        }
        return crossings % 2 == 1
    }

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = abs(lat1 - lat2)
        val dLon = abs(lon1 - lon2) * cos((lat1 + lat2) * 0.5 * PI / 180)
        return 40_000.0 / 360 * sqrt(dLat * dLat + dLon * dLon)
    }

    @Serializable
    private data class RelmDto(
        val name: String = "",
        /** [緯度, 経度] */
        val ne: List<Double> = emptyList(),
        val sw: List<Double> = emptyList(),
    ) {
        val centerLat get() = (ne.getOrElse(0) { 0.0 } + sw.getOrElse(0) { 0.0 }) / 2
        val centerLon get() = (ne.getOrElse(1) { 0.0 } + sw.getOrElse(1) { 0.0 }) / 2

        /** 境界上の点を取りこぼさないよう、矩形を少しだけ広げて見る。 */
        fun contains(lat: Double, lon: Double): Boolean {
            if (ne.size < 2 || sw.size < 2) return false
            return lat in (sw[0] - MARGIN)..(ne[0] + MARGIN) && lon in (sw[1] - MARGIN)..(ne[1] + MARGIN)
        }
    }

    @Serializable
    private data class GeoJsonDto(val features: List<FeatureDto> = emptyList())

    @Serializable
    private data class FeatureDto(val geometry: GeometryDto? = null)

    @Serializable
    private data class GeometryDto(val coordinates: JsonElement? = null)

    private companion object {
        const val BASE = "https://www.jma.go.jp/bosai"
        /** 約 200m。矩形の辺ちょうどにある点を候補から落とさないための余白。 */
        const val MARGIN = 0.002
        /** 境界のどれにも入らないとき、これより遠い市町村は採らない（国外の地点など）。 */
        const val MAX_FALLBACK_KM = 30.0
    }
}
