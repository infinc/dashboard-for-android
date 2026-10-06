package app.dashboard.ui.dashboard

import app.dashboard.i18n.L
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import app.dashboard.data.CRYPTO_COINS
import app.dashboard.data.CRYPTO_RANGES
import app.dashboard.data.CryptoCandle
import app.dashboard.data.CryptoConfig
import app.dashboard.data.CryptoState
import app.dashboard.ui.common.EmptyText
import app.dashboard.ui.common.Tabular
import app.dashboard.ui.common.WdCard
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import app.dashboard.ui.theme.vhText
import java.util.Locale
import kotlin.math.abs

/** チャートの期間（[CryptoConfig.range]）の表示名。並びは [CRYPTO_RANGES] と同じ。 */
val CRYPTO_RANGE_LABELS: List<Pair<String, String>> get() = listOf("1" to L("24 時間", "24 h"), "7" to L("7 日", "7 d"), "30" to L("30 日", "30 d"), "365" to L("1 年", "1 y"))

/** チャートの描き方（[CryptoConfig.chart]）の表示名。 */
val CRYPTO_CHARTS: List<Pair<String, String>> get() = listOf("line" to L("折れ線", "Line"), "candle" to L("ろうそく足", "Candlestick"))

/**
 * 暗号通貨。設定で選んだ 1 つの通貨の値・変化率と、期間のチャート（折れ線かろうそく足）を 1 つだけ出す。
 * 上の行に記号・名前と変化率、その下に大きく値、残りの高さをチャートに使う。
 * 見出しの右の「−」「＋」で、設定を開かずにチャートの期間（横の幅）を短く・長くできる（[onStep] に −1 / +1）。
 * 見出しのすぐ右のボタンで画面いっぱいに出す（[CryptoScreen]。いろいろな通貨・期間・描き方のチャートを見られる）。
 */
@Composable
fun CryptoCard(s: CryptoState?, config: CryptoConfig, now: Long, onStep: (Int) -> Unit, onExpand: () -> Unit, modifier: Modifier) {
    // 設定を変えた直後は、前の通貨の値を出さない
    val c = s?.takeIf { it.coin == config.coin && it.currency == config.currency && it.range == config.range }
    val rangeLabel = CRYPTO_RANGE_LABELS.firstOrNull { it.first == config.range }?.second ?: config.range
    val index = CRYPTO_RANGES.indexOf(config.range)
    WdCard(L("暗号通貨", "Crypto"), modifier, titleAction = { ExpandButton(onExpand, Wd.Text3) }, headerEnd = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 幅が足りないときは、取得した時刻のほうを縮める
            if (c != null && c.fetchedAt > 0) {
                Text(
                    relative(c.fetchedAt, now), color = Wd.Text3, fontSize = 12.tu, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 7.dp),
                )
            }
            StepButton("−", L("期間を短くする", "Shorter period"), index > 0) { onStep(-1) }
            Text(rangeLabel, color = Wd.Text2, fontSize = 12.tu, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 50.dp).padding(horizontal = 3.dp))
            StepButton(L("＋", "+"), L("期間を長くする", "Longer period"), index < CRYPTO_RANGES.lastIndex) { onStep(1) }
        }
    }) {
        when {
            c?.price == null -> EmptyText(if (c?.lastError != null) L("取得できません: ${c.lastError}", "Unavailable: ${c.lastError}") else L("取得中…", "Loading…"), if (c?.lastError != null) Wd.Red else Wd.Text3)
            else -> {
                // 上がりは緑、下がりは赤
                val color = when {
                    c.changePercent == null -> Wd.Text3
                    c.changePercent >= 0 -> Wd.Green
                    else -> Wd.Red
                }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.symbol ?: config.coin.uppercase(), fontSize = 13.5f.tu, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            "  " + (c.name ?: CRYPTO_COINS.firstOrNull { it.first == config.coin }?.second ?: config.coin),
                            color = Wd.Text3, fontSize = 11.5f.tu, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text(
                            c.changePercent?.let { (if (it >= 0) "+" else "−") + "%.2f%%".format(Locale.US, abs(it)) } ?: "",
                            color = color, fontSize = 13.tu, fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        val price = money(c.price, c.currency)
                        Text(
                            price,
                            fontSize = vhText(3.4f, 20f, 30f), fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        // 桁の多い値（1 未満の通貨など）は、高値・安値を省いて値を最後まで出す
                        if (c.high != null && c.low != null && price.length <= WIDE_PRICE) {
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp, bottom = 3.dp)) {
                                Text(L("高 ", "H ") + money(c.high, c.currency), color = Wd.Text3, fontSize = 10.5f.tu, style = Tabular, maxLines = 1)
                                Text(L("安 ", "L ") + money(c.low, c.currency), color = Wd.Text3, fontSize = 10.5f.tu, style = Tabular, maxLines = 1)
                            }
                        }
                    }
                    val chart = Modifier.weight(1f).fillMaxWidth().padding(top = 5.dp)
                    // 描き方を変えた直後など、ろうそく足がまだ無いあいだは折れ線で出す
                    if (config.chart == "candle" && c.candles.size >= 2) CandleChart(c.candles, chart) else PriceChart(c.points, color, chart)
                    if (c.lastError != null) {
                        Text(L("更新できません: ${c.lastError}", "Couldn't update: ${c.lastError}"), color = Wd.Amber, fontSize = 10.5f.tu, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}

/** 見出しの行に収まる小さな「−」「＋」。端まで来たほうは薄くして押せなくする。 */
@Composable
private fun StepButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(26.dp, 20.dp).clip(RoundedCornerShape(6.dp)).background(Wd.Ink.copy(alpha = if (enabled) 0.1f else 0.04f))
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Wd.Text else Wd.Text3.copy(alpha = 0.5f), fontSize = 14.tu, lineHeight = 14.tu)
    }
}

/** 値の文字数がこれを超えたら、右の高値・安値を出さない。 */
private const val WIDE_PRICE = 11

/** 値の書き方。大きい値は整数、1 未満の値は有効数字 4 桁まで。 */
internal fun money(v: Double, currency: String): String {
    val mark = if (currency == "usd") "$" else "¥"
    val a = abs(v)
    return mark + when {
        a >= 1000 -> "%,.0f".format(Locale.US, v)
        a >= 1 -> "%,.2f".format(Locale.US, v)
        a == 0.0 -> "0"
        else -> java.math.BigDecimal(v).round(java.math.MathContext(4)).stripTrailingZeros().toPlainString()
    }
}

/** 期間の値動き。線の下を薄く塗り、期間の始まりの値に点線を引く。 */
@Composable
private fun PriceChart(points: List<Double>, color: Color, modifier: Modifier) {
    if (points.size < 2) {
        Spacer(modifier)
        return
    }
    val guide = Wd.Ink.copy(alpha = 0.18f)
    Canvas(modifier) {
        val lo = points.min()
        val hi = points.max().let { if (it - lo < 1e-12) lo + 1 else it }
        val pad = 2f * density
        fun y(v: Double) = pad + ((size.height - pad * 2) * (1 - (v - lo) / (hi - lo))).toFloat()
        val step = size.width / (points.size - 1)
        val line = Path()
        points.forEachIndexed { i, v -> if (i == 0) line.moveTo(0f, y(v)) else line.lineTo(i * step, y(v)) }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
        drawLine(guide, Offset(0f, y(points.first())), Offset(size.width, y(points.first())), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
        drawPath(line, color, style = Stroke(1.8f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** ろうそく足。終値が始値以上なら緑、下なら赤。細い線が高値〜安値、太い所が始値〜終値。 */
@Composable
private fun CandleChart(candles: List<CryptoCandle>, modifier: Modifier) {
    val up = Wd.Green
    val down = Wd.Red
    Canvas(modifier) {
        val lo = candles.minOf { it.low }
        val hi = candles.maxOf { it.high }.let { if (it - lo < 1e-12) lo + 1 else it }
        val pad = 2f * density
        fun y(v: Double) = pad + ((size.height - pad * 2) * (1 - (v - lo) / (hi - lo))).toFloat()
        val slot = size.width / candles.size
        val body = (slot * 0.64f).coerceAtLeast(1.5f * density)
        val wick = (1f * density).coerceAtMost(body)
        candles.forEachIndexed { i, c ->
            val color = if (c.close >= c.open) up else down
            val cx = slot * (i + 0.5f)
            drawLine(color, Offset(cx, y(c.high)), Offset(cx, y(c.low)), wick)
            val top = y(maxOf(c.open, c.close))
            // 始値と終値が同じ足も、線として見える高さにする
            val height = (y(minOf(c.open, c.close)) - top).coerceAtLeast(1f * density)
            drawRoundRect(color, Offset(cx - body / 2, top), Size(body, height), CornerRadius(0.8f * density))
        }
    }
}
