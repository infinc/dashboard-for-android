package app.dashboard

import app.dashboard.data.PresetTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/** プリセットの時刻での切り替え（どの時刻を過ぎたか）。 */
class PresetScheduleTest {

    private val schedule = listOf(PresetTime("06:00", "day"), PresetTime("22:00", "night"))
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, day, h, m)

    @Test
    fun `parseTime accepts H mm and rejects out of range`() {
        assertEquals(LocalTime.of(6, 30), PresetController.parseTime("6:30"))
        assertEquals(LocalTime.of(22, 0), PresetController.parseTime(" 22:00 "))
        assertNull(PresetController.parseTime("24:00"))
        assertNull(PresetController.parseTime("7:5"))
        assertNull(PresetController.parseTime(""))
    }

    @Test
    fun `crossing a time switches once`() {
        assertEquals("night", PresetController.due(schedule, at(17, 21, 59), at(17, 22, 0))?.id)
        // 同じ時刻をもう一度見ても切り替えない（手で戻したらそのまま）
        assertNull(PresetController.due(schedule, at(17, 22, 0), at(17, 22, 1)))
        assertNull(PresetController.due(schedule, at(17, 12), at(17, 13)))
    }

    @Test
    fun `after a long gap the latest missed time wins`() {
        // 前の日の 20 時から翌朝 7 時まで止まっていた → 22:00 と 6:00 を過ぎた。最後の 6:00（昼）にする
        assertEquals("day", PresetController.due(schedule, at(16, 20), at(17, 7))?.id)
    }
}
