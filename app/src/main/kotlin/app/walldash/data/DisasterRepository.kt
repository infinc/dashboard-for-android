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
 * 気象庁の警報ページが持つ対応表を [WARNING_KINDS] に写してある。
 * 未知のコードは名前を推測せずコードのまま出す。
 *
 * 警報の取得先は `warning/data/r8/{府県コード}.json`。
 * 以前の `warning/data/warning/{府県コード}.json` は気象庁が更新を止めており
 * （2026-09-21 時点で全国が 2026-05-28 のまま）、古い内容をそのまま壁に出してしまうので使わない。
 *
 * 警報・注意報の対象は、天気の地点（[LocationConfig]）がある**市町村**。
 * 緯度経度から気象庁の市町村区分を [JmaAreaLocator] で決め、その親をたどって府県予報区も決める。
 * 天気と防災で別々に地域を選ばせると、地点を変えたときに片方だけ古いまま残るため。
 */
class DisasterRepository(
    context: Context,
    private val client: HttpClient,
    private val configStore: ConfigStore,
) {

    private val cacheFile = File(context.filesDir, "disaster-cache.json")
    private val areaFile = File(context.filesDir, "disaster-area.json")
    private val locator = JmaAreaLocator(client)

    @Volatile
    var state: DisasterState = DisasterState()
        private set

    /** 天気の地点から決めた市町村。地点が変わるまで使い回す（再起動をまたいでも）。 */
    @Volatile
    private var resolved: ResolvedArea? = null

    private var lastAttemptAt = 0L
    private var consecutiveFailures = 0

    init {
        runCatching {
            if (cacheFile.exists()) state = Http.json.decodeFromString(cacheFile.readText())
        }.onFailure { Log.w(TAG, "防災キャッシュの読み込みに失敗", it) }
        runCatching {
            if (areaFile.exists()) resolved = Http.json.decodeFromString(areaFile.readText())
        }.onFailure { Log.w(TAG, "市町村の読み込みに失敗", it) }
    }

    suspend fun refreshIfDue() {
        val config = configStore.get()
        if (!config.disaster.enabled) return
        val now = System.currentTimeMillis()
        val due = if (consecutiveFailures == 0) INTERVAL_MS else backoffMs()
        // 天気の地点を変えたら、次の定期取得を待たずに対象の市町村を決め直す。
        // 失敗が続いている間は間隔を守る（通信できないまま 15 秒ごとに叩かないため）。
        val moved = resolved?.matches(config.location) != true && consecutiveFailures == 0
        if (!moved && now - lastAttemptAt < due) return
        refreshNow()
    }

    suspend fun refreshNow() {
        lastAttemptAt = System.currentTimeMillis()
        val config = configStore.get()
        try {
            val area = resolveArea(config.location)
            val docs: List<WarningDocDto> =
                if (area.found) client.get("$BASE/warning/data/r8/${area.officeCode}.json").body()
                else emptyList()
            val quakes: List<QuakeDto> = client.get("$BASE/quake/data/list.json").body()

            /*
             * 1 つの県ぶんが「大雨」「土砂災害」「風」「波」「雷」…と別々の文書で届く。
             * 各文書の class20Items（市町村ごとの行）から、天気の地点の市町村の行だけを拾って束ねる。
             *
             * 一次細分区域（北部・南部など、class10Items）は使わない。区域の中の
             * どこか 1 か所に出ていれば区域全体に出るので、自分の市町村より強く出ることがある
             * （実際に、市町村は強風注意報なのに区域では暴風警報と出ていた）。
             */
            val kinds = LinkedHashSet<String>()
            val issuing = mutableListOf<Pair<WarningDocDto, Int>>()
            for (doc in docs) {
                val row = doc.warning?.class20Items?.firstOrNull { it.areaCode == area.code } ?: continue
                // 解除されたものと、そもそも発表が無い種別（code が付かない）は除く
                val live = row.kinds
                    .filter { it.code != null && it.status != "解除" }
                    .map { kindLabel(it.code) }
                if (live.isEmpty()) continue
                kinds += live
                issuing += doc to live.maxOf(::kindRank)
            }

            val active = if (kinds.isEmpty()) emptyList() else listOf(
                WarningArea(
                    code = area.code.orEmpty(),
                    name = area.name.orEmpty(),
                    count = kinds.size,
                    kinds = kinds.toList(),
                    // 知らないコードも赤にする。実際にレベル４の大雨危険警報（43）が
                    // 表に無く、橙の「コード43」で出ていた。誤るなら強い側に倒す。
                    severe = kinds.any { it.contains("警報") || it.startsWith(UNKNOWN_KIND_PREFIX) },
                )
            )

            /*
             * 見出しは文書ごとにあるが、壁に出すのは 1 本だけにする。
             * 5 本つなぐと 3 行を超えてカードから溢れ、下の種別の行が押し出された。
             *
             * この市町村に出ている文書のうち、段階がいちばん高いもの、同じ段階なら新しいもの。
             * 県全体の最新を選ぶと段階の低い方の本文が出ることがある（土砂災害がレベル４の日に、
             * 同時刻に出た大雨の本文が選ばれていた）。
             * 発表時刻は同じ気象台の JST 表記なので、文字列の比較で新旧が決まる。
             */
            val headline = issuing
                .sortedWith(
                    compareByDescending<Pair<WarningDocDto, Int>> { it.second }
                        .thenByDescending { it.first.reportDatetime.orEmpty() },
                )
                .firstNotNullOfOrNull { it.first.headlineText?.trim()?.takeIf(String::isNotEmpty) }
            val reportedAt = docs.mapNotNull { it.reportDatetime }.maxOrNull()

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
                officeName = area.officeName,
                areaName = area.name.takeIf { area.found },
                headline = headline,
                reportedAt = reportedAt,
                activeAreas = active,
                tsunami = tsunami,
                typhoons = typhoons,
                volcanoes = volcanoes,
                quakes = quakes
                    .filter { meetsThreshold(it.maxIntensity, config.disaster.minIntensity) }
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

    // ------------------------------------------------------------ 対象の市町村

    /**
     * 天気の地点の市町村と、それが属する府県予報区。
     * 地点が前回と同じなら決め直さない（見つからなかった結果も覚えておく）。
     */
    private suspend fun resolveArea(location: LocationConfig): ResolvedArea {
        resolved?.let { if (it.matches(location)) return it }

        val found = locator.locate(location.latitude, location.longitude)
        val office = found?.let { officeOf(it.code) }
        val next = ResolvedArea(
            latitude = location.latitude,
            longitude = location.longitude,
            code = found?.code.takeIf { office != null },
            name = found?.name.takeIf { office != null },
            officeCode = office?.first,
            officeName = office?.second,
        )
        if (!next.found) Log.w(TAG, "天気の地点から市町村を決められない（国外の地点など）")
        resolved = next
        runCatching { areaFile.writeText(Http.json.encodeToString(next)) }
        return next
    }

    /** 市町村 → 二次細分の中間 → 一次細分 → 府県予報区、と area.json の親をたどる。 */
    private suspend fun officeOf(class20: String): Pair<String, String>? {
        val a: AreaDto = client.get("$BASE/common/const/area.json").body()
        val class15 = a.class20s[class20]?.parent ?: return null
        val class10 = a.class15s[class15]?.parent ?: return null
        val office = a.class10s[class10]?.parent ?: return null
        val name = a.offices[office]?.name ?: return null
        return office to name
    }

    /** 見出しを選ぶときの段階。名前は [WARNING_KINDS] の表記から読む。 */
    private fun kindRank(label: String): Int = when {
        label.contains("特別警報") -> 5
        label.contains("危険警報") -> 4
        label.contains("警報") || label.startsWith(UNKNOWN_KIND_PREFIX) -> 3
        else -> 2
    }

    /**
     * 区域に並べる種別の名前。どの文書（大雨・土砂災害・風…）から来たコードも同じ表で引く。
     *
     * 以前は土砂災害の文書（VPWW56）だけ段階を捨てて「土砂災害」と出していた。
     * 対応表が手元に無かったための措置だったが、注意報もレベル４の危険警報も同じ表示になり、
     * 段階が上がっても種別名が変わらないので通知音も鳴らなかった。
     */
    private fun kindLabel(code: String?): String =
        WARNING_KINDS[code] ?: "$UNKNOWN_KIND_PREFIX$code"

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

        /** 対応表に無いコードの表示（"コード43" など）。赤で出す判定にも使う。 */
        const val UNKNOWN_KIND_PREFIX = "コード"

        /**
         * 警報・注意報の種別コード → 名称。
         *
         * 気象庁は名称テーブルを JSON では公開していないので、気象庁の警報ページ
         * （`bosai/warning/`）のスクリプトが持つ対応表を写した（2026-09-21 確認、全 33 件）。
         * 名前もページの表示に合わせてある。
         *
         * - 大雨・土砂災害・高潮は警戒レベル付きの名前で、レベル４に「危険警報」（4x）がある
         * - 土砂災害（09/29/39/49）も他の種別と同じ表に載っている。文書ごとに別の体系ではない
         * - 洪水（04/18）とその他の注意報（27）はこの表に無い（ページでは河川ごとの氾濫情報を
         *   別の表で扱っている）
         *
         * 知らないコードが来たときは推測せず "コードNN" と出す。誤った種別名を
         * 壁に出すより、コードのまま出して調べられる方がましなため。
         */
        val WARNING_KINDS = mapOf(
            // 大雨・土砂災害・高潮は警戒レベル付き
            "10" to "レベル２大雨注意報", "03" to "レベル３大雨警報",
            "43" to "レベル４大雨危険警報", "33" to "レベル５大雨特別警報",
            "29" to "レベル２土砂災害注意報", "09" to "レベル３土砂災害警報",
            "49" to "レベル４土砂災害危険警報", "39" to "レベル５土砂災害特別警報",
            "19" to "レベル２高潮注意報", "08" to "レベル３高潮警報",
            "48" to "レベル４高潮危険警報", "38" to "レベル５高潮特別警報",
            // それ以外は段階なし
            "15" to "強風注意報", "05" to "暴風警報", "35" to "暴風特別警報",
            "13" to "風雪注意報", "02" to "暴風雪警報", "32" to "暴風雪特別警報",
            "12" to "大雪注意報", "06" to "大雪警報", "36" to "大雪特別警報",
            "16" to "波浪注意報", "07" to "波浪警報", "37" to "波浪特別警報",
            "14" to "雷注意報", "17" to "融雪注意報", "20" to "濃霧注意報",
            "21" to "乾燥注意報", "22" to "なだれ注意報", "23" to "低温注意報",
            "24" to "霜注意報", "25" to "着氷注意報", "26" to "着雪注意報",
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

    /** [resolveArea] の結果。どの地点について決めたかも持ち、地点が変われば決め直す。 */
    @Serializable
    private data class ResolvedArea(
        val latitude: Double,
        val longitude: Double,
        /** 気象庁の市町村区分コード（area.json の class20s）。見つからなければ null。 */
        val code: String? = null,
        val name: String? = null,
        val officeCode: String? = null,
        val officeName: String? = null,
    ) {
        val found: Boolean get() = code != null && officeCode != null

        fun matches(location: LocationConfig): Boolean =
            latitude == location.latitude && longitude == location.longitude
    }

    // ------------------------------------------------------------ DTO

    /**
     * 警報・注意報の 1 文書。
     *
     * 応答は文書の配列で、[dataTypeCode] が種類を表す
     * （VPWW55=大雨など / VPWW56=土砂災害 / VPWW58=風 / VPWW59=波 / VPWW61=雷）。
     * 使っていないが、どの文書から来た値かをログで追えるように残す。
     */
    @Serializable
    private data class WarningDocDto(
        val reportDatetime: String? = null,
        val headlineText: String? = null,
        val dataTypeCode: String? = null,
        val warning: WarningBodyDto? = null,
    )

    /** class10Items（北部・南部などの一次細分区域）も届くが、市町村の行だけを使う。 */
    @Serializable
    private data class WarningBodyDto(
        val class20Items: List<WarningAreaDto> = emptyList(),
    )

    @Serializable
    private data class WarningAreaDto(
        val areaCode: String = "",
        val kinds: List<WarningKindDto> = emptyList(),
    )

    @Serializable
    private data class WarningKindDto(val code: String? = null, val status: String? = null)

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
    private data class AreaNameDto(val name: String = "", val parent: String? = null)
}
