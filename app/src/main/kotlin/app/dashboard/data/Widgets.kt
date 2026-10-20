package app.dashboard.data

import app.dashboard.i18n.L
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

// ---------------------------------------------------------------- 自作カード（ウィジェット）
//
// 「どこから取るか」（[WidgetDef.source]）と「どう見せるか」（[WidgetDef.style]）を設定だけで決めるカード。最大 [MAX_WIDGETS] 枚。
// 取得は WidgetRepository、描画は WidgetCard.kt、設定はアプリの WidgetsPane.kt と Web の widgets.js。

/** 自作カードの枚数。カードは CardLayout.Card の WIDGET1〜WIDGET4。 */
const val MAX_WIDGETS = 4

/** 自作カードの識別子（`POST /api/widgets/{id}` の {id} にもなる）。 */
val WIDGET_IDS: List<String> = (1..MAX_WIDGETS).map { "w$it" }

/** 取得のしかた。"http" = URL から取る、"push" = 外から送ってもらう、"monitor" = 死活監視。 */
val WIDGET_SOURCES: List<String> = listOf("http", "push", "monitor")

/** 見せ方。"number" = 数値、"gauge" = メーター、"list" = 一覧、"text" = 文字、"monitor" = 監視（死活監視のときだけ）。 */
val WIDGET_STYLES: List<String> = listOf("number", "gauge", "list", "text", "monitor")

/** 取りに行く間隔（分）。 */
val WIDGET_INTERVALS: List<Int> = listOf(1, 2, 5, 10, 15, 30, 60, 180, 360, 1440)

/** 監視の行数・一覧の行数の上限。 */
const val MAX_WIDGET_TARGETS = 8
const val MAX_WIDGET_ITEMS = 12

/** 履歴に残す点の数（[WIDGET_HISTORY_STEP_MS] ごとに 1 点で、24 時間ぶん）。 */
const val WIDGET_HISTORY_POINTS = 288
const val WIDGET_HISTORY_STEP_MS = 5 * 60_000L

/** 監視の 1 行（名前と、`https://…` / LAN の `http://…` / `tcp://host:port`）。 */
@Serializable
data class WidgetTarget(val name: String = "", val url: String = "")

/**
 * 自作カード 1 枚の設定。
 *
 * [headerValue] は書き込み専用（Authorization のトークンなど）。PublicConfig には載せず、[WidgetsPublic.headerSet] だけを出す。
 * [path] は値の場所（`state`・`data.temp`・`items[0].name`・`$.rates.JPY`。[JsonPick]）。一覧の見せ方では配列（か物）の場所で、
 * 各要素の [labelKey]・[valueKey] を行にする。
 * しきい値は [warnAt]（注意）と [alertAt]（警戒）。[below] が true なら「下回ったら」。
 */
@Serializable
data class WidgetDef(
    val id: String,
    val title: String = "",
    val source: String = "http",
    val style: String = "number",
    val url: String = "",
    val headerName: String = "",
    val headerValue: String? = null,
    val path: String = "",
    val labelKey: String = "",
    val valueKey: String = "",
    /** 単位を応答から取る場所（Home Assistant の `attributes.unit_of_measurement` など）。空なら [unit]。 */
    val unitPath: String = "",
    val unit: String = "",
    /** 小数の桁数。-1 は自動（大きな値ほど少なく）。 */
    val decimals: Int = -1,
    /** 取り出した値に掛ける数（バイト → MB、K → ℃ の差分は扱わない）。 */
    val scale: Double = 1.0,
    val min: Double = 0.0,
    val max: Double = 100.0,
    val warnAt: Double? = null,
    val alertAt: Double? = null,
    val below: Boolean = false,
    /** 警戒に入ったとき（監視なら応答しなくなったとき）にバナーと音で知らせる。 */
    val notify: Boolean = false,
    /** 知らせる音（[Tones] の ID）。空なら「LINE メモ」の音。 */
    val tone: String = "",
    val intervalMin: Int = 5,
    val targets: List<WidgetTarget> = emptyList(),
) {
    /** 見出しに出す名前（空なら「自作 1」など）。 */
    val label: String get() = title.ifBlank { defaultLabel(id) }

    /** 実際の見せ方。死活監視は必ず監視の見せ方にする。 */
    val shownStyle: String get() = if (source == "monitor") "monitor" else style.takeIf { it in WIDGET_STYLES && it != "monitor" } ?: "number"

    /** 履歴を残す見せ方か。 */
    val keepsHistory: Boolean get() = shownStyle == "number" || shownStyle == "gauge"

    /**
     * 取った値の意味が変わる設定の組み合わせ。変わったら履歴と前の値を捨てる（別の URL の値を同じ折れ線に描かない）。
     * 見出し・色・しきい値だけの変更では捨てない。
     */
    val dataKey: String get() = listOf(source, shownStyle, url, path, labelKey, valueKey, scale.toString(), targets.joinToString("\n") { it.url }).joinToString("\u0001")

    companion object {
        fun defaultLabel(id: String): String = L("自作 ", "Custom ") + id.removePrefix("w")
    }
}

/** 自作カードの設定（[MAX_WIDGETS] 枚ぶん）と、外から送るときのトークン。 */
@Serializable
data class WidgetsConfig(
    val items: List<WidgetDef> = WIDGET_IDS.map { WidgetDef(it) },
    /** `POST /api/widgets/{id}` を受け付けるか。 */
    val pushEnabled: Boolean = false,
    val pushToken: String? = null,
) {
    fun def(id: String): WidgetDef = items.firstOrNull { it.id == id } ?: WidgetDef(id)
}

/** Web の設定画面に出す自作カードの設定（ヘッダーの値は出さない）。 */
@Serializable
data class WidgetsPublic(
    val items: List<WidgetDef> = emptyList(),
    /** 各カードのヘッダーの値が保存されているか（[items] と同じ並び）。 */
    val headerSet: List<Boolean> = emptyList(),
    val pushEnabled: Boolean = false,
)

fun WidgetsConfig.toPublic() = WidgetsPublic(
    items = items.map { it.copy(headerValue = null) },
    headerSet = items.map { !it.headerValue.isNullOrBlank() },
    pushEnabled = pushEnabled,
)

/**
 * 「全て保存」で送る自作カードの設定。[items] の [WidgetDef.headerValue] は null なら変えない、空文字なら消す。
 * ヘッダーの名前を空にしたときも値を消す。
 */
@Serializable
data class WidgetsPatch(val items: List<WidgetDef> = emptyList())

/** 外から送る API の設定（`/api/widgets/config`）。 */
@Serializable
data class WidgetPushInfo(
    val enabled: Boolean,
    val token: String?,
    /** 他の端末から送るときの URL の頭（`http://IP:8080/api/widgets/`）。LAN 公開が無効なら null。 */
    val lanUrl: String?,
    val lanEnabled: Boolean,
)

@Serializable
data class WidgetPushPatch(val enabled: Boolean? = null, val regenerate: Boolean = false)

/** 「試しに取得」の要求（Web の設定画面）。[def] のヘッダーの値が空なら、同じ [WidgetDef.id] の保存済みの値を使う。 */
@Serializable
data class WidgetTestRequest(val def: WidgetDef)

/** 「試しに取得」の結果。[raw] は返ってきた本文（長ければ途中まで）、[data] はそれを [def] で読んだもの。 */
@Serializable
data class WidgetTestResult(
    val ok: Boolean,
    val status: Int? = null,
    val raw: String? = null,
    val data: WidgetData? = null,
    val error: String? = null,
)

// ---------------------------------------------------------------- 取った値

/** 一覧・監視の 1 行。[level] は [WidgetLevel]（0 ふつう・1 注意・2 警戒）。[ms] は監視の応答時間。 */
@Serializable
data class WidgetItem(val label: String, val value: String, val level: Int = 0, val ms: Long? = null)

/** 履歴の 1 点（epoch ms と値）。 */
@Serializable
data class WidgetPoint(val t: Long, val v: Double)

/**
 * 自作カード 1 枚のいまの値。[key] は取ったときの [WidgetDef.dataKey]（設定が変わったら捨てる目印）。
 * [updatedAt] は値が変わった（届いた）時刻、[fetchedAt] は最後に取りに行った時刻（失敗も含む）。
 */
@Serializable
data class WidgetData(
    val id: String,
    val key: String = "",
    val number: Double? = null,
    val text: String? = null,
    val unit: String? = null,
    val items: List<WidgetItem> = emptyList(),
    val history: List<WidgetPoint> = emptyList(),
    val updatedAt: Long = 0,
    val fetchedAt: Long = 0,
    val lastError: String? = null,
)

@Serializable
data class WidgetsState(val items: List<WidgetData> = emptyList()) {
    fun of(id: String): WidgetData? = items.firstOrNull { it.id == id }
}

/** 外から送られた値（`POST /api/widgets/{id}` の本文）を読んだ結果。 */
sealed interface WidgetPush {
    data class Value(val data: WidgetData) : WidgetPush
    data class Bad(val reason: String) : WidgetPush
}

// ---------------------------------------------------------------- しきい値

object WidgetLevel {
    const val OK = 0
    const val WARN = 1
    const val ALERT = 2

    /** [v] がしきい値のどの段か。しきい値が無ければ [OK]。 */
    fun of(v: Double?, def: WidgetDef): Int {
        if (v == null || v.isNaN()) return OK
        fun past(at: Double?) = at != null && if (def.below) v <= at else v >= at
        return when {
            past(def.alertAt) -> ALERT
            past(def.warnAt) -> WARN
            else -> OK
        }
    }

    /** 一覧・監視の行のいちばん重い段。 */
    fun worst(items: List<WidgetItem>): Int = items.maxOfOrNull { it.level } ?: OK
}

// ---------------------------------------------------------------- 値の書式

object WidgetFormat {

    /**
     * 数を表示の文字に。[decimals] が -1 なら自動（1000 以上は整数、100 以上は 1 桁、それより小さければ 2 桁まで。末尾の 0 は落とす）。
     * 桁区切りは言語によらずカンマ。
     */
    fun number(v: Double, decimals: Int = -1): String {
        if (v.isNaN()) return "—"
        if (v.isInfinite()) return if (v > 0) "∞" else "−∞"
        val digits = if (decimals >= 0) decimals.coerceAtMost(6) else when {
            kotlin.math.abs(v) >= 1000 -> 0
            kotlin.math.abs(v) >= 100 -> 1
            else -> 2
        }
        val rounded = BigDecimal(v).setScale(digits, RoundingMode.HALF_UP)
        val pattern = if (digits == 0) "#,##0" else "#,##0." + (if (decimals >= 0) "0" else "#").repeat(digits)
        val f = DecimalFormat(pattern, DecimalFormatSymbols(Locale.US))
        return f.format(rounded).let { if (it == "-0") "0" else it }.replace("-", "−")
    }

    /** 単位を添える。°・% は詰め、それ以外は半角の間を空ける。 */
    fun withUnit(value: String, unit: String?): String {
        val u = unit?.trim().orEmpty()
        if (u.isEmpty()) return value
        return if (u.first() in "°%℃℉′″") value + u else "$value $u"
    }

    /** 応答の値（JSON）を一覧の 1 行に出す文字に。 */
    fun element(e: JsonElement?, decimals: Int = -1, scale: Double = 1.0): String = when (e) {
        null, JsonNull -> "—"
        is JsonPrimitive -> if (e.isString) e.content else e.doubleOrNull?.let { number(it * scale, decimals) } ?: e.content
        is JsonArray -> e.joinToString(", ") { element(it, decimals, scale) }.take(80)
        is JsonObject -> e.entries.joinToString(", ") { "${it.key}: ${element(it.value, decimals, scale)}" }.take(80)
    }
}

// ---------------------------------------------------------------- JSON の中の場所

/**
 * JSON の中の値の場所を、短い道筋で指す。
 *
 * - `a.b.c`（物のキーを順にたどる）・`items[0].name`（配列の何番目か。`[-1]` は最後）・`["キー.の中に.点"]`（引用符のキー）
 * - 先頭の `$` と `$.` は付けても付けなくてもよい（JSONPath に慣れた人向け）
 * - 空の道筋は全体
 * 読めない道筋は [parse] が null を返す。
 */
object JsonPick {

    sealed interface Step {
        data class Key(val name: String) : Step
        data class Index(val index: Int) : Step
    }

    fun parse(path: String): List<Step>? {
        var p = path.trim()
        if (p.startsWith("$")) p = p.removePrefix("$")
        if (p.startsWith(".")) p = p.removePrefix(".")
        val steps = mutableListOf<Step>()
        var i = 0
        val key = StringBuilder()
        fun flushKey(): Boolean {
            if (key.isEmpty()) return true
            steps += Step.Key(key.toString())
            key.clear()
            return true
        }
        while (i < p.length) {
            val ch = p[i]
            when {
                ch == '.' -> {
                    if (key.isEmpty() && (steps.isEmpty() || p.getOrNull(i - 1) == '.')) return null
                    flushKey()
                    i++
                }
                ch == '[' -> {
                    flushKey()
                    val close = findClose(p, i) ?: return null
                    val inner = p.substring(i + 1, close).trim()
                    when {
                        inner.length >= 2 && (inner.first() == '"' && inner.last() == '"' || inner.first() == '\'' && inner.last() == '\'') ->
                            steps += Step.Key(unescape(inner.substring(1, inner.length - 1)))
                        inner.toIntOrNull() != null -> steps += Step.Index(inner.toInt())
                        else -> return null
                    }
                    i = close + 1
                }
                ch == ']' -> return null
                else -> {
                    key.append(ch)
                    i++
                }
            }
        }
        if (p.endsWith(".")) return null
        flushKey()
        return steps
    }

    /** 引用符の中の `]` を飛ばして、対になる `]` の位置。 */
    private fun findClose(p: String, open: Int): Int? {
        var i = open + 1
        var quote: Char? = null
        while (i < p.length) {
            val ch = p[i]
            when {
                quote != null && ch == '\\' -> i++
                quote != null && ch == quote -> quote = null
                quote == null && (ch == '"' || ch == '\'') -> quote = ch
                quote == null && ch == ']' -> return i
            }
            i++
        }
        return null
    }

    private fun unescape(s: String): String = s.replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\")

    /** [root] の中の [path] の値。道筋が読めない・たどれないときは null。 */
    fun pick(root: JsonElement, path: String): JsonElement? {
        val steps = parse(path) ?: return null
        var cur: JsonElement = root
        for (s in steps) {
            cur = when (s) {
                is Step.Key -> (cur as? JsonObject)?.get(s.name) ?: return null
                is Step.Index -> {
                    val arr = cur as? JsonArray ?: return null
                    val i = if (s.index < 0) arr.size + s.index else s.index
                    arr.getOrNull(i) ?: return null
                }
            }
        }
        return cur
    }

    /** 値を数に。文字の数（Home Assistant の "23.4"）・真偽（true = 1）も数にする。数にならなければ null。 */
    fun number(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        p.booleanOrNull?.let { if (!p.isString) return if (it) 1.0 else 0.0 }
        return p.doubleOrNull ?: p.content.trim().replace(",", "").replace("_", "").toDoubleOrNull()
    }

    /** 値を文字に（物・配列は短い JSON のまま）。 */
    fun text(e: JsonElement?): String? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> e.content
        else -> e.toString()
    }

    /** 道筋を 1 つ足す（JSON の木で葉を押したとき）。キーが識別子の形でなければ引用符で囲む。 */
    fun join(parent: String, step: Step): String = when (step) {
        is Step.Index -> "$parent[${step.index}]"
        is Step.Key -> if (IDENT.matches(step.name)) {
            if (parent.isEmpty()) step.name else "$parent.${step.name}"
        } else {
            "$parent[\"${step.name.replace("\\", "\\\\").replace("\"", "\\\"")}\"]"
        }
    }

    private val IDENT = Regex("[A-Za-z_\\p{L}][A-Za-z0-9_\\-\\p{L}]*")
}

// ---------------------------------------------------------------- 応答の読み取り

object WidgetRead {

    /**
     * 応答の本文 [body] を [def] の見せ方で読む。数・文字・一覧のどれかが取れなければ [WidgetPush.Bad]（理由つき）。
     * [now] は取った時刻。履歴は呼び出し側が足す。
     */
    fun parse(def: WidgetDef, body: String, now: Long): WidgetPush {
        val root = runCatching { Http.json.parseToJsonElement(body) }.getOrNull()
            ?: return if (def.shownStyle == "text" && def.path.isBlank()) {
                WidgetPush.Value(WidgetData(def.id, def.dataKey, text = body.trim().take(MAX_TEXT), unit = def.unit.ifBlank { null }, updatedAt = now))
            } else {
                WidgetPush.Bad(L("JSON として読めません", "Not valid JSON"))
            }
        return parse(def, root, now)
    }

    fun parse(def: WidgetDef, root: JsonElement, now: Long): WidgetPush {
        if (JsonPick.parse(def.path) == null) return WidgetPush.Bad(L("値の場所の書き方が読めません: ${def.path}", "Can't read the value path: ${def.path}"))
        val picked = JsonPick.pick(root, def.path)
            ?: return WidgetPush.Bad(L("値の場所が見つかりません: ${def.path.ifBlank { "（全体）" }}", "Value path not found: ${def.path.ifBlank { "(root)" }}"))
        val unit = def.unitPath.takeIf { it.isNotBlank() }?.let { JsonPick.text(JsonPick.pick(root, it)) }?.takeIf { it.isNotBlank() }
            ?: def.unit.ifBlank { null }
        val base = WidgetData(def.id, def.dataKey, unit = unit, updatedAt = now)
        return when (def.shownStyle) {
            "number", "gauge" -> {
                val n = JsonPick.number(picked)
                    ?: return WidgetPush.Bad(L("数ではありません: ", "Not a number: ") + (JsonPick.text(picked) ?: "null").take(40))
                WidgetPush.Value(base.copy(number = n * def.scale))
            }
            "text" -> WidgetPush.Value(base.copy(text = (JsonPick.text(picked) ?: "—").take(MAX_TEXT)))
            "list" -> WidgetPush.Value(base.copy(items = items(def, picked)))
            else -> WidgetPush.Bad(L("この見せ方では読めません", "Can't read with this style"))
        }
    }

    /**
     * 一覧の行。配列なら各要素（物なら [WidgetDef.labelKey] と [WidgetDef.valueKey]、それ以外はそのまま）、
     * 物ならキーと値の組。値が数ならしきい値で色を決める。
     */
    fun items(def: WidgetDef, e: JsonElement): List<WidgetItem> {
        fun row(label: String, value: JsonElement?): WidgetItem {
            val n = JsonPick.number(value)?.let { it * def.scale }
            val shown = if (n != null) WidgetFormat.withUnit(WidgetFormat.number(n, def.decimals), def.unit) else WidgetFormat.element(value, def.decimals, def.scale)
            return WidgetItem(label.take(60), shown.take(60), WidgetLevel.of(n, def))
        }
        return when (e) {
            is JsonArray -> e.take(MAX_WIDGET_ITEMS).mapIndexed { i, el ->
                if (el is JsonObject) {
                    val label = def.labelKey.takeIf { it.isNotBlank() }?.let { JsonPick.text(JsonPick.pick(el, it)) }
                        ?: el.entries.firstOrNull()?.let { JsonPick.text(it.value) } ?: "${i + 1}"
                    val value = def.valueKey.takeIf { it.isNotBlank() }?.let { JsonPick.pick(el, it) }
                        ?: el.entries.drop(1).firstOrNull()?.value
                    row(label, value)
                } else {
                    row("${i + 1}", el)
                }
            }
            is JsonObject -> e.entries.take(MAX_WIDGET_ITEMS).map { (k, v) -> row(k, v) }
            else -> listOf(row(def.label, e))
        }
    }

    /**
     * 外から送られた本文。[def] に値の場所があれば URL から取るときと同じに読む（Home Assistant の状態をそのまま送れる）。
     * 無ければ簡単な形: `{"value": 23.4}`・`{"text": "…"}`・`{"items": [{"label","value","level"}]}`（`unit` も可）、
     * ただの数・文字。
     */
    fun push(def: WidgetDef, body: String, now: Long): WidgetPush {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return WidgetPush.Bad(L("本文が空です", "The body is empty"))
        val root = runCatching { Http.json.parseToJsonElement(trimmed) }.getOrNull()
        if (root != null && def.path.isNotBlank()) return parse(def, root, now)
        val base = WidgetData(def.id, def.dataKey, unit = def.unit.ifBlank { null }, updatedAt = now)
        if (root is JsonObject && (root.containsKey("value") || root.containsKey("text") || root.containsKey("items"))) {
            val unit = JsonPick.text(root["unit"])?.takeIf { it.isNotBlank() }?.take(16) ?: base.unit
            val items = (root["items"] as? JsonArray)?.take(MAX_WIDGET_ITEMS)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val label = JsonPick.text(o["label"]) ?: JsonPick.text(o["name"]) ?: return@mapNotNull null
                val raw = o["value"]
                val n = JsonPick.number(raw)?.let { it * def.scale }
                val value = if (n != null) WidgetFormat.withUnit(WidgetFormat.number(n, def.decimals), JsonPick.text(o["unit"]) ?: def.unit) else WidgetFormat.element(raw)
                val level = (o["level"] as? JsonPrimitive)?.content?.let(::levelOf) ?: WidgetLevel.of(n, def)
                WidgetItem(label.take(60), value.take(60), level)
            }.orEmpty()
            val value = root["value"]
            val n = JsonPick.number(value)
            return WidgetPush.Value(
                base.copy(
                    number = n?.let { it * def.scale },
                    text = (JsonPick.text(root["text"]) ?: value?.takeIf { n == null }?.let(JsonPick::text))?.take(MAX_TEXT),
                    unit = unit,
                    items = items,
                ),
            )
        }
        if (root is JsonPrimitive && root !is JsonNull) {
            val n = JsonPick.number(root)
            return WidgetPush.Value(if (n != null && !root.isString) base.copy(number = n * def.scale) else base.copy(text = root.content.take(MAX_TEXT)))
        }
        if (root != null) return WidgetPush.Bad(L("value・text・items のどれかを送ってください", "Send value, text or items"))
        // JSON でなければ、数なら数、それ以外は文字
        val n = trimmed.replace(",", "").toDoubleOrNull()
        return WidgetPush.Value(if (n != null) base.copy(number = n * def.scale) else base.copy(text = trimmed.take(MAX_TEXT)))
    }

    /** 送られた行の段（"ok" / "warn" / "alert"、または 0〜2）。 */
    fun levelOf(s: String): Int = when (s.trim().lowercase()) {
        "warn", "warning", "1" -> WidgetLevel.WARN
        "alert", "error", "critical", "bad", "2" -> WidgetLevel.ALERT
        else -> WidgetLevel.OK
    }

    const val MAX_TEXT = 500
}

// ---------------------------------------------------------------- ひな形

/** 設定で選べるひな形。[apply] はいまの設定（[WidgetDef.id] は変えない）に当てる。 */
class WidgetTemplate(val id: String, private val ja: String, private val en: String, val apply: (WidgetDef) -> WidgetDef) {
    val label: String get() = L(ja, en)
}

val WIDGET_TEMPLATES: List<WidgetTemplate>
    get() = listOf(
        WidgetTemplate("homeassistant", "Home Assistant のセンサー", "Home Assistant sensor") {
            it.copy(
                title = it.title.ifBlank { L("室温", "Room temperature") },
                source = "http", style = "number",
                url = "http://homeassistant.local:8123/api/states/sensor.living_temperature",
                headerName = "Authorization",
                path = "state", unitPath = "attributes.unit_of_measurement", unit = "",
                intervalMin = 1, decimals = 1, warnAt = 28.0, alertAt = 31.0, below = false,
            )
        },
        WidgetTemplate("github", "GitHub のスター数", "GitHub stars") {
            it.copy(
                title = it.title.ifBlank { L("スター", "Stars") },
                source = "http", style = "number",
                url = "https://api.github.com/repos/infinc/dashboard-for-android",
                headerName = "", path = "stargazers_count", unitPath = "", unit = "★",
                intervalMin = 30, decimals = 0, warnAt = null, alertAt = null,
            )
        },
        WidgetTemplate("fx", "為替（米ドル → 円）", "Exchange rate (USD → JPY)") {
            it.copy(
                title = it.title.ifBlank { "USD/JPY" },
                source = "http", style = "number",
                url = "https://api.frankfurter.app/latest?from=USD&to=JPY",
                headerName = "", path = "rates.JPY", unitPath = "", unit = L("円", "JPY"),
                intervalMin = 60, decimals = 2, warnAt = null, alertAt = null,
            )
        },
        WidgetTemplate("openmeteo", "Open-Meteo の値（地点の湿度）", "Open-Meteo value (humidity here)") {
            it.copy(
                title = it.title.ifBlank { L("湿度", "Humidity") },
                source = "http", style = "gauge",
                url = "https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&current=relative_humidity_2m",
                headerName = "", path = "current.relative_humidity_2m", unitPath = "current_units.relative_humidity_2m", unit = "%",
                intervalMin = 15, decimals = 0, min = 0.0, max = 100.0, warnAt = 30.0, alertAt = 20.0, below = true,
            )
        },
        WidgetTemplate("monitor", "自宅サーバーの監視", "Home server monitor") {
            it.copy(
                title = it.title.ifBlank { L("サーバー", "Servers") },
                source = "monitor", style = "monitor", intervalMin = 1, notify = true,
                targets = it.targets.ifEmpty {
                    listOf(WidgetTarget("Google", "https://www.google.com"), WidgetTarget(L("ルーター", "Router"), "tcp://192.168.1.1:80"))
                },
            )
        },
        WidgetTemplate("push", "外から送る（数値）", "Pushed from outside (number)") {
            it.copy(
                title = it.title.ifBlank { L("電力", "Power") },
                source = "push", style = "number", url = "", headerName = "", path = "", unitPath = "", unit = "W",
                decimals = 0, warnAt = 1200.0, alertAt = 1500.0, below = false,
            )
        },
    )

/** ひな形 1 つ（Web の設定画面へ渡す形。[def] の id・見出しは使う側が自分のものに置き換える）。 */
@Serializable
data class WidgetTemplateChoice(val id: String, val label: String, val def: WidgetDef)

// ---------------------------------------------------------------- 設定の整え

/** 保存・読み込みのときに、自作カードの設定を形の合う値にそろえる（ConfigStore と SettingsController から）。 */
object WidgetSanitize {

    private val HEADER_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}")

    fun config(w: WidgetsConfig): WidgetsConfig = w.copy(
        items = WIDGET_IDS.map { id -> def(w.items.firstOrNull { it.id == id } ?: WidgetDef(id)) },
        pushToken = w.pushToken?.takeIf { it.isNotBlank() },
    )

    fun def(d: WidgetDef): WidgetDef {
        fun finite(v: Double?) = v?.takeIf { it.isFinite() }
        val min = finite(d.min) ?: 0.0
        val max = finite(d.max)?.takeIf { it > min } ?: (min + 100.0)
        val headerName = d.headerName.trim().takeIf { HEADER_NAME.matches(it) } ?: ""
        return d.copy(
            title = d.title.trim().take(30),
            source = d.source.takeIf { it in WIDGET_SOURCES } ?: "http",
            style = d.style.takeIf { it in WIDGET_STYLES } ?: "number",
            url = d.url.trim().take(2000).takeIf { it.isEmpty() || it.startsWith("https://", true) || it.startsWith("http://", true) } ?: "",
            headerName = headerName,
            headerValue = d.headerValue?.trim()?.takeIf { headerName.isNotEmpty() && it.isNotEmpty() && it.none { c -> c == '\r' || c == '\n' } }?.take(4096),
            path = d.path.trim().take(200),
            labelKey = d.labelKey.trim().take(100),
            valueKey = d.valueKey.trim().take(100),
            unitPath = d.unitPath.trim().take(200),
            unit = d.unit.trim().take(16),
            decimals = d.decimals.coerceIn(-1, 6),
            scale = finite(d.scale)?.takeIf { it != 0.0 } ?: 1.0,
            min = min,
            max = max,
            warnAt = finite(d.warnAt),
            alertAt = finite(d.alertAt),
            tone = d.tone.takeIf(Tones::isKnown) ?: "",
            intervalMin = WIDGET_INTERVALS.minBy { kotlin.math.abs(it - d.intervalMin) },
            targets = d.targets
                .map { WidgetTarget(it.name.trim().take(40), it.url.trim().take(500)) }
                .filter { t -> listOf("https://", "http://", "tcp://").any { t.url.startsWith(it, ignoreCase = true) } }
                .take(MAX_WIDGET_TARGETS),
        )
    }

    /**
     * 「全て保存」の自作カードを当てる。ヘッダーの値は書き込み専用（null は変えない・空文字は消す）。
     * 送り先のホストを変えたときは、保存済みの値を新しい送り先へ送らないよう消す（入れ直してもらう）。ヘッダーの名前を空にしても消す。
     * 送られなかったカードはそのまま。
     */
    fun patch(current: WidgetsConfig, patch: WidgetsPatch): WidgetsConfig = config(
        current.copy(
            items = WIDGET_IDS.map { id ->
                val before = current.def(id)
                val next = patch.items.firstOrNull { it.id == id } ?: return@map before
                val header = when {
                    next.headerName.isBlank() -> null
                    next.headerValue == null -> before.headerValue.takeIf { WidgetRepository.hostOf(next.url) == WidgetRepository.hostOf(before.url) }
                    next.headerValue.isBlank() -> null
                    else -> next.headerValue.trim()
                }
                next.copy(id = id, headerValue = header)
            },
        ),
    )

    /**
     * 「監視する先」の行（「名前 URL」か URL だけ）を読む。アプリと Web の設定画面で同じ形。
     */
    fun parseTargets(text: String): List<WidgetTarget> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        val at = Regex("(?i)(https?|tcp)://").find(line)?.range?.first ?: return@map WidgetTarget(line, "")
        WidgetTarget(line.substring(0, at).trim(), line.substring(at).trim())
    }

    fun targetLines(targets: List<WidgetTarget>): String = targets.joinToString("\n") { if (it.name.isBlank()) it.url else "${it.name} ${it.url}" }
}

/** URL の中の `{lat}`・`{lon}`・`{lang}` を「場所」と言語で置き換える（ひな形の Open-Meteo など）。 */
fun widgetUrl(url: String, location: LocationConfig, lang: String): String =
    url.trim()
        .replace("{lat}", location.latitude.toString())
        .replace("{lon}", location.longitude.toString())
        .replace("{lang}", lang)
