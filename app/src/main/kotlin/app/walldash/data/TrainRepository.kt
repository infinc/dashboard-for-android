package app.walldash.data

import android.content.Context
import android.util.Log
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
        .map { RailwayChoice(it.id, it.title, catalog.operators[it.operator] ?: operatorFallback(it.operator)) }
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
            runCatching { loadCatalog(sources) }.onFailure { errors += "路線一覧: ${it.message}" }
        }
        val infos = mutableListOf<Info>()
        sources.forEach { (base, token) ->
            runCatching { infos += fetch(base, "odpt:TrainInformation", token).mapNotNull(::toInfo) }
                .onFailure {
                    Log.w(TAG, "運行情報の取得に失敗: $base", it)
                    errors += "${if (base == CHALLENGE) "チャレンジ" else "本番"}: ${it.message ?: "取得失敗"}"
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
        fun line(id: String, info: Info?): TrainLine {
            val r = railways[id]
            val status = info?.status ?: if (info == null) "情報なし" else "平常運転"
            return TrainLine(
                railway = id,
                title = r?.title ?: id.substringAfterLast('.'),
                operator = (r?.operator ?: info?.operator)?.let { catalog.operators[it] ?: operatorFallback(it) },
                color = r?.color,
                status = status,
                text = info?.text,
                trouble = info?.trouble == true,
            )
        }
        if (selected.isNotEmpty()) {
            return selected.map { id -> line(id, byRailway[id] ?: byOperator[railways[id]?.operator ?: operatorOf(id)]) }
        }
        return infos.filter { it.trouble }.map { info ->
            if (info.railway != null) line(info.railway, info) else TrainLine(
                railway = info.operator.orEmpty(),
                title = catalog.operators[info.operator] ?: operatorFallback(info.operator),
                status = info.status ?: "お知らせ",
                text = info.text,
                trouble = true,
            )
        }
    }

    private suspend fun loadCatalog(sources: List<Pair<String, String>>) {
        val railways = mutableListOf<Railway>()
        val operators = mutableMapOf<String, String>()
        sources.forEach { (base, token) ->
            fetch(base, "odpt:Railway", token).forEach { e ->
                val o = e.jsonObject
                val id = o.str("owl:sameAs") ?: return@forEach
                railways += Railway(id, o.ja("odpt:railwayTitle") ?: o.str("dc:title") ?: id.substringAfterLast('.'), o.str("odpt:operator"), o.str("odpt:color"))
            }
            runCatching {
                fetch(base, "odpt:Operator", token).forEach { e ->
                    val o = e.jsonObject
                    val id = o.str("owl:sameAs") ?: return@forEach
                    operators[id] = o.ja("odpt:operatorTitle") ?: o.str("dc:title") ?: operatorFallback(id)
                }
            }
        }
        if (railways.isEmpty()) error("路線の一覧が空でした")
        catalog = Catalog(railways.distinctBy { it.id }, operators, System.currentTimeMillis())
        runCatching { catalogFile.writeText(Http.json.encodeToString(Catalog.serializer(), catalog)) }
    }

    /** 路線一覧だけを読み直す（設定画面の「路線を読み込む」）。 */
    suspend fun reloadCatalog() {
        val sources = sources(configStore.get().train)
        if (sources.isEmpty()) error("トークンを保存してから読み込んでください")
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
        return Info(o.str("odpt:operator"), o.str("odpt:railway"), status?.takeUnless { normal }, text, !normal)
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

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private data class Info(val operator: String?, val railway: String?, val status: String?, val text: String?, val trouble: Boolean)

    @Serializable
    private data class Railway(val id: String, val title: String, val operator: String? = null, val color: String? = null)

    @Serializable
    private data class Catalog(
        val railways: List<Railway> = emptyList(),
        val operators: Map<String, String> = emptyMap(),
        val fetchedAt: Long = 0,
    )

    private companion object {
        const val TAG = "TrainRepository"
        const val STANDARD = "https://api.odpt.org/api/v4/"
        const val CHALLENGE = "https://api-challenge.odpt.org/api/v4/"
        const val INTERVAL_MS = 300_000L
        const val CATALOG_MS = 24L * 3600 * 1000
    }
}
