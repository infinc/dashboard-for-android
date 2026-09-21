package app.walldash.data

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import kotlin.math.min

/**
 * 気象庁の公開 JSON から警報・注意報、直近の地震 1 件、および
 * 津波・台風・噴火の発表状況を取得する。
 *
 * 警報・地震と、津波・台風・噴火は別々のエンドポイントなので、
 * どれか 1 つが落ちても残りは出せるように個別に例外を受ける。
 *
 * 「いま揺れているか」は防災科研の強震モニタをダッシュボード側で直接出すので、
 * ここで持つのは「直近に起きた 1 件」だけにする。過去の一覧は表示しない。
 *
 * 種別コードの名称テーブルは気象庁が JSON で公開していない（const 配下は 404）ので、
 * 公開資料の対応表を [WARNING_KINDS] に持つ。未知のコードは名前を推測せずコードのまま出す。
 */
class DisasterRepository(
    context: Context,
    private val client: HttpClient,
    private val configStore: ConfigStore,
) {

    private val cacheFile = File(context.filesDir, "disaster-cache.json")

    @Volatile
    var state: DisasterState = DisasterState()
        private set

    /** area.json は 260KB 程度あるので一度だけ取ってメモリに置く。 */
    @Volatile
    private var areaNames: Map<String, String>? = null

    private var lastAttemptAt = 0L
    private var consecutiveFailures = 0

    init {
        runCatching {
            if (cacheFile.exists()) state = Http.json.decodeFromString(cacheFile.readText())
        }.onFailure { Log.w(TAG, "防災キャッシュの読み込みに失敗", it) }
    }

    suspend fun refreshIfDue() {
        val config = configStore.get()
        if (!config.disaster.enabled) return
        val now = System.currentTimeMillis()
        val due = if (consecutiveFailures == 0) INTERVAL_MS else backoffMs()
        if (now - lastAttemptAt < due) return
        refreshNow()
    }

    suspend fun refreshNow() {
        lastAttemptAt = System.currentTimeMillis()
        val config = configStore.get().disaster
        try {
            val names = areaNames ?: loadAreaNames().also { areaNames = it }
            val warning: WarningDto =
                client.get("$BASE/warning/data/warning/${config.officeCode}.json").body()
            val quakes: List<QuakeDto> = client.get("$BASE/quake/data/list.json").body()

            // areaTypes[0] は一次細分区域、[1] は市町村等。
            // 壁掛けの距離で市町村名まで並べても読めないので、一次細分区域だけにする。
            val active = warning.areaTypes.firstOrNull()?.areas.orEmpty()
                .mapNotNull { area ->
                    val live = area.warnings.filter { it.code != null && it.status != "解除" }
                    if (live.isEmpty()) return@mapNotNull null
                    val kinds = live.mapNotNull { it.code }.map { WARNING_KINDS[it] ?: "コード$it" }
                    WarningArea(
                        code = area.code,
                        name = names[area.code] ?: area.code,
                        count = live.size,
                        kinds = kinds,
                        severe = kinds.any { it.contains("警報") },
                    )
                }
                .distinctBy { it.code }

            // 津波・台風・噴火は警報や地震とは別系統。個別に失敗を受けて、
            // 取れなかったものだけ前回の値を残す。
            val tsunami = runCatching { fetchTsunami() }
                .onFailure { Log.w(TAG, "津波情報の取得に失敗", it) }
                .getOrElse { state.tsunami }
            val typhoons = runCatching { fetchTyphoons() }
                .onFailure { Log.w(TAG, "台風情報の取得に失敗", it) }
                .getOrElse { state.typhoons }
            val volcanoes = runCatching { fetchVolcanoes() }
                .onFailure { Log.w(TAG, "噴火情報の取得に失敗", it) }
                .getOrElse { state.volcanoes }

            state = DisasterState(
                available = true,
                officeName = config.officeName,
                headline = warning.headlineText?.takeIf { it.isNotBlank() },
                reportedAt = warning.reportDatetime,
                activeAreas = active,
                tsunami = tsunami,
                typhoons = typhoons,
                volcanoes = volcanoes,
                quakes = quakes
                    .filter { meetsThreshold(it.maxIntensity, config.minIntensity) }
                    // 震源が確定する前の速報は震央地名が空で届く。
                    // 「震度3」だけ出ても場所が分からず壁では役に立たないので落とす。
                    .filter { !it.epicenter.isNullOrBlank() }
                    .map {
                        QuakeInfo(
                            // at = 地震の発生時刻、rdt = 発表時刻。壁に出すのは発生時刻。
                            occurredAt = it.occurredAt ?: it.reportDatetime,
                            epicenter = it.epicenter,
                            magnitude = it.magnitude?.takeIf { m -> m.isNotBlank() && m != "-" },
                            maxIntensity = it.maxIntensity?.takeIf { s -> s.isNotBlank() },
                            title = it.title,
                        )
                    }
                    // 同じ地震で「震源に関する情報」と「震源・震度情報」が二重に来るので、
                    // 発生時刻と震央が同じものはまとめる。
                    .distinctBy { it.occurredAt to it.epicenter }
                    // 壁に出すのは直近の 1 件だけ。
                    .take(1),
                fetchedAt = System.currentTimeMillis(),
                lastError = null,
            )
            consecutiveFailures = 0
            runCatching { cacheFile.writeText(Http.json.encodeToString(state)) }
        } catch (e: Exception) {
            consecutiveFailures += 1
            Log.w(TAG, "防災情報の取得に失敗（${consecutiveFailures}回目）", e)
            state = state.copy(lastError = e.message ?: e::class.java.simpleName)
        }
    }

    // ------------------------------------------------------------ 津波

    /**
     * 津波警報・注意報。
     *
     * 一覧 JSON は津波が無い間はずっと空配列なので、要素の形を実データで確かめられない。
     * 決め打ちの DTO にすると想定外のキー構成で丸ごと落ちるため、JSON のまま受けて
     * 分かるものだけ拾う。題名が取れなくても件数は分かるようにしておく。
     */
    private suspend fun fetchTsunami(): List<TsunamiInfo> {
        val list: JsonArray = client.get("$BASE/tsunami/data/list.json").body()
        return list.mapNotNull { it as? JsonObject }
            .take(3)
            .map { o ->
                TsunamiInfo(
                    title = o.str("ttl") ?: o.str("title"),
                    reportedAt = o.str("rdt") ?: o.str("at") ?: o.str("reportDatetime"),
                )
            }
    }

    // ------------------------------------------------------------ 台風

    /**
     * 発生中の台風。
     *
     * 一覧（targetTc.json）に載っている識別子ごとに詳細 JSON を引く。詳細は配列で、
     * 要素の "part" が文字列("title")だったりオブジェクト({jp,en})だったりと型が揃わない。
     * データクラスに当てはめると落ちるので、JSON のまま読んで必要な値だけ取り出す。
     */
    private suspend fun fetchTyphoons(): List<TyphoonInfo> {
        val targets: List<TargetTcDto> = client.get("$BASE/typhoon/data/targetTc.json").body()
        return targets.take(MAX_TYPHOONS).mapNotNull { target ->
            val id = target.tropicalCyclone?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            runCatching {
                val spec: JsonArray = client.get("$BASE/typhoon/data/$id/specifications.json").body()
                typhoonOf(target, spec)
            }.onFailure { Log.w(TAG, "台風 $id の詳細取得に失敗", it) }.getOrNull()
        }
    }

    private fun typhoonOf(target: TargetTcDto, spec: JsonArray): TyphoonInfo {
        var name: String? = null
        var number: String? = target.typhoonNumber
        var analysis: JsonObject? = null

        for (element in spec) {
            val o = element as? JsonObject ?: continue
            if (o.partName() == "title") {
                name = o.str("name", "jp")
                number = o.str("typhoonNumber") ?: number
            }
            // advancedHours = 0 が「実況」。以降は 12 時間後・24 時間後…の予報。
            if ((o["advancedHours"] as? JsonPrimitive)?.intOrNull == 0) analysis = o
        }

        return TyphoonInfo(
            number = typhoonLabel(number),
            name = name,
            scale = analysis?.str("scale"),
            intensity = analysis?.str("intensity"),
            location = analysis?.str("location"),
            pressureHpa = analysis?.str("pressure"),
            maxWindMps = analysis?.str("maximumWind", "sustained", "m/s"),
            gustMps = analysis?.str("maximumWind", "gust", "m/s"),
            course = analysis?.str("course"),
            speedKmh = analysis?.str("speed", "km/h"),
            reportedAt = target.issue,
        )
    }

    // ------------------------------------------------------------ 噴火

    /**
     * 噴火警報・予報が出ている火山。全国ぶんが 1 つの JSON に入っている。
     *
     * 1 件の中に「対象火山」「対象市町村等」「対象市町村の防災対応等」が並ぶが、
     * 壁に出して意味があるのは火山名と警戒レベルなので「対象火山」だけを見る。
     */
    private suspend fun fetchVolcanoes(): List<VolcanoInfo> {
        val list: List<VolcanoWarningDto> = client.get("$BASE/volcano/data/warning.json").body()
        return list
            .flatMap { entry ->
                entry.volcanoInfos
                    .filter { it.type.contains("対象火山") }
                    .flatMap { info ->
                        info.items.flatMap { item ->
                            item.areas.map { area ->
                                VolcanoInfo(
                                    name = area.name,
                                    level = item.name,
                                    reportedAt = entry.reportDatetime,
                                    severe = isSevereVolcano(item.name),
                                )
                            }
                        }
                    }
            }
            .filter { it.name.isNotBlank() && it.level.isNotBlank() }
            // 同じ火山が複数回出ることがあるので 1 つにまとめ、発表の新しい順に並べる。
            .distinctBy { it.name }
            .sortedByDescending { it.reportedAt ?: "" }
    }

    /** 設定画面の予報区プルダウン用。気象庁の offices をそのまま返す。 */
    suspend fun offices(): List<Office> {
        val dto: AreaDto = client.get("$BASE/common/const/area.json").body()
        return dto.offices.map { (code, v) -> Office(code, v.name) }.sortedBy { it.code }
    }

    private suspend fun loadAreaNames(): Map<String, String> {
        val dto: AreaDto = client.get("$BASE/common/const/area.json").body()
        // 警報 JSON に出てくるのは主に一次細分区域(class10s)。
        // 将来 areaTypes の構成が変わっても名前が出るよう、他の階層も辞書に入れておく。
        return dto.offices.mapValues { it.value.name } +
            dto.class15s.mapValues { it.value.name } +
            dto.class20s.mapValues { it.value.name } +
            dto.class10s.mapValues { it.value.name }
    }

    private fun backoffMs(): Long = min(60_000L shl min(consecutiveFailures - 1, 4), 15 * 60_000L)

    private companion object {
        const val TAG = "DisasterRepository"
        const val BASE = "https://www.jma.go.jp/bosai"
        const val INTERVAL_MS = 5 * 60_000L

        /** 同時に発生する台風は多くても数個。壁に並べて読めるのはこの程度まで。 */
        const val MAX_TYPHOONS = 3

        /**
         * 入れ子の JSON から文字列を 1 つ取り出す。
         * 途中でキーが無い・型が違う場合は null を返し、例外にしない。
         */
        fun JsonObject.str(vararg path: String): String? {
            var current: JsonElement? = this
            for (key in path) current = (current as? JsonObject)?.get(key)
            return (current as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

        /** "part" は文字列のことも {jp, en} のこともある。 */
        fun JsonObject.partName(): String? =
            (this["part"] as? JsonPrimitive)?.contentOrNull ?: str("part", "jp")

        /**
         * 気象庁の台風番号は "2625" のような 4 桁で、下 2 桁が号数（上 2 桁は年）。
         * "2603" は 3 号なので先頭の 0 は落とす。
         */
        fun typhoonLabel(number: String?): String? {
            val n = number?.takeIf { it.length >= 3 } ?: return null
            val serial = n.takeLast(2).trimStart('0').ifEmpty { return null }
            return "台風${serial}号"
        }

        /**
         * 噴火警戒レベル 3 以上、または立ち入りが危険な状態かどうか。
         * 気象庁の表記は全角数字なので、そのまま含まれるかで見る。
         */
        fun isSevereVolcano(level: String): Boolean =
            level.contains("レベル３") || level.contains("レベル４") || level.contains("レベル５") ||
                level.contains("危険") || level.contains("避難")

        /**
         * 警報・注意報の種別コード → 名称。
         *
         * 気象庁は名称テーブルを JSON では公開していないので、公開資料
         * （「気象警報・注意報の種類」）の対応表をここに持つ。
         * 知らないコードが来たときは推測せず "コードNN" と出す。誤った種別名を
         * 壁に出すより、コードのまま出して調べられる方がましなため。
         */
        val WARNING_KINDS = mapOf(
            "02" to "暴風雪警報", "03" to "大雨警報", "04" to "洪水警報",
            "05" to "暴風警報", "06" to "大雪警報", "07" to "波浪警報",
            "08" to "高潮警報",
            "10" to "大雨注意報", "12" to "大雪注意報", "13" to "風雪注意報",
            "14" to "雷注意報", "15" to "強風注意報", "16" to "波浪注意報",
            "17" to "融雪注意報", "18" to "洪水注意報", "19" to "高潮注意報",
            "20" to "濃霧注意報", "21" to "乾燥注意報", "22" to "なだれ注意報",
            "23" to "低温注意報", "24" to "霜注意報", "25" to "着氷注意報",
            "26" to "着雪注意報", "27" to "その他の注意報",
            "32" to "暴風雪特別警報", "33" to "大雨特別警報", "35" to "暴風特別警報",
            "36" to "大雪特別警報", "37" to "波浪特別警報", "38" to "高潮特別警報",
        )

        /**
         * 気象庁の震度表記は "1".."4", "5-", "5+", "6-", "6+", "7"。
         * "5-" は 5.0、"5+" は 5.5 として順序づける。
         */
        fun rank(value: String?): Double {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return -1.0
            val base = v.first().digitToIntOrNull()?.toDouble() ?: return -1.0
            return base + if (v.endsWith("+")) 0.5 else 0.0
        }

        fun meetsThreshold(actual: String?, threshold: String): Boolean {
            val a = rank(actual)
            return a >= 0 && a >= rank(threshold)
        }
    }

    @Serializable
    data class Office(val code: String, val name: String)

    // ------------------------------------------------------------ DTO

    @Serializable
    private data class WarningDto(
        val reportDatetime: String? = null,
        val headlineText: String? = null,
        val areaTypes: List<AreaTypeDto> = emptyList(),
    )

    @Serializable
    private data class AreaTypeDto(val areas: List<AreaEntryDto> = emptyList())

    @Serializable
    private data class AreaEntryDto(
        val code: String = "",
        val warnings: List<WarningEntryDto> = emptyList(),
    )

    @Serializable
    private data class WarningEntryDto(val code: String? = null, val status: String? = null)

    @Serializable
    private data class QuakeDto(
        @SerialName("rdt") val reportDatetime: String? = null,
        @SerialName("at") val occurredAt: String? = null,
        @SerialName("anm") val epicenter: String? = null,
        @SerialName("mag") val magnitude: String? = null,
        @SerialName("maxi") val maxIntensity: String? = null,
        @SerialName("ttl") val title: String? = null,
    )

    @Serializable
    private data class TargetTcDto(
        val tropicalCyclone: String? = null,
        val typhoonNumber: String? = null,
        val category: String? = null,
        val issue: String? = null,
    )

    @Serializable
    private data class VolcanoWarningDto(
        val reportDatetime: String? = null,
        val volcanoInfos: List<VolcanoInfoDto> = emptyList(),
    )

    @Serializable
    private data class VolcanoInfoDto(
        val type: String = "",
        val items: List<VolcanoItemDto> = emptyList(),
    )

    @Serializable
    private data class VolcanoItemDto(
        val name: String = "",
        val code: String? = null,
        val areas: List<VolcanoAreaDto> = emptyList(),
    )

    @Serializable
    private data class VolcanoAreaDto(val name: String = "", val code: String? = null)

    @Serializable
    private data class AreaDto(
        val offices: Map<String, AreaNameDto> = emptyMap(),
        val class10s: Map<String, AreaNameDto> = emptyMap(),
        val class15s: Map<String, AreaNameDto> = emptyMap(),
        val class20s: Map<String, AreaNameDto> = emptyMap(),
    )

    @Serializable
    private data class AreaNameDto(val name: String = "")
}
