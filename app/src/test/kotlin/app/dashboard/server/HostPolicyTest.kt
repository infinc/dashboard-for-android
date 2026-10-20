package app.dashboard.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 設定サーバーの Host の検査（DNS rebinding 対策）とブラウズの止め先の判定。 */
class HostPolicyTest {

    private val lan = { setOf("192.168.0.20", "fe80::1") }

    @Test
    fun `hostOf strips port brackets and case`() {
        assertEquals("127.0.0.1", HostPolicy.hostOf("127.0.0.1:8080"))
        assertEquals("localhost", HostPolicy.hostOf("LocalHost:9090"))
        assertEquals("::1", HostPolicy.hostOf("[::1]:8080"))
        assertEquals("fe80::1", HostPolicy.hostOf("fe80::1"))
        assertEquals(null, HostPolicy.hostOf(""))
    }

    @Test
    fun `loopback and the device's own addresses are allowed on any port`() {
        assertTrue(HostPolicy.isSelf("127.0.0.1:8080", localAddresses = lan))
        assertTrue(HostPolicy.isSelf("localhost:9090", localAddresses = lan))
        assertTrue(HostPolicy.isSelf("[::1]:8080", localAddresses = lan))
        assertTrue(HostPolicy.isSelf("192.168.0.20:8080", localAddresses = lan))
    }

    @Test
    fun `a rebinding name or another LAN host is refused`() {
        assertFalse(HostPolicy.isSelf("evil.example:8080", localAddresses = lan))
        assertFalse(HostPolicy.isSelf("192.168.0.21:8080", localAddresses = lan))
        assertFalse(HostPolicy.isSelf("127.0.0.1.evil.example", localAddresses = lan))
        assertFalse(HostPolicy.isSelf(null, localAddresses = lan))
    }

    @Test
    fun `extra hosts from the settings are allowed`() {
        assertTrue(HostPolicy.isSelf("dashboard.local:8080", listOf("dashboard.local"), localAddresses = lan))
        assertFalse(HostPolicy.isSelf("dashboard.local:8080", localAddresses = lan))
    }
}
