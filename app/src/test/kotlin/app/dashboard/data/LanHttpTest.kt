package app.dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URI

/** LAN の平文 HTTP（自作カード）。どこへ平文で送ってよいかと、応答の読み取り。 */
class LanHttpTest {

    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `private addresses are allowed and public ones are not`() {
        listOf("127.0.0.1", "10.0.0.5", "172.16.3.4", "172.31.255.1", "192.168.1.10", "169.254.1.1", "100.100.1.1", "::1", "fd12::1", "fe80::1")
            .forEach { assertTrue(it, LanHttp.isPrivate(ip(it))) }
        listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "100.128.0.1", "1.1.1.1", "2001:4860::8888")
            .forEach { assertFalse(it, LanHttp.isPrivate(ip(it))) }
    }

    @Test
    fun `names must resolve only to private addresses`() {
        assertEquals(ip("192.168.1.2"), LanHttp.resolvePrivate("nas.local") { arrayOf(ip("192.168.1.2")) })
        // 外のアドレスが 1 つでも混じれば断る（引き直しで外へ向けられないように）
        assertThrows(LanHttp.NotPrivate::class.java) { LanHttp.resolvePrivate("evil.example") { arrayOf(ip("192.168.1.2"), ip("8.8.8.8")) } }
        assertThrows(LanHttp.NotPrivate::class.java) { LanHttp.resolvePrivate("example.com") { arrayOf(ip("93.184.216.34")) } }
    }

    @Test
    fun `request line, host and headers`() {
        val r = LanHttp.request(URI("http://ha.local:8123/api/states/sensor.x?a=1"), mapOf("Authorization" to "Bearer t", "Bad\nName" to "x", "X-Ok" to "a\r\nInjected: 1"))
        assertTrue(r.startsWith("GET /api/states/sensor.x?a=1 HTTP/1.1\r\nHost: ha.local:8123\r\n"))
        assertTrue(r.contains("Authorization: Bearer t\r\n"))
        assertFalse(r.contains("Injected"))
        assertFalse(r.contains("Bad"))
        assertTrue(r.endsWith("\r\n\r\n"))
        assertTrue(LanHttp.request(URI("http://10.0.0.1"), emptyMap()).startsWith("GET / HTTP/1.1\r\nHost: 10.0.0.1\r\n"))
    }

    private fun read(s: String) = LanHttp.read(ByteArrayInputStream(s.toByteArray(Charsets.UTF_8)))

    @Test
    fun `responses with length, chunks or close`() {
        val body = "{\"state\":\"21\"}"
        val a = read("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body")
        assertEquals(200, a.status)
        assertEquals(body, a.body)
        assertEquals("application/json", a.headers["content-type"])
        val b = read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\n\r\n")
        assertEquals("hello world", b.body)
        val c = read("HTTP/1.0 404 Not Found\r\n\r\nmissing")
        assertEquals(404, c.status)
        assertEquals("missing", c.body)
        val jp = "温度"
        val d = read("HTTP/1.1 200 OK\r\nContent-Length: ${jp.toByteArray().size}\r\n\r\n$jp")
        assertEquals(jp, d.body)
    }

    @Test
    fun `broken responses throw`() {
        assertThrows(IOException::class.java) { read("") }
        assertThrows(IOException::class.java) { read("SSH-2.0-OpenSSH\r\n") }
        assertThrows(IOException::class.java) { read("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort") }
        assertThrows(IOException::class.java) { read("HTTP/1.1 200 OK\r\nContent-Length: 99999999\r\n\r\n") }
    }
}
