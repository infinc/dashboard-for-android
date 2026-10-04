package app.dashboard

import app.dashboard.data.CardLayout
import app.dashboard.data.Config
import app.dashboard.data.MemoConfig
import app.dashboard.data.MemoPatch
import app.dashboard.data.SaveAllRequest
import app.dashboard.data.SpotifyConfig
import app.dashboard.data.SpotifyPatch
import app.dashboard.data.WallpaperConfig
import app.dashboard.data.TrainConfig
import app.dashboard.data.TrainPatch
import app.dashboard.data.CalendarConfig
import app.dashboard.data.CalendarPatch
import app.dashboard.data.PhotoConfig
import app.dashboard.data.PhotoPatch
import app.dashboard.data.PhotoRepository
import app.dashboard.data.GITHUB_DAYS
import app.dashboard.data.GithubConfig
import app.dashboard.data.GithubPatch
import app.dashboard.i18n.L
import app.dashboard.server.Auth
import app.dashboard.server.DashboardServer
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * 設定の書き換え。アプリの設定画面と Web の設定画面（/api 以下）は、どちらもここを通る。
 * 画面ごとに保存の手順を持つと、片方だけ検査や後処理が抜けるため。
 */
class SettingsController(private val graph: AppGraph) {

    class SettingsException(val code: String, message: String) : Exception(message)

    /**
     * 「全て保存」。1 回の書き込みにまとめ、取得先が変わったものは待たずに取り直す。
     * カードを増やして画面に収まらなくなる変更は保存しない（設定画面の切り替えで止め損ねた分の最後の砦）。
     * カードの配置は [CardLayout.adjust] で表示するカードに合わせてから保存する。
     */
    fun saveAll(request: SaveAllRequest): Config {
        val before = graph.config.get()
        val settings = request.settings.display?.let { next ->
            val adjusted = CardLayout.adjust(before.display, next, CardLayout.area(graph.context))
            adjusted.message?.let { throw SettingsException("cards_overflow", it) }
            request.settings.copy(display = adjusted.display)
        } ?: request.settings
        val updated = graph.config.update { c ->
            var next = graph.config.patched(c, settings)
            request.memo?.let { next = next.copy(memo = applyMemo(next.memo, it)) }
            request.spotify?.let { next = next.copy(spotify = applySpotify(next.spotify, it)) }
            request.train?.let { next = next.copy(train = applyTrain(next.train, it)) }
            request.calendar?.let { next = next.copy(calendar = applyCalendar(next.calendar, it)) }
            request.photos?.let { next = next.copy(photos = applyPhotos(next.photos, it)) }
            request.ships?.let { next = next.copy(ships = next.ships.copy(apiKey = secret(next.ships.apiKey, it.apiKey))) }
            request.github?.let { next = next.copy(github = applyGithub(next.github, it)) }
            next
        }
        if (sourcesChanged(before, updated)) refreshAllLater()
        return updated
    }

    /**
     * 背景画像を差し替える。「全て保存」を待たずにその場で保存する（画像は下書きに溜められないため）。
     * [open] は画像の中身を読む口で、複数回呼ばれる。
     */
    fun setWallpaper(open: () -> InputStream): Config {
        if (!runCatching { graph.wallpaper.save(open) }.getOrDefault(false)) {
            throw SettingsException("bad_image", L("画像を読み込めませんでした。JPEG・PNG・WebP の画像を選んでください", "Couldn't read the image. Choose a JPEG, PNG or WebP image"))
        }
        return graph.config.update { c -> c.copy(wallpaper = WallpaperConfig(imageSetAt = System.currentTimeMillis())) }
    }

    fun clearWallpaper(): Config {
        graph.wallpaper.clear()
        return graph.config.update { c -> c.copy(wallpaper = WallpaperConfig()) }
    }

    fun setPin(pin: String) {
        if (pin.length < Auth.MIN_PIN_LENGTH) {
            throw SettingsException("pin_too_short", L("PIN は ${Auth.MIN_PIN_LENGTH} 桁以上必要です", "The PIN must be at least ${Auth.MIN_PIN_LENGTH} digits"))
        }
        graph.auth.setPin(pin)
    }

    /** LAN 公開の切り替え。待受アドレスが変わるので、呼び出し側へ応答を返してからサーバーを張り替える。 */
    fun setLanEnabled(enabled: Boolean): Config {
        if (enabled && !graph.auth.hasPin()) {
            throw SettingsException("pin_required", L("LAN 公開を有効にする前に PIN を設定してください", "Set a PIN before enabling LAN access"))
        }
        val updated = graph.config.update { c -> c.copy(lan = c.lan.copy(enabled = enabled)) }
        graph.server.restartLater()
        return updated
    }

    fun setLauncherEnabled(enabled: Boolean) = graph.launcher.setEnabled(enabled)

    /** 他の端末のブラウザから設定画面を開く URL。Wi-Fi の IP が取れないときは null。 */
    fun lanSettingsUrl(): String? =
        graph.wifi.snapshot().ipAddress?.let { "http://$it:${DashboardServer.PORT}/settings" }

    suspend fun refreshAll() {
        runCatching { graph.weather.refreshNow() }
        runCatching { graph.disaster.refreshNow() }
        runCatching { graph.feed.refreshNow() }
        runCatching { graph.memo.refreshNow() }
        runCatching { graph.spotify.refreshNow() }
        runCatching { graph.train.refreshNow() }
        runCatching { graph.calendar.refreshNow() }
        val c = graph.config.get()
        if (c.display.showToday) runCatching { graph.today.refreshNow() }
        if (c.display.showStocks) runCatching { graph.stocks.refreshNow() }
        if (c.display.showCrypto) runCatching { graph.crypto.refreshNow() }
        if (c.display.showCountdown) runCatching { graph.holidays.refreshNow() }
        if (c.display.showPhotos && c.photos.enabled) runCatching { graph.photos.refreshNow() }
        if (c.display.showGithub) runCatching { graph.github.refreshNow() }
    }

    fun refreshAllLater() {
        graph.scope.launch { refreshAll() }
    }

    private fun sourcesChanged(a: Config, b: Config): Boolean =
        a.location != b.location || a.units != b.units || a.disaster != b.disaster ||
            a.feed != b.feed || a.memo != b.memo || a.spotify.enabled != b.spotify.enabled ||
            a.train != b.train || a.calendar != b.calendar || a.stocks != b.stocks || a.crypto != b.crypto ||
            a.photos.enabled != b.photos.enabled || a.photos.albumUrl != b.photos.albumUrl ||
            a.github.user != b.github.user || a.github.token != b.github.token || a.github.days != b.github.days ||
            a.display.language != b.display.language

    private fun applyMemo(current: MemoConfig, patch: MemoPatch) = current.copy(
        enabled = patch.enabled ?: current.enabled,
        endpoint = patch.endpoint?.let(::normalizeMemoEndpoint) ?: current.endpoint,
        // 空文字はトークンを消す意図、null は変更しない
        token = when {
            patch.token == null -> current.token
            patch.token.isBlank() -> null
            else -> patch.token.trim()
        },
        pollIntervalMs = (patch.pollIntervalMs ?: current.pollIntervalMs).coerceIn(10_000, 600_000),
    )

    private fun applyTrain(current: TrainConfig, patch: TrainPatch) = current.copy(
        enabled = patch.enabled ?: current.enabled,
        token = secret(current.token, patch.token),
        challengeToken = secret(current.challengeToken, patch.challengeToken),
        railways = patch.railways?.map { it.trim() }?.filter { it.startsWith("odpt.Railway:") }?.distinct()?.take(12) ?: current.railways,
    )

    private fun applyCalendar(current: CalendarConfig, patch: CalendarPatch) = current.copy(
        enabled = patch.enabled ?: current.enabled,
        mode = patch.mode?.takeIf { it == "caldav" || it == "ics" } ?: current.mode,
        appleId = patch.appleId?.trim() ?: current.appleId,
        password = secret(current.password, patch.password),
        icsUrl = secret(current.icsUrl, patch.icsUrl),
        daysAhead = (patch.daysAhead ?: current.daysAhead).coerceIn(1, 31),
    )

    private fun applyPhotos(current: PhotoConfig, patch: PhotoPatch) = current.copy(
        enabled = patch.enabled ?: current.enabled,
        albumUrl = secret(current.albumUrl, patch.albumUrl),
        // 選択肢に無い秒数は、いちばん近い選択肢に丸める
        intervalSec = (patch.intervalSec ?: current.intervalSec).let { s -> PhotoRepository.INTERVALS.minBy { kotlin.math.abs(it - s) } },
        shuffle = patch.shuffle ?: current.shuffle,
    )

    private fun applyGithub(current: GithubConfig, patch: GithubPatch) = current.copy(
        // URL（https://github.com/name）や先頭の @ を貼られても、ユーザー名だけにする
        user = patch.user?.trim()?.removePrefix("@")?.substringAfterLast("github.com/")?.trim('/')?.substringBefore('/')?.take(39) ?: current.user,
        token = secret(current.token, patch.token),
        days = patch.days?.let { d -> GITHUB_DAYS.minBy { kotlin.math.abs(it - d) } } ?: current.days,
        showGraph = patch.showGraph ?: current.showGraph,
        showCommits = patch.showCommits ?: current.showCommits,
        showPulls = patch.showPulls ?: current.showPulls,
        showIssues = patch.showIssues ?: current.showIssues,
        showRepos = patch.showRepos ?: current.showRepos,
        showProfile = patch.showProfile ?: current.showProfile,
    )

    /** 書き込み専用の値。null は変更しない、空文字は消す。 */
    private fun secret(current: String?, patch: String?) = when {
        patch == null -> current
        patch.isBlank() -> null
        else -> patch.trim()
    }

    private fun applySpotify(current: SpotifyConfig, patch: SpotifyPatch) = current.copy(
        enabled = patch.enabled ?: current.enabled,
        clientId = patch.clientId?.trim() ?: current.clientId,
    )

    /**
     * 利用者は Worker のベース URL や /line/webhook を貼りがちなので、ホストだけ受け取って
     * パスは /memo に固定する（/line/webhook は POST 専用で、GET すると 404 になる）。
     */
    private fun normalizeMemoEndpoint(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val uri = java.net.URI(trimmed)
            if (uri.scheme == null || uri.authority == null) return@runCatching trimmed
            java.net.URI(uri.scheme, uri.authority, "/memo", null, null).toString()
        }.getOrElse { trimmed }
    }
}
