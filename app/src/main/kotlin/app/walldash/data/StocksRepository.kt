package app.walldash.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * 主要な株価・為替の簡単なチャート。Yahoo Finance のチャート API（非公式）から 10 分ごとに取る。
 *
 * 非公式の API なので、仕様が予告なく変わったり止まったりし得る。そのときは直前の値を残してエラーだけ添える。
 */
class StocksRepository(private val client: HttpClient, private val configStore: ConfigStore) {

    @Volatile
    var state: StocksState = StocksState()
        private set

    private var lastAttemptAt = 0L
    private var lastKey: StocksConfig? = null

    suspend fun refreshIfDue(wanted: Boolean) {
        if (!wanted) return
        val config = configStore.get().stocks
        // 銘柄や期間を変えたら待たずに取り直す
        if (config == lastKey && System.currentTimeMillis() - lastAttemptAt < INTERVAL_MS) return
        refreshNow()
    }

    suspend fun refreshNow() {
        lastAttemptAt = System.currentTimeMillis()
        val config = configStore.get().stocks
        lastKey = config
        val errors = mutableListOf<String>()
        val previous = state.quotes.associateBy { it.symbol }
        val quotes = config.symbols.map { s ->
            try {
                fetch(s, config.range)
            } catch (e: Exception) {
                Log.w(TAG, "株価の取得に失敗: ${s.symbol}", e)
                errors += "${s.label}: ${e.message ?: "取得失敗"}"
                previous[s.symbol]?.copy(label = s.label) ?: StockQuote(s.symbol, s.label)
            }
        }
        state = StocksState(quotes, System.currentTimeMillis(), errors.joinToString(" / ").ifEmpty { null })
    }

    private suspend fun fetch(s: StockSymbol, range: String): StockQuote {
        val body = client.get("https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(s.symbol, "UTF-8")) {
            // 既定の User-Agent だと弾かれる
            header("User-Agent", "Mozilla/5.0 (Linux; Android) Walldash")
            parameter("range", range)
            parameter("interval", INTERVALS.getValue(range))
        }.bodyAsText()
        val result = Http.json.parseToJsonElement(body).jsonObject["chart"]!!.jsonObject["result"]!!.jsonArray[0].jsonObject
        val meta = result["meta"]!!.jsonObject
        val closes = result["indicators"]?.jsonObject?.get("quote")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("close")?.jsonArray?.mapNotNull { it.jsonPrimitive.doubleOrNull }.orEmpty()
        val price = meta.num("regularMarketPrice") ?: closes.lastOrNull()
        // 1 日の表示は前日終値と、それより長い期間は期間の始まりの前の終値と比べる
        val base = if (range == "1d") meta.num("previousClose") ?: meta.num("chartPreviousClose") else meta.num("chartPreviousClose") ?: closes.firstOrNull()
        return StockQuote(
            symbol = s.symbol,
            label = s.label,
            price = price,
            changePercent = if (price != null && base != null && base != 0.0) (price - base) / base * 100 else null,
            currency = meta["currency"]?.jsonPrimitive?.content,
            points = closes.takeLast(MAX_POINTS),
            base = base,
        )
    }

    private fun JsonObject.num(key: String) = this[key]?.jsonPrimitive?.doubleOrNull

    private companion object {
        const val TAG = "StocksRepository"
        const val INTERVAL_MS = 600_000L
        const val MAX_POINTS = 160
        val INTERVALS = mapOf("1d" to "5m", "5d" to "30m", "1mo" to "1d", "6mo" to "1d", "1y" to "1wk")
    }
}
