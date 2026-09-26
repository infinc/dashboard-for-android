package app.walldash.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.walldash.data.DisplayConfig
import app.walldash.data.FeedState
import app.walldash.data.MemoState
import app.walldash.data.UnitsConfig
import app.walldash.data.WeatherState
import app.walldash.data.Words
import app.walldash.ui.common.EmptyText
import app.walldash.ui.common.Hairline
import app.walldash.ui.common.Tabular
import app.walldash.ui.common.WdCard
import app.walldash.ui.theme.LocalAccent
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.tu
import app.walldash.ui.theme.vh
import app.walldash.ui.theme.vhText
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val WDAY = listOf("日", "月", "火", "水", "木", "金", "土")

fun wday(date: ZonedDateTime): String = WDAY[date.dayOfWeek.value % 7]

/** 「12 分前」のような相対時刻。 */
fun relative(ts: Long, now: Long): String {
    if (ts <= 0) return "未取得"
    val min = ((now - ts) / 60000.0).roundToInt()
    return when {
        min <= 0 -> "たった今"
        min < 60 -> "$min 分前"
        min / 60 < 24 -> "${min / 60} 時間前"
        else -> "${min / 60 / 24} 日前"
    }
}

/** 気象庁や Open-Meteo の "2026-09-19T21:26:00+09:00" から時刻だけを取り出す。 */
fun hhmm(iso: String?): String = iso?.let { Regex("T(\\d{2}):(\\d{2})").find(it) }
    ?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }.orEmpty()

/** 同じ形の文字列から「9/19 21:26」（月/日 時:分）を取り出す。日付が読めなければ時刻だけ。 */
fun dateTime(iso: String?): String = iso?.let { Regex("\\d{4}-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})").find(it) }
    ?.let { "${it.groupValues[1].toInt()}/${it.groupValues[2].toInt()} ${it.groupValues[3]}:${it.groupValues[4]}" }
    ?: hhmm(iso)

// ---------------------------------------------------------------- 時刻

@Composable
fun ClockCard(now: Long, units: UnitsConfig, display: DisplayConfig, weather: WeatherState?, deviceZone: String?, modifier: Modifier) {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(now).atZone(zone)
    val offsetMin = d.offset.totalSeconds / 60
    val tz = "UTC" + (if (offsetMin < 0) "-" else "+") + abs(offsetMin) / 60 +
        (if (abs(offsetMin) % 60 != 0) ":" + "%02d".format(abs(offsetMin) % 60) else "")

    val h = d.hour
    val shownHour = if (units.clock24h) "%02d".format(h) else ((h % 12).takeIf { it != 0 } ?: 12).toString()
    val suffix = if (units.clock24h) "" else if (h < 12) "AM" else "PM"
    val date = if (display.clockDateFormat == "slash") {
        d.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + " (" + wday(d) + ")"
    } else {
        "${d.year}年${d.monthValue}月${d.dayOfMonth}日 (${wday(d)})"
    }
    val sub = weather?.timezone?.takeIf { it != deviceZone }?.let { tzId ->
        runCatching {
            weather.placeName + " は " + Instant.ofEpochMilli(now).atZone(ZoneId.of(tzId))
                .format(DateTimeFormatter.ofPattern("HH:mm"))
        }.getOrNull()
    }.orEmpty()

    val align = when (display.clockAlign) {
        "center" -> Alignment.CenterHorizontally
        "right" -> Alignment.End
        else -> Alignment.Start
    }
    val accent = LocalAccent.current
    WdCard("時刻", modifier, note = tz) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
            val big = vhText(11f, 48f, 92f)
            Text(
                buildAnnotatedString {
                    append("$shownHour:%02d".format(d.minute))
                    withStyle(SpanStyle(fontSize = big * 0.28f, color = accent, fontWeight = FontWeight.SemiBold)) {
                        if (units.showSeconds) append(" %02d".format(d.second))
                        if (suffix.isNotEmpty()) append(" $suffix")
                    }
                },
                fontSize = big,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.03).em,
                lineHeight = 0.94.em,
                style = Tabular,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            Text(date, color = Wd.Text2, fontSize = vhText(1.9f, 13f, 17f))
            if (sub.isNotEmpty()) Text(sub, color = Wd.Text3, fontSize = 12.tu, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// ---------------------------------------------------------------- 天気

private val COMPASS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

private fun windDir(deg: Int?): String = deg?.let { COMPASS[(((it % 360) + 360) % 360 / 22.5).roundToInt() % 16] }.orEmpty()

fun fmtTemp(v: Double?): String = v?.let { "${it.roundToInt()}°" } ?: "—"

private data class WxCell(val label: String, val value: String, val color: Color = Wd.Text)

private fun wxCell(key: String, w: WeatherState, windUnit: String): WxCell? {
    val c = w.current
    return when (key) {
        "apparent" -> WxCell("体感", fmtTemp(c.apparentTemperature))
        "pm25" -> WxCell(
            "PM2.5",
            c.pm25?.let { "%.1f μg/m³".format(it) } ?: "—",
            c.pm25?.let { if (it >= 70) Wd.Red else if (it >= 35) Wd.Amber else if (it >= 15) Wd.Text else Wd.Green } ?: Wd.Text,
        )
        "pop" -> WxCell("降水確率", c.precipitationProbability?.let { "$it%" } ?: "—")
        "rain" -> WxCell(
            "降水量",
            c.precipitation?.let { "%.1f mm".format(it) } ?: "—",
            if ((c.precipitation ?: 0.0) > 0) Wd.Text else Wd.Text3,
        )
        "humidity" -> WxCell("湿度", c.humidity?.let { "$it%" } ?: "—")
        "wind" -> WxCell("風", c.windSpeed?.let { "${it.roundToInt()} $windUnit ${windDir(c.windDirection)}".trim() } ?: "—")
        "uv" -> WxCell(
            "UV",
            c.uvIndex?.let { if (it < 1) "%.1f".format(it) else it.roundToInt().toString() } ?: "—",
            c.uvIndex?.let { if (it >= 8) Wd.Red else if (it >= 3) Wd.Amber else Wd.Green } ?: Wd.Text,
        )
        "aqi" -> c.aqi?.let {
            val (label, color) = when {
                it <= 20 -> "良い" to Wd.Green
                it <= 40 -> "やや良" to Wd.Green
                it <= 60 -> "普通" to Wd.Text
                it <= 80 -> "悪い" to Wd.Amber
                it <= 100 -> "かなり悪" to Wd.Red
                else -> "非常に悪" to Wd.Red
            }
            WxCell("AQI", "$it $label", color)
        } ?: WxCell("AQI", "—")
        "visibility" -> WxCell(
            "視界",
            c.visibilityMeters?.let { m -> val km = m / 1000; (if (km >= 10) km.roundToInt().toString() else "%.1f".format(km)) + " km" } ?: "—",
        )
        else -> null
    }
}

@Composable
fun WeatherCard(w: WeatherState?, display: DisplayConfig, units: UnitsConfig, now: Long, modifier: Modifier) {
    val note = w?.let { (it.placeName.orEmpty()) + " ・ " + relative(it.fetchedAt, now) }
    WdCard("天気", modifier, note = note) {
        if (w == null || !w.available) {
            EmptyText("天気を取得できていません。")
            return@WdCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(w.current.weatherCode, w.current.isDay, Modifier.size(vh(5.4f, 34.dp, 46.dp)))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    fmtTemp(w.current.temperature),
                    fontSize = vhText(4.2f, 24f, 36f),
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 1.em,
                    style = Tabular,
                )
                Text(weatherLabel(w.current.weatherCode), color = Wd.Text2, fontSize = 12.tu, modifier = Modifier.padding(top = 2.dp))
            }
        }
        val windUnit = if (units.wind == "ms") "m/s" else "km/h"
        val cells = display.weatherFields.mapNotNull { wxCell(it, w, windUnit) }
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            cells.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { cell ->
                        Row(Modifier.weight(1f)) {
                            Text(cell.label, color = Wd.Text3, fontSize = 11.5f.tu, maxLines = 1)
                            Spacer(Modifier.weight(1f))
                            Text(
                                cell.value,
                                color = cell.color,
                                fontSize = 11.5f.tu,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                style = Tabular,
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- LINE メモ

@Composable
fun MemoCard(memo: MemoState?, enabled: Boolean, now: Long, modifier: Modifier) {
    val items = memo?.items.orEmpty()
    WdCard(
        "LINE メモ",
        modifier,
        note = if (enabled) "(${items.size} 件)" else null,
        titleColor = Wd.Violet,
        borderColor = Wd.Violet.copy(alpha = 0.35f),
    ) {
        when {
            items.isNotEmpty() -> {
                val single = items.size == 1
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    items.forEachIndexed { i, item ->
                        if (i > 0) Hairline(Modifier.padding(vertical = 8.dp))
                        Text(
                            item.text,
                            fontSize = if (single) vhText(3.6f, 20f, 34f) else vhText(2.3f, 15f, 20f),
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = if (single) 1.35.em else 1.42.em,
                        )
                        Row(Modifier.padding(top = if (single) 8.dp else 4.dp)) {
                            Text(item.senderName.orEmpty(), color = Wd.Text3, fontSize = 12.tu, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(relative(item.receivedAt, now), color = Wd.Text3, fontSize = 12.tu)
                        }
                    }
                }
            }
            !enabled -> EmptyText("LINE 連携は未設定です。\n設定画面から中継先と端末トークンを登録してください。")
            memo?.lastError != null -> EmptyText("メモを取得できません: ${memo.lastError}", Wd.Red)
            else -> EmptyText("LINE でメッセージを送ると、ここに表示されます。\n「/clear」と送ると全部消えます。")
        }
    }
}

// ---------------------------------------------------------------- ニュース

@Composable
fun NewsCard(feed: FeedState?, enabled: Boolean, now: Long, modifier: Modifier) {
    val items = feed?.items.orEmpty()
    WdCard("ニュース", modifier, note = if (enabled && feed != null && feed.fetchedAt > 0) relative(feed.fetchedAt, now) else null) {
        when {
            !enabled -> EmptyText("フィードは未設定です。設定画面で RSS の URL を登録してください。")
            items.isEmpty() -> EmptyText(if (feed?.lastError != null) "取得できません" else "記事がありません")
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                items.forEachIndexed { i, item ->
                    if (i > 0) Hairline()
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text(item.title, fontSize = 13.5f.tu, lineHeight = 1.45.em, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!item.source.isNullOrEmpty()) {
                            Text(item.source, color = Wd.Text3, fontSize = 11.5f.tu, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 今日の単語

@Composable
fun WordCard(now: Long, modifier: Modifier) {
    val w = remember(now / 3_600_000L) { Words.forHour(now) }
    WdCard("今日の単語", modifier, note = "毎時更新", titleColor = Wd.Red, borderColor = Wd.Red.copy(alpha = 0.4f)) {
        Text(w.word, fontSize = vhText(3.4f, 20f, 30f), fontWeight = FontWeight.SemiBold, lineHeight = 1.1.em)
        Text(w.ipa, color = LocalAccent.current, fontSize = 14.5f.tu, modifier = Modifier.padding(top = 3.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                w.pos,
                fontSize = 11.tu,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Wd.Border).padding(horizontal = 5.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(w.meaning, fontSize = 13.tu, fontWeight = FontWeight.SemiBold, lineHeight = 1.5.em)
        }
    }
}

// ---------------------------------------------------------------- タイマー

@Composable
fun TimerCard(
    timer: DashboardViewModel.TimerState,
    now: Long,
    onPick: (Int, Int) -> Unit,
    onStart: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier,
) {
    val idle = timer.mode == DashboardViewModel.TimerMode.IDLE
    WdCard("タイマー", modifier, note = if (idle) "時 : 分" else null) {
        when (timer.mode) {
            DashboardViewModel.TimerMode.IDLE -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxWidth().height(ROW).clip(RoundedCornerShape(6.dp))
                            .background(LocalAccent.current.copy(alpha = 0.15f))
                            .border(1.dp, Wd.Border, RoundedCornerShape(6.dp)),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Drum(23, timer.hours, { onPick(it, timer.minutes) }, Modifier.weight(1f))
                        Text(":", color = Wd.Text3, fontSize = 18.tu)
                        Drum(59, timer.minutes, { onPick(timer.hours, it) }, Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.width(10.dp))
                PillButton("開始", Wd.Green, Color(0xFF04221A), onStart, Modifier.padding(vertical = 4.dp))
            }
            DashboardViewModel.TimerMode.RUNNING -> {
                val left = ((timer.endAt - now) / 1000.0).roundToInt().coerceAtLeast(0)
                val h = left / 3600
                val m = left % 3600 / 60
                val s = left % 60
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(
                        (if (h > 0) "$h:%02d".format(m) else "%02d".format(m)) + ":%02d".format(s),
                        color = if (left <= 10) Wd.Red else Wd.Text,
                        fontSize = vhText(5.4f, 28f, 44f),
                        fontWeight = FontWeight.SemiBold,
                        style = Tabular,
                    )
                    Spacer(Modifier.height(10.dp))
                    GhostButton("取消", onReset)
                }
            }
            DashboardViewModel.TimerMode.RINGING -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("時間です", color = Wd.Red, fontSize = vhText(3.6f, 20f, 28f), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                PillButton("止める", Wd.Red, Color(0xFF2A0606), onReset)
            }
        }
    }
}

private val ROW = 26.dp

/** 指で回して選ぶ数字のドラム。中央の行が選択。 */
@Composable
private fun Drum(max: Int, value: Int, onChange: (Int) -> Unit, modifier: Modifier) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = value)
    val fling = rememberSnapFlingBehavior(state, SnapPosition.Start)
    val selected by remember { derivedStateOf { centered(state) } }
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to centered(state) }.collect { (moving, v) ->
            if (!moving && v != current) change(v)
        }
    }
    LazyColumn(
        state = state,
        flingBehavior = fling,
        contentPadding = PaddingValues(vertical = ROW),
        modifier = modifier.height(ROW * 3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed((0..max).toList()) { i, n ->
            Box(Modifier.height(ROW).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "%02d".format(n),
                    color = if (i == selected) Wd.Text else Wd.Text3,
                    fontSize = 19.tu,
                    fontWeight = FontWeight.SemiBold,
                    style = Tabular,
                )
            }
        }
    }
}

private fun centered(state: LazyListState): Int {
    val info = state.layoutInfo
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }?.index
        ?: state.firstVisibleItemIndex
}

@Composable
fun PillButton(label: String, bg: Color, fg: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(bg)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 15.tu, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun GhostButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Wd.Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp),
    ) {
        Text(label, color = Wd.Text2, fontSize = 14.tu)
    }
}
