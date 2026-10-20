package app.dashboard.ui.common

import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

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

/**
 * ダッシュボードのカードが、全画面・ブラウズ・設定に覆われて見えていないか。覆っているもの（名前）の集まりで持つ。
 * 見えていない絵（ハムスター・地球儀・秒針）は描き直しを止める（一晩出しっぱなしの全画面の下で 30 コマ/秒で回し続けないため）。
 * 全画面そのものの絵（Spotify のグラデーション）はこれを見ない。
 */
private val covers = MutableStateFlow(emptySet<String>())

fun setCovered(by: String, on: Boolean) = covers.update { if (on) it + by else it - by }

/** カードが覆われている間は待つ。 */
suspend fun awaitUncovered() {
    if (covers.value.isNotEmpty()) covers.first { it.isEmpty() }
}
