package app.walldash

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Phase 2-2 方式A: 既定ランチャー化の切り替え。
 *
 * Android 10 以降はバックグラウンドから Activity を起動できないため、BOOT_COMPLETED で
 * サービスを起こしてもダッシュボード画面は前面に戻らない。HOME アプリとして登録されていれば
 * 起動時に確実に前面へ出るので、壁掛け運用ではこれを第一候補にする。
 *
 * マニフェスト上の HomeAlias は既定で無効。ここで有効化すると端末の「ホームアプリ」選択肢に現れ、
 * 利用者が既定に選べるようになる。
 */
class LauncherMode(private val context: Context) {

    private val alias = ComponentName(context, "app.walldash.HomeAlias")

    fun isEnabled(): Boolean =
        context.packageManager.getComponentEnabledSetting(alias) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(enabled: Boolean) {
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            alias,
            newState,
            PackageManager.DONT_KILL_APP,
        )
    }
}
