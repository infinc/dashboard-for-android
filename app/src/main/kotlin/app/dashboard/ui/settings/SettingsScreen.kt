package app.dashboard.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dashboard.AppGraph
import app.dashboard.SettingsController
import app.dashboard.data.Accents
import app.dashboard.data.CardLayout
import app.dashboard.data.CalendarPatch
import app.dashboard.data.Config
import app.dashboard.data.Countdown
import app.dashboard.data.CountdownConfig
import app.dashboard.data.ConfigPatch
import app.dashboard.data.CRYPTO_COINS
import app.dashboard.data.CRYPTO_INTERVALS
import app.dashboard.data.CryptoConfig
import app.dashboard.data.DEFAULT_WEATHER_FIELDS
import app.dashboard.data.DisasterConfig
import app.dashboard.data.DisplayConfig
import app.dashboard.data.FeedConfig
import app.dashboard.data.GeocodeResult
import app.dashboard.data.LocationConfig
import app.dashboard.data.MemoPatch
import app.dashboard.data.NotificationConfig
import app.dashboard.data.PhotoPatch
import app.dashboard.data.SaveAllRequest
import app.dashboard.data.SpotifyPatch
import app.dashboard.data.ShipPatch
import app.dashboard.data.GithubPatch
import app.dashboard.data.GITHUB_DAYS
import app.dashboard.i18n.L
import app.dashboard.i18n.Lang
import app.dashboard.data.StockSymbol
import app.dashboard.data.StocksConfig
import app.dashboard.data.TrainPatch
import app.dashboard.data.Tones
import app.dashboard.data.UnitsConfig
import app.dashboard.server.DashboardServer
import app.dashboard.ui.dashboard.CRYPTO_CHARTS
import app.dashboard.ui.dashboard.CRYPTO_RANGE_LABELS
import app.dashboard.ui.theme.LocalAccent
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 「全て保存」でまとめて保存する値。トークンは書き込み専用なので、入力されたときだけ送る。 */
private data class Draft(
    val display: DisplayConfig,
    val units: UnitsConfig,
    val location: LocationConfig,
    val notifications: NotificationConfig,
    val disaster: DisasterConfig,
    val feedEnabled: Boolean,
    val feedUrls: String,
    val feedMax: String,
    val memoEnabled: Boolean,
    val memoEndpoint: String,
    val memoIntervalSec: String,
    val memoToken: String,
    val spotifyEnabled: Boolean,
    val spotifyClientId: String,
    val spotifySaveLyrics: Boolean,
    val trainEnabled: Boolean,
    val trainToken: String,
    val trainChallengeToken: String,
    val trainRailways: List<String>,
    val calendarEnabled: Boolean,
    val calendarMode: String,
    val calendarAppleId: String,
    val calendarPassword: String,
    val calendarIcsUrl: String,
    val calendarDays: String,
    val stocksText: String,
    val stocksRange: String,
    val countdownBuiltins: List<String>,
    val countdownText: String,
    val photosEnabled: Boolean,
    val photosUrl: String,
    val photosInterval: Int,
    val photosShuffle: Boolean,
    /** 一覧から選んだ通貨の ID。一覧に無い通貨を自分で入れるときは [CRYPTO_CUSTOM]。 */
    val cryptoCoin: String,
    val cryptoCustomId: String,
    val cryptoCurrency: String,
    val cryptoRange: String,
    val cryptoChart: String,
    val cryptoInterval: Int,
    val shipKey: String,
    val githubUser: String,
    val githubToken: String,
    val githubDays: Int,
    val githubShowGraph: Boolean,
    val githubShowCommits: Boolean,
    val githubShowPulls: Boolean,
    val githubShowIssues: Boolean,
    val githubShowRepos: Boolean,
    val githubShowProfile: Boolean,
) {
    fun toRequest() = SaveAllRequest(
        settings = ConfigPatch(
            location = location,
            units = units,
            display = display,
            disaster = disaster,
            feed = FeedConfig(feedEnabled, feedUrls.lines().map { it.trim() }.filter { it.isNotEmpty() }, feedMax.toIntOrNull() ?: 6),
            notifications = notifications,
            stocks = StocksConfig(parseStocks(stocksText), stocksRange),
            countdown = CountdownConfig(countdownBuiltins, Countdown.parseLines(countdownText)),
            crypto = CryptoConfig(if (cryptoCoin == CRYPTO_CUSTOM) cryptoCustomId.trim().lowercase() else cryptoCoin, cryptoCurrency, cryptoRange, cryptoChart, cryptoInterval),
        ),
        train = TrainPatch(
            enabled = trainEnabled,
            token = trainToken.takeIf { it.isNotEmpty() },
            challengeToken = trainChallengeToken.takeIf { it.isNotEmpty() },
            railways = trainRailways,
        ),
        calendar = CalendarPatch(
            enabled = calendarEnabled,
            mode = calendarMode,
            appleId = calendarAppleId.trim(),
            password = calendarPassword.takeIf { it.isNotEmpty() },
            icsUrl = calendarIcsUrl.takeIf { it.isNotEmpty() },
            daysAhead = calendarDays.toIntOrNull() ?: 7,
        ),
        memo = MemoPatch(
            enabled = memoEnabled,
            endpoint = memoEndpoint.trim(),
            token = memoToken.takeIf { it.isNotEmpty() },
            pollIntervalMs = (memoIntervalSec.toLongOrNull() ?: 30) * 1000,
        ),
        spotify = SpotifyPatch(enabled = spotifyEnabled, clientId = spotifyClientId.trim(), saveLyrics = spotifySaveLyrics),
        photos = PhotoPatch(
            enabled = photosEnabled,
            albumUrl = photosUrl.takeIf { it.isNotEmpty() },
            intervalSec = photosInterval,
            shuffle = photosShuffle,
        ),
        ships = ShipPatch(apiKey = shipKey.takeIf { it.isNotEmpty() }),
        github = GithubPatch(
            user = githubUser.trim(),
            token = githubToken.takeIf { it.isNotEmpty() },
            days = githubDays,
            showGraph = githubShowGraph,
            showCommits = githubShowCommits,
            showPulls = githubShowPulls,
            showIssues = githubShowIssues,
            showRepos = githubShowRepos,
            showProfile = githubShowProfile,
        ),
    )

    companion object {
        fun of(c: Config) = Draft(
            display = c.display,
            units = c.units,
            location = c.location,
            notifications = c.notifications,
            disaster = c.disaster,
            feedEnabled = c.feed.enabled,
            feedUrls = c.feed.urls.joinToString("\n"),
            feedMax = c.feed.maxItems.toString(),
            memoEnabled = c.memo.enabled,
            memoEndpoint = c.memo.endpoint,
            memoIntervalSec = (c.memo.pollIntervalMs / 1000).toString(),
            memoToken = "",
            spotifyEnabled = c.spotify.enabled,
            spotifyClientId = c.spotify.clientId,
            spotifySaveLyrics = c.spotify.saveLyrics,
            trainEnabled = c.train.enabled,
            trainToken = "",
            trainChallengeToken = "",
            trainRailways = c.train.railways,
            calendarEnabled = c.calendar.enabled,
            calendarMode = c.calendar.mode,
            calendarAppleId = c.calendar.appleId,
            calendarPassword = "",
            calendarIcsUrl = "",
            calendarDays = c.calendar.daysAhead.toString(),
            stocksText = c.stocks.symbols.joinToString("\n") { "${it.symbol} ${it.label}" },
            stocksRange = c.stocks.range,
            countdownBuiltins = c.countdown.builtins,
            countdownText = Countdown.toLines(c.countdown.custom),
            photosEnabled = c.photos.enabled,
            photosUrl = "",
            photosInterval = c.photos.intervalSec,
            photosShuffle = c.photos.shuffle,
            cryptoCoin = if (CRYPTO_COINS.any { it.first == c.crypto.coin }) c.crypto.coin else CRYPTO_CUSTOM,
            cryptoCustomId = if (CRYPTO_COINS.any { it.first == c.crypto.coin }) "" else c.crypto.coin,
            cryptoCurrency = c.crypto.currency,
            cryptoRange = c.crypto.range,
            cryptoChart = c.crypto.chart,
            cryptoInterval = c.crypto.intervalMin,
            shipKey = "",
            githubUser = c.github.user,
            githubToken = "",
            githubDays = c.github.days,
            githubShowGraph = c.github.showGraph,
            githubShowCommits = c.github.showCommits,
            githubShowPulls = c.github.showPulls,
            githubShowIssues = c.github.showIssues,
            githubShowRepos = c.github.showRepos,
            githubShowProfile = c.github.showProfile,
        )

        const val CRYPTO_CUSTOM = "custom"

        /** 「^N225 日経平均」の行を銘柄にする。名前を省いたら記号をそのまま名前にする。 */
        fun parseStocks(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val symbol = line.substringBefore(' ').substringBefore('\t')
            StockSymbol(symbol, line.removePrefix(symbol).trim().ifEmpty { symbol })
        }
    }
}

private enum class Pane(
    private val ja: String,
    private val en: String,
    private val groupJa: String,
    private val groupEn: String,
    val card: ((DisplayConfig) -> Boolean)? = null,
) {
    Presets("プリセット", "Presets", "全体", "General"),
    Language("言語", "Language", "全体", "General"),
    Palette("配色", "Colors", "全体", "General"),
    Layout("カードの配置", "Card layout", "全体", "General"),
    Theme("テーマ", "Theme", "全体", "General"),
    Screen("画面の明るさ", "Brightness", "全体", "General"),
    Place("場所", "Location", "全体", "General"),
    Notify("通知", "Notifications", "全体", "General"),
    Clock("時刻", "Clock", "カード", "Cards", { it.showClock }),
    Weather("天気", "Weather", "カード", "Cards", { it.showWeather }),
    Disaster("防災", "Alerts", "カード", "Cards", { it.showDisaster }),
    Memo("LINE メモ", "LINE memo", "カード", "Cards", { it.showMemo }),
    Hourly("時間別予報", "Hourly", "カード", "Cards", { it.showHourly }),
    Spotify("Spotify", "Spotify", "カード", "Cards", { it.showSpotify }),
    Wifi("Wi-Fi", "Wi-Fi", "カード", "Cards", { it.showWifi }),
    Stats("端末状態", "Device", "カード", "Cards", { it.showDeviceStats }),
    News("ニュース", "News", "カード", "Cards", { it.showFeed }),
    Daily("週間予報", "Weekly", "カード", "Cards", { it.showDaily }),
    Timer("タイマー", "Timer", "カード", "Cards", { it.showTimer }),
    Word("今日の単語", "Word of the hour", "カード", "Cards", { it.showWord }),
    AnalogClock("アナログ時計", "Analog clock", "カード", "Cards", { it.showAnalogClock }),
    Calendar("予定表", "Calendar", "カード", "Cards", { it.showCalendar }),
    Train("運行情報", "Trains", "カード", "Cards", { it.showTrain }),
    Radar("雨雲レーダー", "Rain radar", "カード", "Cards", { it.showRadar }),
    SunMoon("日の出・月", "Sun & Moon", "カード", "Cards", { it.showSunMoon }),
    Countdown("カウントダウン", "Countdown", "カード", "Cards", { it.showCountdown }),
    Today("今日は何の日", "On this day", "カード", "Cards", { it.showToday }),
    Stocks("株価", "Stocks", "カード", "Cards", { it.showStocks }),
    Calculator("計算機", "Calculator", "カード", "Cards", { it.showCalculator }),
    Photos("写真", "Photos", "カード", "Cards", { it.showPhotos }),
    Crypto("暗号通貨", "Crypto", "カード", "Cards", { it.showCrypto }),
    Flights("飛行機", "Flights", "カード", "Cards", { it.showFlights }),
    Ships("船舶", "Ships", "カード", "Cards", { it.showShips }),
    Github("GitHub", "GitHub", "カード", "Cards", { it.showGithub }),
    Todo("Todo", "To-do", "カード", "Cards", { it.showTodo }),
    Hamster("ハムスター", "Hamster", "カード", "Cards", { it.showHamster }),
    Device("ホームアプリ", "Home app", "端末", "Device"),
    Network("ネットワーク", "Network", "端末", "Device"),
    Lock("設定の PIN", "Settings PIN", "端末", "Device"),
    Info("情報", "About", "情報", "About"),
    ;

    val label: String get() = L(ja, en)
    val group: String get() = L(groupJa, groupEn)
}

/**
 * アプリ内の設定画面。ダッシュボードの上に重ねて出す。
 * 各項目の変更は下書きに溜め、左下の「全て保存」で 1 回にまとめて保存する。
 */
@Composable
fun SettingsPanel(graph: AppGraph, onClose: () -> Unit, onOpenBrowser: (String) -> Unit) {
    val config by graph.config.flow.collectAsStateWithLifecycle()
    var base by remember { mutableStateOf(Draft.of(config)) }
    var draft by remember { mutableStateOf(base) }
    var pane by remember { mutableStateOf(Pane.Language) }
    var saveStatus by remember { mutableStateOf("") }
    var confirmClose by remember { mutableStateOf(false) }
    var versionArt by remember { mutableStateOf(false) }
    /** カードを増やせなかった理由。null でなければダイアログで出す。 */
    var blocked by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dirty = draft != base

    // 他の端末（Web の設定画面）から保存されたときは、手元で編集していなければ追従する
    LaunchedEffect(config.configVersion) {
        val latest = Draft.of(config)
        if (draft == base) draft = latest
        base = latest
    }

    /**
     * 下書きを書き換える。カードの表示を切り替えたときは配置を合わせ直し（空きが足りなければほかのカードを最小の幅まで縮める）、
     * それでも画面に収まらなくなる変更は、理由を出して取りやめる。
     */
    fun update(next: Draft) {
        val adjusted = CardLayout.adjust(draft.display, next.display, CardLayout.area(context))
        if (adjusted.message != null) {
            blocked = adjusted.message
            return
        }
        draft = next.copy(display = adjusted.display)
        saveStatus = ""
    }

    fun save(then: () -> Unit = {}) {
        saveStatus = L("保存中…", "Saving…")
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { graph.settings.saveAll(draft.toRequest()) } }
                .onSuccess { saved ->
                    base = Draft.of(saved)
                    draft = base
                    saveStatus = L("保存しました", "Saved")
                    then()
                }
                .onFailure {
                    val message = (it as? SettingsController.SettingsException)?.message ?: L("エラー: ${it.message}", "Error: ${it.message}")
                    saveStatus = message
                    blocked = message
                }
        }
    }

    fun requestClose() {
        if (dirty) confirmClose = true else onClose()
    }

    BackHandler(enabled = !versionArt) { requestClose() }

    Box(
        Modifier.fillMaxSize().background(Color(0xB8040609))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { requestClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(18.dp)).background(Wd.Surface)
                .border(1.dp, Wd.Border, RoundedCornerShape(18.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .imePadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L("設定", "Settings"), fontSize = 16.tu, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                ActionButton(L("閉じる", "Close"), ::requestClose)
            }
            Row(Modifier.fillMaxSize()) {
                Nav(pane, draft.display, dirty, saveStatus, onSelect = { pane = it }, onSave = { save() }, onRefresh = {
                    scope.launch(Dispatchers.IO) { graph.settings.refreshAll() }
                })
                Column(
                    Modifier.weight(1f).fillMaxHeight().background(Wd.Bg)
                        .verticalScroll(rememberScrollState(), enabled = true)
                        .padding(horizontal = 28.dp, vertical = 22.dp),
                ) {
                    PaneContent(pane, graph, config, draft, ::update, onOpenBrowser, dirty) { versionArt = true }
                }
            }
        }
    }

    if (versionArt) VersionArt(onBack = { versionArt = false })

    blocked?.let { message ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            title = { Text(L("カードを増やせません", "Can't add the card")) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { blocked = null }) { Text("OK") } },
        )
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(L("未保存の変更があります。", "You have unsaved changes.")) },
            text = { Text(L("保存せずに閉じると、変更した内容は失われます。", "If you close without saving, your changes will be lost.")) },
            confirmButton = { TextButton(onClick = { confirmClose = false; save(onClose) }) { Text(L("保存して閉じる", "Save and close")) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmClose = false }) { Text(L("キャンセル", "Cancel")) }
                    TextButton(onClick = { confirmClose = false; onClose() }) { Text(L("保存せずに閉じる", "Close without saving"), color = Wd.Red) }
                }
            },
        )
    }
}

@Composable
private fun Nav(
    selected: Pane,
    display: DisplayConfig,
    dirty: Boolean,
    status: String,
    onSelect: (Pane) -> Unit,
    onSave: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.width(230.dp).fillMaxHeight().background(Wd.Surface).padding(horizontal = 10.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            var group = ""
            Pane.entries.forEach { p ->
                if (p.group != group) {
                    group = p.group
                    Text(group, color = Wd.Text3, fontSize = 11.tu, modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp))
                }
                val on = p.card?.invoke(display)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(if (p == selected) Wd.Surface2 else Color.Transparent)
                        .clickable { onSelect(p) }.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(
                            when (on) {
                                null -> Color.Transparent
                                true -> LocalAccent.current
                                false -> Wd.Border
                            },
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(p.label, fontSize = 14.tu, color = if (p == selected) Wd.Text else Wd.Text2)
                }
            }
        }
        Column(Modifier.padding(vertical = 12.dp)) {
            ActionButton(if (dirty) L("全て保存", "Save all") else L("変更はありません", "No changes"), onSave, Modifier.fillMaxWidth(), primary = true, enabled = dirty)
            StatusText(if (dirty) L("未保存の変更があります", "You have unsaved changes") else status, if (dirty) Wd.Amber else Wd.Green)
            Spacer(Modifier.height(8.dp))
            ActionButton(L("今すぐ全データを取得し直す", "Refresh all data now"), onRefresh, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PaneContent(
    pane: Pane, graph: AppGraph, config: Config, d: Draft, set: (Draft) -> Unit, onOpenBrowser: (String) -> Unit,
    dirty: Boolean, onVersionArt: () -> Unit,
) {
    val disp = d.display
    fun display(block: DisplayConfig.() -> DisplayConfig) = set(d.copy(display = disp.block()))

    @Composable
    fun CardSwitch(on: Boolean, update: DisplayConfig.(Boolean) -> DisplayConfig) {
        Field { SwitchRow(L("このカードをダッシュボードに表示する", "Show this card on the dashboard"), on, { display { update(it) } }) }
    }

    when (pane) {
        Pane.Language -> {
            PaneTitle(L("言語", "Language"), L("ダッシュボード・設定画面（アプリと Web）・通知の言葉を選びます。", "Choose the language of the dashboard, the settings (app and web) and notifications."))
            Field(L("表示の言語", "Display language"), L("「全て保存」を押すと切り替わります。", "Takes effect when you press Save all.")) {
                Select(listOf(Lang.JA to "日本語", Lang.EN to "English"), disp.language, { display { copy(language = it) } })
            }
            Notice(
                L(
                    "英語にすると、取得先が英語の名前を出しているもの（地震の震源・路線名・天気の地点の検索結果など）は英語に、Wikipedia の「今日は何の日」は英語版の記事になります。" +
                        "ニュースの見出し・LINE メモ・予定・曲名など、利用者や配信元が書いた文は元の言葉のままです。",
                    "In English, data that the sources also publish in English (earthquake epicenters, railway names, place search results…) is shown in English, " +
                        "and \"On this day\" uses the English Wikipedia. Text written by you or by publishers (news headlines, LINE memos, events, song titles) stays as it is.",
                ),
            )
        }

        Pane.Flights -> {
            PaneTitle(
                L("飛行機", "Flights"),
                L(
                    "世界の飛行機の位置を地図に出すカードです（Flightradar24 のようなもの）。色は高度で、機体を押すと便名・機種・高度・速さを出します。" +
                        "ドラッグで地図を動かし、右上の「＋」「−」で拡大・縮小、その下のボタンで「場所」の地点へ戻ります。",
                    "Shows aircraft around the world on a map (like Flightradar24). Colors show altitude; tap a plane for its flight, type, altitude and speed. " +
                        "Drag to move the map, use + / − at the top right to zoom, and the button below to return to your location.",
                ),
            )
            CardSwitch(disp.showFlights) { copy(showFlights = it) }
            Notice(
                L(
                    "位置は adsb.lol の公開 API（登録不要、ODbL）から、カードを表示している間だけ 15 秒ごとに地図のまわり（最大 250 海里）を取ります。" +
                        "受信は世界中のボランティアの受信機によるもので、海の上や受信機の少ない地域では出ない機体があります。",
                    "Positions come from the public adsb.lol API (no sign-up, ODbL), fetched every 15 seconds around the map (up to 250 nm) only while the card is shown. " +
                        "Coverage relies on volunteer receivers, so aircraft over the ocean or in sparse areas may be missing.",
                ),
            )
        }

        Pane.Ships -> {
            PaneTitle(
                L("船舶", "Ships"),
                L(
                    "世界の船の位置（AIS）を地図に出すカードです（MarineTraffic のようなもの）。色は船種で、船を押すと名前・船種・速さ・行き先を出します。地図の動かし方は飛行機と同じです。",
                    "Shows ship positions (AIS) on a map (like MarineTraffic). Colors show the ship type; tap a ship for its name, type, speed and destination. The map works like the Flights card.",
                ),
            )
            CardSwitch(disp.showShips) { copy(showShips = it) }
            Field(
                L("aisstream.io の API キー", "aisstream.io API key"),
                L(
                    "aisstream.io（無料）に GitHub のアカウントでサインインし、「API Keys」で作ったキーを入れてください。保存済みの値は表示しません。",
                    "Sign in to aisstream.io (free) with a GitHub account and create a key under \"API Keys\". The saved value is never shown.",
                ),
            ) {
                Input(
                    d.shipKey, { set(d.copy(shipKey = it)) },
                    placeholder = if (config.ships.apiKey.isNullOrBlank()) L("未設定", "Not set") else L("設定済み（変更する場合のみ入力）", "Set (enter only to change)"),
                    password = true,
                )
            }
            Notice(
                L(
                    "カードを表示している間だけ aisstream.io につなぎ、地図に見えている範囲の電文を受け取ります。船は数秒〜数分ごとに位置を送るので、つないでから少しずつ船が増えます（20 分届かない船は消します）。" +
                        "受信は世界中のボランティアの受信局によるもので、地域によっては船が少なく出ます。MarineTraffic の API は有料のため使っていません。",
                    "The card connects to aisstream.io only while shown and receives messages for the visible area. Ships report every few seconds to minutes, so they appear gradually " +
                        "(ships silent for 20 minutes are removed). Coverage depends on volunteer stations. MarineTraffic's API is paid, so it isn't used.",
                ),
            )
        }

        Pane.Github -> {
            PaneTitle(
                "GitHub",
                L(
                    "GitHub の直近のコミット・プルリクエスト・Issue の数、コントリビューションの絵、スターの多いリポジトリを出すカードです。30 分ごとに取得します。",
                    "Shows your recent commit, pull request and issue counts, the contribution graph and your most-starred repositories. Updated every 30 minutes.",
                ),
            )
            CardSwitch(disp.showGithub) { copy(showGithub = it) }
            Field(L("ユーザー名", "User name"), L("github.com/ の後ろの名前です（URL を貼っても構いません）。", "The name after github.com/ (pasting the URL is fine).")) {
                Input(d.githubUser, { set(d.copy(githubUser = it)) }, placeholder = L("例: octocat", "e.g. octocat"))
            }
            Field(
                L("アクセストークン（任意）", "Access token (optional)"),
                L(
                    "無くても公開の情報は読めます。あると API の回数の上限が増え、プロフィールと同じ数え方（非公開のリポジトリを含む）の正確な数になります。" +
                        "github.com → Settings → Developer settings → Personal access tokens で、権限なし（読み取り）のトークンを作ってください。保存済みの値は表示しません。",
                    "Public data works without one. With a token the rate limit is higher and the counts match your profile (including private repositories). " +
                        "Create a token with no scopes under github.com → Settings → Developer settings → Personal access tokens. The saved value is never shown.",
                ),
            ) {
                Input(
                    d.githubToken, { set(d.copy(githubToken = it)) },
                    placeholder = if (config.github.token.isNullOrBlank()) L("未設定", "Not set") else L("設定済み（変更する場合のみ入力）", "Set (enter only to change)"),
                    password = true,
                )
            }
            Field(L("数える期間", "Period for counts")) {
                Select(GITHUB_DAYS.map { it to if (it == 365) L("1 年", "1 year") else L("$it 日", "$it days") }, d.githubDays, { set(d.copy(githubDays = it)) })
            }
            Field(L("カードに出すもの", "What to show")) {
                SwitchRow(L("コミット数", "Commits"), d.githubShowCommits, { set(d.copy(githubShowCommits = it)) })
                SwitchRow(L("プルリクエスト数", "Pull requests"), d.githubShowPulls, { set(d.copy(githubShowPulls = it)) })
                SwitchRow(L("Issue 数", "Issues"), d.githubShowIssues, { set(d.copy(githubShowIssues = it)) })
                SwitchRow(L("スターの合計とフォロワー", "Total stars and followers"), d.githubShowProfile, { set(d.copy(githubShowProfile = it)) })
                SwitchRow(L("コントリビューションの絵", "Contribution graph"), d.githubShowGraph, { set(d.copy(githubShowGraph = it)) })
                SwitchRow(L("人気のリポジトリ（スターの多い順）", "Popular repositories (most stars)"), d.githubShowRepos, { set(d.copy(githubShowRepos = it)) })
            }
            val s = graph.github.state
            StatusText(
                when {
                    config.github.user.isBlank() -> L("ユーザー名を入れて「全て保存」してください", "Enter a user name and press Save all")
                    !config.display.showGithub -> L("カードを表示しているときだけ取得します", "Fetched only while the card is shown")
                    s.lastError != null && s.fetchedAt == 0L -> L("エラー: ", "Error: ") + s.lastError
                    s.fetchedAt > 0 -> L("取得できています", "Fetching OK") + (s.lastError?.let { L("（$it）", " ($it)") } ?: "")
                    else -> L("まだ取得していません —「全て保存」のあと少し待ってください", "Not fetched yet — wait a moment after Save all")
                },
                if (s.lastError != null) Wd.Amber else Wd.Text2,
            )
        }

        Pane.Palette -> {
            PaneTitle("配色", L("ダッシュボード全体の色を決めます。グラフの線や強調の色がこの色になります。", "Sets the color of the whole dashboard. Graph lines and highlights use this color."))
            Field(L("アクセント色", "Accent color")) {
                ColorSwatches(Accents.ALL.map { it.hex to it.label }, disp.accent) { display { copy(accent = it) } }
            }
            Notice(L("カードの幅と並びは「カードの配置」で変えられます。自動で並べているあいだは、カードを非表示にして空いた列が同じ行に残ったカードへ配分され、1 行が常に画面幅いっぱいになります。", "Card widths and order can be changed in \"Card layout\". While cards are arranged automatically, columns freed by hidden cards go to the remaining cards in the same row, so each row always fills the screen width."))
            Notice(L("カードを表示しすぎて、ほかのカードを最小の幅まで縮めても画面に収まらないときは、そのカードは表示できません（理由をお知らせします）。先にほかのカードを非表示にしてください。縦向きの画面は縦にスクロールするので、この制限はありません。", "If a card won't fit on the screen even after shrinking the others to their minimum width, it can't be shown (you'll be told why). Hide another card first. Portrait screens scroll vertically, so this limit doesn't apply there."))
        }

        Pane.Layout -> LayoutPane(disp) { display { it } }

        Pane.Theme -> ThemePane(graph, config, d, set)

        Pane.Screen -> {
            PaneTitle("画面の明るさ", L("壁掛けでは端末の自動輝度が明滅するため、明るさはアプリ側で固定します。", "Wall-mounted tablets flicker with auto-brightness, so the app fixes the brightness itself."))
            PercentSlider(L("通常時の明るさ", "Normal brightness"), (disp.normalBrightness * 100).roundToInt(), 10, 100, 5, { display { copy(normalBrightness = it / 100.0) } }, L("操作があるときの画面の明るさです。", "Screen brightness while in use."))
            Field(hint = L("画面に触れると即座に元の明るさへ戻ります。バックライト自体を下げるので、消費電力と焼き付きの両方に効きます。", "Touching the screen restores the brightness immediately. It lowers the backlight itself, which saves power and reduces burn-in.")) {
                SwitchRow(L("無操作が続いたら画面を暗くする", "Dim the screen when idle"), disp.idleDimEnabled, { display { copy(idleDimEnabled = it) } })
            }
            Field(L("暗くするまでの時間（秒）", "Time before dimming (seconds)"), L("30〜3600 秒", "30–3600 seconds")) {
                Input(disp.idleDimAfterSeconds.toString(), { v -> v.filter(Char::isDigit).toIntOrNull()?.let { display { copy(idleDimAfterSeconds = it) } } }, number = true)
            }
            PercentSlider(L("暗くしたときの明るさ", "Dimmed brightness"), (disp.idleDimBrightness * 100).roundToInt(), 5, 100, 5, { display { copy(idleDimBrightness = it / 100.0) } })
            Field(hint = L("同じ位置に同じ絵を長時間映し続けると形が薄く残ることがあるため、5 分ごとに画面全体を最大 2 ピクセルだけ動かします。", "Showing the same image in the same place for a long time can leave a faint ghost, so the whole screen shifts by up to 2 pixels every 5 minutes.")) {
                SwitchRow(L("焼き付き防止のシフト", "Burn-in protection shift"), disp.burnInShiftEnabled, { display { copy(burnInShiftEnabled = it) } })
            }
        }

        Pane.Place -> PlacePane(graph, config, d, set)

        Pane.Notify -> {
            PaneTitle("通知", L("ダッシュボードが鳴らす音の設定です。音を切っても、画面のバナー表示は出ます。", "Sounds played by the dashboard. Banners still appear even when sounds are off."))
            val n = d.notifications
            fun notify(block: NotificationConfig.() -> NotificationConfig) = set(d.copy(notifications = n.block()))
            val tones = Tones.ALL.map { it.id to it.label }
            ToneField(L("防災情報", "Disaster alerts"), L("警報・注意報・地震・津波・台風・噴火のいずれかが切り替わったときに 1 回だけ鳴ります。", "Plays once when any warning, advisory, earthquake, tsunami, typhoon or eruption information changes."), n.disasterSound, { notify { copy(disasterSound = it) } }, tones, n.disasterTone, { notify { copy(disasterTone = it) } }) {
                graph.notices.play(n.disasterTone, Tones.DEFAULT_DISASTER, volume = n.volume)
            }
            ToneField(L("充電ケーブルの抜き差し", "Charging cable plugged / unplugged"), L("挿したときはそのまま、抜いたときは音の高さを逆にして鳴らします。", "Plays as is when plugged in, and with the pitch reversed when unplugged."), n.chargingSound, { notify { copy(chargingSound = it) } }, tones, n.chargingTone, { notify { copy(chargingTone = it) } }) {
                graph.notices.play(n.chargingTone, Tones.DEFAULT_CHARGING, volume = n.volume)
            }
            ToneField("タイマー", L("タイマーの鳴動は切れません（自分で時間を決めて鳴らすもののため）。", "The timer alarm can't be turned off (you set it yourself)."), null, {}, tones, n.timerTone, { notify { copy(timerTone = it) } }) {
                graph.notices.play(n.timerTone, Tones.DEFAULT_TIMER, volume = n.volume)
            }
            Text(L("ほかの通知（切ると音もバナーも出ません）", "Other notifications (turning one off hides both sound and banner)"), color = Wd.Text, fontSize = 15.tu, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            ToneField(
                L("電池の残量が少ない", "Low battery"), L("充電していないときに、残量が決めた値を切ったら 1 回知らせます（2% 戻るか充電すると、次も知らせます）。", "Notifies once when the battery drops below the chosen level while not charging (notifies again after it recovers by 2% or is charged)."),
                n.batteryLowEnabled, { notify { copy(batteryLowEnabled = it) } }, tones, n.batteryLowTone, { notify { copy(batteryLowTone = it) } },
                switchLabel = L("知らせる", "Notify"),
                threshold = { Select(app.dashboard.data.BATTERY_LOW_CHOICES.map { it to L("$it% を切ったら", "Below $it%") }, n.batteryLowPercent, { notify { copy(batteryLowPercent = it) } }) },
            ) { graph.notices.play(n.batteryLowTone, Tones.DEFAULT_BATTERY_LOW, volume = n.volume) }
            ToneField(
                L("電池の温度が高い", "Battery too hot"), L("電池の温度が決めた値を超えたら 1 回知らせます（1 ℃ 下がると、次も知らせます）。", "Notifies once when the battery temperature exceeds the chosen value (notifies again after it drops by 1 °C)."),
                n.batteryHotEnabled, { notify { copy(batteryHotEnabled = it) } }, tones, n.batteryHotTone, { notify { copy(batteryHotTone = it) } },
                switchLabel = L("知らせる", "Notify"),
                threshold = { Select(app.dashboard.data.BATTERY_HOT_CHOICES.map { it to L("$it ℃ を超えたら", "Above $it °C") }, n.batteryHotC, { notify { copy(batteryHotC = it) } }) },
            ) { graph.notices.play(n.batteryHotTone, Tones.DEFAULT_BATTERY_HOT, volume = n.volume) }
            ToneField(
                L("LINE メモが届いた", "New LINE memo"), L("新しいメモが届いたら、メモの中身をバナーに出します。", "Shows the memo text in a banner when a new memo arrives."),
                n.memoEnabled, { notify { copy(memoEnabled = it) } }, tones, n.memoTone, { notify { copy(memoTone = it) } }, switchLabel = L("知らせる", "Notify"),
            ) { graph.notices.play(n.memoTone, Tones.DEFAULT_MEMO, volume = n.volume) }
            ToneField(
                L("Wi-Fi が切れた", "Wi-Fi disconnected"), L("Wi-Fi の接続が 4 秒ほど続けて切れていたら知らせます。", "Notifies when Wi-Fi stays disconnected for about 4 seconds."),
                n.wifiLostEnabled, { notify { copy(wifiLostEnabled = it) } }, tones, n.wifiLostTone, { notify { copy(wifiLostTone = it) } }, switchLabel = L("知らせる", "Notify"),
            ) { graph.notices.play(n.wifiLostTone, Tones.DEFAULT_WIFI_LOST, volume = n.volume) }
            ToneField(
                L("まもなく雨が降る", "Rain coming soon"), L("「場所」の地点で、決めた時間のうちに雨が降り始める予報が出たら知らせます。気象庁の降水ナウキャスト（雨雲レーダーの予報、5 分ごと）で調べ、国外の地点では時間別予報を使います。", "Notifies when rain is forecast to start at your location within the chosen time. Uses JMA's precipitation nowcast (radar forecast, every 5 minutes); outside Japan the hourly forecast is used."),
                n.rainEnabled, { notify { copy(rainEnabled = it) } }, tones, n.rainTone, { notify { copy(rainTone = it) } },
                switchLabel = L("知らせる", "Notify"),
                threshold = { Select(app.dashboard.data.RAIN_MINUTE_CHOICES.map { it to L("$it 分以内に降り始めるとき", "When rain starts within $it min") }, n.rainMinutes, { notify { copy(rainMinutes = it) } }) },
            ) { graph.notices.play(n.rainTone, Tones.DEFAULT_RAIN, volume = n.volume) }
            PercentSlider(
                L("通知音の音量", "Notification volume"), (n.volume * 100).roundToInt(), 0, 100, 5, { notify { copy(volume = it / 100.0) } },
                L("鳴らす直前に端末のメディア音量をこの大きさまで動かし、鳴り終わったら元に戻します。0% にすると鳴りません。", "Sets the device's media volume to this level just before playing and restores it afterwards. 0% means silent."),
            )
            Notice(L("ブラウズで動画を見ている最中に通知が鳴ると、その 1〜2 秒だけ動画の音量も一緒に変わります（端末の音量そのものを動かしているため）。", "If a notification plays while you're watching a video in Browse, the video volume also changes for those 1–2 seconds (because the device volume itself is changed)."))
        }

        Pane.Clock -> {
            PaneTitle("時刻", L("大きな時計と日付を出すカードです。", "A card with a large clock and the date."))
            CardSwitch(disp.showClock) { copy(showClock = it) }
            Field(L("時刻表示", "Time format")) {
                Select(listOf(true to L("24 時間", "24-hour"), false to L("12 時間（AM/PM）", "12-hour (AM/PM)")), d.units.clock24h, { set(d.copy(units = d.units.copy(clock24h = it))) })
            }
            Field(L("揃え", "Alignment")) {
                Select(listOf("left" to L("左", "Left"), "center" to L("中央", "Center"), "right" to L("右", "Right")), disp.clockAlign, { display { copy(clockAlign = it) } })
            }
            Field(L("日付の書き方", "Date format")) {
                val today = remember { java.time.ZonedDateTime.now() }
                Select(app.dashboard.data.DATE_FORMATS.map { it to app.dashboard.ui.dashboard.formatDate(today, it) }, disp.clockDateFormat, { display { copy(clockDateFormat = it) } })
            }
            Field { SwitchRow(L("秒を表示する", "Show seconds"), d.units.showSeconds, { set(d.copy(units = d.units.copy(showSeconds = it))) }) }
        }

        Pane.Weather -> {
            PaneTitle("天気", L("いまの天気と、体感・降水・風などの値を出すカードです。地点は「場所」で決めます。", "Current weather with feels-like, precipitation, wind and more. The location is set in \"Location\"."))
            CardSwitch(disp.showWeather) { copy(showWeather = it) }
            Field(L("気温の単位", "Temperature unit")) {
                Select(listOf("c" to L("摂氏（℃）", "Celsius (°C)"), "f" to L("華氏（℉）", "Fahrenheit (°F)")), d.units.temperature, { set(d.copy(units = d.units.copy(temperature = it))) })
            }
            Field(L("風速の単位", "Wind speed unit")) {
                Select(listOf("kmh" to "km/h", "ms" to "m/s"), d.units.wind, { set(d.copy(units = d.units.copy(wind = it))) })
            }
            Field(L("出す項目", "Items"), L("3 列で左上から詰めて並べます（9 項目で 3 行）。", "Filled from the top left in 3 columns (9 items make 3 rows).")) {
                val labels = mapOf(
                    "apparent" to L("体感温度", "Feels like"), "pm25" to "PM2.5", "pop" to L("降水確率", "Chance of rain"), "rain" to L("降水量", "Precipitation"), "humidity" to L("湿度", "Humidity"),
                    "wind" to L("風", "Wind"), "uv" to L("UV 指数", "UV index"), "aqi" to L("大気質（AQI）", "Air quality (AQI)"), "visibility" to L("視界", "Visibility"),
                )
                DEFAULT_WEATHER_FIELDS.chunked(3).forEach { row ->
                    Row {
                        row.forEach { key ->
                            CheckRow(labels.getValue(key), key in disp.weatherFields, { on ->
                                val next = DEFAULT_WEATHER_FIELDS.filter { if (it == key) on else it in disp.weatherFields }
                                display { copy(weatherFields = next) }
                            }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Pane.Disaster -> {
            PaneTitle(L("防災（気象庁）", "Alerts (JMA)"), L("警報・注意報、地震、津波、台風、噴火を出すカードです。右側には強震モニタを重ねます。", "Shows warnings and advisories, earthquakes, tsunamis, typhoons and eruptions. The real-time seismic monitor is shown on the right."))
            CardSwitch(disp.showDisaster) { copy(showDisaster = it) }
            Field(hint = L("切ると通信自体を止めます。カードを隠すだけなら上のスイッチを切ってください。", "Turning this off stops fetching altogether. To just hide the card, use the switch above.")) {
                SwitchRow(L("防災情報を取得する", "Fetch disaster information"), d.disaster.enabled, { set(d.copy(disaster = d.disaster.copy(enabled = it))) })
            }
            Field(L("警報・注意報の地域", "Area for warnings and advisories"), L("「場所」で選んだ天気の地点がある市町村の警報・注意報を表示します。変えるときは「場所」を変更してください。", "Shows warnings and advisories for the municipality of your weather location. To change it, change \"Location\".")) {
                val s = graph.disaster.state
                val text = when {
                    !config.disaster.enabled -> L("防災情報の取得が無効です", "Fetching disaster information is off")
                    d.location != config.location -> L("「場所」の変更を保存すると、その地点の市町村に変わります", "After you save the new \"Location\", this changes to that municipality")
                    !s.available -> L("まだ取得できていません", "Not fetched yet")
                    s.areaName == null -> L("天気の地点から市町村を決められません。「場所」で国内の地点を選んでください", "Can't determine a municipality from your location. Choose a location in Japan under \"Location\"")
                    else -> listOfNotNull(app.dashboard.data.Jma.pick(s.officeName, s.officeNameEn), app.dashboard.data.Jma.pick(s.areaName, s.areaNameEn)).joinToString(" ")
                }
                Text(text, fontSize = 14.tu)
            }
            Field(L("表示する地震の最大震度", "Minimum earthquake intensity"), L("この震度以上で直近の 1 件だけを表示します。", "Shows only the latest earthquake at or above this intensity.")) {
                Select(listOf("1" to L("震度1以上", "Intensity 1+"), "3" to L("震度3以上", "Intensity 3+"), "4" to L("震度4以上", "Intensity 4+"), "5-" to L("震度5弱以上", "Intensity 5 Lower+")), d.disaster.minIntensity, { set(d.copy(disaster = d.disaster.copy(minIntensity = it))) })
            }
            Field(L("カードに出す情報", "What to show"), L("津波と警報・注意報は命に関わるため、常に表示します。強震モニタを外すと本文が横いっぱいに広がり、3 秒ごとの画像取得も止まります。", "Tsunamis, warnings and advisories are always shown because they concern safety. Hiding the seismic monitor widens the text and stops the image download every 3 seconds.")) {
                SwitchRow(L("台風情報", "Typhoons"), disp.disasterShowTyphoon, { display { copy(disasterShowTyphoon = it) } })
                SwitchRow(L("噴火情報", "Eruptions"), disp.disasterShowVolcano, { display { copy(disasterShowVolcano = it) } })
                SwitchRow(L("強震モニタ", "Seismic monitor"), disp.disasterShowKmoni, { display { copy(disasterShowKmoni = it) } })
            }
            Notice(L("警報・注意報は気象庁の警報ページと同じ名前で表示します（例: レベル４土砂災害危険警報）。市町村は、気象庁のページの「現在地」と同じ方法で、天気の地点の緯度経度から決めています。", "Warnings and advisories use the same names as JMA's warning page. The municipality is determined from your location's coordinates, the same way JMA's \"Current location\" works."))
        }

        Pane.Memo -> {
            PaneTitle("LINE メモ", L("LINE Bot に送ったメッセージを壁に並べるカードです。中継（Cloudflare Worker）の作り方は worker/README.md を見てください。", "Shows messages sent to a LINE bot. See worker/README.md for how to build the relay (Cloudflare Worker)."))
            CardSwitch(disp.showMemo) { copy(showMemo = it) }
            Field { SwitchRow(L("メモを取得する", "Fetch memos"), d.memoEnabled, { set(d.copy(memoEnabled = it)) }) }
            Field(L("中継先の URL", "Relay URL"), L("Worker の URL を貼れば、末尾は自動で /memo に直します。", "Paste the Worker URL; the path is fixed to /memo automatically.")) {
                Input(d.memoEndpoint, { set(d.copy(memoEndpoint = it)) }, placeholder = "https://dashboard-line-relay.xxxx.workers.dev")
            }
            Field(L("端末トークン", "Device token"), L("Worker の DEVICE_TOKEN と同じ値。保存済みの値は表示しません。", "Same value as the Worker's DEVICE_TOKEN. The saved value is never shown.")) {
                Input(d.memoToken, { set(d.copy(memoToken = it)) }, placeholder = if (config.memo.token.isNullOrBlank()) L("未設定", "Not set") else L("設定済み（変更する場合のみ入力）", "Set (enter only to change)"), password = true)
            }
            Field(
                L("取得の間隔（秒）", "Fetch interval (seconds)"),
                L(
                    "10〜600 秒。おすすめは 30 秒（既定）です。届いてすぐ見たいなら 10〜15 秒、ふだんは 30〜60 秒、たまにしか送らないなら 120〜300 秒で十分です。" +
                        "短くするほど早く届きますが、通信と電池の消費が増え、Cloudflare の無料枠（Workers のリクエスト・KV の読み取りとも 1 日 10 万回）にも近づきます（10 秒で 1 日 8,640 回、30 秒で 2,880 回）。",
                    "10–600 seconds. 30 seconds (the default) is recommended. Use 10–15 seconds to see memos right away, 30–60 seconds for everyday use, and 120–300 seconds if you rarely send any. " +
                        "Shorter intervals deliver sooner but use more traffic and battery, and get closer to the Cloudflare free tier (100,000 Workers requests and KV reads a day): 8,640 a day at 10 s, 2,880 at 30 s.",
                ),
            ) {
                Input(d.memoIntervalSec, { set(d.copy(memoIntervalSec = it.filter(Char::isDigit))) }, number = true)
            }
        }

        Pane.Hourly -> {
            PaneTitle("時間別予報", L("これから 12 時間の気温と降水確率のグラフです。", "Temperature and chance of rain for the next 12 hours."))
            CardSwitch(disp.showHourly) { copy(showHourly = it) }
            Field(L("描くもの", "What to draw")) {
                Select(listOf("both" to L("気温と降水確率", "Temperature and chance of rain"), "temp" to L("気温だけ", "Temperature only"), "precip" to L("降水確率だけ", "Chance of rain only")), disp.hourlyMode, { display { copy(hourlyMode = it) } })
            }
        }

        Pane.Spotify -> SpotifyPane(graph, config, d, set, onOpenBrowser)

        Pane.Wifi -> {
            PaneTitle("Wi-Fi", L("Wi-Fi のリンク速度・電波の強さ・通信量を出すカードです。", "Shows Wi-Fi link speed, signal strength and traffic."))
            CardSwitch(disp.showWifi) { copy(showWifi = it) }
            Field { SwitchRow(L("回る地球を出す", "Show the spinning globe"), disp.wifiShowGlobe, { display { copy(wifiShowGlobe = it) } }) }
        }

        Pane.Stats -> {
            PaneTitle("端末状態", L("CPU・電池・温度・ストレージ・メモリを出すカードです。", "Shows CPU, battery, temperature, storage and memory."))
            CardSwitch(disp.showDeviceStats) { copy(showDeviceStats = it) }
        }

        Pane.News -> {
            PaneTitle(L("ニュース / RSS", "News / RSS"), L("登録した RSS / Atom の見出しを出すカードです。サイトのトップページの URL でも、フィードを自動で探します。", "Shows headlines from your RSS / Atom feeds. A site's top page URL also works; the feed is found automatically."))
            CardSwitch(disp.showFeed) { copy(showFeed = it) }
            Field { SwitchRow(L("ニュースを取得する", "Fetch news"), d.feedEnabled, { set(d.copy(feedEnabled = it)) }) }
            Field(L("フィードの URL（1 行に 1 つ、最大 5 本）", "Feed URLs (one per line, up to 5)")) {
                Input(d.feedUrls, { set(d.copy(feedUrls = it)) }, placeholder = "https://example.com/rss.xml", singleLine = false, minLines = 3)
            }
            Field(L("表示する件数（1〜40）", "Number of items (1–40)")) {
                Input(d.feedMax, { set(d.copy(feedMax = it.filter(Char::isDigit))) }, number = true)
            }
        }

        Pane.Daily -> {
            PaneTitle("週間予報", L("7 日間の天気・気温の範囲・降水を出すカードです。", "7-day weather, temperature range and precipitation."))
            CardSwitch(disp.showDaily) { copy(showDaily = it) }
        }

        Pane.Timer -> {
            PaneTitle("タイマー", L("時・分をドラムで選んで開始する簡易タイマーです。鳴る音は「通知」で選べます。", "A simple timer: pick hours and minutes on the drums and start. The alarm sound is chosen in \"Notifications\"."))
            CardSwitch(disp.showTimer) { copy(showTimer = it) }
        }

        Pane.Word -> {
            PaneTitle("今日の単語", L("英単語（英検準1級程度）を 1 時間に 1 語ずつ出すカードです。", "Shows one English word (advanced level) per hour."))
            CardSwitch(disp.showWord) { copy(showWord = it) }
        }

        Pane.AnalogClock -> {
            PaneTitle("アナログ時計", L("1/5 秒刻みの細かい目盛りと日付窓のある、アナログの時計です。", "An analog clock with fine 1/5-second ticks and a date window."))
            CardSwitch(disp.showAnalogClock) { copy(showAnalogClock = it) }
            Field(hint = L("切ると秒針が 1 秒ごとに刻みます。描き直しが 1 秒に 1 回になるので、古い端末で動きが重いときに切ってください。", "When off, the second hand ticks once per second. It redraws only once a second, so turn this off if animation is slow on an old device.")) {
                SwitchRow(L("秒針をなめらかに動かす", "Smooth second hand"), disp.analogSweep, { display { copy(analogSweep = it) } })
            }
            Field { SwitchRow(L("文字盤に数字を入れる", "Show numerals on the dial"), disp.analogNumerals, { display { copy(analogNumerals = it) } }) }
        }

        Pane.Calendar -> CalendarPane(graph, config, d, set)
        Pane.Train -> TrainPane(graph, config, d, set)

        Pane.Radar -> {
            PaneTitle("雨雲レーダー", L("気象庁の雨雲の動き（5 分ごと）を、文字や道路の無い灰色の地図（Esri）に重ねて出すカードです。中心は「場所」で選んだ地点で、ドラッグで別の場所へ動かせます（3 分触らなければ戻ります）。範囲はカードの右上の「＋」「−」で変えられます。", "Shows JMA's rain clouds (every 5 minutes) over a plain gray map (Esri) without labels or roads. It's centered on your \"Location\"; drag to move elsewhere (it returns after 3 minutes untouched). Zoom with + / − at the top right of the card."))
            CardSwitch(disp.showRadar) { copy(showRadar = it) }
            Notice(L("カードを表示している間だけ、5 分ごとに地図と雨雲の画像（合わせて 30 枚ほど、1 枚数 KB）を取得します。日本国内の地点だけ雨雲が出ます。", "Map and rain images (about 30 tiles of a few KB each) are fetched every 5 minutes only while the card is shown. Rain is shown only for locations in Japan."))
        }

        Pane.SunMoon -> {
            PaneTitle(L("日の出・日の入り ／ 月", "Sunrise / sunset & Moon"), L("今日の日の出・日の入りと太陽の位置、いまの月の満ち欠け・月齢・次の満月と新月を出すカードです。", "Shows today's sunrise and sunset with the sun's position, plus the current moon phase, age and the next full and new moon."))
            CardSwitch(disp.showSunMoon) { copy(showSunMoon = it) }
            Notice(L("日の出・日の入りは天気と同じ Open-Meteo の値（「場所」の地点）です。月の満ち欠けは端末の中で計算するので、通信は増えません。", "Sunrise and sunset come from Open-Meteo (your \"Location\"), like the weather. The moon phase is calculated on the device, so no extra traffic."))
        }

        Pane.Countdown -> {
            PaneTitle("カウントダウン", L("決めた日までの残りの日数を、近い順に並べるカードです。1 日を切ると時・分・秒で数えます。", "Counts down the days to the dates you choose, nearest first. Under one day it counts hours, minutes and seconds."))
            CardSwitch(disp.showCountdown) { copy(showCountdown = it) }
            Field(L("数える行事", "Built-in events"), L("「次の祝日」「次の休日」は内閣府の祝日の一覧（週に 1 回取得）を使います。", "\"Next holiday\" and \"Next day off\" use the Cabinet Office's list of Japanese holidays (fetched weekly).")) {
                CountdownLabels.forEach { (key, label) ->
                    CheckRow(label, key in d.countdownBuiltins, { on ->
                        set(d.copy(countdownBuiltins = CountdownLabels.keys.filter { if (it == key) on else it in d.countdownBuiltins }))
                    })
                }
            }
            Field(L("自分で決める日（1 行に 1 つ）", "Your own dates (one per line)"), L("「名前 日付」の形で書きます。日付は 2027-03-18（その日だけ）か 03-18（毎年）。後ろに 18:30 のように時刻も付けられます（最大 10 行）。", "Write \"name date\". The date is 2027-03-18 (that day only) or 03-18 (every year). You can add a time like 18:30 (up to 10 lines).")) {
                Input(d.countdownText, { set(d.copy(countdownText = it)) }, placeholder = L("卒業 2027-03-18\n誕生日 05-04", "Graduation 2027-03-18\nBirthday 05-04"), singleLine = false, minLines = 3)
            }
        }

        Pane.Today -> {
            PaneTitle("今日は何の日", L("今日の記念日・年中行事を出すカードです。日本語版 Wikipedia の日付の記事（例:「9月26日」）から、1 日 1 回取得します。", "Shows today's observances. Fetched once a day from Wikipedia (in English, the \"On this day\" feed of the English Wikipedia)."))
            CardSwitch(disp.showToday) { copy(showToday = it) }
            Field(hint = L("1 時間ごとに別のできごとに入れ替わります。", "Changes to a different event every hour.")) {
                SwitchRow(L("過去の今日のできごとを 1 件添える", "Add one historical event for today"), disp.todayShowEvent, { display { copy(todayShowEvent = it) } })
            }
            Notice(L("記事は Wikipedia の執筆者によるもので、CC BY-SA 4.0 で公開されています（フッターに出典を出します）。", "Articles are written by Wikipedia contributors and published under CC BY-SA 4.0 (credited in the footer)."))
        }

        Pane.Stocks -> {
            PaneTitle("株価", L("主要な株価指数や為替の値と、簡単なチャートを出すカードです。10 分ごとに取得します。", "Shows major stock indices and exchange rates with small charts. Fetched every 10 minutes."))
            CardSwitch(disp.showStocks) { copy(showStocks = it) }
            Field(L("銘柄（1 行に 1 つ、最大 6 つ）", "Symbols (one per line, up to 6)"), L("「記号 表示名」の形で書きます。記号は Yahoo Finance の表記です（例: ^N225 日経平均 / ^DJI NY ダウ / ^GSPC S&P 500 / 7203.T トヨタ / USDJPY=X ドル円）。", "Write \"symbol name\". Symbols use Yahoo Finance notation (e.g. ^N225 Nikkei 225 / ^DJI Dow Jones / ^GSPC S&P 500 / 7203.T Toyota / USDJPY=X USD/JPY).")) {
                Input(d.stocksText, { set(d.copy(stocksText = it)) }, placeholder = L("^N225 日経平均", "^N225 Nikkei 225"), singleLine = false, minLines = 4)
            }
            Field(L("チャートの期間", "Chart period")) {
                Select(listOf("1d" to L("1 日（5 分足）", "1 day (5-min bars)"), "5d" to L("5 日", "5 days"), "1mo" to L("1 か月", "1 month"), "6mo" to L("6 か月", "6 months"), "1y" to L("1 年", "1 year")), d.stocksRange, { set(d.copy(stocksRange = it)) })
            }
            Notice(L("値は Yahoo Finance の公開されていない API から取っています。数十分の遅れがあり、予告なく取れなくなることがあります。投資の判断には使わないでください。", "Values come from an unofficial Yahoo Finance API. They are delayed by tens of minutes and may stop working without notice. Don't use them for investment decisions."), Wd.Amber)
        }

        Pane.Calculator -> {
            PaneTitle("計算機", L("四則演算の計算機です。掛け算・割り算を先に計算します（12 + 3 × 2 = 18）。", "A four-function calculator. Multiplication and division come first (12 + 3 × 2 = 18)."))
            CardSwitch(disp.showCalculator) { copy(showCalculator = it) }
            Notice(L("カードが横長のときは左に表示・右に鍵盤、「カードの配置」で縦に 2 行ぶんへ伸ばすと上に表示・下に大きな鍵盤になります。", "When the card is wide, the display is on the left and the keypad on the right; stretch it to 2 rows in \"Card layout\" to get the display on top and a large keypad below."))
        }

        Pane.Photos -> PhotosPane(graph, config, d, set)

        Pane.Crypto -> {
            PaneTitle("暗号通貨", L("選んだ 1 つの暗号通貨の値と、値動きのチャートを出すカードです。チャートは 1 つだけで、どの通貨を出すか、折れ線とろうそく足のどちらで描くかをここで決めます。", "Shows the price of one cryptocurrency with a chart. There's a single chart; choose the coin and whether to draw a line or candlesticks here."))
            CardSwitch(disp.showCrypto) { copy(showCrypto = it) }
            Field(L("表示する通貨", "Coin")) {
                Select(CRYPTO_COINS + (Draft.CRYPTO_CUSTOM to L("その他（ID を入力）", "Other (enter an ID)")), d.cryptoCoin, { set(d.copy(cryptoCoin = it)) })
            }
            if (d.cryptoCoin == Draft.CRYPTO_CUSTOM) {
                Field(L("通貨の ID", "Coin ID"), L("CoinGecko の通貨のページの URL の末尾です（例: coingecko.com/ja/コイン/shiba-inu なら shiba-inu。英小文字・数字・ハイフン）。", "The end of the coin's page URL on CoinGecko (e.g. shiba-inu for coingecko.com/en/coins/shiba-inu; lowercase letters, digits and hyphens).")) {
                    Input(d.cryptoCustomId, { set(d.copy(cryptoCustomId = it)) }, placeholder = L("例: shiba-inu", "e.g. shiba-inu"))
                }
            }
            Field(L("値の通貨", "Currency")) {
                Select(listOf("jpy" to L("日本円（¥）", "Japanese yen (¥)"), "usd" to L("米ドル（$）", "US dollar ($)")), d.cryptoCurrency, { set(d.copy(cryptoCurrency = it)) })
            }
            Field(L("チャートの期間", "Chart period"), L("カードの見出しの右の「−」「＋」でも、設定を開かずに変えられます。", "You can also change it without opening settings, with − / + to the right of the card title.")) {
                Select(CRYPTO_RANGE_LABELS, d.cryptoRange, { set(d.copy(cryptoRange = it)) })
            }
            Field(L("チャートの描き方", "Chart style"), L("ろうそく足の 1 本は、24 時間なら 30 分、7 日なら 4 時間、30 日なら 16 時間、1 年なら 8 日ぶんです。緑が値上がり、赤が値下がりです。", "One candle covers 30 minutes for 24 hours, 4 hours for 7 days, 16 hours for 30 days and 8 days for 1 year. Green means up, red means down.")) {
                Select(CRYPTO_CHARTS, d.cryptoChart, { set(d.copy(cryptoChart = it)) })
            }
            Field(L("更新の間隔", "Update interval"), L("短くするほど通信が増えます。取得先の登録なしの枠は 1 分に数回までなので、1 分にすると混み合う時間帯に取れないことがあります。", "Shorter intervals mean more traffic. The free tier allows only a few calls per minute, so 1 minute may fail at busy times.")) {
                Select(CRYPTO_INTERVALS.map { it to if (it < 60) L("$it 分", "$it min") else L("${it / 60} 時間", "${it / 60} h") }, d.cryptoInterval, { set(d.copy(cryptoInterval = it)) })
            }
            val s = graph.crypto.state
            StatusText(
                when {
                    !config.display.showCrypto -> L("カードを表示しているときだけ取得します", "Fetched only while the card is shown")
                    s.lastError != null -> L("エラー: ${s.lastError}", "Error: ${s.lastError}")
                    s.fetchedAt > 0 -> L("取得できています（${listOfNotNull(s.name, s.symbol).joinToString(" ")}）", "Fetching OK (${listOfNotNull(s.name, s.symbol).joinToString(" ")})")
                    else -> L("まだ取得していません —「全て保存」のあと少し待ってください", "Not fetched yet — wait a moment after Save all")
                },
                if (s.lastError != null && config.display.showCrypto) Wd.Red else Wd.Text2,
            )
            Spacer(Modifier.height(18.dp))
            Notice(L("値は CoinGecko の公開 API（登録不要）から取っています。数分の遅れがあり、混み合うと一時的に取れないことがあります。Phantom の API は外部のアプリに公開されていないため使っていません。投資の判断には使わないでください。", "Values come from CoinGecko's public API (no sign-up). They are delayed by a few minutes and may be unavailable when busy. Phantom's API isn't open to other apps, so it isn't used. Don't use them for investment decisions."), Wd.Amber)
        }

        Pane.Hamster -> {
            PaneTitle("ハムスター", L("画面下で回し車を走るハムスターです。意匠は Uiverse.io の Nawsome 作「Loader」（MIT License）によります。", "A hamster running on a wheel at the bottom of the screen. Design based on \"Loader\" by Nawsome on Uiverse.io (MIT License)."))
            Field { SwitchRow(L("ハムスターを出す", "Show the hamster"), disp.showHamster, { display { copy(showHamster = it) } }) }
        }

        Pane.Device -> DevicePane(graph)
        Pane.Network -> NetworkPane(graph, config)
        Pane.Presets -> PresetsPane(graph, config, dirty)
        Pane.Lock -> LockPane(graph, config)
        Pane.Info -> InfoPane(onVersionArt)

        Pane.Todo -> {
            PaneTitle("Todo", L("やることを並べるカードです。カードの下の欄に書いて追加し、左の丸を押すと消えます。", "A card listing things to do. Type in the box at the bottom of the card to add one; tap the circle on the left to remove it."))
            CardSwitch(disp.showTodo) { copy(showTodo = it) }
            Notice(L("Todo の中身は設定ではなくこの端末のデータなので、プリセットを切り替えても変わりません（最大 50 件）。", "To-dos are data on this device, not settings, so switching presets doesn't change them (up to 50)."))
        }
    }
}

@Composable
private fun ThemePane(graph: AppGraph, config: Config, d: Draft, set: (Draft) -> Unit) {
    val disp = d.display
    val hasImage = config.wallpaper.imageSetAt > 0
    var status by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf(Wd.Text2) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun report(ok: String, block: () -> Unit) {
        status = L("処理中…", "Working…")
        statusColor = Wd.Text2
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(block) }
                .onSuccess { status = ok; statusColor = Wd.Green }
                .onFailure { status = (it as? SettingsController.SettingsException)?.message ?: L("エラー: ${it.message}", "Error: ${it.message}"); statusColor = Wd.Red }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        report(L("背景画像を設定しました", "Background image set")) {
            graph.settings.setWallpaper { context.contentResolver.openInputStream(uri) ?: error(L("画像を開けません", "Can't open the image")) }
        }
    }

    PaneTitle("テーマ", L("ダッシュボード・設定・ブラウズの色の基調と、ダッシュボードの背景を決めます。", "Sets the color scheme of the dashboard, settings and browser, and the dashboard background."))
    Field(L("色の基調", "Color scheme"), L("ダークは暗い部屋で眩しくならない配色、ホワイトは明るい部屋で読みやすい配色です。", "Dark avoids glare in a dark room; White is easier to read in a bright room.")) {
        Select(listOf("dark" to L("ダーク", "Dark"), "light" to L("ホワイト", "White")), disp.theme, { set(d.copy(display = disp.copy(theme = it))) })
    }
    Field(L("背景画像", "Background image"), L("端末の写真から選びます。選んだ画像は画面の大きさに縮めて保存し、選んだその場で反映します（「全て保存」は要りません）。", "Choose from the device's photos. The image is shrunk to the screen size and applied immediately (no need to Save all).")) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(if (hasImage) L("画像を選び直す", "Choose another image") else L("画像を選ぶ", "Choose an image"), {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            ActionButton(L("背景画像を外す", "Remove background image"), { report(L("背景画像を外しました", "Background image removed")) { graph.settings.clearWallpaper() } }, enabled = hasImage)
        }
        StatusText(if (status.isNotEmpty()) status else if (hasImage) L("背景画像を表示中", "Showing a background image") else L("背景画像なし", "No background image"), if (status.isNotEmpty()) statusColor else Wd.Text2)
    }
    Field(L("カードの背景色", "Card color"), L("すべてのカードの面の色です。文字が読めるよう、選んだ色をテーマの面の色に混ぜて使います（ダークは 3 割ほど、ホワイトは 2 割ほど）。背景画像があるときは下の不透明度も効きます。", "The surface color of all cards. To keep text readable, the color is mixed into the theme's surface color (about 30% for Dark, 20% for White). With a background image, the opacity below also applies.")) {
        ColorSwatches(app.dashboard.data.CardColors.ALL.map { it.hex to it.label }, disp.cardColor) { set(d.copy(display = disp.copy(cardColor = it))) }
    }
    PercentSlider(
        L("カードの不透明度", "Card opacity"), (disp.cardOpacity * 100).roundToInt(), 20, 100, 5, { set(d.copy(display = disp.copy(cardOpacity = it / 100.0))) },
        L("背景画像があるときだけ効きます。小さいほどカードが透けて背景が見えます（100% で透けません）。", "Only applies with a background image. Lower values let more of the background show through (100% is opaque)."),
    )
    PercentSlider(
        L("カードの角の丸み", "Card corner radius"), disp.cardRadius, app.dashboard.data.CARD_RADIUS_MIN, app.dashboard.data.CARD_RADIUS_MAX, 2,
        { set(d.copy(display = disp.copy(cardRadius = it))) },
        L("0 で四角、大きいほど丸くなります（既定は ${app.dashboard.data.CARD_RADIUS_DEFAULT}）。下の見本はいまの値で描いています。", "0 is square; larger is rounder (default ${app.dashboard.data.CARD_RADIUS_DEFAULT}). The preview below uses the current value."),
        unit = " dp",
    )
    RadiusPreview(disp.cardRadius)
}

/** カードの角の丸みの見本。ダッシュボードのカードと同じ枠（[app.dashboard.ui.common.WdCard]）を、選んだ丸みで 3 枚並べる。 */
@Composable
private fun RadiusPreview(radius: Int) {
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().padding(bottom = 22.dp).height(150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val saved = Wd.cardRadius
        Wd.cardRadius = radius.dp
        app.dashboard.ui.common.WdCard(L("時刻", "Clock"), Modifier.weight(1.2f).fillMaxHeight()) {
            Text("12:34", fontSize = 40.tu, fontWeight = FontWeight.SemiBold)
            Text(L("見本", "Preview"), color = Wd.Text2, fontSize = 12.tu)
        }
        app.dashboard.ui.common.WdCard(L("天気", "Weather"), Modifier.weight(1f).fillMaxHeight(), note = "24°") {
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape((radius * 0.6f).dp)).background(accent.copy(alpha = 0.22f)))
        }
        app.dashboard.ui.common.WdCard("Todo", Modifier.weight(0.8f).fillMaxHeight()) {
            Text("• " + L("買い物", "Groceries"), fontSize = 13.tu)
            Text("• " + L("洗濯", "Laundry"), fontSize = 13.tu)
        }
        Wd.cardRadius = saved
    }
}

private val CountdownLabels get() = app.dashboard.data.Countdown.BUILTIN_LABELS

/** 写真を切り替える間隔の選択肢（秒 → 表示）。 */
internal fun photoIntervalLabel(sec: Int) = when {
    sec < 60 -> L("$sec 秒", "$sec s")
    sec < 3600 -> L("${sec / 60} 分", "${sec / 60} min")
    sec < 86400 -> L("${sec / 3600} 時間", "${sec / 3600} h")
    else -> L("${sec / 86400} 日", "${sec / 86400} d")
}

@Composable
private fun PhotosPane(graph: AppGraph, config: Config, d: Draft, set: (Draft) -> Unit) {
    val disp = d.display
    val c = config.photos
    val s = graph.photos.state
    PaneTitle(L("写真（iCloud 共有アルバム）", "Photos (iCloud Shared Album)"), L("iCloud の共有アルバムの写真を、決めた間隔で切り替えて出すカードです。カードを押すと次の写真へ進みます。", "Shows photos from an iCloud Shared Album, switching at the chosen interval. Tap the card for the next photo."))
    Field { SwitchRow(L("このカードをダッシュボードに表示する", "Show this card on the dashboard"), disp.showPhotos, { set(d.copy(display = disp.copy(showPhotos = it))) }) }
    Field { SwitchRow(L("写真を取得する", "Fetch photos"), d.photosEnabled, { set(d.copy(photosEnabled = it)) }) }
    Field(
        L("共有アルバムの URL", "Shared album URL"),
        L("iPhone の「写真」→ 共有アルバムを開く → 人のアイコン →「公開 Web サイト」を ON にして出る URL（https://photos.icloud.com/shared/album/… か、古い https://www.icloud.com/sharedalbum/#…）。", "The URL shown after turning on \"Public Website\" in iPhone Photos → open the shared album → people icon (https://photos.icloud.com/shared/album/… or the older https://www.icloud.com/sharedalbum/#…).") +
            L("URL を知っている人は誰でも写真を見られるので、保存済みの値は表示しません。", " Anyone with the URL can see the photos, so the saved value is never shown."),
    ) {
        Input(d.photosUrl, { set(d.copy(photosUrl = it)) }, placeholder = if (c.albumUrl.isNullOrBlank()) "https://photos.icloud.com/shared/album/…" else L("設定済み（変更する場合のみ入力）", "Set (enter only to change)"), password = true)
    }
    Field(L("写真を変える間隔", "Photo interval")) {
        Select(app.dashboard.data.PhotoRepository.INTERVALS.map { it to photoIntervalLabel(it) }, d.photosInterval, { set(d.copy(photosInterval = it)) })
    }
    Field { SwitchRow(L("順番を混ぜる（オフなら新しい写真から順に）", "Shuffle (when off, newest first)"), d.photosShuffle, { set(d.copy(photosShuffle = it)) }) }
    StatusText(
        when {
            !c.enabled -> L("取得は無効です", "Fetching is off")
            s.lastError != null -> L("エラー: ${s.lastError}", "Error: ${s.lastError}")
            s.fetchedAt > 0 -> L("取得できています（${s.albumName ?: "アルバム"}、${s.count} 枚）", "Fetching OK (${s.albumName ?: "album"}, ${s.count} photos)")
            else -> L("まだ取得していません —「全て保存」のあと少し待ってください（カードを表示しているときだけ取得します）", "Not fetched yet — wait a moment after Save all (fetched only while the card is shown)")
        },
        if (s.lastError != null && c.enabled) Wd.Red else Wd.Text2,
    )
    Spacer(Modifier.height(18.dp))
    Notice(
        L("iCloud 写真のライブラリそのものは、Apple ID の 2 ファクタ認証が要る非公開の仕組みのため読めません（App 用パスワードも使えません）。", "The iCloud Photos library itself can't be read because it requires Apple ID two-factor authentication (app-specific passwords don't work). ") +
            L("出したい写真を共有アルバムに入れてください。写真は 30 分ごとに一覧を取り直し、出すときに 1 枚ずつ読み込みます（1 枚 数百 KB 〜 1 MB）。", "Put the photos you want into a shared album. The list is refreshed every 30 minutes and photos are loaded one at a time (a few hundred KB to 1 MB each)."),
    )
}

@Composable
private fun CalendarPane(graph: AppGraph, config: Config, d: Draft, set: (Draft) -> Unit) {
    val disp = d.display
    val c = config.calendar
    val s = graph.calendar.state
    PaneTitle(L("予定表（iCloud カレンダー）", "Calendar (iCloud)"), L("iCloud のカレンダーの予定を、今日から決めた日数ぶん並べるカードです。15 分ごとに取得します。", "Lists events from your iCloud calendars for the chosen number of days from today. Fetched every 15 minutes."))
    Field { SwitchRow(L("このカードをダッシュボードに表示する", "Show this card on the dashboard"), disp.showCalendar, { set(d.copy(display = disp.copy(showCalendar = it))) }) }
    Field { SwitchRow(L("予定を取得する", "Fetch events"), d.calendarEnabled, { set(d.copy(calendarEnabled = it)) }) }
    Field(L("つなぎ方", "Connection")) {
        Select(listOf("caldav" to L("Apple ID で接続（すべてのカレンダー）", "Sign in with Apple ID (all calendars)"), "ics" to L("共有カレンダーの公開 URL", "Public URL of a shared calendar")), d.calendarMode, { set(d.copy(calendarMode = it)) })
    }
    if (d.calendarMode == "ics") {
        Field(L("公開 URL", "Public URL"), L("iPhone の「カレンダー」→ カレンダーの (i) →「公開カレンダー」を ON にして出る webcal:// の URL。URL を知っている人は誰でも予定を読めるので、保存済みの値は表示しません。", "The webcal:// URL shown after turning on \"Public Calendar\" in iPhone Calendar → the calendar's (i). Anyone with the URL can read the events, so the saved value is never shown.")) {
            Input(d.calendarIcsUrl, { set(d.copy(calendarIcsUrl = it)) }, placeholder = if (c.icsUrl.isNullOrBlank()) "webcal://p00-caldav.icloud.com/published/2/…" else L("設定済み（変更する場合のみ入力）", "Set (enter only to change)"), password = true)
        }
    } else {
        Field("Apple ID") {
            Input(d.calendarAppleId, { set(d.copy(calendarAppleId = it)) }, placeholder = L("例: name@icloud.com", "e.g. name@icloud.com"))
        }
        Field(L("App 用パスワード", "App-specific password"), L("Apple ID のふだんのパスワードでは接続できません。account.apple.com →「サインインとセキュリティ」→「App 用パスワード」で作った 16 文字を入れてください（いつでも取り消せます）。保存済みの値は表示しません。", "Your normal Apple ID password won't work. Enter the 16 characters created at account.apple.com → \"Sign-In and Security\" → \"App-Specific Passwords\" (you can revoke it any time). The saved value is never shown.")) {
            Input(d.calendarPassword, { set(d.copy(calendarPassword = it)) }, placeholder = if (c.password.isNullOrBlank()) "xxxx-xxxx-xxxx-xxxx" else L("設定済み（変更する場合のみ入力）", "Set (enter only to change)"), password = true)
        }
    }
    Field(L("何日先まで出すか（1〜31 日）", "Days ahead (1–31)")) {
        Input(d.calendarDays, { set(d.copy(calendarDays = it.filter(Char::isDigit))) }, number = true)
    }
    StatusText(
        when {
            !c.enabled -> L("取得は無効です", "Fetching is off")
            s.lastError != null -> L("エラー: ${s.lastError}", "Error: ${s.lastError}")
            s.fetchedAt > 0 -> L("取得できています（${s.events.size} 件）", "Fetching OK (${s.events.size} events)")
            else -> L("まだ取得していません —「全て保存」のあと少し待ってください", "Not fetched yet — wait a moment after Save all")
        },
        if (s.lastError != null && c.enabled) Wd.Red else Wd.Text2,
    )
}

@Composable
private fun TrainPane(graph: AppGraph, config: Config, d: Draft, set: (Draft) -> Unit) {
    val disp = d.display
    val t = config.train
    var choices by remember { mutableStateOf(graph.train.railwayChoices()) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val s = graph.train.state
    PaneTitle(L("運行情報（公共交通オープンデータ）", "Trains (Public Transportation Open Data)"), L("電車の遅れ・運転見合わせを出すカードです。公共交通オープンデータセンター（ODPT）の API から 5 分ごとに取得します。", "Shows train delays and suspensions. Fetched every 5 minutes from the ODPT (Public Transportation Open Data Center) API."))
    Field { SwitchRow(L("このカードをダッシュボードに表示する", "Show this card on the dashboard"), disp.showTrain, { set(d.copy(display = disp.copy(showTrain = it))) }) }
    Field { SwitchRow(L("運行情報を取得する", "Fetch train information"), d.trainEnabled, { set(d.copy(trainEnabled = it)) }) }
    Field(L("アクセストークン（本番）", "Access token (production)"), L("developer.odpt.org で利用者登録（無料）をするともらえます。東京メトロ・都営地下鉄・私鉄各社の運行情報が取れます。保存済みの値は表示しません。", "Get one by registering (free) at developer.odpt.org. Covers Tokyo Metro, Toei Subway and many private railways. The saved value is never shown.")) {
        Input(d.trainToken, { set(d.copy(trainToken = it)) }, placeholder = if (t.token.isNullOrBlank()) L("未設定", "Not set") else L("設定済み（変更する場合のみ入力）", "Set (enter only to change)"), password = true)
    }
    Field(L("アクセストークン（チャレンジ）", "Access token (challenge)"), L("JR 東日本などは「公共交通オープンデータチャレンジ」用の API にだけ載っています。チャレンジに参加登録するともらえる別のトークンです（期間限定）。無くても動きます。", "JR East and some others are only in the API for the \"Open Data Challenge for Public Transportation\". It's a separate token you get by entering the challenge (limited period). Optional.")) {
        Input(d.trainChallengeToken, { set(d.copy(trainChallengeToken = it)) }, placeholder = if (t.challengeToken.isNullOrBlank()) L("未設定", "Not set") else L("設定済み（変更する場合のみ入力）", "Set (enter only to change)"), password = true)
    }
    Field(L("表示する路線（最大 12）", "Lines to show (up to 12)"), L("選ばないと、遅れや運転見合わせが出ている路線だけを出します（すべて平常なら「すべて平常運転」）。一覧はトークンを保存してから読み込めます。", "If none are selected, only lines with delays or suspensions are shown (\"All lines normal\" if none). Load the list after saving a token.")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(L("路線の一覧を読み込む", "Load the line list"), {
                status = L("読み込み中…", "Loading…")
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { graph.train.reloadCatalog() } }
                    choices = graph.train.railwayChoices()
                    status = r.fold({ L("${choices.size} 路線を読み込みました", "Loaded ${choices.size} lines") }, { L("エラー: ${it.message}", "Error: ${it.message}") })
                }
            }, enabled = !t.token.isNullOrBlank() || !t.challengeToken.isNullOrBlank())
            Spacer(Modifier.width(10.dp))
            if (d.trainRailways.isNotEmpty()) ActionButton(L("選択をすべて外す", "Clear selection"), { set(d.copy(trainRailways = emptyList())) })
        }
        StatusText(status)
        var group = ""
        choices.forEach { r ->
            if (r.operator != group) {
                group = r.operator
                Text(group, color = Wd.Text3, fontSize = 12.tu, modifier = Modifier.padding(top = 10.dp))
            }
            CheckRow(r.title, r.id in d.trainRailways, { on ->
                val next = if (on) (d.trainRailways + r.id).distinct().take(12) else d.trainRailways - r.id
                set(d.copy(trainRailways = next))
            })
        }
    }
    StatusText(
        when {
            !t.enabled -> L("取得は無効です", "Fetching is off")
            s.lastError != null && s.fetchedAt == 0L -> L("エラー: ${s.lastError}", "Error: ${s.lastError}")
            s.fetchedAt > 0 -> L("取得できています", "Fetching OK") + (s.lastError?.let { L("（一部エラー: $it）", " (partial error: $it)") } ?: "")
            else -> L("まだ取得していません —「全て保存」のあと少し待ってください", "Not fetched yet — wait a moment after Save all")
        },
        if (s.lastError != null && t.enabled) Wd.Amber else Wd.Text2,
    )
    Spacer(Modifier.height(18.dp))
    Notice(L("データは公共交通オープンデータ協議会が「公共交通オープンデータ基本ライセンス」で提供しているもので、正確さは保証されていません。急ぐときは各事業者の公式の案内も確かめてください。", "Data is provided by the Association for Open Data of Public Transportation under its basic license, and accuracy isn't guaranteed. When in a hurry, also check the operators' official information."))
}

@Composable
private fun ToneField(
    title: String,
    hint: String,
    enabled: Boolean?,
    onEnabled: (Boolean) -> Unit,
    tones: List<Pair<String, String>>,
    tone: String,
    onTone: (String) -> Unit,
    switchLabel: String = L("鳴らす", "Play"),
    threshold: (@Composable () -> Unit)? = null,
    onPreview: () -> Unit,
) {
    Field(title, hint) {
        if (enabled != null) SwitchRow(switchLabel, enabled, onEnabled)
        threshold?.let {
            it()
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Select(tones, tone, onTone, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            ActionButton(L("試聴", "Preview"), onPreview)
        }
    }
}

@Composable
private fun PlacePane(graph: AppGraph, config: Config, d: Draft, set: (Draft) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    PaneTitle("場所", L("天気・時間別予報・週間予報・日の出入り、そして防災の警報・注意報が、ここで決めた地点のものになります。", "Weather, hourly and weekly forecasts, sunrise/sunset and disaster warnings all use the location chosen here."))
    Field(L("都市名で検索", "Search by city name"), L("Open-Meteo の地名検索はローマ字か英語で入力してください（「札幌」では一致しません）。結果は日本語で返ります。", "Type in English or romaji (e.g. Sapporo). Results are returned in the display language.")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Input(query, { query = it }, Modifier.weight(1f), placeholder = L("例: Sapporo / Tokyo", "e.g. Sapporo / Tokyo"))
            Spacer(Modifier.width(10.dp))
            ActionButton(L("検索", "Search"), {
                val q = query.trim()
                if (q.isEmpty()) return@ActionButton
                status = L("検索中…", "Searching…")
                scope.launch {
                    val found = withContext(Dispatchers.IO) { runCatching { graph.weather.geocode(q) } }
                    results = found.getOrDefault(emptyList())
                    status = found.fold(
                        { if (it.isEmpty()) L("見つかりませんでした。ローマ字か英語で入力してください（例: Sapporo）", "Not found. Type in English or romaji (e.g. Sapporo)") else "" },
                        { L("エラー: ${it.message}", "Error: ${it.message}") },
                    )
                }
            })
        }
        StatusText(status)
        results.forEach { r ->
            Text(
                listOfNotNull(r.name, r.admin, r.country).joinToString(" / "),
                fontSize = 14.tu,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Wd.Border, RoundedCornerShape(8.dp))
                    .clickable {
                        set(d.copy(location = LocationConfig(true, r.nameJa ?: r.name, r.latitude, r.longitude, r.timezone, r.nameEn)))
                        results = emptyList()
                        query = ""
                    }.padding(12.dp),
            )
        }
    }
    Text(L("現在の設定地点: ${config.location.name}（${config.location.timezone}）", "Current location: ${config.location.displayName()} (${config.location.timezone})"), color = Wd.Text2, fontSize = 13.tu)
    if (d.location != config.location) {
        StatusText(L("選択中: ${d.location.name} — 「全て保存」で反映されます", "Selected: ${d.location.displayName()} — applied when you Save all"), Wd.Amber)
    }
}

@Composable
private fun SpotifyPane(graph: AppGraph, config: Config, d: Draft, set: (Draft) -> Unit, onOpenBrowser: (String) -> Unit) {
    val disp = d.display
    val connected = !config.spotify.refreshToken.isNullOrBlank()
    val savedId = config.spotify.clientId.isNotBlank() && d.spotifyClientId.trim() == config.spotify.clientId
    PaneTitle("Spotify", L("再生中の曲のジャケットと曲名を出し、再生・一時停止・曲送りができるカードです。", "Shows the cover and title of the song playing, with play, pause and skip controls."))
    Field { SwitchRow(L("このカードをダッシュボードに表示する", "Show this card on the dashboard"), disp.showSpotify, { set(d.copy(display = disp.copy(showSpotify = it))) }) }
    Field { SwitchRow(L("再生中の曲を取得する", "Fetch the song playing"), d.spotifyEnabled, { set(d.copy(spotifyEnabled = it)) }) }
    Field(L("カードに出すもの", "What to show"), L("どちらも外すと、空いた高さをジャケット・曲名・アーティスト名に回して大きく表示します。", "If both are off, the freed height is used to show the cover, title and artist larger.")) {
        SwitchRow(L("再生・曲送りのボタン", "Play / skip buttons"), disp.spotifyShowControls, { set(d.copy(display = disp.copy(spotifyShowControls = it))) })
        SwitchRow(L("再生時間", "Playback time"), disp.spotifyShowProgress, { set(d.copy(display = disp.copy(spotifyShowProgress = it))) })
    }
    Field("Client ID", L("秘密の値ではありません。入力したら「全て保存」してから「Spotify と連携」を押してください。", "Not a secret. After entering it, press Save all, then \"Connect Spotify\".")) {
        Input(d.spotifyClientId, { set(d.copy(spotifyClientId = it)) }, placeholder = L("例: 3f9a2c1b4d5e6f7a8b9c0d1e2f3a4b5c", "e.g. 3f9a2c1b4d5e6f7a8b9c0d1e2f3a4b5c"))
    }
    LyricsField(graph, d, set)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(L("Spotify と連携", "Connect Spotify"), { onOpenBrowser("http://127.0.0.1:${DashboardServer.PORT}/api/spotify/start") }, enabled = savedId)
        ActionButton(L("連携を解除", "Disconnect"), { graph.spotify.disconnect() }, enabled = connected)
    }
    StatusText(
        when {
            connected -> L("連携済み", "Connected")
            savedId -> L("未連携 —「Spotify と連携」を押してください", "Not connected — press \"Connect Spotify\"")
            else -> L("Client ID を保存すると連携できます", "Save a Client ID to connect")
        },
    )
    Spacer(Modifier.height(18.dp))
    Notice(
        L("操作は Spotify で鳴らしている端末に送るもので、このタブレットからは音は出ません（曲送りには Spotify Premium が必要です）。\n", "Controls are sent to the device playing Spotify; no sound comes from this tablet (skipping requires Spotify Premium).\n") +
            L("使うには Spotify Developer Dashboard で「Create app」→ Redirect URI に ${DashboardServer.SPOTIFY_REDIRECT_URI} を追加 → API は「Web API」を選び、", "To use it, in the Spotify Developer Dashboard choose \"Create app\" → add ${DashboardServer.SPOTIFY_REDIRECT_URI} as a Redirect URI → select \"Web API\", ") +
            L("できた Client ID を上に入力してください（Client Secret は使いません）。", "then enter the resulting Client ID above (the Client Secret isn't used)."),
    )
}

/** 歌詞の保存（直近 [app.dashboard.data.LyricsRepository.MAX_SAVED] 曲）。 */
@Composable
private fun LyricsField(graph: AppGraph, d: Draft, set: (Draft) -> Unit) {
    var count by remember { mutableStateOf(graph.lyrics.savedCount()) }
    val max = app.dashboard.data.LyricsRepository.MAX_SAVED
    Field(
        L("歌詞の保存", "Saving lyrics"),
        L(
            "オンにすると、全画面で見つけた歌詞を直近 $max 曲までこの端末に保存し、次からは通信せずに出します（古いものから消します）。" +
                "オフの間は保存も、保存した歌詞を使うこともしません（保存済みの歌詞は消すまで残ります）。",
            "When on, lyrics found in full screen are saved on this device for the latest $max songs and shown without fetching next time (oldest removed first). " +
                "When off, lyrics are neither saved nor read from storage (saved lyrics stay until you delete them).",
        ),
    ) {
        SwitchRow(L("直近 $max 曲の歌詞を端末に保存する", "Save lyrics of the latest $max songs on this device"), d.spotifySaveLyrics, { set(d.copy(spotifySaveLyrics = it)) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L("保存済み: $count 曲", "Saved: $count songs"), color = Wd.Text2, fontSize = 13.tu, modifier = Modifier.weight(1f))
            ActionButton(L("保存した歌詞を消す", "Delete saved lyrics"), {
                graph.lyrics.clearSaved()
                count = graph.lyrics.savedCount()
            }, enabled = count > 0)
        }
    }
}

@Composable
private fun DevicePane(graph: AppGraph) {
    var enabled by remember { mutableStateOf(graph.launcher.isEnabled()) }
    PaneTitle("ホームアプリ", L("再起動したあと、ダッシュボードを自動で前面に戻すための設定です。", "Settings to bring the dashboard back to the front automatically after a restart."))
    Notice(
        L("Android 10 以降はバックグラウンドからの画面起動が制限されるため、再起動後にダッシュボードが自動で前面に出るとは限りません。", "Since Android 10, apps can't start screens from the background, so the dashboard may not come to the front after a restart. ") +
            L("この端末のホームアプリを Dashboard にすると確実に前面へ戻ります。有効化したあと、端末側で「ホームアプリ」の選択ダイアログが出たら Dashboard を選んでください。", "Setting Dashboard as this device's home app reliably brings it back. After enabling, choose Dashboard when the device asks for a home app."),
    )
    ActionButton(if (enabled) L("ホームアプリ登録を解除", "Unregister as home app") else L("ホームアプリとして登録", "Register as home app"), {
        graph.settings.setLauncherEnabled(!enabled)
        enabled = graph.launcher.isEnabled()
    })
    StatusText(if (enabled) L("登録済み — 端末の既定ホームアプリに Dashboard を選べます", "Registered — you can choose Dashboard as the default home app") else L("未登録 — 再起動後は手動でアプリを開く必要があります", "Not registered — open the app manually after a restart"))
    Spacer(Modifier.height(22.dp))
    BatteryOptimization()
}

/**
 * 電池の最適化の対象から外す。対象のままだと、端末によっては常駐サービスや起動時の自動開始が止められる。
 * 許可のダイアログを持たない端末では、最適化の一覧画面を開いて手で外してもらう。
 */
@Composable
private fun BatteryOptimization() {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    fun check() = power?.isIgnoringBatteryOptimizations(context.packageName) == true
    var ignoring by remember { mutableStateOf(check()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ignoring = check() }

    Text(L("電池の最適化", "Battery optimization"), color = Wd.Text2, fontSize = 13.tu, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
    ActionButton(if (ignoring) L("除外済み", "Excluded") else L("電池の最適化から除外する", "Exclude from battery optimization"), {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(request) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }, enabled = !ignoring)
    Hint(L("メーカー独自の省電力機能（自動起動の管理など）は、端末の設定アプリで別に許可が必要なことがあります。", "Manufacturer-specific power saving (auto-start management, etc.) may need separate permission in the device's settings app."))
}

/** プリセットの作成・切り替え・名前の変更・削除（その場で保存。「全て保存」には含めない）。 */
@Composable
private fun PresetsPane(graph: AppGraph, config: Config, dirty: Boolean) {
    val presets = graph.presets.list(config)
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf(Wd.Text2) }
    var renaming by remember { mutableStateOf<app.dashboard.data.Preset?>(null) }
    var removing by remember { mutableStateOf<app.dashboard.data.Preset?>(null) }
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    val max = app.dashboard.data.MAX_PRESETS

    fun run(ok: String, block: () -> Unit) {
        status = L("処理中…", "Working…")
        statusColor = Wd.Text2
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(block) }
                .onSuccess { status = ok; statusColor = Wd.Green }
                .onFailure { status = (it as? SettingsController.SettingsException)?.message ?: L("エラー: ${it.message}", "Error: ${it.message}"); statusColor = Wd.Red }
        }
    }

    PaneTitle(
        L("プリセット", "Presets"),
        L(
            "カード・配色・テーマ・背景画像・通知などの設定を丸ごと、最大 $max 個まで持っておき、ダッシュボード右下のボタンからすぐ切り替えられます。" +
                "ほかの面で変えた設定は、使用中のプリセットに入ります。切り替えても変わらないのは、ブラウズのお気に入りだけです（LAN 公開と、この設定画面の PIN も端末の守りのため共通です）。",
            "Keep up to $max complete sets of settings (cards, colors, theme, background image, notifications…) and switch instantly with the button at the bottom right of the dashboard. " +
                "Changes made in other panes go into the preset in use. Only browser favorites stay the same across presets (LAN access and the settings PIN are also shared, for security).",
        ),
    )
    if (dirty) Notice(L("未保存の変更があります。切り替え・作成の前に「全て保存」を押してください（押さずに切り替えると変更は失われます）。", "You have unsaved changes. Press Save all before switching or creating (otherwise the changes are lost)."), Wd.Amber)
    presets.items.forEach { p ->
        val active = p.id == presets.active
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp))
                .background(if (active) accent.copy(alpha = 0.10f) else Wd.Surface2)
                .border(1.dp, if (active) accent.copy(alpha = 0.6f) else Wd.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(p.name, fontSize = 15.tu, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (active) {
                Text(L("使用中", "In use"), color = accent, fontSize = 12.tu, fontWeight = FontWeight.SemiBold)
            } else {
                ActionButton(L("切り替える", "Switch"), { run(L("「${p.name}」に切り替えました", "Switched to \"${p.name}\"")) { graph.presets.switchTo(p.id) } }, primary = true, enabled = !dirty)
            }
            ActionButton(L("名前を変更", "Rename"), { renaming = p })
            ActionButton(L("削除", "Delete"), { removing = p }, enabled = presets.items.size > 1 && !dirty, color = Wd.Red)
        }
    }
    Spacer(Modifier.height(8.dp))
    Field(
        L("新しいプリセット", "New preset"),
        L("いまの設定（背景画像を含む）を写して作り、そのプリセットに切り替えます。あとはほかの面で好きに変えてください。", "Creates a copy of the current settings (including the background image) and switches to it. Then change it in the other panes as you like."),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Input(name, { name = it.take(app.dashboard.PresetController.MAX_NAME) }, Modifier.weight(1f), placeholder = L("名前（空なら B・C…）", "Name (B, C… if empty)"))
            Spacer(Modifier.width(10.dp))
            ActionButton(L("作成", "Create"), {
                val n = name
                name = ""
                run(L("作成して切り替えました", "Created and switched")) { graph.presets.create(n) }
            }, primary = true, enabled = presets.items.size < max && !dirty)
        }
        Hint(L("${presets.items.size} / $max 個", "${presets.items.size} / $max"))
    }
    StatusText(status, statusColor)

    renaming?.let { p ->
        var value by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(L("名前を変更", "Rename")) },
            text = { Input(value, { value = it.take(app.dashboard.PresetController.MAX_NAME) }) },
            confirmButton = {
                TextButton(onClick = {
                    val v = value
                    renaming = null
                    run(L("名前を変更しました", "Renamed")) { graph.presets.rename(p.id, v) }
                }) { Text(L("変更", "Rename")) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(L("キャンセル", "Cancel")) } },
        )
    }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(L("「${p.name}」を削除", "Delete \"${p.name}\"")) },
            text = {
                Text(
                    if (p.id == presets.active) L("使用中のプリセットです。削除すると、残りの最初のプリセットに切り替わります。元に戻せません。", "This preset is in use. Deleting it switches to the first remaining preset. This can't be undone.")
                    else L("このプリセットの設定と背景画像を消します。元に戻せません。", "Deletes this preset's settings and background image. This can't be undone."),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    run(L("削除しました", "Deleted")) { graph.presets.delete(p.id) }
                }) { Text(L("削除", "Delete"), color = Wd.Red) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(L("キャンセル", "Cancel")) } },
        )
    }
}

/** この設定画面を開くときの PIN（既定はオフ）。 */
@Composable
private fun LockPane(graph: AppGraph, config: Config) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf(Wd.Text2) }
    val scope = rememberCoroutineScope()
    val on = config.settingsLock.enabled && config.settingsLock.pinHash != null
    val min = app.dashboard.server.Auth.MIN_SETTINGS_PIN_LENGTH

    fun report(ok: String, block: () -> Unit) {
        status = L("処理中…", "Working…")
        statusColor = Wd.Text2
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(block) }
                .onSuccess { status = ok; statusColor = Wd.Green }
                .onFailure { status = it.message ?: L("エラー", "Error"); statusColor = Wd.Red }
        }
    }

    PaneTitle(
        L("設定の PIN", "Settings PIN"),
        L(
            "ダッシュボードの「設定」を開くときに PIN を求めます。家族や来客に設定を変えられたくないときに使います。既定はオフです。" +
                "Web の設定画面（PC・LAN から）は「ネットワーク」の PIN で守られていて、これとは別です。",
            "Asks for a PIN when opening Settings from the dashboard, so others can't change your settings. Off by default. " +
                "The web settings (from a PC or LAN) are protected by the PIN under \"Network\", which is separate.",
        ),
    )
    StatusText(if (on) L("オン — 設定を開くたびに PIN を聞きます", "On — the PIN is asked each time Settings opens") else L("オフ", "Off"), if (on) Wd.Green else Wd.Text2)
    Spacer(Modifier.height(14.dp))
    Field(L(if (on) "PIN を変える" else "PIN を決めてオンにする", if (on) "Change the PIN" else "Set a PIN and turn on"), L("数字 $min 桁以上。忘れると設定を開けなくなるので、控えておいてください（アプリを入れ直すと設定ごと消えます）。", "$min or more digits. If you forget it you can't open Settings, so keep a note (reinstalling the app erases all settings).")) {
        Input(pin, { pin = it.filter(Char::isDigit).take(12) }, placeholder = L("新しい PIN", "New PIN"), password = true, number = true)
        Spacer(Modifier.height(8.dp))
        Input(again, { again = it.filter(Char::isDigit).take(12) }, placeholder = L("もう一度", "Again"), password = true, number = true)
        Spacer(Modifier.height(10.dp))
        ActionButton(if (on) L("PIN を変更", "Change PIN") else L("PIN を設定してオンにする", "Set PIN and turn on"), {
            val value = pin
            val confirm = again
            pin = ""
            again = ""
            when {
                value.length < min -> { status = L("PIN は $min 桁以上必要です", "The PIN must be at least $min digits"); statusColor = Wd.Red }
                value != confirm -> { status = L("2 つの PIN が違います", "The two PINs don't match"); statusColor = Wd.Red }
                else -> report(L("PIN を設定しました", "PIN set")) { graph.auth.setSettingsPin(value) }
            }
        }, primary = true)
    }
    if (on) ActionButton(L("PIN をオフにする", "Turn off the PIN"), { report(L("PIN をオフにしました", "PIN turned off")) { graph.auth.clearSettingsPin() } }, color = Wd.Red)
    StatusText(status, statusColor)
}

/** 情報（バージョン）。 */
@Composable
private fun InfoPane(onVersionArt: () -> Unit) {
    var taps by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    PaneTitle(L("情報", "About"), L("このアプリについて。", "About this app."))
    Field(L("バージョン", "Version"), L("Releases の APK は v1.2.3 の形、ソースコードから自分でビルドしたものは「それより前の最新のバージョン-コミットの名前」の形です。", "APKs from Releases show v1.2.3; builds from source show \"latest earlier version-commit name\".")) {
        Text(
            app.dashboard.BuildConfig.VERSION_LABEL,
            fontSize = 22.tu,
            fontWeight = FontWeight.SemiBold,
            style = app.dashboard.ui.common.Tabular,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    val now = System.currentTimeMillis()
                    taps = if (now - lastTap < 1500) taps + 1 else 1
                    lastTap = now
                    if (taps >= 5) {
                        taps = 0
                        onVersionArt()
                    }
                }
                .padding(vertical = 4.dp),
        )
    }
    Field(L("ライセンス", "License")) { Text("MIT License", color = Wd.Text2, fontSize = 14.tu) }
    Notice(
        L(
            "天気: Open-Meteo.com (CC BY 4.0) ・ 防災・雨雲: 気象庁 ・ 強震モニタ: 防災科学技術研究所 ・ ハムスター: Uiverse.io の Nawsome 作「Loader」(MIT)",
            "Weather: Open-Meteo.com (CC BY 4.0) · Alerts & rain: JMA · Seismic monitor: NIED · Hamster: \"Loader\" by Nawsome on Uiverse.io (MIT)",
        ),
    )
}

@Composable
private fun NetworkPane(graph: AppGraph, config: Config) {
    var pin by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf(Wd.Text2) }
    var confirm by remember { mutableStateOf(false) }
    val lanOn = config.lan.enabled
    val pinSet = config.lan.pinHash != null
    val url = graph.settings.lanSettingsUrl()
    val scope = rememberCoroutineScope()

    /** PIN のハッシュ（PBKDF2）は古い端末で 1 秒近くかかるので、画面のスレッドでは回さない。 */
    fun report(block: () -> Unit, ok: String) {
        status = L("処理中…", "Working…")
        statusColor = Wd.Text2
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(block) }
                .onSuccess { status = ok; statusColor = Wd.Green }
                .onFailure { status = (it as? SettingsController.SettingsException)?.message ?: L("エラー: ${it.message}", "Error: ${it.message}"); statusColor = Wd.Red }
        }
    }

    PaneTitle(L("ネットワーク（LAN 公開）", "Network (LAN access)"), L("既定ではこの端末の中と、USB でつないだ PC からしか設定画面を開けません。LAN の他の端末のブラウザからも開けるようにします。", "By default, settings can only be opened on this device and from a PC connected by USB. This lets browsers on other devices on your LAN open them too."))
    Notice(
        L("LAN 公開は家庭内の信頼できる LAN 限定の機能です。通信は平文 HTTP のため、PIN やセッションは同じネットワーク上で盗聴され得ます。", "LAN access is meant only for a trusted home network. Traffic is plain HTTP, so the PIN and sessions could be intercepted on the same network. ") +
            L("ゲスト Wi-Fi、社内共有 LAN、不特定多数が接続するネットワークでは有効にしないでください。", "Don't enable it on guest Wi-Fi, shared office networks or any network open to strangers."),
        Wd.Amber,
    )
    Field(L("PIN（6 桁以上）", "PIN (6+ digits)"), L("LAN 公開を有効にする前に設定が必要です。変えると、他の端末のログインはすべて無効になります。", "Required before enabling LAN access. Changing it signs out all other devices.")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Input(pin, { pin = it }, Modifier.weight(1f), placeholder = if (pinSet) L("設定済み（変更する場合のみ入力）", "Set (enter only to change)") else L("新しい PIN", "New PIN"), password = true)
            Spacer(Modifier.width(10.dp))
            ActionButton(L("PIN を設定", "Set PIN"), {
                // 入力欄はすぐ空にするので、別スレッドで読む前に値を取っておく
                val value = pin
                report({ graph.settings.setPin(value) }, L("PIN を設定しました（既存のログインは無効化されます）", "PIN set (existing sign-ins are revoked)"))
                pin = ""
            })
        }
    }
    ActionButton(if (lanOn) L("LAN 公開を無効にする", "Disable LAN access") else L("LAN 公開を有効にする", "Enable LAN access"), {
        if (lanOn) report({ graph.settings.setLanEnabled(false) }, L("LAN 公開を無効にしました", "LAN access disabled")) else confirm = true
    }, primary = !lanOn)
    StatusText(
        when {
            lanOn && url != null -> L("公開中 — 他の端末のブラウザで $url を開き、PIN でログインしてください", "Shared — open $url in a browser on another device and sign in with the PIN")
            lanOn -> L("公開中 — Wi-Fi の IP アドレスを取得できません", "Shared — can't get the Wi-Fi IP address")
            pinSet -> L("この端末と USB の PC からだけ開けます（PIN 設定済み）", "Only this device and a USB-connected PC can open it (PIN set)")
            else -> L("この端末と USB の PC からだけ開けます（PIN 未設定）", "Only this device and a USB-connected PC can open it (no PIN)")
        },
        if (lanOn) Wd.Green else Wd.Text2,
    )
    StatusText(status, statusColor)

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(L("LAN 公開を有効にします", "Enable LAN access")) },
            text = { Text(L("信頼できる家庭内 LAN でのみ使用してください。続けますか？", "Use only on a trusted home LAN. Continue?")) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    report({ graph.settings.setLanEnabled(true) }, L("LAN 公開を有効にしました", "LAN access enabled"))
                }) { Text(L("有効にする", "Enable")) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(L("キャンセル", "Cancel")) } },
        )
    }
}
