package app.dashboard.data

import app.dashboard.i18n.L
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
 *
 * 2026 年からの新しい共有アルバム（https://photos.icloud.com/shared/album/01b… の URL）は sharedstreams に無く、
 * CloudKit（ckdatabasews）から読む。[loadCloudKit] を参照。
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
            val key = cloudKeyOf(url)
            val token = if (key == null) tokenOf(url) ?: error(
                L("共有アルバムの URL の形ではありません（https://photos.icloud.com/shared/album/… か https://www.icloud.com/sharedalbum/#B0… の形）", "Not a shared album URL (expected https://photos.icloud.com/shared/album/… or https://www.icloud.com/sharedalbum/#B0…)")
            ) else null
            val (name, list) = withContext(Dispatchers.IO) { if (key != null) loadCloudKit(key) else load(token!!) }
            photos = list
            state = PhotoState(name, list.size, System.currentTimeMillis(), if (list.isEmpty()) L("アルバムに写真がありません", "The album has no photos") else null)
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
                ?: error(L("アルバムの置き場を決められません", "Couldn't determine where the album is stored"))
            base = "https://$host/$token/sharedstreams/"
            stream = post(base + "webstream", """{"streamCtag":null}""")
        }
        when (stream.first) {
            200 -> Unit
            404 -> error(L("アルバムが見つかりません。共有アルバムの「公開 Web サイト」が ON か確かめてください", "Album not found. Check that \"Public Website\" is on for the shared album"))
            else -> error(L("iCloud が ${stream.first} を返しました", "iCloud returned ${stream.first}"))
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
            if (res.first != 200) error(L("画像の URL を取得できません（${res.first}）", "Couldn't get image URLs (${res.first})"))
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

    /**
     * 新しい共有アルバム（photos.icloud.com/shared/album/{key}）。photos.icloud.com の Web 版と同じ手順で読む。
     *
     * 1. public/records/resolve … key → アルバムの名前、zoneID、匿名で読むためのトークン（20 分有効）と置き場（p50 など）
     * 2. shared/records/query（CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted）… 写真ごとに CPLMaster（元の画像）と CPLAsset（日付・編集後の画像など）が対で来る。
     *    startRank から古い順に読み、来た CPLAsset の数だけ startRank を進める
     *
     * 画像の URL（downloadURL）は署名付きで、`${f}` をファイル名に置き換えて開く。
     */
    private fun loadCloudKit(key: String): Pair<String?, List<Photo>> {
        val resolve = post(
            "https://ckdatabasews.icloud.com/database/1/com.apple.photos.cloud/production/public/records/resolve?remapEnums=true&getCurrentSyncToken=true&sharing_url_key=$key",
            buildJsonObject { put("shortGUIDs", buildJsonArray { add(buildJsonObject { put("value", key) }) }) }.toString(),
            CK_ORIGIN,
        )
        if (resolve.first == 404) error(L("アルバムが見つかりません。共有アルバムの「公開 Web サイト」が ON か確かめてください", "Album not found. Check that \"Public Website\" is on for the shared album"))
        if (resolve.first != 200) error(L("iCloud が ${resolve.first} を返しました", "iCloud returned ${resolve.first}"))
        val result = (Http.json.parseToJsonElement(resolve.second).jsonObject["results"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: error(L("アルバムを読めません", "Couldn't read the album"))
        val access = result["anonymousPublicAccess"] as? JsonObject
            ?: error(L("アルバムが公開されていません。共有アルバムの「公開 Web サイト」が ON か確かめてください", "The album isn't public. Check that \"Public Website\" is on for the shared album"))
        val token = access.text("token") ?: error(L("アルバムを読むためのトークンがありません", "No token to read the album"))
        val partition = access.text("databasePartition")?.removeSuffix(":443") ?: "https://ckdatabasews.icloud.com"
        val zoneID = result["zoneID"] as? JsonObject ?: error(L("アルバムの置き場を決められません", "Couldn't determine where the album is stored"))
        val name = ((result["share"] as? JsonObject)?.get("fields") as? JsonObject)?.field("cloudkit.title")?.text("value")
        val queryUrl = "$partition/database/1/com.apple.photos.cloud/production/shared/records/query?remapEnums=true&getCurrentSyncToken=true" +
            "&sharing_url_key=$key&publicAccessAuthToken=${java.net.URLEncoder.encode(token, "UTF-8")}"

        val masters = HashMap<String, JsonObject>()
        val assets = ArrayList<Pair<String, JsonObject>>()
        var rank = 0
        while (assets.size < MAX_PHOTOS) {
            val body = buildJsonObject {
                put("query", buildJsonObject {
                    put("recordType", "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted")
                    put("filterBy", buildJsonArray {
                        add(buildJsonObject {
                            put("fieldName", "startRank"); put("comparator", "EQUALS")
                            put("fieldValue", buildJsonObject { put("type", "INT64"); put("value", rank) })
                        })
                        add(buildJsonObject {
                            put("fieldName", "direction"); put("comparator", "EQUALS")
                            put("fieldValue", buildJsonObject { put("type", "STRING"); put("value", "ASCENDING") })
                        })
                    })
                })
                put("zoneID", zoneID)
                put("resultsLimit", PAGE)
            }.toString()
            val res = post(queryUrl, body, CK_ORIGIN)
            if (res.first != 200) error(L("写真の一覧を取得できません（${res.first}）", "Couldn't get the photo list (${res.first})"))
            val records = (Http.json.parseToJsonElement(res.second).jsonObject["records"] as? JsonArray).orEmpty()
            var added = 0
            records.forEach { e ->
                val r = e as? JsonObject ?: return@forEach
                val id = r.text("recordName") ?: return@forEach
                val fields = r["fields"] as? JsonObject ?: return@forEach
                when (r.text("recordType")) {
                    "CPLMaster" -> masters[id] = fields
                    "CPLAsset" -> { assets += id to fields; added++ }
                }
            }
            if (added == 0) break
            rank += added
        }

        // 写真ごとに、画面に出すのにちょうどよい大きさの JPEG を 1 つ選ぶ。編集した写真は CPLAsset の resJPEGFullRes が編集後の画像
        val list = assets.mapNotNull { (id, a) ->
            val masterId = (a.field("masterRef")?.get("value") as? JsonObject)?.text("recordName")
            val m = masterId?.let { masters[it] } ?: return@mapNotNull null
            val candidates = listOf(a to "resJPEGFull", m to "resJPEGFull", m to "resJPEGMed", m to "resJPEGThumb").mapNotNull { (f, res) ->
                val u = (f.field("${res}Res")?.get("value") as? JsonObject)?.text("downloadURL") ?: return@mapNotNull null
                val w = f.field("${res}Width")?.text("value")?.toIntOrNull() ?: 0
                val h = f.field("${res}Height")?.text("value")?.toIntOrNull() ?: 0
                Triple(u.replace("\${f}", "photo.jpg"), w, h)
            }
            val best = candidates.filter { maxOf(it.second, it.third) <= MAX_SIDE }.maxByOrNull { it.second * it.third }
                ?: candidates.minByOrNull { it.second * it.third }
                ?: return@mapNotNull null
            val takenAt = a.field("assetDate")?.text("value")?.toLongOrNull()?.let { java.time.Instant.ofEpochMilli(it).toString() }
            Photo(id, best.first, best.second, best.third, a.field("captionEnc")?.text("value")?.let(::decodeCaption), takenAt)
        }.reversed()
        return name to list
    }

    /** captionEnc は UTF-8 の文字を Base64 にしたもの。plist（bplist）で来たら読まない。 */
    private fun decodeCaption(b64: String): String? = runCatching {
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        String(bytes, Charsets.UTF_8).takeIf { !it.startsWith("bplist") && it.isNotBlank() }
    }.getOrNull()

    private fun JsonObject.field(key: String): JsonObject? = this[key] as? JsonObject

    private fun post(url: String, body: String, origin: String = "https://www.icloud.com"): Pair<Int, String> {
        val request = Request.Builder().url(url)
            .header("Origin", origin)
            .header("Referer", "$origin/")
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
        private const val CK_ORIGIN = "https://photos.icloud.com"
        /** CloudKit の 1 回の問い合わせで読む数と、読む写真の上限。 */
        private const val PAGE = 200
        private const val MAX_PHOTOS = 5000

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

        /**
         * 新しい共有アルバムの URL（https://photos.icloud.com/shared/album/01b…、https://share.icloud.com/photos/01b… も）から key を取り出す。
         * key だけ（0 で始まる）を貼られても受け付ける。古い形（#B0…）なら null。
         */
        fun cloudKeyOf(url: String): String? {
            val s = url.trim()
            Regex("""(?:/shared/album/|share\.icloud\.com/photos/)([A-Za-z0-9_-]{10,40})""").find(s)?.let { return it.groupValues[1] }
            return s.takeIf { it.startsWith("0") && it.length in 20..40 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
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
