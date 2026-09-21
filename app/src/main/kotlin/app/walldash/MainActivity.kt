package app.walldash

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
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
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.walldash.data.ConfigStore
import app.walldash.data.Favorite
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

    /** いま開いているページを登録する星。メニューは押したときに組み立てる。 */
    private var favStar: TextView? = null

    /** 星の状態と登録名を決めるために、開いているページを覚えておく。 */
    private var currentUrl: String = ""
    private var currentTitle: String = ""

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

    /**
     * 通知音を鳴らす直前のメディア音量。null は「通知のために動かしていない」。
     *
     * WebAudio のゲインで絞る方式では、端末の主音量が小さいときに通知もそのぶん小さくなり、
     * 「主音量は小さいままでも通知だけは聞こえるようにする」ができなかった。
     * そのため主音量そのものを設定値まで動かし、鳴り終わったら必ずここへ戻す。
     */
    private var volumeBeforeNotice: Int? = null

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
     * 通知音のあいだだけ、メディア音量を設定した値へ動かす。
     *
     * [holdMs] 経過後に元の音量へ戻す。鳴っている最中に重ねて呼ばれたら、
     * そのたびに戻す時刻を延ばす（タイマーの連打のように音が続く場合のため）。
     * 元の音量は最初の 1 回だけ覚える。途中で上書きすると、戻す先が
     * 「通知のために上げた音量」になってしまう。
     */
    private fun holdNoticeVolume(holdMs: Long) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        if (max <= 0) return

        val want = ConfigStore.getInstance(this).get().notifications.volume
        val target = Math.round(want * max).toInt().coerceIn(0, max)

        handler.removeCallbacks(restoreVolumeRunnable)
        if (volumeBeforeNotice == null) {
            volumeBeforeNotice = runCatching {
                audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }.getOrNull() ?: return
        }
        // FLAG は 0。音量 UI も操作音も出さずに変える。
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
            .onFailure { Log.w(TAG, "メディア音量を変更できない（マナーモード等）", it) }
        handler.postDelayed(restoreVolumeRunnable, holdMs)
    }

    /** 通知のために動かした音量を元に戻す。動かしていなければ何もしない。 */
    private fun restoreNoticeVolume() {
        handler.removeCallbacks(restoreVolumeRunnable)
        val saved = volumeBeforeNotice ?: return
        volumeBeforeNotice = null
        val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
            .onFailure { Log.w(TAG, "メディア音量を戻せない", it) }
    }

    private val restoreVolumeRunnable = Runnable { restoreNoticeVolume() }

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

    /*
     * 他のアプリへ移るときは、通知のために上げた音量を必ず戻す。
     * ここで戻さないと、鳴っている途中で離れた場合に上げたままになる。
     */
    override fun onPause() {
        restoreNoticeVolume()
        super.onPause()
    }

    override fun onDestroy() {
        restoreNoticeVolume()
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
                    onBrowserUrlChanged(url)
                }

                override fun onPageFinished(v: WebView, url: String) {
                    onBrowserUrlChanged(url)
                    // onReceivedTitle が来ないページ用の保険。
                    if (currentTitle.isBlank()) currentTitle = cleanTitle(v.title, url)
                    updateFavStar()
                }

                /*
                 * YouTube のように、ページを読み直さず履歴だけ書き換えて中身を差し替える
                 * サイト（history.pushState）は onPageStarted も onPageFinished も通らない。
                 * 動画を選んでも URL 欄が前のままで、★ も切り替わらなかったのはこのため。
                 * URL が変わったことはこちらに来るので、同じ処理をここでも呼ぶ。
                 * 普通の読み込み・戻る進む・# の変化でも呼ばれるが、
                 * onBrowserUrlChanged() が同じ URL を無視するので二重にはならない。
                 */
                override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) {
                    onBrowserUrlChanged(url)
                }
            }

            /*
             * 題名は onPageFinished の時点ではまだ入っていないことがある
             * （実機で Yahoo!ニュースを登録したらホスト名になった）。
             * 確実なのは WebChromeClient 側のこの通知なので、こちらを主に使う。
             */
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(v: WebView, title: String?) {
                    currentTitle = cleanTitle(title, v.url.orEmpty())
                }
            }
        }
        browserView = view

        // いま開いているページの登録・解除。押すたびに ☆ と ★ が入れ替わる。
        val star = toolButton("☆") { toggleFavorite() }.apply { textSize = 20f }
        favStar = star

        // 三本線。いまは「お気に入り」だけだが、あとから項目を足す入口にする。
        val menu = toolButton("\u2630") { showBrowserMenu(it) }.apply { textSize = 18f }

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
            addView(star)
            addView(menu)
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
        favStar = null
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

    // ------------------------------------------------------- お気に入り

    private fun favorites(): List<Favorite> =
        ConfigStore.getInstance(this).get().browser.favorites

    /**
     * 表示中のページが変わったときの共通処理。URL 欄と ★ をそろえる。
     *
     * 呼ばれる経路が 3 つある（読み込み開始・読み込み完了・履歴の書き換え）ので、
     * 同じ URL で重ねて呼ばれても困らないように先頭で弾く。
     */
    private fun onBrowserUrlChanged(url: String) {
        if (url == currentUrl) return
        currentUrl = url
        // 新しいページの題名はまだ来ていない。直後の onReceivedTitle で入る。
        // ここで前のページの題名を残すと、登録名の既定値が別ページのものになる。
        currentTitle = ""
        syncUrlField(url)
        updateFavStar()
    }

    /** URL 欄を表示中のページに合わせる。入力中の文字は消さない。 */
    private fun syncUrlField(url: String) {
        val field = browserUrlField ?: return
        if (field.hasFocus()) return
        if (field.text.toString() != url) field.setText(url)
    }

    /** 登録できるのは実際に開いている http(s) のページだけ。 */
    private fun favoritableUrl(): String? =
        currentUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun updateFavStar() {
        val star = favStar ?: return
        val url = favoritableUrl()
        val saved = url != null && favorites().any { it.url == url }
        star.text = if (saved) "★" else "☆"
        star.setTextColor(
            Color.parseColor(
                when {
                    saved -> "#FFB347"
                    url == null -> "#5B6774"   // 登録できないページでは押せないと分かる灰色
                    else -> "#93A1B1"
                },
            ),
        )
    }

    /**
     * いま開いているページを登録する／登録を外す。
     *
     * 追加と削除を 1 つのボタンにまとめている。壁の前で触るボタンを増やしたくないのと、
     * 「星が付いているかどうか」がそのまま登録状態の表示になるため。
     * 追加するときだけ名前を尋ねる（URL はいま開いているものをそのまま使う）。
     */
    private fun toggleFavorite() {
        val url = favoritableUrl() ?: run {
            toast("このページは登録できません")
            return
        }
        if (favorites().any { it.url == url }) {
            ConfigStore.getInstance(this).updateFavorites { list -> list.filterNot { it.url == url } }
            updateFavStar()
            toast("お気に入りから外しました")
            return
        }
        if (favorites().size >= ConfigStore.MAX_FAVORITES) {
            toast("お気に入りは ${ConfigStore.MAX_FAVORITES} 件までです")
            return
        }
        promptAddFavorite(url)
    }

    /**
     * 名前を決めて登録する。
     *
     * 既定値はページの題名（無ければホスト名）を入れておき、全選択した状態で開く。
     * そのまま「追加」を押せば題名のまま、打ち始めれば置き換わる。
     * ページの題名はサイト名や煽り文句が長く付いていることが多く、
     * 一覧に並べるには自分で短くしたくなるため。
     */
    private fun promptAddFavorite(url: String) {
        val input = EditText(this).apply {
            setText(favoriteTitle(url))
            setSingleLine()
            setSelectAllOnFocus(true)
            setTextColor(Color.parseColor("#E8EEF5"))
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("お気に入りに追加")
            .setMessage(url)
            .setView(box)
            .setPositiveButton("追加") { _, _ ->
                // 空にされたら題名かホスト名に戻す。一覧に無名の行を作らない。
                val name = input.text.toString().trim().ifEmpty { favoriteTitle(url) }
                ConfigStore.getInstance(this).updateFavorites { list ->
                    list + Favorite(url = url, title = name)
                }
                updateFavStar()
                toast("お気に入りに追加しました")
            }
            .setNegativeButton("キャンセル", null)
            .create()

        // 壁の前で触るので、開いた時点でキーボードまで出しておく
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    /**
     * 題名として使えるものだけを残す。
     * <title> の無いページでは WebView が URL をそのまま題名として渡してくるので、
     * それはホスト名に落とすために空として扱う。
     */
    private fun cleanTitle(raw: String?, url: String): String {
        val title = raw?.trim().orEmpty()
        if (title.isEmpty()) return ""
        if (title == url || title.startsWith("http://") || title.startsWith("https://")) return ""
        return title
    }

    /** 登録名の既定値。ページの title が無ければホスト名にする（空欄にはしない）。 */
    private fun favoriteTitle(url: String): String {
        val title = currentTitle.trim()
        if (title.isNotEmpty()) return title
        return runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: url
    }

    // ------------------------------------------------------- メニュー

    /**
     * 三本線のメニュー。いまは「お気に入り」だけだが、
     * ツールバーにボタンを足さずに項目を増やせる入口として置いている。
     */
    private fun showBrowserMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_FAVORITES, 0, "お気に入り")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_FAVORITES -> { showFavoritesDialog(); true }
                    else -> false
                }
            }
            show()
        }
    }

    /**
     * 登録済みの一覧。名前を押せばそこへ移動し、削除も同じ画面からできる。
     * 消しても開いたままにするので、続けて整理できる。
     */
    private fun showFavoritesDialog() {
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            setPadding(dp(14), dp(4), dp(14), dp(4))
            addView(rows)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("お気に入り")
            .setView(scroll)
            .setNegativeButton("閉じる", null)
            .create()

        fun refresh() {
            rows.removeAllViews()
            val saved = favorites()
            if (saved.isEmpty()) {
                rows.addView(
                    TextView(this).apply {
                        text = "まだありません。ページを開いて ☆ を押すと登録できます。"
                        textSize = 13f
                        setTextColor(Color.parseColor("#93A1B1"))
                        setPadding(dp(4), dp(16), dp(4), dp(16))
                    },
                )
                return
            }
            for (fav in saved) rows.addView(favoriteRow(fav, dialog) { refresh() })
        }

        refresh()
        dialog.show()
    }

    /** 一覧の 1 行。左半分が移動、右端が削除。 */
    private fun favoriteRow(fav: Favorite, dialog: AlertDialog, onChanged: () -> Unit): View {
        val label = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 行の大半を当たり判定にする。壁の前では細い文字だけを狙わせない。
            setPadding(dp(4), dp(11), dp(10), dp(11))
            isClickable = true
            addView(
                TextView(this@MainActivity).apply {
                    text = fav.title
                    textSize = 15f
                    setTextColor(Color.parseColor("#E8EEF5"))
                    setSingleLine()
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = fav.url
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#5B6774"))
                    setSingleLine()
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
            setOnClickListener {
                dialog.dismiss()
                openFavorite(fav.url)
            }
        }

        val remove = TextView(this).apply {
            text = "削除"
            textSize = 13f
            setTextColor(Color.parseColor("#FF6B6B"))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#4A2B2B"))
            }
            isClickable = true
            setOnClickListener { confirmRemoveFavorite(fav, onChanged) }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(remove)
        }
    }

    /**
     * お気に入りから開く。
     *
     * URL 欄に焦点が残っていると onPageStarted は欄を書き換えない
     * （打っている途中の文字を消さないための作り）。お気に入りを選んだときは
     * 入力中ではないので、焦点を外して欄も移動先に合わせる。
     */
    private fun openFavorite(url: String) {
        browserUrlField?.clearFocus()
        hideKeyboard()
        browserUrlField?.setText(url)
        browserView?.loadUrl(url)
    }

    private fun confirmRemoveFavorite(fav: Favorite, onDone: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("お気に入りから削除")
            .setMessage("${fav.title}\n${fav.url}")
            .setPositiveButton("削除") { _, _ ->
                ConfigStore.getInstance(this).updateFavorites { list ->
                    list.filterNot { it.url == fav.url }
                }
                updateFavStar()
                onDone()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun toolButton(label: String, onClick: (View) -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.parseColor("#E8EEF5"))
            gravity = Gravity.CENTER
            minWidth = dp(46)
            setPadding(dp(8), dp(9), dp(8), dp(9))
            isClickable = true
            setOnClickListener { onClick(it) }
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
                    /*
                     * 通知音のあいだだけメディア音量を設定値へ動かす。
                     * ms=0 はその場で元へ戻す（タイマーを手で止めたときなど）。
                     * 上限を設けるのは、呼び出し側の不具合で上げっぱなしにしないため。
                     */
                    "volume" -> {
                        val ms = request.url?.getQueryParameter("ms")?.toLongOrNull() ?: 0L
                        if (ms <= 0) restoreNoticeVolume()
                        else holdNoticeVolume(ms.coerceAtMost(MAX_VOLUME_HOLD_MS))
                    }
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
        /** 音量を上げたままにできる上限。 */
        const val MAX_VOLUME_HOLD_MS = 60_000L
        /** 三本線メニューの項目 id。 */
        const val MENU_FAVORITES = 1
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
