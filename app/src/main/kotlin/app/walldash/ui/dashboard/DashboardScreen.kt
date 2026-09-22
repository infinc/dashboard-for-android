package app.walldash.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.walldash.ui.common.WdIcons
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.tu
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.max

private enum class Slot(val span: Int) {
    CLOCK(8), WEATHER(8), DISASTER(8), MEMO(10), HOURLY(9), SPOTIFY(5),
    WIFI(6), STATS(10), NEWS(8), DAILY(12), TIMER(6), WORD(6),
}

private const val COLUMNS = 24
private const val COMPACT_WIDTH_DP = 840
private val GAP = 12.dp

@Composable
fun DashboardScreen(vm: DashboardViewModel, onOpenSettings: () -> Unit, onOpenBrowser: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val rssi by vm.rssiHistory.collectAsStateWithLifecycle()
    val cpu by vm.cpuHistory.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val kmoniBase by vm.kmoniBase.collectAsStateWithLifecycle()
    val kmoni by vm.kmoni.collectAsStateWithLifecycle()
    val album by vm.album.collectAsStateWithLifecycle()
    val timer by vm.timer.collectAsStateWithLifecycle()

    val d = config.display
    val s = state
    val shown = Slot.entries.filter {
        when (it) {
            Slot.CLOCK -> d.showClock
            Slot.WEATHER -> d.showWeather
            Slot.DISASTER -> d.showDisaster
            Slot.MEMO -> d.showMemo
            Slot.HOURLY -> d.showHourly
            Slot.SPOTIFY -> d.showSpotify
            Slot.WIFI -> d.showWifi
            Slot.STATS -> d.showDeviceStats
            Slot.NEWS -> d.showFeed
            Slot.DAILY -> d.showDaily
            Slot.TIMER -> d.showTimer
            Slot.WORD -> d.showWord
        }
    }

    @Composable
    fun Card(slot: Slot, modifier: Modifier) {
        when (slot) {
            Slot.CLOCK -> ClockCard(now, config.units, d, s?.weather, s?.deviceTimezone, modifier)
            Slot.WEATHER -> WeatherCard(s?.weather, d, config.units, now, modifier)
            Slot.DISASTER -> DisasterCard(s?.disaster, config.disaster.enabled, d, kmoniBase, kmoni, modifier)
            Slot.MEMO -> MemoCard(s?.memo, config.memo.enabled, now, modifier)
            Slot.HOURLY -> HourlyCard(s?.weather, d.hourlyMode, modifier)
            Slot.SPOTIFY -> SpotifyCard(
                s?.spotify, config.spotify.enabled, !config.spotify.refreshToken.isNullOrBlank(), d, album, now,
                vm::spotifyControl, modifier,
            )
            Slot.WIFI -> WifiCard(s?.wifi, s?.deviceStats, rssi, d.wifiShowGlobe, modifier)
            Slot.STATS -> StatsCard(s?.deviceStats, cpu, modifier)
            Slot.NEWS -> NewsCard(s?.feed, config.feed.enabled, now, modifier)
            Slot.DAILY -> DailyCard(s?.weather, modifier)
            Slot.TIMER -> TimerCard(timer, now, vm::pickTimer, vm::startTimer, vm::resetTimer, modifier)
            Slot.WORD -> WordCard(now, modifier)
        }
    }

    val shift = burnInShift(d.burnInShiftEnabled)
    // 縦向きと、横でも幅の狭い端末（スマホなど）は 2 列にして縦にスクロールさせる
    val compact = LocalConfiguration.current.let { it.screenHeightDp > it.screenWidthDp || it.screenWidthDp < COMPACT_WIDTH_DP }

    Box(Modifier.fillMaxSize().background(Wd.Bg).drawBehind { glow() }) {
        Column(Modifier.fillMaxSize().offset { shift }) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(GAP)) {
                val rows = pack(shown.map { it to if (compact) compactSpan(it) else it.span })
                val fit = (maxHeight - GAP * max(0, rows.size - 1)) / max(1, rows.size)
                val rowHeight = if (compact) maxOf(fit, 190.dp) else fit
                val body: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                        rows.forEach { row ->
                            Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                                row.forEach { (slot, span) -> Card(slot, Modifier.weight(span.toFloat()).fillMaxSize()) }
                            }
                        }
                    }
                }
                if (rowHeight > fit) Box(Modifier.verticalScroll(rememberScrollState())) { body() } else body()
            }
            Footer(now, s?.serverTime ?: 0L, refreshing, vm::refreshAll, onOpenSettings, onOpenBrowser)
        }

        AnimatedVisibility(
            visible = toast != null,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
            enter = slideInVertically { -it * 2 },
            exit = slideOutVertically { -it * 2 },
        ) {
            toast?.let { ToastBanner(it) }
        }

        if (d.showHamster) Hamster(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp))
    }
}

private fun compactSpan(slot: Slot) = if (slot == Slot.HOURLY) COLUMNS else COLUMNS / 2

/**
 * 行に詰め、行ごとの合計を 24 列ちょうどにする。
 * 非表示のカードが空けた列は、同じ行に残ったカードへ元の幅に比例して配る（端数は最大剰余法）。
 */
private fun <T> pack(items: List<Pair<T, Int>>): List<List<Pair<T, Int>>> {
    val rows = mutableListOf<MutableList<Pair<T, Int>>>()
    var used = 0
    items.forEach { item ->
        if (rows.isEmpty() || used + item.second > COLUMNS) {
            rows += mutableListOf<Pair<T, Int>>()
            used = 0
        }
        rows.last() += item
        used += item.second
    }
    return rows.map { row ->
        val total = row.sumOf { it.second }
        val extra = COLUMNS - total
        if (extra <= 0) return@map row
        val exact = row.map { extra.toDouble() * it.second / total }
        val spans = row.mapIndexed { i, it -> it.second + floor(exact[i]).toInt() }.toMutableList()
        val left = extra - exact.sumOf { floor(it).toInt() }
        exact.indices.sortedByDescending { exact[it] - floor(exact[it]) }.take(left).forEach { spans[it] += 1 }
        row.mapIndexed { i, it -> it.first to spans[i] }
    }
}

/** 5 分ごとに画面全体を ±2px 動かす（同じ画素を光らせ続けないため）。 */
@Composable
private fun burnInShift(enabled: Boolean): IntOffset {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(enabled) {
        while (enabled) {
            delay(300_000)
            step++
        }
    }
    val offsets = listOf(0 to 0, 2 to 1, 0 to 2, -2 to 1, -2 to -1, 0 to -2, 2 to -1)
    val (x, y) = if (enabled) offsets[step % offsets.size] else 0 to 0
    val shifted by animateIntOffsetAsState(IntOffset(x, y), tween(3000), label = "shift")
    return shifted
}

/** 画面上部のごく淡い光。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.glow() {
    val c = Offset(size.width / 2, -size.height * 0.25f)
    scale(1.3f * size.width / (0.8f * size.height), 1f, c) {
        drawCircle(
            Brush.radialGradient(
                0f to Color(0x174DD4FF),
                0.62f to Color.Transparent,
                center = c,
                radius = 0.8f * size.height,
            ),
            radius = 0.8f * size.height,
            center = c,
        )
    }
}

@Composable
private fun Footer(
    now: Long,
    updatedAt: Long,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onBrowse: () -> Unit,
) {
    val spin by rememberInfiniteTransition(label = "refresh").animateFloat(
        0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "spin",
    )
    Row(
        Modifier.fillMaxWidth().padding(start = GAP, end = GAP, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Weather data by Open-Meteo.com (CC BY 4.0) ・ 防災情報: 気象庁 ・ 強震モニタ: 防災科学技術研究所",
            color = Wd.Text3,
            fontSize = 11.tu,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            FooterButton(WdIcons.Refresh, "更新", onRefresh, Modifier.rotate(if (refreshing) spin else 0f))
            FooterButton(WdIcons.Gear, "設定", onSettings)
            FooterButton(WdIcons.Browse, "ブラウズ", onBrowse)
        }
        Text("更新 " + relative(updatedAt, now), color = Wd.Text3, fontSize = 11.tu)
    }
}

@Composable
private fun FooterButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = modifier.size(20.dp))
    }
}

@Composable
private fun ToastBanner(t: DashboardViewModel.Toast) {
    val width = LocalConfiguration.current.screenWidthDp.dp
    Column(
        Modifier.widthIn(max = width * 0.72f)
            .clip(RoundedCornerShape(14.dp))
            .background(Wd.Surface.copy(alpha = 0.97f))
            .border(1.dp, if (t.severe) Wd.Red.copy(alpha = 0.7f) else Wd.Amber.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(t.title, color = if (t.severe) Wd.Red else Wd.Amber, fontSize = 11.tu, letterSpacing = 0.12.em)
        Spacer(Modifier.height(3.dp))
        Text(t.body, fontSize = 15.tu, fontWeight = FontWeight.SemiBold, lineHeight = 1.45.em)
    }
}

