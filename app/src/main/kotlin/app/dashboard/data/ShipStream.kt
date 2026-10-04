package app.dashboard.data

import android.util.Log
import app.dashboard.i18n.L
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 船の位置（AIS）。aisstream.io の WebSocket に、地図に見えている範囲（[Box]）を伝えて、届いた位置を貯める。
 *
 * MarineTraffic・VesselFinder の API は有料なので、無料の登録で API キーを作れる aisstream.io を使う。
 * aisstream.io は「その範囲で新しく届いた電文」を流すだけで、いまの全隻の一覧は返さない。船は数秒〜数分ごとに位置を送るので、
 * つないでから少しずつ地図に船が増える。受け取った船は [EXPIRE_MS] 位置が届かなければ消す。
 *
 * つなぐのはカードが見えている間だけ（[watch] に範囲を渡す。null で切る）。範囲が変わったら、同じ接続で購読を送り直す。
 */
class ShipStream(private val configStore: ConfigStore, private val scope: CoroutineScope) {

    /** 地図の範囲（度）。 */
    data class Box(val south: Double, val west: Double, val north: Double, val east: Double) {
        fun contains(lat: Double, lon: Double) = lat in south..north && lon in west..east
    }

    sealed interface Status {
        data object Idle : Status
        data object NoKey : Status
        data object Connecting : Status
        data object Live : Status
        data class Error(val message: String) : Status
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val all = ConcurrentHashMap<Long, Ship>()
    private val _ships = MutableStateFlow<List<Ship>>(emptyList())
    val ships: StateFlow<List<Ship>> = _ships.asStateFlow()
    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var wanted: Box? = null
    @Volatile private var subscribed: Box? = null
    @Volatile private var keyInUse: String? = null
    private var loop: Job? = null

    /** 見たい範囲を伝える（null で切る）。範囲は少し広げて購読する（少しのドラッグで購読をやり直さないように）。 */
    @Synchronized
    fun watch(box: Box?) {
        wanted = box
        if (box == null) {
            loop?.cancel()
            loop = null
            close()
            _status.value = Status.Idle
            return
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        val s = socket
        val sub = subscribed
        if (s != null && sub != null && !covers(sub, box)) send(s, box)
    }

    /** 接続を保ち、1 秒ごとに地図へ出す一覧を作り直す。切れたら 15 秒後につなぎ直す。 */
    private suspend fun run() {
        var retryAt = 0L
        while (scope.isActive) {
            val box = wanted ?: break
            val key = configStore.get().ships.apiKey?.trim().orEmpty()
            when {
                key.isEmpty() -> {
                    close()
                    _status.value = Status.NoKey
                }
                socket == null || key != keyInUse -> if (System.currentTimeMillis() >= retryAt) {
                    close()
                    connect(key)
                    retryAt = System.currentTimeMillis() + RETRY_MS
                }
            }
            publish(box)
            delay(1_000)
        }
    }

    private fun publish(box: Box) {
        val now = System.currentTimeMillis()
        all.values.removeIf { now - it.seenAt > EXPIRE_MS }
        val wide = widen(box, 1.0)
        _ships.value = all.values.filter { wide.contains(it.lat, it.lon) }
    }

    private fun connect(key: String) {
        keyInUse = key
        _status.value = Status.Connecting
        socket = client.newWebSocket(Request.Builder().url(URL).build(), object : WebSocketListener() {
            /** この接続で電文を 1 つでも受け取ったか（受け取る前に切られたら、API キーが受け付けられなかったとみなす）。 */
            @Volatile var heard = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                // つないでから 3 秒以内に購読を送らないと切られる
                wanted?.let { send(webSocket, it) }
                _status.value = Status.Live
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                heard = true
                when (val u = parse(text, System.currentTimeMillis())) {
                    is Update.Position -> {
                        all.compute(u.ship.mmsi) { _, old ->
                            u.ship.copy(
                                name = u.ship.name ?: old?.name,
                                shipType = old?.shipType ?: 0,
                                destination = old?.destination,
                            )
                        }
                    }
                    is Update.Static -> all.computeIfPresent(u.mmsi) { _, old ->
                        old.copy(name = u.name ?: old.name, shipType = u.type ?: old.shipType, destination = u.destination ?: old.destination)
                    }
                    is Update.Failure -> _status.value = Status.Error(u.message)
                    null -> Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "AIS の接続が切れた", t)
                lost(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lost(webSocket)
            }

            private fun lost(webSocket: WebSocket) {
                if (socket !== webSocket) return
                socket = null
                subscribed = null
                if (_status.value is Status.Error) return
                _status.value = Status.Error(
                    if (!heard) L("接続を断られました。API キーを確かめてください", "The connection was refused. Check the API key")
                    else L("接続が切れました。つなぎ直しています…", "Disconnected. Reconnecting…"),
                )
            }
        })
    }

    private fun send(ws: WebSocket, box: Box) {
        val key = keyInUse ?: return
        val sub = widen(box, 0.5)
        if (ws.send(subscription(key, sub))) subscribed = sub
    }

    private fun close() {
        socket?.close(1000, null)
        socket = null
        subscribed = null
    }

    sealed interface Update {
        data class Position(val ship: Ship) : Update
        data class Static(val mmsi: Long, val name: String?, val type: Int?, val destination: String?) : Update
        data class Failure(val message: String) : Update
    }

    companion object {
        private const val TAG = "ShipStream"
        private const val URL = "wss://stream.aisstream.io/v0/stream"
        private const val RETRY_MS = 15_000L
        /** これだけ位置が届かなければ地図から消す（停泊中の船は数分おきにしか送らない）。 */
        const val EXPIRE_MS = 20 * 60_000L

        /** [box] を縦横それぞれ [ratio] 倍ぶん広げる（緯度は ±85 度まで）。 */
        fun widen(box: Box, ratio: Double): Box {
            val dLat = (box.north - box.south) * ratio / 2
            val dLon = (box.east - box.west) * ratio / 2
            return Box((box.south - dLat).coerceAtLeast(-85.0), box.west - dLon, (box.north + dLat).coerceAtMost(85.0), box.east + dLon)
        }

        /** [outer] が [inner] をすっぽり含むか。 */
        fun covers(outer: Box, inner: Box) =
            inner.south >= outer.south && inner.north <= outer.north && inner.west >= outer.west && inner.east <= outer.east

        /** 購読の電文。経度が ±180 を越える範囲は、そのまま渡すと何も届かないので丸める。 */
        fun subscription(key: String, box: Box): String = buildJsonObject {
            put("APIKey", key)
            put("BoundingBoxes", buildJsonArray {
                add(buildJsonArray {
                    add(buildJsonArray { add(JsonPrimitive(box.south)); add(JsonPrimitive(box.west.coerceIn(-180.0, 180.0))) })
                    add(buildJsonArray { add(JsonPrimitive(box.north)); add(JsonPrimitive(box.east.coerceIn(-180.0, 180.0))) })
                })
            })
            put("FilterMessageTypes", buildJsonArray {
                add(JsonPrimitive("PositionReport"))
                add(JsonPrimitive("StandardClassBPositionReport"))
                add(JsonPrimitive("ShipStaticData"))
            })
        }.toString()

        /** aisstream.io の電文 1 つを読む。使わない種類は null。 */
        fun parse(text: String, now: Long): Update? {
            val root = runCatching { Http.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            root["error"]?.jsonPrimitive?.contentOrNull?.let { return Update.Failure(it) }
            val type = root["MessageType"]?.jsonPrimitive?.contentOrNull ?: return null
            val meta = root["MetaData"] as? JsonObject
            val body = (root["Message"] as? JsonObject)?.get(type) as? JsonObject ?: return null
            val mmsi = meta?.get("MMSI")?.jsonPrimitive?.longOrNull ?: body["UserID"]?.jsonPrimitive?.longOrNull ?: return null
            val metaName = meta?.get("ShipName")?.jsonPrimitive?.contentOrNull?.trim()?.trimEnd('@')?.trim()?.takeIf { it.isNotEmpty() }
            return when (type) {
                "PositionReport", "StandardClassBPositionReport" -> {
                    val lat = body.num("Latitude") ?: meta?.num("latitude") ?: return null
                    val lon = body.num("Longitude") ?: meta?.num("longitude") ?: return null
                    // 91 / 181 は「位置なし」
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                    Update.Position(
                        Ship(
                            mmsi = mmsi,
                            name = metaName,
                            lat = lat,
                            lon = lon,
                            cog = body.num("Cog")?.takeIf { it < 360 },
                            sog = body.num("Sog")?.takeIf { it < 102.2 },
                            heading = body.num("TrueHeading")?.takeIf { it < 360 },
                            seenAt = now,
                        ),
                    )
                }
                "ShipStaticData" -> Update.Static(
                    mmsi,
                    body["Name"]?.jsonPrimitive?.contentOrNull?.trim()?.trimEnd('@')?.trim()?.takeIf { it.isNotEmpty() } ?: metaName,
                    body["Type"]?.jsonPrimitive?.intOrNull,
                    body["Destination"]?.jsonPrimitive?.contentOrNull?.trim()?.trimEnd('@')?.trim()?.takeIf { it.isNotEmpty() },
                )
                else -> null
            }
        }

        private fun JsonObject.num(key: String) = this[key]?.jsonPrimitive?.doubleOrNull

        /** AIS の船種の番号を、地図の色分けの分類にする。 */
        fun category(type: Int): ShipCategory = when (type) {
            in 60..69 -> ShipCategory.PASSENGER
            in 70..79 -> ShipCategory.CARGO
            in 80..89 -> ShipCategory.TANKER
            30 -> ShipCategory.FISHING
            31, 32, 52 -> ShipCategory.TUG
            in 36..37 -> ShipCategory.PLEASURE
            in 40..49 -> ShipCategory.HIGH_SPEED
            in 50..59, 33, 34, 35 -> ShipCategory.SPECIAL
            else -> ShipCategory.OTHER
        }
    }
}

/** 地図の船の色分け（MarineTraffic と同じ考え方）。 */
enum class ShipCategory(private val ja: String, private val en: String) {
    CARGO("貨物船", "Cargo"),
    TANKER("タンカー", "Tanker"),
    PASSENGER("旅客船", "Passenger"),
    FISHING("漁船", "Fishing"),
    TUG("タグボート", "Tug"),
    HIGH_SPEED("高速船", "High-speed"),
    PLEASURE("プレジャー", "Pleasure"),
    SPECIAL("特殊船", "Special"),
    OTHER("その他", "Other"),
    ;

    val label: String get() = L(ja, en)
}
