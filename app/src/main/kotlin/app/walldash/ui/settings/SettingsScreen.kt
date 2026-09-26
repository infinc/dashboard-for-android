package app.walldash.ui.settings

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
import app.walldash.AppGraph
import app.walldash.SettingsController
import app.walldash.data.Accents
import app.walldash.data.CardLayout
import app.walldash.data.CalendarPatch
import app.walldash.data.Config
import app.walldash.data.Countdown
import app.walldash.data.CountdownConfig
import app.walldash.data.ConfigPatch
import app.walldash.data.DEFAULT_WEATHER_FIELDS
import app.walldash.data.DisasterConfig
import app.walldash.data.DisplayConfig
import app.walldash.data.FeedConfig
import app.walldash.data.GeocodeResult
import app.walldash.data.LocationConfig
import app.walldash.data.MemoPatch
import app.walldash.data.NotificationConfig
import app.walldash.data.SaveAllRequest
import app.walldash.data.SpotifyPatch
import app.walldash.data.StockSymbol
import app.walldash.data.StocksConfig
import app.walldash.data.TrainPatch
import app.walldash.data.Tones
import app.walldash.data.UnitsConfig
import app.walldash.server.DashboardServer
import app.walldash.ui.theme.LocalAccent
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.tu
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
        spotify = SpotifyPatch(enabled = spotifyEnabled, clientId = spotifyClientId.trim()),
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
        )

        /** 「^N225 日経平均」の行を銘柄にする。名前を省いたら記号をそのまま名前にする。 */
        fun parseStocks(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val symbol = line.substringBefore(' ').substringBefore('\t')
            StockSymbol(symbol, line.removePrefix(symbol).trim().ifEmpty { symbol })
        }
    }
}

private enum class Pane(val label: String, val group: String, val card: ((DisplayConfig) -> Boolean)? = null) {
    Palette("配色", "全体"),
    Theme("テーマ", "全体"),
    Screen("画面の明るさ", "全体"),
    Place("場所", "全体"),
    Notify("通知", "全体"),
    Clock("時刻", "カード", { it.showClock }),
    Weather("天気", "カード", { it.showWeather }),
    Disaster("防災", "カード", { it.showDisaster }),
    Memo("LINE メモ", "カード", { it.showMemo }),
    Hourly("時間別予報", "カード", { it.showHourly }),
    Spotify("Spotify", "カード", { it.showSpotify }),
    Wifi("Wi-Fi", "カード", { it.showWifi }),
    Stats("端末状態", "カード", { it.showDeviceStats }),
    News("ニュース", "カード", { it.showFeed }),
    Daily("週間予報", "カード", { it.showDaily }),
    Timer("タイマー", "カード", { it.showTimer }),
    Word("今日の単語", "カード", { it.showWord }),
    AnalogClock("アナログ時計", "カード", { it.showAnalogClock }),
    Calendar("予定表", "カード", { it.showCalendar }),
    Train("運行情報", "カード", { it.showTrain }),
    Radar("雨雲レーダー", "カード", { it.showRadar }),
    SunMoon("日の出・月", "カード", { it.showSunMoon }),
    Countdown("カウントダウン", "カード", { it.showCountdown }),
    Today("今日は何の日", "カード", { it.showToday }),
    Stocks("株価", "カード", { it.showStocks }),
    Hamster("ハムスター", "カード", { it.showHamster }),
    Device("ホームアプリ", "端末"),
    Network("ネットワーク", "端末"),
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
    var pane by remember { mutableStateOf(Pane.Palette) }
    var saveStatus by remember { mutableStateOf("") }
    var confirmClose by remember { mutableStateOf(false) }
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

    /** 下書きを書き換える。カードを増やして画面に収まらなくなる変更は、理由を出して取りやめる。 */
    fun update(next: Draft) {
        val message = CardLayout.overflowMessage(draft.display, next.display, CardLayout.area(context))
        if (message != null) {
            blocked = message
            return
        }
        draft = next
        saveStatus = ""
    }

    fun save(then: () -> Unit = {}) {
        saveStatus = "保存中…"
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { graph.settings.saveAll(draft.toRequest()) } }
                .onSuccess { saved ->
                    base = Draft.of(saved)
                    draft = base
                    saveStatus = "保存しました"
                    then()
                }
                .onFailure {
                    val message = (it as? SettingsController.SettingsException)?.message ?: "エラー: ${it.message}"
                    saveStatus = message
                    blocked = message
                }
        }
    }

    fun requestClose() {
        if (dirty) confirmClose = true else onClose()
    }

    BackHandler { requestClose() }

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
                Text("設定", fontSize = 16.tu, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                ActionButton("閉じる", ::requestClose)
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
                    PaneContent(pane, graph, config, draft, ::update, onOpenBrowser)
                }
            }
        }
    }

    blocked?.let { message ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            title = { Text("カードを増やせません") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { blocked = null }) { Text("OK") } },
        )
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("未保存の変更があります。") },
            text = { Text("保存せずに閉じると、変更した内容は失われます。") },
            confirmButton = { TextButton(onClick = { confirmClose = false; save(onClose) }) { Text("保存して閉じる") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmClose = false }) { Text("キャンセル") }
                    TextButton(onClick = { confirmClose = false; onClose() }) { Text("保存せずに閉じる", color = Wd.Red) }
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
            ActionButton(if (dirty) "全て保存" else "変更はありません", onSave, Modifier.fillMaxWidth(), primary = true, enabled = dirty)
            StatusText(if (dirty) "未保存の変更があります" else status, if (dirty) Wd.Amber else Wd.Green)
            Spacer(Modifier.height(8.dp))
            ActionButton("今すぐ全データを取得し直す", onRefresh, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PaneContent(pane: Pane, graph: AppGraph, config: Config, d: Draft, set: (Draft) -> Unit, onOpenBrowser: (String) -> Unit) {
    val disp = d.display
    fun display(block: DisplayConfig.() -> DisplayConfig) = set(d.copy(display = disp.block()))

    @Composable
    fun CardSwitch(on: Boolean, update: DisplayConfig.(Boolean) -> DisplayConfig) {
        Field { SwitchRow("このカードをダッシュボードに表示する", on, { display { update(it) } }) }
    }

    when (pane) {
        Pane.Palette -> {
            PaneTitle("配色", "ダッシュボード全体の色を決めます。グラフの線や強調の色がこの色になります。")
            Field("アクセント色") {
                ColorSwatches(Accents.ALL.map { it.hex to it.label }, disp.accent) { display { copy(accent = it) } }
            }
            Notice("カードを非表示にしても空白は残りません。空いた列は同じ行に残ったカードへ自動で配分され、1 行が常に画面幅いっぱいになります。")
            Notice("カードを表示しすぎて画面に収まらなくなるときは、そのカードは表示できません（理由をお知らせします）。先にほかのカードを非表示にしてください。縦向きの画面は縦にスクロールするので、この制限はありません。")
        }

        Pane.Theme -> ThemePane(graph, config, d, set)

        Pane.Screen -> {
            PaneTitle("画面の明るさ", "壁掛けでは端末の自動輝度が明滅するため、明るさはアプリ側で固定します。")
            PercentSlider("通常時の明るさ", (disp.normalBrightness * 100).roundToInt(), 10, 100, 5, { display { copy(normalBrightness = it / 100.0) } }, "操作があるときの画面の明るさです。")
            Field(hint = "画面に触れると即座に元の明るさへ戻ります。バックライト自体を下げるので、消費電力と焼き付きの両方に効きます。") {
                SwitchRow("無操作が続いたら画面を暗くする", disp.idleDimEnabled, { display { copy(idleDimEnabled = it) } })
            }
            Field("暗くするまでの時間（秒）", "30〜3600 秒") {
                Input(disp.idleDimAfterSeconds.toString(), { v -> v.filter(Char::isDigit).toIntOrNull()?.let { display { copy(idleDimAfterSeconds = it) } } }, number = true)
            }
            PercentSlider("暗くしたときの明るさ", (disp.idleDimBrightness * 100).roundToInt(), 5, 100, 5, { display { copy(idleDimBrightness = it / 100.0) } })
            Field(hint = "同じ位置に同じ絵を長時間映し続けると形が薄く残ることがあるため、5 分ごとに画面全体を最大 2 ピクセルだけ動かします。") {
                SwitchRow("焼き付き防止のシフト", disp.burnInShiftEnabled, { display { copy(burnInShiftEnabled = it) } })
            }
        }

        Pane.Place -> PlacePane(graph, config, d, set)

        Pane.Notify -> {
            PaneTitle("通知", "ダッシュボードが鳴らす音の設定です。音を切っても、画面のバナー表示は出ます。")
            val n = d.notifications
            fun notify(block: NotificationConfig.() -> NotificationConfig) = set(d.copy(notifications = n.block()))
            val tones = Tones.ALL.map { it.id to it.label }
            ToneField("防災情報", "警報・注意報・地震・津波・台風・噴火のいずれかが切り替わったときに 1 回だけ鳴ります。", n.disasterSound, { notify { copy(disasterSound = it) } }, tones, n.disasterTone, { notify { copy(disasterTone = it) } }) {
                graph.notices.play(n.disasterTone, Tones.DEFAULT_DISASTER, volume = n.volume)
            }
            ToneField("充電ケーブルの抜き差し", "挿したときはそのまま、抜いたときは音の高さを逆にして鳴らします。", n.chargingSound, { notify { copy(chargingSound = it) } }, tones, n.chargingTone, { notify { copy(chargingTone = it) } }) {
                graph.notices.play(n.chargingTone, Tones.DEFAULT_CHARGING, volume = n.volume)
            }
            ToneField("タイマー", "タイマーの鳴動は切れません（自分で時間を決めて鳴らすもののため）。", null, {}, tones, n.timerTone, { notify { copy(timerTone = it) } }) {
                graph.notices.play(n.timerTone, Tones.DEFAULT_TIMER, volume = n.volume)
            }
            PercentSlider(
                "通知音の音量", (n.volume * 100).roundToInt(), 0, 100, 5, { notify { copy(volume = it / 100.0) } },
                "鳴らす直前に端末のメディア音量をこの大きさまで動かし、鳴り終わったら元に戻します。0% にすると鳴りません。",
            )
            Notice("ブラウズで動画を見ている最中に通知が鳴ると、その 1〜2 秒だけ動画の音量も一緒に変わります（端末の音量そのものを動かしているため）。")
        }

        Pane.Clock -> {
            PaneTitle("時刻", "大きな時計と日付を出すカードです。")
            CardSwitch(disp.showClock) { copy(showClock = it) }
            Field("時刻表示") {
                Select(listOf(true to "24 時間", false to "12 時間（AM/PM）"), d.units.clock24h, { set(d.copy(units = d.units.copy(clock24h = it))) })
            }
            Field("揃え") {
                Select(listOf("left" to "左", "center" to "中央", "right" to "右"), disp.clockAlign, { display { copy(clockAlign = it) } })
            }
            Field("日付の書き方") {
                Select(listOf("ja" to "2026年9月21日 (月)", "slash" to "2026/09/21 (月)"), disp.clockDateFormat, { display { copy(clockDateFormat = it) } })
            }
            Field { SwitchRow("秒を表示する", d.units.showSeconds, { set(d.copy(units = d.units.copy(showSeconds = it))) }) }
        }

        Pane.Weather -> {
            PaneTitle("天気", "いまの天気と、体感・降水・風などの値を出すカードです。地点は「場所」で決めます。")
            CardSwitch(disp.showWeather) { copy(showWeather = it) }
            Field("気温の単位") {
                Select(listOf("c" to "摂氏（℃）", "f" to "華氏（℉）"), d.units.temperature, { set(d.copy(units = d.units.copy(temperature = it))) })
            }
            Field("風速の単位") {
                Select(listOf("kmh" to "km/h", "ms" to "m/s"), d.units.wind, { set(d.copy(units = d.units.copy(wind = it))) })
            }
            Field("出す項目", "3 列で左上から詰めて並べます（9 項目で 3 行）。") {
                val labels = mapOf(
                    "apparent" to "体感温度", "pm25" to "PM2.5", "pop" to "降水確率", "rain" to "降水量", "humidity" to "湿度",
                    "wind" to "風", "uv" to "UV 指数", "aqi" to "大気質（AQI）", "visibility" to "視界",
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
            PaneTitle("防災（気象庁）", "警報・注意報、地震、津波、台風、噴火を出すカードです。右側には強震モニタを重ねます。")
            CardSwitch(disp.showDisaster) { copy(showDisaster = it) }
            Field(hint = "切ると通信自体を止めます。カードを隠すだけなら上のスイッチを切ってください。") {
                SwitchRow("防災情報を取得する", d.disaster.enabled, { set(d.copy(disaster = d.disaster.copy(enabled = it))) })
            }
            Field("警報・注意報の地域", "「場所」で選んだ天気の地点がある市町村の警報・注意報を表示します。変えるときは「場所」を変更してください。") {
                val s = graph.disaster.state
                val text = when {
                    !config.disaster.enabled -> "防災情報の取得が無効です"
                    d.location != config.location -> "「場所」の変更を保存すると、その地点の市町村に変わります"
                    !s.available -> "まだ取得できていません"
                    s.areaName == null -> "天気の地点から市町村を決められません。「場所」で国内の地点を選んでください"
                    else -> listOfNotNull(s.officeName, s.areaName).joinToString(" ")
                }
                Text(text, fontSize = 14.tu)
            }
            Field("表示する地震の最大震度", "この震度以上で直近の 1 件だけを表示します。") {
                Select(listOf("1" to "震度1以上", "3" to "震度3以上", "4" to "震度4以上", "5-" to "震度5弱以上"), d.disaster.minIntensity, { set(d.copy(disaster = d.disaster.copy(minIntensity = it))) })
            }
            Field("カードに出す情報", "津波と警報・注意報は命に関わるため、常に表示します。強震モニタを外すと本文が横いっぱいに広がり、3 秒ごとの画像取得も止まります。") {
                SwitchRow("台風情報", disp.disasterShowTyphoon, { display { copy(disasterShowTyphoon = it) } })
                SwitchRow("噴火情報", disp.disasterShowVolcano, { display { copy(disasterShowVolcano = it) } })
                SwitchRow("強震モニタ", disp.disasterShowKmoni, { display { copy(disasterShowKmoni = it) } })
            }
            Notice("警報・注意報は気象庁の警報ページと同じ名前で表示します（例: レベル４土砂災害危険警報）。市町村は、気象庁のページの「現在地」と同じ方法で、天気の地点の緯度経度から決めています。")
        }

        Pane.Memo -> {
            PaneTitle("LINE メモ", "LINE Bot に送ったメッセージを壁に並べるカードです。中継（Cloudflare Worker）の作り方は worker/README.md を見てください。")
            CardSwitch(disp.showMemo) { copy(showMemo = it) }
            Field { SwitchRow("メモを取得する", d.memoEnabled, { set(d.copy(memoEnabled = it)) }) }
            Field("中継先の URL", "Worker の URL を貼れば、末尾は自動で /memo に直します。") {
                Input(d.memoEndpoint, { set(d.copy(memoEndpoint = it)) }, placeholder = "https://walldash-line-relay.xxxx.workers.dev")
            }
            Field("端末トークン", "Worker の DEVICE_TOKEN と同じ値。保存済みの値は表示しません。") {
                Input(d.memoToken, { set(d.copy(memoToken = it)) }, placeholder = if (config.memo.token.isNullOrBlank()) "未設定" else "設定済み（変更する場合のみ入力）", password = true)
            }
            Field("取得の間隔（秒）", "10〜600 秒") {
                Input(d.memoIntervalSec, { set(d.copy(memoIntervalSec = it.filter(Char::isDigit))) }, number = true)
            }
        }

        Pane.Hourly -> {
            PaneTitle("時間別予報", "これから 12 時間の気温と降水確率のグラフです。")
            CardSwitch(disp.showHourly) { copy(showHourly = it) }
            Field("描くもの") {
                Select(listOf("both" to "気温と降水確率", "temp" to "気温だけ", "precip" to "降水確率だけ"), disp.hourlyMode, { display { copy(hourlyMode = it) } })
            }
        }

        Pane.Spotify -> SpotifyPane(graph, config, d, set, onOpenBrowser)

        Pane.Wifi -> {
            PaneTitle("Wi-Fi", "Wi-Fi のリンク速度・電波の強さ・通信量を出すカードです。")
            CardSwitch(disp.showWifi) { copy(showWifi = it) }
            Field { SwitchRow("回る地球を出す", disp.wifiShowGlobe, { display { copy(wifiShowGlobe = it) } }) }
        }

        Pane.Stats -> {
            PaneTitle("端末状態", "CPU・電池・温度・ストレージ・メモリを出すカードです。")
            CardSwitch(disp.showDeviceStats) { copy(showDeviceStats = it) }
        }

        Pane.News -> {
            PaneTitle("ニュース / RSS", "登録した RSS / Atom の見出しを出すカードです。サイトのトップページの URL でも、フィードを自動で探します。")
            CardSwitch(disp.showFeed) { copy(showFeed = it) }
            Field { SwitchRow("ニュースを取得する", d.feedEnabled, { set(d.copy(feedEnabled = it)) }) }
            Field("フィードの URL（1 行に 1 つ、最大 5 本）") {
                Input(d.feedUrls, { set(d.copy(feedUrls = it)) }, placeholder = "https://example.com/rss.xml", singleLine = false, minLines = 3)
            }
            Field("表示する件数（1〜40）") {
                Input(d.feedMax, { set(d.copy(feedMax = it.filter(Char::isDigit))) }, number = true)
            }
        }

        Pane.Daily -> {
            PaneTitle("週間予報", "7 日間の天気・気温の範囲・降水を出すカードです。")
            CardSwitch(disp.showDaily) { copy(showDaily = it) }
        }

        Pane.Timer -> {
            PaneTitle("タイマー", "時・分をドラムで選んで開始する簡易タイマーです。鳴る音は「通知」で選べます。")
            CardSwitch(disp.showTimer) { copy(showTimer = it) }
        }

        Pane.Word -> {
            PaneTitle("今日の単語", "英単語（英検準1級程度）を 1 時間に 1 語ずつ出すカードです。")
            CardSwitch(disp.showWord) { copy(showWord = it) }
        }

        Pane.AnalogClock -> {
            PaneTitle("アナログ時計", "1/5 秒刻みの細かい目盛りと日付窓のある、アナログの時計です。")
            CardSwitch(disp.showAnalogClock) { copy(showAnalogClock = it) }
            Field(hint = "切ると秒針が 1 秒ごとに刻みます。描き直しが 1 秒に 1 回になるので、古い端末で動きが重いときに切ってください。") {
                SwitchRow("秒針をなめらかに動かす", disp.analogSweep, { display { copy(analogSweep = it) } })
            }
            Field { SwitchRow("文字盤に数字を入れる", disp.analogNumerals, { display { copy(analogNumerals = it) } }) }
        }

        Pane.Calendar -> CalendarPane(graph, config, d, set)
        Pane.Train -> TrainPane(graph, config, d, set)

        Pane.Radar -> {
            PaneTitle("雨雲レーダー", "気象庁の雨雲の動き（5 分ごと）を、国土地理院の地図に重ねて出すカードです。中心は「場所」で選んだ地点です。")
            CardSwitch(disp.showRadar) { copy(showRadar = it) }
            Field("範囲") {
                Select(listOf(6 to "広域（関東全体くらい）", 8 to "地方（県くらい）", 10 to "周辺（市くらい）"), disp.radarZoom, { display { copy(radarZoom = it) } })
            }
            Notice("カードを表示している間だけ、5 分ごとに地図と雨雲の画像（合わせて 30 枚ほど、1 枚数 KB）を取得します。日本国内の地点だけ雨雲が出ます。")
        }

        Pane.SunMoon -> {
            PaneTitle("日の出・日の入り ／ 月", "今日の日の出・日の入りと太陽の位置、いまの月の満ち欠け・月齢・次の満月と新月を出すカードです。")
            CardSwitch(disp.showSunMoon) { copy(showSunMoon = it) }
            Notice("日の出・日の入りは天気と同じ Open-Meteo の値（「場所」の地点）です。月の満ち欠けは端末の中で計算するので、通信は増えません。")
        }

        Pane.Countdown -> {
            PaneTitle("カウントダウン", "決めた日までの残りの日数を、近い順に並べるカードです。1 日を切ると時・分・秒で数えます。")
            CardSwitch(disp.showCountdown) { copy(showCountdown = it) }
            Field("数える行事", "「次の祝日」「次の休日」は内閣府の祝日の一覧（週に 1 回取得）を使います。") {
                CountdownLabels.forEach { (key, label) ->
                    CheckRow(label, key in d.countdownBuiltins, { on ->
                        set(d.copy(countdownBuiltins = CountdownLabels.keys.filter { if (it == key) on else it in d.countdownBuiltins }))
                    })
                }
            }
            Field("自分で決める日（1 行に 1 つ）", "「名前 日付」の形で書きます。日付は 2027-03-18（その日だけ）か 03-18（毎年）。後ろに 18:30 のように時刻も付けられます（最大 10 行）。") {
                Input(d.countdownText, { set(d.copy(countdownText = it)) }, placeholder = "卒業 2027-03-18\n誕生日 05-04", singleLine = false, minLines = 3)
            }
        }

        Pane.Today -> {
            PaneTitle("今日は何の日", "今日の記念日・年中行事を出すカードです。日本語版 Wikipedia の日付の記事（例:「9月26日」）から、1 日 1 回取得します。")
            CardSwitch(disp.showToday) { copy(showToday = it) }
            Field(hint = "1 時間ごとに別のできごとに入れ替わります。") {
                SwitchRow("過去の今日のできごとを 1 件添える", disp.todayShowEvent, { display { copy(todayShowEvent = it) } })
            }
            Notice("記事は Wikipedia の執筆者によるもので、CC BY-SA 4.0 で公開されています（フッターに出典を出します）。")
        }

        Pane.Stocks -> {
            PaneTitle("株価", "主要な株価指数や為替の値と、簡単なチャートを出すカードです。10 分ごとに取得します。")
            CardSwitch(disp.showStocks) { copy(showStocks = it) }
            Field("銘柄（1 行に 1 つ、最大 6 つ）", "「記号 表示名」の形で書きます。記号は Yahoo Finance の表記です（例: ^N225 日経平均 / ^DJI NY ダウ / ^GSPC S&P 500 / 7203.T トヨタ / USDJPY=X ドル円）。") {
                Input(d.stocksText, { set(d.copy(stocksText = it)) }, placeholder = "^N225 日経平均", singleLine = false, minLines = 4)
            }
            Field("チャートの期間") {
                Select(listOf("1d" to "1 日（5 分足）", "5d" to "5 日", "1mo" to "1 か月", "6mo" to "6 か月", "1y" to "1 年"), d.stocksRange, { set(d.copy(stocksRange = it)) })
            }
            Notice("値は Yahoo Finance の公開されていない API から取っています。数十分の遅れがあり、予告なく取れなくなることがあります。投資の判断には使わないでください。", Wd.Amber)
        }

        Pane.Hamster -> {
            PaneTitle("ハムスター", "画面下で回し車を走るハムスターです。意匠は Uiverse.io の Nawsome 作「Loader」（MIT License）によります。")
            Field { SwitchRow("ハムスターを出す", disp.showHamster, { display { copy(showHamster = it) } }) }
        }

        Pane.Device -> DevicePane(graph)
        Pane.Network -> NetworkPane(graph, config)
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
        status = "処理中…"
        statusColor = Wd.Text2
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(block) }
                .onSuccess { status = ok; statusColor = Wd.Green }
                .onFailure { status = (it as? SettingsController.SettingsException)?.message ?: "エラー: ${it.message}"; statusColor = Wd.Red }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        report("背景画像を設定しました") {
            graph.settings.setWallpaper { context.contentResolver.openInputStream(uri) ?: error("画像を開けません") }
        }
    }

    PaneTitle("テーマ", "ダッシュボード・設定・ブラウズの色の基調と、ダッシュボードの背景を決めます。")
    Field("色の基調", "ダークは暗い部屋で眩しくならない配色、ホワイトは明るい部屋で読みやすい配色です。") {
        Select(listOf("dark" to "ダーク", "light" to "ホワイト"), disp.theme, { set(d.copy(display = disp.copy(theme = it))) })
    }
    Field("背景画像", "端末の写真から選びます。選んだ画像は画面の大きさに縮めて保存し、選んだその場で反映します（「全て保存」は要りません）。") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(if (hasImage) "画像を選び直す" else "画像を選ぶ", {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            ActionButton("背景画像を外す", { report("背景画像を外しました") { graph.settings.clearWallpaper() } }, enabled = hasImage)
        }
        StatusText(if (status.isNotEmpty()) status else if (hasImage) "背景画像を表示中" else "背景画像なし", if (status.isNotEmpty()) statusColor else Wd.Text2)
    }
    PercentSlider(
        "カードの不透明度", (disp.cardOpacity * 100).roundToInt(), 20, 100, 5, { set(d.copy(display = disp.copy(cardOpacity = it / 100.0))) },
        "背景画像があるときだけ効きます。小さいほどカードが透けて背景が見えます（100% で透けません）。",
    )
}

private val CountdownLabels = app.walldash.data.Countdown.BUILTIN_LABELS

@Composable
private fun CalendarPane(graph: AppGraph, config: Config, d: Draft, set: (Draft) -> Unit) {
    val disp = d.display
    val c = config.calendar
    val s = graph.calendar.state
    PaneTitle("予定表（iCloud カレンダー）", "iCloud のカレンダーの予定を、今日から決めた日数ぶん並べるカードです。15 分ごとに取得します。")
    Field { SwitchRow("このカードをダッシュボードに表示する", disp.showCalendar, { set(d.copy(display = disp.copy(showCalendar = it))) }) }
    Field { SwitchRow("予定を取得する", d.calendarEnabled, { set(d.copy(calendarEnabled = it)) }) }
    Field("つなぎ方") {
        Select(listOf("caldav" to "Apple ID で接続（すべてのカレンダー）", "ics" to "共有カレンダーの公開 URL"), d.calendarMode, { set(d.copy(calendarMode = it)) })
    }
    if (d.calendarMode == "ics") {
        Field("公開 URL", "iPhone の「カレンダー」→ カレンダーの (i) →「公開カレンダー」を ON にして出る webcal:// の URL。URL を知っている人は誰でも予定を読めるので、保存済みの値は表示しません。") {
            Input(d.calendarIcsUrl, { set(d.copy(calendarIcsUrl = it)) }, placeholder = if (c.icsUrl.isNullOrBlank()) "webcal://p00-caldav.icloud.com/published/2/…" else "設定済み（変更する場合のみ入力）", password = true)
        }
    } else {
        Field("Apple ID") {
            Input(d.calendarAppleId, { set(d.copy(calendarAppleId = it)) }, placeholder = "例: name@icloud.com")
        }
        Field("App 用パスワード", "Apple ID のふだんのパスワードでは接続できません。account.apple.com →「サインインとセキュリティ」→「App 用パスワード」で作った 16 文字を入れてください（いつでも取り消せます）。保存済みの値は表示しません。") {
            Input(d.calendarPassword, { set(d.copy(calendarPassword = it)) }, placeholder = if (c.password.isNullOrBlank()) "xxxx-xxxx-xxxx-xxxx" else "設定済み（変更する場合のみ入力）", password = true)
        }
    }
    Field("何日先まで出すか（1〜31 日）") {
        Input(d.calendarDays, { set(d.copy(calendarDays = it.filter(Char::isDigit))) }, number = true)
    }
    StatusText(
        when {
            !c.enabled -> "取得は無効です"
            s.lastError != null -> "エラー: ${s.lastError}"
            s.fetchedAt > 0 -> "取得できています（${s.events.size} 件）"
            else -> "まだ取得していません —「全て保存」のあと少し待ってください"
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
    PaneTitle("運行情報（公共交通オープンデータ）", "電車の遅れ・運転見合わせを出すカードです。公共交通オープンデータセンター（ODPT）の API から 5 分ごとに取得します。")
    Field { SwitchRow("このカードをダッシュボードに表示する", disp.showTrain, { set(d.copy(display = disp.copy(showTrain = it))) }) }
    Field { SwitchRow("運行情報を取得する", d.trainEnabled, { set(d.copy(trainEnabled = it)) }) }
    Field("アクセストークン（本番）", "developer.odpt.org で利用者登録（無料）をするともらえます。東京メトロ・都営地下鉄・私鉄各社の運行情報が取れます。保存済みの値は表示しません。") {
        Input(d.trainToken, { set(d.copy(trainToken = it)) }, placeholder = if (t.token.isNullOrBlank()) "未設定" else "設定済み（変更する場合のみ入力）", password = true)
    }
    Field("アクセストークン（チャレンジ）", "JR 東日本などは「公共交通オープンデータチャレンジ」用の API にだけ載っています。チャレンジに参加登録するともらえる別のトークンです（期間限定）。無くても動きます。") {
        Input(d.trainChallengeToken, { set(d.copy(trainChallengeToken = it)) }, placeholder = if (t.challengeToken.isNullOrBlank()) "未設定" else "設定済み（変更する場合のみ入力）", password = true)
    }
    Field("表示する路線（最大 12）", "選ばないと、遅れや運転見合わせが出ている路線だけを出します（すべて平常なら「すべて平常運転」）。一覧はトークンを保存してから読み込めます。") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton("路線の一覧を読み込む", {
                status = "読み込み中…"
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { graph.train.reloadCatalog() } }
                    choices = graph.train.railwayChoices()
                    status = r.fold({ "${choices.size} 路線を読み込みました" }, { "エラー: ${it.message}" })
                }
            }, enabled = !t.token.isNullOrBlank() || !t.challengeToken.isNullOrBlank())
            Spacer(Modifier.width(10.dp))
            if (d.trainRailways.isNotEmpty()) ActionButton("選択をすべて外す", { set(d.copy(trainRailways = emptyList())) })
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
            !t.enabled -> "取得は無効です"
            s.lastError != null && s.fetchedAt == 0L -> "エラー: ${s.lastError}"
            s.fetchedAt > 0 -> "取得できています" + (s.lastError?.let { "（一部エラー: $it）" } ?: "")
            else -> "まだ取得していません —「全て保存」のあと少し待ってください"
        },
        if (s.lastError != null && t.enabled) Wd.Amber else Wd.Text2,
    )
    Spacer(Modifier.height(18.dp))
    Notice("データは公共交通オープンデータ協議会が「公共交通オープンデータ基本ライセンス」で提供しているもので、正確さは保証されていません。急ぐときは各事業者の公式の案内も確かめてください。")
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
    onPreview: () -> Unit,
) {
    Field(title, hint) {
        if (enabled != null) SwitchRow("鳴らす", enabled, onEnabled)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Select(tones, tone, onTone, Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            ActionButton("試聴", onPreview)
        }
    }
}

@Composable
private fun PlacePane(graph: AppGraph, config: Config, d: Draft, set: (Draft) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    PaneTitle("場所", "天気・時間別予報・週間予報・日の出入り、そして防災の警報・注意報が、ここで決めた地点のものになります。")
    Field("都市名で検索", "Open-Meteo の地名検索はローマ字か英語で入力してください（「札幌」では一致しません）。結果は日本語で返ります。") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Input(query, { query = it }, Modifier.weight(1f), placeholder = "例: Sapporo / Tokyo")
            Spacer(Modifier.width(10.dp))
            ActionButton("検索", {
                val q = query.trim()
                if (q.isEmpty()) return@ActionButton
                status = "検索中…"
                scope.launch {
                    val found = withContext(Dispatchers.IO) { runCatching { graph.weather.geocode(q) } }
                    results = found.getOrDefault(emptyList())
                    status = found.fold(
                        { if (it.isEmpty()) "見つかりませんでした。ローマ字か英語で入力してください（例: Sapporo）" else "" },
                        { "エラー: ${it.message}" },
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
                        set(d.copy(location = LocationConfig(true, r.name, r.latitude, r.longitude, r.timezone)))
                        results = emptyList()
                        query = ""
                    }.padding(12.dp),
            )
        }
    }
    Text("現在の設定地点: ${config.location.name}（${config.location.timezone}）", color = Wd.Text2, fontSize = 13.tu)
    if (d.location != config.location) {
        StatusText("選択中: ${d.location.name} — 「全て保存」で反映されます", Wd.Amber)
    }
}

@Composable
private fun SpotifyPane(graph: AppGraph, config: Config, d: Draft, set: (Draft) -> Unit, onOpenBrowser: (String) -> Unit) {
    val disp = d.display
    val connected = !config.spotify.refreshToken.isNullOrBlank()
    val savedId = config.spotify.clientId.isNotBlank() && d.spotifyClientId.trim() == config.spotify.clientId
    PaneTitle("Spotify", "再生中の曲のジャケットと曲名を出し、再生・一時停止・曲送りができるカードです。")
    Field { SwitchRow("このカードをダッシュボードに表示する", disp.showSpotify, { set(d.copy(display = disp.copy(showSpotify = it))) }) }
    Field { SwitchRow("再生中の曲を取得する", d.spotifyEnabled, { set(d.copy(spotifyEnabled = it)) }) }
    Field("カードに出すもの", "どちらも外すと、空いた高さをジャケット・曲名・アーティスト名に回して大きく表示します。") {
        SwitchRow("再生・曲送りのボタン", disp.spotifyShowControls, { set(d.copy(display = disp.copy(spotifyShowControls = it))) })
        SwitchRow("再生時間", disp.spotifyShowProgress, { set(d.copy(display = disp.copy(spotifyShowProgress = it))) })
    }
    Field("Client ID", "秘密の値ではありません。入力したら「全て保存」してから「Spotify と連携」を押してください。") {
        Input(d.spotifyClientId, { set(d.copy(spotifyClientId = it)) }, placeholder = "例: 3f9a2c1b4d5e6f7a8b9c0d1e2f3a4b5c")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton("Spotify と連携", { onOpenBrowser("http://127.0.0.1:${DashboardServer.PORT}/api/spotify/start") }, enabled = savedId)
        ActionButton("連携を解除", { graph.spotify.disconnect() }, enabled = connected)
    }
    StatusText(
        when {
            connected -> "連携済み"
            savedId -> "未連携 —「Spotify と連携」を押してください"
            else -> "Client ID を保存すると連携できます"
        },
    )
    Spacer(Modifier.height(18.dp))
    Notice(
        "操作は Spotify で鳴らしている端末に送るもので、このタブレットからは音は出ません（曲送りには Spotify Premium が必要です）。\n" +
            "使うには Spotify Developer Dashboard で「Create app」→ Redirect URI に ${DashboardServer.SPOTIFY_REDIRECT_URI} を追加 → API は「Web API」を選び、" +
            "できた Client ID を上に入力してください（Client Secret は使いません）。",
    )
}

@Composable
private fun DevicePane(graph: AppGraph) {
    var enabled by remember { mutableStateOf(graph.launcher.isEnabled()) }
    PaneTitle("ホームアプリ", "再起動したあと、ダッシュボードを自動で前面に戻すための設定です。")
    Notice(
        "Android 10 以降はバックグラウンドからの画面起動が制限されるため、再起動後にダッシュボードが自動で前面に出るとは限りません。" +
            "この端末のホームアプリを Walldash にすると確実に前面へ戻ります。有効化したあと、端末側で「ホームアプリ」の選択ダイアログが出たら Walldash を選んでください。",
    )
    ActionButton(if (enabled) "ホームアプリ登録を解除" else "ホームアプリとして登録", {
        graph.settings.setLauncherEnabled(!enabled)
        enabled = graph.launcher.isEnabled()
    })
    StatusText(if (enabled) "登録済み — 端末の既定ホームアプリに Walldash を選べます" else "未登録 — 再起動後は手動でアプリを開く必要があります")
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

    Text("電池の最適化", color = Wd.Text2, fontSize = 13.tu, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
    ActionButton(if (ignoring) "除外済み" else "電池の最適化から除外する", {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(request) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }, enabled = !ignoring)
    Hint("メーカー独自の省電力機能（自動起動の管理など）は、端末の設定アプリで別に許可が必要なことがあります。")
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
        status = "処理中…"
        statusColor = Wd.Text2
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(block) }
                .onSuccess { status = ok; statusColor = Wd.Green }
                .onFailure { status = (it as? SettingsController.SettingsException)?.message ?: "エラー: ${it.message}"; statusColor = Wd.Red }
        }
    }

    PaneTitle("ネットワーク（LAN 公開）", "既定ではこの端末の中と、USB でつないだ PC からしか設定画面を開けません。LAN の他の端末のブラウザからも開けるようにします。")
    Notice(
        "LAN 公開は家庭内の信頼できる LAN 限定の機能です。通信は平文 HTTP のため、PIN やセッションは同じネットワーク上で盗聴され得ます。" +
            "ゲスト Wi-Fi、社内共有 LAN、不特定多数が接続するネットワークでは有効にしないでください。",
        Wd.Amber,
    )
    Field("PIN（6 桁以上）", "LAN 公開を有効にする前に設定が必要です。変えると、他の端末のログインはすべて無効になります。") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Input(pin, { pin = it }, Modifier.weight(1f), placeholder = if (pinSet) "設定済み（変更する場合のみ入力）" else "新しい PIN", password = true)
            Spacer(Modifier.width(10.dp))
            ActionButton("PIN を設定", { report({ graph.settings.setPin(pin) }, "PIN を設定しました（既存のログインは無効化されます）"); pin = "" })
        }
    }
    ActionButton(if (lanOn) "LAN 公開を無効にする" else "LAN 公開を有効にする", {
        if (lanOn) report({ graph.settings.setLanEnabled(false) }, "LAN 公開を無効にしました") else confirm = true
    }, primary = !lanOn)
    StatusText(
        when {
            lanOn && url != null -> "公開中 — 他の端末のブラウザで $url を開き、PIN でログインしてください"
            lanOn -> "公開中 — Wi-Fi の IP アドレスを取得できません"
            pinSet -> "この端末と USB の PC からだけ開けます（PIN 設定済み）"
            else -> "この端末と USB の PC からだけ開けます（PIN 未設定）"
        },
        if (lanOn) Wd.Green else Wd.Text2,
    )
    StatusText(status, statusColor)

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("LAN 公開を有効にします") },
            text = { Text("信頼できる家庭内 LAN でのみ使用してください。続けますか？") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    report({ graph.settings.setLanEnabled(true) }, "LAN 公開を有効にしました")
                }) { Text("有効にする") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("キャンセル") } },
        )
    }
}
