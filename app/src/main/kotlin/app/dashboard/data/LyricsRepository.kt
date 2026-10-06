package app.dashboard.data

import app.dashboard.i18n.L
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.abs

/**
 * Spotify の全画面に出す歌詞。
 *
 * Spotify の Web API は歌詞を返さない（アプリの歌詞は Musixmatch の非公開の経路）。
 * ここでは有志の歌詞データベース LRCLIB（lrclib.net、登録不要・無料）から、曲名・アーティスト名・アルバム名・長さで探す。
 * 時刻付きの歌詞（LRC）があればそれを、無ければ時刻の無い歌詞を使う。
 * 1 曲ごとに 1 回だけ探し、結果（見つからなかったことも）を覚えておく。
 *
 * LRCLIB はよく 503（ServerOverloaded）を返す。`/api/get` は登録の無い曲で 404 の代わりに 503 になることが多く、
 * 短い間に続けて呼んだときも 503 になる。そこで、混雑は間を空けて試し直し（[fetch]）、`/api/get` がだめなら
 * 手元の登録だけを見る `/api/get-cached` で確かめ、1 つの探し方が失敗しても残りの探し方を続ける（[lrclib]）。
 *
 * LRCLIB に時刻付きの歌詞が無い・LRCLIB が使えないときは、2 つ目の取得先として NetEase Cloud Music（網易雲音楽）の
 * 公開の検索と歌詞の API を見る（[netease]。非公式、登録不要。日本の曲の時刻付きの歌詞が LRCLIB より多い）。
 * 見つけた歌詞は端末に保存し（`filesDir/lyrics/`、[MAX_SAVED] 曲まで）、次からは通信なしで出す。
 * どちらの取得先も使えないときは、保存してある歌詞を使う。
 * 保存は設定（[saveEnabled]、Spotify の「歌詞を端末に保存する」）で切れる。切っている間は保存も、保存した歌詞を読むこともしない。
 */
class LyricsRepository(
    context: Context,
    private val client: HttpClient,
    private val saveEnabled: () -> Boolean = { true },
) {

    /** 歌詞 1 行。[timeMs] は曲の頭からの時刻（時刻の無い歌詞では null）。 */
    @Serializable
    data class Line(val timeMs: Long? = null, val text: String)

    /**
     * [synced] は歌詞の提供元が時刻を付けているか。[estimated] は時刻の無い歌詞に、曲の長さから目安の時刻を振ったもの
     * （画面では同じように流して動かすが、位置は目安）。[source] は取得先の名前（画面の出典に出す）。
     */
    @Serializable
    data class Lyrics(
        val lines: List<Line>,
        val synced: Boolean,
        val instrumental: Boolean = false,
        val estimated: Boolean = false,
        val source: String = LRCLIB,
    )

    private val dir = File(context.filesDir, "lyrics")

    /** 歌詞を取得できなかった（混雑・通信の失敗）。[message] は画面にそのまま出す短い説明。 */
    class Unavailable(message: String, cause: Throwable) : Exception(message, cause)

    /** [until] は覚えておく期限（ミリ秒）。探している途中で失敗があった結果は、取りこぼしがあり得るので期限を付ける。 */
    private class Entry(val lyrics: Lyrics?, val until: Long)

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 30
    }

    /** 見つからなければ null。どの取得先でも確かめられなかったときは [Unavailable]（覚えずに、次にまた探す）。 */
    suspend fun find(track: String, artist: String?, album: String?, durationMs: Long?): Lyrics? {
        val key = listOf(track, artist, album, durationMs?.div(1000)).joinToString("|")
        synchronized(cache) {
            cache[key]?.let { if (System.currentTimeMillis() < it.until) return it.lyrics else cache.remove(key) }
        }
        // 保存してある歌詞が時刻付きなら、通信しない。時刻の無い歌詞は、時刻付きが後から登録されることがあるので探し直す
        val saved = if (saveEnabled()) load(key) else null
        if (saved != null && (saved.synced || saved.instrumental)) {
            synchronized(cache) { cache[key] = Entry(saved, Long.MAX_VALUE) }
            return saved
        }
        var failure: Exception? = null
        var found = lrclib(track, artist, album, durationMs) { if (failure == null) failure = it }
        if (found == null || !(found.synced || found.instrumental)) {
            val other = try {
                netease(track, artist, durationMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failure == null) failure = e
                null
            }
            if (other != null && (other.synced || found == null)) found = other
        }
        found = found?.let { if (it.synced || it.instrumental) it else estimate(it, durationMs) }
        if (found != null) { if (saveEnabled()) save(key, found) } else found = saved
        failure?.let { if (found == null) throw Unavailable(describe(it), it) }
        val until = if (failure == null) Long.MAX_VALUE else System.currentTimeMillis() + RECHECK_MS
        synchronized(cache) { cache[key] = Entry(found, until) }
        return found
    }

    /** LRCLIB。失敗した探し方は [failed] に伝えて、残りの探し方を続ける。 */
    private suspend fun lrclib(track: String, artist: String?, album: String?, durationMs: Long?, failed: (Exception) -> Unit): Lyrics? {
        // Spotify は共作のアーティストを「A, B」とつなげて返すが、LRCLIB は主なアーティストだけで登録されていることが多い
        val main = artist?.split(", ", " & ", " feat. ", " x ")?.first()?.trim()?.takeIf { it.isNotEmpty() && it != artist }
        // 時刻付きの歌詞が見つかるまで順に探し、どこにも無ければ最初に見つかった時刻の無い歌詞を使う
        val attempts = listOfNotNull<suspend () -> Lyrics?>(
            { exact(track, artist, album, durationMs) },
            { search(track, artist, durationMs) },
            main?.let { a -> { exact(track, a, album, durationMs) } },
            main?.let { a -> { search(track, a, durationMs) } },
        )
        var found: Lyrics? = null
        for (attempt in attempts) {
            // 1 つの探し方が失敗しても、残りの探し方で見つかることがあるので続ける
            val lyrics = try {
                attempt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
                null
            } ?: continue
            if (lyrics.synced || lyrics.instrumental) return lyrics
            if (found == null) found = lyrics
        }
        return found
    }

    /**
     * NetEase Cloud Music。曲名（「feat.」などの付け足しを除く）と先頭のアーティスト名で検索し、
     * 曲名が同じ・長さが近い・アーティストが 1 人でも同じ曲の歌詞（LRC）を順に読む。
     */
    private suspend fun netease(track: String, artist: String?, durationMs: Long?): Lyrics? {
        val title = track.replace(FEAT_PAREN, "").replace(FEAT_TAIL, "").trim().ifEmpty { track }
        val artists = artist?.split(", ", " & ")?.map(::norm)?.filter { it.isNotEmpty() }.orEmpty()
        val headers: HttpRequestBuilder.() -> Unit = { header("Referer", "https://music.163.com/") }
        val body = fetch("$NETEASE/cloudsearch/pc", BROWSER_AGENT) {
            headers()
            parameter("s", listOfNotNull(title, artist?.split(", ")?.first()).joinToString(" "))
            parameter("type", 1)
            parameter("limit", 10)
        } ?: return null
        val songs = (((Http.json.parseToJsonElement(body) as? JsonObject)?.get("result") as? JsonObject)?.get("songs") as? JsonArray)
            .orEmpty().mapNotNull { it as? JsonObject }
        val wanted = norm(title)
        val candidates = songs
            .filter { o -> o.text("name")?.let { norm(it.replace(FEAT_PAREN, "").replace(FEAT_TAIL, "")) } == wanted }
            .filter { o -> durationMs == null || (o.num("dt")?.let { abs(it - durationMs) <= 4000 } ?: false) }
            .filter { o ->
                val names = (o["ar"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("name")?.let(::norm) }
                artists.isEmpty() || names.any { n -> artists.any { a -> a == n || a.contains(n) || n.contains(a) } }
            }
            .sortedBy { o -> durationMs?.let { abs((o.num("dt") ?: 0.0) - it) } ?: 0.0 }
            .take(4)
        var plain: Lyrics? = null
        for (song in candidates) {
            val id = song.num("id")?.toLong() ?: continue
            val text = fetch("$NETEASE/song/lyric", BROWSER_AGENT) {
                headers()
                parameter("id", id)
                parameter("lv", 1)
                parameter("kv", 1)
                parameter("tv", -1)
            }?.let { ((Http.json.parseToJsonElement(it) as? JsonObject)?.get("lrc") as? JsonObject)?.text("lyric") } ?: continue
            // 先頭に「作词 : …」「作曲 : …」の行が付くので除く
            val timed = parseLrc(text).filterNot { CREDIT.containsMatchIn(it.text) }.dropWhile { it.text.isEmpty() }
            if (timed.any { it.text.isNotEmpty() }) return Lyrics(timed, synced = true, source = NETEASE_NAME)
            if (plain == null && STAMP.find(text) == null) {
                val lines = text.lines().map { Line(null, it.trim()) }.filterNot { CREDIT.containsMatchIn(it.text) }.dropWhile { it.text.isEmpty() }
                if (lines.any { it.text.isNotEmpty() }) plain = Lyrics(lines, synced = false, source = NETEASE_NAME)
            }
        }
        return plain
    }

    /** 書き方の違い（大文字小文字・全角半角・記号・空白）を無視して比べるための形。 */
    private fun norm(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }

    private fun fileOf(key: String): File =
        File(dir, MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")

    private suspend fun load(key: String): Lyrics? = withContext(Dispatchers.IO) {
        runCatching { Http.json.decodeFromString(Lyrics.serializer(), fileOf(key).readText()) }.getOrNull()
    }

    /** 端末に保存してある歌詞の数。 */
    fun savedCount(): Int = dir.listFiles().orEmpty().count { it.name.endsWith(".json") }

    /** 端末に保存してある歌詞をすべて消す。 */
    fun clearSaved() {
        dir.listFiles().orEmpty().forEach { it.delete() }
        synchronized(cache) { cache.clear() }
    }

    /** 保存する。[MAX_SAVED] 曲を超えたら古いものから消す。 */
    private suspend fun save(key: String, lyrics: Lyrics) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            fileOf(key).writeText(Http.json.encodeToString(Lyrics.serializer(), lyrics))
            val files = dir.listFiles().orEmpty()
            if (files.size > MAX_SAVED) files.sortedBy { it.lastModified() }.take(files.size - MAX_SAVED).forEach { it.delete() }
        }
    }

    /**
     * 取得先を呼ぶ。404 は null。混雑（5xx・429）と通信の失敗は、間を空けて [TRIES] 回まで試す。
     */
    private suspend fun fetch(url: String, agent: String, params: HttpRequestBuilder.() -> Unit): String? {
        var last: Exception? = null
        for (i in 0 until TRIES) {
            if (i > 0) delay(RETRY_WAIT_MS * i)
            try {
                return client.get(url) {
                    header("User-Agent", agent)
                    params()
                }.bodyAsText()
            } catch (e: ClientRequestException) {
                when (e.response.status.value) {
                    404 -> return null
                    429 -> last = e
                    else -> throw e
                }
            } catch (e: ServerResponseException) {
                last = e
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IllegalStateException()
    }

    private fun describe(e: Exception): String = when (e) {
        is ServerResponseException -> L("歌詞のサーバーが混み合っています（${e.response.status.value}）", "The lyrics server is busy (${e.response.status.value})")
        is ClientRequestException -> L("歌詞のサーバーが混み合っています（${e.response.status.value}）", "The lyrics server is busy (${e.response.status.value})")
        else -> L("歌詞のサーバーにつながりません", "Can't reach the lyrics server")
    }

    private suspend fun exact(track: String, artist: String?, album: String?, durationMs: Long?): Lyrics? {
        if (artist == null || album == null || durationMs == null) return null
        val params: HttpRequestBuilder.() -> Unit = {
            parameter("track_name", track)
            parameter("artist_name", artist)
            parameter("album_name", album)
            parameter("duration", durationMs / 1000)
        }
        val body = try {
            fetch("$BASE/get", USER_AGENT, params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 登録の無い曲では /get が 404 ではなく 503 を返し続けることが多い。手元の登録だけを見る /get-cached で確かめる
            fetch("$BASE/get-cached", USER_AGENT, params)
        } ?: return null
        return (Http.json.parseToJsonElement(body) as? JsonObject)?.let(::toLyrics)
    }

    /** 名前の書き方がアルバムと少し違う登録も拾う。長さの近いものから、時刻付きを優先して選ぶ。 */
    private suspend fun search(track: String, artist: String?, durationMs: Long?): Lyrics? {
        val body = fetch("$BASE/search", USER_AGENT) {
            parameter("track_name", track)
            if (artist != null) parameter("artist_name", artist)
        } ?: return null
        val items = (Http.json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val seconds = durationMs?.div(1000.0)
        return items
            .filter { o -> seconds == null || (o.num("duration")?.let { abs(it - seconds) <= 4 } ?: false) }
            .sortedWith(compareBy({ it.text("syncedLyrics") == null }, { o -> seconds?.let { abs((o.num("duration") ?: 0.0) - it) } ?: 0.0 }))
            .firstNotNullOfOrNull(::toLyrics)
    }

    /**
     * 時刻の無い歌詞に、目安の時刻を振る（画面で流して動かすため）。
     * 曲の頭の 8% と終わりの 6% は前奏・後奏として空け、その間に行を文字数に比例して並べる（空行は短い間として数える）。
     * 曲の長さが分からなければ時刻を振らない。
     */
    private fun estimate(lyrics: Lyrics, durationMs: Long?): Lyrics {
        val total = durationMs?.takeIf { it > 0 } ?: return lyrics
        val lines = lyrics.lines.dropLastWhile { it.text.isEmpty() }
        if (lines.isEmpty()) return lyrics
        val weights = lines.map { if (it.text.isEmpty()) 4.0 else 6.0 + it.text.length }
        val start = total * 0.08
        val span = total * 0.86
        val sum = weights.sum()
        var t = start
        val timed = lines.mapIndexed { i, line ->
            Line(t.toLong(), line.text).also { t += span * weights[i] / sum }
        }
        return lyrics.copy(lines = timed, estimated = true)
    }

    private fun toLyrics(o: JsonObject): Lyrics? {
        if ((o["instrumental"] as? JsonPrimitive)?.booleanOrNull == true) return Lyrics(emptyList(), synced = false, instrumental = true)
        o.text("syncedLyrics")?.let(::parseLrc)?.takeIf { it.isNotEmpty() }?.let { return Lyrics(it, synced = true) }
        val plain = o.text("plainLyrics")?.lines()?.map { Line(null, it.trim()) }?.dropWhile { it.text.isEmpty() }
        return plain?.takeIf { it.any { l -> l.text.isNotEmpty() } }?.let { Lyrics(it, synced = false) }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

    companion object {
        const val LRCLIB = "LRCLIB"
        private const val NETEASE_NAME = "NetEase Cloud Music"
        private const val BASE = "https://lrclib.net/api"
        private const val NETEASE = "https://music.163.com/api"
        private const val BROWSER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        const val MAX_SAVED = 300
        private const val USER_AGENT = "Dashboard/0.1 (wall dashboard for Android)"
        private const val TRIES = 3
        private const val RETRY_WAIT_MS = 900L
        private const val RECHECK_MS = 10 * 60_000L
        private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")

        // 「曲名 (feat. A & B)」「曲名 - feat. A」の付け足し
        private val FEAT_PAREN = Regex("""\s*[(\[（【][^)\]）】]*\b(?:feat|ft|featuring|with|prod)\b[^)\]）】]*[)\]）】]""", RegexOption.IGNORE_CASE)
        private val FEAT_TAIL = Regex("""\s+-\s+(?:feat|ft|featuring|with)\b.*$""", RegexOption.IGNORE_CASE)

        // NetEase の歌詞の先頭に付く、作詞・作曲などの行
        private val CREDIT = Regex("""^\s*(?:作词|作詞|作曲|编曲|編曲|制作人|制作|混音|母带|录音|监制|出品|和声|发行|企划)\s*[:：]""")

        /** "[01:23.45] 歌詞" の行を読む。1 行に時刻が複数付いていれば、それぞれの時刻に同じ歌詞を置く。 */
        fun parseLrc(text: String): List<Line> = text.lines().flatMap { raw ->
            val stamps = STAMP.findAll(raw).toList()
            if (stamps.isEmpty() || stamps.first().range.first != 0) return@flatMap emptyList()
            val words = raw.substring(stamps.last().range.last + 1).trim()
            stamps.map { m ->
                val (min, sec, frac) = m.destructured
                val ms = when (frac.length) {
                    0 -> 0
                    1 -> frac.toInt() * 100
                    2 -> frac.toInt() * 10
                    else -> frac.take(3).toInt()
                }
                Line(min.toLong() * 60_000 + sec.toLong() * 1000 + ms, words)
            }
        }.sortedBy { it.timeMs }
    }
}
