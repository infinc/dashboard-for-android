package app.walldash.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.walldash.data.CalendarEvent
import app.walldash.data.CalendarState
import app.walldash.data.Countdown
import app.walldash.data.CountdownConfig
import app.walldash.data.Holiday
import app.walldash.data.StockQuote
import app.walldash.data.StocksState
import app.walldash.data.TodayState
import app.walldash.data.TrainState
import app.walldash.ui.common.EmptyText
import app.walldash.ui.common.Hairline
import app.walldash.ui.common.Tabular
import app.walldash.ui.common.WdCard
import app.walldash.ui.theme.LocalAccent
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.colorOf
import app.walldash.ui.theme.tu
import app.walldash.ui.theme.vhText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------- 運行情報

@Composable
fun TrainCard(t: TrainState?, enabled: Boolean, configured: Boolean, now: Long, modifier: Modifier) {
    val lines = t?.lines.orEmpty()
    val trouble = lines.any { it.trouble }
    WdCard(
        "運行情報",
        modifier,
        note = if (enabled && t != null && t.fetchedAt > 0) relative(t.fetchedAt, now) else null,
        titleColor = if (trouble) Wd.Amber else Wd.Text3,
        borderColor = if (trouble) Wd.Amber.copy(alpha = 0.5f) else Wd.Border,
    ) {
        when {
            !enabled || !configured -> EmptyText("運行情報は未設定です。設定画面の「運行情報」で ODPT のトークンを登録してください。")
            t == null || t.fetchedAt == 0L -> EmptyText(if (t?.lastError != null) "取得できません: ${t.lastError}" else "取得中…", if (t?.lastError != null) Wd.Red else Wd.Text3)
            lines.isEmpty() -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Text("すべて平常運転", color = Wd.Green, fontSize = vhText(2.6f, 16f, 22f), fontWeight = FontWeight.SemiBold)
                Text("遅れ・運転見合わせの路線はありません", color = Wd.Text3, fontSize = 12.tu, modifier = Modifier.padding(top = 4.dp))
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                lines.forEachIndexed { i, line ->
                    if (i > 0) Hairline()
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(4.dp, 16.dp).clip(RoundedCornerShape(2.dp))
                                    .background(line.color?.let { colorOf(it, Wd.Text3) } ?: Wd.Text3),
                            )
                            Spacer(Modifier.width(7.dp))
                            // 路線名と事業者名で残りの幅を使い切り、状態は必ず右端に置く
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Text(line.title, fontSize = 13.5f.tu, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (line.operator != null) {
                                    Text("  " + line.operator, color = Wd.Text3, fontSize = 11.tu, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Text(
                                line.status,
                                color = when {
                                    !line.trouble && line.status == "情報なし" -> Wd.Text3
                                    !line.trouble -> Wd.Green
                                    "見合わせ" in line.status || "運休" in line.status -> Wd.Red
                                    else -> Wd.Amber
                                },
                                fontSize = 12.5f.tu,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        if (line.trouble && !line.text.isNullOrBlank()) {
                            Text(line.text, color = Wd.Text2, fontSize = 11.5f.tu, lineHeight = 1.45.em, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 11.dp, top = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 今日は何の日

@Composable
fun TodayCard(t: TodayState?, now: Long, showEvent: Boolean, modifier: Modifier) {
    val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    val stale = t == null || t.date != date.toLocalDate().toString()
    WdCard("今日は何の日", modifier, note = "${date.monthValue}月${date.dayOfMonth}日") {
        when {
            t == null || t.days.isEmpty() && t.events.isEmpty() ->
                EmptyText(if (t?.lastError != null) "取得できません: ${t.lastError}" else "取得中…", if (t?.lastError != null) Wd.Red else Wd.Text3)
            else -> Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (stale) Text("（前日までの情報です）", color = Wd.Text3, fontSize = 11.tu)
                    t.days.forEachIndexed { i, item ->
                        Row(Modifier.padding(top = if (i == 0) 0.dp else 5.dp), verticalAlignment = Alignment.Top) {
                            Text(
                                item.name,
                                fontSize = if (i == 0) vhText(2.3f, 15f, 19f) else 13.5f.tu,
                                fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
                                lineHeight = 1.35.em,
                                modifier = Modifier.weight(1f),
                            )
                            item.region?.let { Text(it, color = Wd.Text3, fontSize = 11.tu, modifier = Modifier.padding(start = 6.dp, top = 2.dp)) }
                        }
                        if (i == 0 && item.note != null) {
                            Text(item.note, color = Wd.Text2, fontSize = 11.5f.tu, lineHeight = 1.5.em, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
                // できごとは 1 時間ごとに入れ替える
                if (showEvent && t.events.isNotEmpty()) {
                    val event = t.events[((now / 3_600_000L) % t.events.size).toInt()]
                    Hairline(Modifier.padding(top = 6.dp))
                    Text(event, color = Wd.Text2, fontSize = 11.5f.tu, lineHeight = 1.45.em, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 予定表

@Composable
fun CalendarCard(c: CalendarState?, enabled: Boolean, configured: Boolean, now: Long, modifier: Modifier) {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    // 終わった予定は消す（終日の予定はその日のうちは残す）
    val events = c?.events.orEmpty().filter { it.end > now || it.allDay && day(it.start, zone) == today }
    WdCard("予定表", modifier, note = if (enabled && configured) "${events.size} 件" else null) {
        when {
            !enabled || !configured -> EmptyText("予定表は未設定です。設定画面の「予定表」で iCloud と連携してください。")
            c == null || c.fetchedAt == 0L -> EmptyText("取得中…")
            c.lastError != null && events.isEmpty() -> EmptyText("取得できません: ${c.lastError}", Wd.Red)
            events.isEmpty() -> EmptyText("予定はありません。")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                var shownDay: LocalDate? = null
                events.forEach { ev ->
                    val d = maxOf(day(ev.start, zone), today)
                    if (d != shownDay) {
                        shownDay = d
                        val label = when (d) {
                            today -> "今日"
                            today.plusDays(1) -> "明日"
                            else -> "${d.monthValue}/${d.dayOfMonth}（${"月火水木金土日"[d.dayOfWeek.value - 1]}）"
                        }
                        Text(label, color = if (d == today) LocalAccent.current else Wd.Text3, fontSize = 11.tu, letterSpacing = 0.1.em, modifier = Modifier.padding(top = if (d == today && ev == events.first()) 0.dp else 7.dp, bottom = 2.dp))
                    }
                    EventRow(ev, zone)
                }
            }
        }
    }
}

private fun day(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

@Composable
private fun EventRow(ev: CalendarEvent, zone: ZoneId) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(ev.color?.let { colorOf(it, LocalAccent.current) } ?: LocalAccent.current))
        Spacer(Modifier.width(7.dp))
        val time = if (ev.allDay) "終日" else Instant.ofEpochMilli(ev.start).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
        Text(time, color = Wd.Text2, fontSize = 12.tu, style = Tabular, modifier = Modifier.widthIn(min = 40.dp))
        Text(ev.title, fontSize = 13.5f.tu, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------- 株価

@Composable
fun StocksCard(s: StocksState?, range: String, now: Long, modifier: Modifier) {
    val quotes = s?.quotes.orEmpty()
    val rangeLabel = mapOf("1d" to "1 日", "5d" to "5 日", "1mo" to "1 か月", "6mo" to "6 か月", "1y" to "1 年")[range] ?: range
    WdCard("株価", modifier, note = if (s != null && s.fetchedAt > 0) "$rangeLabel ・ ${relative(s.fetchedAt, now)}" else rangeLabel) {
        when {
            quotes.isEmpty() -> EmptyText(if (s?.lastError != null) "取得できません: ${s.lastError}" else "取得中…", if (s?.lastError != null) Wd.Red else Wd.Text3)
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                quotes.forEachIndexed { i, q ->
                    if (i > 0) Hairline()
                    QuoteRow(q, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QuoteRow(q: StockQuote, modifier: Modifier) {
    // 上がりは緑、下がりは赤
    val up = (q.changePercent ?: 0.0) >= 0
    val color = if (q.changePercent == null) Wd.Text3 else if (up) Wd.Green else Wd.Red
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(q.label, color = Wd.Text2, fontSize = 12.5f.tu, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(76.dp))
        Sparkline(q.points, q.base, color, Modifier.weight(1f).fillMaxHeight().padding(vertical = 3.dp, horizontal = 6.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 86.dp)) {
            Text(q.price?.let(::price) ?: "—", fontSize = 14.tu, fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1)
            Text(
                q.changePercent?.let { (if (it >= 0) "+" else "−") + "%.2f%%".format(Locale.US, abs(it)) } ?: "",
                color = color, fontSize = 11.5f.tu, style = Tabular, maxLines = 1,
            )
        }
    }
}

private fun price(v: Double): String = if (v >= 1000) "%,.0f".format(Locale.US, v) else "%,.2f".format(Locale.US, v)

@Composable
private fun Sparkline(points: List<Double>, base: Double?, color: Color, modifier: Modifier) {
    if (points.size < 2) {
        Spacer(modifier)
        return
    }
    val guide = Wd.Ink.copy(alpha = 0.18f)
    Canvas(modifier) {
        val lo = min(points.min(), base ?: points.min())
        val hi = max(points.max(), base ?: points.max()).let { if (it - lo < 1e-9) lo + 1 else it }
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
        val step = size.width / (points.size - 1)
        base?.let {
            drawLine(guide, Offset(0f, y(it)), Offset(size.width, y(it)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
        }
        val path = Path()
        points.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * step, y(v)) }
        drawPath(path, color, style = Stroke(1.6f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---------------------------------------------------------------- カウントダウン

@Composable
fun CountdownCard(config: CountdownConfig, holidays: List<Holiday>, now: Long, modifier: Modifier) {
    // 行事の日付は分が変わるたびに求め直せば十分（残り時間の表示は毎秒）
    val targets = remember(config, holidays, now / 60_000L) { Countdown.targets(config, holidays, now) }
    WdCard("カウントダウン", modifier) {
        when {
            targets.isEmpty() -> EmptyText("数える行事がありません。設定画面の「カウントダウン」で選んでください。")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                targets.forEachIndexed { i, t ->
                    if (i > 0) Hairline()
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name + "まで", fontSize = 13.5f.tu, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val note = t.note ?: Instant.ofEpochMilli(t.at).atZone(ZoneId.systemDefault()).let { "${it.monthValue}月${it.dayOfMonth}日 %02d:%02d".format(it.hour, it.minute) }
                            Text(note, color = Wd.Text3, fontSize = 11.tu, maxLines = 1)
                        }
                        Remaining(t.at - now, i == 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun Remaining(ms: Long, first: Boolean) {
    val accent = LocalAccent.current
    val sec = ms / 1000
    val days = sec / 86_400
    if (days >= 1) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("あと ", color = Wd.Text3, fontSize = 11.tu, modifier = Modifier.padding(bottom = 2.dp))
            Text(
                "%,d".format(Locale.US, days),
                color = if (first) accent else Wd.Text,
                fontSize = if (first) vhText(3.2f, 20f, 28f) else vhText(2.4f, 16f, 21f),
                fontWeight = FontWeight.SemiBold,
                style = Tabular,
                textAlign = TextAlign.End,
            )
            Text(" 日", color = Wd.Text2, fontSize = 11.5f.tu, modifier = Modifier.padding(bottom = 2.dp))
        }
    } else {
        Text(
            "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60),
            color = Wd.Amber,
            fontSize = if (first) vhText(3.0f, 19f, 26f) else vhText(2.3f, 15f, 20f),
            fontWeight = FontWeight.SemiBold,
            style = Tabular,
        )
    }
}
