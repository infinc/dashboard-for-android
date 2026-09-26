package app.walldash.data

import android.util.Log
import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 予定表。iCloud のカレンダーを 15 分ごとに読む。
 *
 * - "caldav" … caldav.icloud.com に Apple ID と App 用パスワード（appleid.apple.com で作る 16 文字）で接続する。
 *   繰り返しの予定は iCloud 側で 1 回ずつに展開してもらう（calendar-query の expand）
 * - "ics" … 共有カレンダーの公開 URL（webcal://〜）をそのまま読む。繰り返しは [Ics] が展開する
 */
class CalendarRepository(private val client: HttpClient, private val configStore: ConfigStore) {

    @Volatile
    var state: CalendarState = CalendarState()
        private set

    private var lastAttemptAt = 0L
    private var lastKey: CalendarConfig? = null

    /** 見つけたカレンダー（URL・名前・色）。接続先の設定が変わるまで使い回す。 */
    private var calendars: List<Collection>? = null
    private var calendarsFor: String? = null

    private val dav by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun refreshIfDue() {
        val c = configStore.get().calendar
        if (!c.enabled || !configured(c)) return
        if (c == lastKey && System.currentTimeMillis() - lastAttemptAt < INTERVAL_MS) return
        refreshNow()
    }

    suspend fun refreshNow() {
        lastAttemptAt = System.currentTimeMillis()
        val c = configStore.get().calendar
        lastKey = c
        if (!configured(c)) return
        val zone = ZoneId.systemDefault()
        val from = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = LocalDate.now(zone).plusDays(c.daysAhead.coerceIn(1, 31).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        try {
            val events = if (c.mode == "ics") fromIcs(c.icsUrl!!, from, to, zone) else fromCalDav(c, from, to, zone)
            state = CalendarState(events.take(MAX_EVENTS), System.currentTimeMillis(), null)
        } catch (e: Exception) {
            Log.w(TAG, "予定の取得に失敗", e)
            state = state.copy(fetchedAt = System.currentTimeMillis(), lastError = e.message ?: e::class.java.simpleName)
        }
    }

    private fun configured(c: CalendarConfig) =
        if (c.mode == "ics") !c.icsUrl.isNullOrBlank() else c.appleId.isNotBlank() && !c.password.isNullOrBlank()

    // ------------------------------------------------------------ 公開 URL

    private suspend fun fromIcs(url: String, from: Long, to: Long, zone: ZoneId): List<CalendarEvent> {
        val https = url.trim().replaceFirst(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://")
        val text = client.get(https).bodyAsText()
        if ("BEGIN:VCALENDAR" !in text) error("カレンダーの URL ではないようです（iCalendar の形式ではありません）")
        val name = Regex("X-WR-CALNAME:(.*)").find(text)?.groupValues?.get(1)?.trim()
        return Ics.events(text, from, to, zone, name, null)
    }

    // ------------------------------------------------------------ CalDAV

    private data class Collection(val url: String, val name: String?, val color: String?)

    private suspend fun fromCalDav(c: CalendarConfig, from: Long, to: Long, zone: ZoneId): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val auth = Credentials.basic(c.appleId.trim(), c.password!!.replace(" ", "").trim())
        val key = c.appleId.trim()
        val list = calendars?.takeIf { calendarsFor == key } ?: discover(auth).also { calendars = it; calendarsFor = key }
        if (list.isEmpty()) error("予定を入れられるカレンダーが見つかりませんでした")
        val start = utc(from)
        val end = utc(to)
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:prop><C:calendar-data><C:expand start="$start" end="$end"/></C:calendar-data></D:prop>
              <C:filter><C:comp-filter name="VCALENDAR"><C:comp-filter name="VEVENT">
                <C:time-range start="$start" end="$end"/>
              </C:comp-filter></C:comp-filter></C:filter>
            </C:calendar-query>
        """.trimIndent()
        list.flatMap { cal ->
            val xml = send(cal.url, "REPORT", body, auth, depth = "1")
            texts(xml, "calendar-data").flatMap { Ics.events(it, from, to, zone, cal.name, cal.color) }
        }.sortedWith(compareBy({ it.start }, { !it.allDay }))
    }

    /** caldav.icloud.com → 利用者の principal → calendar-home-set → その下の VEVENT を入れられるカレンダー。 */
    private fun discover(auth: String): List<Collection> {
        val root = "https://caldav.icloud.com/"
        val principalXml = send(root, "PROPFIND", propfind("<D:current-user-principal/>"), auth, depth = "0")
        val principal = hrefIn(principalXml, "current-user-principal") ?: error("iCloud の利用者情報を読めませんでした")
        val principalUrl = resolve(root, principal)
        val homeXml = send(principalUrl, "PROPFIND", propfind("<C:calendar-home-set/>"), auth, depth = "0")
        val home = resolve(principalUrl, hrefIn(homeXml, "calendar-home-set") ?: error("カレンダーの置き場所を読めませんでした"))
        val listXml = send(
            home, "PROPFIND",
            propfind("<D:displayname/><D:resourcetype/><C:supported-calendar-component-set/><A:calendar-color/>"),
            auth, depth = "1",
        )
        return collections(listXml, home)
    }

    private fun propfind(props: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:A="http://apple.com/ns/ical/"><D:prop>$props</D:prop></D:propfind>
    """.trimIndent()

    private fun send(url: String, method: String, body: String, auth: String, depth: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("Depth", depth)
            .method(method, body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        dav.newCall(request).execute().use { res ->
            if (res.code == 401) error("Apple ID か App 用パスワードが違います（通常のパスワードでは接続できません）")
            if (!res.isSuccessful) error("iCloud が HTTP ${res.code} を返しました")
            return res.body?.string().orEmpty()
        }
    }

    private fun resolve(base: String, href: String): String = URI(base).resolve(href.trim()).toString()

    private fun utc(ms: Long) = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC))

    /** [prop] の中の最初の href。 */
    private fun hrefIn(xml: String, prop: String): String? {
        val p = parser(xml)
        var inside = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == prop) inside = true
            if (p.eventType == XmlPullParser.END_TAG && p.name == prop) inside = false
            if (inside && p.eventType == XmlPullParser.START_TAG && p.name == "href") return p.nextText()
        }
        return null
    }

    /** 名前が [tag] の要素の中身をすべて。 */
    private fun texts(xml: String, tag: String): List<String> {
        val p = parser(xml)
        val out = mutableListOf<String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == tag) out += p.nextText()
        }
        return out
    }

    /** PROPFIND（Depth: 1）の応答から、VEVENT を入れられるカレンダーだけを拾う（リマインダーや受信箱は除く）。 */
    private fun collections(xml: String, home: String): List<Collection> {
        val p = parser(xml)
        val out = mutableListOf<Collection>()
        var href: String? = null
        var name: String? = null
        var color: String? = null
        var calendar = false
        var events = false
        var inResourceType = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "response" -> { href = null; name = null; color = null; calendar = false; events = false }
                    "href" -> if (href == null) href = p.nextText()
                    "displayname" -> name = p.nextText()
                    "calendar-color" -> color = p.nextText().trim().take(7).takeIf { it.startsWith("#") }
                    "resourcetype" -> inResourceType = true
                    "calendar" -> if (inResourceType) calendar = true
                    "comp" -> if (p.getAttributeValue(null, "name") == "VEVENT") events = true
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "resourcetype" -> inResourceType = false
                    "response" -> href?.let { if (calendar && events) out += Collection(resolve(home, it), name, color) }
                }
            }
        }
        return out
    }

    private fun parser(xml: String): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(StringReader(xml))
    }

    private companion object {
        const val TAG = "CalendarRepository"
        const val INTERVAL_MS = 900_000L
        const val MAX_EVENTS = 60
    }
}
