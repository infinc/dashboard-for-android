package app.walldash.data

import android.net.TrafficStats
import android.os.SystemClock

/**
 * 端末が「いまどれだけ通信しているか」を、OS が持つ累積バイト数の差分から求める。
 *
 * 自分で通信を発生させることはしない（スピードテストではない）。すでに流れている
 * 通信を数えるだけなので、Wi-Fi のリンク速度と違って実際に出ている速度が分かる。
 *
 * [TrafficStats] が返すのは端末全体の合計で、ループバック（アプリ内蔵サーバーと
 * WebView のやり取り）は含まれない。そのため、ダッシュボード自身のポーリングが
 * 数字に乗ることはない。
 */
class TrafficMonitor {

    private var lastRxBytes = -1L
    private var lastTxBytes = -1L
    private var lastAt = 0L

    private var rxBps: Long? = null
    private var txBps: Long? = null

    /**
     * 呼ばれるたびに前回との差分から bps を出す。
     *
     * 呼び出し間隔が短すぎるとパケット 1 つの有無で値が大きく跳ねるので、
     * [MIN_INTERVAL_MS] に満たないうちは前回の結果をそのまま返して落ち着かせる。
     */
    @Synchronized
    fun sample(): Pair<Long?, Long?> {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == UNSUPPORTED || tx == UNSUPPORTED) return null to null

        val at = SystemClock.elapsedRealtime()
        if (lastRxBytes < 0) {
            // 初回は基準を置くだけ。差分が無いので速度は出せない。
            lastRxBytes = rx
            lastTxBytes = tx
            lastAt = at
            return null to null
        }

        val ms = at - lastAt
        if (ms >= MIN_INTERVAL_MS) {
            rxBps = bitsPerSec(rx - lastRxBytes, ms)
            txBps = bitsPerSec(tx - lastTxBytes, ms)
            lastRxBytes = rx
            lastTxBytes = tx
            lastAt = at
        }
        return rxBps to txBps
    }

    /** 再起動でカウンタが巻き戻ると差分が負になるので、その回は 0 として扱う。 */
    private fun bitsPerSec(deltaBytes: Long, ms: Long): Long =
        if (deltaBytes <= 0) 0L else deltaBytes * 8 * 1000 / ms

    private companion object {
        val UNSUPPORTED = TrafficStats.UNSUPPORTED.toLong()
        const val MIN_INTERVAL_MS = 1000L
    }
}
