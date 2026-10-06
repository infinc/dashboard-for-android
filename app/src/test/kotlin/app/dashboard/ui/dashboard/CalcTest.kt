package app.dashboard.ui.dashboard

import app.dashboard.i18n.Lang
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** 計算機カードの計算（掛け算・割り算が先、10 桁に丸める）。 */
class CalcTest {

    private fun keys(vararg k: String): Calc = k.fold(Calc()) { c, key -> c.press(key) }

    @After
    fun reset() {
        Lang.current = Lang.JA
    }

    @Test
    fun `multiplication comes before addition`() {
        assertEquals("18", keys("1", "2", "+", "3", "×", "2", "=").display())
    }

    @Test
    fun `large products are grouped by thousands`() {
        assertEquals("109,876,463", keys("1", "2", "3", "4", "5", "6", "7", "×", "8", "9", "=").display())
    }

    @Test
    fun `one third is rounded to ten digits`() {
        assertEquals("0.3333333333", keys("1", "÷", "3", "=").display())
    }

    @Test
    fun `dividing by zero shows a message in the current language`() {
        assertEquals("0 では割れません", keys("5", "÷", "0", "=").display())
        Lang.current = Lang.EN
        assertEquals("Can't divide by 0", keys("5", "÷", "0", "=").display())
    }

    @Test
    fun `an operator after equals continues from the answer`() {
        assertEquals("20", keys("4", "×", "5", "=", "+", "0", "=").display())
    }

    @Test
    fun `backspace removes the last digit`() {
        assertEquals("12", keys("1", "2", "3", "⌫").display())
    }

    @Test
    fun `percent divides by one hundred`() {
        assertEquals("0.5", keys("5", "0", "%").display())
    }

    @Test
    fun `very large numbers switch to exponent form`() {
        assertEquals("1e20", Calc.plain(java.math.BigDecimal("1e20")))
    }
}
