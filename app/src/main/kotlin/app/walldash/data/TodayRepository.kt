package app.walldash.data

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate

/**
 * 今日は何の日。日本語版 Wikipedia の日付のページ（例: 9月26日）の「記念日・年中行事」と「できごと」を読む。
 *
 * 1 日 1 回だけ取り、filesDir/today-cache.json に置く。記事は CC BY-SA なので、画面のフッターに出典を出す。
 */
class TodayRepository(context: Context, private val client: HttpClient) {

    private val cacheFile = File(context.filesDir, "today-cache.json")

    @Volatile
    var state: TodayState = runCatching {
        if (cacheFile.exists()) Http.json.decodeFromString<TodayState>(cacheFile.readText()) else TodayState()
    }.getOrDefault(TodayState())
        private set

    private var lastAttemptAt = 0L

    suspend fun refreshIfDue(wanted: Boolean) {
        if (!wanted) return
        if (state.date == LocalDate.now().toString() && state.lastError == null) return
        if (System.currentTimeMillis() - lastAttemptAt < RETRY_MS) return
        refreshNow()
    }

    suspend fun refreshNow() {
        lastAttemptAt = System.currentTimeMillis()
        val today = LocalDate.now()
        val page = "${today.monthValue}月${today.dayOfMonth}日"
        try {
            val sections = get<SectionsResponse>(page, prop = "sections").parse.sections
            fun section(name: String) = sections.firstOrNull { it.line.trim() == name }?.index
            val days = section("記念日・年中行事")?.let { parseDays(get<WikitextResponse>(page, "wikitext", it).parse.wikitext) }.orEmpty()
            val events = section("できごと")?.let { parseEvents(get<WikitextResponse>(page, "wikitext", it).parse.wikitext) }.orEmpty()
            if (days.isEmpty() && events.isEmpty()) error("記念日を読み取れませんでした")
            state = TodayState(today.toString(), days, events, System.currentTimeMillis(), null)
            runCatching { cacheFile.writeText(Http.json.encodeToString(TodayState.serializer(), state)) }
        } catch (e: Exception) {
            Log.w(TAG, "今日は何の日の取得に失敗", e)
            state = state.copy(lastError = e.message ?: e::class.java.simpleName)
        }
    }

    private suspend inline fun <reified T> get(page: String, prop: String, section: String? = null): T =
        client.get("https://ja.wikipedia.org/w/api.php") {
            // Wikipedia は連絡先の分かる User-Agent を求めている（無いと弾かれることがある）
            header("User-Agent", USER_AGENT)
            parameter("action", "parse")
            parameter("page", page)
            parameter("prop", prop)
            section?.let { parameter("section", it) }
            parameter("format", "json")
            parameter("formatversion", "2")
            parameter("redirects", "1")
        }.body()

    @Serializable
    private data class SectionsResponse(val parse: Sections)

    @Serializable
    private data class Sections(val sections: List<Section> = emptyList())

    @Serializable
    private data class Section(val index: String, val line: String)

    @Serializable
    private data class WikitextResponse(val parse: Wikitext)

    @Serializable
    private data class Wikitext(val wikitext: String = "")

    companion object {
        private const val TAG = "TodayRepository"
        private const val RETRY_MS = 3600_000L
        private const val USER_AGENT = "Walldash/1.0 (self-hosted wall dashboard for Android tablets)"

        /** {{JPN}} などの国旗テンプレートを国・地域名に直す。知らないものは捨てる。 */
        private val REGIONS = mapOf(
            "JPN" to "日本", "World" to "世界", "USA" to "アメリカ", "CHN" to "中国", "KOR" to "韓国", "TWN" to "台湾",
            "GBR" to "イギリス", "UK" to "イギリス", "FRA" to "フランス", "DEU" to "ドイツ", "GER" to "ドイツ", "ITA" to "イタリア",
            "RUS" to "ロシア", "IND" to "インド", "EUR" to "ヨーロッパ", "EU" to "EU", "AUS" to "オーストラリア", "CAN" to "カナダ",
            "BRA" to "ブラジル", "MEX" to "メキシコ", "ESP" to "スペイン", "NLD" to "オランダ", "PRK" to "北朝鮮",
        )

        /**
         * 「* ワープロ記念日（{{JPN}}）」の行を名前と国に、続く「*: …」を説明にする。
         * 日本のものを先に並べる（壁に出せる数が限られるため）。
         */
        internal fun parseDays(wikitext: String): List<TodayItem> {
            val items = mutableListOf<TodayItem>()
            wikitext.lines().forEach { raw ->
                val line = raw.trimEnd()
                when {
                    line.startsWith("*:") || line.startsWith("**") -> {
                        val last = items.removeLastOrNull() ?: return@forEach
                        val text = clean(line.trimStart('*', ':', ' '))
                        items += if (last.note == null && text.isNotEmpty()) last.copy(note = text) else last
                    }
                    line.startsWith("*") -> {
                        val body = line.trimStart('*', ' ')
                        val region = Regex("\\{\\{([A-Za-z]+)}}").find(body)?.groupValues?.get(1)?.let { REGIONS[it] }
                        // 名前は「（{{JPN}}、このころ）」の手前まで
                        val name = clean(body.substringBefore("（{{").substringBefore("({{"))
                        if (name.isNotEmpty()) items += TodayItem(name, region)
                    }
                }
            }
            return items.sortedBy { if (it.region == "日本") 0 else 1 }
        }

        /** 「* [[1978年]] - 東芝が…」のうち、年とできごとの両方が読めるものだけ。 */
        internal fun parseEvents(wikitext: String): List<String> = wikitext.lines()
            .filter { it.startsWith("* ") || it.startsWith("*[[") }
            .map { clean(it.trimStart('*', ' ')) }
            .filter { Regex("^(紀元前)?\\d{1,4}年").containsMatchIn(it) && " - " in it }

        /** ウィキ記法を地の文にする（リンク・テンプレート・脚注・強調・HTML を落とす）。 */
        internal fun clean(text: String): String {
            var s = text
            s = s.replace(Regex("<ref[^>/]*/>"), "")
            s = s.replace(Regex("<ref[^>]*>.*?</ref>"), "")
            s = s.replace(Regex("<ref[^>]*>.*$"), "")
            s = s.replace(Regex("\\[\\[(?:File|ファイル|画像|Image):[^]]*(\\[\\[[^]]*]][^]]*)*]]"), "")
            // テンプレートは入れ子になり得るので内側から消す
            repeat(4) { s = s.replace(Regex("\\{\\{[^{}]*}}"), "") }
            s = s.replace(Regex("\\[\\[[^]|]*\\|([^]]*)]]"), "$1")
            s = s.replace(Regex("\\[\\[([^]]*)]]"), "$1")
            s = s.replace(Regex("\\[https?://\\S+ ([^]]*)]"), "$1")
            s = s.replace(Regex("'{2,}"), "")
            s = s.replace(Regex("<[^>]+>"), "")
            s = s.replace("&nbsp;", " ")
            s = s.replace(Regex("（\\s*[、，]?\\s*）"), "")
            return s.replace(Regex("\\s+"), " ").trim()
        }
    }
}
