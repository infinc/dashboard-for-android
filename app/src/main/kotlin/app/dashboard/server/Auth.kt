package app.dashboard.server

import app.dashboard.i18n.L
import android.os.Build
import android.util.Base64
import app.dashboard.data.ConfigStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 設定画面の認証。
 *
 * 方針（Phase 2-5）:
 *  - loopback(127.0.0.1 / ::1) からの接続は認証を免除する。USB の `adb forward` 経由がこれに当たり、
 *    これが設定の主導線になる。
 *  - LAN からの接続は PIN 必須。PIN は PBKDF2 でハッシュ化して保存し、平文は保持しない。
 *  - 総当たりを避けるため IP 単位で試行回数を制限する。
 *
 * 注意: Ktor の XForwardedHeaders プラグインは意図的に入れていない。入れると X-Forwarded-For を
 * 偽装するだけで loopback 判定を突破できてしまうため。remoteAddress は常に実ソケットの値を使う。
 */
class Auth(private val store: ConfigStore) {

    private val sessions = ConcurrentHashMap<String, Long>()
    private val attempts = ConcurrentHashMap<String, Attempts>()
    private val random = SecureRandom()

    private class Attempts(@Volatile var count: Int = 0, @Volatile var lockedUntil: Long = 0L)

    sealed interface LoginResult {
        data class Success(val token: String, val expiresAt: Long) : LoginResult
        data class Failed(val remaining: Int) : LoginResult
        data class Locked(val retryAfterSeconds: Long) : LoginResult
        data object NoPinConfigured : LoginResult
    }

    fun isLoopback(remoteAddress: String?): Boolean {
        val host = (remoteAddress ?: return false).substringBefore('%').trim('[', ']')
        return host == "127.0.0.1" || host == "::1" || host == "0:0:0:0:0:0:0:1" ||
            host.startsWith("127.")
    }

    // ------------------------------------------------------------------ PIN

    fun setPin(pin: String) {
        require(pin.length >= MIN_PIN_LENGTH) { L("PIN は $MIN_PIN_LENGTH 桁以上必要です", "The PIN must be at least $MIN_PIN_LENGTH digits") }
        val salt = ByteArray(16).also(random::nextBytes)
        val algorithm = preferredAlgorithm()
        val iterations = iterationsFor(algorithm)
        val hash = derive(pin, salt, iterations, algorithm)
        store.update { c ->
            c.copy(
                lan = c.lan.copy(
                    pinHash = encode(hash),
                    pinSalt = encode(salt),
                    pinIterations = iterations,
                    pinAlgorithm = algorithm,
                )
            )
        }
        // PIN を変えたら既存セッションは全て無効にする
        sessions.clear()
    }

    fun clearPin() {
        store.update { c -> c.copy(lan = c.lan.copy(pinHash = null, pinSalt = null, pinIterations = 0)) }
        sessions.clear()
    }

    fun hasPin(): Boolean = store.get().lan.pinHash != null

    // ------------------------------------------------- アプリの設定画面の PIN（LAN の PIN とは別）

    private val lockAttempts = Attempts()

    sealed interface UnlockResult {
        data object Success : UnlockResult
        data class Failed(val remaining: Int) : UnlockResult
        data class Locked(val retryAfterSeconds: Long) : UnlockResult
    }

    /** 設定画面の PIN を決めてオンにする。ハッシュの作り方は LAN の PIN と同じ。 */
    fun setSettingsPin(pin: String) {
        require(pin.length >= MIN_SETTINGS_PIN_LENGTH) { L("PIN は $MIN_SETTINGS_PIN_LENGTH 桁以上必要です", "The PIN must be at least $MIN_SETTINGS_PIN_LENGTH digits") }
        val salt = ByteArray(16).also(random::nextBytes)
        val algorithm = preferredAlgorithm()
        val iterations = iterationsFor(algorithm)
        val hash = derive(pin, salt, iterations, algorithm)
        store.update { c ->
            c.copy(settingsLock = app.dashboard.data.SettingsLockConfig(true, encode(hash), encode(salt), iterations, algorithm))
        }
    }

    fun clearSettingsPin() {
        store.update { c -> c.copy(settingsLock = app.dashboard.data.SettingsLockConfig()) }
    }

    fun settingsLocked(): Boolean = store.get().settingsLock.let { it.enabled && it.pinHash != null && it.pinSalt != null }

    /** 設定画面の PIN を確かめる。5 回まちがえると 5 分は受け付けない。 */
    fun unlockSettings(pin: String): UnlockResult {
        val now = System.currentTimeMillis()
        if (lockAttempts.lockedUntil > now) return UnlockResult.Locked((lockAttempts.lockedUntil - now + 999) / 1000)
        val lock = store.get().settingsLock
        val hash = lock.pinHash ?: return UnlockResult.Success
        val salt = lock.pinSalt ?: return UnlockResult.Success
        if (MessageDigest.isEqual(derive(pin, decode(salt), lock.pinIterations, lock.pinAlgorithm), decode(hash))) {
            lockAttempts.count = 0
            lockAttempts.lockedUntil = 0
            return UnlockResult.Success
        }
        lockAttempts.count += 1
        if (lockAttempts.count >= MAX_ATTEMPTS) {
            lockAttempts.count = 0
            lockAttempts.lockedUntil = now + LOCKOUT_MS
            return UnlockResult.Locked(LOCKOUT_MS / 1000)
        }
        return UnlockResult.Failed(MAX_ATTEMPTS - lockAttempts.count)
    }

    fun login(remoteAddress: String?, pin: String): LoginResult {
        val ip = normalizeIp(remoteAddress)
        val record = attempts.getOrPut(ip) { Attempts() }
        val now = System.currentTimeMillis()

        if (record.lockedUntil > now) {
            return LoginResult.Locked((record.lockedUntil - now + 999) / 1000)
        }

        val lan = store.get().lan
        val storedHash = lan.pinHash
        val storedSalt = lan.pinSalt
        if (storedHash == null || storedSalt == null) return LoginResult.NoPinConfigured

        val candidate = derive(pin, decode(storedSalt), lan.pinIterations, lan.pinAlgorithm)
        val matches = MessageDigest.isEqual(candidate, decode(storedHash))

        if (!matches) {
            record.count += 1
            if (record.count >= MAX_ATTEMPTS) {
                record.lockedUntil = now + LOCKOUT_MS
                record.count = 0
                return LoginResult.Locked(LOCKOUT_MS / 1000)
            }
            return LoginResult.Failed(MAX_ATTEMPTS - record.count)
        }

        record.count = 0
        record.lockedUntil = 0
        val token = newToken()
        val expiresAt = now + SESSION_TTL_MS
        sessions[token] = expiresAt
        return LoginResult.Success(token, expiresAt)
    }

    // -------------------------------------------------------------- session

    fun isValidSession(token: String?): Boolean {
        val t = token ?: return false
        val expiresAt = sessions[t] ?: return false
        if (expiresAt <= System.currentTimeMillis()) {
            sessions.remove(t)
            return false
        }
        return true
    }

    fun logout(token: String?) {
        token?.let(sessions::remove)
    }

    fun activeSessionCount(): Int {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { it.value <= now }
        return sessions.size
    }

    /** 権限の境界。loopback か、有効なセッションを持つ LAN クライアントだけが true。 */
    fun isAuthorized(remoteAddress: String?, token: String?): Boolean =
        isLoopback(remoteAddress) || isValidSession(token)

    // ---------------------------------------------------------------- 内部

    private fun newToken(): String = encode(ByteArray(32).also(random::nextBytes))

    private fun normalizeIp(remoteAddress: String?): String =
        (remoteAddress ?: "unknown").substringBefore('%').trim('[', ']')

    private fun preferredAlgorithm(): String =
        // PBKDF2WithHmacSHA256 は API 26 から。24/25 は SHA1 しか無い。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "PBKDF2WithHmacSHA256"
        else "PBKDF2WithHmacSHA1"

    private fun iterationsFor(algorithm: String) =
        if (algorithm.endsWith("SHA256")) 120_000 else 200_000

    private fun derive(pin: String, salt: ByteArray, iterations: Int, algorithm: String): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations.coerceAtLeast(10_000), 256)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } catch (e: Exception) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        const val MIN_PIN_LENGTH = 6
        /** 設定画面の PIN は端末の前で打つものなので、LAN の PIN より短くてよい。 */
        const val MIN_SETTINGS_PIN_LENGTH = 4
        const val SESSION_COOKIE = "dashboard_session"
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_MS = 5 * 60 * 1000L
        private const val SESSION_TTL_MS = 24 * 60 * 60 * 1000L
    }
}
