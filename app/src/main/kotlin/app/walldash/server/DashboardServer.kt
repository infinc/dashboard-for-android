package app.walldash.server

import android.content.Context
import android.util.Log
import app.walldash.LauncherMode
import app.walldash.data.ApiError
import app.walldash.data.ConfigPatch
import app.walldash.data.ConfigStore
import app.walldash.data.DeviceState
import app.walldash.data.DeviceStatsMonitor
import app.walldash.data.DisasterRepository
import app.walldash.data.FeedRepository
import app.walldash.data.MemoPatch
import app.walldash.data.MemoRepository
import app.walldash.data.SpotifyPatch
import app.walldash.data.SpotifyRepository
import app.walldash.data.SpotifyState
import app.walldash.data.WeatherRepository
import app.walldash.data.WifiMonitor
import app.walldash.data.toPublic
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.TimeZone

/**
 * 内蔵 HTTP サーバー。ダッシュボード表示と設定画面の両方を配信する。
 *
 * 待受は 8080 固定（v1 では設定項目にしない）。WebView の参照先・adb forward・ブラウザ URL が
 * 同時に壊れるため、変更可能にするのは専用の再起動導線を設計してからにする。
 *
 * 待受アドレスは設定に従う:
 *  - 既定           : 127.0.0.1 のみ（USB / adb forward 経由だけで設定できる）
 *  - LAN 公開 ON 時 : 0.0.0.0（PIN 必須。設定画面から明示的に有効化したときだけ）
 */
class DashboardServer(
    private val context: Context,
    private val configStore: ConfigStore,
    private val wifiMonitor: WifiMonitor,
    private val weatherRepository: WeatherRepository,
    private val deviceStatsMonitor: DeviceStatsMonitor,
    private val disasterRepository: DisasterRepository,
    private val feedRepository: FeedRepository,
    private val memoRepository: MemoRepository,
    private val spotifyRepository: SpotifyRepository,
    private val auth: Auth,
    private val launcherMode: LauncherMode,
) {

    private var engine: EmbeddedServer<*, *>? = null

    @Volatile
    var boundHost: String = LOOPBACK
        private set

    fun start() {
        stop()
        val host = if (configStore.get().lan.enabled) ANY else LOOPBACK
        boundHost = host
        engine = embeddedServer(CIO, host = host, port = PORT) { module() }.also {
            it.start(wait = false)
        }
        Log.i(TAG, "サーバー起動: http://$host:$PORT")
    }

    fun stop() {
        engine?.let { runCatching { it.stop(500, 1500) } }
        engine = null
    }

    /** LAN 公開の切り替え後など、待受アドレスを変える必要があるときに呼ぶ。 */
    fun restart() = start()

    // ------------------------------------------------------------- routing

    private fun Application.module() {
        install(ContentNegotiation) { json(apiJson) }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                Log.e(TAG, "リクエスト処理で例外", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiError("internal_error", cause.message)
                )
            }
        }

        routing {
            get("/") { serveAsset(call, "web/index.html") }
            get("/healthz") { call.respondText("ok", ContentType.Text.Plain) }

            get("/static/{path...}") {
                val rel = call.parameters.getAll("path").orEmpty().joinToString("/")
                if (rel.isEmpty() || rel.contains("..")) {
                    call.respond(HttpStatusCode.NotFound); return@get
                }
                serveAsset(call, "web/$rel")
            }

            get("/settings") {
                if (!authorized(call)) {
                    serveAsset(call, "web/login.html", HttpStatusCode.Unauthorized)
                } else {
                    serveAsset(call, "web/settings.html")
                }
            }

            get("/api/state") { call.respond(buildState(call)) }

            post("/api/login") {
                if (!csrfOk(call)) {
                    call.respond(HttpStatusCode.Forbidden, ApiError("bad_origin")); return@post
                }
                val body = call.receive<LoginRequest>()
                when (val result = auth.login(remoteAddress(call), body.pin)) {
                    is Auth.LoginResult.Success -> {
                        call.response.cookies.append(
                            Cookie(
                                name = Auth.SESSION_COOKIE,
                                value = result.token,
                                httpOnly = true,
                                path = "/",
                                maxAge = ((result.expiresAt - System.currentTimeMillis()) / 1000).toInt(),
                                extensions = mapOf("SameSite" to "Strict"),
                            )
                        )
                        call.respond(LoginResponse(ok = true))
                    }

                    is Auth.LoginResult.Failed -> call.respond(
                        HttpStatusCode.Unauthorized,
                        LoginResponse(ok = false, remaining = result.remaining, message = "PIN が違います")
                    )

                    is Auth.LoginResult.Locked -> call.respond(
                        HttpStatusCode.TooManyRequests,
                        LoginResponse(
                            ok = false,
                            retryAfterSeconds = result.retryAfterSeconds,
                            message = "試行回数の上限に達しました。しばらく待ってから再試行してください"
                        )
                    )

                    Auth.LoginResult.NoPinConfigured -> call.respond(
                        HttpStatusCode.Conflict,
                        LoginResponse(ok = false, message = "PIN が未設定です。USB 接続から設定してください")
                    )
                }
            }

            post("/api/logout") {
                auth.logout(call.request.cookies[Auth.SESSION_COOKIE])
                call.response.cookies.append(
                    Cookie(Auth.SESSION_COOKIE, "", path = "/", maxAge = 0)
                )
                call.respond(LoginResponse(ok = true))
            }

            get("/api/settings") {
                if (!authorized(call)) { unauthorized(call); return@get }
                call.respond(configStore.get().toPublic())
            }

            post("/api/settings") {
                if (!guardWrite(call)) return@post
                val patch = call.receive<ConfigPatch>()
                call.respond(configStore.applyPatch(patch).toPublic())
            }

            post("/api/lan") {
                if (!guardWrite(call)) return@post
                val body = call.receive<LanRequest>()
                body.pin?.let { pin ->
                    if (pin.length < Auth.MIN_PIN_LENGTH) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("pin_too_short", "PIN は ${Auth.MIN_PIN_LENGTH} 桁以上必要です")
                        )
                        return@post
                    }
                    auth.setPin(pin)
                }
                if (body.enabled == true && !auth.hasPin()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("pin_required", "LAN 公開を有効にする前に PIN を設定してください")
                    )
                    return@post
                }
                body.enabled?.let { enabled ->
                    configStore.update { c -> c.copy(lan = c.lan.copy(enabled = enabled)) }
                    // 待受アドレスが変わるため、応答を返した後にサーバーを張り替える
                    restartLater()
                }
                call.respond(configStore.get().toPublic())
            }

            get("/api/device") {
                if (!authorized(call)) { unauthorized(call); return@get }
                call.respond(
                    DeviceInfo(
                        launcherHomeEnabled = launcherMode.isEnabled(),
                        boundHost = boundHost,
                        port = PORT,
                        activeSessions = auth.activeSessionCount(),
                        pinSet = auth.hasPin(),
                    )
                )
            }

            post("/api/device") {
                if (!guardWrite(call)) return@post
                val body = call.receive<DeviceRequest>()
                body.launcherHomeEnabled?.let(launcherMode::setEnabled)
                call.respond(
                    DeviceInfo(
                        launcherHomeEnabled = launcherMode.isEnabled(),
                        boundHost = boundHost,
                        port = PORT,
                        activeSessions = auth.activeSessionCount(),
                        pinSet = auth.hasPin(),
                    )
                )
            }

            post("/api/memo") {
                if (!guardWrite(call)) return@post
                val patch = call.receive<MemoPatch>()
                val updated = configStore.update { c ->
                    c.copy(
                        memo = c.memo.copy(
                            enabled = patch.enabled ?: c.memo.enabled,
                            endpoint = patch.endpoint?.let(::normalizeMemoEndpoint) ?: c.memo.endpoint,
                            // 空文字が来たらトークンを消す意図とみなす
                            token = when {
                                patch.token == null -> c.memo.token
                                patch.token.isBlank() -> null
                                else -> patch.token
                            },
                            pollIntervalMs = patch.pollIntervalMs ?: c.memo.pollIntervalMs,
                        )
                    )
                }
                call.respond(updated.toPublic())
            }

            /*
             * Spotify 連携。
             *
             * 認可画面へは「開始」→「折り返し」の 2 本で足りる。折り返し先を
             * 127.0.0.1 の自分自身にしているので、端末の外に認可コードが出ない。
             */
            post("/api/spotify") {
                if (!guardWrite(call)) return@post
                val patch = call.receive<SpotifyPatch>()
                val updated = configStore.update { c ->
                    c.copy(
                        spotify = c.spotify.copy(
                            enabled = patch.enabled ?: c.spotify.enabled,
                            clientId = patch.clientId?.trim() ?: c.spotify.clientId,
                        )
                    )
                }
                call.respond(updated.toPublic())
            }

            get("/api/spotify/start") {
                if (!guardWrite(call)) return@get
                val url = spotifyRepository.authorizeUrl(spotifyRedirectUri())
                if (url == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("client_id_missing", "クライアント ID を保存してから連携してください"),
                    )
                    return@get
                }
                call.respondRedirect(url)
            }

            get("/api/spotify/callback") {
                // 認可画面からの折り返し。Spotify 側は Cookie を持たないため
                // 認証の壁は置けないが、ループバックにしか戻ってこない。
                val error = call.request.queryParameters["error"]
                if (error != null) {
                    call.respondText(
                        spotifyResultHtml("連携できませんでした", error),
                        ContentType.Text.Html,
                    )
                    return@get
                }
                val code = call.request.queryParameters["code"]
                if (code.isNullOrBlank()) {
                    call.respondText(
                        spotifyResultHtml("連携できませんでした", "認可コードがありません"),
                        ContentType.Text.Html,
                    )
                    return@get
                }
                val result = spotifyRepository.exchangeCode(code, spotifyRedirectUri())
                if (result.isSuccess) {
                    runCatching { spotifyRepository.refreshNow() }
                    call.respondText(
                        spotifyResultHtml("Spotify と連携しました", "この画面は閉じて構いません。"),
                        ContentType.Text.Html,
                    )
                } else {
                    call.respondText(
                        spotifyResultHtml(
                            "連携できませんでした",
                            result.exceptionOrNull()?.message ?: "不明なエラー",
                        ),
                        ContentType.Text.Html,
                    )
                }
            }

            post("/api/spotify/control") {
                if (!guardWrite(call)) return@post
                val action = call.request.queryParameters["action"].orEmpty()
                val result = spotifyRepository.control(action)
                if (result.isSuccess) {
                    call.respond(spotifyRepository.state)
                } else {
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ApiError(
                            "spotify_control_failed",
                            result.exceptionOrNull()?.message ?: "操作できません",
                        ),
                    )
                }
            }

            post("/api/spotify/disconnect") {
                if (!guardWrite(call)) return@post
                spotifyRepository.disconnect()
                call.respond(configStore.get().toPublic())
            }

            post("/api/refresh") {
                if (!guardWrite(call)) return@post
                // 設定を変えた直後に反映を待たせないための即時取得
                runCatching { weatherRepository.refreshNow() }
                runCatching { disasterRepository.refreshNow() }
                runCatching { feedRepository.refreshNow() }
                runCatching { memoRepository.refreshNow() }
                runCatching { spotifyRepository.refreshNow() }
                call.respond(buildState(call))
            }

            get("/api/geocode") {
                if (!authorized(call)) { unauthorized(call); return@get }
                val query = call.request.queryParameters["q"]?.trim().orEmpty()
                if (query.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("missing_query")); return@get
                }
                call.respond(weatherRepository.geocode(query))
            }
        }
    }

    // ------------------------------------------------------------- helpers

    private fun remoteAddress(call: ApplicationCall): String =
        // XForwardedHeaders プラグインは入れていないので、ここは実ソケットのアドレスになる。
        call.request.origin.remoteAddress

    private fun authorized(call: ApplicationCall): Boolean =
        auth.isAuthorized(remoteAddress(call), call.request.cookies[Auth.SESSION_COOKIE])

    private suspend fun unauthorized(call: ApplicationCall) =
        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))

    /** 書き込み系の共通ガード: 認証 + CSRF。通れば true。 */
    private suspend fun guardWrite(call: ApplicationCall): Boolean {
        if (!csrfOk(call)) {
            call.respond(HttpStatusCode.Forbidden, ApiError("bad_origin")); return false
        }
        if (!authorized(call)) { unauthorized(call); return false }
        return true
    }

    /**
     * CSRF 対策。ブラウザは GET/HEAD 以外で必ず Origin を送るため、
     * Origin があれば Host と一致することを要求する。
     * Origin が無いのは curl 等の非ブラウザなので、loopback からのみ許可する。
     */
    private fun csrfOk(call: ApplicationCall): Boolean {
        val origin = call.request.headers["Origin"]
            ?: return auth.isLoopback(remoteAddress(call))
        val host = call.request.headers["Host"] ?: return false
        return origin == "http://$host" || origin == "https://$host"
    }

    /**
     * Spotify に登録する折り返し先。
     *
     * ループバック固定にしているのは、Spotify が平文 HTTP を 127.0.0.1 にしか認めないため。
     * PC から `adb forward` 越しに設定している場合も、PC の 127.0.0.1:8080 が
     * そのまま端末へ転送されるので同じ URL で成立する。
     */
    private fun spotifyRedirectUri(): String = "http://127.0.0.1:$PORT/api/spotify/callback"

    private fun spotifyResultHtml(title: String, detail: String): String = """
        <!doctype html><html lang="ja"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Spotify 連携</title></head>
        <body style="background:#0A0C10;color:#E8EEF5;font-family:system-ui,sans-serif;
        display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
        <div style="text-align:center;padding:24px">
        <p style="font-size:20px;margin:0 0 8px">${'$'}{escapeHtml(title)}</p>
        <p style="color:#93A1B1;font-size:14px;margin:0">${'$'}{escapeHtml(detail)}</p>
        </div></body></html>
    """.trimIndent()

    /** 認可の失敗理由は外部由来の文字列なので、HTML に混ぜる前に無害化する。 */
    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun buildState(call: ApplicationCall): DeviceState {
        val full = authorized(call)
        val wifi = wifiMonitor.snapshot()
        val config = configStore.get().toPublic()
        val now = System.currentTimeMillis()
        val timezone = TimeZone.getDefault().id

        if (full) {
            return DeviceState(
                serverTime = now,
                deviceTimezone = timezone,
                wifi = wifi,
                weather = weatherRepository.state,
                deviceStats = deviceStatsMonitor.snapshot(),
                disaster = disasterRepository.state,
                feed = feedRepository.state,
                memo = memoRepository.state,
                spotify = spotifyRepository.state,
                config = config,
                masked = false,
            )
        }

        // LAN からの未認証アクセスには機微な値を返さない。
        // 伏せるもの: SSID / IP アドレス / 正確な緯度経度 / LINE メモの本文。
        //   メモは家族のやり取りが載るため、防災・天気より秘匿性が高い。
        // 返すもの  : 時刻・天気概況・都市名・防災情報（公開情報）。
        return DeviceState(
            serverTime = now,
            deviceTimezone = timezone,
            wifi = wifi.copy(ssid = null, ipAddress = null),
            weather = weatherRepository.state,
            deviceStats = deviceStatsMonitor.snapshot(),
            disaster = disasterRepository.state,
            feed = feedRepository.state,
            memo = memoRepository.state.copy(items = emptyList(), text = null, senderName = null),
            // 何を聴いているかは生活の様子が出るので、LAN の未認証には返さない。
            spotify = SpotifyState(),
            config = config.copy(
                location = config.location.copy(latitude = 0.0, longitude = 0.0)
            ),
            masked = true,
        )
    }

    private suspend fun serveAsset(
        call: ApplicationCall,
        path: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) {
        val bytes = runCatching {
            context.assets.open(path).use { it.readBytes() }
        }.getOrNull()

        if (bytes == null) {
            call.respond(HttpStatusCode.NotFound, ApiError("not_found", path))
            return
        }
        call.respondBytes(bytes, contentTypeFor(path), status)
    }

    private fun contentTypeFor(path: String): ContentType = when (path.substringAfterLast('.', "")) {
        "html" -> ContentType.Text.Html
        "css" -> ContentType.Text.CSS
        "js" -> ContentType.Application.JavaScript
        "json" -> ContentType.Application.Json
        "svg" -> ContentType.Image.SVG
        "png" -> ContentType.Image.PNG
        "woff2" -> ContentType("font", "woff2")
        "woff" -> ContentType("font", "woff")
        else -> ContentType.Application.OctetStream
    }

    /**
     * メモ取得先の URL を正規化する。
     *
     * 利用者は Worker のベース URL や、LINE 用の /line/webhook を貼りがちで、実際そうなった。
     * /line/webhook は POST 専用なので GET すると 404 になり、原因が分かりにくい。
     * タブレットが読むのは常に /memo なので、ホストだけ受け取ってパスはこちらで固定する。
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

    /** 応答を返し終えてから待受を張り替えるための遅延再起動。 */
    private fun restartLater() {
        Thread {
            Thread.sleep(300)
            runCatching { restart() }.onFailure { Log.e(TAG, "サーバー再起動に失敗", it) }
        }.start()
    }

    // ------------------------------------------------------------- 入出力

    @Serializable
    private data class LoginRequest(val pin: String)

    @Serializable
    private data class LoginResponse(
        val ok: Boolean,
        val message: String? = null,
        val remaining: Int? = null,
        val retryAfterSeconds: Long? = null,
    )

    @Serializable
    private data class LanRequest(val enabled: Boolean? = null, val pin: String? = null)

    @Serializable
    private data class DeviceInfo(
        val launcherHomeEnabled: Boolean,
        val boundHost: String,
        val port: Int,
        val activeSessions: Int,
        val pinSet: Boolean,
    )

    @Serializable
    private data class DeviceRequest(val launcherHomeEnabled: Boolean? = null)

    companion object {
        const val PORT = 8080
        private const val TAG = "DashboardServer"
        private const val LOOPBACK = "127.0.0.1"
        private const val ANY = "0.0.0.0"

        private val apiJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
