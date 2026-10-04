package app.dashboard.ui.dashboard

import app.cash.turbine.test
import app.dashboard.data.Aircraft
import app.dashboard.data.FlightRepository
import app.dashboard.ui.map.MapFrame
import app.dashboard.ui.map.MapMath
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 飛行機のカードの取得の間隔と、位置の見込み。 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightTrackerTest {

    private fun frame(lat: Double, lon: Double, zoom: Int = 8) = MapFrame(
        zoom = zoom, centerX = MapMath.x(lon, zoom), centerY = MapMath.y(lat, zoom),
        homeX = MapMath.x(lon, zoom), homeY = MapMath.y(lat, zoom), minZoom = 3, maxZoom = 13,
    )

    @Test
    fun `radius covers the view and is capped by the API`() {
        assertTrue(FlightTracker.radiusNm(frame(35.0, 139.0, 8), false) in 100..250)
        assertEquals(250, FlightTracker.radiusNm(frame(35.0, 139.0, 4), true))
    }

    @Test
    fun `extrapolate moves a flying aircraft along its track`() {
        val a = Aircraft("x", "T1", null, null, 35.0, 139.0, 30000, false, 360.0, 90.0)
        val (lat, lon) = FlightTracker.extrapolate(a, 10.0)
        assertEquals(35.0, lat, 1e-6)
        assertTrue("moves east", lon > 139.0)
        val ground = a.copy(onGround = true)
        assertEquals(35.0 to 139.0, FlightTracker.extrapolate(ground, 10.0))
    }

    @Test
    fun `tracker fetches immediately and then every interval`() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            respond("""{"ac":[{"hex":"a","lat":35.0,"lon":139.0,"alt_baro":1000}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val f = frame(35.0, 139.0)
        val tracker = FlightTracker(backgroundScope, FlightRepository(client), { f }, { false }, clock = { testScheduler.currentTime })
        tracker.state.test {
            assertEquals(0, awaitItem().aircraft.size)
            tracker.setActive(true)
            assertEquals(1, awaitItem().aircraft.size)
            assertEquals(1, calls)
            advanceTimeBy(FlightTracker.INTERVAL_MS + 1_500)
            awaitItem()
            assertEquals(2, calls)
            tracker.setActive(false)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `moving the map far triggers an early fetch`() = runTest {
        var calls = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = HttpClient(MockEngine.create {
            this.dispatcher = dispatcher
            addHandler {
                calls++
                respond("""{"ac":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        var f = frame(35.0, 139.0)
        val tracker = FlightTracker(backgroundScope, FlightRepository(client), { f }, { false }, clock = { testScheduler.currentTime }, dispatcher = dispatcher)
        tracker.setActive(true)
        advanceTimeBy(500)
        assertEquals(1, calls)
        // 少し動かしただけでは取り直さない
        f = frame(35.01, 139.01)
        advanceTimeBy(3_000)
        assertEquals(1, calls)
        // 大きく動かすと、間隔を待たずに取り直す
        f = frame(37.0, 141.0)
        advanceTimeBy(1_100)
        assertEquals(2, calls)
        tracker.setActive(false)
    }
}
