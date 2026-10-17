package app.dashboard.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dashboard.data.Health
import app.dashboard.data.HealthHour
import app.dashboard.data.HealthState
import app.dashboard.i18n.L
import app.dashboard.ui.common.EmptyText
import app.dashboard.ui.common.Tabular
import app.dashboard.ui.common.WdCard
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 段階の色（0 がいちばん穏やか）。 */
@Composable
private fun bandColor(level: Int): Color = when (level) {
    0 -> Wd.Green
    1 -> Wd.Cyan
    2 -> Wd.Amber
    3 -> Wd.Red
    else -> Wd.Violet
}

/**
 * 空気・体調。暑さ指数（推定）・紫外線・PM2.5 の今の値と段階、今日のいちばん高い値と時刻、下に 12 時間ぶんの暑さ指数の帯。
 */
@Composable
fun HealthCard(s: HealthState?, now: Long, modifier: Modifier) {
    val zone = s?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    val hourKey = remember(now / 600_000L, zone) {
        Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.HOURS).toString()
    }
    val today = hourKey.take(10)
    WdCard(L("空気・体調", "Air & health"), modifier, note = s?.lastError?.let { L("更新できません", "Can't update") }) {
        val hours = s?.hours.orEmpty()
        val nowIndex = hours.indexOfFirst { it.time >= hourKey }
        if (hours.isEmpty() || nowIndex < 0) {
            EmptyText(s?.lastError ?: L("取得中です…", "Fetching…"))
            return@WdCard
        }
        val current = hours[nowIndex]
        val todays = hours.filter { it.time.startsWith(today) }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric(
                L("暑さ指数", "Heat (WBGT)"), current.wbgt, "℃", 1, current.wbgt?.let(Health::wbgtBand),
                todays.peak { it.wbgt }, Modifier.weight(1f),
            )
            Metric(
                L("紫外線", "UV index"), current.uv, "", 1, current.uv?.let(Health::uvBand),
                todays.peak { it.uv }, Modifier.weight(1f),
            )
            Metric(
                "PM2.5", current.pm25, " µg/m³", 0, current.pm25?.let(Health::pm25Band),
                todays.peak { it.pm25 }, Modifier.weight(1f),
            )
        }
        HeatStrip(hours.drop(nowIndex).take(12), Modifier.fillMaxWidth().height(26.dp).padding(top = 6.dp))
        Text(
            L("暑さ指数は気温・湿度・日射・風からの推定（12 時間先まで）", "WBGT is estimated from temperature, humidity, sunlight and wind (next 12 hours)"),
            color = Wd.Text3, fontSize = 10.tu, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 今日のいちばん高い値と、その時刻（"14時"）。 */
private fun List<HealthHour>.peak(value: (HealthHour) -> Double?): Pair<Double, String>? =
    mapNotNull { h -> value(h)?.let { it to h.time } }.maxByOrNull { it.first }
        ?.let { (v, t) -> v to L("${t.substring(11, 13).toInt()}時", "${t.substring(11, 13).toInt()}:00") }

@Composable
private fun Metric(
    label: String,
    value: Double?,
    unit: String,
    decimals: Int,
    band: Health.Band?,
    peak: Pair<Double, String>?,
    modifier: Modifier,
) {
    fun fmt(v: Double) = if (decimals == 0) Math.round(v).toString() else "%.${decimals}f".format(v)
    Column(modifier.fillMaxHeight()) {
        Text(label, color = Wd.Text3, fontSize = 11.tu, maxLines = 1)
        Text(
            value?.let { fmt(it) + unit } ?: "—",
            fontSize = 26.tu, fontWeight = FontWeight.SemiBold, style = Tabular, maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (band != null) {
            val c = bandColor(band.level)
            Box(
                Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.16f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) { Text(band.label, color = c, fontSize = 11.tu, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
        peak?.let { (v, at) ->
            Text(
                L("今日の最高 ${fmt(v)}（$at）", "Today's max ${fmt(v)} ($at)"),
                color = Wd.Text3, fontSize = 10.tu, maxLines = 1, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 12 時間ぶんの暑さ指数を、段階の色の帯で並べる。 */
@Composable
private fun HeatStrip(hours: List<HealthHour>, modifier: Modifier) {
    val colors = (0..4).map { bandColor(it) }
    val empty = Wd.BorderSoft
    Canvas(modifier) {
        if (hours.isEmpty()) return@Canvas
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (hours.size - 1)) / hours.size
        hours.forEachIndexed { i, h ->
            val c = h.wbgt?.let { colors[Health.wbgtBand(it).level].copy(alpha = 0.75f) } ?: empty
            drawRoundRect(c, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(3.dp.toPx()))
        }
    }
}
