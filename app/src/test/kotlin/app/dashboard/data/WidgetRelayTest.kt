package app.dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** 自作カードの中継（WidgetRelay）: 指示の取り出しと、返す先・取りに行く先の制限。 */
class WidgetRelayTest {

    private val card = "https://relay.example.workers.dev/relay"

    private fun job(
        result: String = "https://relay.example.workers.dev/relay/result",
        requests: List<RelayRequest> = listOf(RelayRequest("POST", "https://site.example.jp/index.php", mapOf("op" to "srch"))),
        id: String = "j-1",
    ) = RelayJob(id, result, requests)

    @Test
    fun `no relay field means an ordinary fetch`() {
        assertNull(WidgetRelay.job("""{"text":"ok","items":[]}"""))
        assertNull(WidgetRelay.job("""{"relay":null}"""))
        assertNull(WidgetRelay.job("not json"))
    }

    @Test
    fun `relay instruction is read from the response`() {
        val j = WidgetRelay.job(
            """{"text":"x","relay":{"id":"abc","result":"https://h/r","requests":[{"method":"POST","url":"https://s/i","form":{"a":"1"}}]}}""",
        )
        assertNotNull(j)
        assertEquals("abc", j!!.id)
        assertEquals("1", j.requests.single().form["a"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed relay instruction is an error`() {
        WidgetRelay.job("""{"relay":{"id":"a","requests":"nope"}}""")
    }

    @Test
    fun `a valid instruction passes`() {
        assertNull(WidgetRelay.check(card, job()))
    }

    @Test
    fun `result must be https on the card's host`() {
        assertNotNull(WidgetRelay.check(card, job(result = "https://evil.example.com/collect")))
        assertNotNull(WidgetRelay.check(card, job(result = "http://relay.example.workers.dev/relay/result")))
    }

    @Test
    fun `requests must be https and not local`() {
        assertNotNull(WidgetRelay.check(card, job(requests = listOf(RelayRequest("GET", "http://site.example.jp/")))))
        assertNotNull(WidgetRelay.check(card, job(requests = listOf(RelayRequest("GET", "https://192.168.1.1/")))))
        assertNotNull(WidgetRelay.check(card, job(requests = listOf(RelayRequest("GET", "https://localhost/")))))
        assertNotNull(WidgetRelay.check(card, job(requests = listOf(RelayRequest("GET", "https://nas.local/")))))
        assertNotNull(WidgetRelay.check(card, job(requests = listOf(RelayRequest("DELETE", "https://site.example.jp/")))))
        assertNotNull(WidgetRelay.check(card, job(requests = emptyList())))
        assertNotNull(WidgetRelay.check(card, job(requests = List(WidgetRelay.MAX_REQUESTS + 1) { RelayRequest("GET", "https://site.example.jp/") })))
        assertNotNull(WidgetRelay.check(card, job(id = "bad id with spaces")))
    }

    @Test
    fun `names that resolve to private addresses are refused`() {
        val lan = { _: String -> arrayOf(InetAddress.getByName("10.0.0.5")) }
        val pub = { _: String -> arrayOf(InetAddress.getByName("203.0.113.10")) }
        assertTrue(WidgetRelay.isPrivateHost("sneaky.example.com", lan))
        assertFalse(WidgetRelay.isPrivateHost("site.example.jp", pub))
    }
}
