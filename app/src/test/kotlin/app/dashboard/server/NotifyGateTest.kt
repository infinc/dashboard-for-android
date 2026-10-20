package app.dashboard.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外から通知を出す API の門番（トークン・締め出し・送りすぎ）。 */
class NotifyGateTest {

    private var now = 1_000_000L
    private val gate = NotifyGate { now }

    @Test
    fun `off or without a token nothing passes`() {
        assertEquals(NotifyGate.Result.Disabled, gate.check("1.2.3.4", "Bearer t", enabled = false, token = "t"))
        assertEquals(NotifyGate.Result.Disabled, gate.check("1.2.3.4", "Bearer t", enabled = true, token = null))
    }

    @Test
    fun `right token passes and a second one within a second is held back`() {
        assertEquals(NotifyGate.Result.Ok, gate.check("1.2.3.4", "Bearer abc", true, "abc"))
        now += 500
        assertEquals(NotifyGate.Result.TooMany, gate.check("1.2.3.4", "Bearer abc", true, "abc"))
        now += 600
        assertEquals(NotifyGate.Result.Ok, gate.check("1.2.3.4", "Bearer abc", true, "abc"))
    }

    @Test
    fun `ten wrong tokens lock that address only`() {
        repeat(9) { assertEquals(NotifyGate.Result.BadToken, gate.check("1.2.3.4", "Bearer nope", true, "abc")) }
        assertTrue(gate.check("1.2.3.4", "Bearer nope", true, "abc") is NotifyGate.Result.Locked)
        // 締め出し中は正しいトークンでも通さない
        assertTrue(gate.check("1.2.3.4", "Bearer abc", true, "abc") is NotifyGate.Result.Locked)
        assertEquals(NotifyGate.Result.Ok, gate.check("5.6.7.8", "Bearer abc", true, "abc"))
    }
}
