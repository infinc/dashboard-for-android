package app.dashboard.data

import app.dashboard.data.CardLayout.Card
import app.dashboard.data.CardLayout.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** カードの並べ方（画面・アプリの設定画面・Web の設定画面・保存の検査が同じ計算を使う）。 */
class CardLayoutTest {

    /** 横向き 1280x800dp のタブレット（4 行まで並ぶ）。 */
    private val tablet = CardLayout.Area(heightDp = 740, screenWidthDp = 1280, screenHeightDp = 800)

    @Test
    fun `pack fills each row to 24 columns in proportion`() {
        val rows = CardLayout.pack(listOf("a" to 8, "b" to 8))
        assertEquals(1, rows.size)
        assertEquals(24, rows[0].sumOf { it.second })
        assertEquals(listOf(12, 12), rows[0].map { it.second })
    }

    @Test
    fun `default cards fit in four rows on a landscape tablet`() {
        val fit = CardLayout.fit(DisplayConfig(), tablet)
        assertTrue(fit.fits)
        assertEquals(4, fit.maxRows)
    }

    @Test
    fun `positions skip columns taken by a card stretched from the row above`() {
        val rows = listOf(
            listOf(Tile(Card.CLOCK, 8, height = 2), Tile(Card.WEATHER, 16)),
            listOf(Tile(Card.TIMER, 8)),
        )
        val placed = CardLayout.positions(rows, 4)!!
        val timer = placed.first { it.card == Card.TIMER }
        assertEquals(1, timer.row)
        assertEquals(8, timer.col)
    }

    @Test
    fun `positions reject a row wider than 24 columns`() {
        assertNull(CardLayout.positions(listOf(listOf(Tile(Card.CLOCK, 20), Tile(Card.WEATHER, 8))), 4))
    }

    @Test
    fun `adding a card squeezes the others down to their minimum width`() {
        val before = DisplayConfig()
        val after = before.copy(showFlights = true)
        val adjusted = CardLayout.adjust(before, after, tablet)
        assertNull(adjusted.message)
        val rows = CardLayout.slots(adjusted.display)
        assertTrue(rows.flatten().any { it.card == Card.FLIGHTS })
        rows.forEach { row -> assertTrue(row.sumOf { it.span } <= CardLayout.COLUMNS) }
        rows.flatten().forEach { assertTrue("${it.card} is narrower than its minimum", it.span >= it.card.min) }
    }

    @Test
    fun `adding too many cards is refused with a reason`() {
        val before = DisplayConfig()
        val after = before.copy(
            showFlights = true, showShips = true, showGithub = true, showCrypto = true, showStocks = true,
            showCalendar = true, showTrain = true, showRadar = true, showPhotos = true,
        )
        val adjusted = CardLayout.adjust(before, after, tablet)
        assertNotNull(adjusted.message)
    }

    @Test
    fun `resize moves width to the right neighbour`() {
        val rows = listOf(listOf(Tile(Card.CLOCK, 12), Tile(Card.WEATHER, 12)))
        val next = CardLayout.resize(rows, 0, 0, 2)
        assertEquals(14, next[0][0].span)
        assertEquals(10, next[0][1].span)
    }

    @Test
    fun `resize never goes below the neighbour's minimum`() {
        val rows = listOf(listOf(Tile(Card.CLOCK, 16), Tile(Card.WEATHER, Card.WEATHER.min)))
        val next = CardLayout.resize(rows, 0, 0, 5)
        assertEquals(Card.WEATHER.min, next[0][1].span)
        assertEquals(24, next[0].sumOf { it.span })
    }

    @Test
    fun `move puts a card into another row`() {
        val rows = listOf(
            listOf(Tile(Card.CLOCK, 8), Tile(Card.WEATHER, 8)),
            listOf(Tile(Card.TIMER, 6)),
        )
        val next = CardLayout.move(rows, 0, 1, 1, 1)!!
        assertEquals(listOf(Card.CLOCK), next[0].map { it.card })
        assertEquals(listOf(Card.TIMER, Card.WEATHER), next[1].map { it.card })
    }

    @Test
    fun `remove leaves the gap empty`() {
        val rows = listOf(listOf(Tile(Card.CLOCK, 8), Tile(Card.WEATHER, 8)))
        val next = CardLayout.remove(rows, 0, 0)
        assertEquals(listOf(Card.WEATHER), next[0].map { it.card })
        assertEquals(8, next[0][0].span)
    }

    @Test
    fun `every card has a minimum no wider than its default width`() {
        Card.entries.forEach { assertTrue("${it.name}: min ${it.min} > span ${it.span}", it.min <= it.span) }
    }
}
