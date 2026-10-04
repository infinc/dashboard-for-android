package app.dashboard.data

import app.dashboard.i18n.L
import android.graphics.BitmapFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * 天気の地点で、もうすぐ雨が降り始めるか。
 *
 * 気象庁の降水ナウキャスト（雨雲レーダーと同じ jmatile。5 分ごと・60 分先まで）の、地点の画素に雨の色があるかを見る。
 * いまの観測（targetTimes_N1 の最新）と、予報（targetTimes_N2）を近い順に調べる。
 * 国外の地点などでナウキャストが読めないときは、時間別予報（Open-Meteo）の天気コードで代わりに判断する。
 */
class RainForecast(private val client: HttpClient) {

    sealed interface Result {
        /** いま地点で降っている。 */
        data object Raining : Result
        /** [minutes] 分後に降り始める予報。[source] は判断に使った情報。 */
        data class Starts(val minutes: Int, val source: String) : Result
        /** 調べた範囲では降らない。 */
        data object Dry : Result
        /** 判断できない（通信の失敗など）。 */
        data object Unknown : Result
    }

    suspend fun check(latitude: Double, longitude: Double, withinMinutes: Int, weather: WeatherState): Result =
        runCatching { nowcast(latitude, longitude, withinMinutes) }.getOrNull() ?: hourly(weather, withinMinutes)

    private suspend fun nowcast(latitude: Double, longitude: Double, withinMinutes: Int): Result? {
        val observed = times("targetTimes_N1.json").maxByOrNull { it.second } ?: return null
        val now = System.currentTimeMillis()
        if (rainAt(observed.first, observed.second, latitude, longitude) ?: return null) return Result.Raining
        val forecasts = times("targetTimes_N2.json").sortedBy { it.second }
        for ((base, valid) in forecasts) {
            val at = millis(valid)
            val minutes = ((at - now) / 60_000L).toInt()
            if (minutes > withinMinutes) break
            if (rainAt(base, valid, latitude, longitude) == true) return Result.Starts(minutes.coerceAtLeast(1), L("降水ナウキャスト", "precipitation nowcast"))
        }
        return Result.Dry
    }

    /** 一覧の（basetime, validtime）。 */
    private suspend fun times(file: String): List<Pair<String, String>> =
        Http.json.parseToJsonElement(client.get("$BASE/$file").bodyAsText()).jsonArray.mapNotNull {
            val o = it.jsonObject
            val b = o["basetime"]?.jsonPrimitive?.content
            val v = o["validtime"]?.jsonPrimitive?.content
            if (b != null && v != null) b to v else null
        }

    /** 地点の画素（と周りの 3 画素、ズーム 10 で 1 画素およそ 120 m）に雨の色があるか。タイルが読めなければ null。 */
    private suspend fun rainAt(base: String, valid: String, latitude: Double, longitude: Double): Boolean? {
        val n = 1 shl ZOOM
        val lat = Math.toRadians(latitude.coerceIn(-85.0, 85.0))
        val px = (longitude + 180) / 360 * n * 256
        val py = (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2 * n * 256
        val tx = floor(px / 256).toInt()
        val ty = floor(py / 256).toInt()
        val bytes = runCatching { client.get("$BASE/$base/none/$valid/surf/hrpns/$ZOOM/$tx/$ty.png").readRawBytes() }.getOrNull() ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val cx = (px - tx * 256).toInt()
        val cy = (py - ty * 256).toInt()
        try {
            for (dx in -3..3) for (dy in -3..3) {
                val x = (cx + dx).coerceIn(0, bmp.width - 1)
                val y = (cy + dy).coerceIn(0, bmp.height - 1)
                if (android.graphics.Color.alpha(bmp.getPixel(x, y)) > 40) return true
            }
            return false
        } finally {
            bmp.recycle()
        }
    }

    /** 時間別予報で、[withinMinutes] 分以内に始まる時間が雨（天気コード）で降水確率 50% 以上なら降り始めとみなす。 */
    private fun hourly(weather: WeatherState, withinMinutes: Int): Result {
        if (weather.hourly.isEmpty()) return Result.Unknown
        val zone = runCatching { ZoneId.of(weather.timezone ?: "") }.getOrDefault(ZoneId.systemDefault())
        val now = System.currentTimeMillis()
        if (isRain(weather.current.weatherCode)) return Result.Raining
        weather.hourly.forEach { h ->
            val at = runCatching { LocalDateTime.parse(h.time).atZone(zone).toInstant().toEpochMilli() }.getOrNull() ?: return@forEach
            val minutes = ((at - now) / 60_000L).toInt()
            if (minutes < 0) return@forEach
            if (minutes > withinMinutes) return Result.Dry
            if (isRain(h.weatherCode) && (h.precipitationProbability ?: 0) >= 50) return Result.Starts(minutes.coerceAtLeast(1), L("時間別予報", "hourly forecast"))
        }
        return Result.Dry
    }

    /** WMO の天気コードのうち、雨・雪・雷雨。 */
    private fun isRain(code: Int?) = code != null && (code in 51..67 || code in 71..77 || code in 80..86 || code in 95..99)

    private fun millis(stamp: String) = LocalDateTime.parse(stamp, STAMP).toInstant(ZoneOffset.UTC).toEpochMilli()

    private companion object {
        const val BASE = "https://www.jma.go.jp/bosai/jmatile/data/nowc"
        const val ZOOM = 10
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}
