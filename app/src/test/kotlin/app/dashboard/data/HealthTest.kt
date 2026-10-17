package app.dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 空気・体調の目安（暑さ指数の推定と段階）。 */
class HealthTest {

    @Test
    fun `wbgt estimate for a hot humid sunny afternoon`() {
        // 30℃・70%・日射 800 W/m²・風 2 m/s → 約 29.8（厳重警戒）
        assertEquals(29.8, Health.wbgt(30.0, 70.0, 800.0, 2.0), 0.05)
        assertEquals(3, Health.wbgtBand(29.8).level)
    }

    @Test
    fun `negative radiation is treated as zero`() {
        assertEquals(Health.wbgt(20.0, 50.0, 0.0, 1.0), Health.wbgt(20.0, 50.0, -5.0, 1.0), 0.0)
    }

    @Test
    fun `band edges`() {
        assertEquals(0, Health.wbgtBand(20.9).level)
        assertEquals(1, Health.wbgtBand(21.0).level)
        assertEquals(4, Health.wbgtBand(31.0).level)
        assertEquals(0, Health.uvBand(2.9).level)
        assertEquals(2, Health.uvBand(6.0).level)
        assertEquals(4, Health.uvBand(11.0).level)
        assertEquals(1, Health.pm25Band(20.0).level)
        assertEquals(3, Health.pm25Band(71.0).level)
    }
}
