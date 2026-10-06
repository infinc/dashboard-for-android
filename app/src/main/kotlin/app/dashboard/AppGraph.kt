package app.dashboard

import android.content.Context
import app.dashboard.data.CalendarRepository
import app.dashboard.data.ConfigStore
import app.dashboard.data.CryptoRepository
import app.dashboard.data.DeviceState
import app.dashboard.data.DeviceStatsMonitor
import app.dashboard.data.DisasterRepository
import app.dashboard.data.FeedRepository
import app.dashboard.data.FlightRepository
import app.dashboard.data.GithubRepository
import app.dashboard.data.HolidayRepository
import app.dashboard.data.Http
import app.dashboard.data.LyricsRepository
import app.dashboard.data.MemoRepository
import app.dashboard.data.PhotoRepository
import app.dashboard.data.RainForecast
import app.dashboard.data.ShipStream
import app.dashboard.data.SpotifyRepository
import app.dashboard.data.StocksRepository
import app.dashboard.data.TodayRepository
import app.dashboard.data.TodoRepository
import app.dashboard.data.TrainRepository
import app.dashboard.data.WallpaperStore
import app.dashboard.data.WeatherRepository
import app.dashboard.data.WifiMonitor
import app.dashboard.data.toPublic
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

    val auth = Auth(config)
    val launcher = LauncherMode(this.context)
    val notices = NoticePlayer(this.context, config)
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
        config = config.get().toPublic(),
    )

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
