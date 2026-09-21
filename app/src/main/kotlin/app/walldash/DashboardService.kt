package app.walldash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.walldash.data.ConfigStore
import app.walldash.data.DeviceStatsMonitor
import app.walldash.data.DisasterRepository
import app.walldash.data.FeedRepository
import app.walldash.data.Http
import app.walldash.data.MemoRepository
import app.walldash.data.SpotifyRepository
import app.walldash.data.WeatherRepository
import app.walldash.data.WifiMonitor
import app.walldash.server.Auth
import app.walldash.server.DashboardServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ダッシュボードの常駐サービス。
 *
 * フォアグラウンドサービスにしている理由は、アプリが背面に回っても
 * LAN / USB からの設定アクセスと天気の定期取得を生かし続けるため。
 * foregroundServiceType は specialUse（マニフェストで用途説明の <property> も宣言済み）。
 */
class DashboardService : LifecycleService() {

    @Volatile private var wifiMonitor: WifiMonitor? = null
    @Volatile private var server: DashboardServer? = null
    @Volatile private var httpClient: io.ktor.client.HttpClient? = null

    override fun onCreate() {
        super.onCreate()

        // 何よりも先にフォアグラウンド化する。
        // startForegroundService() から 5 秒以内に startForeground() を呼ばないと
        // RemoteServiceException でプロセスごと落とされる。WebView の初期化が重い端末では、
        // 先に重い初期化を置くとこの 5 秒を簡単に超える（実機で実際に落ちた）。
        runCatching { startForegroundCompat() }
            .onFailure { Log.e(TAG, "startForeground に失敗", it) }

        // 残りの初期化はメインスレッドを塞がないよう別スレッドで行う。
        lifecycleScope.launch(Dispatchers.IO) {
            val app = applicationContext
            val config = ConfigStore.getInstance(app)
            val client = Http.newClient().also { httpClient = it }

            val wifi = WifiMonitor(app).also { it.start() }
            val weather = WeatherRepository(app, client, config)
            val disaster = DisasterRepository(app, client, config)
            val feed = FeedRepository(client, config)
            val memo = MemoRepository(client, config)
            val spotify = SpotifyRepository(client, config)
            val deviceStats = DeviceStatsMonitor(app)

            val dashboardServer = DashboardServer(
                context = app,
                configStore = config,
                wifiMonitor = wifi,
                weatherRepository = weather,
                deviceStatsMonitor = deviceStats,
                disasterRepository = disaster,
                feedRepository = feed,
                memoRepository = memo,
                spotifyRepository = spotify,
                auth = Auth(config),
                launcherMode = LauncherMode(app),
            )
            wifiMonitor = wifi
            server = dashboardServer

            runCatching { dashboardServer.start() }
                .onSuccess { Log.i(TAG, "サーバー起動完了") }
                .onFailure { Log.e(TAG, "サーバーの起動に失敗", it) }

            /*
             * 再生中の曲は数分で変わるので、他より短い間隔で見に行く。
             * 15 秒間隔の輪に混ぜると、曲が変わってから壁に出るまでが目に見えて遅れる。
             * 未連携のときは即座に戻るので、回しても負荷にはならない。
             */
            launch {
                while (isActive) {
                    runCatching { spotify.refreshIfDue() }
                        .onFailure { Log.w(TAG, "Spotify の定期取得でエラー", it) }
                    delay(SPOTIFY_TICK_MS)
                }
            }

            // 各取得先は自分の間隔を持っているので、ここは一定間隔で声をかけるだけ。
            // 1 つが失敗しても他を止めない。
            while (isActive) {
                runCatching { weather.refreshIfDue() }
                    .onFailure { Log.w(TAG, "天気の定期取得でエラー", it) }
                runCatching { disaster.refreshIfDue() }
                    .onFailure { Log.w(TAG, "防災情報の定期取得でエラー", it) }
                runCatching { feed.refreshIfDue() }
                    .onFailure { Log.w(TAG, "フィードの定期取得でエラー", it) }
                runCatching { memo.refreshIfDue() }
                    .onFailure { Log.w(TAG, "メモの定期取得でエラー", it) }
                delay(TICK_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // 端末が何らかの理由でサービスを落としても復帰させる
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { server?.stop() }
        runCatching { wifiMonitor?.stop() }
        runCatching { httpClient?.close() }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                description = "壁掛けダッシュボードの常時稼働を示す通知です"
            }
            manager.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("http://127.0.0.1:${DashboardServer.PORT}")
            .setSmallIcon(R.drawable.ic_stat_walldash)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "DashboardService"
        private const val CHANNEL_ID = "walldash_running"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_MS = 15_000L
        private const val SPOTIFY_TICK_MS = 5_000L

        fun start(context: Context) {
            val intent = Intent(context, DashboardService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.e(TAG, "サービス起動に失敗", it) }
        }
    }
}
