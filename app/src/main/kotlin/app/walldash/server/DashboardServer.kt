package app.walldash.server

import android.util.Log
import app.walldash.AppGraph
import app.walldash.SettingsController
import app.walldash.data.ApiError
import app.walldash.data.CardLayout
import app.walldash.data.DisplayConfig
import app.walldash.data.SaveAllRequest
import app.walldash.data.Tones
import app.walldash.data.WallpaperStore
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
import io.ktor.server.request.contentLength
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

/**
 * 内蔵 HTTP サーバー。PC や他の端末のブラウザから開く設定画面と、その API を配信する。
 * タブレット自身の画面はアプリ（Compose）が描くので、ここは使わない。
 *
 * 待受は既定で 127.0.0.1（USB の adb forward 経由だけ）、LAN 公開 ON のときだけ 0.0.0.0。
 * ポートは 8080 固定（adb forward・ブラウザの URL・Spotify の Redirect URI が同時に壊れるため）。
 */
class DashboardServer(private val graph: AppGraph) {

    private var engine: EmbeddedServer<*, *>? = null
    private val auth get() = graph.auth

    @Volatile
    var boundHost: String = LOOPBACK
        private set

    @Synchronized
    fun start() {
        stop()
        val host = if (graph.config.get().lan.enabled) ANY else LOOPBACK
        boundHost = host
        engine = embeddedServer(CIO, host = host, port = PORT) { module() }.also {
            it.start(wait = false)
        }
        Log.i(TAG, "サーバー起動: http://$host:$PORT")
    }

    @Synchronized
    fun stop() {
        engine?.let { runCatching { it.stop(500, 1500) } }
        engine = null
    }

    /** 応答を返し終えてから待受を張り替える（LAN 公開の切り替え後）。 */
    fun restartLater() {
        Thread {
            Thread.sleep(300)
            runCatching { start() }.onFailure { Log.e(TAG, "サーバー再起動に失敗", it) }
        }.start()
    }

    private fun Application.module() {
        install(ContentNegotiation) { json(apiJson) }
        install(StatusPages) {
            exception<SettingsController.SettingsException> { call, cause ->
                call.respond(HttpStatusCode.BadRequest, ApiError(cause.code, cause.message))
            }
            exception<Throwable> { call, cause ->
                Log.e(TAG, "リクエスト処理で例外", cause)
                call.respond(HttpStatusCode.InternalServerError, ApiError("internal_error", cause.message))
            }
        }

        routing {
            get("/") { call.respondRedirect("/settings") }
            get("/healthz") { call.respondText("ok", ContentType.Text.Plain) }

            get("/static/{path...}") {
                val rel = call.parameters.getAll("path").orEmpty().joinToString("/")
                if (rel.isEmpty() || rel.contains("..")) {
                    call.respond(HttpStatusCode.NotFound); return@get
                }
                serveAsset(call, "web/$rel")
            }

            get("/settings") {
                if (authorized(call)) serveAsset(call, "web/settings.html")
                else serveAsset(call, "web/login.html", HttpStatusCode.Unauthorized)
            }

            get("/api/state") {
                if (!authorized(call)) { unauthorized(call); return@get }
                call.respond(graph.snapshot())
            }

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
                            ),
                        )
                        call.respond(LoginResponse(ok = true))
                    }
                    is Auth.LoginResult.Failed -> call.respond(
                        HttpStatusCode.Unauthorized,
                        LoginResponse(ok = false, remaining = result.remaining, message = "PIN が違います"),
                    )
                    is Auth.LoginResult.Locked -> call.respond(
                        HttpStatusCode.TooManyRequests,
                        LoginResponse(
                            ok = false,
                            retryAfterSeconds = result.retryAfterSeconds,
                            message = "試行回数の上限に達しました。しばらく待ってから再試行してください",
                        ),
                    )
                    Auth.LoginResult.NoPinConfigured -> call.respond(
                        HttpStatusCode.Conflict,
                        LoginResponse(ok = false, message = "PIN が未設定です。タブレットの設定画面から設定してください"),
                    )
                }
            }

            post("/api/logout") {
                auth.logout(call.request.cookies[Auth.SESSION_COOKIE])
                call.response.cookies.append(Cookie(Auth.SESSION_COOKIE, "", path = "/", maxAge = 0))
                call.respond(LoginResponse(ok = true))
            }

            get("/api/settings") {
                if (!authorized(call)) { unauthorized(call); return@get }
                call.respond(graph.config.get().toPublic())
            }

            post("/api/settings") {
                if (!guardWrite(call)) return@post
                call.respond(graph.settings.saveAll(call.receive<SaveAllRequest>()).toPublic())
            }

            /** カードを表示に切り替える前の確認。判定はアプリの設定画面・saveAll と同じ CardLayout で行う。 */
            post("/api/layout/check") {
                if (!guardWrite(call)) return@post
                val body = call.receive<LayoutCheckRequest>()
                val message = CardLayout.overflowMessage(body.before, body.after, CardLayout.area(graph.context))
                call.respond(LayoutCheckResponse(ok = message == null, message = message))
            }

            /** 背景画像。本文は画像ファイルそのもの（縮小と向きの補正はここで行う）。 */
            post("/api/wallpaper") {
                if (!guardWrite(call)) return@post
                val declared = call.request.contentLength()
                if (declared != null && declared > WallpaperStore.MAX_UPLOAD_BYTES) {
                    call.respond(HttpStatusCode.PayloadTooLarge, ApiError("too_large", "画像が大きすぎます（25 MB まで）"))
                    return@post
                }
                val bytes = call.receive<ByteArray>()
                if (bytes.size > WallpaperStore.MAX_UPLOAD_BYTES) {
                    call.respond(HttpStatusCode.PayloadTooLarge, ApiError("too_large", "画像が大きすぎます（25 MB まで）"))
                    return@post
                }
                call.respond(graph.settings.setWallpaper { bytes.inputStream() }.toPublic())
            }

            post("/api/wallpaper/clear") {
                if (!guardWrite(call)) return@post
                call.respond(graph.settings.clearWallpaper().toPublic())
            }

            /** 運行情報の路線の一覧（設定画面で表示する路線を選ぶ）。 */
            get("/api/train/railways") {
                if (!authorized(call)) { unauthorized(call); return@get }
                call.respond(graph.train.railwayChoices())
            }

            post("/api/train/railways/reload") {
                if (!guardWrite(call)) return@post
                runCatching { graph.train.reloadCatalog() }.onFailure {
                    call.respond(HttpStatusCode.BadRequest, ApiError("train_catalog", it.message)); return@post
                }
                call.respond(graph.train.railwayChoices())
            }

            post("/api/lan") {
                if (!guardWrite(call)) return@post
                val body = call.receive<LanRequest>()
                body.pin?.let(graph.settings::setPin)
                body.enabled?.let(graph.settings::setLanEnabled)
                call.respond(graph.config.get().toPublic())
            }

            get("/api/device") {
                if (!authorized(call)) { unauthorized(call); return@get }
                call.respond(deviceInfo())
            }

            post("/api/device") {
                if (!guardWrite(call)) return@post
                call.receive<DeviceRequest>().launcherHomeEnabled?.let(graph.settings::setLauncherEnabled)
                call.respond(deviceInfo())
            }

            /*
             * Spotify の認可。折り返し先を 127.0.0.1 の自分自身にしているので、端末の外に認可コードが出ない。
             * 開始は GET の画面遷移なので Origin が付かず、実質 loopback（タブレット自身と USB の PC）からだけ通る。
             */
            get("/api/spotify/start") {
                if (!guardWrite(call)) return@get
                val url = graph.spotify.authorizeUrl(SPOTIFY_REDIRECT_URI)
                if (url == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("client_id_missing", "Client ID を保存してから連携してください"),
                    )
                    return@get
                }
                call.respondRedirect(url)
            }

            get("/api/spotify/callback") {
                val error = call.request.queryParameters["error"]
                val code = call.request.queryParameters["code"]
                val (title, detail) = when {
                    error != null -> "連携できませんでした" to error
                    code.isNullOrBlank() -> "連携できませんでした" to "認可コードがありません"
                    else -> {
                        val result = graph.spotify.exchangeCode(code, SPOTIFY_REDIRECT_URI)
                        if (result.isSuccess) {
                            runCatching { graph.spotify.refreshNow() }
                            "Spotify と連携しました" to "この画面は閉じて構いません。"
                        } else {
                            "連携できませんでした" to (result.exceptionOrNull()?.message ?: "不明なエラー")
                        }
                    }
                }
                call.respondText(resultHtml(title, detail), ContentType.Text.Html)
            }

            post("/api/spotify/disconnect") {
                if (!guardWrite(call)) return@post
                graph.spotify.disconnect()
                call.respond(graph.config.get().toPublic())
            }

            post("/api/refresh") {
                if (!guardWrite(call)) return@post
                graph.settings.refreshAll()
                call.respond(graph.snapshot())
            }

            get("/api/geocode") {
                if (!authorized(call)) { unauthorized(call); return@get }
                val query = call.request.queryParameters["q"]?.trim().orEmpty()
                if (query.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("missing_query")); return@get
                }
                call.respond(graph.weather.geocode(query))
            }

            /** 設定画面の「試聴」。音はタブレットから出る。 */
            post("/api/sound/preview") {
                if (!guardWrite(call)) return@post
                val tone = call.request.queryParameters["tone"].orEmpty()
                if (!Tones.isKnown(tone)) {
                    call.respond(HttpStatusCode.BadRequest, ApiError("unknown_tone")); return@post
                }
                val volume = call.request.queryParameters["volume"]?.toDoubleOrNull()?.coerceIn(0.0, 1.0)
                if (volume == null) graph.notices.play(tone, tone) else graph.notices.play(tone, tone, volume = volume)
                call.respond(LoginResponse(ok = true))
            }
        }
    }

    // ------------------------------------------------------------- helpers

    /** XForwardedHeaders は入れていないので、ここは実ソケットのアドレス（偽装できない）。 */
    private fun remoteAddress(call: ApplicationCall): String = call.request.origin.remoteAddress

    private fun authorized(call: ApplicationCall): Boolean =
        auth.isAuthorized(remoteAddress(call), call.request.cookies[Auth.SESSION_COOKIE])

    private suspend fun unauthorized(call: ApplicationCall) =
        call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized"))

    private suspend fun guardWrite(call: ApplicationCall): Boolean {
        if (!csrfOk(call)) {
            call.respond(HttpStatusCode.Forbidden, ApiError("bad_origin")); return false
        }
        if (!authorized(call)) { unauthorized(call); return false }
        return true
    }

    /**
     * CSRF 対策。ブラウザは GET/HEAD 以外で必ず Origin を送るので、あれば Host と一致を求める。
     * Origin が無いのは curl 等の非ブラウザなので、loopback からだけ許す。
     */
    private fun csrfOk(call: ApplicationCall): Boolean {
        val origin = call.request.headers["Origin"] ?: return auth.isLoopback(remoteAddress(call))
        val host = call.request.headers["Host"] ?: return false
        return origin == "http://$host" || origin == "https://$host"
    }

    private fun deviceInfo() = DeviceInfo(
        launcherHomeEnabled = graph.launcher.isEnabled(),
        boundHost = boundHost,
        port = PORT,
        activeSessions = auth.activeSessionCount(),
        pinSet = auth.hasPin(),
        lanUrl = graph.settings.lanSettingsUrl(),
    )

    private fun resultHtml(title: String, detail: String): String = """
        <!doctype html><html lang="ja"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Spotify 連携</title></head>
        <body style="background:#0A0C10;color:#E8EEF5;font-family:system-ui,sans-serif;
        display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
        <div style="text-align:center;padding:24px">
        <p style="font-size:20px;margin:0 0 8px">${escapeHtml(title)}</p>
        <p style="color:#93A1B1;font-size:14px;margin:0">${escapeHtml(detail)}</p>
        </div></body></html>
    """.trimIndent()

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private suspend fun serveAsset(call: ApplicationCall, path: String, status: HttpStatusCode = HttpStatusCode.OK) {
        val bytes = runCatching { graph.context.assets.open(path).use { it.readBytes() } }.getOrNull()
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
        else -> ContentType.Application.OctetStream
    }

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
    private data class LayoutCheckRequest(val before: DisplayConfig, val after: DisplayConfig)

    @Serializable
    private data class LayoutCheckResponse(val ok: Boolean, val message: String? = null)

    @Serializable
    private data class LanRequest(val enabled: Boolean? = null, val pin: String? = null)

    @Serializable
    private data class DeviceInfo(
        val launcherHomeEnabled: Boolean,
        val boundHost: String,
        val port: Int,
        val activeSessions: Int,
        val pinSet: Boolean,
        val lanUrl: String? = null,
    )

    @Serializable
    private data class DeviceRequest(val launcherHomeEnabled: Boolean? = null)

    companion object {
        const val PORT = 8080
        /** Spotify は平文 HTTP の折り返しを 127.0.0.1 にしか認めない。USB の PC からも同じ URL で成立する。 */
        const val SPOTIFY_REDIRECT_URI = "http://127.0.0.1:$PORT/api/spotify/callback"
        private const val TAG = "DashboardServer"
        private const val LOOPBACK = "127.0.0.1"
        private const val ANY = "0.0.0.0"

        private val apiJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
