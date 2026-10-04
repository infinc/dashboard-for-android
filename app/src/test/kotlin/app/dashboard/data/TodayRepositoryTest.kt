package app.dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 今日は何の日（日本語版の記事の読み取りと、英語版の onthisday の読み取り）。 */
class TodayRepositoryTest {

    @Test
    fun `japanese observances put Japan first`() {
        val wikitext = """
            * 世界の何かの日（{{World}}）
            * ワープロ記念日（{{JPN}}）
            *: 1978年のこの日、東芝が[[ワードプロセッサ]]を発表した。
        """.trimIndent()
        val days = TodayRepository.parseDays(wikitext)
        assertEquals("ワープロ記念日", days[0].name)
        assertEquals("日本", days[0].region)
        assertTrue(days[0].note!!.contains("ワードプロセッサ"))
    }

    @Test
    fun `english on this day groups feast days and keeps regions`() {
        val body = """
            {"holidays":[
              {"text":"Christian feast day:\nAmun"},
              {"text":"Christian feast day:\nFrancis of Assisi"},
              {"text":"Cinnamon Roll Day  (Sweden and Finland)"}
            ],
            "events":[{"year":2006,"text":"WikiLeaks is launched."}]}
        """.trimIndent()
        val (days, events) = TodayRepository.parseOnThisDay(body)
        assertEquals("Cinnamon Roll Day", days[0].name)
        assertEquals("Sweden and Finland", days[0].region)
        assertEquals("Christian feast day", days[1].name)
        assertEquals("Amun, Francis of Assisi", days[1].note)
        assertEquals(listOf("2006 - WikiLeaks is launched."), events)
    }

    @Test
    fun `wiki markup is removed`() {
        assertEquals("東芝が発表", TodayRepository.clean("[[東芝]]が'''発表'''<ref>出典</ref>"))
    }
}
