package app.walldash.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 写真カードの写真。iCloud の共有アルバムを、「公開 Web サイト」の URL から読む。
 *
 * iCloud 写真のライブラリそのものは、Apple ID の 2 ファクタ認証を通す非公開の手順が要り、App 用パスワードでも読めない。
 * 共有アルバムは「公開 Web サイト」を ON にすると、www.icloud.com が使う API（sharedstreams）から誰でも読めるので、それを使う。
 *
 * 1. webstream … アルバムの名前と写真の一覧（写真ごとに大きさ違いの画像の checksum）
 * 2. webasseturls … checksum → 画像の URL（署名付きで、数時間で切れる）
 *
 * アルバムの置き場（p23 など）は URL の記号から決まるが、2024 年からは違う置き場を 330 と本文の X-Apple-MMe-Host で教えてくる。
 * Ktor の共有クライアントは 3xx を例外にするので、ここは OkHttp で送る。
 * URL が切れるので [INTERVAL_MS] ごとに一覧から取り直す。
 */
class PhotoRepository(private val configStore: ConfigStore) {

    /** 写真 1 枚。[url] は署名付きで、知っていれば誰でも開けるので外（/api/state）には出さない。 */
    data class Photo(val guid: String, val url: String, val width: Int, val height: Int, val caption: String?, val takenAt: String?)

    @Volatile
    var state: PhotoState = PhotoState()
        private set

    @Volatile
    var photos: List<Photo> = emptyList()
        private set

    private var lastAttemptAt = 0L
    private var lastUrl: String? = null

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    suspend fun refreshIfDue(shown: Boolean) {
        val c = configStore.get().photos
        if (!shown || !c.enabled || c.albumUrl.isNullOrBlank()) return
        if (c.albumUrl == lastUrl && System.currentTimeMillis() - lastAttemptAt < INTERVAL_MS) return
        refreshNow()
    }

    /** 画像の URL が切れた（403 など）ときに、画面から呼んで取り直してもらう。続けて呼ばれても 1 分は空ける。 */
    suspend fun refreshSoon() {
        if (System.currentTimeMillis() - lastAttemptAt < 60_000) return
        refreshNow()
    }

    suspend fun refreshNow() {
        lastAttemptAt = System.currentTimeMillis()
        val c = configStore.get().photos
        val url = c.albumUrl
        if (url.isNullOrBlank()) {
            lastUrl = null
            photos = emptyList()
            state = PhotoState()
            return
        }
        if (url != lastUrl) photos = emptyList()
        lastUrl = url
        try {
            val token = tokenOf(url) ?: error("共有アルバムの URL の形ではありません（https://www.icloud.com/sharedalbum/#B0… の形）")
            val (name, list) = withContext(Dispatchers.IO) { load(token) }
            photos = list
            state = PhotoState(name, list.size, System.currentTimeMillis(), if (list.isEmpty()) "アルバムに写真がありません" else null)
        } catch (e: Exception) {
            Log.w(TAG, "共有アルバムの取得に失敗", e)
            state = state.copy(fetchedAt = System.currentTimeMillis(), lastError = e.message ?: e::class.java.simpleName)
        }
    }

    private fun load(token: String): Pair<String?, List<Photo>> {
        var base = baseUrl(token)
        var stream = post(base + "webstream", """{"streamCtag":null}""")
        if (stream.first == 330) {
            val host = (Http.json.parseToJsonElement(stream.second).jsonObject["X-Apple-MMe-Host"] as? JsonPrimitive)?.contentOrNull
                ?: error("アルバムの置き場を決められません")
            base = "https://$host/$token/sharedstreams/"
            stream = post(base + "webstream", """{"streamCtag":null}""")
        }
        when (stream.first) {
            200 -> Unit
            404 -> error("アルバムが見つかりません。共有アルバムの「公開 Web サイト」が ON か確かめてください")
            else -> error("iCloud が ${stream.first} を返しました")
        }
        val root = Http.json.parseToJsonElement(stream.second).jsonObject
        val name = (root["streamName"] as? JsonPrimitive)?.contentOrNull
        // 写真ごとに、画面に出すのにちょうどよい大きさ（長辺 2048 前後）の画像を 1 つ選ぶ。動画は表紙の画像
        val picks = (root["photos"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val guid = o.text("photoGuid") ?: return@mapNotNull null
            val derivatives = (o["derivatives"] as? JsonObject) ?: return@mapNotNull null
            val sizes = derivatives.mapNotNull { (key, v) ->
                val d = v as? JsonObject ?: return@mapNotNull null
                val sum = d.text("checksum") ?: return@mapNotNull null
                Triple(key, sum, (d.text("width")?.toIntOrNull() ?: 0) to (d.text("height")?.toIntOrNull() ?: 0))
            }
            val video = o.text("mediaAssetType") == "video"
            val images = if (video) sizes.filter { it.first == "PosterFrame" } else sizes.filter { it.first.toIntOrNull() != null }
            val best = images.filter { maxOf(it.third.first, it.third.second) <= MAX_SIDE }.maxByOrNull { it.third.first * it.third.second }
                ?: images.minByOrNull { it.third.first * it.third.second }
                ?: return@mapNotNull null
            Pick(guid, best.second, best.third.first, best.third.second, o.text("caption"), o.text("dateCreated"))
        }
        val urls = HashMap<String, String>()
        picks.chunked(25).forEach { chunk ->
            val body = buildJsonObject { put("photoGuids", buildJsonArray { chunk.forEach { add(JsonPrimitive(it.guid)) } }) }.toString()
            val res = post(base + "webasseturls", body)
            if (res.first != 200) error("画像の URL を取得できません（${res.first}）")
            val items = Http.json.parseToJsonElement(res.second).jsonObject["items"] as? JsonObject ?: return@forEach
            items.forEach { (sum, v) ->
                val o = v as? JsonObject ?: return@forEach
                val location = o.text("url_location") ?: return@forEach
                val path = o.text("url_path") ?: return@forEach
                urls[sum] = "https://$location$path"
            }
        }
        // アルバムの一覧は古い順に来る。新しい順にしておく（順番どおりに出すときは新しい写真から）
        val list = picks.mapNotNull { p -> urls[p.checksum]?.let { Photo(p.guid, it, p.width, p.height, p.caption, p.takenAt) } }.reversed()
        return name to list
    }

    private fun post(url: String, body: String): Pair<Int, String> {
        val request = Request.Builder().url(url)
            .header("Origin", "https://www.icloud.com")
            .header("Referer", "https://www.icloud.com/sharedalbum/")
            .post(body.toRequestBody("text/plain".toMediaType()))
            .build()
        return http.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private data class Pick(val guid: String, val checksum: String, val width: Int, val height: Int, val caption: String?, val takenAt: String?)

    companion object {
        private const val TAG = "PhotoRepository"
        private const val INTERVAL_MS = 30 * 60_000L
        /** 画面（1280 x 800 程度）に出すのに十分な大きさ。これより大きい元の画像は取らない。 */
        private const val MAX_SIDE = 2100

        /** 切り替えの間隔として選べる秒数。 */
        val INTERVALS = listOf(10, 30, 60, 300, 600, 1800, 3600, 10800, 86400)

        /**
         * 共有アルバムの URL（https://www.icloud.com/sharedalbum/#B0aB…、言語の入った /sharedalbum/ja-jp/#… も）から記号を取り出す。
         * 記号だけを貼られても受け付ける。
         */
        fun tokenOf(url: String): String? {
            val raw = url.trim().substringAfterLast('#').substringBefore(';').trim()
            return raw.takeIf { it.length in 10..40 && it.all { c -> c.isLetterOrDigit() } }
        }

        /** 記号の 2〜3 文字目（62 進数）がアルバムの置き場の番号（p01〜）。 */
        private fun baseUrl(token: String): String {
            val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            fun base62(s: String) = s.fold(0) { acc, c -> acc * 62 + chars.indexOf(c) }
            val partition = if (token[0] == 'A') base62(token.substring(1, 2)) else base62(token.substring(1, 3))
            return "https://p%02d-sharedstreams.icloud.com/$token/sharedstreams/".format(partition)
        }
    }
}
