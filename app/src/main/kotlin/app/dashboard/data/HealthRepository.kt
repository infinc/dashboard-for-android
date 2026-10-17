package app.dashboard.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 空気・体調のカード（暑さ指数・紫外線・PM2.5）。天気と同じ Open-Meteo から、カードを出している間だけ 30 分ごとに取る。
 * 暑さ指数は Open-Meteo に無いので、気温・湿度・日射・風から [Health.wbgt] で推定する。
 * 気温・風は表示の単位に関係なく ℃・m/s で取る（暑さ指数の式と段階が ℃ のため）。
 */
class HealthRepository(private val client: HttpClient, private val configStore: ConfigStore) {

    @Volatile
    var state: HealthState = HealthState()
        private set

    @Volatile private var lastAttemptAt = 0L
    @Volatile private var lastLocation: LocationConfig? = null

    suspend fun refreshIfDue(shown: Boolean) {
        if (!shown) return
        val loc = configStore.get().location
        val interval = if (state.lastError != null) RETRY_MS else INTERVAL_MS
        // 地点を変えたら待たずに取り直す
        if (loc == lastLocation && System.currentTimeMillis() - lastAttemptAt < interval) return
        refreshNow()
    }

    suspend fun refreshNow() {
        lastAttemptAt = System.currentTimeMillis()
        val loc = configStore.get().location
        lastLocation = loc
        try {
            val f: ForecastDto = client.get(FORECAST_URL) {
                parameter("latitude", loc.latitude)
                parameter("longitude", loc.longitude)
                parameter("timezone", loc.timezone)
                parameter("forecast_days", 2)
                parameter("hourly", "temperature_2m,relative_humidity_2m,shortwave_radiation,wind_speed_10m,uv_index")
                parameter("wind_speed_unit", "ms")
            }.body()
            // 大気質は別の取得先。落ちても暑さ指数と紫外線は出す
            val air: AirDto? = try {
                client.get(AIR_URL) {
                    parameter("latitude", loc.latitude)
                    parameter("longitude", loc.longitude)
                    parameter("timezone", loc.timezone)
                    parameter("forecast_days", 2)
                    parameter("hourly", "pm2_5")
                }.body()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "PM2.5 の取得に失敗", e)
                null
            }
            val h = f.hourly ?: error("hourly がありません")
            val pm = air?.hourly?.let { a -> a.time.zip(a.pm25).toMap() }.orEmpty()
            val hours = h.time.mapIndexed { i, t ->
                val ta = h.temperature.getOrNull(i)
                val rh = h.humidity.getOrNull(i)
                val sr = h.radiation.getOrNull(i)
                val ws = h.wind.getOrNull(i)
                HealthHour(
                    time = t,
                    wbgt = if (ta != null && rh != null && sr != null && ws != null) Health.wbgt(ta, rh, sr, ws) else null,
                    uv = h.uv.getOrNull(i),
                    pm25 = pm[t],
                )
            }
            state = HealthState(hours, f.timezone, System.currentTimeMillis(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "空気・体調の取得に失敗", e)
            state = state.copy(fetchedAt = System.currentTimeMillis(), lastError = e.message ?: e::class.java.simpleName)
        }
    }

    @Serializable
    private data class ForecastDto(val timezone: String? = null, val hourly: HourlyDto? = null)

    @Serializable
    private data class HourlyDto(
        val time: List<String> = emptyList(),
        @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
        @SerialName("relative_humidity_2m") val humidity: List<Double?> = emptyList(),
        @SerialName("shortwave_radiation") val radiation: List<Double?> = emptyList(),
        @SerialName("wind_speed_10m") val wind: List<Double?> = emptyList(),
        @SerialName("uv_index") val uv: List<Double?> = emptyList(),
    )

    @Serializable
    private data class AirDto(val hourly: AirHourlyDto? = null)

    @Serializable
    private data class AirHourlyDto(
        val time: List<String> = emptyList(),
        @SerialName("pm2_5") val pm25: List<Double?> = emptyList(),
    )

    companion object {
        private const val TAG = "HealthRepository"
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val AIR_URL = "https://air-quality-api.open-meteo.com/v1/air-quality"
        private const val INTERVAL_MS = 30 * 60_000L
        private const val RETRY_MS = 5 * 60_000L
    }
}
