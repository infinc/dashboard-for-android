package app.walldash

import android.content.Context
import app.walldash.data.CalendarRepository
import app.walldash.data.ConfigStore
import app.walldash.data.DeviceState
import app.walldash.data.DeviceStatsMonitor
import app.walldash.data.DisasterRepository
import app.walldash.data.FeedRepository
import app.walldash.data.HolidayRepository
import app.walldash.data.Http
import app.walldash.data.MemoRepository
import app.walldash.data.SpotifyRepository
import app.walldash.data.StocksRepository
import app.walldash.data.TodayRepository
import app.walldash.data.TrainRepository
import app.walldash.data.WallpaperStore
import app.walldash.data.WeatherRepository
import app.walldash.data.WifiMonitor
import app.walldash.data.toPublic
import app.walldash.server.Auth
import app.walldash.server.DashboardServer
import app.walldash.sound.NoticePlayer
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
    val holidays = HolidayRepository(this.context, http)

    val auth = Auth(config)
    val launcher = LauncherMode(this.context)
    val notices = NoticePlayer(this.context, config)
    val settings = SettingsController(this)
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
