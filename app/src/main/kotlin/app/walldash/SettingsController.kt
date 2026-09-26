package app.walldash

import app.walldash.data.CardLayout
import app.walldash.data.Config
import app.walldash.data.MemoConfig
import app.walldash.data.MemoPatch
import app.walldash.data.SaveAllRequest
import app.walldash.data.SpotifyConfig
import app.walldash.data.SpotifyPatch
import app.walldash.data.WallpaperConfig
import app.walldash.data.TrainConfig
import app.walldash.data.TrainPatch
import app.walldash.data.CalendarConfig
import app.walldash.data.CalendarPatch
import app.walldash.server.Auth
import app.walldash.server.DashboardServer
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
     */
    fun saveAll(request: SaveAllRequest): Config {
        val before = graph.config.get()
        request.settings.display?.let { next ->
            CardLayout.overflowMessage(before.display, next, CardLayout.area(graph.context))
                ?.let { throw SettingsException("cards_overflow", it) }
        }
        val updated = graph.config.update { c ->
            var next = graph.config.patched(c, request.settings)
            request.memo?.let { next = next.copy(memo = applyMemo(next.memo, it)) }
            request.spotify?.let { next = next.copy(spotify = applySpotify(next.spotify, it)) }
            request.train?.let { next = next.copy(train = applyTrain(next.train, it)) }
            request.calendar?.let { next = next.copy(calendar = applyCalendar(next.calendar, it)) }
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
            throw SettingsException("bad_image", "画像を読み込めませんでした。JPEG・PNG・WebP の画像を選んでください")
        }
        return graph.config.update { c -> c.copy(wallpaper = WallpaperConfig(imageSetAt = System.currentTimeMillis())) }
    }

    fun clearWallpaper(): Config {
        graph.wallpaper.clear()
        return graph.config.update { c -> c.copy(wallpaper = WallpaperConfig()) }
    }

    fun setPin(pin: String) {
        if (pin.length < Auth.MIN_PIN_LENGTH) {
            throw SettingsException("pin_too_short", "PIN は ${Auth.MIN_PIN_LENGTH} 桁以上必要です")
        }
        graph.auth.setPin(pin)
    }

    /** LAN 公開の切り替え。待受アドレスが変わるので、呼び出し側へ応答を返してからサーバーを張り替える。 */
    fun setLanEnabled(enabled: Boolean): Config {
        if (enabled && !graph.auth.hasPin()) {
            throw SettingsException("pin_required", "LAN 公開を有効にする前に PIN を設定してください")
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
        if (c.display.showCountdown) runCatching { graph.holidays.refreshNow() }
    }

    fun refreshAllLater() {
        graph.scope.launch { refreshAll() }
    }

    private fun sourcesChanged(a: Config, b: Config): Boolean =
        a.location != b.location || a.units != b.units || a.disaster != b.disaster ||
            a.feed != b.feed || a.memo != b.memo || a.spotify.enabled != b.spotify.enabled ||
            a.train != b.train || a.calendar != b.calendar || a.stocks != b.stocks

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
