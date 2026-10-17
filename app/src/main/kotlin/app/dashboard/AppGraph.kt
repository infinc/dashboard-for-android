package app.dashboard

import android.content.Context
import app.dashboard.data.AppUpdater
import app.dashboard.data.CalendarRepository
import app.dashboard.data.ConfigStore
import app.dashboard.data.CryptoRepository
import app.dashboard.data.DeviceState
import app.dashboard.data.DeviceStatsMonitor
import app.dashboard.data.DisasterRepository
import app.dashboard.data.FeedRepository
import app.dashboard.data.FlightRepository
import app.dashboard.data.GithubRepository
import app.dashboard.data.HealthRepository
import app.dashboard.data.HolidayRepository
import app.dashboard.data.Http
import app.dashboard.data.LyricsRepository
import app.dashboard.data.MemoRepository
import app.dashboard.data.PhotoRepository
import app.dashboard.data.RainForecast
import app.dashboard.data.ShipStream
import app.dashboard.data.SourceHealth
import app.dashboard.data.SpotifyRepository
import app.dashboard.data.StatusResponse
import app.dashboard.data.StocksRepository
import app.dashboard.data.TodayRepository
import app.dashboard.data.TodoRepository
import app.dashboard.data.TrainRepository
import app.dashboard.data.WallpaperStore
import app.dashboard.data.WeatherRepository
import app.dashboard.data.WifiMonitor
import app.dashboard.data.toPublic
import app.dashboard.i18n.L
import app.dashboard.server.Auth
import app.dashboard.server.DashboardServer
import app.dashboard.sound.NoticePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.TimeZone

/**
 * プロセスに 1 つだけ持つ部品の置き場。
 * サービス（定期取得とサーバー）、Activity（画面）、サーバー（Web の設定画面）が同じ実体を使う。
 */
class AppGraph private constructor(context: Context) {

    val context: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val config = ConfigStore.getInstance(this.context)
    val http = Http.newClient()

    val wifi = WifiMonitor(this.context).also { it.start() }
    val weather = WeatherRepository(this.context, http, config)
    /** 空気・体調のカード（暑さ指数・紫外線・PM2.5）。 */
    val healthRepo = HealthRepository(http, config)
    val disaster = DisasterRepository(this.context, http, config)
    val feed = FeedRepository(http, config)
    val memo = MemoRepository(http, config)
    val spotify = SpotifyRepository(http, config)
    val deviceStats = DeviceStatsMonitor(this.context)
    val wallpaper = WallpaperStore(this.context)
    val train = TrainRepository(this.context, http, config)
    val today = TodayRepository(this.context, http)
    val calendar = CalendarRepository(http, config)
    val stocks = StocksRepository(http, config)
    val crypto = CryptoRepository(http, config)
    val holidays = HolidayRepository(this.context, http)
    val photos = PhotoRepository(config)
    val lyrics = LyricsRepository(this.context, http) { config.get().spotify.saveLyrics }
    val rain = RainForecast(http)
    val flights = FlightRepository(http)
    val ships = ShipStream(config, scope)
    val github = GithubRepository(http, config)
    val todos = TodoRepository(this.context)
    val updater = AppUpdater(this.context, http, BuildConfig.VERSION_LABEL)

    val auth = Auth(config)
    val launcher = LauncherMode(this.context)
    val notices = NoticePlayer(this.context, config)

    /** 外から届いた通知（POST /api/notify）。ダッシュボードの画面が受け取ってバナーと音を出す。画面が無いときは捨てる。 */
    val externalNotices = kotlinx.coroutines.flow.MutableSharedFlow<app.dashboard.data.ExternalNotice>(extraBufferCapacity = 8)
    val settings = SettingsController(this)
    val presets = PresetController(this)
    val server = DashboardServer(this)

    /** 画面と Web の設定画面が読む、いまの全データ。 */
    fun snapshot(): DeviceState = DeviceState(
        serverTime = System.currentTimeMillis(),
        deviceTimezone = TimeZone.getDefault().id,
        wifi = wifi.snapshot(),
        weather = weather.state,
        deviceStats = deviceStats.snapshot(),
        disaster = disaster.state,
        feed = feed.state,
        memo = memo.state,
        spotify = spotify.state,
        train = train.state,
        today = today.state,
        calendar = calendar.state,
        stocks = stocks.state,
        holidays = holidays.upcoming(),
        photos = photos.state,
        crypto = crypto.state,
        github = github.state,
        health = healthRepo.state,
        config = config.get().toPublic(),
    )

    /**
     * 取得先ごとの状況（設定の「取得状況」・/api/status）。「全部の取得が失敗し続ける」ような不具合を、画面から見つけられるように。
     * [SourceHealth.enabled] は、その取得先がいま動いているはずか（設定で使っていない・カードを出していないものは false）。
     */
    fun health(): StatusResponse {
        val c = config.get()
        val d = c.display
        fun h(ja: String, en: String, enabled: Boolean, fetchedAt: Long, lastError: String?) =
            SourceHealth(L(ja, en), enabled, fetchedAt, lastError)
        return StatusResponse(
            sources = listOf(
                weather.state.let { h("天気", "Weather", true, it.fetchedAt, it.lastError) },
                disaster.state.let { h("防災", "Alerts", c.disaster.enabled, it.fetchedAt, it.lastError) },
                feed.state.let { h("ニュース", "News", c.feed.enabled, it.fetchedAt, it.lastError) },
                memo.state.let { h("LINE メモ", "LINE memo", c.memo.enabled, it.fetchedAt, it.lastError) },
                spotify.state.let { h("Spotify", "Spotify", c.spotify.enabled && !c.spotify.refreshToken.isNullOrBlank(), it.fetchedAt, it.lastError) },
                train.state.let { h("運行情報", "Trains", train.active(), it.fetchedAt, it.lastError) },
                calendar.state.let { h("予定表", "Calendar", calendar.active(), it.fetchedAt, it.lastError) },
                today.state.let { h("今日は何の日", "On this day", d.showToday, it.fetchedAt, it.lastError) },
                stocks.state.let { h("株価", "Stocks", d.showStocks, it.fetchedAt, it.lastError) },
                crypto.state.let { h("暗号通貨", "Crypto", d.showCrypto, it.fetchedAt, it.lastError) },
                photos.state.let { h("写真", "Photos", d.showPhotos && c.photos.enabled, it.fetchedAt, it.lastError) },
                github.state.let { h("GitHub", "GitHub", d.showGithub, it.fetchedAt, it.lastError) },
                healthRepo.state.let { h("空気・体調", "Air & health", d.showHealth, it.fetchedAt, it.lastError) },
            ),
            quarantined = config.quarantined(),
        )
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
