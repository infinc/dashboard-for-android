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
import app.walldash.data.Tones
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    private companion object {
        const val POLL_MS = 2_000L
        const val KMONI_MS = 3_000L
        const val KMONI_DELAY_MS = 2_000L
        const val TOAST_MS = 5_000L
        const val HISTORY = 60
        const val SEEN_INIT = "disasterSeenInit"
        const val SEEN_PREFIX = "disasterSeen."
        const val TIMER_END = "timerEndAt"
        const val KMONI_URL = "http://www.kmoni.bosai.go.jp/data/map_img/RealTimeImg/acmap_s/"
        const val KMONI_BASE_URL = "http://www.kmoni.bosai.go.jp/data/map_img/CommonImg/base_map_w.gif"
        val JST: ZoneId = ZoneId.of("Asia/Tokyo")
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        /** 並びがそのまま通知の優先順（津波が出ていれば津波を見出しにする）。 */
        val ALERT_LABELS = linkedMapOf(
            "tsunami" to "津波",
            "warning" to "警報・注意報",
            "typhoon" to "台風",
            "volcano" to "噴火",
        )
    }
}
