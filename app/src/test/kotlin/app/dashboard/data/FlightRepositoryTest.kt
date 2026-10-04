package app.dashboard.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 飛行機の位置（adsb.lol の応答の読み取りと、問い合わせの URL）。 */
class FlightRepositoryTest {

    private val sample = """
        {"ac":[
          {"hex":"84b3eb","flight":"APJ491  ","r":"JA202P","t":"A20N","alt_baro":25000,"gs":476.7,"track":40.41,"lat":35.6277,"lon":137.9236,"seen_pos":0.3},
          {"hex":"8681b4","r":"JA73NN","alt_baro":"ground","gs":3.1,"true_heading":120.0,"lat":35.55,"lon":139.78},
          {"hex":"nopos","flight":"XXX1"}
        ],"msg":"No error","now":1791077341000,"total":3}
    """.trimIndent()

    @Test
    fun `parse reads position, altitude and callsign`() {
        val list = FlightRepository.parse(sample)
        assertEquals(2, list.size)
        val a = list[0]
        assertEquals("APJ491", a.callsign)
        assertEquals("A20N", a.type)
        assertEquals(25000, a.altitudeFt)
        assertFalse(a.onGround)
        assertEquals(40.41, a.track!!, 1e-9)
        val g = list[1]
        assertTrue(g.onGround)
        assertEquals(0, g.altitudeFt)
        assertNull(g.callsign)
        assertEquals(120.0, g.track!!, 1e-9)
    }

    @Test
    fun `around asks for the point and radius capped at 250 nm`() = runTest {
        var url = ""
        val client = HttpClient(MockEngine { req ->
            url = req.url.toString()
            respond(sample, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val list = FlightRepository(client).around(35.68, 139.69, 900)
        assertEquals("https://api.adsb.lol/v2/point/35.6800/139.6900/250", url)
        assertEquals(2, list.size)
    }
}
