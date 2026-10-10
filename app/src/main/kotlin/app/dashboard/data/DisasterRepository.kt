package app.dashboard.data

import android.content.Context
import android.util.Log
import app.dashboard.i18n.L
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
import kotlinx.serialization.json.doubleOrNull
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
            val tsunami = runCatching { fetchTsunami(area) }
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
                officeNameEn = area.officeNameEn,
                areaNameEn = area.nameEn.takeIf { area.found },
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
                            epicenterEn = it.epicenterEn?.takeIf { e -> e.isNotBlank() },
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
     * 津波警報・注意報・予報のうち、いまこの地点に関係するもの。
     *
     * `list.json` は「いま出ている津波情報」ではなく発表の履歴で、期限の切れた古い発表も、同じ地震の続報も並ぶ
     * （2026-10-10 に、9/30 の与那国島近海の津波予報と、10/10 の中米の地震の津波予報の 2 件が並び、カードに 2 つ出ていた）。
     * また 1 つの発表は全国の沿岸（津波予報区）ごとの内容を持ち、海から遠い地点には関係しない。そこで:
     * 1. 同じ地震（`eid`）は最新の発表だけにし、取り消されたものは除く
     * 2. 本文（`{json}`）の `Head.ValidDateTime` を過ぎたものは除く
     * 3. 地点の近くに海岸線のある予報区（[TsunamiCoast.nearby]）の項目のうち、「津波なし」「解除」以外があるものだけ残す
     * 題名は残った項目でいちばん強い種別（例: 「津波予報（若干の海面変動）」）。内陸の地点では何も出ない。
     */
    private suspend fun fetchTsunami(area: ResolvedArea): List<TsunamiInfo> {
        val list: JsonArray = client.get("$BASE/tsunami/data/list.json").body()
        val near = tsunamiAreas(area)
        if (near.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return list.mapNotNull { it as? JsonObject }
            // 発表時刻（JST の同じ書式）の新しい順にして、地震ごとに最新だけ
            .sortedByDescending { it.str("rdt").orEmpty() }
            .distinctBy { it.str("eid") ?: it.str("json") }
            .filter { it.str("ift") != "取消" }
            .take(MAX_TSUNAMI_EVENTS)
            .mapNotNull { o ->
                val file = o.str("json") ?: return@mapNotNull null
                val doc = tsunamiDocs[file] ?: client.get("$BASE/tsunami/data/$file").body<JsonObject>().also { tsunamiDocs[file] = it }
                val valid = doc.str("Head", "ValidDateTime")?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                if (valid != null && valid < now) return@mapNotNull null
                val items = ((doc["Body"] as? JsonObject)?.get("Tsunami") as? JsonObject)
                    ?.let { (it["Forecast"] as? JsonObject)?.get("Item") as? JsonArray }
                    .orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .filter { it.str("Area", "Code") in near }
                    .filter { item ->
                        val kind = item.str("Category", "Kind", "Name").orEmpty()
                        kind.isNotEmpty() && "なし" !in kind && "解除" !in kind
                    }
                val top = items.maxByOrNull { tsunamiRank(it.str("Category", "Kind", "Name").orEmpty()) } ?: return@mapNotNull null
                TsunamiInfo(
                    title = top.str("Category", "Kind", "Name"),
                    reportedAt = o.str("rdt") ?: doc.str("Head", "ReportDateTime"),
                    titleEn = top.str("Category", "Kind", "enName"),
                    area = items.mapNotNull { it.str("Area", "Name") }.distinct().joinToString("・").ifEmpty { null },
                    areaEn = items.mapNotNull { it.str("Area", "enName") }.distinct().joinToString(", ").ifEmpty { null },
                )
            }
            .take(1)
    }

    /** 地点の近くの津波予報区。海岸線（約 115KB）はプロセスで 1 度だけ読み、地点ごとの結果も覚える。 */
    private suspend fun tsunamiAreas(area: ResolvedArea): Set<String> {
        val key = area.latitude to area.longitude
        tsunamiNear?.let { (k, v) -> if (k == key) return v }
        val coasts = tsunamiCoasts ?: TsunamiCoast.parse(client.get("$BASE/common/const/geojson/tsunami.json").body<JsonElement>())
            .also { tsunamiCoasts = it }
        return TsunamiCoast.nearby(coasts, area.latitude, area.longitude).also { tsunamiNear = key to it }
    }

    /** 津波の種別の強さ（大きいほど強い）。 */
    private fun tsunamiRank(kind: String) = when {
        "大津波警報" in kind -> 4
        "津波警報" in kind -> 3
        "注意報" in kind -> 2
        else -> 1
    }

    @Volatile private var tsunamiCoasts: Map<String, List<List<DoubleArray>>>? = null
    @Volatile private var tsunamiNear: Pair<Pair<Double, Double>, Set<String>>? = null
    /** 津波の本文はファイル名ごとに中身が変わらないので覚える（数件だけ）。 */
    private val tsunamiDocs = object : LinkedHashMap<String, JsonObject>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>?) = size > 8
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
        var nameEn: String? = null
        var number: String? = target.typhoonNumber
        var analysis: JsonObject? = null

        for (element in spec) {
            val o = element as? JsonObject ?: continue
            if (o.partName() == "title") {
                name = o.str("name", "jp")
                nameEn = o.str("name", "en")
                number = o.str("typhoonNumber") ?: number
            }
            // advancedHours = 0 が「実況」。以降は 12 時間後・24 時間後…の予報。
            if ((o["advancedHours"] as? JsonPrimitive)?.intOrNull == 0) analysis = o
        }

        return TyphoonInfo(
            id = target.tropicalCyclone,
            number = typhoonLabel(number),
            name = name,
            nameEn = nameEn,
            lat = analysis?.degree(0),
            lon = analysis?.degree(1),
            // 大きさ・強さが付かない台風は "-" が入っている
            scale = analysis?.str("scale")?.takeIf { it != "-" },
            intensity = analysis?.str("intensity")?.takeIf { it != "-" },
            location = analysis?.str("location"),
            pressureHpa = analysis?.str("pressure"),
            maxWindMps = analysis?.str("maximumWind", "sustained", "m/s"),
            gustMps = analysis?.str("maximumWind", "gust", "m/s"),
            course = analysis?.str("course"),
            speedKmh = analysis?.str("speed", "km/h"),
            reportedAt = target.issue,
        )
    }

    /**
     * 台風の進路図（全画面）の材料。開いたときにだけ取る（壁に出している間の定期取得には入れない）。
     *
     * forecast.json … 経路（"track" の中の "typhoon" / "preTyphoon"）、実況の強風域・暴風域、予報円の中心と半径（メートル）
     * specifications.json … 各時刻の位置の表現・気圧・風速・進路・暴風（警戒）域の半径（km）
     * どちらも要素ごとに形が違う（"part" が文字列だったりオブジェクトだったり）ので、JSON のまま読む。
     */
    suspend fun typhoonTrack(id: String): TyphoonTrack {
        require(id.matches(Regex("[A-Za-z0-9]+"))) { L("台風の識別子が正しくありません", "Invalid typhoon identifier") }
        val forecast: JsonArray = client.get("$BASE/typhoon/data/$id/forecast.json").body()
        val spec: JsonArray = runCatching { client.get("$BASE/typhoon/data/$id/specifications.json").body<JsonArray>() }
            .getOrDefault(JsonArray(emptyList()))
        val specs = spec.mapNotNull { it as? JsonObject }
        val title = specs.firstOrNull { it.partName() == "title" } ?: forecast.mapNotNull { it as? JsonObject }.firstOrNull { it.partName() == "title" }
        val byHours = specs.mapNotNull { o -> (o["advancedHours"] as? JsonPrimitive)?.intOrNull?.let { it to o } }.toMap()

        var track = emptyList<LatLon>()
        var pre = emptyList<LatLon>()
        var gale: Circle? = null
        var storm: Circle? = null
        val points = mutableListOf<TyphoonPoint>()
        for (element in forecast) {
            val o = element as? JsonObject ?: continue
            val hours = (o["advancedHours"] as? JsonPrimitive)?.intOrNull ?: continue
            val center = o.latLon("center") ?: continue
            val s = byHours[hours]
            if (hours == 0) {
                o.obj("track")?.let { t ->
                    track = t.latLons("typhoon")
                    pre = t.latLons("preTyphoon")
                }
                gale = o.obj("galeWarningArea")?.let { g -> g.latLon("center")?.let { c -> g.num("radius")?.let { Circle(c, it / 1000) } } }
                storm = o.obj("stormWarningArea")?.arcs()?.firstOrNull()
            }
            val circle = o.obj("probabilityCircle")?.num("radius")?.div(1000)
            points += TyphoonPoint(
                hours = hours,
                validTime = o.str("validtime", "JST") ?: s?.str("validtime", "JST"),
                center = center,
                circleKm = circle ?: s?.num("probabilityCircleRadius", "km"),
                stormKm = s?.ranges("stormWarning")?.maxOfOrNull { it.second }
                    ?: if (hours == 0) storm?.radiusKm else o.obj("stormWarningArea")?.arcs()?.filter { it.center == center }?.maxOfOrNull { it.radiusKm },
                category = s?.str("category", "jp"),
                scale = s?.str("scale")?.takeIf { it != "-" },
                intensity = s?.str("intensity")?.takeIf { it != "-" },
                location = s?.str("location"),
                pressureHpa = s?.str("pressure"),
                maxWindMps = s?.str("maximumWind", "sustained", "m/s"),
                gustMps = s?.str("maximumWind", "gust", "m/s"),
                course = s?.str("course"),
                speedKmh = s?.str("speed", "km/h"),
                galeText = s?.ranges("galeWarning")?.takeIf { it.isNotEmpty() }
                    ?.joinToString(L(" ・ ", " · ")) { (area, km) -> "${Jma.direction(area)} ${km.toInt()} km" },
            )
        }
        if (points.isEmpty()) error(L("進路の情報がありません", "No track information"))
        return TyphoonTrack(
            id = id,
            number = typhoonLabel(title?.str("typhoonNumber")),
            name = title?.str("name", "jp"),
            nameEn = title?.str("name", "en"),
            reportedAt = title?.str("issue", "JST"),
            track = track,
            preTrack = pre,
            points = points.sortedBy { it.hours },
            gale = gale,
            storm = storm,
        )
    }

    /** 実況の "position": {"deg": [緯度, 経度]} の [i] 番目。 */
    private fun JsonObject.degree(i: Int): Double? =
        (((this["position"] as? JsonObject)?.get("deg") as? JsonArray)?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.obj(key: String): JsonObject? = when (val v = this[key]) {
        is JsonObject -> v
        // 文字列に JSON を詰めて返してくることがあるので、そのときは読み直す
        is JsonPrimitive -> v.contentOrNull?.let { runCatching { Http.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        else -> null
    }

    private fun JsonElement.toLatLon(): LatLon? {
        val a = this as? JsonArray ?: return null
        val lat = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val lon = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        return LatLon(lat, lon)
    }

    private fun JsonObject.latLon(key: String): LatLon? = this[key]?.toLatLon()

    private fun JsonObject.latLons(key: String): List<LatLon> = (this[key] as? JsonArray)?.mapNotNull { it.toLatLon() }.orEmpty()

    private fun JsonObject.num(vararg path: String): Double? {
        var current: JsonElement? = this
        for (key in path) current = (current as? JsonObject)?.get(key)
        return (current as? JsonPrimitive)?.doubleOrNull
    }

    /** 暴風域の円弧 [[中心], 半径(m), [角度, 角度]] の並びを円にする（中心と半径だけを使う）。 */
    private fun JsonObject.arcs(): List<Circle> = (this["arc"] as? JsonArray).orEmpty().mapNotNull { e ->
        val a = e as? JsonArray ?: return@mapNotNull null
        val c = a.getOrNull(0)?.toLatLon() ?: return@mapNotNull null
        val r = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        Circle(c, r / 1000)
    }

    /** specifications.json の stormWarning / galeWarning（[{area, range: {km}}]）→ (方角, km)。方角が「全域」なら "全域"。 */
    private fun JsonObject.ranges(key: String): List<Pair<String, Double>>? = (this[key] as? JsonArray)?.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val km = o.num("range", "km") ?: return@mapNotNull null
        val area = (o["area"] as? JsonPrimitive)?.contentOrNull ?: o.str("area", "jp") ?: ""
        area to km
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
            officeCode = office?.code,
            officeName = office?.name,
            nameEn = office?.areaEn,
            officeNameEn = office?.nameEn,
        )
        if (!next.found) Log.w(TAG, "天気の地点から市町村を決められない（国外の地点など）")
        resolved = next
        runCatching { areaFile.writeText(Http.json.encodeToString(next)) }
        return next
    }

    private class Office(val code: String, val name: String, val nameEn: String?, val areaEn: String?)

    /** 市町村 → 二次細分の中間 → 一次細分 → 府県予報区、と area.json の親をたどる。英語の名前（enName）も拾う。 */
    private suspend fun officeOf(class20: String): Office? {
        val a: AreaDto = client.get("$BASE/common/const/area.json").body()
        val class15 = a.class20s[class20]?.parent ?: return null
        val class10 = a.class15s[class15]?.parent ?: return null
        val office = a.class10s[class10]?.parent ?: return null
        val name = a.offices[office]?.name ?: return null
        return Office(office, name, a.offices[office]?.enName, a.class20s[class20]?.enName)
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
        /** 見る津波の発表（地震）の数。 */
        const val MAX_TSUNAMI_EVENTS = 3

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
        val nameEn: String? = null,
        val officeNameEn: String? = null,
    ) {
        val found: Boolean get() = code != null && officeCode != null

        // 英語の名前を持たない古い保存（2026-10-04 より前）は決め直す
        fun matches(location: LocationConfig): Boolean =
            latitude == location.latitude && longitude == location.longitude && (!found || nameEn != null)
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
        @SerialName("en_anm") val epicenterEn: String? = null,
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
    private data class AreaNameDto(val name: String = "", val parent: String? = null, val enName: String? = null)
}
