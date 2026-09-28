package app.walldash.ui.dashboard

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.walldash.AppGraph
import app.walldash.data.Config
import app.walldash.data.DeviceState
import app.walldash.data.DisasterState
import app.walldash.data.LyricsRepository
import app.walldash.data.SpotifyState
import app.walldash.data.Tones
import app.walldash.data.TyphoonTrack
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.pow

/**
 * ダッシュボード画面の状態。
 *
 * データは同じプロセスの Repository から 2 秒ごとに読む（HTTP は通さない）。
 * 防災の切り替わりと充電の抜き差しの検知、タイマー、強震モニタの画像もここで持つ。
 */
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    val graph = AppGraph.get(app)
    private val prefs = app.getSharedPreferences("walldash_ui", Context.MODE_PRIVATE)

    val config: StateFlow<Config> = graph.config.flow

    private val _state = MutableStateFlow<DeviceState?>(null)
    val state = _state.asStateFlow()

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now = _now.asStateFlow()

    private val _rssi = MutableStateFlow<List<Int?>>(emptyList())
    val rssiHistory = _rssi.asStateFlow()
    private val _cpu = MutableStateFlow<List<Int>>(emptyList())
    val cpuHistory = _cpu.asStateFlow()

    data class Toast(val title: String, val body: String, val severe: Boolean)

    private val _toast = MutableStateFlow<Toast?>(null)
    val toast = _toast.asStateFlow()
    private var toastJob: Job? = null

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    data class KmoniFrame(val image: ImageBitmap? = null, val label: String = "", val failed: Boolean = false)

    private val _kmoniBase = MutableStateFlow<ImageBitmap?>(null)
    val kmoniBase = _kmoniBase.asStateFlow()
    private val _kmoni = MutableStateFlow(KmoniFrame())
    val kmoni = _kmoni.asStateFlow()
    private var kmoniFailures = 0

    private val _album = MutableStateFlow<Pair<String, ImageBitmap>?>(null)
    val album = _album.asStateFlow()
    private var albumLoading: String? = null

    /**
     * 雨雲レーダー。地図と雨雲（気象庁のナウキャスト）のタイルを、表示の中心のまわり 5 x 3 枚ずつ持つ。
     * 座標はズーム [zoom] のタイル 1 枚 = 256 とした値。[centerX] / [centerY] は表示の中心（ドラッグで動く）、
     * [homeX] / [homeY] は天気の地点。地図は文字や道路の無い灰色の地図（[dark] はダークのテーマ用の暗い版）。
     * 雨雲は偶数のズームにしか無い（奇数は空の画像が返る）ので、[rain] は [rainZoom] のタイルで持ち、引き伸ばして重ねる。
     * [old] は「＋」「−」を押す前のズーム [oldZoom] の地図。新しい地図が揃うまで引き伸ばして下に敷く（真っ白にしない）。
     * [oldRain] も同じく、雨雲のズームが変わる前の雨雲（[oldRainZoom]）。
     */
    data class RadarFrame(
        val zoom: Int = 0,
        val centerX: Float = 0f,
        val centerY: Float = 0f,
        val homeX: Float = 0f,
        val homeY: Float = 0f,
        val dark: Boolean = true,
        val base: Map<Pair<Int, Int>, ImageBitmap> = emptyMap(),
        val rain: Map<Pair<Int, Int>, ImageBitmap> = emptyMap(),
        val oldZoom: Int = 0,
        val old: Map<Pair<Int, Int>, ImageBitmap> = emptyMap(),
        val oldRainZoom: Int = 0,
        val oldRain: Map<Pair<Int, Int>, ImageBitmap> = emptyMap(),
        val label: String = "",
        val failed: Boolean = false,
    ) {
        /** 地点から動かしているか（地名を注記から外す）。 */
        val panned: Boolean get() = abs(centerX - homeX) > 1f || abs(centerY - homeY) > 1f
        val rainZoom: Int get() = rainZoomOf(zoom)
        val canZoomIn: Boolean get() = zoom in 1 until RADAR_ZOOM_MAX
        val canZoomOut: Boolean get() = zoom > RADAR_ZOOM_MIN
    }

    private val _radar = MutableStateFlow(RadarFrame())
    val radar = _radar.asStateFlow()
    private var radarAt = 0L
    @Volatile private var radarStamp: String? = null
    private var radarPanAt = 0L
    /** ドラッグ・「＋」「−」のたびに送る。受け取る側は 1 つだけで、足りないタイルを取りに行く（途中の取得は止めない）。 */
    private val radarWake = Channel<Unit>(Channel.CONFLATED)
    /** 「＋」「−」で選んだズーム（次に開いたときも同じにする）。 */
    private var radarZoom = prefs.getInt(RADAR_ZOOM, RADAR_ZOOM_DEFAULT).coerceIn(RADAR_ZOOM_MIN, RADAR_ZOOM_MAX)
    private val radarLock = Mutex()
    /** 取れなかったタイル（日本の外の雨雲など、サーバーが無いと答えたもの）。同じ URL を何度も取りに行かない。 */
    private val radarMissing = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 全画面（Spotify・時刻）で「画面を暗くしない」を選んでいるか（次に開いたときも同じにする。両方で共通）。 */
    private val _keepAwake = MutableStateFlow(prefs.getBoolean(KEEP_AWAKE, false))
    val keepAwake = _keepAwake.asStateFlow()
    private val _fullscreenOpen = MutableStateFlow(false)

    /**
     * 無操作の減光を止めるか。全画面を開いていて、かつ「画面を暗くしない」がオンのとき。
     * 全画面を閉じたら（オンのままでも）false に戻り、MainActivity が設定の時間で暗くする予約をし直す。
     */
    val holdAwake: StateFlow<Boolean> = combine(_keepAwake, _fullscreenOpen) { on, open -> on && open }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setKeepAwake(on: Boolean) {
        _keepAwake.value = on
        prefs.edit().putBoolean(KEEP_AWAKE, on).apply()
    }

    fun setFullscreenOpen(open: Boolean) {
        _fullscreenOpen.value = open
    }

    /**
     * 写真カードに出している写真。[index] は何枚目か（1 から）、[count] はアルバムの枚数。
     * 写真の URL は切れるので持たず、読み込んだ画像だけを持つ。
     */
    data class PhotoFrame(val image: ImageBitmap, val caption: String?, val takenAt: String?, val index: Int, val count: Int)

    private val _photo = MutableStateFlow<PhotoFrame?>(null)
    val photo = _photo.asStateFlow()
    /** 出す順（写真の guid）。アルバムの中身か「順番を混ぜる」が変わったら作り直す。 */
    private var photoOrder: List<String> = emptyList()
    private var photoOrderKey: Any? = null
    private var photoPos = -1
    private var photoShownAt = 0L
    private val photoWake = Channel<Unit>(Channel.CONFLATED)

    /** 背景画像。設定で選ばれた（imageSetAt が変わった）ときだけ読み直す。 */
    private val _wallpaper = MutableStateFlow<ImageBitmap?>(null)
    val wallpaper = _wallpaper.asStateFlow()

    enum class TimerMode { IDLE, RUNNING, RINGING }

    data class TimerState(
        val mode: TimerMode = TimerMode.IDLE,
        val endAt: Long = 0,
        val hours: Int = 0,
        val minutes: Int = 5,
    )

    private val _timer = MutableStateFlow(restoreTimer())
    val timer = _timer.asStateFlow()

    @Volatile
    private var active = true
    private var lastCharging: Boolean? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            config.map { it.wallpaper.imageSetAt }.distinctUntilChanged().collect { at ->
                _wallpaper.value = if (at > 0) graph.wallpaper.load()?.asImageBitmap() else null
            }
        }
        viewModelScope.launch {
            while (true) {
                val t = System.currentTimeMillis()
                _now.value = t
                tickTimer(t)
                delay(1000 - t % 1000)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (active) poll()
                delay(POLL_MS)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (active && wantsKmoni()) kmoniTick()
                delay(KMONI_MS)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (active) try { photoTick() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                // 1 秒ごとに切り替えの時刻を見る。カードを押されたらすぐ次へ
                withTimeoutOrNull(1_000) { photoWake.receive() }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (active && graph.config.get().display.showRadar) {
                    try { radarTick() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                }
                // 1 分待つ。ドラッグ・「＋」「−」があればすぐに足りないタイルを取りに行く
                withTimeoutOrNull(RADAR_CHECK_MS) {
                    while (true) {
                        radarWake.receive()
                        delay(150)
                        if (active) try { loadRadarTiles() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    /** 画面が見えていない間（別アプリ・ブラウズ中）は取得と描画の材料集めを止める。 */
    fun setActive(on: Boolean) {
        active = on
        if (on) viewModelScope.launch(Dispatchers.IO) { poll() }
    }

    fun refreshAll() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            graph.settings.refreshAll()
            poll()
            _refreshing.value = false
        }
    }

    /** 操作は Spotify を鳴らしている端末へ送る。このタブレットから音は出ない。 */
    fun spotifyControl(action: String) {
        viewModelScope.launch(Dispatchers.IO) {
            graph.spotify.control(action).onFailure { showToast("Spotify", it.message ?: "操作できません", false) }
            poll()
        }
    }

    fun showToast(title: String, body: String, severe: Boolean) {
        _toast.value = Toast(title, body, severe)
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(TOAST_MS)
            _toast.value = null
        }
    }

    // ------------------------------------------------------------ 取得

    private fun poll() {
        val s = runCatching { graph.snapshot() }.getOrNull() ?: return
        _state.value = s
        _rssi.update { (it + s.wifi.rssiDbm).takeLast(HISTORY) }
        s.deviceStats.cpuPercent?.let { c -> _cpu.update { (it + c).takeLast(HISTORY) } }
        checkCharging(s.deviceStats.charging)
        if (s.config.disaster.enabled && s.disaster.available) checkDisaster(s.disaster)
        loadAlbum(s.spotify.albumImageUrl)
    }

    /** 挿したら低→高、抜けたら高→低。起動直後は前の状態が無いので鳴らさない。 */
    private fun checkCharging(charging: Boolean) {
        val before = lastCharging
        lastCharging = charging
        if (before == null || before == charging) return
        val n = graph.config.get().notifications
        if (n.chargingSound) graph.notices.play(n.chargingTone, Tones.DEFAULT_CHARGING, reversed = !charging)
    }

    /**
     * 警報・注意報・津波・台風・噴火が新しく出たか切り替わったときだけ知らせる。
     * 発表時刻は比べない（内容が同じでも打ち直されるため）。台風は号数・名前・大きさ・強さだけを見る。
     */
    private fun checkDisaster(d: DisasterState) {
        val marks = mapOf(
            "tsunami" to d.tsunami.joinToString("|") { it.title ?: "津波情報" },
            "warning" to (listOf(d.headline.orEmpty()) + d.activeAreas.map { it.code + ":" + it.kinds.joinToString(",") })
                .joinToString("|"),
            "typhoon" to d.typhoons.joinToString("|") { listOf(it.number, it.name, it.scale, it.intensity).joinToString("/") },
            "volcano" to d.volcanoes.joinToString("|") { it.name + "/" + it.level },
        )
        if (!prefs.getBoolean(SEEN_INIT, false)) {
            saveSeen(marks)
            return
        }
        val changed = marks.filter { (k, v) -> prefs.getString("$SEEN_PREFIX$k", "") != v }.keys.toList()
        if (changed.isEmpty()) return
        saveSeen(marks)

        val n = graph.config.get().notifications
        if (n.disasterSound) graph.notices.play(n.disasterTone, Tones.DEFAULT_DISASTER)
        showToast(changed.joinToString(" ・ ") { ALERT_LABELS.getValue(it) }, alertDetail(d, changed.first()), changed.first() == "tsunami")
    }

    private fun saveSeen(marks: Map<String, String>) {
        prefs.edit().apply {
            marks.forEach { (k, v) -> putString("$SEEN_PREFIX$k", v) }
            putBoolean(SEEN_INIT, true)
        }.apply()
    }

    private fun alertDetail(d: DisasterState, key: String): String = when (key) {
        "tsunami" -> d.tsunami.takeIf { it.isNotEmpty() }?.joinToString(" ・ ") { it.title ?: "津波情報" }
            ?: "津波情報は解除されました"
        "typhoon" -> d.typhoons.firstOrNull()?.let { t ->
            val kind = listOfNotNull(t.scale, t.intensity).joinToString("・")
            listOfNotNull(t.number, t.name).joinToString(" ") + if (kind.isNotEmpty()) "（$kind）" else ""
        } ?: "台風の情報はなくなりました"
        "volcano" -> d.volcanoes.firstOrNull()?.let { it.name + " " + it.level } ?: "噴火警報は出ていません"
        else -> d.headline ?: d.activeAreas.firstOrNull()?.let { it.name + " " + it.kinds.joinToString("・") }
            ?: "警報・注意報は解除されました"
    }

    // ------------------------------------------------------------ 画像

    private fun wantsKmoni(): Boolean {
        val d = graph.config.get().display
        return d.showDisaster && d.disasterShowKmoni
    }

    /** 防災科研の強震モニタ。画像は JST のファイル名で 1 秒ごとに出るので、2 秒遅らせて取る。 */
    private suspend fun kmoniTick() {
        if (_kmoniBase.value == null) _kmoniBase.value = fetchBitmap(KMONI_BASE_URL)
        val at = Instant.now().minusMillis(KMONI_DELAY_MS).atZone(JST)
        val url = KMONI_URL + at.format(DAY) + "/" + at.format(STAMP) + ".acmap_s.gif"
        val image = fetchBitmap(url)
        if (image != null) {
            kmoniFailures = 0
            _kmoni.value = KmoniFrame(image, at.format(CLOCK), failed = false)
        } else if (++kmoniFailures >= 4) {
            _kmoni.update { it.copy(label = "取得不可", failed = true) }
        }
    }

    /**
     * 雨雲は 5 分ごとに出る。地点・テーマが変わったら地図から取り直す（ズームは [zoomRadar] が合わせる）。
     * ドラッグで動かしたまま [RADAR_RETURN_MS] 触られなければ、地点へ戻す。
     * 気象庁の時刻（targetTimes）は UTC の "yyyyMMddHHmmss"。
     */
    private suspend fun radarTick() {
        val c = graph.config.get()
        val dark = c.display.theme != "light"
        val now = System.currentTimeMillis()
        // ズームは表示中のもの（「＋」「−」と同時に動いても食い違わないように、見るのは 1 か所だけ）
        val f = _radar.value
        val zoom = if (f.zoom != 0) f.zoom else radarZoom
        val (hx, hy) = homeOf(c.location.latitude, c.location.longitude, zoom)
        // 「＋」「−」で倍にした地点の座標は、計算し直した値と端数だけずれることがあるので少し幅を持たせる
        if (f.zoom == 0 || abs(f.homeX - hx) > 0.5f || abs(f.homeY - hy) > 0.5f || f.dark != dark) {
            val reset = RadarFrame(zoom, hx, hy, hx, hy, dark, label = f.label)
            if (_radar.compareAndSet(f, reset)) {
                radarMissing.clear()
                radarStamp = null
                radarAt = 0
            }
        } else if (f.panned && now - radarPanAt > RADAR_RETURN_MS) {
            _radar.update { it.copy(centerX = it.homeX, centerY = it.homeY) }
        }
        if (now - radarAt >= RADAR_MS) {
            radarAt = now
            val times = runCatching { graph.http.get(RADAR_TIMES).bodyAsText() }.getOrNull()
            val stamp = times?.let { Regex("\"basetime\"\\s*:\\s*\"(\\d{14})\"").find(it)?.groupValues?.get(1) }
            if (stamp == null) {
                _radar.update { it.copy(label = "取得できません") }
            } else if (stamp != radarStamp) {
                // 新しい雨雲は揃ってから差し替える（途中で消えてちらつかないように）
                val rz = _radar.value.rainZoom
                val rain = rainKeys(_radar.value).mapNotNull { k ->
                    (fetchRadarTile(rainUrl(stamp, rz, k)) as? Tile.Ok)?.let { k to it.image }
                }.toMap()
                val local = runCatching {
                    java.time.LocalDateTime.parse(stamp, STAMP).atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault())
                }.getOrNull()
                radarStamp = stamp
                // 取れなかった地図も 5 分ごとに取り直す
                radarMissing.clear()
                _radar.update {
                    if (it.rainZoom != rz) it
                    else it.copy(rain = rain, label = local?.let { t -> "%d:%02d 観測".format(t.hour, t.minute) }.orEmpty())
                }
            }
        }
        loadRadarTiles()
    }

    /** 地点（緯度・経度）の、ズーム [zoom] での座標（タイル 1 枚 = 256）。 */
    private fun homeOf(latitude: Double, longitude: Double, zoom: Int): Pair<Float, Float> {
        val n = 1 shl zoom
        val latR = Math.toRadians(latitude.coerceIn(-85.0, 85.0))
        val x = ((longitude + 180) / 360 * n * 256).toFloat()
        val y = ((1 - kotlin.math.ln(kotlin.math.tan(latR) + 1 / kotlin.math.cos(latR)) / Math.PI) / 2 * n * 256).toFloat()
        return x to y
    }

    /** ドラッグで地図を動かす。[dx] / [dy] は表示の中心を動かす量（タイル 1 枚 = 256）。足りないタイルは取りに行く。 */
    fun panRadar(dx: Float, dy: Float) {
        _radar.update {
            if (it.zoom == 0) return@update it
            val world = (1 shl it.zoom) * 256f
            // 東西はつながっているので、どこまででも動かせる（タイルの URL で折り返す）。南北は地図の端まで
            it.copy(centerX = it.centerX + dx, centerY = (it.centerY + dy).coerceIn(0f, world))
        }
        radarPanAt = System.currentTimeMillis()
        radarWake.trySend(Unit)
    }

    /**
     * 「＋」（[step] = 1）で 1 段拡大、「−」（-1）で 1 段縮小。表示の中心はそのままにする。
     * 雨雲のズームが変わらない段（奇数 ⇔ 1 つ下の偶数）なら雨雲の画像は取り直さずに使う。
     */
    fun zoomRadar(step: Int) {
        var changed = false
        _radar.update {
            if (it.zoom == 0) return@update it
            val z = (it.zoom + step).coerceIn(RADAR_ZOOM_MIN, RADAR_ZOOM_MAX)
            if (z == it.zoom) return@update it
            changed = true
            val k = 2f.pow(z - it.zoom)
            val sameRain = rainZoomOf(z) == it.rainZoom
            // 押す前の地図が見えている範囲を埋めていればそれを敷く。埋まっていなければ（続けて押したとき）前から敷いていた地図を使い続ける
            val useBase = it.old.isEmpty() || radarKeys(it).all { key -> key in it.base }
            val useRain = it.oldRain.isEmpty() || rainKeys(it).all { key -> key in it.rain }
            it.copy(
                zoom = z,
                centerX = it.centerX * k, centerY = it.centerY * k,
                homeX = it.homeX * k, homeY = it.homeY * k,
                base = emptyMap(),
                oldZoom = if (useBase) it.zoom else it.oldZoom,
                old = if (useBase) it.base else it.old,
                rain = if (sameRain) it.rain else emptyMap(),
                oldRainZoom = if (sameRain || !useRain) it.oldRainZoom else it.rainZoom,
                oldRain = if (sameRain || !useRain) it.oldRain else it.rain,
                failed = false,
            )
        }
        if (!changed) return
        radarZoom = _radar.value.zoom
        prefs.edit().putInt(RADAR_ZOOM, radarZoom).apply()
        radarPanAt = System.currentTimeMillis()
        radarWake.trySend(Unit)
    }

    /**
     * 表示の中心のまわりで足りないタイルを 1 枚ずつ取って足す。1 枚ごとにいまの表示を見直すので、
     * 取っている途中でドラッグ・ズームされても、その時点で要るタイルから取る。離れたタイルは捨てる（メモリを食わないように）。
     */
    private suspend fun loadRadarTiles() = radarLock.withLock {
        // 通信の失敗は、この回ではもう取りに行かない（次の回でまた試す）
        val failed = HashSet<String>()
        while (true) {
            val f = _radar.value
            if (f.zoom == 0) return@withLock
            val stamp = radarStamp
            val baseNeed = radarKeys(f).map { it to baseUrl(f.dark, f.zoom, it) }
                .firstOrNull { (k, url) -> k !in f.base && url !in radarMissing && url !in failed }
            if (baseNeed != null) {
                val (k, url) = baseNeed
                when (val t = fetchRadarTile(url)) {
                    is Tile.Ok -> _radar.update {
                        if (it.zoom == f.zoom && it.dark == f.dark) it.copy(base = it.base + (k to t.image), failed = false) else it
                    }
                    Tile.Missing -> Unit
                    Tile.Error -> failed += url
                }
                continue
            }
            val rz = f.rainZoom
            val rainNeed = if (stamp == null) null else rainKeys(f).map { it to rainUrl(stamp, rz, it) }
                .firstOrNull { (k, url) -> k !in f.rain && url !in radarMissing && url !in failed }
            if (rainNeed != null) {
                val (k, url) = rainNeed
                when (val t = fetchRadarTile(url)) {
                    is Tile.Ok -> _radar.update {
                        if (it.rainZoom == rz && radarStamp == stamp) it.copy(rain = it.rain + (k to t.image)) else it
                    }
                    Tile.Missing -> Unit
                    Tile.Error -> failed += url
                }
                continue
            }
            break
        }
        _radar.update { cur ->
            if (cur.zoom == 0) return@update cur
            val tx = kotlin.math.floor(cur.centerX / 256).toInt()
            val ty = (cur.centerY / 256).toInt()
            val shift = cur.zoom - cur.rainZoom
            fun near(k: Pair<Int, Int>) = abs(k.first - tx) <= 4 && abs(k.second - ty) <= 3
            fun nearRain(k: Pair<Int, Int>) = abs((k.first shl shift) - tx) <= 4 + (1 shl shift) && abs((k.second shl shift) - ty) <= 3 + (1 shl shift)
            // 見えている範囲の新しい地図（雨雲）が揃ったときだけ、敷いていた前のズームのものを捨てる
            val baseDone = radarKeys(cur).all { it in cur.base || baseUrl(cur.dark, cur.zoom, it) in radarMissing }
            val rainDone = radarStamp.let { s -> s == null || rainKeys(cur).all { it in cur.rain || rainUrl(s, cur.rainZoom, it) in radarMissing } }
            cur.copy(
                base = cur.base.filterKeys(::near),
                rain = cur.rain.filterKeys(::nearRain),
                old = if (baseDone) emptyMap() else cur.old,
                oldZoom = if (baseDone) 0 else cur.oldZoom,
                oldRain = if (rainDone) emptyMap() else cur.oldRain,
                oldRainZoom = if (rainDone) 0 else cur.oldRainZoom,
                failed = cur.base.isEmpty() && cur.old.isEmpty(),
            )
        }
    }

    private fun radarKeys(f: RadarFrame): List<Pair<Int, Int>> {
        val n = 1 shl f.zoom
        val tx = kotlin.math.floor(f.centerX / 256).toInt()
        val ty = (f.centerY / 256).toInt()
        // 真ん中から外へ向かって取る（ドラッグした先が早く埋まる）。東西は折り返すので、x は範囲の外でもよい
        return (-2..2).flatMap { dx -> (-1..1).map { dy -> Pair(tx + dx, ty + dy) } }
            .filter { it.second in 0 until n }
            .sortedBy { abs(it.first - tx) + abs(it.second - ty) }
    }

    /** 表示に要る雨雲のタイル（[RadarFrame.rainZoom] の座標）。 */
    private fun rainKeys(f: RadarFrame): List<Pair<Int, Int>> {
        val shift = f.zoom - f.rainZoom
        return radarKeys(f).map { Pair(it.first shr shift, it.second shr shift) }.distinct()
    }

    private sealed interface Tile {
        class Ok(val image: ImageBitmap) : Tile
        /** サーバーが無いと答えた（日本の外の雨雲など）。5 分ごとに忘れる。 */
        data object Missing : Tile
        /** 通信の失敗。覚えずに、また取りに行く。 */
        data object Error : Tile
    }

    /** タイルを 1 枚取る。止められた（キャンセル）ときは失敗として覚えずに、そのまま止まる。 */
    private suspend fun fetchRadarTile(url: String): Tile {
        if (url in radarMissing) return Tile.Missing
        return try {
            val bytes = graph.http.get(url).readRawBytes()
            val img = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            if (img != null) Tile.Ok(img) else Tile.Missing.also { radarMissing += url }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            radarMissing += url
            Tile.Missing
        } catch (e: Exception) {
            Tile.Error
        }
    }

    /** Esri の灰色の地図（文字・道路の記号が無く、雨雲が読みやすい）。URL は {z}/{y}/{x} の順。 */
    private fun baseUrl(dark: Boolean, zoom: Int, k: Pair<Int, Int>) =
        "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_${if (dark) "Dark" else "Light"}_Gray_Base/MapServer/tile/$zoom/${k.second}/${Math.floorMod(k.first, 1 shl zoom)}"

    private fun rainUrl(stamp: String, zoom: Int, k: Pair<Int, Int>) =
        "https://www.jma.go.jp/bosai/jmatile/data/nowc/$stamp/none/$stamp/surf/hrpns/$zoom/${Math.floorMod(k.first, 1 shl zoom)}/${k.second}.png"

    private fun loadAlbum(url: String?) {
        if (url == null || _album.value?.first == url || albumLoading == url) return
        albumLoading = url
        viewModelScope.launch(Dispatchers.IO) {
            fetchBitmap(url)?.let { _album.value = url to it }
            albumLoading = null
        }
    }

    private suspend fun fetchBitmap(url: String): ImageBitmap? = runCatching {
        val bytes = graph.http.get(url).readRawBytes()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()

    // ------------------------------------------------------------ 写真

    /** 写真カードを押したら、待たずに次の写真へ。 */
    fun nextPhoto() {
        photoShownAt = 0
        photoWake.trySend(Unit)
    }

    /**
     * 設定の間隔ごとに次の写真を読む。アルバムを取り直して中身が変わったら、出す順を作り直す（いまの写真の次から続ける）。
     * 画像の URL が切れていたら（403 など）、アルバムを取り直してもらってから次の回に読む。
     */
    private suspend fun photoTick() {
        val c = graph.config.get()
        if (!c.display.showPhotos || !c.photos.enabled) {
            _photo.value = null
            return
        }
        val list = graph.photos.photos
        if (list.isEmpty()) {
            if (graph.photos.state.lastError != null) _photo.value = null
            return
        }
        val key = list.map { it.guid } to c.photos.shuffle
        if (key != photoOrderKey) {
            val current = photoOrder.getOrNull(photoPos)
            photoOrder = list.map { it.guid }.let { if (c.photos.shuffle) it.shuffled() else it }
            photoOrderKey = key
            photoPos = photoOrder.indexOf(current).let { if (it >= 0) it else -1 }
            if (current == null || photoPos < 0) photoShownAt = 0
        }
        val now = System.currentTimeMillis()
        if (_photo.value != null && now - photoShownAt < c.photos.intervalSec * 1000L) return
        val nextPos = (photoPos + 1) % photoOrder.size
        // 1 周したら、混ぜる設定のときは順番を混ぜ直す（毎周同じ並びにしない）
        if (nextPos == 0 && c.photos.shuffle && photoOrder.size > 2) photoOrder = photoOrder.shuffled()
        val guid = photoOrder[nextPos]
        val p = list.firstOrNull { it.guid == guid } ?: return
        val image = try {
            decodeScaled(graph.http.get(p.url).readRawBytes(), PHOTO_MAX_SIDE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            graph.photos.refreshSoon()
            return
        } catch (e: Exception) {
            null
        }
        photoPos = nextPos
        photoShownAt = now
        if (image != null) _photo.value = PhotoFrame(image, p.caption, p.takenAt, list.indexOf(p) + 1, list.size)
    }

    /** 大きな写真は長辺 [maxSide] まで縮めて読む（この端末はメモリが少ない）。 */
    private fun decodeScaled(bytes: ByteArray, maxSide: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    // ------------------------------------------------------------ 全画面の材料（台風・歌詞）

    /** 台風の進路図。全画面を開いたときにだけ取る。 */
    suspend fun typhoonTrack(id: String): Result<TyphoonTrack> = runCatching { graph.disaster.typhoonTrack(id) }

    /** 台風の進路図の地図（Esri の暗い灰色の地図、タイル 1 枚）。 */
    suspend fun darkMapTile(zoom: Int, x: Int, y: Int): ImageBitmap? =
        (fetchRadarTile(baseUrl(true, zoom, x to y)) as? Tile.Ok)?.image

    /** 再生中の曲の歌詞（LRCLIB）。見つからなければ null。 */
    suspend fun lyrics(sp: SpotifyState): Result<LyricsRepository.Lyrics?> = runCatching {
        graph.lyrics.find(sp.trackName ?: return@runCatching null, sp.artistName, sp.albumName, sp.durationMs)
    }

    // ------------------------------------------------------------ タイマー

    fun pickTimer(hours: Int, minutes: Int) {
        _timer.update { it.copy(hours = hours, minutes = minutes) }
    }

    fun startTimer() {
        val t = _timer.value
        val total = t.hours * 3600 + t.minutes * 60
        if (total <= 0) return
        _timer.value = t.copy(mode = TimerMode.RUNNING, endAt = System.currentTimeMillis() + total * 1000L)
        saveTimer()
    }

    fun resetTimer() {
        graph.notices.stopRing()
        _timer.update { it.copy(mode = TimerMode.IDLE, endAt = 0) }
        saveTimer()
    }

    private fun tickTimer(now: Long) {
        val t = _timer.value
        if (t.mode != TimerMode.RUNNING || now < t.endAt) return
        _timer.value = t.copy(mode = TimerMode.RINGING)
        saveTimer()
        graph.notices.startRing(graph.config.get().notifications.timerTone)
    }

    /** 残り時間は終了時刻で持つ（画面が止まっていた間もずれないように）。期限切れは拾わない。 */
    private fun restoreTimer(): TimerState {
        val end = prefs.getLong(TIMER_END, 0)
        return if (end > System.currentTimeMillis()) TimerState(TimerMode.RUNNING, end) else TimerState()
    }

    private fun saveTimer() {
        val t = _timer.value
        prefs.edit().putLong(TIMER_END, if (t.mode == TimerMode.RUNNING) t.endAt else 0).apply()
    }

    override fun onCleared() {
        graph.notices.stopRing()
    }

    companion object {
        /** 雨雲レーダーのズームの範囲。4 で日本全体、11 で市区町村くらい。 */
        const val RADAR_ZOOM_MIN = 4
        const val RADAR_ZOOM_MAX = 11

        /** 気象庁の雨雲は偶数のズームだけ（10 まで）。奇数は 1 つ下の偶数を引き伸ばす。 */
        fun rainZoomOf(zoom: Int) = minOf(zoom and 1.inv(), 10)

        private const val PHOTO_MAX_SIDE = 1600
        private const val POLL_MS = 2_000L
        private const val KMONI_MS = 3_000L
        private const val RADAR_CHECK_MS = 60_000L
        private const val RADAR_MS = 300_000L
        private const val RADAR_RETURN_MS = 180_000L
        private const val RADAR_ZOOM = "radarZoom"
        private const val RADAR_ZOOM_DEFAULT = 8
        private const val RADAR_TIMES = "https://www.jma.go.jp/bosai/jmatile/data/nowc/targetTimes_N1.json"
        private const val KMONI_DELAY_MS = 2_000L
        private const val TOAST_MS = 5_000L
        private const val HISTORY = 60
        private const val SEEN_INIT = "disasterSeenInit"
        private const val SEEN_PREFIX = "disasterSeen."
        private const val TIMER_END = "timerEndAt"
        private const val KEEP_AWAKE = "nowPlayingKeepAwake"
        private const val KMONI_URL = "http://www.kmoni.bosai.go.jp/data/map_img/RealTimeImg/acmap_s/"
        private const val KMONI_BASE_URL = "http://www.kmoni.bosai.go.jp/data/map_img/CommonImg/base_map_w.gif"
        private val JST: ZoneId = ZoneId.of("Asia/Tokyo")
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        /** 並びがそのまま通知の優先順（津波が出ていれば津波を見出しにする）。 */
        private val ALERT_LABELS = linkedMapOf(
            "tsunami" to "津波",
            "warning" to "警報・注意報",
            "typhoon" to "台風",
            "volcano" to "噴火",
        )
    }
}
