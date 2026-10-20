package app.dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spotify の全画面の見た目（SpotifyLayout）の丸めと、1 色の背景の色の受け取り。 */
class SpotifyLayoutTest {

    @Test
    fun `範囲外の値は丸める`() {
        val lo = SpotifyLayout(
            hideSeconds = 0, coverSize = 500, coverX = -3, lyricsWidth = 5, lyricsWeight = 950, titleWeight = 150,
            lyricsMode = "bogus", titleAlign = "middle", controlsSize = 10, progressY = 120,
        ).sanitized()
        assertEquals(2, lo.hideSeconds)
        assertEquals(100, lo.coverSize)
        assertEquals(0, lo.coverX)
        assertEquals(20, lo.lyricsWidth)
        assertEquals(900, lo.lyricsWeight)
        assertEquals(100, lo.titleWeight)
        assertEquals("tap", lo.lyricsMode)
        assertEquals("left", lo.titleAlign)
        assertEquals(50, lo.controlsSize)
        assertEquals(100, lo.progressY)
    }

    @Test
    fun `既定値は丸めても変わらない`() {
        assertEquals(SpotifyLayout(), SpotifyLayout().sanitized())
    }

    @Test
    fun `古い設定（layout の無い spotify）も既定の見た目で読める`() {
        val c = Http.json.decodeFromString(SpotifyConfig.serializer(), """{"enabled":true,"background":"spike"}""")
        assertEquals(SpotifyLayout(), c.layout)
        assertEquals(DEFAULT_MONO_COLOR, c.monoColor)
    }

    @Test
    fun `色は #RRGGBB だけを受け取る`() {
        assertEquals("#3E6FB8", monoColorOrNull(" #3e6fb8 "))
        assertNull(monoColorOrNull("3e6fb8"))
        assertNull(monoColorOrNull("#3e6fb"))
        assertNull(monoColorOrNull("red"))
        assertNull(monoColorOrNull(null))
    }
}
