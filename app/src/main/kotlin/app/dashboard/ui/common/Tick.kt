package app.dashboard.ui.common

import androidx.compose.runtime.withFrameMillis

/**
 * いつも動いている絵（ハムスター・Wi-Fi の地球儀・アナログ時計の秒針・Spotify の全画面のグラデーション）の描き直しの刻み（ms）。
 *
 * 画面は 1 か所でも描き直すと全体を合成し直すので、それぞれが勝手な時刻に描き直すと毎フレーム（60 コマ/秒）合成することになる。
 * フレームの時刻を同じ刻みで区切り、皆が同じフレームで描き直すと、合成は約 30 コマ/秒で済む。
 */
const val TICK_MS = 33L

/**
 * [last]（前に描き直したフレームの時刻）のあと、[every] の刻みを越えた最初のフレームまで待ち、その時刻を返す。
 * [every] は [TICK_MS] の倍数にする（ずれると、ほかの絵と同じフレームにそろわない）。
 * 絵の状態は [onFrame] の中で書き換える。待ち終わってから書き換えると、描き直しが次のフレームにずれて、そろわない。
 */
suspend fun awaitTick(last: Long, every: Long = TICK_MS, onFrame: (Long) -> Unit = {}): Long {
    while (true) {
        var hit = -1L
        withFrameMillis { t ->
            if (t / every != last / every) {
                onFrame(t)
                hit = t
            }
        }
        if (hit >= 0) return hit
    }
}
