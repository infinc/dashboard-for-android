package app.dashboard.server

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 外から通知を出す API（POST /api/notify）の門番。トークンの照合、まちがいが続く IP の締め出し、送りすぎの抑え。
 * ログインの PIN と違い、ブラウザからではなく Home Assistant などのプログラムから来るので、Cookie ではなくトークンで確かめる。
 */
class NotifyGate(
    /** 続けて受け付けるまでの間（自作カードへ送る口は短くする）。 */
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface Result {
        data object Ok : Result
        data object Disabled : Result
        data object BadToken : Result
        data class Locked(val retryAfterSeconds: Long) : Result
        data object TooMany : Result
    }

    private class Misses(var count: Int = 0, var lockedUntil: Long = 0)

    private val misses = ConcurrentHashMap<String, Misses>()
    private var lastAt = 0L

    /** [header] は Authorization ヘッダーの値（"Bearer …"）。 */
    fun check(ip: String, header: String?, enabled: Boolean, token: String?): Result {
        if (!enabled || token.isNullOrEmpty()) return Result.Disabled
        val m = misses.getOrPut(ip) { Misses() }
        synchronized(m) {
            val now = clock()
            if (m.lockedUntil > now) return Result.Locked((m.lockedUntil - now + 999) / 1000)
            val sent = header?.removePrefix("Bearer")?.trim().orEmpty()
            if (!MessageDigest.isEqual(sent.toByteArray(), token.toByteArray())) {
                m.count += 1
                if (m.count >= MAX_MISSES) {
                    m.count = 0
                    m.lockedUntil = now + LOCKOUT_MS
                    return Result.Locked(LOCKOUT_MS / 1000)
                }
                return Result.BadToken
            }
            m.count = 0
        }
        synchronized(this) {
            val now = clock()
            if (now - lastAt < minIntervalMs) return Result.TooMany
            lastAt = now
        }
        return Result.Ok
    }

    companion object {
        private const val MAX_MISSES = 10
        private const val LOCKOUT_MS = 10 * 60 * 1000L
        /** 続けて送られても、バナーが読めないほど入れ替わらないように。 */
        const val MIN_INTERVAL_MS = 1_000L
        const val MAX_TITLE = 40
        const val MAX_TEXT = 300
    }
}
