package app.dashboard.data

import android.content.Context
import android.util.Log
import app.dashboard.i18n.L
import app.dashboard.i18n.Lang
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 電車の運行情報。公共交通オープンデータセンター（ODPT）の API から 5 分ごとに取る。
 *
 * - api.odpt.org（本番）… 東京メトロ・都営・私鉄など。開発者サイトで登録するとトークンがもらえる
 * - api-challenge.odpt.org（チャレンジ）… JR 東日本などはこちらにだけ載っている。チャレンジの参加登録で別のトークンになる
 *
 * どちらか一方のトークンだけでも動く。路線名と色は odpt:Railway から取り、1 日 1 回だけ読み直す（filesDir/train-railways.json）。
 */
class TrainRepository(context: Context, private val client: HttpClient, private val configStore: ConfigStore) {

    private val catalogFile = File(context.filesDir, "train-railways.json")

    @Volatile
    var state: TrainState = TrainState()
        private set

    @Volatile
    private var catalog: Catalog = runCatching {
        if (catalogFile.exists()) Http.json.decodeFromString<Catalog>(catalogFile.readText()) else Catalog()
    }.getOrDefault(Catalog())

    private var lastAttemptAt = 0L

    /** 設定画面で選ぶための路線の一覧（事業者ごと・名前順）。 */
    fun railwayChoices(): List<RailwayChoice> = catalog.railways
        .map { RailwayChoice(it.id, if (Lang.en) it.titleEn ?: it.title else it.title, it.operator?.let(::operatorName) ?: operatorFallback(it.operator)) }
        .sortedWith(compareBy({ it.operator }, { it.title }))

    suspend fun refreshIfDue() {
        val c = configStore.get().train
        if (!c.enabled || sources(c).isEmpty()) return
        if (System.currentTimeMillis() - lastAttemptAt < INTERVAL_MS) return
        refreshNow()
    }

    suspend fun refreshNow() {
        lastAttemptAt = System.currentTimeMillis()
        val c = configStore.get().train
        val sources = sources(c)
        if (sources.isEmpty()) return
        val errors = mutableListOf<String>()
        if (System.currentTimeMillis() - catalog.fetchedAt > CATALOG_MS || catalog.railways.isEmpty()) {
            runCatching { loadCatalog(sources) }.onFailure { errors += L("路線一覧: ", "Line list: ") + it.message }
        }
        val infos = mutableListOf<Info>()
        sources.forEach { (base, token) ->
            runCatching { infos += fetch(base, "odpt:TrainInformation", token).mapNotNull(::toInfo) }
                .onFailure {
                    Log.w(TAG, "運行情報の取得に失敗: $base", it)
                    errors += (if (base == CHALLENGE) L("チャレンジ", "Challenge") else L("本番", "Production")) + ": " + (it.message ?: L("取得失敗", "failed"))
                }
        }
        if (infos.isEmpty() && errors.isNotEmpty()) {
            state = state.copy(lastError = errors.joinToString(" / "))
            return
        }
        state = TrainState(lines(c.railways, infos), System.currentTimeMillis(), errors.joinToString(" / ").ifEmpty { null })
    }

    /** 選んだ路線があればその順に。無ければ平常でない路線だけ。 */
    private fun lines(selected: List<String>, infos: List<Info>): List<TrainLine> {
        val byRailway = infos.filter { it.railway != null }.associateBy { it.railway }
        val byOperator = infos.filter { it.railway == null }.associateBy { it.operator }
        val railways = catalog.railways.associateBy { it.id }
        // 事業者ごと運行情報が 1 件も来ていない路線は、取得の失敗ではなく配信の対象外（本番の API に JR 東日本などは無い）
        val reporting = infos.mapNotNull { it.operator }.toSet()
        fun line(id: String, info: Info?): TrainLine {
            val r = railways[id]
            val operator = r?.operator ?: operatorOf(id)
            val status = info?.status ?: when {
                info != null -> L("平常運転", "Normal")
                operator !in reporting -> L(NOT_PROVIDED, "Not provided")
                else -> L("情報なし", "No info")
            }
            return TrainLine(
                railway = id,
                title = r?.let { if (Lang.en) it.titleEn ?: it.title else it.title } ?: id.substringAfterLast('.'),
                operator = (r?.operator ?: info?.operator)?.let(::operatorName),
                color = r?.color,
                status = status,
                text = info?.text,
                trouble = info?.trouble == true,
                quiet = info == null,
                stopped = info?.stopped == true,
            )
        }
        if (selected.isNotEmpty()) {
            return selected.map { id -> line(id, byRailway[id] ?: byOperator[railways[id]?.operator ?: operatorOf(id)]) }
        }
        return infos.filter { it.trouble }.map { info ->
            if (info.railway != null) line(info.railway, info) else TrainLine(
                railway = info.operator.orEmpty(),
                title = info.operator?.let(::operatorName) ?: operatorFallback(info.operator),
                status = info.status ?: L("お知らせ", "Notice"),
                text = info.text,
                trouble = true,
                stopped = info.stopped,
            )
        }
    }

    /** 事業者の名前（いまの言語。英語の名前が無ければ日本語）。 */
    private fun operatorName(id: String): String =
        (if (Lang.en) catalog.operatorsEn[id] else null) ?: catalog.operators[id] ?: operatorFallback(id)

    private suspend fun loadCatalog(sources: List<Pair<String, String>>) {
        val railways = mutableListOf<Railway>()
        val operators = mutableMapOf<String, String>()
        val operatorsEn = mutableMapOf<String, String>()
        sources.forEach { (base, token) ->
            fetch(base, "odpt:Railway", token).forEach { e ->
                val o = e.jsonObject
                val id = o.str("owl:sameAs") ?: return@forEach
                railways += Railway(
                    id, o.ja("odpt:railwayTitle") ?: o.str("dc:title") ?: id.substringAfterLast('.'), o.str("odpt:operator"), o.str("odpt:color"),
                    titleEn = o.en("odpt:railwayTitle"),
                )
            }
            runCatching {
                fetch(base, "odpt:Operator", token).forEach { e ->
                    val o = e.jsonObject
                    val id = o.str("owl:sameAs") ?: return@forEach
                    operators[id] = o.ja("odpt:operatorTitle") ?: o.str("dc:title") ?: operatorFallback(id)
                    o.en("odpt:operatorTitle")?.let { operatorsEn[id] = it }
                }
            }
        }
        if (railways.isEmpty()) error(L("路線の一覧が空でした", "The line list was empty"))
        catalog = Catalog(railways.distinctBy { it.id }, operators, System.currentTimeMillis(), operatorsEn)
        runCatching { catalogFile.writeText(Http.json.encodeToString(Catalog.serializer(), catalog)) }
    }

    /** 路線一覧だけを読み直す（設定画面の「路線を読み込む」）。 */
    suspend fun reloadCatalog() {
        val sources = sources(configStore.get().train)
        if (sources.isEmpty()) error(L("トークンを保存してから読み込んでください", "Save a token before loading"))
        loadCatalog(sources)
    }

    private suspend fun fetch(base: String, type: String, token: String): List<JsonElement> {
        val body = client.get(base + type) { parameter("acl:consumerKey", token) }.bodyAsText()
        return Http.json.parseToJsonElement(body).jsonArray
    }

    private fun toInfo(e: JsonElement): Info? {
        val o = e as? JsonObject ?: return null
        val status = o.ja("odpt:trainInformationStatus")
        val text = o.ja("odpt:trainInformationText")
        // 状態が無い・「平常」を含むものは平常。文だけで知らせる事業者もあるので、文に「平常」が無ければ気にかける
        val normal = if (status != null) "平常" in status || status == "通常運転" else text == null || "平常" in text || "通常" in text
        val stopped = status != null && ("見合わせ" in status || "運休" in status)
        // 判定は日本語で行い、出す文はいまの言語で（英語が無い事業者は日本語のまま）
        val shownStatus = if (Lang.en) o.en("odpt:trainInformationStatus") ?: status else status
        val shownText = if (Lang.en) o.en("odpt:trainInformationText") ?: text else text
        return Info(o.str("odpt:operator"), o.str("odpt:railway"), shownStatus?.takeUnless { normal }, shownText, !normal, stopped)
    }

    private fun sources(c: TrainConfig) = buildList {
        c.token?.takeIf { it.isNotBlank() }?.let { add(STANDARD to it) }
        c.challengeToken?.takeIf { it.isNotBlank() }?.let { add(CHALLENGE to it) }
    }

    private fun operatorOf(railwayId: String) = "odpt.Operator:" + railwayId.substringAfter(':').substringBefore('.')

    private fun operatorFallback(id: String?) = id?.substringAfter(':').orEmpty()

    /** 値が {"ja": …, "en": …} の多言語の形でも、ただの文字列でも読む。 */
    private fun JsonObject.ja(key: String): String? = when (val v = this[key]) {
        is JsonObject -> (v["ja"] ?: v["en"])?.jsonPrimitive?.contentOrNull
        is JsonPrimitive -> v.contentOrNull
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** 多言語の形の英語。無ければ null。 */
    private fun JsonObject.en(key: String): String? = ((this[key] as? JsonObject)?.get("en") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private data class Info(val operator: String?, val railway: String?, val status: String?, val text: String?, val trouble: Boolean, val stopped: Boolean = false)

    @Serializable
    private data class Railway(val id: String, val title: String, val operator: String? = null, val color: String? = null, val titleEn: String? = null)

    @Serializable
    private data class Catalog(
        val railways: List<Railway> = emptyList(),
        val operators: Map<String, String> = emptyMap(),
        val fetchedAt: Long = 0,
        val operatorsEn: Map<String, String> = emptyMap(),
    )

    companion object {
        private const val TAG = "TrainRepository"
        private const val STANDARD = "https://api.odpt.org/api/v4/"
        private const val CHALLENGE = "https://api-challenge.odpt.org/api/v4/"
        private const val INTERVAL_MS = 300_000L
        private const val CATALOG_MS = 24L * 3600 * 1000
        /** 選んだ路線の事業者が、持っているトークンの API に運行情報を出していない。 */
        const val NOT_PROVIDED = "配信なし"
    }
}
