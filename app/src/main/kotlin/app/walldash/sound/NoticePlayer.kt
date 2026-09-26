package app.walldash.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import app.walldash.data.ConfigStore
import app.walldash.data.Tones
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 通知音をその場で合成して鳴らす。
 *
 * 音量は合成のゲインではなく端末のメディア音量で作る。ゲインで絞ると端末の音量が小さいときに
 * 通知まで小さくなるため、鳴らす間だけメディア音量を設定値へ動かし、終われば元へ戻す。
 */
class NoticePlayer(context: Context, private val config: ConfigStore) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    private var volumeBefore: Int? = null
    private var restoreJob: Job? = null
    private var ringJob: Job? = null

    val isRinging: Boolean get() = ringJob?.isActive == true

    /** [reversed] は音の高さの並びを逆にする（充電を抜いたとき）。[volume] は試聴で保存前の値を使うため。 */
    fun play(toneId: String, fallback: String, reversed: Boolean = false, volume: Double = config.get().notifications.volume) {
        if (volume <= 0.0) return
        val pcm = synthesize(Tones.byId(toneId, fallback), reversed)
        val soundMs = pcm.size * 1000L / RATE
        holdVolume(volume, SETTLE_MS + soundMs + TAIL_MS)
        scope.launch {
            delay(SETTLE_MS)
            playPcm(pcm)
        }
    }

    /** タイマーの鳴動。止めるまで、または約 40 秒たつまで繰り返す。 */
    fun startRing(toneId: String) {
        stopRing()
        ringJob = scope.launch {
            repeat(RING_REPEATS) {
                if (!isActive) return@launch
                play(toneId, Tones.DEFAULT_TIMER)
                delay(RING_INTERVAL_MS)
            }
        }
    }

    fun stopRing() {
        ringJob?.cancel()
        ringJob = null
        restoreVolume()
    }

    /** 通知のために動かした音量を戻す。動かしていなければ何もしない。 */
    fun restoreVolume() {
        synchronized(lock) {
            restoreJob?.cancel()
            restoreJob = null
            val saved = volumeBefore ?: return
            volumeBefore = null
            runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0) }
                .onFailure { Log.w(TAG, "メディア音量を戻せない", it) }
        }
    }

    /** 元の音量は最初の 1 回だけ覚える。重ねて呼ばれたときは戻す時刻を延ばすだけ。 */
    private fun holdVolume(volume: Double, holdMs: Long) {
        synchronized(lock) { holdVolumeLocked(volume, holdMs) }
    }

    private fun holdVolumeLocked(volume: Double, holdMs: Long) {
        val am = audio ?: return
        val max = runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        if (max <= 0) return
        if (volumeBefore == null) {
            volumeBefore = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return
        }
        runCatching {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * max).roundToInt().coerceIn(0, max), 0)
        }.onFailure { Log.w(TAG, "メディア音量を変更できない（マナーモード等）", it) }
        restoreJob?.cancel()
        restoreJob = scope.launch {
            delay(holdMs.coerceAtMost(MAX_HOLD_MS))
            restoreVolume()
        }
    }

    private fun playPcm(pcm: ShortArray) {
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.onFailure { Log.w(TAG, "AudioTrack を作れない", it) }.getOrNull() ?: return

        runCatching {
            track.write(pcm, 0, pcm.size)
            track.play()
        }.onFailure {
            Log.w(TAG, "通知音を鳴らせない", it)
            track.release()
            return
        }
        scope.launch {
            delay(pcm.size * 1000L / RATE + 100)
            runCatching { track.stop() }
            track.release()
        }
    }

    /** 各音に 20ms の立ち上がりと指数的な減衰を付ける（付けないと「プツッ」と雑音が乗る）。 */
    private fun synthesize(tone: Tones.Tone, reversed: Boolean): ShortArray {
        val notes = if (!reversed) tone.notes else tone.notes.mapIndexed { i, n ->
            n.copy(frequency = tone.notes[tone.notes.size - 1 - i].frequency)
        }
        val total = notes.maxOf { it.start + it.duration } + 0.08
        val samples = FloatArray((total * RATE).toInt())
        for (note in notes) {
            val first = (note.start * RATE).toInt()
            val last = min(samples.size, ((note.start + note.duration + 0.05) * RATE).toInt())
            for (i in first until last) {
                val t = i.toDouble() / RATE - note.start
                val envelope = when {
                    t < ATTACK -> FLOOR * (PEAK / FLOOR).pow(t / ATTACK)
                    else -> PEAK * (FLOOR / PEAK).pow(((t - ATTACK) / (note.duration - ATTACK)).coerceIn(0.0, 1.0))
                }
                samples[i] += (wave(tone.wave, 2 * PI * note.frequency * t) * envelope).toFloat()
            }
        }
        return ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun wave(kind: Tones.Wave, phase: Double): Double = when (kind) {
        Tones.Wave.SINE -> sin(phase)
        Tones.Wave.TRIANGLE -> 2 / PI * asin(sin(phase))
        Tones.Wave.SQUARE -> if (sin(phase) >= 0) 0.55 else -0.55
    }

    private companion object {
        const val TAG = "NoticePlayer"
        const val RATE = 22_050
        const val ATTACK = 0.02
        const val PEAK = 0.34
        const val FLOOR = 0.0001

        /** 音量の変更は非同期に効くので、当たるのを待ってから鳴らす。 */
        const val SETTLE_MS = 180L
        const val TAIL_MS = 400L
        const val MAX_HOLD_MS = 60_000L
        const val RING_INTERVAL_MS = 2_000L
        const val RING_REPEATS = 20
    }
}
