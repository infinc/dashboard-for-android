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
 * 暗号通貨の値とチャート（1 つだけ）。CoinGecko の公開 API（登録不要）から、設定した間隔（既定 10 分）で取る。
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

    /** 全画面の結果（通貨・値の通貨・期間・ろうそく足かどうかごと）。[DETAIL_FRESH_MS] のうちは取り直さない。 */
    private val details = HashMap<String, CryptoDetail>()

    /**
     * 全画面に出す詳しい値とチャート。値（`coins/markets`）・折れ線と出来高（`market_chart`）、ろうそく足を選んだら `ohlc` も取る。
     * カードの見回りと同じ枠を使うので、同時には取りに行かない。
     */
    suspend fun detail(coin: String, currency: String, days: String, candle: Boolean): CryptoDetail = lock.withLock {
        val key = listOf(coin, currency, days, candle).joinToString("/")
        details[key]?.takeIf { System.currentTimeMillis() - it.fetchedAt < DETAIL_FRESH_MS }?.let { return@withLock it }
        try {
            fetchDetail(coin, currency, days, candle).also {
                if (details.size >= MAX_CACHE) details.minByOrNull { e -> e.value.fetchedAt }?.let { e -> details.remove(e.key) }
                details[key] = it
            }
        } catch (e: Exception) {
            Log.w(TAG, "暗号通貨の詳しい値の取得に失敗: $coin", e)
            throw IllegalStateException(describe(e), e)
        }
    }

    private suspend fun fetchDetail(coin: String, currency: String, days: String, candle: Boolean): CryptoDetail {
        val period = PERIODS[days]
        // 変化率は全部の期間を 1 度に頼む（期間を切り替えても同じ問い合わせになり、取り直さずに済む）
        val market = Http.json.parseToJsonElement(
            cachedGet("$BASE/coins/markets", "vs_currency" to currency, "ids" to coin, "price_change_percentage" to "24h,7d,30d,1y"),
        ).jsonArray.firstOrNull()?.jsonObject ?: throw NotFound()
        val path = "$BASE/coins/" + URLEncoder.encode(coin, "UTF-8")
        val chart = Http.json.parseToJsonElement(cachedGet("$path/market_chart", "vs_currency" to currency, "days" to days)).jsonObject
        fun series(name: String) = chart[name]?.jsonArray?.mapNotNull { row ->
            val t = row.jsonArray.getOrNull(0)?.jsonPrimitive?.doubleOrNull?.toLong()
            val v = row.jsonArray.getOrNull(1)?.jsonPrimitive?.doubleOrNull
            if (t != null && v != null) t to v else null
        }.orEmpty()
        val prices = series("prices")
        val volumes = series("total_volumes").toMap()
        val picked = pick(prices.size, MAX_DETAIL_POINTS).map { prices[it] }
        val candles = if (!candle) emptyList() else merge(
            Http.json.parseToJsonElement(cachedGet("$path/ohlc", "vs_currency" to currency, "days" to days)).jsonArray.mapNotNull { row ->
                val v = row.jsonArray.mapNotNull { it.jsonPrimitive.doubleOrNull }
                if (v.size == 5) CryptoCandle(v[1], v[2], v[3], v[4], v[0].toLong()) else null
            },
            MAX_DETAIL_CANDLES,
        )
        val price = market.num("current_price") ?: prices.lastOrNull()?.second
        val first = candles.firstOrNull()?.open ?: prices.firstOrNull()?.second
        return CryptoDetail(
            coin = coin,
            currency = currency,
            days = days,
            name = market["name"]?.jsonPrimitive?.contentOrNull,
            symbol = market["symbol"]?.jsonPrimitive?.contentOrNull?.uppercase(),
            rank = market.num("market_cap_rank")?.toInt(),
            price = price,
            changePercent = period?.let { market.num("price_change_percentage_${it}_in_currency") }
                ?: if (price != null && first != null && first != 0.0) (price - first) / first * 100 else null,
            change24h = market.num("price_change_percentage_24h_in_currency") ?: market.num("price_change_percentage_24h"),
            high = candles.maxOfOrNull { it.high } ?: prices.maxOfOrNull { it.second },
            low = candles.minOfOrNull { it.low } ?: prices.minOfOrNull { it.second },
            marketCap = market.num("market_cap"),
            volume24h = market.num("total_volume"),
            ath = market.num("ath"),
            athChangePercent = market.num("ath_change_percentage"),
            times = picked.map { it.first },
            prices = picked.map { it.second },
            // 出来高は値と同じ時刻のもの（無ければ 0）
            volumes = picked.map { volumes[it.first] ?: 0.0 },
            candles = candles,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun load(config: CryptoConfig) {
        lastAttemptAt = System.currentTimeMillis()
        val key = keyOf(config)
        try {
            val fresh = fetch(config)
            if (cache.size >= MAX_CACHE && key !in cache) cache.minByOrNull { it.value.fetchedAt }?.let { cache.remove(it.key) }
            cache[key] = fresh
            state = fresh
        } catch (e: Exception) {
            Log.w(TAG, "暗号通貨の取得に失敗: ${config.coin}", e)
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

    /** 全画面の応答（URL ごと）。描き方や期間を切り替えても、同じ問い合わせは [DETAIL_FRESH_MS] のうち取り直さない。 */
    private val bodies = HashMap<String, Pair<Long, String>>()

    private suspend fun cachedGet(url: String, vararg params: Pair<String, String>): String {
        val key = url + params.joinToString("&", "?") { "${it.first}=${it.second}" }
        val now = System.currentTimeMillis()
        bodies[key]?.takeIf { now - it.first < DETAIL_FRESH_MS }?.let { return it.second }
        val body = get(url) { params.forEach { (k, v) -> parameter(k, v) } }
        if (bodies.size >= 32) bodies.entries.removeAll { now - it.value.first >= DETAIL_FRESH_MS }
        bodies[key] = now to body
        return body
    }

    /** チャートに描く点を [MAX_POINTS] まで等間隔に間引く（最初と最後は残す）。 */
    private fun thin(points: List<Double>): List<Double> = pick(points.size, MAX_POINTS).map { points[it] }

    /** [size] 個から [max] 個を等間隔に選ぶときの番号（最初と最後は残す）。 */
    private fun pick(size: Int, max: Int): List<Int> =
        if (size <= max) List(size) { it } else List(max) { i -> (i.toLong() * (size - 1) / (max - 1)).toInt() }

    /** ろうそく足が多すぎると 1 本が細くて読めないので、隣どうしをまとめて [max] 本までにする。 */
    private fun merge(candles: List<CryptoCandle>, max: Int = MAX_CANDLES): List<CryptoCandle> {
        if (candles.size <= max) return candles
        val group = (candles.size + max - 1) / max
        // 端数は古い側に寄せ、いちばん新しい足が欠けないようにする
        val head = candles.size % group
        return (listOfNotNull(candles.take(head).takeIf { it.isNotEmpty() }) + candles.drop(head).chunked(group)).map { g ->
            CryptoCandle(g.first().open, g.maxOf { it.high }, g.minOf { it.low }, g.last().close, g.last().time)
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
        const val MAX_DETAIL_POINTS = 360
        const val MAX_DETAIL_CANDLES = 90
        const val DETAIL_FRESH_MS = 180_000L
        /** チャートの期間（日数）→ CoinGecko の変化率の期間の名前。 */
        val PERIODS = mapOf("1" to "24h", "7" to "7d", "30" to "30d", "365" to "1y")
        // 90 日は CoinGecko の変化率に無いので、チャートの始まりの値から求める
    }
}
