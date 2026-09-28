package app.walldash.data

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

/**
 * Spotify の全画面に出す歌詞。
 *
 * Spotify の Web API は歌詞を返さない（アプリの歌詞は Musixmatch の非公開の経路）。
 * ここでは有志の歌詞データベース LRCLIB（lrclib.net、登録不要・無料）から、曲名・アーティスト名・アルバム名・長さで探す。
 * 時刻付きの歌詞（LRC）があればそれを、無ければ時刻の無い歌詞を使う。
 * 1 曲ごとに 1 回だけ探し、結果（見つからなかったことも）を覚えておく。
 */
class LyricsRepository(private val client: HttpClient) {

    /** 歌詞 1 行。[timeMs] は曲の頭からの時刻（時刻の無い歌詞では null）。 */
    data class Line(val timeMs: Long?, val text: String)

    /**
     * [synced] は歌詞の提供元が時刻を付けているか。[estimated] は時刻の無い歌詞に、曲の長さから目安の時刻を振ったもの
     * （画面では同じように流して動かすが、位置は目安）。
     */
    data class Lyrics(val lines: List<Line>, val synced: Boolean, val instrumental: Boolean = false, val estimated: Boolean = false)

    private val cache = object : LinkedHashMap<String, Lyrics?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > 30
    }

    /** 見つからなければ null。通信に失敗したときは例外（覚えずに、次にまた探す）。 */
    suspend fun find(track: String, artist: String?, album: String?, durationMs: Long?): Lyrics? {
        val key = listOf(track, artist, album, durationMs?.div(1000)).joinToString("|")
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
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
            val lyrics = attempt() ?: continue
            if (lyrics.synced || lyrics.instrumental) {
                found = lyrics
                break
            }
            if (found == null) found = lyrics
        }
        found = found?.let { if (it.synced || it.instrumental) it else estimate(it, durationMs) }
        synchronized(cache) { cache[key] = found }
        return found
    }

    private suspend fun exact(track: String, artist: String?, album: String?, durationMs: Long?): Lyrics? {
        if (artist == null || album == null || durationMs == null) return null
        val body = try {
            client.get("$BASE/get") {
                header("User-Agent", USER_AGENT)
                parameter("track_name", track)
                parameter("artist_name", artist)
                parameter("album_name", album)
                parameter("duration", durationMs / 1000)
            }.bodyAsText()
        } catch (e: ClientRequestException) {
            if (e.response.status.value == 404) return null
            throw e
        }
        return (Http.json.parseToJsonElement(body) as? JsonObject)?.let(::toLyrics)
    }

    /** 名前の書き方がアルバムと少し違う登録も拾う。長さの近いものから、時刻付きを優先して選ぶ。 */
    private suspend fun search(track: String, artist: String?, durationMs: Long?): Lyrics? {
        val body = client.get("$BASE/search") {
            header("User-Agent", USER_AGENT)
            parameter("track_name", track)
            if (artist != null) parameter("artist_name", artist)
        }.bodyAsText()
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
        return Lyrics(timed, synced = false, estimated = true)
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
        private const val BASE = "https://lrclib.net/api"
        private const val USER_AGENT = "Walldash/0.1 (wall dashboard for Android)"
        private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

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
