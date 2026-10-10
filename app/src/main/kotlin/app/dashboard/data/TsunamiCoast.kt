package app.dashboard.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 地点から近い津波予報区。
 *
 * 気象庁の津波情報は沿岸ごと（津波予報区。3 桁のコード）に出る。県に出ていても、海から離れた内陸の市町村には関係ない
 * （実際に、遠い地震の「津波予報（若干の海面変動）」が太平洋側の沿岸に出たとき、内陸の地点の防災カードにも出ていた）。
 * そこで、気象庁の地図が使う予報区ごとの海岸線（`common/const/geojson/tsunami.json`、MultiLineString）から、
 * 地点から [NEAR_KM] 以内に海岸線のある予報区だけを「この地点の予報区」とする。海岸線は粗く間引かれているので少し余裕を持たせる。
 */
object TsunamiCoast {

    /** 海岸線からこの距離以内なら、その予報区の津波情報を出す。 */
    const val NEAR_KM = 10.0

    /** 予報区のコード → 海岸線（経度・緯度の点の列の集まり）。 */
    fun parse(geojson: JsonElement): Map<String, List<List<DoubleArray>>> {
        val features = ((geojson as? JsonObject)?.get("features") as? JsonArray) ?: return emptyMap()
        val out = HashMap<String, MutableList<List<DoubleArray>>>()
        for (f in features) {
            val o = f as? JsonObject ?: continue
            val code = ((o["properties"] as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull ?: continue
            val coords = (o["geometry"] as? JsonObject)?.get("coordinates") ?: continue
            collectLines(coords, out.getOrPut(code) { mutableListOf() })
        }
        return out
    }

    /** 地点から [km] 以内に海岸線のある予報区のコード。内陸なら空。 */
    fun nearby(coasts: Map<String, List<List<DoubleArray>>>, lat: Double, lon: Double, km: Double = NEAR_KM): Set<String> =
        coasts.filterValues { lines -> lines.any { distanceKm(it, lat, lon) <= km } }.keys

    /** 線（点の列）の階層を、MultiLineString でも LineString でも同じに集める。 */
    private fun collectLines(e: JsonElement, out: MutableList<List<DoubleArray>>) {
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
            arr.forEach { collectLines(it, out) }
        }
    }

    /** 地点から線までのいちばん近い距離（km）。地点の緯度で経度を縮めた平面で測る（数十 km なら十分）。 */
    private fun distanceKm(line: List<DoubleArray>, lat: Double, lon: Double): Double {
        if (line.isEmpty()) return Double.MAX_VALUE
        val kx = cos(lat * PI / 180) * KM_PER_DEG
        fun px(p: DoubleArray) = (p[0] - lon) * kx
        fun py(p: DoubleArray) = (p[1] - lat) * KM_PER_DEG
        if (line.size == 1) return sqrt(px(line[0]).let { it * it } + py(line[0]).let { it * it })
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val ax = px(line[i]); val ay = py(line[i])
            val bx = px(line[i + 1]); val by = py(line[i + 1])
            val dx = bx - ax; val dy = by - ay
            val len = dx * dx + dy * dy
            val t = if (len == 0.0) 0.0 else ((-ax * dx - ay * dy) / len).coerceIn(0.0, 1.0)
            val cx = ax + t * dx; val cy = ay + t * dy
            best = minOf(best, sqrt(cx * cx + cy * cy))
        }
        return best
    }

    private const val KM_PER_DEG = 40_000.0 / 360
}
