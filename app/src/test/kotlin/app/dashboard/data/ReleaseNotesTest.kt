package app.dashboard.data

import app.dashboard.data.ReleaseNotes.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {

    @Test
    fun `リリースの説明文を見出し・文・箇条に分ける`() {
        val body = "# Dashboard_v1.2.5\r\n\r\n- **概要**\r\n  更なる拡張性の向上\r\n\r\n- **新機能**\r\n  - Spotify の背景を選択可能に\r\n    - 単色\r\n  - `CoinGecko` の API\r\n"
        assertEquals(
            listOf(
                Line("概要", heading = true),
                Line("更なる拡張性の向上"),
                Line("新機能", heading = true),
                Line("Spotify の背景を選択可能に", depth = 1, bullet = true),
                Line("単色", depth = 2, bullet = true),
                Line("CoinGecko の API", depth = 1, bullet = true),
            ),
            ReleaseNotes.parse(body),
        )
    }

    @Test
    fun `中身の無い節は出さない`() {
        val body = "# Dashboard_v1.0\r\n\r\n- **概要**\r\n  最初のリリース\r\n\r\n- **新機能**\r\n\r\n- **改善点**"
        assertEquals(listOf(Line("概要", heading = true), Line("最初のリリース")), ReleaseNotes.parse(body))
    }

    @Test
    fun `空なら何も無い`() {
        assertTrue(ReleaseNotes.parse(null).isEmpty())
        assertTrue(ReleaseNotes.parse("  \r\n").isEmpty())
    }
}
