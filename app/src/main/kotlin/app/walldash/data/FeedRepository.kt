package app.walldash.data

import android.util.Log
import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.min

/**
 * 利用者が設定した RSS / Atom フィードの見出しを取得する。
 *
 * 本文は扱わず見出しだけにしている。壁掛けの距離から読めるのは見出しまでで、
 * 本文まで持つとメモリと描画コストに対して得られるものが小さいため。
 */
class FeedRepository(
    private val client: HttpClient,
    private val configStore: ConfigStore,
) {

    @Volatile
    var state: FeedState = FeedState()
        private set

    private var lastAttemptAt = 0L

    suspend fun refreshIfDue() {
        val config = configStore.get().feed
        if (!config.enabled || config.urls.isEmpty()) return
        if (System.currentTimeMillis() - lastAttemptAt < INTERVAL_MS) return
        refreshNow()
    }

    suspend fun refreshNow() {
        lastAttemptAt = System.currentTimeMillis()
        val config = configStore.get().feed
        val perFeed = mutableListOf<List<FeedItem>>()
        val errors = mutableListOf<String>()

        for (url in config.urls.take(MAX_FEEDS)) {
            try {
                // 1 本失敗しても他のフィードは表示し続ける
                perFeed += fetchFeed(url)
            } catch (e: Exception) {
                Log.w(TAG, "フィード取得に失敗: $url", e)
                errors += "${shortHost(url)}: ${e.message ?: "取得失敗"}"
            }
        }

        // 1 本のフィードが一覧を占領しないよう、フィード間で交互に並べる
        val merged = interleave(perFeed).take(config.maxItems.coerceIn(1, MAX_TOTAL))

        state = if (merged.isEmpty()) {
            // 全滅したときは直前の見出しを残す。壁を空白にしない。
            state.copy(lastError = errors.joinToString(" / ").ifEmpty { null })
        } else {
            FeedState(
                items = merged,
                fetchedAt = System.currentTimeMillis(),
                lastError = errors.joinToString(" / ").ifEmpty { null },
            )
        }
    }

    /**
     * URL を取得する。RSS/Atom ならそのまま解析し、HTML なら
     * `<link rel="alternate" type="application/rss+xml">` を辿ってフィードを探す。
     *
     * 利用者はサイトのトップページ URL を貼りがちで（実際そうなった）、
     * そのたびに「これはフィードではない」と突き返すより自動で解決した方が親切なため。
     */
    private suspend fun fetchFeed(url: String): List<FeedItem> {
        val body = client.get(url).bodyAsText()
        if (looksLikeFeed(body)) return parse(body)

        val discovered = discoverFeedUrl(body, url)
            ?: throw IllegalStateException("RSS ではなく HTML ページのようです。フィードの URL を指定してください")
        Log.i(TAG, "フィードを自動検出: $url -> $discovered")
        return parse(client.get(discovered).bodyAsText())
    }

    private fun looksLikeFeed(body: String): Boolean {
        val head = body.take(1500).lowercase()
        return head.contains("<rss") || head.contains("<feed") || head.contains("<rdf:rdf")
    }

    private fun discoverFeedUrl(html: String, baseUrl: String): String? {
        for (m in LINK_TAG.findAll(html.take(400_000))) {
            val tag = m.value
            if (!tag.contains("rss+xml", true) && !tag.contains("atom+xml", true)) continue
            val href = HREF.find(tag)?.groupValues?.get(1) ?: continue
            return runCatching { java.net.URI(baseUrl).resolve(href).toString() }.getOrElse { href }
        }
        return null
    }

    private fun interleave(lists: List<List<FeedItem>>): List<FeedItem> {
        if (lists.size <= 1) return lists.flatten()
        val out = mutableListOf<FeedItem>()
        val max = lists.maxOf { it.size }
        for (i in 0 until max) for (list in lists) list.getOrNull(i)?.let(out::add)
        return out
    }

    /**
     * RSS 2.0 と Atom の両方を 1 つのパーサで扱う。
     * RSS は item/title、Atom は entry/title に見出しが入る。
     */
    private fun parse(xml: String): List<FeedItem> {
        val items = mutableListOf<FeedItem>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }

        var channelTitle: String? = null
        var inEntry = false
        var title: String? = null
        var published: String? = null
        var depthTag: String? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    depthTag = parser.name.lowercase()
                    if (depthTag == "item" || depthTag == "entry") {
                        inEntry = true; title = null; published = null
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) when (depthTag) {
                        "title" -> if (inEntry) { if (title == null) title = text } else if (channelTitle == null) channelTitle = text
                        "pubdate", "published", "updated" -> if (inEntry && published == null) published = text
                    }
                }

                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    if (name == "item" || name == "entry") {
                        title?.let { items += FeedItem(it, channelTitle, published) }
                        inEntry = false
                    }
                    depthTag = null
                }
            }
            if (items.size >= MAX_ITEMS_PER_FEED) break
        }
        return items
    }

    private fun shortHost(url: String) =
        runCatching { java.net.URI(url).host ?: url }.getOrElse { url }

    private companion object {
        const val TAG = "FeedRepository"
        const val INTERVAL_MS = 15 * 60_000L
        const val MAX_FEEDS = 5
        const val MAX_ITEMS_PER_FEED = 30
        const val MAX_TOTAL = 40
        val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        val HREF = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    }
}
