package app.dashboard.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ConnectException

/** 自作カードの取得・取り込み・通知・保存（MockEngine と仮の LAN・TCP で）。 */
class WidgetRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var config = Config()
    private val notices = mutableListOf<ExternalNotice>()
    private val requests = mutableListOf<String>()
    private var body = "{\"data\":{\"temp\":21.5}}"
    private var status = HttpStatusCode.OK
    private var now = 1_800_000_000_000L

    private val client = HttpClient(MockEngine) {
        install(HttpTimeout)
        engine {
            addHandler { req ->
                requests += req.url.toString() + " auth=" + (req.headers[HttpHeaders.Authorization] ?: "")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
    }

    private fun widget(def: WidgetDef, shown: Boolean = true) {
        config = config.copy(
            widgets = config.widgets.copy(items = WIDGET_IDS.map { if (it == def.id) def else config.widgets.def(it) }),
            display = config.display.copy(showWidget1 = shown),
        )
    }

    /** 取得は Ktor・IO のスレッドで進むので、仮の時計を進めながら本当に終わるまで待つ。 */
    private fun kotlinx.coroutines.test.TestScope.settle(r: WidgetRepository) {
        repeat(400) {
            advanceUntilIdle()
            if (!r.busy) {
                advanceUntilIdle()
                return
            }
            Thread.sleep(5)
        }
        error("取得が終わりません")
    }

    private fun kotlinx.coroutines.test.TestScope.repo(
        lan: (String, Map<String, String>) -> LanHttp.Response = { _, _ -> error("no lan") },
        tcp: (String, Int, Int) -> Unit = { _, _, _ -> },
    ) = WidgetRepository(tmp.root, client, { config }, this, { notices += it }, lan, tcp, { now })

    @Test
    fun `fetches https and keeps history`() = runTest {
        widget(WidgetDef("w1", url = "https://api.example.com/x?lat={lat}", path = "data.temp", unit = "℃", headerName = "Authorization", headerValue = "Bearer s"))
        val r = repo()
        r.refreshIfDue(config.display)
        settle(r)
        val d = r.state.of("w1")!!
        assertEquals(21.5, d.number!!, 1e-9)
        assertEquals("℃", d.unit)
        assertNull(d.lastError)
        assertEquals(1, d.history.size)
        assertTrue(requests.single().startsWith("https://api.example.com/x?lat=35.6895"))
        assertTrue(requests.single().endsWith("auth=Bearer s"))
        // 間隔が来るまで取りに行かない
        r.refreshIfDue(config.display)
        settle(r)
        assertEquals(1, requests.size)
        now += 5 * 60_000L
        body = "{\"data\":{\"temp\":22}}"
        r.refreshIfDue(config.display)
        settle(r)
        assertEquals(2, requests.size)
        assertEquals(listOf(21.5, 22.0), r.state.of("w1")!!.history.map { it.v })
    }

    @Test
    fun `hidden and push cards are not fetched`() = runTest {
        widget(WidgetDef("w1", url = "https://x", path = "a"), shown = false)
        val r = repo()
        r.refreshIfDue(config.display)
        widget(WidgetDef("w1", source = "push"))
        r.refreshIfDue(config.display)
        settle(r)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `errors are kept with the last value and retried sooner`() = runTest {
        widget(WidgetDef("w1", url = "https://x", path = "data.temp", intervalMin = 60))
        val r = repo()
        r.refreshIfDue(config.display)
        settle(r)
        status = HttpStatusCode.ServiceUnavailable
        now += 60 * 60_000L
        r.refreshIfDue(config.display)
        settle(r)
        val d = r.state.of("w1")!!
        assertEquals("HTTP 503", d.lastError)
        assertEquals(21.5, d.number!!, 1e-9)
        // 失敗の後は 2 分で試し直す
        now += 2 * 60_000L
        status = HttpStatusCode.OK
        r.refreshIfDue(config.display)
        settle(r)
        assertNull(r.state.of("w1")!!.lastError)
        assertEquals(3, requests.size)
    }

    @Test
    fun `plain http goes through the lan client only`() = runTest {
        val lanCalls = mutableListOf<String>()
        widget(WidgetDef("w1", url = "http://192.168.1.5:8123/api/states/sensor.t", path = "state", headerName = "Authorization", headerValue = "Bearer h"))
        val r = repo(lan = { url, headers ->
            lanCalls += url + " " + headers["Authorization"]
            LanHttp.Response(200, emptyMap(), "{\"state\":\"19.5\"}")
        })
        r.refreshIfDue(config.display)
        settle(r)
        assertEquals(listOf("http://192.168.1.5:8123/api/states/sensor.t Bearer h"), lanCalls)
        assertTrue(requests.isEmpty())
        assertEquals(19.5, r.state.of("w1")!!.number!!, 1e-9)
    }

    @Test
    fun `changing the source drops old values`() = runTest {
        widget(WidgetDef("w1", url = "https://x", path = "data.temp"))
        val r = repo()
        r.refreshIfDue(config.display)
        settle(r)
        assertNotNull(r.state.of("w1"))
        widget(WidgetDef("w1", url = "https://y", path = "data.temp"))
        r.refreshIfDue(config.display)
        settle(r)
        val d = r.state.of("w1")!!
        assertEquals(config.widgets.def("w1").dataKey, d.key)
        assertEquals(1, d.history.size)
        assertEquals(2, requests.size)
    }

    @Test
    fun `alerts notify on entering and leaving but not on the first value`() = runTest {
        widget(WidgetDef("w1", source = "push", title = "電力", unit = "W", alertAt = 1500.0, notify = true, tone = "chime"))
        val r = repo()
        r.push("w1", "1600")
        assertTrue(notices.isEmpty())
        r.push("w1", "800")
        assertEquals(1, notices.size)
        assertEquals("電力", notices.last().title)
        r.push("w1", "1700")
        assertEquals(2, notices.size)
        assertTrue(notices.last().text.contains("1,700 W"))
        assertEquals("chime", notices.last().tone)
        r.push("w1", "1800")
        assertEquals(2, notices.size)
    }

    @Test
    fun `monitor checks each target`() = runTest {
        widget(WidgetDef("w1", source = "monitor", notify = true, targets = listOf(WidgetTarget("Web", "https://ok.example"), WidgetTarget("SSH", "tcp://10.0.0.2:22"), WidgetTarget("NAS", "tcp://10.0.0.3:5000"))))
        var nasUp = true
        val r = repo(tcp = { host, _, _ -> if (host == "10.0.0.3" && !nasUp) throw ConnectException("refused") })
        r.refreshIfDue(config.display)
        settle(r)
        assertEquals(listOf(WidgetLevel.OK, WidgetLevel.OK, WidgetLevel.OK), r.state.of("w1")!!.items.map { it.level })
        nasUp = false
        now += 5 * 60_000L
        r.refreshIfDue(config.display)
        settle(r)
        val items = r.state.of("w1")!!.items
        assertEquals(WidgetLevel.ALERT, items[2].level)
        assertEquals(1, notices.size)
        assertTrue(notices.single().text.contains("NAS"))
    }

    @Test
    fun `values survive a restart`() = runTest {
        widget(WidgetDef("w1", source = "push"))
        val r = repo()
        r.push("w1", "{\"value\": 42}")
        r.save()
        val again = repo()
        assertEquals(42.0, again.state.of("w1")!!.number!!, 1e-9)
    }

    @Test
    fun `test returns the raw body and uses the saved header for the same host`() = runTest {
        widget(WidgetDef("w1", url = "https://api.example.com/a", path = "data.temp", headerName = "Authorization", headerValue = "Bearer saved"))
        val r = repo()
        val ok = r.test(WidgetDef("w1", url = "https://api.example.com/b", path = "data.temp", headerName = "Authorization"))
        assertTrue(ok.ok)
        assertEquals(body, ok.raw)
        assertTrue(requests.last().endsWith("auth=Bearer saved"))
        // 別のホストへは保存したトークンを送らない
        r.test(WidgetDef("w1", url = "https://other.example.com/b", path = "data.temp", headerName = "Authorization"))
        assertTrue(requests.last().endsWith("auth="))
        val bad = r.test(WidgetDef("w1", url = "https://api.example.com/a", path = "nope"))
        assertFalse(bad.ok)
        assertNotNull(bad.raw)
        val plain = r.test(WidgetDef("w1", url = "ftp://x", path = "a"))
        assertFalse(plain.ok)
    }
}
