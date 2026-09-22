package app.walldash

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.walldash.ui.browser.BrowserScreen
import app.walldash.ui.dashboard.DashboardScreen
import app.walldash.ui.dashboard.DashboardViewModel
import app.walldash.ui.settings.SettingsPanel
import app.walldash.ui.theme.WalldashTheme
import app.walldash.ui.theme.colorOf

/**
 * 壁面に出す画面。ダッシュボード・設定・ブラウズはすべてこの Activity の Compose で描く。
 * ここが受け持つのは、全画面化・消灯防止・明るさ（通常時／無操作時）・権限の確認。
 */
class MainActivity : ComponentActivity() {

    private val vm: DashboardViewModel by viewModels()
    private val graph by lazy { AppGraph.get(this) }

    private var settingsOpen by mutableStateOf(false)
    /** null は閉じている、"" は開始ページ、それ以外はその URL を開く。 */
    private var browserUrl by mutableStateOf<String?>(null)
    private var resumed = false

    private val idleHandler = Handler(Looper.getMainLooper())
    private val dimRunnable = Runnable { applyBrightness(dimmed = true) }
    private var dimmed = false

    /** 通知で明るくする直前の状態。null は「通知のために明るくしてはいない」。 */
    private var dimBeforeNotice: Boolean? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            result.forEach { (permission, granted) -> Log.i(TAG, "権限 $permission: ${if (granted) "許可" else "拒否"}") }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val config by graph.config.flow.collectAsStateWithLifecycle()
            val toast by vm.toast.collectAsStateWithLifecycle()

            LaunchedEffect(config.configVersion) { applyBrightness(dimmed) }
            // 減光中でも通知が読めるよう、バナーが出ている間だけ明るくする
            LaunchedEffect(toast != null) { if (toast != null) wakeForNotice() else restoreAfterNotice() }
            LaunchedEffect(browserUrl) { vm.setActive(browserUrl == null && resumed) }

            WalldashTheme(colorOf(config.display.accent)) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
                    DashboardScreen(vm, onOpenSettings = { settingsOpen = true }, onOpenBrowser = { browserUrl = "" })
                    if (settingsOpen) SettingsPanel(graph, onClose = { settingsOpen = false }, onOpenBrowser = { browserUrl = it })
                    browserUrl?.let { url ->
                        BrowserScreen(graph.config, url.ifEmpty { null }, onClose = { browserUrl = null; hideSystemBars() })
                    }
                }
            }
        }
        requestNeededPermissions()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 通知の途中で人が来たなら、以降は無操作タイマーに任せる（通知が消えた瞬間に暗くしない）
        dimBeforeNotice = null
        resetIdleTimer()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        resetIdleTimer()
        vm.setActive(browserUrl == null)
        // onCreate で呼ぶと、初回の重い初期化と重なって startForeground() の 5 秒制限に間に合わないことがある
        DashboardService.start(applicationContext)
    }

    override fun onPause() {
        resumed = false
        graph.notices.restoreVolume()
        vm.setActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        graph.notices.restoreVolume()
        idleHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun wakeForNotice() {
        if (dimBeforeNotice == null) dimBeforeNotice = dimmed
        applyBrightness(dimmed = false)
    }

    /** 元から明るかったときは通知が消えても暗くしない（人がいる前で急に暗くしないため）。 */
    private fun restoreAfterNotice() {
        val wasDimmed = dimBeforeNotice ?: return
        dimBeforeNotice = null
        if (wasDimmed) applyBrightness(dimmed = true)
    }

    private fun resetIdleTimer() {
        applyBrightness(dimmed = false)
        idleHandler.removeCallbacks(dimRunnable)
        val display = graph.config.get().display
        if (display.idleDimEnabled) idleHandler.postDelayed(dimRunnable, display.idleDimAfterSeconds * 1000L)
    }

    /** 明るさは常にこのウィンドウが決める（端末の自動輝度に任せると壁掛けでは明滅する）。 */
    private fun applyBrightness(dimmed: Boolean) {
        this.dimmed = dimmed
        val display = graph.config.get().display
        window.attributes = window.attributes.apply {
            screenBrightness = (if (dimmed) display.idleDimBrightness else display.normalBrightness).toFloat()
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** SSID の取得に要る権限は API 33 で変わった（32 以下は位置情報、33 以上は付近のデバイス）。 */
    private fun requestNeededPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
