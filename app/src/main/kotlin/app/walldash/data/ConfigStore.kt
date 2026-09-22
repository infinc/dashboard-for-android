package app.walldash.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * filesDir/config.json を読み書きする。
 *
 * 保存のたびに configVersion を +1 する。ダッシュボードは /api/state のポーリングで
 * この値の変化を検知して再描画するため、設定変更後に手動リロードが要らない。
 */
class ConfigStore(context: Context) {

    private val file = File(context.filesDir, "config.json")
    private val tmpFile = File(context.filesDir, "config.json.tmp")
    private val lock = Any()

    @Volatile
    private var cached: Config? = null

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun get(): Config = synchronized(lock) {
        cached?.let { return it }
        val loaded = runCatching {
            if (file.exists()) json.decodeFromString<Config>(file.readText()) else Config()
        }.getOrElse {
            Log.w(TAG, "config.json の読み込みに失敗。既定値で起動する", it)
            Config()
        }
        cached = loaded
        loaded
    }

    private val state by lazy { MutableStateFlow(get()) }

    /** アプリの画面が設定の変化を受け取るための流れ。値は [get] と常に同じ。 */
    val flow: StateFlow<Config> get() = state

    /**
     * 設定を書き換える。[mutate] の戻り値がそのまま新しい設定になる。
     * configVersion は呼び出し側では触らず、ここで必ず +1 する。
     */
    fun update(mutate: (Config) -> Config): Config = synchronized(lock) {
        val current = get()
        val next = mutate(current).copy(configVersion = current.configVersion + 1)
        writeAtomically(next)
        cached = next
        state.value = next
        next
    }

    /** 公開設定の差分を当てた設定を返す（保存はしない）。PIN 等の機微な値はこの経路では変わらない。 */
    fun patched(c: Config, patch: ConfigPatch): Config = c.copy(
        location = patch.location ?: c.location,
        units = patch.units ?: c.units,
        display = patch.display?.let(::sanitizeDisplay) ?: c.display,
        refresh = patch.refresh?.let(::sanitizeRefresh) ?: c.refresh,
        disaster = patch.disaster ?: c.disaster,
        feed = patch.feed?.let(::sanitizeFeed) ?: c.feed,
        notifications = patch.notifications?.let(::sanitizeNotifications) ?: c.notifications,
    )

    private fun sanitizeNotifications(n: NotificationConfig) = n.copy(
        volume = n.volume.coerceIn(0.0, 1.0),
        disasterTone = n.disasterTone.takeIf(Tones::isKnown) ?: Tones.DEFAULT_DISASTER,
        chargingTone = n.chargingTone.takeIf(Tones::isKnown) ?: Tones.DEFAULT_CHARGING,
        timerTone = n.timerTone.takeIf(Tones::isKnown) ?: Tones.DEFAULT_TIMER,
    )

    /** フィードは数と件数に上限を設ける。壁掛けで読める量と、取得にかかる時間の両方のため。 */
    private fun sanitizeFeed(f: FeedConfig) = f.copy(
        urls = f.urls.map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .take(5),
        maxItems = f.maxItems.coerceIn(1, 40),
    )

    private fun sanitizeDisplay(d: DisplayConfig) = d.copy(
        // 0 にすると画面が完全に消えて操作不能に見えるため、下限を設ける
        normalBrightness = d.normalBrightness.coerceIn(0.05, 1.0),
        idleDimAfterSeconds = d.idleDimAfterSeconds.coerceIn(30, 3600),
        idleDimBrightness = d.idleDimBrightness.coerceIn(0.05, 1.0),
        accent = if (Accents.isKnown(d.accent)) d.accent.uppercase() else Accents.DEFAULT,
        clockAlign = if (d.clockAlign in ALLOWED_ALIGNS) d.clockAlign else "left",
        clockDateFormat = if (d.clockDateFormat in ALLOWED_DATE_FORMATS) d.clockDateFormat else "ja",
        hourlyMode = if (d.hourlyMode in ALLOWED_HOURLY_MODES) d.hourlyMode else "both",
        // 知らないキーは捨てる。重複も落とす（同じ項目が 2 度並ぶと 3 列 3 行から溢れる）。
        // 全部外すと天気カードが数字だけになるので、空になったら既定へ戻す。
        weatherFields = d.weatherFields
            .filter { it in DEFAULT_WEATHER_FIELDS }
            .distinct()
            .ifEmpty { DEFAULT_WEATHER_FIELDS },
    )

    /**
     * ブラウズのお気に入りを書き換える。整形（重複・件数・名前の長さ）が必ず効くよう、入口はこれ 1 つ。
     */
    fun updateFavorites(mutate: (List<Favorite>) -> List<Favorite>): Config = update { c ->
        c.copy(browser = sanitizeBrowser(c.browser.copy(favorites = mutate(c.browser.favorites))))
    }

    private fun sanitizeBrowser(b: BrowserConfig) = b.copy(
        favorites = b.favorites
            .map { it.copy(url = it.url.trim(), title = it.title.trim().take(MAX_FAVORITE_TITLE)) }
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
            .distinctBy { it.url }
            .take(MAX_FAVORITES),
    )

    private fun sanitizeRefresh(r: RefreshConfig) = r.copy(
        // 極端な値でバッテリーと発熱が悪化しないよう、両端を固定する
        wifiIntervalMs = r.wifiIntervalMs.coerceIn(1_000, 60_000),
        weatherIntervalMs = r.weatherIntervalMs.coerceIn(300_000, 3_600_000),
    )

    private fun writeAtomically(config: Config) {
        runCatching {
            tmpFile.writeText(json.encodeToString(config))
            // 書き込み途中に電源が落ちても config.json が壊れないよう、一時ファイル経由で差し替える
            if (!tmpFile.renameTo(file)) {
                file.writeText(tmpFile.readText())
                tmpFile.delete()
            }
        }.onFailure { Log.e(TAG, "config.json の保存に失敗", it) }
    }

    companion object {
        private const val TAG = "ConfigStore"

        @Volatile
        private var instance: ConfigStore? = null

        /**
         * プロセス内で 1 つだけ持つ。
         * サービスが書き換えた設定を Activity 側からも遅延なく読めるようにするため
         * （別インスタンスだとそれぞれのキャッシュがずれる）。
         */
        fun getInstance(context: Context): ConfigStore =
            instance ?: synchronized(this) {
                instance ?: ConfigStore(context.applicationContext).also { instance = it }
            }

        const val MAX_FAVORITES = 30
        private const val MAX_FAVORITE_TITLE = 80

        private val ALLOWED_ALIGNS = setOf("left", "center", "right")
        private val ALLOWED_DATE_FORMATS = setOf("ja", "slash")
        private val ALLOWED_HOURLY_MODES = setOf("both", "temp", "precip")
    }
}
