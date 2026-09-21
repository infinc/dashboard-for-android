package app.walldash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 端末再起動とアプリ更新のあとにサービスを起こす。
 *
 * 注意: ここで Activity を起動しても Android 10 以降のバックグラウンド Activity 起動制限で
 * 前面には出ない。画面を壁面表示に戻す手段は [LauncherMode]（HOME アプリ化）側で扱う。
 * ランチャー化していない端末では、再起動後に一度だけ手動でアプリを開く運用になる。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "${intent.action} を受信。サービスを起動する")
                DashboardService.start(context.applicationContext)
            }
        }
    }

    private companion object { const val TAG = "BootReceiver" }
}
