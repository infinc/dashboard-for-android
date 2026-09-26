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
import app.walldash.server.DashboardServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常駐サービス。定期取得と、PC・他端末向けの設定サーバーを生かし続ける。
 * 画面が背面に回っても止めないために前面サービスにしている（種別は specialUse）。
 */
class DashboardService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()

        // startForegroundService() から 5 秒以内に startForeground() を呼ばないとプロセスごと落とされる。
        // 重い初期化より必ず先に置く（実機で実際に落ちた）。
        runCatching { startForegroundCompat() }
            .onFailure { Log.e(TAG, "startForeground に失敗", it) }

        lifecycleScope.launch(Dispatchers.IO) {
            val graph = AppGraph.get(applicationContext)
            runCatching { graph.server.start() }
                .onFailure { Log.e(TAG, "サーバーの起動に失敗", it) }

            // 曲は数分で変わるので、他より短い間隔で見に行く
            launch {
                while (isActive) {
                    runCatching { graph.spotify.refreshIfDue() }
                        .onFailure { Log.w(TAG, "Spotify の定期取得でエラー", it) }
                    delay(SPOTIFY_TICK_MS)
                }
            }

            // 各取得先が自分の間隔を持っているので、ここは声をかけるだけ。1 つの失敗で他を止めない。
            while (isActive) {
                runCatching { graph.weather.refreshIfDue() }
                    .onFailure { Log.w(TAG, "天気の定期取得でエラー", it) }
                runCatching { graph.disaster.refreshIfDue() }
                    .onFailure { Log.w(TAG, "防災情報の定期取得でエラー", it) }
                runCatching { graph.feed.refreshIfDue() }
                    .onFailure { Log.w(TAG, "フィードの定期取得でエラー", it) }
                runCatching { graph.memo.refreshIfDue() }
                    .onFailure { Log.w(TAG, "メモの定期取得でエラー", it) }
                runCatching { graph.train.refreshIfDue() }
                    .onFailure { Log.w(TAG, "運行情報の定期取得でエラー", it) }
                runCatching { graph.calendar.refreshIfDue() }
                    .onFailure { Log.w(TAG, "予定の定期取得でエラー", it) }
                // アカウントの要らない取得先は、カードを出しているときだけ通信する
                val d = graph.config.get().display
                val cd = graph.config.get().countdown.builtins
                runCatching { graph.today.refreshIfDue(d.showToday) }
                    .onFailure { Log.w(TAG, "今日は何の日の定期取得でエラー", it) }
                runCatching { graph.stocks.refreshIfDue(d.showStocks) }
                    .onFailure { Log.w(TAG, "株価の定期取得でエラー", it) }
                runCatching { graph.holidays.refreshIfDue(d.showCountdown && ("holiday" in cd || "dayoff" in cd)) }
                    .onFailure { Log.w(TAG, "祝日の定期取得でエラー", it) }
                delay(TICK_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { AppGraph.get(applicationContext).server.stop() }
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
            .setContentText("設定: http://127.0.0.1:${DashboardServer.PORT}/settings")
            .setSmallIcon(R.drawable.ic_stat_walldash)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
