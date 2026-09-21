package app.walldash.data

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.min

/**
 * Open-Meteo から天気を取得して保持する。
 *
 * - 取得間隔は設定値（既定 10 分）。
 * - 成功するたびに filesDir/weather-cache.json に保存し、再起動直後や通信断でも即表示できるようにする。
 * - 失敗時は指数バックオフで再試行しつつ、直前のキャッシュを返し続ける（画面を空白にしない）。
 *
 * データ提供: Open-Meteo.com — CC BY 4.0。UI 側に帰属表示を必ず出すこと。
 */
class WeatherRepository(
    context: Context,
    private val client: HttpClient,
    private val configStore: ConfigStore,
) {

    private val cacheFile = File(context.filesDir, "weather-cache.json")

    private val json = Http.json

    @Volatile
    var state: WeatherState = WeatherState()
        private set

    private var lastAttemptAt = 0L
    private var consecutiveFailures = 0

    init {
        runCatching {
            if (cacheFile.exists()) {
                state = json.decodeFromString<WeatherState>(cacheFile.readText())
            }
        }.onFailure { Log.w(TAG, "天気キャッシュの読み込みに失敗", it) }
    }

    /** 取得間隔（失敗中はバックオフ）を過ぎていれば取りに行く。 */
    suspend fun refreshIfDue() {
        val now = System.currentTimeMillis()
        val interval = configStore.get().refresh.weatherIntervalMs
        val due = if (consecutiveFailures == 0) interval else backoffMs()
        if (now - lastAttemptAt < due) return
        refreshNow()
    }

    suspend fun refreshNow() {
        lastAttemptAt = System.currentTimeMillis()
        val config = configStore.get()
        val loc = config.location
        try {
            val dto: ForecastDto = client.get(FORECAST_URL) {
                parameter("latitude", loc.latitude)
                parameter("longitude", loc.longitude)
                parameter("timezone", loc.timezone)
                parameter("forecast_days", 7)
                parameter(
                    "current",
                    "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code," +
                        "wind_speed_10m,wind_direction_10m,precipitation"
                )
                // uv_index と visibility は current に無い項目なので、時間別から現在時刻の値を拾う
                parameter(
                    "hourly",
                    "temperature_2m,precipitation_probability,weather_code,uv_index,visibility"
                )
                parameter(
                    "daily",
                    "weather_code,temperature_2m_max,temperature_2m_min," +
                        "precipitation_sum,precipitation_probability_max,sunrise,sunset"
                )
                parameter(
                    "temperature_unit",
                    if (config.units.temperature == "f") "fahrenheit" else "celsius"
                )
                parameter("wind_speed_unit", if (config.units.wind == "ms") "ms" else "kmh")
            }.body()

            // 大気質は別 API なので、落ちても天気本体は出せるように切り離して足す
            val air = fetchAirQuality(loc)
            val base = dto.toState(loc.name)
            state = base.copy(
                current = base.current.copy(
                    aqi = air?.europeanAqi?.let { Math.round(it).toInt() },
                    pm25 = air?.pm25,
                )
            )
            consecutiveFailures = 0
            runCatching { cacheFile.writeText(json.encodeToString(state)) }
                .onFailure { Log.w(TAG, "天気キャッシュの保存に失敗", it) }
        } catch (e: Exception) {
            consecutiveFailures += 1
            Log.w(TAG, "天気の取得に失敗（${consecutiveFailures}回目）。キャッシュを表示し続ける", e)
            state = state.copy(lastError = e.message ?: e::class.java.simpleName)
        }
    }

    suspend fun geocode(query: String): List<GeocodeResult> {
        val dto: GeocodeResponseDto = client.get(GEOCODE_URL) {
            parameter("name", query)
            parameter("count", 8)
            parameter("language", "ja")
            parameter("format", "json")
        }.body()
        return dto.results.orEmpty().map {
            GeocodeResult(
                name = it.name,
                admin = it.admin1,
                country = it.country,
                latitude = it.latitude,
                longitude = it.longitude,
                timezone = it.timezone ?: "auto",
            )
        }
    }

    /**
     * 大気質（European AQI）を取る。
     *
     * 天気とは別のエンドポイントなので、こちらだけ落ちることがある。
     * その場合は前回値を捨てて null にし、古い数値を最新のように見せない。
     */
    private suspend fun fetchAirQuality(loc: LocationConfig): AirQualityCurrentDto? = runCatching {
        val dto: AirQualityDto = client.get(AIR_QUALITY_URL) {
            parameter("latitude", loc.latitude)
            parameter("longitude", loc.longitude)
            parameter("timezone", loc.timezone)
            parameter("current", "european_aqi,pm2_5")
        }.body()
        dto.current
    }.onFailure { Log.w(TAG, "大気質の取得に失敗。AQI と PM2.5 は非表示にする", it) }.getOrNull()

    /** 1分 → 2分 → 4分 … 最大 30 分。 */
    private fun backoffMs(): Long =
        min(60_000L shl min(consecutiveFailures - 1, 5), 30 * 60_000L)

    // ------------------------------------------------------------ DTO

    @Serializable
    private data class ForecastDto(
        val timezone: String? = null,
        val current: CurrentDto? = null,
        val hourly: HourlyDto? = null,
        val daily: DailyDto? = null,
    ) {
        fun toState(placeName: String): WeatherState {
            val hours = hourly?.toHours().orEmpty()
            // 現在時刻の降水確率は current に無いため、時間別配列の該当時刻から拾う
            val currentHour = current?.time?.let { t -> hours.firstOrNull { it.time == t } }
            val startIndex = current?.time
                ?.let { t -> hours.indexOfFirst { it.time >= t }.takeIf { it >= 0 } }
            val upcoming = startIndex?.let { idx -> hours.drop(idx).take(12) } ?: hours.take(12)
            // UV 指数と視程も current には無いので、同じ添字で時間別から取る
            val nowIndex = startIndex ?: 0

            return WeatherState(
                available = current != null,
                placeName = placeName,
                timezone = timezone,
                current = WeatherCurrent(
                    temperature = current?.temperature,
                    apparentTemperature = current?.apparentTemperature,
                    humidity = current?.humidity,
                    windSpeed = current?.windSpeed,
                    windDirection = current?.windDirection?.let { Math.round(it).toInt() },
                    precipitationProbability = currentHour?.precipitationProbability
                        ?: upcoming.firstOrNull()?.precipitationProbability,
                    precipitation = current?.precipitation,
                    uvIndex = hourly?.uvIndex?.getOrNull(nowIndex),
                    visibilityMeters = hourly?.visibility?.getOrNull(nowIndex),
                    weatherCode = current?.weatherCode,
                    isDay = (current?.isDay ?: 1) == 1,
                ),
                hourly = upcoming,
                daily = daily?.toDays().orEmpty(),
                fetchedAt = System.currentTimeMillis(),
                lastError = null,
            )
        }
    }

    @Serializable
    private data class CurrentDto(
        val time: String? = null,
        @SerialName("temperature_2m") val temperature: Double? = null,
        @SerialName("relative_humidity_2m") val humidity: Int? = null,
        @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
        @SerialName("is_day") val isDay: Int? = null,
        @SerialName("weather_code") val weatherCode: Int? = null,
        @SerialName("wind_speed_10m") val windSpeed: Double? = null,
        @SerialName("wind_direction_10m") val windDirection: Double? = null,
        val precipitation: Double? = null,
    )

    @Serializable
    private data class HourlyDto(
        val time: List<String> = emptyList(),
        @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
        @SerialName("precipitation_probability") val precipitationProbability: List<Int?> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
        @SerialName("uv_index") val uvIndex: List<Double?> = emptyList(),
        val visibility: List<Double?> = emptyList(),
    ) {
        fun toHours(): List<WeatherHour> = time.mapIndexed { i, t ->
            WeatherHour(
                time = t,
                temperature = temperature.getOrNull(i),
                precipitationProbability = precipitationProbability.getOrNull(i),
                weatherCode = weatherCode.getOrNull(i),
            )
        }
    }

    @Serializable
    private data class DailyDto(
        val time: List<String> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
        @SerialName("temperature_2m_max") val tempMax: List<Double?> = emptyList(),
        @SerialName("temperature_2m_min") val tempMin: List<Double?> = emptyList(),
        @SerialName("precipitation_sum") val precipitationSum: List<Double?> = emptyList(),
        @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int?> = emptyList(),
        val sunrise: List<String?> = emptyList(),
        val sunset: List<String?> = emptyList(),
    ) {
        fun toDays(): List<WeatherDay> = time.mapIndexed { i, d ->
            WeatherDay(
                date = d,
                tempMax = tempMax.getOrNull(i),
                tempMin = tempMin.getOrNull(i),
                weatherCode = weatherCode.getOrNull(i),
                precipitationSum = precipitationSum.getOrNull(i),
                precipitationProbabilityMax = precipitationProbabilityMax.getOrNull(i),
                sunrise = sunrise.getOrNull(i),
                sunset = sunset.getOrNull(i),
            )
        }
    }

    @Serializable
    private data class AirQualityDto(val current: AirQualityCurrentDto? = null)

    @Serializable
    private data class AirQualityCurrentDto(
        @SerialName("european_aqi") val europeanAqi: Double? = null,
        @SerialName("pm2_5") val pm25: Double? = null,
    )

    @Serializable
    private data class GeocodeResponseDto(val results: List<GeocodeItemDto>? = null)

    @Serializable
    private data class GeocodeItemDto(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val country: String? = null,
        val admin1: String? = null,
        val timezone: String? = null,
    )

    private companion object {
        const val TAG = "WeatherRepository"
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val AIR_QUALITY_URL = "https://air-quality-api.open-meteo.com/v1/air-quality"
        const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
    }
}

