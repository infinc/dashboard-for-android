package app.dashboard.ui.dashboard

import app.dashboard.i18n.L
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import app.dashboard.ui.common.Tabular
import app.dashboard.ui.common.WdCard
import app.dashboard.ui.theme.LocalAccent
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

// ---------------------------------------------------------------- 計算機

/**
 * 計算機。四則演算は掛け算・割り算を先に計算する（12 + 3 × 2 = 18）。数は BigDecimal で持ち、0.1 + 0.2 も 0.3 になる。
 *
 * 上に横長の表示（左に式、右に大きく数）、その下に鍵盤。
 * 横長のカード（1 行の高さ）では鍵盤を 4 行 x 5 列、縦長のカード（2 行の高さや縦向きの画面）では 5 行 x 4 列にする。
 */
@Composable
fun CalculatorCard(modifier: Modifier) {
    var calc by remember { mutableStateOf(Calc()) }
    fun press(key: String) {
        calc = calc.press(key)
    }
    WdCard(L("計算機", "Calculator"), modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val tall = maxHeight > maxWidth * 0.75f
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (tall) 6.dp else 4.dp)) {
                Screen(calc, Modifier.fillMaxWidth().weight(1f))
                if (tall) Keypad(TALL_KEYS, ::press, Modifier.fillMaxWidth().weight(4.2f))
                else Keypad(WIDE_KEYS, ::press, Modifier.fillMaxWidth().weight(2.9f))
            }
        }
    }
}

private val WIDE_KEYS = listOf(
    listOf("7", "8", "9", "÷", "AC"),
    listOf("4", "5", "6", "×", "⌫"),
    listOf("1", "2", "3", "−", "%"),
    listOf("0", "00", ".", "+", "="),
)

private val TALL_KEYS = listOf(
    listOf("AC", "⌫", "%", "÷"),
    listOf("7", "8", "9", "×"),
    listOf("4", "5", "6", "−"),
    listOf("1", "2", "3", "+"),
    listOf("0", "00", ".", "="),
)

/**
 * 横長の表示。左に式（小さく、長ければ頭を省く）、右にいま入力中の数・答え（大きく）。
 * 長い数は文字を縮めて 1 行に収める。
 */
@Composable
private fun Screen(calc: Calc, modifier: Modifier) {
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(8.dp)).background(Wd.Bg.copy(alpha = 0.55f)).padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val main = calc.display()
        // 式に幅の 3 割ほどを残し、残りに入る大きさまで縮める（高さが上限）。文字の幅は、数字 ≒ 0.58em、「.」「,」≒ 0.28em、日本語 ≒ 1em
        val ems = main.sumOf { c ->
            when {
                c == '.' || c == ',' -> 0.28
                c.code < 128 -> 0.58
                else -> 1.0
            }
        }.toFloat().coerceAtLeast(2.4f)
        val byWidth = maxWidth.value * 0.7f / ems
        val size = minOf(byWidth, maxHeight.value * 0.78f, 40f).coerceAtLeast(11f)
        val small = (maxHeight.value * 0.36f).coerceIn(10f, 14f)
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                calc.expression(),
                color = Wd.Text3,
                fontSize = small.tu,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                style = Tabular,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Text(
                main,
                color = if (calc.error != null) Wd.Red else Wd.Text,
                fontSize = size.tu,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 1.em,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End,
                style = Tabular,
            )
        }
    }
}

@Composable
private fun Keypad(rows: List<List<String>>, onPress: (String) -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { key -> Key(key, onPress) }
            }
        }
    }
}

@Composable
private fun RowScope.Key(key: String, onPress: (String) -> Unit) {
    val accent = LocalAccent.current
    val (bg, fg) = when (key) {
        "=" -> accent to Wd.OnAccent
        "+", "−", "×", "÷" -> accent.copy(alpha = 0.18f) to accent
        "AC", "⌫", "%" -> Wd.Border.copy(alpha = 0.9f) to Wd.Text2
        else -> Wd.Surface2.copy(alpha = if (Wd.palette.light) 1f else 0.9f) to Wd.Text
    }
    BoxWithConstraints(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(bg).clickable { onPress(key) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            key,
            color = fg,
            fontSize = min(maxHeight * 0.62f, maxWidth * 0.42f).value.coerceIn(10f, 22f).tu,
            lineHeight = 1.em,
            fontWeight = if (key == "=") FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            style = Tabular,
        )
    }
}

/**
 * 計算機の状態。[tokens] は確定した数と演算子の並び（"12", "+", "3", "×"）、[entry] は入力中の数。
 * [done] は「=」を押した直後（次に数字を押すと新しい計算、演算子を押すと答えから続ける）。
 */
internal data class Calc(
    val tokens: List<String> = emptyList(),
    val entry: String = "",
    val done: Boolean = false,
    val shown: String = "",
    val error: String? = null,
) {
    fun press(key: String): Calc = when (key) {
        "AC" -> Calc()
        "⌫" -> back()
        "%" -> percent()
        "=" -> equals()
        "+", "−", "×", "÷" -> operator(key)
        "." -> dot()
        else -> digits(key)
    }

    private fun fresh() = if (done || error != null) Calc() else this

    private fun digits(d: String): Calc {
        val c = fresh()
        val raw = c.entry + d
        // 先頭の余分な 0 は落とす（"007" → "7"、"0.05" はそのまま）
        val next = if ('.' in raw) raw else raw.trimStart('0').ifEmpty { "0" }
        if (next.count(Char::isDigit) > MAX_DIGITS) return c
        return c.copy(entry = next)
    }

    private fun dot(): Calc {
        val c = fresh()
        if ('.' in c.entry) return c
        return c.copy(entry = c.entry.ifEmpty { "0" } + ".")
    }

    private fun operator(op: String): Calc {
        if (error != null) return this
        if (done) return Calc(tokens = listOf(entry, op))
        return when {
            entry.isNotEmpty() -> copy(tokens = tokens + entry.trimEnd('.') + op, entry = "")
            tokens.isNotEmpty() -> copy(tokens = tokens.dropLast(1) + op)
            else -> copy(tokens = listOf("0", op))
        }
    }

    private fun percent(): Calc {
        if (error != null || entry.isEmpty()) return this
        val v = BigDecimal(entry.trimEnd('.')).divide(BigDecimal(100))
        return copy(entry = plain(v), done = false, tokens = if (done) emptyList() else tokens)
    }

    private fun back(): Calc = when {
        error != null || done -> Calc()
        entry.isNotEmpty() -> copy(entry = entry.dropLast(1))
        tokens.isNotEmpty() -> copy(tokens = tokens.dropLast(2), entry = tokens.dropLast(1).lastOrNull().orEmpty())
        else -> this
    }

    private fun equals(): Calc {
        if (error != null || done) return this
        val all = (if (entry.isNotEmpty()) tokens + entry.trimEnd('.') else tokens.dropLast(1))
        if (all.size < 3) return this
        val shown = all.joinToString(" ") { if (it in OPS) it else group(it) } + " ="
        return try {
            Calc(entry = plain(evaluate(all)), done = true, shown = shown)
        } catch (e: ArithmeticException) {
            Calc(done = true, shown = shown, error = L("0 では割れません", "Can't divide by 0"))
        }
    }

    /** 大きく出す数。入力中の数、無ければ最後に確定した数、答え、エラーの文。 */
    fun display(): String = error ?: group(entry.ifEmpty { tokens.lastOrNull { it !in OPS } ?: "0" })

    /** 上に小さく出す式。「=」のあとは答えを出した式。 */
    fun expression(): String = if (done) shown else tokens.joinToString(" ") { if (it in OPS) it else group(it) }

    companion object {
        private const val MAX_DIGITS = 15
        /** 答えの有効桁数（カードの幅で読める桁数）。 */
        private const val SHOWN_DIGITS = 10
        private val OPS = setOf("+", "−", "×", "÷")
        private val CONTEXT = MathContext(16, RoundingMode.HALF_UP)

        /** 掛け算・割り算を先に、そのあと足し算・引き算を左から。 */
        fun evaluate(tokens: List<String>): BigDecimal {
            val terms = mutableListOf(BigDecimal(tokens[0]))
            val ops = mutableListOf<String>()
            var i = 1
            while (i + 1 < tokens.size) {
                val op = tokens[i]
                val v = BigDecimal(tokens[i + 1])
                when (op) {
                    "×" -> terms[terms.lastIndex] = terms.last().multiply(v, CONTEXT)
                    "÷" -> {
                        if (v.signum() == 0) throw ArithmeticException("divide by zero")
                        terms[terms.lastIndex] = terms.last().divide(v, CONTEXT)
                    }
                    else -> {
                        ops += op
                        terms += v
                    }
                }
                i += 2
            }
            var total = terms[0]
            ops.forEachIndexed { k, op -> total = if (op == "+") total.add(terms[k + 1]) else total.subtract(terms[k + 1]) }
            return total
        }

        /** 10 桁（整数部が長ければ 15 桁）に丸めて末尾の 0 を落とす。桁が多すぎる・小さすぎる数は「1.23e15」の形にする。 */
        fun plain(v: BigDecimal): String {
            // 整数部は 15 桁まで落とさない（123456789012 が丸まらないように）。小数は合わせて 10 桁まで
            val intDigits = (v.precision() - v.scale()).coerceAtLeast(1)
            val r = v.round(MathContext(intDigits.coerceIn(SHOWN_DIGITS, 15), RoundingMode.HALF_UP)).stripTrailingZeros()
            if (r.signum() == 0) return "0"
            val abs = r.abs()
            if (abs >= BigDecimal("1e15") || abs < BigDecimal("1e-9")) {
                val exp = r.precision() - r.scale() - 1
                val mantissa = r.movePointLeft(exp).round(MathContext(8)).stripTrailingZeros().toPlainString()
                return "${mantissa}e$exp"
            }
            return r.toPlainString()
        }

        /** 整数部を 3 桁ごとに区切る（"-1234.5" → "-1,234.5"）。指数の形はそのまま。 */
        fun group(s: String): String {
            if ('e' in s) return s
            val neg = s.startsWith("-")
            val body = s.removePrefix("-")
            val int = body.substringBefore('.')
            val rest = body.substring(int.length)
            val grouped = int.reversed().chunked(3).joinToString(",").reversed()
            return (if (neg) "-" else "") + grouped + rest
        }
    }
}
