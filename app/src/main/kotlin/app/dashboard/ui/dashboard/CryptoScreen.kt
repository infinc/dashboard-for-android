package app.dashboard.ui.dashboard

import app.dashboard.i18n.L
import app.dashboard.i18n.Lang
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dashboard.data.CRYPTO_COINS
import app.dashboard.data.CRYPTO_DETAIL_RANGES
import app.dashboard.data.CryptoCandle
import app.dashboard.data.CryptoConfig
import app.dashboard.data.CryptoDetail
import app.dashboard.data.CryptoState
import app.dashboard.ui.common.Tabular
import app.dashboard.ui.theme.LocalAccent
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * 暗号通貨を画面いっぱいに出す（カードの見出しの右のボタンから開く）。
 * 上に通貨の名前・値・変化率と、時価総額・24 時間の出来高・期間の高値と安値・最高値。
 * その下で通貨（主な 13 種と設定の通貨）・期間（24 時間〜1 年）・描き方（折れ線／ろうそく足）・値の通貨（円／米ドル）を切り替えて、
 * 値のチャート（右に値の目盛り、下に時刻）と出来高の棒グラフを見る。チャートに触れている間は、その時刻の値を出す。
 * ここで切り替えたものは設定には残さない（カードはそのまま）。
 */
@Composable
fun CryptoScreen(
    card: CryptoState?,
    config: CryptoConfig,
    now: Long,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    load: suspend (coin: String, currency: String, days: String, candle: Boolean) -> Result<CryptoDetail>,
    onBack: () -> Unit,
) {
    var coin by remember { mutableStateOf(config.coin) }
    var currency by remember { mutableStateOf(config.currency) }
    var days by remember { mutableStateOf(config.range) }
    var candle by remember { mutableStateOf(config.chart == "candle") }
    var detail by remember { mutableStateOf<CryptoDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(coin, currency, days, candle) {
        // 取れなければ（混み合っているときなど）、全画面を開いている間は 30 秒ごとに試し直す
        while (true) {
            loading = true
            val r = load(coin, currency, days, candle)
            loading = false
            r.onSuccess { detail = it; error = null }.onFailure { error = it.message ?: L("取得できません", "Unavailable") }
            if (r.isSuccess) break
            delay(RETRY_MS)
        }
    }
    // 一覧に無い通貨（設定で ID を入れたもの）も選べるようにする
    val coins = CRYPTO_COINS.map { it.first to symbolOf(it.second) }.let { list ->
        if (list.any { it.first == config.coin }) list else listOf(config.coin to (card?.symbol ?: config.coin.uppercase())) + list
    }
    // 切り替えた直後は、前の通貨の値を新しい通貨として出さない
    val d = detail?.takeIf { it.coin == coin && it.currency == currency && it.days == days }

    FullscreenFrame(Wd.Bg, keepAwake, onKeepAwake, onBack, backTint = Wd.Text) { _, _ ->
        Column(Modifier.fillMaxSize().padding(start = 84.dp, end = 24.dp, top = 16.dp, bottom = 16.dp)) {
            Header(d, coin, days, currency, now, loading, error, Modifier.padding(end = 190.dp))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    coins.forEach { (id, symbol) -> Chip(symbol, id == coin) { coin = id } }
                }
                Spacer(Modifier.width(14.dp))
                Segmented(CRYPTO_DETAIL_RANGES, days) { days = it }
                Spacer(Modifier.width(10.dp))
                Segmented(listOf("line" to L("折れ線", "Line"), "candle" to L("ろうそく足", "Candlestick")), if (candle) "candle" else "line") { candle = it == "candle" }
                Spacer(Modifier.width(10.dp))
                Segmented(listOf("jpy" to "¥", "usd" to "$"), currency) { currency = it }
            }
            Spacer(Modifier.height(12.dp))
            if (d == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(error?.let { L("取得できません: $it", "Unavailable: $it") } ?: L("取得中…", "Loading…"), color = if (error != null) Wd.Red else Wd.Text3, fontSize = 16.tu)
                }
            } else {
                DetailChart(d, candle, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** 「ビットコイン（BTC）」から「BTC」を取り出す。 */
private fun symbolOf(label: String): String = Regex("[（(]([^）)]+)[）)]").find(label)?.groupValues?.get(1) ?: label

@Composable
private fun Header(d: CryptoDetail?, coin: String, days: String, currency: String, now: Long, loading: Boolean, error: String?, modifier: Modifier) {
    val range = CRYPTO_DETAIL_RANGES.firstOrNull { it.first == days }?.second ?: days
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(d?.symbol ?: coin.uppercase(), color = Wd.Text, fontSize = 30.tu, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("  " + (d?.name ?: ""), color = Wd.Text3, fontSize = 16.tu, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
            d?.rank?.let { Text(L("  時価総額 $it 位", "  Market cap #$it"), color = Wd.Text3, fontSize = 13.tu, modifier = Modifier.padding(bottom = 5.dp)) }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    loading -> L("読み込み中…", "Loading…")
                    error != null && d != null -> L("更新できません: $error", "Couldn't update: $error")
                    d != null -> L("${relative(d.fetchedAt, now)}に取得", "Fetched ${relative(d.fetchedAt, now)}")
                    else -> ""
                },
                color = if (error != null && !loading) Wd.Amber else Wd.Text3, fontSize = 12.tu, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 5.dp, start = 12.dp),
            )
        }
        if (d?.price == null) return@Column
        Row(verticalAlignment = Alignment.Bottom) {
            Text(money(d.price, currency), color = Wd.Text, fontSize = 44.tu, fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1)
            d.changePercent?.let {
                Text("  ${signed(it)}", color = trend(it), fontSize = 22.tu, fontWeight = FontWeight.SemiBold, style = Tabular, modifier = Modifier.padding(bottom = 6.dp))
                Text(L("（$range）", " ($range)"), color = Wd.Text3, fontSize = 14.tu, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (days != "1") d.change24h?.let {
                Text(L("  24 時間 ", "  24 h "), color = Wd.Text3, fontSize = 14.tu, modifier = Modifier.padding(bottom = 8.dp))
                Text(signed(it), color = trend(it), fontSize = 14.tu, style = Tabular, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Stat(L("時価総額", "Market cap"), d.marketCap?.let { compact(it, currency) })
            Stat(L("24 時間の出来高", "24 h volume"), d.volume24h?.let { compact(it, currency) })
            Stat(L("期間の高値", "Period high"), d.high?.let { money(it, currency) })
            Stat(L("期間の安値", "Period low"), d.low?.let { money(it, currency) })
            Stat(L("最高値", "All-time high"), d.ath?.let { money(it, currency) + (d.athChangePercent?.let { p -> L("（${signed(p)}）", " (${signed(p)})") } ?: "") })
        }
    }
}

@Composable
private fun Stat(label: String, value: String?) {
    if (value == null) return
    Column {
        Text(label, color = Wd.Text3, fontSize = 11.tu)
        Text(value, color = Wd.Text2, fontSize = 14.tu, style = Tabular, maxLines = 1)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Text(
        label,
        color = if (selected) Wd.OnAccent else Wd.Text2,
        fontSize = 13.tu,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent else Wd.Surface2)
            .border(1.dp, if (selected) accent else Wd.Border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun Segmented(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    val accent = LocalAccent.current
    Row(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Wd.Border, RoundedCornerShape(10.dp))) {
        options.forEach { (v, label) ->
            Text(
                label,
                color = if (v == value) Wd.OnAccent else Wd.Text2,
                fontSize = 13.tu,
                fontWeight = if (v == value) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.background(if (v == value) accent else Color.Transparent)
                    .clickable { onChange(v) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

/** 値のチャート（上）と出来高（下）。右に値の目盛り、下に時刻。触れている間はその時刻の値を出す。 */
@Composable
private fun DetailChart(d: CryptoDetail, candle: Boolean, modifier: Modifier) {
    var touchX by remember { mutableStateOf<Float?>(null) }
    /** 値のチャートの幅（px、右の目盛りの分を除く）。触れている点の時刻を求めるのに使う。 */
    var plotWidth by remember { mutableStateOf(1f) }
    val candles = if (candle) d.candles else emptyList()
    val up = Wd.Green
    val down = Wd.Red
    val color = trend(d.changePercent ?: 0.0)
    val grid = Wd.Ink.copy(alpha = 0.08f)
    val guide = Wd.Ink.copy(alpha = 0.22f)
    val axis = Wd.Text3
    val volumeColor = Wd.Ink.copy(alpha = 0.22f)
    // 時刻の範囲（ろうそく足は 1 本ぶんの幅を左に足す）
    val step = if (candles.size >= 2) (candles.last().time - candles.first().time) / (candles.size - 1) else 0L
    val t0 = minOf(d.times.firstOrNull() ?: 0L, (candles.firstOrNull()?.time ?: Long.MAX_VALUE) - step)
    val t1 = maxOf(d.times.lastOrNull() ?: 0L, candles.lastOrNull()?.time ?: 0L)
    val values = if (candles.isNotEmpty()) candles.flatMap { listOf(it.high, it.low) } else d.prices
    if (values.size < 2 || t1 <= t0) {
        Box(modifier, contentAlignment = Alignment.Center) { Text(L("チャートのデータがありません", "No chart data"), color = Wd.Text3, fontSize = 16.tu) }
        return
    }
    val lo = values.min()
    val hi = values.max().let { if (it - lo < 1e-12) lo + 1 else it }
    // 目盛りの文字の幅だけ右を空ける
    val gutter = 96.dp
    val ticks = List(5) { i -> lo + (hi - lo) * i / 4 }

    val zone = ZoneId.systemDefault()
    val gutterPx = with(LocalDensity.current) { gutter.toPx() }
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { plotWidth = (it.width - gutterPx).coerceAtLeast(1f) }) {
                Canvas(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            val down0 = awaitFirstDown()
                            touchX = down0.position.x
                            while (true) {
                                val event = awaitPointerEvent()
                                val c = event.changes.firstOrNull() ?: break
                                if (!c.pressed) break
                                touchX = c.position.x
                                c.consume()
                            }
                            touchX = null
                        }
                    },
                ) {
                    val w = size.width - gutter.toPx()
                    val h = size.height
                    val pad = 6.dp.toPx()
                    fun x(t: Long) = ((t - t0).toDouble() / (t1 - t0) * w).toFloat()
                    fun y(v: Double) = pad + ((h - pad * 2) * (1 - (v - lo) / (hi - lo))).toFloat()
                    ticks.forEach { v -> drawLine(grid, Offset(0f, y(v)), Offset(w, y(v)), 1f) }
                    val base = candles.firstOrNull()?.open ?: d.prices.first()
                    drawLine(guide, Offset(0f, y(base)), Offset(w, y(base)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    if (candles.isNotEmpty()) {
                        val slot = w / candles.size
                        val body = (slot * 0.62f).coerceAtLeast(2f)
                        candles.forEach { c ->
                            val cx = x(c.time - step / 2)
                            val col = if (c.close >= c.open) up else down
                            drawLine(col, Offset(cx, y(c.high)), Offset(cx, y(c.low)), 1.2f * density)
                            val top = y(maxOf(c.open, c.close))
                            drawRoundRect(col, Offset(cx - body / 2, top), Size(body, (y(minOf(c.open, c.close)) - top).coerceAtLeast(1.5f * density)), CornerRadius(1.5f))
                        }
                    } else {
                        val line = Path()
                        d.prices.forEachIndexed { i, v -> if (i == 0) line.moveTo(x(d.times[i]), y(v)) else line.lineTo(x(d.times[i]), y(v)) }
                        val fill = Path().apply {
                            addPath(line)
                            lineTo(x(d.times.last()), h)
                            lineTo(x(d.times.first()), h)
                            close()
                        }
                        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0f))))
                        drawPath(line, color, style = Stroke(2.2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    touchX?.let { tx ->
                        val cx = tx.coerceIn(0f, w)
                        drawLine(guide, Offset(cx, 0f), Offset(cx, h), 1.2f * density)
                        nearest(d, candles, t0 + ((cx / w) * (t1 - t0)).toLong(), step)?.let { (_, v) ->
                            drawCircle(color, 4.dp.toPx(), Offset(cx, y(v)))
                        }
                    }
                }
                // 値の目盛り（右）
                ticks.forEach { v ->
                    val frac = ((v - lo) / (hi - lo)).toFloat()
                    Box(Modifier.fillMaxSize()) {
                        Text(
                            money(v, d.currency), color = axis, fontSize = 11.tu, style = Tabular, maxLines = 1,
                            modifier = Modifier.align(BiasAlignment(1f, 1f - 2f * frac)).padding(start = 8.dp),
                        )
                    }
                }
            }
            // 出来高（CoinGecko の値は各時刻までの 24 時間の出来高なので、動きが見えるよう最小〜最大の幅で描く）
            Canvas(Modifier.fillMaxWidth().height(70.dp).padding(top = 8.dp)) {
                val w = size.width - gutter.toPx()
                val vs = d.volumes.filter { it > 0 }
                if (vs.size < 2) return@Canvas
                val vLo = vs.min() * 0.98
                val vHi = vs.max().let { if (it - vLo < 1e-9) vLo + 1 else it }
                val bar = (w / d.volumes.size * 0.7f).coerceAtLeast(1f)
                d.volumes.forEachIndexed { i, v ->
                    if (v <= 0) return@forEachIndexed
                    val bx = ((d.times[i] - t0).toDouble() / (t1 - t0) * w).toFloat()
                    val bh = (((v - vLo) / (vHi - vLo)) * size.height).toFloat().coerceAtLeast(1f)
                    drawRect(volumeColor, Offset(bx - bar / 2, size.height - bh), Size(bar, bh))
                }
            }
            // 時刻（下）
            Row(Modifier.fillMaxWidth().padding(end = gutter, top = 4.dp)) {
                List(5) { i -> t0 + (t1 - t0) * i / 4 }.forEachIndexed { i, t ->
                    Text(
                        timeLabel(t, d.days, zone), color = axis, fontSize = 11.tu, maxLines = 1,
                        textAlign = when (i) { 0 -> TextAlign.Start; 4 -> TextAlign.End; else -> TextAlign.Center },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Text(L("24 時間の出来高", "24 h volume"), color = axis, fontSize = 11.tu, modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 40.dp))
        // 触れている点の値（左上）
        touchX?.let { tx ->
            val t = t0 + ((tx.coerceIn(0f, plotWidth) / plotWidth) * (t1 - t0)).toLong()
            nearest(d, candles, t, step)?.let { hit -> TouchLabel(d, candles, hit, zone, Modifier.align(Alignment.TopStart).padding(8.dp)) }
        }
    }
}

@Composable
private fun TouchLabel(d: CryptoDetail, candles: List<CryptoCandle>, hit: Pair<Long, Double>, zone: ZoneId, modifier: Modifier) {
    val c = candles.firstOrNull { it.time == hit.first }
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(Wd.Surface.copy(alpha = 0.92f))
            .border(1.dp, Wd.Border, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(timeLabel(hit.first, "full", zone), color = Wd.Text3, fontSize = 12.tu)
        if (c != null) {
            Text(
                L("始 ${money(c.open, d.currency)}  高 ${money(c.high, d.currency)}  安 ${money(c.low, d.currency)}  終 ${money(c.close, d.currency)}", "O ${money(c.open, d.currency)}  H ${money(c.high, d.currency)}  L ${money(c.low, d.currency)}  C ${money(c.close, d.currency)}"),
                color = Wd.Text, fontSize = 14.tu, style = Tabular,
            )
        } else {
            Text(money(hit.second, d.currency), color = Wd.Text, fontSize = 18.tu, fontWeight = FontWeight.SemiBold, style = Tabular)
        }
    }
}

/** 時刻 [t] にいちばん近い点（時刻と値）。ろうそく足なら終値。 */
private fun nearest(d: CryptoDetail, candles: List<CryptoCandle>, t: Long, step: Long): Pair<Long, Double>? =
    if (candles.isNotEmpty()) candles.minByOrNull { abs(it.time - step / 2 - t) }?.let { it.time to it.close }
    else d.times.indices.minByOrNull { abs(d.times[it] - t) }?.let { d.times[it] to d.prices[it] }

private fun timeLabel(t: Long, days: String, zone: ZoneId): String {
    val z = Instant.ofEpochMilli(t).atZone(zone)
    return when (days) {
        "1" -> "%d:%02d".format(z.hour, z.minute)
        "365" -> "${z.year}/${z.monthValue}"
        "full" -> "${z.year}/${z.monthValue}/${z.dayOfMonth} %d:%02d".format(z.hour, z.minute)
        else -> "${z.monthValue}/${z.dayOfMonth}"
    }
}

@Composable
private fun trend(p: Double): Color = if (p >= 0) Wd.Green else Wd.Red

private fun signed(p: Double): String = (if (p >= 0) "+" else "−") + "%.2f%%".format(Locale.US, abs(p))

/** 大きな金額を「1.23 兆」「4.5 億」（米ドルは T / B / M）で短く書く。 */
private fun compact(v: Double, currency: String): String {
    val mark = if (currency == "usd") "$" else "¥"
    return mark + if (currency == "usd" || Lang.en) when {
        v >= 1e12 -> "%.2fT".format(Locale.US, v / 1e12)
        v >= 1e9 -> "%.2fB".format(Locale.US, v / 1e9)
        v >= 1e6 -> "%.2fM".format(Locale.US, v / 1e6)
        else -> "%,.0f".format(Locale.US, v)
    } else when {
        v >= 1e12 -> "%.2f 兆".format(Locale.US, v / 1e12)
        v >= 1e8 -> "%.1f 億".format(Locale.US, v / 1e8)
        v >= 1e4 -> "%.0f 万".format(Locale.US, v / 1e4)
        else -> "%,.0f".format(Locale.US, v)
    }
}

private const val RETRY_MS = 30_000L
