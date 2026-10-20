package app.dashboard.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dashboard.AppGraph
import app.dashboard.data.CardLayout
import app.dashboard.data.Config
import app.dashboard.data.DisplayConfig
import app.dashboard.data.Http
import app.dashboard.data.JsonPick
import app.dashboard.data.Tones
import app.dashboard.data.WIDGET_IDS
import app.dashboard.data.WIDGET_INTERVALS
import app.dashboard.data.WIDGET_TEMPLATES
import app.dashboard.data.WidgetDef
import app.dashboard.data.WidgetFormat
import app.dashboard.data.WidgetLevel
import app.dashboard.data.WidgetPushInfo
import app.dashboard.data.WidgetPushPatch
import app.dashboard.data.WidgetRepository
import app.dashboard.data.WidgetSanitize
import app.dashboard.data.WidgetTestResult
import app.dashboard.i18n.L
import app.dashboard.server.DashboardServer
import app.dashboard.ui.common.Tabular
import app.dashboard.ui.theme.LocalAccent
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 自作カード 1 枚の下書き。数は打ちかけ（空・「-」だけ）も持てるよう文字で持ち、保存のときに数にする。
 * [headerValue] は入れたときだけ送る（空なら保存済みの値のまま）。[clearHeader] で消す。
 */
internal data class WidgetDraft(
    val id: String,
    val title: String,
    val source: String,
    val style: String,
    val url: String,
    val headerName: String,
    val headerValue: String,
    val clearHeader: Boolean,
    val path: String,
    val labelKey: String,
    val valueKey: String,
    val unitPath: String,
    val unit: String,
    val decimals: Int,
    val scale: String,
    val min: String,
    val max: String,
    val warnAt: String,
    val alertAt: String,
    val below: Boolean,
    val notify: Boolean,
    val tone: String,
    val intervalMin: Int,
    val targets: String,
) {
    fun toDef(): WidgetDef = WidgetDef(
        id = id,
        title = title.trim(),
        source = source,
        style = style,
        url = url.trim(),
        headerName = headerName.trim(),
        headerValue = if (clearHeader) "" else headerValue.takeIf { it.isNotEmpty() },
        path = path.trim(),
        labelKey = labelKey.trim(),
        valueKey = valueKey.trim(),
        unitPath = unitPath.trim(),
        unit = unit.trim(),
        decimals = decimals,
        scale = num(scale) ?: 1.0,
        min = num(min) ?: 0.0,
        max = num(max) ?: 100.0,
        warnAt = num(warnAt),
        alertAt = num(alertAt),
        below = below,
        notify = notify,
        tone = tone,
        intervalMin = intervalMin,
        targets = WidgetSanitize.parseTargets(targets),
    )

    companion object {
        fun of(d: WidgetDef) = WidgetDraft(
            id = d.id,
            title = d.title,
            source = d.source,
            style = d.style,
            url = d.url,
            headerName = d.headerName,
            headerValue = "",
            clearHeader = false,
            path = d.path,
            labelKey = d.labelKey,
            valueKey = d.valueKey,
            unitPath = d.unitPath,
            unit = d.unit,
            decimals = d.decimals,
            scale = plain(d.scale),
            min = plain(d.min),
            max = plain(d.max),
            warnAt = d.warnAt?.let(::plain).orEmpty(),
            alertAt = d.alertAt?.let(::plain).orEmpty(),
            below = d.below,
            notify = d.notify,
            tone = d.tone,
            intervalMin = d.intervalMin,
            targets = WidgetSanitize.targetLines(d.targets),
        )

        private fun num(s: String): Double? = s.trim().replace(",", "").replace("−", "-").toDoubleOrNull()

        /** 入力欄に戻す数（1.0 は「1」）。 */
        private fun plain(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
    }
}

/**
 * 設定の「自作カード」。上の 4 つの札で直すカードを選び、取得のしかた・見せ方・しきい値を決める。
 * 「試しに取得」で返ってきた JSON を木で出し、値を押すと「値の場所」に入る。
 * 外から送る口（トークン）は 4 枚で共通で、その場で保存する（「全て保存」は通さない。外からの通知と同じ）。
 */
@Composable
internal fun WidgetsPane(
    graph: AppGraph,
    config: Config,
    drafts: List<WidgetDraft>,
    display: DisplayConfig,
    onDrafts: (List<WidgetDraft>) -> Unit,
    onShown: (index: Int, on: Boolean) -> Unit,
) {
    var selected by remember { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, WIDGET_IDS.size - 1)
    val w = drafts.getOrNull(index) ?: return
    val saved = config.widgets.def(w.id)
    fun set(next: WidgetDraft) = onDrafts(drafts.mapIndexed { i, d -> if (i == index) next else d })

    PaneTitle(
        L("自作カード", "Custom cards"),
        L(
            "取得先と見せ方を自分で決めるカードです（4 枚まで）。JSON を返す URL（Home Assistant・家のセンサー・好きな API）から取る、ほかの機器から送ってもらう、サーバーが動いているかを見張る、のどれかを選びます。",
            "Cards whose source and look you choose yourself (up to 4). Get a value from a URL that returns JSON (Home Assistant, home sensors, any API), have another device push it, or watch whether servers are up.",
        ),
    )

    // どのカードを直すか
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        drafts.forEachIndexed { i, d ->
            val on = WidgetRepository.shown(display, i)
            val active = i == index
            Row(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (active) Wd.Surface2 else Color.Transparent)
                    .border(1.dp, if (active) LocalAccent.current else Wd.Border, RoundedCornerShape(10.dp))
                    .clickable { selected = i }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (on) LocalAccent.current else Wd.Border))
                Spacer(Modifier.width(8.dp))
                Text(
                    WidgetDef.defaultLabel(d.id) + d.title.takeIf { it.isNotBlank() }?.let { L("：", ": ") + it }.orEmpty(),
                    fontSize = 13.5f.tu, color = if (active) Wd.Text else Wd.Text2, maxLines = 1,
                )
            }
        }
    }

    Field { SwitchRow(L("このカードをダッシュボードに表示する", "Show this card on the dashboard"), WidgetRepository.shown(display, index), { onShown(index, it) }) }

    Field(L("ひな形", "Template"), L("選ぶと、下の欄をその例で埋めます（見出しが空なら見出しも）。あとから自由に直せます。", "Fills the fields below with an example (and the title if it's empty). You can change anything afterwards.")) {
        Select(
            listOf("" to L("選んでください…", "Choose…")) + WIDGET_TEMPLATES.map { it.id to it.label },
            "",
            { id ->
                val t = WIDGET_TEMPLATES.firstOrNull { it.id == id } ?: return@Select
                set(WidgetDraft.of(t.apply(w.toDef().copy(headerValue = null))).copy(headerValue = w.headerValue))
            },
        )
    }

    Field(L("見出し", "Title"), L("カードの左上に出す名前です（30 文字まで）。空なら「${WidgetDef.defaultLabel(w.id)}」。", "The name at the top left of the card (up to 30 characters). Empty means \"${WidgetDef.defaultLabel(w.id)}\".")) {
        Input(w.title, { set(w.copy(title = it)) }, placeholder = WidgetDef.defaultLabel(w.id))
    }

    Field(L("取得のしかた", "Source")) {
        Select(
            listOf(
                "http" to L("URL から取る（JSON）", "Fetch from a URL (JSON)"),
                "push" to L("外から送ってもらう", "Pushed from outside"),
                "monitor" to L("死活監視（動いているか）", "Uptime monitor"),
            ),
            w.source, { set(w.copy(source = it)) },
        )
    }

    when (w.source) {
        "http" -> {
            Field(
                "URL",
                L(
                    "JSON を返す URL です。外のサイトは https:// にしてください。http:// は家の中（LAN）の機器（192.168.x.x・homeassistant.local など）だけに使えます。" +
                        "{lat}・{lon} は「場所」の緯度・経度に、{lang} は表示の言語に置き換えます。",
                    "A URL that returns JSON. Use https:// for outside sites; plain http:// works only for devices on your LAN (192.168.x.x, homeassistant.local…). " +
                        "{lat} and {lon} become your location's coordinates and {lang} the display language.",
                ),
            ) {
                Input(w.url, { set(w.copy(url = it)) }, placeholder = "https://…")
            }
            Field(
                L("ヘッダー（任意）", "Header (optional)"),
                L(
                    "トークンの要る API のためのヘッダー 1 つです（Home Assistant なら名前「Authorization」、値「Bearer 長期アクセストークン」）。値は保存したあと表示しません。URL のホストを変えると値は消えます（入れ直してください）。",
                    "One header for APIs that need a token (for Home Assistant: name \"Authorization\", value \"Bearer <long-lived token>\"). The saved value is never shown. Changing the URL's host clears it (enter it again).",
                ),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Input(w.headerName, { set(w.copy(headerName = it)) }, Modifier.weight(0.4f), placeholder = L("名前（例: Authorization）", "Name (e.g. Authorization)"))
                    Input(
                        w.headerValue, { set(w.copy(headerValue = it, clearHeader = false)) }, Modifier.weight(0.6f),
                        placeholder = when {
                            w.clearHeader -> L("保存すると消します", "Cleared when saved")
                            !saved.headerValue.isNullOrBlank() -> L("設定済み（変更する場合のみ入力）", "Set (enter only to change)")
                            else -> L("値", "Value")
                        },
                        password = true,
                    )
                }
                if (!saved.headerValue.isNullOrBlank() && !w.clearHeader) {
                    Spacer(Modifier.size(8.dp))
                    ActionButton(L("保存したヘッダーの値を消す", "Clear the saved header value"), { set(w.copy(headerValue = "", clearHeader = true)) })
                }
            }
            IntervalField(w) { set(w.copy(intervalMin = it)) }
        }
        "push" -> PushField(graph, config, w.id)
        "monitor" -> {
            Field(
                L("監視する先", "Targets"),
                L(
                    "1 行に 1 つ、「名前 URL」の形で（名前は省けます。8 つまで）。https:// と LAN の http:// は応答（2xx・3xx は正常、4xx は注意、5xx と応答なしは警戒）を、tcp://ホスト:ポート はつながるかを見ます。",
                    "One per line as \"name URL\" (the name is optional, up to 8). For https:// and LAN http:// the response is checked (2xx/3xx up, 4xx warning, 5xx or no response down); tcp://host:port checks that it connects.",
                ),
            ) {
                Input(w.targets, { set(w.copy(targets = it)) }, singleLine = false, minLines = 4, placeholder = "NAS http://192.168.1.10:5000\nSSH tcp://192.168.1.20:22\nGoogle https://www.google.com")
            }
            IntervalField(w) { set(w.copy(intervalMin = it)) }
        }
    }

    if (w.source != "monitor") {
        Field(L("見せ方", "Style")) {
            Select(
                listOf(
                    "number" to L("数値（大きな値と 24 時間の折れ線）", "Number (big value and 24 h line)"),
                    "gauge" to L("メーター（最小〜最大の半円）", "Gauge (half circle from min to max)"),
                    "list" to L("一覧（名前と値の行）", "List (rows of names and values)"),
                    "text" to L("文字", "Text"),
                ),
                w.style.takeIf { it != "monitor" } ?: "number", { set(w.copy(style = it)) },
            )
        }
        Field(
            L("値の場所", "Value path"),
            when (w.style) {
                "list" -> L(
                    "配列（か物）の場所です。配列なら各要素の「名前のキー」「値のキー」を 1 行にし、物ならキーと値を並べます。空なら全体。",
                    "Path to an array (or object). For an array, each element's name key and value key become a row; for an object, its keys and values are listed. Empty means the whole response.",
                )
                else -> L(
                    "応答の中の値の場所です（例: state・data.temp・items[0].value・rates.JPY）。下の「試しに取得」で値を押すと入ります。" +
                        if (w.source == "push") "外から送るときは空でかまいません（{\"value\": 23.4} の形で送れます）。" else "",
                    "Where the value is in the response (e.g. state, data.temp, items[0].value, rates.JPY). Tap a value after \"Try fetching\" below to fill it in." +
                        if (w.source == "push") " Leave it empty when pushing simple bodies like {\"value\": 23.4}." else "",
                )
            },
        ) {
            Input(w.path, { set(w.copy(path = it)) }, placeholder = L("例: data.temperature", "e.g. data.temperature"))
            if (JsonPick.parse(w.path) == null) StatusText(L("書き方が読めません（例: a.b[0].c、[\"点.の入ったキー\"]）", "Can't read this path (e.g. a.b[0].c, [\"key.with.dots\"])"), Wd.Red)
        }
        if (w.style == "list") {
            Field(L("一覧の行（配列のとき）", "List rows (for arrays)"), L("各要素の中の、名前と値の場所です。空なら 1 つ目と 2 つ目の項目。", "Paths inside each element for the name and the value. Empty means the first and second fields.")) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Input(w.labelKey, { set(w.copy(labelKey = it)) }, Modifier.weight(1f), placeholder = L("名前のキー（例: name）", "Name key (e.g. name)"))
                    Input(w.valueKey, { set(w.copy(valueKey = it)) }, Modifier.weight(1f), placeholder = L("値のキー（例: value）", "Value key (e.g. value)"))
                }
            }
        }
        if (w.style != "text") {
            Field(L("単位と桁", "Unit and digits"), L("単位の場所を入れると応答の単位を使います（Home Assistant の attributes.unit_of_measurement など）。倍率は取り出した値に掛けます（バイト → MB なら 0.000001）。", "With a unit path the response's own unit is used (e.g. Home Assistant's attributes.unit_of_measurement). The scale multiplies the value (bytes → MB: 0.000001).")) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Input(w.unit, { set(w.copy(unit = it)) }, Modifier.weight(0.7f), placeholder = L("単位（例: ℃）", "Unit (e.g. °C)"))
                    Input(w.unitPath, { set(w.copy(unitPath = it)) }, Modifier.weight(1f), placeholder = L("単位の場所（任意）", "Unit path (optional)"))
                    Input(w.scale, { set(w.copy(scale = it)) }, Modifier.weight(0.5f), placeholder = L("倍率", "Scale"))
                }
                Spacer(Modifier.size(10.dp))
                Select(
                    listOf(-1 to L("小数の桁: 自動", "Decimals: auto")) + (0..4).map { it to L("小数の桁: $it", "Decimals: $it") },
                    w.decimals, { set(w.copy(decimals = it)) },
                )
            }
        }
        if (w.style == "gauge") {
            Field(L("メーターの範囲", "Gauge range")) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Input(w.min, { set(w.copy(min = it)) }, Modifier.weight(1f), placeholder = L("最小", "Min"), number = true)
                    Input(w.max, { set(w.copy(max = it)) }, Modifier.weight(1f), placeholder = L("最大", "Max"), number = true)
                }
            }
        }
        if (w.style != "text") {
            Field(
                L("しきい値（任意）", "Thresholds (optional)"),
                L("越えると色が変わります（注意は琥珀、警戒は赤）。一覧では各行の値で決めます。", "Colors change when crossed (amber for warning, red for alert). In lists, each row is judged by its own value."),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Input(w.warnAt, { set(w.copy(warnAt = it)) }, Modifier.weight(1f), placeholder = L("注意", "Warning"))
                    Input(w.alertAt, { set(w.copy(alertAt = it)) }, Modifier.weight(1f), placeholder = L("警戒", "Alert"))
                }
                SwitchRow(L("下回ったら（例: 湿度・電池）", "When it falls below (e.g. humidity, battery)"), w.below, { set(w.copy(below = it)) })
            }
        }
    }

    if (w.style != "text" || w.source == "monitor") {
        Field(
            L("知らせる", "Notify"),
            if (w.source == "monitor") L("どれかが応答しなくなったとき・すべて戻ったときに、バナーと音で知らせます。", "Shows a banner and plays a sound when something stops responding and when everything is back.")
            else L("警戒に入ったとき・ふつうに戻ったときに、バナーと音で知らせます。", "Shows a banner and plays a sound when it enters the alert level and when it returns to normal."),
        ) {
            SwitchRow(L("知らせる", "Notify"), w.notify, { set(w.copy(notify = it)) })
            if (w.notify) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Select(listOf("" to L("音: LINE メモと同じ", "Sound: same as LINE memo")) + Tones.ALL.map { it.id to it.label }, w.tone, { set(w.copy(tone = it)) }, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    ActionButton(L("試聴", "Preview"), { graph.notices.play(w.tone.ifEmpty { config.notifications.memoTone }, Tones.DEFAULT_MEMO, volume = config.notifications.volume) })
                }
            }
        }
    }

    TestField(graph, w) { set(it) }
    WidgetStatus(graph, config, w, display, index)
}

@Composable
private fun IntervalField(w: WidgetDraft, onChange: (Int) -> Unit) {
    Field(L("取りに行く間隔", "Interval"), L("カードを表示している間だけ取りに行きます。", "Fetched only while the card is shown.")) {
        Select(
            WIDGET_INTERVALS.map { m ->
                m to when {
                    m < 60 -> L("$m 分ごと", "Every $m min")
                    m == 1440 -> L("1 日ごと", "Once a day")
                    else -> L("${m / 60} 時間ごと", "Every ${m / 60} h")
                }
            },
            w.intervalMin, onChange,
        )
    }
}

/** いまの取得のようす（保存済みの設定で動いている値）。 */
@Composable
private fun WidgetStatus(graph: AppGraph, config: Config, w: WidgetDraft, display: DisplayConfig, index: Int) {
    val def = config.widgets.def(w.id)
    val s = graph.widgets.state.of(w.id)?.takeIf { it.key == def.dataKey }
    StatusText(
        when {
            !WidgetRepository.shown(display, index) -> L("カードを表示しているときだけ取得します", "Fetched only while the card is shown")
            !WidgetRepository.configured(def) -> L("取得先を入れて「全て保存」してください", "Enter a source and press Save all")
            def.source == "push" && !config.widgets.pushEnabled -> L("外から送る口が閉じています", "Pushing is turned off")
            s?.lastError != null -> L("エラー: ", "Error: ") + s.lastError
            s != null && s.updatedAt > 0 -> L("取得できています", "Working") + (s.number?.let { L("（いまの値 ", " (now ") + WidgetFormat.withUnit(WidgetFormat.number(it, def.decimals), s.unit) + L("）", ")") } ?: "")
            def.source == "push" -> L("まだ値が届いていません", "No value has arrived yet")
            else -> L("まだ取得していません —「全て保存」のあと少し待ってください", "Not fetched yet — wait a moment after Save all")
        },
        if (s?.lastError != null) Wd.Amber else Wd.Text2,
    )
}

// ---------------------------------------------------------------- 外から送る

@Composable
private fun PushField(graph: AppGraph, config: Config, id: String) {
    var info by remember(config.widgets.pushEnabled, config.widgets.pushToken, config.lan.enabled) { mutableStateOf<WidgetPushInfo>(graph.settings.widgetPushInfo()) }
    var status by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf(Wd.Text2) }
    val scope = rememberCoroutineScope()

    fun change(patch: WidgetPushPatch, ok: String) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { graph.settings.updateWidgetPush(patch) } }
                .onSuccess { info = it; status = ok; statusColor = Wd.Green }
                .onFailure { status = L("エラー: ${it.message}", "Error: ${it.message}"); statusColor = Wd.Red }
        }
    }

    Field(
        L("外から送る口（4 枚で共通・すぐに保存）", "Push endpoint (shared by all 4 cards, saved at once)"),
        L(
            "Home Assistant の自動化・家のセンサー・PC のスクリプトから、このカードへ値を送ります。トークンを 10 回まちがえた端末は 10 分受け付けません。",
            "Send values to this card from Home Assistant automations, home sensors or scripts on your PC. A device that sends a wrong token 10 times is refused for 10 minutes.",
        ),
    ) {
        SwitchRow(L("外から送られた値を受け付ける", "Accept pushed values"), info.enabled, { on -> change(WidgetPushPatch(enabled = on), if (on) L("オンにしました", "Turned on") else L("オフにしました", "Turned off")) })
        if (info.enabled) {
            Spacer(Modifier.size(6.dp))
            Text(L("トークン", "Token"), color = Wd.Text2, fontSize = 13.tu)
            SelectionContainer { Text(info.token.orEmpty(), fontSize = 14.tu, style = Tabular) }
            Spacer(Modifier.size(8.dp))
            ActionButton(L("トークンを作り直す", "Regenerate the token"), { change(WidgetPushPatch(regenerate = true), L("作り直しました。送る側の設定も直してください", "Regenerated. Update the sender too")) })
            Spacer(Modifier.size(12.dp))
            val url = (info.lanUrl ?: "http://<${L("タブレットの IP", "tablet IP")}>:${DashboardServer.PORT}/api/widgets/") + id
            Text(L("送り方", "How to send"), color = Wd.Text2, fontSize = 13.tu)
            SelectionContainer {
                Text(
                    "curl -X POST $url \\\n  -H 'Authorization: Bearer ${info.token.orEmpty()}' \\\n  -H 'Content-Type: application/json' \\\n  -d '{\"value\": 23.4}'",
                    fontSize = 12.tu, color = Wd.Text2, style = Tabular,
                )
            }
            Hint(
                L(
                    "本文は {\"value\": 数}・{\"text\": \"文字\"}・{\"items\": [{\"label\": \"名前\", \"value\": 値, \"level\": \"ok|warn|alert\"}]}（unit も付けられます）か、ただの数・文字。「値の場所」を入れておけば、Home Assistant の状態などをそのまま送れます。",
                    "The body is {\"value\": number}, {\"text\": \"text\"} or {\"items\": [{\"label\": \"name\", \"value\": v, \"level\": \"ok|warn|alert\"}]} (unit is allowed too), or a bare number or text. With a value path set, you can send e.g. a Home Assistant state as is.",
                ),
            )
            if (!info.lanEnabled) Hint(L("他の端末から送るには「ネットワーク」で LAN 公開を有効にしてください。", "To send from other devices, enable LAN access under \"Network\"."))
        }
        StatusText(status, statusColor)
    }
}

// ---------------------------------------------------------------- 試しに取得

/** JSON の木の 1 行。[path] はその場所、[leaf] は値（数・文字など）か。 */
private data class TreeRow(val depth: Int, val key: String, val path: String, val preview: String, val leaf: Boolean)

private fun flatten(e: JsonElement, key: String, path: String, depth: Int, out: MutableList<TreeRow>) {
    if (out.size >= MAX_TREE_ROWS) return
    when (e) {
        is JsonObject -> {
            out += TreeRow(depth, key, path, "{${e.size}}", false)
            if (depth < MAX_TREE_DEPTH) e.entries.forEach { (k, v) -> flatten(v, k, JsonPick.join(path, JsonPick.Step.Key(k)), depth + 1, out) }
        }
        is JsonArray -> {
            out += TreeRow(depth, key, path, "[${e.size}]", false)
            if (depth < MAX_TREE_DEPTH) e.take(MAX_TREE_ITEMS).forEachIndexed { i, v -> flatten(v, "[$i]", JsonPick.join(path, JsonPick.Step.Index(i)), depth + 1, out) }
        }
        is JsonPrimitive -> out += TreeRow(depth, key, path, (if (e.isString) "\"${e.content}\"" else e.content).take(60), true)
    }
}

private const val MAX_TREE_ROWS = 300
private const val MAX_TREE_DEPTH = 7
private const val MAX_TREE_ITEMS = 20

@Composable
private fun TestField(graph: AppGraph, w: WidgetDraft, onChange: (WidgetDraft) -> Unit) {
    if (w.source == "push") return
    var result by remember(w.id) { mutableStateOf<WidgetTestResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Field(
        L("試しに取得", "Try fetching"),
        L("保存していない下書きのまま 1 回だけ取りに行きます。下の木で値を押すと「値の場所」に入ります（一覧の見せ方なら配列を押します）。", "Fetches once with the unsaved draft. Tap a value in the tree below to set the value path (for the list style, tap an array)."),
    ) {
        ActionButton(if (busy) L("取得中…", "Fetching…") else L("試しに取得", "Try fetching"), {
            busy = true
            scope.launch {
                result = withContext(Dispatchers.IO) { runCatching { graph.widgets.test(WidgetSanitize.def(w.toDef()).copy(headerValue = w.headerValue.ifEmpty { null })) }.getOrElse { WidgetTestResult(false, error = it.message) } }
                busy = false
            }
        }, enabled = !busy)
        result?.let { r -> TestResultView(r, w, onChange) }
    }
}

@Composable
private fun TestResultView(r: WidgetTestResult, w: WidgetDraft, onChange: (WidgetDraft) -> Unit) {
    Column(Modifier.padding(top = 10.dp)) {
        val summary = when {
            !r.ok -> L("失敗: ", "Failed: ") + (r.error ?: "")
            r.data?.number != null -> L("取れました: ", "Got: ") + WidgetFormat.withUnit(WidgetFormat.number(r.data.number, w.decimals), r.data.unit)
            r.data?.text != null -> L("取れました: ", "Got: ") + r.data.text.take(80)
            r.data != null && r.data.items.isNotEmpty() -> L("取れました: ${r.data.items.size} 行", "Got ${r.data.items.size} rows") +
                L("（警戒 ${r.data.items.count { it.level == WidgetLevel.ALERT }}）", " (${r.data.items.count { it.level == WidgetLevel.ALERT }} alert)")
            else -> L("取れました", "OK")
        }
        StatusText((r.status?.let { "HTTP $it ・ " } ?: "") + summary, if (r.ok) Wd.Green else Wd.Red)
        r.data?.items?.takeIf { it.isNotEmpty() && w.source == "monitor" }?.forEach {
            Text("${it.label}: ${it.value}", fontSize = 12.5f.tu, color = if (it.level == WidgetLevel.OK) Wd.Text2 else Wd.Red)
        }
        val root = remember(r.raw) { r.raw?.let { runCatching { Http.json.parseToJsonElement(it) }.getOrNull() } }
        val rows = remember(root) { root?.let { mutableListOf<TreeRow>().also { out -> flatten(it, "$", "", 0, out) } }.orEmpty() }
        if (rows.isNotEmpty()) {
            Column(
                Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(max = 360.dp)
                    .clip(RoundedCornerShape(10.dp)).border(1.dp, Wd.Border, RoundedCornerShape(10.dp))
                    .background(Wd.Surface).verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
            ) {
                rows.forEach { row ->
                    val pickable = if (w.style == "list") !row.leaf || row.depth == 0 else row.leaf
                    val chosen = row.path == w.path.trim() && pickable
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (chosen) LocalAccent.current.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable(enabled = pickable) { onChange(w.copy(path = row.path)) }
                            .padding(start = (10 + row.depth * 14).dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                    ) {
                        Text(row.key, fontSize = 12.5f.tu, color = if (pickable) Wd.Text else Wd.Text3, fontWeight = if (row.leaf) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        Text(row.preview, fontSize = 12.5f.tu, color = if (pickable) LocalAccent.current else Wd.Text3, style = Tabular, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (rows.size >= MAX_TREE_ROWS) Hint(L("長いので途中までです", "Truncated (too long)"))
        } else if (r.raw != null) {
            SelectionContainer {
                Text(r.raw.take(1200), fontSize = 12.tu, color = Wd.Text2, style = Tabular, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** カードの表示の切り替え（[CardLayout.Card.WIDGET1] など）。 */
internal fun widgetCard(index: Int): CardLayout.Card = CardLayout.Card.valueOf("WIDGET${index + 1}")
