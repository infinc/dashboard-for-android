package app.dashboard.ui.map

import androidx.compose.ui.graphics.ImageBitmap
import app.cash.turbine.test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 地図の座標の計算と、ドラッグ・「＋」「−」・「現在地に戻る」の動き（飛行機・船舶のカード）。 */
class TileMapTest {

    @Test
    fun `lat lon round trip`() {
        for (zoom in listOf(3, 8, 14)) {
            val x = MapMath.x(139.69, zoom)
            val y = MapMath.y(35.68, zoom)
            assertEquals(139.69, MapMath.lon(x, zoom), 1e-9)
            assertEquals(35.68, MapMath.lat(y, zoom), 1e-9)
        }
    }

    @Test
    fun `longitude wraps around the world`() {
        assertEquals(-170.0, MapMath.wrapLon(190.0), 1e-9)
        assertEquals(170.0, MapMath.wrapLon(-550.0), 1e-9)
    }

    private fun controller(scope: TestScope, saved: IntArray = intArrayOf(8)) = TileMapController(
        scope = scope,
        // 画像は使わないので、取れたことにして null 以外の何かを返したいが、ImageBitmap は作れないので「無い」と答える
        load = { _: String -> null as ImageBitmap? },
        url = TileMapController::esriGray,
        savedZoom = { saved[0] },
        saveZoom = { saved[0] = it },
        minZoom = 3,
        maxZoom = 13,
        clock = { scope.testScheduler.currentTime },
    )

    @Test
    fun `setHome centers the map on the location at the saved zoom`() = runTest {
        val map = controller(this)
        map.frame.test {
            assertFalse(awaitItem().ready)
            map.setHome(35.68, 139.69, dark = true)
            val f = awaitItem()
            assertEquals(8, f.zoom)
            assertEquals(35.68, f.centerLat, 1e-6)
            assertEquals(139.69, f.centerLon, 1e-6)
            assertFalse(f.panned)
        }
    }

    @Test
    fun `pan, zoom and recenter`() = runTest {
        val saved = intArrayOf(8)
        val map = controller(this, saved)
        map.setHome(35.68, 139.69, dark = true)
        val home = map.frame.value
        map.pan(100f, -50f)
        assertTrue(map.frame.value.panned)
        assertEquals(home.centerX + 100, map.frame.value.centerX, 1e-6)

        map.zoom(1)
        assertEquals(9, map.frame.value.zoom)
        assertEquals(9, saved[0])
        // 拡大しても表示の中心の緯度経度は変わらない
        assertEquals(MapMath.lon(home.centerX + 100, 8), MapMath.lon(map.frame.value.centerX, 9), 1e-9)

        map.recenter()
        assertFalse(map.frame.value.panned)
        assertEquals(9, map.frame.value.zoom)
    }

    @Test
    fun `zoom stops at the limits`() = runTest {
        val map = controller(this, intArrayOf(13))
        map.setHome(0.0, 0.0, dark = false)
        assertFalse(map.frame.value.canZoomIn)
        map.zoom(1)
        assertEquals(13, map.frame.value.zoom)
        assertTrue(map.frame.value.canZoomOut)
    }

    @Test
    fun `keys start from the center tile`() = runTest {
        val map = controller(this)
        map.setHome(35.68, 139.69, dark = true)
        val f = map.frame.value
        val keys = map.keys(f)
        assertEquals(15, keys.size)
        assertEquals(Pair((f.centerX / 256).toInt(), (f.centerY / 256).toInt()), keys.first())
        map.setWide(true)
        assertEquals(35, map.keys(f).size)
    }
}
