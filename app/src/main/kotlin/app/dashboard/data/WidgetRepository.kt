package app.dashboard.data

import android.util.Log
import app.dashboard.i18n.L
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * 自作カードの値（[WidgetsConfig]）。
 *
 * - URL から取る: 見回り（[refreshIfDue]、15 秒ごと）がカードを出している間だけ、カードごとの間隔で取りに行く。
 *   `https://` は共有の Ktor、LAN の `http://` は [LanHttp]（平文はプライベートなアドレスだけ）
 * - 外から送る: サーバーが [push] に本文を渡す
 * - 死活監視: 行ごとに応答の有無と時間（`tcp://` はつながるかだけ）
 *
 * 値と 24 時間ぶんの履歴は `filesDir/widgets-data.json` に残す（書き込みは [SAVE_DELAY_MS] ごとにまとめる）。
 * しきい値の「警戒」に入った・戻ったときは [notify] で知らせる（画面がバナーと音を出す）。
 * 取得はカードごとに別のコルーチンで動かし、遅い 1 枚がほかのカードや見回りを止めないようにする。
 */
class WidgetRepository(
    dir: File,
    private val client: HttpClient,
    private val config: () -> Config,
    private val scope: CoroutineScope,
    private val notify: (ExternalNotice) -> Unit,
    private val lan: (String, Map<String, String>) -> LanHttp.Response = { url, headers -> LanHttp.get(url, headers) },
    private val tcp: (String, Int, Int) -> Unit = ::tcpConnect,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Volatile
    var state: WidgetsState = WidgetsState()
        private set

    private val file = File(dir, FILE_NAME)
    private val data = ConcurrentHashMap<String, WidgetData>()
    private val lastAttempt = ConcurrentHashMap<String, Long>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    /** 前に見た段（通知の判定）。まだ見ていないカードは入っていない（起動直後の 1 回目では知らせない）。 */
    private val levels = ConcurrentHashMap<String, Int>()
    @Volatile private var saveJob: Job? = null

    init {
        load()
    }

    /** 取りに行っている途中のカードがあるか（テストが待つため）。 */
    internal val busy: Boolean get() = running.isNotEmpty()

    /** 見回り（15 秒ごと）から。カードを出していて、間隔が来た「URL から取る」「死活監視」のカードを取りに行く。 */
    fun refreshIfDue(display: DisplayConfig) {
        val c = config()
        WIDGET_IDS.forEachIndexed { i, id ->
            val def = c.widgets.def(id)
            reconcile(def)
            if (!shown(display, i) || def.source == "push" || !configured(def)) return@forEachIndexed
            val now = clock()
            val last = lastAttempt[id] ?: 0L
            val interval = def.intervalMin.coerceAtLeast(1) * 60_000L
            val wait = if (data[id]?.lastError != null) minOf(interval, RETRY_MS) else interval
            if (now - last < wait) return@forEachIndexed
            if (!running.add(id)) return@forEachIndexed
            lastAttempt[id] = now
            scope.launch {
                try {
                    fetch(def)
                } finally {
                    running.remove(id)
                }
            }
        }
    }

    /** 「今すぐ全データを取得し直す」。出しているカードを順に取り直す。 */
    suspend fun refreshNow() {
        val c = config()
        WIDGET_IDS.forEachIndexed { i, id ->
            val def = c.widgets.def(id)
            reconcile(def)
            if (!shown(c.display, i) || def.source == "push" || !configured(def)) return@forEachIndexed
            if (!running.add(id)) return@forEachIndexed
            try {
                lastAttempt[id] = clock()
                fetch(def)
            } finally {
                running.remove(id)
            }
        }
    }

    /**
     * 外から送られた本文を取り込む（`POST /api/widgets/{id}`）。[WidgetPush.Bad] なら何も変えない。
     * 取り込むのは「外から送る」のカードだけ（呼び出し側が確かめる）。
     */
    fun push(id: String, body: String): WidgetPush {
        val def = config().widgets.def(id)
        reconcile(def)
        val now = clock()
        val r = WidgetRead.push(def, body, now)
        if (r is WidgetPush.Value) accept(def, r.data.copy(fetchedAt = now, lastError = null))
        return r
    }

    /** 「試しに取得」。保存はせず、通知も出さない。 */
    suspend fun test(input: WidgetDef): WidgetTestResult {
        val saved = config().widgets.def(input.id)
        // ヘッダーの値を空のまま試したときは、保存済みの値を使う（同じ送り先のときだけ。別の送り先へトークンを渡さない）
        val def = if (input.headerValue.isNullOrEmpty() && input.headerName.isNotBlank() && hostOf(input.url) == hostOf(saved.url)) {
            input.copy(headerValue = saved.headerValue)
        } else {
            input
        }
        val now = clock()
        return try {
            when (def.source) {
                "push" -> WidgetTestResult(false, error = L("「外から送る」のカードは試せません。値を送ってカードで確かめてください", "Pushed cards can't be tested here. Send a value and check the card"))
                "monitor" -> {
                    if (def.targets.isEmpty()) return WidgetTestResult(false, error = L("監視する先がありません", "No targets to check"))
                    WidgetTestResult(true, data = monitor(def, now))
                }
                else -> {
                    if (def.url.isBlank()) return WidgetTestResult(false, error = L("URL を入れてください", "Enter a URL"))
                    val r = get(def)
                    val raw = r.body.take(MAX_RAW)
                    if (r.status !in 200..299) return WidgetTestResult(false, r.status, raw, error = "HTTP ${r.status}")
                    when (val p = WidgetRead.parse(def, r.body, now)) {
                        is WidgetPush.Value -> WidgetTestResult(true, r.status, raw, p.data)
                        is WidgetPush.Bad -> WidgetTestResult(false, r.status, raw, error = p.reason)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WidgetTestResult(false, error = describe(e))
        }
    }

    // ---------------------------------------------------------------- 取得

    private class Fetched(val status: Int, val body: String)

    private class WidgetException(message: String) : Exception(message)

    private suspend fun fetch(def: WidgetDef) {
        val now = clock()
        try {
            val next = if (def.source == "monitor") {
                monitor(def, now)
            } else {
                val r = get(def)
                if (r.status !in 200..299) throw WidgetException("HTTP ${r.status}")
                when (val p = WidgetRead.parse(def, r.body, now)) {
                    is WidgetPush.Value -> p.data
                    is WidgetPush.Bad -> throw WidgetException(p.reason)
                }
            }
            // 取っている間に設定が変わっていたら捨てる（古い URL の値を新しいカードに入れない）
            if (config().widgets.def(def.id).dataKey != def.dataKey) return
            accept(def, next.copy(fetchedAt = now, lastError = null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "自作カード ${def.id} の取得に失敗", e)
            if (config().widgets.def(def.id).dataKey != def.dataKey) return
            put(current(def).copy(fetchedAt = now, lastError = describe(e)))
        }
    }

    /** 本文を取る。`https://` は Ktor、`http://` は LAN だけ（[LanHttp]）。 */
    private suspend fun get(def: WidgetDef): Fetched {
        val c = config()
        val url = widgetUrl(def.url, c.location, c.display.language)
        val headers = buildMap {
            if (def.headerName.isNotBlank() && !def.headerValue.isNullOrBlank()) put(def.headerName.trim(), def.headerValue.trim())
        }
        return when (schemeOf(url)) {
            "https" -> {
                val res = client.get(url) {
                    expectSuccess = false
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    header(HttpHeaders.Accept, "application/json, text/plain, */*")
                    headers.forEach { (k, v) -> header(k, v) }
                }
                val length = res.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (length != null && length > LanHttp.MAX_BYTES) throw WidgetException(L("応答が大きすぎます（1 MB まで）", "The response is too large (1 MB max)"))
                Fetched(res.status.value, res.bodyAsText())
            }
            "http" -> withContext(Dispatchers.IO) { lan(url, headers).let { Fetched(it.status, it.body) } }
            else -> throw WidgetException(L("URL は https:// か http:// で始めてください", "The URL must start with https:// or http://"))
        }
    }

    /** 死活監視。行ごとに並べて確かめる。 */
    private suspend fun monitor(def: WidgetDef, now: Long): WidgetData = coroutineScope {
        val rows = def.targets.take(MAX_WIDGET_TARGETS).map { t -> async { probe(t) } }.awaitAll()
        WidgetData(def.id, def.dataKey, items = rows, updatedAt = now)
    }

    private suspend fun probe(t: WidgetTarget): WidgetItem {
        val name = t.name.ifBlank { hostOf(t.url) ?: t.url }.take(40)
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        return try {
            val url = t.url.trim()
            when (schemeOf(url)) {
                "tcp" -> {
                    val uri = URI(url)
                    val host = uri.host ?: throw WidgetException(L("ホストがありません", "No host"))
                    if (uri.port <= 0) throw WidgetException(L("ポートを書いてください（tcp://host:22）", "Add a port (tcp://host:22)"))
                    withContext(Dispatchers.IO) { tcp(host, uri.port, PROBE_TIMEOUT_MS) }
                    val ms = elapsed()
                    WidgetItem(name, "$ms ms", WidgetLevel.OK, ms)
                }
                "https" -> {
                    val res = client.get(url) {
                        expectSuccess = false
                        timeout { requestTimeoutMillis = PROBE_TIMEOUT_MS.toLong() }
                        header(HttpHeaders.UserAgent, USER_AGENT)
                    }
                    statusRow(name, res.status.value, elapsed())
                }
                "http" -> {
                    val res = withContext(Dispatchers.IO) { lan(url, emptyMap()) }
                    statusRow(name, res.status, elapsed())
                }
                else -> WidgetItem(name, L("URL の形が違います", "Bad URL"), WidgetLevel.ALERT)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WidgetItem(name, shortReason(e), WidgetLevel.ALERT)
        }
    }

    /** 2xx・3xx は応答あり、4xx は注意（つながってはいる）、5xx は警戒。 */
    private fun statusRow(name: String, status: Int, ms: Long): WidgetItem = when (status) {
        in 200..399 -> WidgetItem(name, "$ms ms", WidgetLevel.OK, ms)
        in 400..499 -> WidgetItem(name, "HTTP $status", WidgetLevel.WARN, ms)
        else -> WidgetItem(name, "HTTP $status", WidgetLevel.ALERT, ms)
    }

    // ---------------------------------------------------------------- 値の取り込み

    /** 取った（届いた）値を入れる。履歴を足し、段が変わったら知らせる。 */
    private fun accept(def: WidgetDef, next: WidgetData) {
        val prev = current(def)
        val history = if (def.keepsHistory && next.number != null) appendHistory(prev.history, next.updatedAt, next.number) else prev.history
        val merged = next.copy(id = def.id, key = def.dataKey, history = history)
        put(merged)
        checkLevel(def, merged)
    }

    private fun checkLevel(def: WidgetDef, d: WidgetData) {
        val level = when (def.shownStyle) {
            "number", "gauge" -> WidgetLevel.of(d.number, def)
            "list", "monitor" -> WidgetLevel.worst(d.items)
            else -> WidgetLevel.OK
        }
        val before = levels.put(def.id, level)
        if (!def.notify || before == null || before == level) return
        val tone = def.tone.takeIf(Tones::isKnown) ?: config().notifications.memoTone
        val value = d.number?.let { WidgetFormat.withUnit(WidgetFormat.number(it, def.decimals), d.unit) }
        when {
            level == WidgetLevel.ALERT -> {
                val text = if (def.shownStyle == "monitor") {
                    val down = d.items.filter { it.level == WidgetLevel.ALERT }.joinToString(L("・", ", ")) { it.label }
                    L("$down が応答しません", "$down is not responding")
                } else if (value != null) {
                    val at = def.alertAt?.let { WidgetFormat.withUnit(WidgetFormat.number(it, def.decimals), d.unit) }
                    if (def.below) L("$value になりました（${at} を下回りました）", "Now $value (below $at)")
                    else L("$value になりました（${at} を超えました）", "Now $value (above $at)")
                } else {
                    val bad = d.items.filter { it.level == WidgetLevel.ALERT }.joinToString(L("・", ", ")) { "${it.label} ${it.value}" }
                    L("警戒: $bad", "Alert: $bad")
                }
                notify(ExternalNotice(def.label, text.take(200), tone))
            }
            before == WidgetLevel.ALERT && level == WidgetLevel.OK -> {
                val text = if (def.shownStyle == "monitor") L("すべて応答しています", "Everything is responding again")
                else L("元に戻りました", "Back to normal") + (value?.let { L("（$it）", " ($it)") } ?: "")
                notify(ExternalNotice(def.label, text, tone))
            }
        }
    }

    /** 設定が変わって値の意味が変わったカードは、前の値・履歴・段を捨て、すぐに取り直す。 */
    private fun reconcile(def: WidgetDef) {
        val d = data[def.id] ?: return
        if (d.key == def.dataKey) return
        data.remove(def.id)
        levels.remove(def.id)
        lastAttempt.remove(def.id)
        publish()
        scheduleSave()
    }

    private fun current(def: WidgetDef): WidgetData =
        data[def.id]?.takeIf { it.key == def.dataKey } ?: WidgetData(def.id, def.dataKey)

    private fun put(d: WidgetData) {
        data[d.id] = d
        publish()
        scheduleSave()
    }

    private fun publish() {
        state = WidgetsState(WIDGET_IDS.mapNotNull { data[it] })
    }

    // ---------------------------------------------------------------- 保存

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val saved = Http.json.decodeFromString<WidgetsState>(file.readText())
            saved.items.filter { it.id in WIDGET_IDS }.forEach { data[it.id] = it.copy(lastError = null) }
            publish()
        }.onFailure { Log.w(TAG, "widgets-data.json を読めませんでした", it) }
    }

    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            save()
        }
    }

    /** いまの値を書き出す（一時ファイル経由）。 */
    fun save() {
        runCatching {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(Http.json.encodeToString(WidgetsState.serializer(), state))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "widgets-data.json の保存に失敗", it) }
    }

    companion object {
        private const val TAG = "WidgetRepository"
        const val FILE_NAME = "widgets-data.json"
        private const val RETRY_MS = 2 * 60_000L
        private const val SAVE_DELAY_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 8_000
        /** 「試しに取得」で返す本文の長さ。 */
        const val MAX_RAW = 64 * 1024
        private const val USER_AGENT = "Dashboard (Android; custom card)"

        /** 自作カード [index]（0 から）を出しているか。 */
        fun shown(d: DisplayConfig, index: Int): Boolean = when (index) {
            0 -> d.showWidget1
            1 -> d.showWidget2
            2 -> d.showWidget3
            3 -> d.showWidget4
            else -> false
        }

        /** 取りに行けるだけの設定があるか。 */
        fun configured(def: WidgetDef): Boolean = when (def.source) {
            "monitor" -> def.targets.isNotEmpty()
            "push" -> true
            else -> def.url.isNotBlank()
        }

        /** 24 時間より古い点を捨て、[WIDGET_HISTORY_STEP_MS] の区切りごとに 1 点にする（同じ区切りの中は新しい値で置き換える）。 */
        fun appendHistory(history: List<WidgetPoint>, t: Long, v: Double): List<WidgetPoint> {
            val kept = history.filter { t - it.t < 24 * 3_600_000L && it.t <= t }
            val last = kept.lastOrNull()
            val next = if (last != null && last.t / WIDGET_HISTORY_STEP_MS == t / WIDGET_HISTORY_STEP_MS) kept.dropLast(1) + WidgetPoint(t, v) else kept + WidgetPoint(t, v)
            return next.takeLast(WIDGET_HISTORY_POINTS + 1)
        }

        fun schemeOf(url: String): String? = runCatching { URI(url.trim()).scheme?.lowercase() }.getOrNull()

        fun hostOf(url: String): String? = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()

        /** 画面に出す失敗の理由。 */
        fun describe(e: Throwable): String = when (e) {
            is LanHttp.NotPrivate -> L(
                "平文の http:// は家の中（LAN）の機器だけに使えます。外のサイトは https:// にしてください",
                "Plain http:// is only allowed for devices on your LAN. Use https:// for outside sites",
            )
            is UnknownHostException -> L("名前を引けません", "Can't resolve the host name") + (e.message?.let { L("（$it）", " ($it)") } ?: "")
            is SocketTimeoutException -> L("応答がありません（時間切れ）", "No response (timed out)")
            is ConnectException -> L("つながりません", "Connection refused")
            is IllegalArgumentException, is java.net.URISyntaxException -> L("URL の形が違います", "The URL isn't valid")
            else -> e.message?.takeIf { it.isNotBlank() }?.take(120) ?: e::class.java.simpleName
        }

        private fun shortReason(e: Throwable): String = when (e) {
            is SocketTimeoutException -> L("時間切れ", "Timed out")
            is UnknownHostException -> L("名前なし", "No host")
            is ConnectException -> L("拒否", "Refused")
            is LanHttp.NotPrivate -> L("LAN の外", "Not LAN")
            is IOException -> L("応答なし", "No response")
            else -> e.message?.take(20) ?: L("応答なし", "No response")
        }

        private fun tcpConnect(host: String, port: Int, timeoutMs: Int) {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        }
    }
}
