package app.dashboard.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.key
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
import app.dashboard.data.CardLayout
import app.dashboard.data.toPublic
import app.dashboard.i18n.L
import app.dashboard.ui.common.WdIcons
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
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
    val photo by vm.photo.collectAsStateWithLifecycle()
    val flightFrame by vm.flightMap.frame.collectAsStateWithLifecycle()
    val flights by vm.flights.state.collectAsStateWithLifecycle()
    val shipFrame by vm.shipMap.frame.collectAsStateWithLifecycle()
    val ships by vm.ships.collectAsStateWithLifecycle()
    val shipStatus by vm.shipStatus.collectAsStateWithLifecycle()

    val d = config.display
    val s = state
    /** Spotify の再生中の曲を画面いっぱいに出しているか。 */
    var nowPlaying by remember { mutableStateOf(false) }
    /** 時刻を画面いっぱいに出しているか。 */
    var bigClock by remember { mutableStateOf(false) }
    /** 雨雲レーダー・暗号通貨を画面いっぱいに出しているか。 */
    var radarFull by remember { mutableStateOf(false) }
    var cryptoFull by remember { mutableStateOf(false) }
    var flightFull by remember { mutableStateOf(false) }
    var shipFull by remember { mutableStateOf(false) }
    /** 進路図を画面いっぱいに出している台風の識別子。 */
    var typhoonId by remember { mutableStateOf<String?>(null) }
    /** 最後に開いた台風（進路図を閉じるまで、台風が一覧から消えても同じものを出し続ける）。 */
    var typhoonShown by remember { mutableStateOf<app.dashboard.data.TyphoonInfo?>(null) }
    val keepAwake by vm.keepAwake.collectAsStateWithLifecycle()
    // 全画面の間だけ「画面を暗くしない」を効かせる（MainActivity が holdAwake を見る。閉じたら無操作の減光に戻る）
    val fullscreen = nowPlaying || bigClock || radarFull || cryptoFull || flightFull || shipFull
    LaunchedEffect(fullscreen) { vm.setFullscreenOpen(fullscreen) }

    // 地点の名前は設定の言語で（英語の名前を持たない古い設定では、気象庁の市町村の英語名）
    val weather = s?.weather?.let { w -> w.copy(placeName = config.location.displayName(s.disaster.areaNameEn)) }

    @Composable
    fun Card(slot: Slot, modifier: Modifier) {
        when (slot) {
            Slot.CLOCK -> ClockCard(now, config.units, d, weather, s?.deviceTimezone, { bigClock = true }, modifier)
            Slot.WEATHER -> WeatherCard(weather, d, config.units, now, modifier)
            Slot.DISASTER -> DisasterCard(s?.disaster, config.disaster.enabled, d, kmoniBase, kmoni, { typhoonId = it.id; typhoonShown = it }, modifier)
            Slot.MEMO -> MemoCard(s?.memo, config.memo.enabled, now, modifier)
            Slot.HOURLY -> HourlyCard(s?.weather, d.hourlyMode, modifier)
            Slot.SPOTIFY -> SpotifyCard(
                s?.spotify, config.spotify.enabled, !config.spotify.refreshToken.isNullOrBlank(), d, album, now,
                vm::spotifyControl, { nowPlaying = true }, modifier,
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
            Slot.RADAR -> RadarCard(radar, config.location.displayName(s?.disaster?.areaNameEn), vm::panRadar, vm::zoomRadar, vm::recenterRadar, { radarFull = true }, modifier)
            Slot.SUN_MOON -> SunMoonCard(s?.weather, now, modifier)
            Slot.COUNTDOWN -> CountdownCard(config.countdown, s?.holidays.orEmpty(), now, modifier)
            Slot.TODAY -> TodayCard(s?.today, now, d.todayShowEvent, modifier)
            Slot.STOCKS -> StocksCard(s?.stocks, config.stocks.range, now, modifier)
            Slot.CALCULATOR -> CalculatorCard(modifier)
            Slot.PHOTOS -> PhotoCard(photo, s?.photos, config.photos, vm::nextPhoto, modifier)
            Slot.CRYPTO -> CryptoCard(s?.crypto, config.crypto, now, vm::stepCryptoRange, { cryptoFull = true }, modifier)
            Slot.FLIGHTS -> FlightCard(
                flightFrame, flights, now, vm.flightMap::pan, vm.flightMap::zoom, vm.flightMap::recenter, { flightFull = true }, modifier,
            )
            Slot.SHIPS -> ShipCard(
                shipFrame, ships, shipStatus, now, vm.shipMap::pan, vm.shipMap::zoom, vm.shipMap::recenter, { shipFull = true }, modifier,
            )
            Slot.GITHUB -> GithubCard(s?.github, remember(config.github) { config.toPublic().github }, now, modifier)
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
                val grid = CardLayout.grid(d, area)
                val fit = (maxHeight - GAP * max(0, grid.rows - 1)) / max(1, grid.rows)
                // それでも収まらないとき（設定で止める前の古い設定や、画面の小さい端末への持ち込み）は、
                // 詰め込んで文字を重ねるより、1 行の高さを保って縦にスクロールさせる
                val fits = compact || grid.rows <= CardLayout.maxRows(area)
                SideEffect { overflow = !fits }
                val rowHeight = when {
                    compact -> maxOf(fit, 190.dp)
                    !fits -> CardLayout.minRowDp(screen.screenHeightDp).dp
                    else -> fit
                }
                // 24 列の格子に置く（利用者の配置で行の右端が余っているときや、縦に伸ばしたカードの下は、そのまま空く）
                val pitch = (maxWidth + GAP) / CardLayout.COLUMNS
                val body: @Composable () -> Unit = {
                    Box(Modifier.fillMaxWidth().height(rowHeight * grid.rows + GAP * max(0, grid.rows - 1))) {
                        grid.cards.forEach { p ->
                            key(p.card) {
                                Card(
                                    p.card,
                                    Modifier
                                        .offset(x = pitch * p.col, y = (rowHeight + GAP) * p.row)
                                        .size(pitch * p.span - GAP, rowHeight * p.height + GAP * (p.height - 1)),
                                )
                            }
                        }
                    }
                }
                if (rowHeight > fit) Box(Modifier.verticalScroll(rememberScrollState())) { body() } else body()
            }
            Footer(credits(d), now, s?.serverTime ?: 0L, refreshing, overflow, vm::refreshAll, onOpenSettings, onOpenBrowser)
        }

        if (d.showHamster) Hamster(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp))

        AnimatedVisibility(nowPlaying, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            NowPlayingScreen(s?.spotify, album, now, keepAwake, vm::setKeepAwake, vm::spotifyControl, vm::lyrics, onBack = { nowPlaying = false })
        }
        AnimatedVisibility(bigClock, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            BigClockScreen(now, config.units, d, keepAwake, vm::setKeepAwake, onBack = { bigClock = false })
        }
        AnimatedVisibility(radarFull, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            RadarScreen(
                radar, config.location.displayName(s?.disaster?.areaNameEn), keepAwake, vm::setKeepAwake,
                vm::panRadar, vm::zoomRadar, vm::recenterRadar, vm::setRadarFullscreen, onBack = { radarFull = false },
            )
        }
        AnimatedVisibility(cryptoFull, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            CryptoScreen(s?.crypto, config.crypto, now, keepAwake, vm::setKeepAwake, vm::cryptoDetail, onBack = { cryptoFull = false })
        }
        AnimatedVisibility(flightFull, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            var selected by remember { mutableStateOf<Any?>(null) }
            TrackerScreen(
                L("飛行機", "Flights") + if (flights.fetchedAt > 0) L(" ・ ${flights.aircraft.size} 機", " · ${flights.aircraft.size} aircraft") else "",
                flightFrame, keepAwake, vm::setKeepAwake, vm.flightMap::zoom, vm.flightMap::recenter, vm::setFlightFullscreen,
                onBack = { flightFull = false },
                map = { FlightMap(flightFrame, flights, now, selected, { selected = it }, vm.flightMap::pan, true, it) },
                footer = { AltitudeLegend(Modifier) },
                info = { m ->
                    flights.aircraft.firstOrNull { it.hex == selected }?.let { a ->
                        InfoBox(a.callsign ?: a.registration ?: a.hex.uppercase(), aircraftLines(a), true, m)
                    }
                },
            )
        }
        AnimatedVisibility(shipFull, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            var selected by remember { mutableStateOf<Any?>(null) }
            TrackerScreen(
                L("船舶", "Ships") + L(" ・ ${ships.size} 隻", " · ${ships.size} ships"),
                shipFrame, keepAwake, vm::setKeepAwake, vm.shipMap::zoom, vm.shipMap::recenter, vm::setShipFullscreen,
                onBack = { shipFull = false },
                map = { ShipMap(shipFrame, ships, selected, { selected = it }, vm.shipMap::pan, true, it) },
                footer = {
                    Column {
                        ShipLegend(Modifier)
                        ShipStatusText(shipStatus, ships.isEmpty(), true, Modifier.padding(top = 6.dp))
                    }
                },
                info = { m ->
                    ships.firstOrNull { it.mmsi == selected }?.let { sh -> InfoBox(sh.name ?: "MMSI ${sh.mmsi}", shipLines(sh, now), true, m) }
                },
            )
        }
        AnimatedVisibility(typhoonId != null, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            // 発表が更新されたら新しい方を渡す（進路図を取り直す）
            val info = s?.disaster?.typhoons?.firstOrNull { it.id == typhoonShown?.id } ?: typhoonShown
            info?.let {
                TyphoonScreen(
                    it,
                    app.dashboard.data.LatLon(config.location.latitude, config.location.longitude),
                    vm::typhoonTrack,
                    vm::darkMapTile,
                    onBack = { typhoonId = null },
                )
            }
        }
        // 通知のバナーは全画面の上にも出す
        AnimatedVisibility(
            visible = toast != null,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
            enter = slideInVertically { -it * 2 },
            exit = slideOutVertically { -it * 2 },
        ) {
            toast?.let { ToastBanner(it) }
        }
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

private fun calendarConfigured(c: app.dashboard.data.Config) = c.calendar.let {
    if (it.mode == "ics") !it.icsUrl.isNullOrBlank() else it.appleId.isNotBlank() && !it.password.isNullOrBlank()
}

/** フッターに出す出典。表示しているカードの分だけ並べる（全部並べると 1 行に収まらない）。 */
private fun credits(d: app.dashboard.data.DisplayConfig): String = buildList {
    add("Weather data by Open-Meteo.com (CC BY 4.0)")
    if (d.showDisaster || d.showRadar) add(L("防災情報・雨雲: 気象庁", "Alerts & rain: JMA"))
    if (d.showDisaster && d.disasterShowKmoni) add(L("強震モニタ: 防災科学技術研究所", "Seismic monitor: NIED"))
    if (d.showRadar) add(L("地図: Esri, HERE, Garmin, © OpenStreetMap", "Map: Esri, HERE, Garmin, © OpenStreetMap"))
    if (d.showTrain) add(L("運行情報: 公共交通オープンデータ協議会", "Trains: Association for Open Data of Public Transportation"))
    if (d.showToday) add(L("今日は何の日: Wikipedia (CC BY-SA)", "On this day: Wikipedia (CC BY-SA)"))
    if (d.showStocks) add(L("株価: Yahoo Finance", "Stocks: Yahoo Finance"))
    if (d.showCrypto) add(L("暗号通貨: CoinGecko", "Crypto: CoinGecko"))
    if (d.showCountdown) add(L("祝日: 内閣府", "Holidays: Cabinet Office, Japan"))
    if (d.showFlights) add("Flights: adsb.lol (ODbL)")
    if (d.showShips) add("AIS: aisstream.io")
    if (d.showFlights || d.showShips) if (!d.showRadar) add(L("地図: Esri, HERE, Garmin, © OpenStreetMap", "Map: Esri, HERE, Garmin, © OpenStreetMap"))
}.joinToString(L(" ・ ", " · "))

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
            FooterButton(WdIcons.Refresh, L("更新", "Refresh"), onRefresh, Modifier.rotate(if (refreshing) spin else 0f))
            FooterButton(WdIcons.Gear, L("設定", "Settings"), onSettings)
            FooterButton(WdIcons.Browse, L("ブラウズ", "Browse"), onBrowse)
        }
        if (overflow) Text(L("カードが画面に収まりません（設定でカードを減らしてください）", "Cards don't fit on the screen (hide some cards in Settings)"), color = Wd.Amber, fontSize = 11.tu, maxLines = 1)
        Text(L("更新 ", "Updated ") + relative(updatedAt, now), color = Wd.Text3, fontSize = 11.tu)
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

