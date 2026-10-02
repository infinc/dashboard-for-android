package app.dashboard.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

/**
 * 暗号資産の値とチャート（1 つだけ）。CoinGecko の公開 API（登録不要）から、設定した間隔（既定 10 分）で取る。
 *
 * Phantom の API（api.phantom.app）は公開されておらず、アプリの外からは 403 で断られるので使えない。
 * CoinGecko の登録なしの枠は 1 分に数回までなので、1 回の更新は 2 回の通信（値と、折れ線かろうそく足のどちらか）に留める。
 * 取れなかったときは直前の値を残してエラーだけ添え、2 分後に試し直す。
 *
 * カードの「−」「＋」で期間を行き来しても通信が増えすぎないよう、期間・描き方ごとの結果を覚えておき、
 * 更新の間隔のうちに戻ってきたときは取り直さずにそれを出す。
 */
class CryptoRepository(private val client: HttpClient, private val configStore: ConfigStore) {

    @Volatile
    var state: CryptoState = CryptoState()
        private set

    /** 見回り（DashboardService）とカードの「−」「＋」が同時に取りに行かないようにする。 */
    private val lock = Mutex()
    private var lastAttemptAt = 0L
    private var lastKey: CryptoConfig? = null
    /** 通貨・値の通貨・期間・描き方ごとの、最後に取れた結果。 */
    private val cache = HashMap<CryptoConfig, CryptoState>()

    suspend fun refreshIfDue(wanted: Boolean) {
        if (!wanted) return
        lock.withLock {
            val config = configStore.get().crypto
            val key = keyOf(config)
            val interval = config.intervalMin * 60_000L
            val now = System.currentTimeMillis()
            if (key != lastKey) {
                // 通貨・期間・描き方を変えたら待たずに切り替える。前に取った結果があればまずそれを出し、新しければ取り直さない
                lastKey = key
                val cached = cache[key]
                if (cached != null) {
                    state = cached
                    if (now - cached.fetchedAt < interval) {
                        lastAttemptAt = cached.fetchedAt
                        return
                    }
                }
            } else if (now - lastAttemptAt < (if (state.lastError != null) RETRY_MS else interval)) {
                return
            }
            load(config)
        }
    }

    suspend fun refreshNow() = lock.withLock {
        val config = configStore.get().crypto
        lastKey = keyOf(config)
        load(config)
    }

    private fun keyOf(config: CryptoConfig) = config.copy(intervalMin = 0)

    private suspend fun load(config: CryptoConfig) {
        lastAttemptAt = System.currentTimeMillis()
        val key = keyOf(config)
        try {
            val fresh = fetch(config)
            if (cache.size >= MAX_CACHE && key !in cache) cache.minByOrNull { it.value.fetchedAt }?.let { cache.remove(it.key) }
            cache[key] = fresh
            state = fresh
        } catch (e: Exception) {
            Log.w(TAG, "暗号資産の取得に失敗: ${config.coin}", e)
            // 別の通貨・期間の値は、失敗したときに残さない
            val previous = cache[key] ?: CryptoState(config.coin, config.currency, config.range)
            state = previous.copy(lastError = describe(e))
        }
    }

    private suspend fun fetch(config: CryptoConfig): CryptoState {
        val period = PERIODS.getValue(config.range)
        val market = Http.json.parseToJsonElement(
            get("$BASE/coins/markets") {
                parameter("vs_currency", config.currency)
                parameter("ids", config.coin)
                parameter("price_change_percentage", period)
            },
        ).jsonArray.firstOrNull()?.jsonObject ?: throw NotFound()
        val path = "$BASE/coins/" + URLEncoder.encode(config.coin, "UTF-8")
        val candles: List<CryptoCandle>
        val points: List<Double>
        if (config.chart == "candle") {
            // [時刻, 始値, 高値, 安値, 終値] の並び。1 日は 30 分足、7・30 日は 4 時間足、1 年は 4 日足で返る
            candles = merge(
                Http.json.parseToJsonElement(
                    get("$path/ohlc") {
                        parameter("vs_currency", config.currency)
                        parameter("days", config.range)
                    },
                ).jsonArray.mapNotNull { row ->
                    val v = row.jsonArray.drop(1).mapNotNull { it.jsonPrimitive.doubleOrNull }
                    if (v.size == 4) CryptoCandle(v[0], v[1], v[2], v[3]) else null
                },
            )
            points = candles.map { it.close }
        } else {
            candles = emptyList()
            points = Http.json.parseToJsonElement(
                get("$path/market_chart") {
                    parameter("vs_currency", config.currency)
                    parameter("days", config.range)
                },
            ).jsonObject["prices"]?.jsonArray?.mapNotNull { it.jsonArray.getOrNull(1)?.jsonPrimitive?.doubleOrNull }.orEmpty()
        }
        val price = market.num("current_price") ?: points.lastOrNull()
        val first = candles.firstOrNull()?.open ?: points.firstOrNull()
        return CryptoState(
            coin = config.coin,
            currency = config.currency,
            range = config.range,
            name = market["name"]?.jsonPrimitive?.contentOrNull,
            symbol = market["symbol"]?.jsonPrimitive?.contentOrNull?.uppercase(),
            price = price,
            changePercent = market.num("price_change_percentage_${period}_in_currency")
                ?: if (price != null && first != null && first != 0.0) (price - first) / first * 100 else null,
            high = candles.maxOfOrNull { it.high } ?: points.maxOrNull(),
            low = candles.minOfOrNull { it.low } ?: points.minOrNull(),
            points = thin(points),
            candles = candles,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun get(url: String, block: HttpRequestBuilder.() -> Unit): String =
        client.get(url) {
            header("User-Agent", "Mozilla/5.0 (Linux; Android) Dashboard")
            header("Accept", "application/json")
            block()
        }.bodyAsText()

    /** チャートに描く点を [MAX_POINTS] まで等間隔に間引く（最初と最後は残す）。 */
    private fun thin(points: List<Double>): List<Double> =
        if (points.size <= MAX_POINTS) points
        else List(MAX_POINTS) { i -> points[(i.toLong() * (points.size - 1) / (MAX_POINTS - 1)).toInt()] }

    /** ろうそく足が多すぎると 1 本が細くて読めないので、隣どうしをまとめて [MAX_CANDLES] 本までにする。 */
    private fun merge(candles: List<CryptoCandle>): List<CryptoCandle> {
        if (candles.size <= MAX_CANDLES) return candles
        val group = (candles.size + MAX_CANDLES - 1) / MAX_CANDLES
        // 端数は古い側に寄せ、いちばん新しい足が欠けないようにする
        val head = candles.size % group
        return (listOfNotNull(candles.take(head).takeIf { it.isNotEmpty() }) + candles.drop(head).chunked(group)).map { g ->
            CryptoCandle(g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close)
        }
    }

    private fun describe(e: Exception): String = when {
        e is NotFound -> "通貨が見つかりません（設定画面で CoinGecko の ID を確かめてください）"
        e is ResponseException && e.response.status.value == 429 -> "取得先が混み合っています（数分後に試し直します）"
        e is ResponseException && e.response.status.value == 404 -> "通貨が見つかりません（設定画面で CoinGecko の ID を確かめてください）"
        else -> e.message ?: "取得失敗"
    }

    private fun JsonObject.num(key: String) = this[key]?.jsonPrimitive?.doubleOrNull

    private class NotFound : Exception()

    private companion object {
        const val TAG = "CryptoRepository"
        const val BASE = "https://api.coingecko.com/api/v3"
        const val RETRY_MS = 120_000L
        const val MAX_POINTS = 180
        const val MAX_CANDLES = 48
        const val MAX_CACHE = 16
        /** チャートの期間（日数）→ CoinGecko の変化率の期間の名前。 */
        val PERIODS = mapOf("1" to "24h", "7" to "7d", "30" to "30d", "365" to "1y")
    }
}
