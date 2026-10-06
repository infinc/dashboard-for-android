package app.dashboard.data

import app.dashboard.i18n.L
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * 飛行機の位置（ADS-B）。adsb.lol の公開 API（登録不要、ODbL）で、地点のまわり [MAX_RADIUS_NM] 海里までの機体を取る。
 *
 * Flightradar24 の API は有料で、OpenSky Network は登録なしだと 1 日 400 回までしか呼べないため、上限の無い adsb.lol を使う。
 * どの範囲を取るかは地図の表示（ドラッグ・ズーム）で決まるので、取りに行くのは画面の側（[app.dashboard.ui.dashboard.FlightTracker]）。
 */
class FlightRepository(private val client: HttpClient) {

    /** [lat] / [lon] のまわり [radiusNm] 海里の機体。 */
    suspend fun around(lat: Double, lon: Double, radiusNm: Int): List<Aircraft> {
        val r = radiusNm.coerceIn(1, MAX_RADIUS_NM)
        val url = String.format(Locale.US, "$BASE/point/%.4f/%.4f/%d", lat, lon, r)
        val body = try {
            client.get(url) { header("User-Agent", USER_AGENT) }.bodyAsText()
        } catch (e: ResponseException) {
            throw IllegalStateException(
                if (e.response.status.value == 429) L("混み合っています。少し待ってから取り直します", "Too many requests. Retrying shortly")
                else L("取得できません（${e.response.status.value}）", "Unavailable (${e.response.status.value})"),
                e,
            )
        }
        return parse(body)
    }

    companion object {
        private const val BASE = "https://api.adsb.lol/v2"
        private const val USER_AGENT = "Dashboard/0.1 (Android; wall dashboard)"
        /** adsb.lol の point の上限（海里）。 */
        const val MAX_RADIUS_NM = 250

        /** adsb.lol（readsb の aircraft.json と同じ形）の応答を読む。位置の無い機体は捨てる。 */
        fun parse(body: String): List<Aircraft> {
            val root = Http.json.parseToJsonElement(body).jsonObject
            return root["ac"]?.jsonArray.orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val lat = o.double("lat") ?: return@mapNotNull null
                val lon = o.double("lon") ?: return@mapNotNull null
                val alt = o["alt_baro"]?.jsonPrimitive
                val ground = alt?.contentOrNull == "ground"
                Aircraft(
                    hex = o.string("hex") ?: return@mapNotNull null,
                    callsign = o.string("flight")?.trim()?.takeIf { it.isNotEmpty() },
                    registration = o.string("r")?.trim()?.takeIf { it.isNotEmpty() },
                    type = o.string("t")?.trim()?.takeIf { it.isNotEmpty() },
                    lat = lat,
                    lon = lon,
                    altitudeFt = if (ground) 0 else alt?.intOrNull ?: alt?.doubleOrNull?.toInt(),
                    onGround = ground,
                    speedKt = o.double("gs"),
                    track = o.double("track") ?: o.double("true_heading") ?: o.double("calc_track"),
                    seenSec = o.double("seen_pos") ?: o.double("seen") ?: 0.0,
                )
            }
        }

        private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        private fun JsonObject.double(key: String) = this[key]?.jsonPrimitive?.doubleOrNull
    }
}
