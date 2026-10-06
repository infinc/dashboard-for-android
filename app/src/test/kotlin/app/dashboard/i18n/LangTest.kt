package app.dashboard.i18n

import app.dashboard.data.CardLayout
import app.dashboard.data.Countdown
import app.dashboard.data.Jma
import app.dashboard.data.LocationConfig
import app.dashboard.data.Tones
import app.dashboard.data.stockLabel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** 表示の言語の切り替え（L・各名前の英語・気象庁の値の英語）。 */
class LangTest {

    @After
    fun reset() {
        Lang.current = Lang.JA
    }

    @Test
    fun `L follows the current language`() {
        assertEquals("時刻", L("時刻", "Clock"))
        Lang.current = Lang.EN
        assertEquals("Clock", L("時刻", "Clock"))
    }

    @Test
    fun `unknown languages fall back to Japanese`() {
        Lang.current = "fr"
        assertEquals(Lang.JA, Lang.current)
    }

    @Test
    fun `card and tone names switch with the language`() {
        assertEquals("雨雲レーダー", CardLayout.Card.RADAR.label)
        assertEquals("チャイム", Tones.byId("chime", "chime").label)
        Lang.current = Lang.EN
        assertEquals("Rain radar", CardLayout.Card.RADAR.label)
        assertEquals("Chime", Tones.byId("chime", "chime").label)
        assertEquals("Flights", CardLayout.Card.FLIGHTS.label)
    }

    @Test
    fun `location name uses the English name when there is one`() {
        val loc = LocationConfig(configured = true, name = "札幌", nameEn = "Sapporo")
        assertEquals("札幌", loc.displayName())
        Lang.current = Lang.EN
        assertEquals("Sapporo", loc.displayName())
        assertEquals("Tokyo", LocationConfig().displayName())
        assertEquals("Hakodate City", LocationConfig(configured = true, name = "函館").displayName("Hakodate City"))
    }

    @Test
    fun `JMA values are translated only in English`() {
        assertEquals("雷注意報", Jma.kind("雷注意報"))
        Lang.current = Lang.EN
        assertEquals("Thunderstorm advisory", Jma.kind("雷注意報"))
        assertEquals("Level 4 landslide danger warning", Jma.kind("レベル４土砂災害危険警報"))
        assertEquals("Code 43", Jma.kind("コード43"))
        assertEquals("Typhoon No. 27", Jma.typhoonNumber("台風27号"))
        assertEquals("NNE", Jma.course("北北東"))
        assertEquals("Very strong", Jma.intensity("非常に強い"))
        assertEquals("22.1°N 147.2°E", Jma.location("小笠原近海", 22.1, 147.2))
        assertEquals("Level 3 (Do not approach the volcano)", Jma.volcanoLevel("レベル３（入山規制）"))
        assertEquals("Kirishimayama (Shinmoedake)", Jma.volcanoName("霧島山（新燃岳）"))
        assertEquals("Tsunami forecast", Jma.tsunami("津波予報"))
        assertEquals("Major tsunami warning, Tsunami warning", Jma.tsunami("大津波警報・津波警報"))
    }

    @Test
    fun `holiday and default stock names are translated`() {
        Lang.current = Lang.EN
        assertEquals("Culture Day", Countdown.holidayName("文化の日"))
        assertEquals("Nikkei 225", stockLabel("日経平均"))
        assertEquals("My stock", stockLabel("My stock"))
    }
}
