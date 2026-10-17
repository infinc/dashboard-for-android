package app.dashboard.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.dashboard.data.WidgetData
import app.dashboard.data.WidgetDef
import app.dashboard.data.WidgetFormat
import app.dashboard.data.WidgetItem
import app.dashboard.data.WidgetLevel
import app.dashboard.data.WidgetPoint
import app.dashboard.data.WidgetRepository
import app.dashboard.i18n.L
import app.dashboard.i18n.SEP
import app.dashboard.ui.common.EmptyText
import app.dashboard.ui.common.Hairline
import app.dashboard.ui.common.Tabular
import app.dashboard.ui.common.WdCard
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import kotlin.math.max
import kotlin.math.min

/** 段の色（ふつうは [ok]、注意は琥珀、警戒は赤）。 */
@Composable
internal fun levelColor(level: Int, ok: Color = Wd.Text): Color = when (level) {
    WidgetLevel.ALERT -> Wd.Red
    WidgetLevel.WARN -> Wd.Amber
    else -> ok
}

/**
 * 自作カード 1 枚。見せ方（[WidgetDef.shownStyle]）ごとに中身を描く。
 * 数値・メーターで履歴が 2 点以上あれば、見出しの右に全画面のボタン（[onExpand]、24 時間のグラフ）。
 * [pushEnabled] は「外から送る」口が開いているか（閉じていれば、その旨を出す）。
 */
@Composable
fun WidgetCard(def: WidgetDef, data: WidgetData?, pushEnabled: Boolean, now: Long, onExpand: () -> Unit, modifier: Modifier) {
    // 設定が変わった直後の、前の設定の値は出さない
    val d = data?.takeIf { it.key == def.dataKey }
    val style = def.shownStyle
    val canExpand = def.keepsHistory && (d?.history?.size ?: 0) >= 2
    val note = when {
        d == null -> null
        d.lastError != null && d.updatedAt > 0 -> L("更新できません", "Can't update")
        style == "monitor" && d.items.isNotEmpty() -> L("${d.items.count { it.level == WidgetLevel.OK }}/${d.items.size} 応答", "${d.items.count { it.level == WidgetLevel.OK }}/${d.items.size} up")
        d.updatedAt > 0 -> relative(d.updatedAt, now)
        else -> null
    }
    val level = d?.let { levelOf(def, it) } ?: WidgetLevel.OK
    WdCard(
        def.label,
        modifier,
        note = note,
        borderColor = when (level) {
            WidgetLevel.ALERT -> Wd.Red.copy(alpha = 0.7f)
            WidgetLevel.WARN -> Wd.Amber.copy(alpha = 0.55f)
            else -> Wd.Border
        },
        titleAction = if (canExpand) ({ ExpandButton(onExpand, color = Wd.Text3) }) else null,
    ) {
        val empty = emptyMessage(def, d, pushEnabled)
        if (empty != null) {
            EmptyText(empty.first, if (empty.second) Wd.Red else Wd.Text3)
            if (def.source == "push" && pushEnabled && d == null) {
                Text(
                    "POST /api/widgets/${def.id}",
                    color = Wd.Text3, fontSize = 11.tu, style = Tabular, modifier = Modifier.padding(top = 6.dp),
                )
            }
            return@WdCard
        }
        val v = d!!
        when (style) {
            "number" -> NumberBody(def, v, Modifier.fillMaxSize())
            "gauge" -> GaugeBody(def, v, Modifier.fillMaxSize())
            "list" -> ListBody(v.items, Modifier.fillMaxSize())
            "text" -> TextBody(v.text.orEmpty(), Modifier.fillMaxSize())
            "monitor" -> MonitorBody(v.items, Modifier.fillMaxSize())
        }
    }
}

/** カードのいちばん重い段（枠の色に使う）。 */
internal fun levelOf(def: WidgetDef, d: WidgetData): Int = when (def.shownStyle) {
    "number", "gauge" -> WidgetLevel.of(d.number, def)
    "list", "monitor" -> WidgetLevel.worst(d.items)
    else -> WidgetLevel.OK
}

/** 値が無いときの文と、それが失敗か（赤で出す）。値があれば null。 */
private fun emptyMessage(def: WidgetDef, d: WidgetData?, pushEnabled: Boolean): Pair<String, Boolean>? {
    val hasValue = d != null && when (def.shownStyle) {
        "number", "gauge" -> d.number != null
        "text" -> d.text != null
        else -> d.items.isNotEmpty() || d.updatedAt > 0
    }
    if (hasValue) return null
    return when {
        !WidgetRepository.configured(def) -> L(
            "設定の「自作カード」で、どこから取るかを決めてください。",
            "Choose where to get the value under \"Custom cards\" in Settings.",
        ) to false
        def.source == "push" && !pushEnabled -> L(
            "設定の「自作カード」で「外から送る」口を有効にしてください。",
            "Enable pushing under \"Custom cards\" in Settings.",
        ) to false
        def.source == "push" -> L("まだ値が届いていません。", "No value has arrived yet.") to false
        d?.lastError != null -> d.lastError to true
        else -> L("取得中です…", "Fetching…") to false
    }
}

// ---------------------------------------------------------------- 数値

@Composable
private fun NumberBody(def: WidgetDef, d: WidgetData, modifier: Modifier) {
    val value = d.number ?: return
    val level = WidgetLevel.of(value, def)
    val history = d.history
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(if (history.size >= 2) 0.55f else 1f)) {
            val shown = WidgetFormat.number(value, def.decimals)
            val unit = d.unit.orEmpty()
            // 幅と高さの両方に収まる大きさ（数字はおよそ 0.6 字幅）
            val chars = shown.length + if (unit.isEmpty()) 0 else (unit.length * 0.55f + 0.5f).toInt()
            val byWidth = maxWidth.value / max(3f, chars * 0.62f)
            val size = min(maxHeight.value * 0.78f, byWidth).coerceIn(18f, 96f)
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.Bottom) {
                Text(
                    shown, color = levelColor(level), fontSize = size.tu, fontWeight = FontWeight.SemiBold,
                    style = Tabular, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                )
                if (unit.isNotEmpty()) {
                    Text(
                        unit, color = Wd.Text2, fontSize = (size * 0.38f).coerceAtLeast(11f).tu, maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp, bottom = (size * 0.12f).dp),
                    )
                }
            }
        }
        if (history.size >= 2) {
            val lo = history.minOf { it.v }
            val hi = history.maxOf { it.v }
            Sparkline(history, def, levelColor(level, Wd.Cyan), Modifier.fillMaxWidth().weight(0.45f).padding(top = 6.dp))
            Text(
                L("24 時間", "24 h") + "  " +
                    L("最高 ", "high ") + WidgetFormat.number(hi, def.decimals) + SEP +
                    L("最低 ", "low ") + WidgetFormat.number(lo, def.decimals),
                color = Wd.Text3, fontSize = 10.5f.tu, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        } else {
            thresholdLine(def, d.unit)?.let { Text(it, color = Wd.Text3, fontSize = 10.5f.tu, maxLines = 1) }
        }
    }
}

/** しきい値の説明（「注意 28 ℃ ・ 警戒 31 ℃ 以上」）。無ければ null。 */
internal fun thresholdLine(def: WidgetDef, unit: String?): String? {
    val parts = listOfNotNull(
        def.warnAt?.let { L("注意 ", "warn ") + WidgetFormat.withUnit(WidgetFormat.number(it, def.decimals), unit) },
        def.alertAt?.let { L("警戒 ", "alert ") + WidgetFormat.withUnit(WidgetFormat.number(it, def.decimals), unit) },
    )
    if (parts.isEmpty()) return null
    return parts.joinToString(SEP) + if (def.below) L(" 以下", " or below") else L(" 以上", " or above")
}

/** 小さな折れ線（下を薄く塗る）。しきい値があれば点線を引く。 */
@Composable
private fun Sparkline(points: List<WidgetPoint>, def: WidgetDef, color: Color, modifier: Modifier) {
    val amber = Wd.Amber
    val red = Wd.Red
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val t0 = points.first().t
        val t1 = max(points.last().t, t0 + 1)
        var lo = points.minOf { it.v }
        var hi = points.maxOf { it.v }
        // 近いしきい値は枠に入れる（線が見えるように）
        listOfNotNull(def.warnAt, def.alertAt).forEach { at ->
            val span = max(hi - lo, 1e-9)
            if (at >= lo - span * 0.5 && at <= hi + span * 0.5) {
                lo = min(lo, at)
                hi = max(hi, at)
            }
        }
        if (hi - lo < 1e-9) { hi += 0.5; lo -= 0.5 }
        val pad = 2.dp.toPx()
        fun x(t: Long) = ((t - t0).toFloat() / (t1 - t0)) * size.width
        fun y(v: Double) = pad + (size.height - pad * 2) * (1f - ((v - lo) / (hi - lo)).toFloat())
        val line = Path()
        points.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(p.t), y(p.v)) else line.lineTo(x(p.t), y(p.v)) }
        val fill = Path().apply {
            addPath(line)
            lineTo(x(points.last().t), size.height)
            lineTo(x(points.first().t), size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f))))
        drawPath(line, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        def.warnAt?.takeIf { it in lo..hi }?.let { drawLine(amber.copy(alpha = 0.6f), Offset(0f, y(it)), Offset(size.width, y(it)), 1f, pathEffect = dash) }
        def.alertAt?.takeIf { it in lo..hi }?.let { drawLine(red.copy(alpha = 0.6f), Offset(0f, y(it)), Offset(size.width, y(it)), 1f, pathEffect = dash) }
        val last = points.last()
        drawCircle(color, 2.6.dp.toPx(), Offset(x(last.t), y(last.v)))
    }
}

// ---------------------------------------------------------------- メーター

@Composable
private fun GaugeBody(def: WidgetDef, d: WidgetData, modifier: Modifier) {
    val value = d.number ?: return
    val level = WidgetLevel.of(value, def)
    val color = levelColor(level, Wd.Cyan)
    val track = Wd.BorderSoft
    val amber = Wd.Amber
    val red = Wd.Red
    BoxWithConstraints(modifier) {
        // 半円が幅と高さの両方に入る大きさ
        val diameter = min(maxWidth.value, maxHeight.value * 1.55f)
        val stroke = (diameter * 0.09f).coerceIn(6f, 22f)
        Canvas(Modifier.align(Alignment.TopCenter).size(diameter.dp, (diameter / 2 + stroke / 2).dp)) {
            val sw = stroke.dp.toPx()
            val r = (size.width - sw) / 2
            val topLeft = Offset(sw / 2, sw / 2)
            val arc = Size(r * 2, r * 2)
            fun frac(v: Double) = ((v - def.min) / (def.max - def.min)).toFloat().coerceIn(0f, 1f)
            drawArc(track, 180f, 180f, false, topLeft, arc, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(color, 180f, 180f * frac(value), false, topLeft, arc, style = Stroke(sw, cap = StrokeCap.Round))
            // しきい値の目盛り
            val center = Offset(size.width / 2, sw / 2 + r)
            listOfNotNull(def.warnAt?.let { it to amber }, def.alertAt?.let { it to red }).forEach { (at, c) ->
                val a = Math.toRadians(180.0 + 180.0 * frac(at))
                val inner = r - sw * 0.9f
                val outer = r + sw * 0.9f
                drawLine(
                    c,
                    Offset(center.x + (inner * kotlin.math.cos(a)).toFloat(), center.y + (inner * kotlin.math.sin(a)).toFloat()),
                    Offset(center.x + (outer * kotlin.math.cos(a)).toFloat(), center.y + (outer * kotlin.math.sin(a)).toFloat()),
                    2.dp.toPx(),
                )
            }
        }
        val valueSize = (diameter * 0.2f).coerceIn(16f, 64f)
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = (diameter * 0.2f).dp).width(diameter.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                WidgetFormat.number(value, def.decimals), color = levelColor(level), fontSize = valueSize.tu,
                fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1, softWrap = false,
            )
            d.unit?.takeIf { it.isNotBlank() }?.let { Text(it, color = Wd.Text2, fontSize = (valueSize * 0.4f).coerceAtLeast(11f).tu, maxLines = 1) }
        }
        Row(Modifier.align(Alignment.TopCenter).width(diameter.dp).padding(top = (diameter / 2 + stroke).dp)) {
            Text(WidgetFormat.number(def.min, def.decimals), color = Wd.Text3, fontSize = 10.5f.tu, style = Tabular, modifier = Modifier.weight(1f))
            Text(WidgetFormat.number(def.max, def.decimals), color = Wd.Text3, fontSize = 10.5f.tu, style = Tabular, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------- 一覧・文字・監視

@Composable
private fun ListBody(items: List<WidgetItem>, modifier: Modifier) {
    if (items.isEmpty()) {
        EmptyText(L("項目がありません", "No items"))
        return
    }
    Column(modifier.verticalScroll(rememberScrollState())) {
        items.forEachIndexed { i, it ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it.label, color = Wd.Text2, fontSize = 13.tu, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(it.value, color = levelColor(it.level), fontSize = 14.tu, fontWeight = FontWeight.Medium, style = Tabular, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TextBody(text: String, modifier: Modifier) {
    val size: TextUnit = when {
        text.length <= 12 -> 30.tu
        text.length <= 40 -> 20.tu
        text.length <= 120 -> 15.tu
        else -> 13.tu
    }
    Box(modifier.verticalScroll(rememberScrollState())) {
        Text(text, color = Wd.Text, fontSize = size, lineHeight = 1.45.em)
    }
}

@Composable
private fun MonitorBody(items: List<WidgetItem>, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach {
            val c = levelColor(it.level, Wd.Green)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(9.dp))
                Text(it.label, color = Wd.Text, fontSize = 13.5f.tu, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(it.value, color = if (it.level == WidgetLevel.OK) Wd.Text2 else c, fontSize = 13.tu, style = Tabular, maxLines = 1)
            }
        }
    }
}
