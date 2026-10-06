package app.dashboard.ui.dashboard

import app.dashboard.i18n.Lang
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 時刻カードの日付の書き方（設定の「日付の書き方」）。 */
class DateFormatTest {

    private val fri = ZonedDateTime.of(2026, 10, 2, 9, 0, 0, 0, ZoneId.of("Asia/Tokyo"))

    @After
    fun reset() {
        Lang.current = Lang.JA
    }

    @Test
    fun `english styles`() {
        assertEquals("02/10/2026 Fri", formatDate(fri, "dmy"))
        assertEquals("October 2nd, 2026 Fri", formatDate(fri, "long"))
        assertEquals("2026-10-02", formatDate(fri, "iso"))
        assertEquals("October 2nd, 2026", formatDate(fri, "longNoDay"))
    }

    @Test
    fun `ordinals`() {
        fun day(d: Int) = formatDate(fri.withDayOfMonth(d), "longNoDay").substringAfter("October ").substringBefore(",")
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "31st"), listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23, 31).map(::day))
    }

    @Test
    fun `existing styles keep their look`() {
        assertEquals("2026年10月2日 (金)", formatDate(fri, "ja"))
        assertEquals("2026/10/02 (金)", formatDate(fri, "slash"))
        Lang.current = Lang.EN
        assertEquals("Fri, Oct 2, 2026", formatDate(fri, "ja"))
        assertEquals("02/10/2026 Fri", formatDate(fri, "dmy"))
    }
}
