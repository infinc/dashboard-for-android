package app.walldash

import app.walldash.data.Config
import app.walldash.data.MemoConfig
import app.walldash.data.MemoPatch
import app.walldash.data.SaveAllRequest
import app.walldash.data.SpotifyConfig
import app.walldash.data.SpotifyPatch
import app.walldash.server.Auth
import app.walldash.server.DashboardServer
import kotlinx.coroutines.launch

/**
 * 設定の書き換え。アプリの設定画面と Web の設定画面（/api 以下）は、どちらもここを通る。
 * 画面ごとに保存の手順を持つと、片方だけ検査や後処理が抜けるため。
 */
class SettingsController(private val graph: AppGraph) {

    class SettingsException(val code: String, message: String) : Exception(message)

    /** 「全て保存」。1 回の書き込みにまとめ、取得先が変わったものは待たずに取り直す。 */
    fun saveAll(request: SaveAllRequest): Config {
        val before = graph.config.get()
        val updated = graph.config.update { c ->
            var next = graph.config.patched(c, request.settings)
            request.memo?.let { next = next.copy(memo = applyMemo(next.memo, it)) }
            request.spotify?.let { next = next.copy(spotify = applySpotify(next.spotify, it)) }
            next
        }
        if (sourcesChanged(before, updated)) refreshAllLater()
        return updated
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
    }

    fun refreshAllLater() {
        graph.scope.launch { refreshAll() }
    }

    private fun sourcesChanged(a: Config, b: Config): Boolean =
        a.location != b.location || a.units != b.units || a.disaster != b.disaster ||
            a.feed != b.feed || a.memo != b.memo || a.spotify.enabled != b.spotify.enabled

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
