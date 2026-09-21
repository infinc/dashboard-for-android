package app.walldash.data

import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * CPU 使用率を求める。
 *
 * `/proc/stat` はアプリからは読めない。Android 8 以降、untrusted_app からの
 * 参照が SELinux で遮断されているため（この端末でも Permission denied を確認済み）。
 *
 * 代わりに cpuidle のアイドル滞在時間を使う。各コアの
 * `/sys/devices/system/cpu/cpuN/cpuidle/stateM/time`（マイクロ秒の累積）は読めるので、
 *
 *     使用率 = 1 − Δアイドル時間 / (Δ実時間 × コア数)
 *
 * で「どれだけアイドルでなかったか」が出る。`dumpsys cpuinfo` とは集計の窓も対象も
 * 違うので数値は一致しないが（実測で 18% 対 10%）、傾向を見るには十分で、
 * 何より権限なしに継続取得できるのが利点。
 *
 * cpuidle が読めない端末では null を返し、UI 側はグラフを出さない。
 */
class CpuMonitor {

    @Volatile
    private var lastIdleMicros = -1L
    private var lastSampledAt = 0L

    @Volatile
    private var lastPercent: Int? = null

    /** 起動時に一度だけ数えれば足りる。コアの増減は考慮しない。 */
    private val coreCount: Int = detectCoreCount()

    /**
     * 直近の区間の使用率(0..100)。
     * 初回と、前回から間が無さすぎるときは前の値をそのまま返す（0 除算と暴れを避ける）。
     */
    fun sample(): Int? {
        val now = SystemClock.elapsedRealtime()
        val idle = readIdleMicros() ?: return null

        val elapsedMs = now - lastSampledAt
        val previousIdle = lastIdleMicros
        if (previousIdle < 0 || elapsedMs < MIN_WINDOW_MS) {
            if (previousIdle < 0) {
                lastIdleMicros = idle
                lastSampledAt = now
            }
            return lastPercent
        }

        lastIdleMicros = idle
        lastSampledAt = now

        val totalMicros = elapsedMs * 1000.0 * coreCount
        if (totalMicros <= 0.0) return lastPercent
        val busy = 1.0 - (idle - previousIdle) / totalMicros
        // コアがオフラインだった区間はアイドル時間が進まず 100% 側に振れる。
        // 実害のある値にはならないよう丸めてから切り詰める。
        lastPercent = Math.round(busy * 100).toInt().coerceIn(0, 100)
        return lastPercent
    }

    /** 全コアの全アイドル状態の滞在時間(μs)を合計する。1 つも読めなければ null。 */
    private fun readIdleMicros(): Long? {
        var total = 0L
        var read = 0
        for (core in 0 until coreCount) {
            val dir = File("/sys/devices/system/cpu/cpu$core/cpuidle")
            val states = dir.listFiles { f -> f.isDirectory && f.name.startsWith("state") } ?: continue
            for (state in states) {
                val value = runCatching { File(state, "time").readText().trim().toLong() }.getOrNull()
                if (value != null) { total += value; read++ }
            }
        }
        return if (read == 0) null else total
    }

    private fun detectCoreCount(): Int = runCatching {
        // "0-7" や "0-3,6-7" の形式。末尾の番号 + 1 ではなく、実際に並ぶ個数を数える。
        val text = File("/sys/devices/system/cpu/online").readText().trim()
        text.split(",").sumOf { part ->
            val range = part.split("-")
            if (range.size == 2) range[1].toInt() - range[0].toInt() + 1 else 1
        }
    }.getOrElse {
        Log.w(TAG, "オンラインコア数を読めないので availableProcessors を使う", it)
        Runtime.getRuntime().availableProcessors()
    }.coerceAtLeast(1)

    private companion object {
        const val TAG = "CpuMonitor"

        /** これより短い区間だと量子化誤差で値が暴れるので、前回値を使い回す。 */
        const val MIN_WINDOW_MS = 900L
    }
}
