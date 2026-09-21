package app.walldash.data

import android.util.Base64
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify で再生中の曲を取得する。
 *
 * 認可は PKCE 付きの認可コードフロー。クライアントシークレットを壁掛け端末に置かずに済む。
 * 折り返し先は端末自身の内蔵サーバー（127.0.0.1）で、Spotify はループバックに限り
 * 平文 HTTP の折り返しを認めている。
 *
 * アクセストークンは 1 時間で切れるのでメモリにだけ置き、更新用トークンは設定に保存する。
 * 再生操作は一切しない（読み取りのスコープしか要求しない）。
 */
class SpotifyRepository(
    private val client: HttpClient,
    private val configStore: ConfigStore,
) {

    @Volatile
    var state: SpotifyState = SpotifyState()
        private set

    private var accessToken: String? = null
    private var accessTokenExpiresAt = 0L
    private var lastAttemptAt = 0L
    private var consecutiveFailures = 0

    /**
     * 認可の途中でだけ使う検証子。
     * 折り返しが来たときに突き合わせる必要があるので、開始から折り返しまでの間だけ持つ。
     */
    @Volatile
    private var pendingVerifier: String? = null

    // ------------------------------------------------------------ 取得

    suspend fun refreshIfDue() {
        val config = configStore.get().spotify
        if (!config.enabled || config.refreshToken.isNullOrBlank()) {
            if (state.available) state = SpotifyState()
            return
        }
        val now = System.currentTimeMillis()
        val due = if (consecutiveFailures == 0) INTERVAL_MS else backoffMs()
        if (now - lastAttemptAt < due) return
        refreshNow()
    }

    suspend fun refreshNow() {
        lastAttemptAt = System.currentTimeMillis()
        val config = configStore.get().spotify
        if (!config.enabled || config.refreshToken.isNullOrBlank()) {
            state = SpotifyState()
            return
        }
        try {
            val token = validAccessToken(config) ?: run {
                state = state.copy(available = false, lastError = "トークンを更新できません")
                return
            }
            val response: HttpResponse = client.get(NOW_PLAYING_URL) {
                header("Authorization", "Bearer $token")
                // 204（無音）と 401（要再認可）を自分で見分けたいので、
                // 共有クライアントの expectSuccess をこの要求だけ外す。
                expectSuccess = false
            }
            when (response.status) {
                // 204 は「いま何も鳴っていない」。エラーではないので、そう表示する。
                HttpStatusCode.NoContent -> {
                    state = SpotifyState(
                        available = true,
                        playing = false,
                        fetchedAt = System.currentTimeMillis(),
                    )
                }

                HttpStatusCode.OK -> {
                    val dto: NowPlayingDto = response.body()
                    state = toState(dto)
                }

                HttpStatusCode.Unauthorized -> {
                    // 期限切れなら次回に取り直す。権限を取り消された場合もここに来る。
                    accessToken = null
                    accessTokenExpiresAt = 0
                    throw IllegalStateException("認可が無効です（再連携が必要かもしれません）")
                }

                else -> throw IllegalStateException("HTTP ${response.status.value}")
            }
            consecutiveFailures = 0
        } catch (e: Exception) {
            consecutiveFailures += 1
            Log.w(TAG, "Spotify の取得に失敗（${consecutiveFailures}回目）", e)
            state = state.copy(lastError = e.message ?: e::class.java.simpleName)
        }
    }

    private fun toState(dto: NowPlayingDto): SpotifyState {
        val item = dto.item
        // 画像は大きい順に並んでいる。壁に出すのは小さめのカードなので中間のものを選ぶ。
        val images = item?.album?.images.orEmpty()
        val image = images.getOrNull(1) ?: images.firstOrNull()
        return SpotifyState(
            available = true,
            playing = dto.isPlaying && item != null,
            trackName = item?.name,
            artistName = item?.artists.orEmpty().mapNotNull { it.name }.joinToString(", ")
                .takeIf { it.isNotBlank() },
            albumName = item?.album?.name,
            albumImageUrl = image?.url,
            progressMs = dto.progressMs,
            durationMs = item?.durationMs,
            fetchedAt = System.currentTimeMillis(),
            lastError = null,
        )
    }

    // ------------------------------------------------------------ 認可

    /**
     * 認可画面の URL を組み立てる。同時に検証子を作って持っておく。
     * 呼び出し側（サーバー）はこの URL へ転送するだけでよい。
     */
    fun authorizeUrl(redirectUri: String): String? {
        val clientId = configStore.get().spotify.clientId.trim()
        if (clientId.isEmpty()) return null

        val verifier = randomVerifier()
        pendingVerifier = verifier
        val challenge = base64Url(sha256(verifier.toByteArray(Charsets.US_ASCII)))

        return AUTHORIZE_URL +
            "?client_id=" + encode(clientId) +
            "&response_type=code" +
            "&redirect_uri=" + encode(redirectUri) +
            "&code_challenge_method=S256" +
            "&code_challenge=" + challenge +
            "&scope=" + encode(SCOPES) +
            // 同意画面を必ず出す。一度許可していると Spotify は確認を省くことがあり、
            // 後から権限を増やしたときに古いままの認可で戻ってきてしまう。
            "&show_dialog=true"
    }

    /**
     * 折り返しで受け取った認可コードを更新用トークンに交換する。
     * 成功したら設定に保存し、次の取得からすぐ使えるようにする。
     */
    suspend fun exchangeCode(code: String, redirectUri: String): Result<Unit> {
        val verifier = pendingVerifier
            ?: return Result.failure(IllegalStateException("認可の途中経過が見つかりません。設定画面からやり直してください。"))
        val clientId = configStore.get().spotify.clientId.trim()
        if (clientId.isEmpty()) return Result.failure(IllegalStateException("クライアント ID が未設定です"))

        return runCatching {
            val token: TokenDto = client.submitForm(
                url = TOKEN_URL,
                formParameters = Parameters.build {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", redirectUri)
                    append("client_id", clientId)
                    append("code_verifier", verifier)
                },
            ).body()

            val refresh = token.refreshToken
                ?: throw IllegalStateException("更新用トークンが返りませんでした")

            pendingVerifier = null
            accessToken = token.accessToken
            accessTokenExpiresAt = System.currentTimeMillis() + token.expiresIn * 1000L - SKEW_MS
            configStore.update { c ->
                c.copy(spotify = c.spotify.copy(enabled = true, refreshToken = refresh))
            }
            consecutiveFailures = 0
            lastAttemptAt = 0
        }
    }

    /**
     * 再生の操作。Spotify 側で鳴っている端末に対して指示を送るだけで、
     * このタブレットから音は出ない。
     */
    suspend fun control(action: String): Result<Unit> {
        val config = configStore.get().spotify
        if (!config.enabled || config.refreshToken.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Spotify が未連携です"))
        }
        return runCatching {
            val token = validAccessToken(config)
                ?: throw IllegalStateException("トークンを更新できません")
            val base = "https://api.spotify.com/v1/me/player"
            val response: HttpResponse = when (action) {
                "play" -> client.put("$base/play") { authed(token) }
                "pause" -> client.put("$base/pause") { authed(token) }
                "next" -> client.post("$base/next") { authed(token) }
                "previous" -> client.post("$base/previous") { authed(token) }
                else -> throw IllegalArgumentException("不明な操作: $action")
            }
            // 204 が正常。403 は無料プランなど操作が許されていない場合に返る。
            if (response.status.value !in 200..299) {
                throw IllegalStateException(
                    when (response.status) {
                        // 操作の権限は後から足したので、それ以前に連携した認可には入っていない。
                        // 取り直すまで読み取りはできるが操作だけ弾かれる、という状態になる。
                        HttpStatusCode.Unauthorized ->
                            "操作の権限がありません。設定画面から連携をやり直してください"
                        HttpStatusCode.Forbidden ->
                            "操作を拒否されました（Spotify Premium が必要です）"
                        HttpStatusCode.NotFound ->
                            "操作できる再生先が見つかりません（Spotify アプリで一度再生してください）"
                        else -> "HTTP ${response.status.value}"
                    }
                )
            }
            // 指示が反映されるまで少し待ってから取り直す。すぐ読むと前の状態が返る。
            kotlinx.coroutines.delay(400)
            lastAttemptAt = 0
            refreshNow()
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authed(token: String) {
        header("Authorization", "Bearer $token")
        // 204 を自分で正常扱いにするため
        expectSuccess = false
    }

    /** 連携を解除する。端末に残る更新用トークンを消すのが主目的。 */
    fun disconnect() {
        pendingVerifier = null
        accessToken = null
        accessTokenExpiresAt = 0
        state = SpotifyState()
        configStore.update { c -> c.copy(spotify = c.spotify.copy(refreshToken = null)) }
    }

    /** 期限内のアクセストークンを返す。切れていれば更新用トークンで取り直す。 */
    private suspend fun validAccessToken(config: SpotifyConfig): String? {
        val cached = accessToken
        if (cached != null && System.currentTimeMillis() < accessTokenExpiresAt) return cached

        val refresh = config.refreshToken ?: return null
        val clientId = config.clientId.trim().ifEmpty { return null }

        val token: TokenDto = client.submitForm(
            url = TOKEN_URL,
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refresh)
                append("client_id", clientId)
            },
        ).body()

        accessToken = token.accessToken
        accessTokenExpiresAt = System.currentTimeMillis() + token.expiresIn * 1000L - SKEW_MS
        // 更新用トークンが差し替わって返ることがある。取り落とすと次回から認可が切れる。
        token.refreshToken?.takeIf { it.isNotBlank() && it != refresh }?.let { rotated ->
            configStore.update { c -> c.copy(spotify = c.spotify.copy(refreshToken = rotated)) }
        }
        return accessToken
    }

    // ------------------------------------------------------------ 小道具

    private fun randomVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun backoffMs(): Long =
        minOf(30_000L shl minOf(consecutiveFailures - 1, 4), 10 * 60_000L)

    private companion object {
        const val TAG = "SpotifyRepository"
        const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
        const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        const val NOW_PLAYING_URL = "https://api.spotify.com/v1/me/player/currently-playing"

        /**
         * 再生中の曲を読むためのスコープと、再生・停止・曲送りのためのスコープ。
         * 履歴やライブラリ、プレイリストには触らない。
         * ここを増やすと既存の認可では足りなくなるので、連携をやり直す必要がある。
         */
        const val SCOPES =
            "user-read-currently-playing user-read-playback-state user-modify-playback-state"

        /** 曲は数分で変わるので、壁の表示が遅れて見えない程度の間隔にする。 */
        const val INTERVAL_MS = 5_000L

        /** 期限ぎりぎりで使って 401 を踏まないよう、少し手前で切れたことにする。 */
        const val SKEW_MS = 30_000L
    }

    // ------------------------------------------------------------ DTO

    @Serializable
    private data class TokenDto(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = 3600,
    )

    @Serializable
    private data class NowPlayingDto(
        @SerialName("is_playing") val isPlaying: Boolean = false,
        @SerialName("progress_ms") val progressMs: Long? = null,
        val item: TrackDto? = null,
    )

    @Serializable
    private data class TrackDto(
        val name: String? = null,
        @SerialName("duration_ms") val durationMs: Long? = null,
        val artists: List<ArtistDto> = emptyList(),
        val album: AlbumDto? = null,
    )

    @Serializable
    private data class ArtistDto(val name: String? = null)

    @Serializable
    private data class AlbumDto(
        val name: String? = null,
        val images: List<ImageDto> = emptyList(),
    )

    @Serializable
    private data class ImageDto(val url: String? = null, val width: Int? = null)
}
