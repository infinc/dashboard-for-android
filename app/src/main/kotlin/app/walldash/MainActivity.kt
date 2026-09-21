package app.walldash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.walldash.data.ConfigStore
import app.walldash.server.DashboardServer

/**
 * 壁面に出す画面そのもの。全画面 WebView で内蔵サーバーのダッシュボードを表示するだけ。
 *
 * UI の実体は assets/web 以下の HTML/CSS/JS 側にあるため、この Activity は
 * 「全画面にする」「画面を消さない」「サーバーが立ち上がるまで再試行する」ことに徹する。
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var loadAttempts = 0

    /** ブラウズ中だけ存在する。ホームに戻したら破棄してメモリを返す。 */
    private var browserLayer: LinearLayout? = null
    private var browserView: WebView? = null
    private var browserUrlField: EditText? = null

    /**
     * 戻るキーはブラウズ中だけ拾う。ダッシュボード表示中の挙動は今までどおりにしておく
     * （壁掛けの常用画面なので、ここで握ると閉じられなくなる端末が出る）。
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val view = browserView
            if (view != null && view.canGoBack()) view.goBack() else hideBrowser()
        }
    }

    private val idleHandler = Handler(Looper.getMainLooper())
    private val dimRunnable = Runnable { applyBrightness(dimmed = true) }
    private var dimmed = false

    /** 通知で明るくする直前の状態。null は「通知のために明るくしてはいない」。 */
    private var dimBeforeNotice: Boolean? = null
    private var lastConfigVersion = -1L

    /*
     * 設定はサーバー側（同一プロセス）で書き換わるが、Activity は書き換わったことを知らない。
     * 明るさを変えた直後に反映されないと調整しづらいので、configVersion を短い間隔で見て
     * 変化していたら明るさを当て直す。WebView との橋渡しを足すより単純で、面も増えない。
     */
    private val configWatcher = object : Runnable {
        override fun run() {
            val version = ConfigStore.getInstance(this@MainActivity).get().configVersion
            if (version != lastConfigVersion) {
                lastConfigVersion = version
                applyBrightness(dimmed)
            }
            idleHandler.postDelayed(this, CONFIG_WATCH_MS)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            result.forEach { (permission, granted) ->
                Log.i(TAG, "権限 $permission: ${if (granted) "許可" else "拒否"}")
            }
            // 拒否されてもダッシュボードは動く。SSID 欄に理由が出るだけ。
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // デバッグビルドだけ Chrome DevTools から中を見られるようにする。
        // 壁掛け UI は実機の WebView（Chrome 81 相当）でしか再現しない不具合が出るため、
        // PC から chrome://inspect で繋いで確認できる経路を残しておく。
        if (0 != (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0C10"))
            overScrollMode = View.OVER_SCROLL_NEVER
            isLongClickable = false
            setOnLongClickListener { true }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // 壁面表示なのでユーザーによる拡大縮小は無効にする
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
                mediaPlaybackRequiresUserGesture = false
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            }
            webViewClient = RetryingWebViewClient()
        }

        // ブラウザ画面をダッシュボードの上に重ねられるよう、入れ物を挟む。
        root = FrameLayout(this).apply {
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, backCallback)

        requestNeededPermissions()
        loadDashboard()
        idleHandler.post(configWatcher)
    }

    /** 画面に触れたら明るさを戻し、無操作タイマーを張り直す。 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 通知の途中で人が来たなら、以降は無操作タイマーに任せる。
        // ここで覚えを消さないと、通知が消えた瞬間に目の前で暗くなる。
        dimBeforeNotice = null
        resetIdleTimer()
        return super.dispatchTouchEvent(ev)
    }

    /*
     * 通知バナーが出ている間だけ画面を明るくする。
     *
     * 音だけ鳴っても、減光中の壁掛けでは何が起きたか読めない。
     * 元から明るかったときは通知が消えても暗くしない（人がいる前で急に暗くしないため）。
     * 無操作タイマーはそのままなので、誰も来なければいずれ通常どおり減光される。
     */
    private fun wakeForNotice() {
        if (dimBeforeNotice == null) dimBeforeNotice = dimmed
        applyBrightness(dimmed = false)
    }

    private fun restoreAfterNotice() {
        val wasDimmed = dimBeforeNotice ?: return
        dimBeforeNotice = null
        if (wasDimmed) applyBrightness(dimmed = true)
    }

    /**
     * 一定時間タッチが無ければバックライトを落とす。
     * CSS で暗くするのではなくウィンドウの輝度を下げているので、
     * 消費電力と焼き付きの両方に効く。触れば即座に戻る。
     */
    private fun resetIdleTimer() {
        applyBrightness(dimmed = false)
        idleHandler.removeCallbacks(dimRunnable)
        val display = ConfigStore.getInstance(this).get().display
        if (display.idleDimEnabled) {
            idleHandler.postDelayed(dimRunnable, display.idleDimAfterSeconds * 1000L)
        }
    }

    /**
     * 明るさは常にこのウィンドウが決める。
     * 操作があるうちは通常時の明るさ、無操作が続いたら減光後の明るさ。
     * 端末側の自動輝度に任せると壁掛けでは明滅するため、両方ともここで固定する。
     */
    private fun applyBrightness(dimmed: Boolean) {
        this.dimmed = dimmed
        val display = ConfigStore.getInstance(this).get().display
        val params = window.attributes
        params.screenBrightness =
            (if (dimmed) display.idleDimBrightness else display.normalBrightness).toFloat()
        window.attributes = params
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        resetIdleTimer()
        // サービス起動は onCreate ではなくここで行う。onCreate の中で呼ぶと、
        // WebView の生成が終わるまでメインスレッドが塞がり、startForeground() の
        // 5 秒制限に間に合わずにプロセスごと落ちることがある。
        // 起動直後はサーバーがまだ無いため WebView は失敗するが、再試行で追いつく。
        DashboardService.start(applicationContext)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        idleHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        browserView?.destroy()
        browserView = null
        webView.destroy()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun loadDashboard() {
        webView.loadUrl("http://127.0.0.1:${DashboardServer.PORT}/")
    }

    // ------------------------------------------------------------ ブラウザ

    /*
     * ダッシュボードの上に重ねる簡易ブラウザ。
     *
     * iframe では出せない。多くのサイトが X-Frame-Options で枠内表示を拒むため、
     * 実際に見えるのは自前のページだけになってしまう。素の WebView を別に持つ。
     *
     * ホームに戻したら破棄する。メモリ 1.8GB の端末で 2 画面ぶんの WebView を
     * 抱え続けないため（設定パネルを iframe で読み捨てているのと同じ考え方）。
     * 副次的に、見ていたページやログイン状態が壁に出しっぱなしにならない。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun showBrowser(startUrl: String? = null) {
        if (browserLayer != null) {
            // すでに開いているなら、指定があればそこへ移すだけ
            startUrl?.let { browserView?.loadUrl(it) }
            return
        }

        val layer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A0C10"))
            // 下の層（ダッシュボード）へタップが抜けないようにする
            isClickable = true
        }

        val field = EditText(this).apply {
            setSingleLine()
            hint = "URL または検索語"
            textSize = 14f
            setTextColor(Color.parseColor("#E8EEF5"))
            setHintTextColor(Color.parseColor("#5B6774"))
            setBackgroundColor(Color.parseColor("#0A0C10"))
            setPadding(dp(12), dp(9), dp(12), dp(9))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            // 触れたら今の URL を選択状態にする。そうしないと打った文字が
            // 表示中の URL の後ろに繋がってしまう（普通のブラウザと同じ挙動にする）。
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, event ->
                // IME の「実行」だけでなく、外付けキーボードや adb からの Enter も拾う
                val go = actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                        event.action == KeyEvent.ACTION_DOWN)
                if (go) { browserGo(text.toString()); true } else false
            }
        }
        browserUrlField = field

        val view = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0C10"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // ダッシュボードと違い、ここは普通のブラウザとして使うので拡大を許す
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, url: String, icon: android.graphics.Bitmap?) {
                    // 入力中に書き換えると打っている途中の文字が消えるので、焦点が無いときだけ
                    if (browserUrlField?.hasFocus() != true) browserUrlField?.setText(url)
                }
            }
        }
        browserView = view

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#12161D"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(toolButton("ホーム") { hideBrowser() })
            addView(toolButton("◀") { if (view.canGoBack()) view.goBack() })
            addView(toolButton("▶") { if (view.canGoForward()) view.goForward() })
            addView(toolButton("⟳") { view.reload() })
            addView(
                field,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(6) },
            )
        }

        layer.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        layer.addView(
            view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(
            layer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        browserLayer = layer

        // 裏のダッシュボードは止めておく。2 秒ごとの取得と描画を続ける意味がない。
        // pauseTimers() はプロセス内の全 WebView に効いてしまうので使わない。
        webView.onPause()

        // ソフトキーボードが URL 欄を隠さないよう、ブラウズ中だけ通常のインセット処理に戻す
        WindowCompat.setDecorFitsSystemWindows(window, true)
        backCallback.isEnabled = true
        view.loadUrl(startUrl?.takeIf { it.startsWith("http") } ?: HOME_URL)
    }

    private fun hideBrowser() {
        val layer = browserLayer ?: return
        backCallback.isEnabled = false
        hideKeyboard()
        browserView?.let {
            it.stopLoading()
            layer.removeView(it)
            it.destroy()
        }
        browserView = null
        browserUrlField = null
        root.removeView(layer)
        browserLayer = null

        webView.onResume()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
    }

    /**
     * 入力された文字列を URL として開く。
     * スキーム付きならそのまま、空白が無くドットを含むならホスト名、それ以外は検索語とみなす。
     */
    private fun browserGo(input: String) {
        val query = input.trim()
        if (query.isEmpty()) return
        val target = when {
            query.startsWith("http://") || query.startsWith("https://") -> query
            !query.contains(' ') && query.contains('.') -> "https://$query"
            else -> "https://www.google.com/search?q=" + Uri.encode(query)
        }
        browserView?.loadUrl(target)
        hideKeyboard()
    }

    private fun toolButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.parseColor("#E8EEF5"))
            gravity = Gravity.CENTER
            minWidth = dp(46)
            setPadding(dp(8), dp(9), dp(8), dp(9))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    /**
     * Activity の起動直後はサービス内のサーバーがまだ待受を開いていないことがある。
     * 数回だけ間隔を空けて再読込し、それでも駄目なら理由を画面に出す。
     */
    private inner class RetryingWebViewClient : WebViewClient() {
        /*
         * ダッシュボードの「ブラウズ」ボタンは walldash://browser へ遷移しようとする。
         * JavascriptInterface を足すより面が小さく、127.0.0.1 の自前ページ以外からは
         * そもそも呼ばれない（このクライアントはダッシュボード側の WebView にしか付けない）。
         */
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            if (request.url?.scheme == APP_SCHEME) {
                when (request.url?.host) {
                    // url を付けて呼べる（Spotify の認可画面をここから開くのに使う）。
                    // 付いていなければ開始ページを出す。
                    "browser" -> showBrowser(request.url?.getQueryParameter("url"))
                    "wake" -> wakeForNotice()
                    "restore" -> restoreAfterNotice()
                }
                return true
            }
            return false
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame) return
            if (loadAttempts >= MAX_LOAD_ATTEMPTS) {
                view.loadDataWithBaseURL(
                    null,
                    FAILURE_HTML,
                    "text/html",
                    "utf-8",
                    null,
                )
                return
            }
            loadAttempts += 1
            handler.postDelayed({ loadDashboard() }, RETRY_DELAY_MS)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (url.startsWith("http://127.0.0.1")) loadAttempts = 0
        }
    }

    private fun requestNeededPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                // API 33 以降は位置情報ではなくこちらで SSID を取得する
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                // API 32 以下は SSID 取得に位置情報が必要
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private companion object {
        const val TAG = "MainActivity"
        /** ダッシュボードのページから Activity 側の機能を呼ぶための独自スキーム。 */
        const val APP_SCHEME = "walldash"
        /**
         * ブラウザの開始ページ。壁の前で長い URL を打つのは現実的でないので、
         * 検索から始められるようにしている。
         */
        const val HOME_URL = "https://www.google.com"
        const val MAX_LOAD_ATTEMPTS = 20
        const val RETRY_DELAY_MS = 500L
        const val CONFIG_WATCH_MS = 3_000L
        val FAILURE_HTML = """
            <html><body style="background:#0A0C10;color:#E8EEF5;font-family:sans-serif;
            display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
            <div style="text-align:center">
            <p style="font-size:20px">内蔵サーバーに接続できません</p>
            <p style="color:#93A1B1;font-size:14px">adb logcat で DashboardService のログを確認してください</p>
            </div></body></html>
        """.trimIndent()
    }
}
