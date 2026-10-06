package app.dashboard.data

import app.dashboard.ui.dashboard.weeksOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** GitHub のコントリビューションの絵（プロフィールの HTML の読み取りと週への並べ替え）。 */
class GithubRepositoryTest {

    private val html = """
        <table><tbody><tr>
        <td tabindex="0" data-ix="0" style="width: 10px" data-date="2026-09-27" id="contribution-day-component-0-0" data-level="2" role="gridcell" class="ContributionCalendar-day"></td>
        <td tabindex="0" data-ix="1" data-date="2026-09-28" id="contribution-day-component-1-0" data-level="0" class="ContributionCalendar-day"></td>
        <td data-date="2026-09-29" id="contribution-day-component-2-0" data-level="4" class="ContributionCalendar-day"></td>
        </tr></tbody></table>
        <tool-tip id="t1" for="contribution-day-component-0-0" class="sr-only">5 contributions on September 27th.</tool-tip>
        <tool-tip id="t2" for="contribution-day-component-1-0" class="sr-only">No contributions on September 28th.</tool-tip>
        <tool-tip id="t3" for="contribution-day-component-2-0" class="sr-only">1,234 contributions on September 29th.</tool-tip>
    """.trimIndent()

    @Test
    fun `contributions are read with counts and levels`() {
        val days = GithubRepository.parseContributions(html)
        assertEquals(listOf("2026-09-27", "2026-09-28", "2026-09-29"), days.map { it.date })
        assertEquals(listOf(5, 0, 1234), days.map { it.count })
        assertEquals(listOf(2, 0, 4), days.map { it.level })
    }

    @Test
    fun `days are grouped into sunday weeks`() {
        // 2026-09-27 は日曜
        val days = listOf("2026-09-26", "2026-09-27", "2026-09-28").map { GithubDay(it, 0, 0) }
        val weeks = weeksOf(days)
        assertEquals(2, weeks.size)
        assertEquals(6, weeks[0].single().first)
        assertEquals(listOf(0, 1), weeks[1].map { it.first })
    }
}
