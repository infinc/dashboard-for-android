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
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.walldash.data.CardLayout
import app.walldash.ui.common.WdIcons
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.tu
import kotlinx.coroutines.delay
import kotlin.math.max

private typealias Slot = CardLayout.Card

private val GAP = CardLayout.GAP_DP.dp

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

    val wallpaper by vm.wallpaper.collectAsStateWithLifecycle()
    val radar by vm.radar.collectAsStateWithLifecycle()

    val d = config.display
    val s = state

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
            Slot.ANALOG_CLOCK -> AnalogClockCard(now, d.analogSweep, d.analogNumerals, modifier)
            Slot.CALENDAR -> CalendarCard(s?.calendar, config.calendar.enabled, calendarConfigured(config), now, modifier)
            Slot.TRAIN -> TrainCard(s?.train, config.train.enabled, !config.train.token.isNullOrBlank() || !config.train.challengeToken.isNullOrBlank(), now, modifier)
            Slot.RADAR -> RadarCard(radar, config.location.name, modifier)
            Slot.SUN_MOON -> SunMoonCard(s?.weather, now, modifier)
            Slot.COUNTDOWN -> CountdownCard(config.countdown, s?.holidays.orEmpty(), now, modifier)
            Slot.TODAY -> TodayCard(s?.today, now, d.todayShowEvent, modifier)
            Slot.STOCKS -> StocksCard(s?.stocks, config.stocks.range, now, modifier)
        }
    }

    val shift = burnInShift(d.burnInShiftEnabled)
    val screen = LocalConfiguration.current
    // 縦向きと、横でも幅の狭い端末（スマホなど）は 2 列にして縦にスクロールさせる
    val compact = CardLayout.isCompact(screen.screenWidthDp, screen.screenHeightDp)
    var overflow by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Wd.Bg).drawBehind { if (wallpaper == null) glow() }) {
        wallpaper?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Column(Modifier.fillMaxSize().offset { shift }) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(GAP)) {
                val area = CardLayout.Area(maxHeight.value.toInt(), screen.screenWidthDp, screen.screenHeightDp)
                // 設定画面が「カードを増やしても収まるか」を判定するときに、この実測の高さを使う
                SideEffect { CardLayout.measured = area }
                // 横向きで行が溢れるときは、週間予報を半分の幅まで縮めて空いた列に後ろのカードを並べる
                val rows = CardLayout.rows(d, area)
                val fit = (maxHeight - GAP * max(0, rows.size - 1)) / max(1, rows.size)
                // それでも収まらないとき（設定で止める前の古い設定や、画面の小さい端末への持ち込み）は、
                // 詰め込んで文字を重ねるより、1 行の高さを保って縦にスクロールさせる
                val fits = compact || rows.size <= CardLayout.maxRows(area)
                SideEffect { overflow = !fits }
                val rowHeight = when {
                    compact -> maxOf(fit, 190.dp)
                    !fits -> CardLayout.minRowDp(screen.screenHeightDp).dp
                    else -> fit
                }
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
            Footer(credits(d), now, s?.serverTime ?: 0L, refreshing, overflow, vm::refreshAll, onOpenSettings, onOpenBrowser)
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

private fun calendarConfigured(c: app.walldash.data.Config) = c.calendar.let {
    if (it.mode == "ics") !it.icsUrl.isNullOrBlank() else it.appleId.isNotBlank() && !it.password.isNullOrBlank()
}

/** フッターに出す出典。表示しているカードの分だけ並べる（全部並べると 1 行に収まらない）。 */
private fun credits(d: app.walldash.data.DisplayConfig): String = buildList {
    add("Weather data by Open-Meteo.com (CC BY 4.0)")
    if (d.showDisaster || d.showRadar) add("防災情報・雨雲: 気象庁")
    if (d.showDisaster && d.disasterShowKmoni) add("強震モニタ: 防災科学技術研究所")
    if (d.showRadar) add("地図: 地理院タイル")
    if (d.showTrain) add("運行情報: 公共交通オープンデータ協議会")
    if (d.showToday) add("今日は何の日: Wikipedia (CC BY-SA)")
    if (d.showStocks) add("株価: Yahoo Finance")
    if (d.showCountdown) add("祝日: 内閣府")
}.joinToString(" ・ ")

@Composable
private fun Footer(
    credits: String,
    now: Long,
    updatedAt: Long,
    refreshing: Boolean,
    overflow: Boolean,
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
            credits,
            color = Wd.Text3,
            fontSize = 11.tu,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            FooterButton(WdIcons.Refresh, "更新", onRefresh, Modifier.rotate(if (refreshing) spin else 0f))
            FooterButton(WdIcons.Gear, "設定", onSettings)
            FooterButton(WdIcons.Browse, "ブラウズ", onBrowse)
        }
        if (overflow) Text("カードが画面に収まりません（設定でカードを減らしてください）", color = Wd.Amber, fontSize = 11.tu, maxLines = 1)
        Text("更新 " + relative(updatedAt, now), color = Wd.Text3, fontSize = 11.tu)
    }
}

@Composable
private fun FooterButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Wd.Text, modifier = modifier.size(20.dp))
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

