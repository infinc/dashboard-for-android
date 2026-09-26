package app.walldash.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 月の満ち欠けの計算（通信しない）。
 *
 * 新月・満月の時刻は Meeus『Astronomical Algorithms』49 章の式（主要な周期項のみ）で求める。
 * 惑星による補正と ΔT は省いているので誤差は数分程度。壁に出す「月齢」「次の満月」には十分。
 */
object Astro {

    private const val SYNODIC = 29.530588861
    private const val JD_UNIX_EPOCH = 2440587.5

    data class MoonPhase(
        /** 月齢（直前の新月からの日数）。 */
        val age: Double,
        /** 満ち欠けの位置 0..1（0 = 新月、0.5 = 満月）。 */
        val phase: Double,
        /** 輝いて見える割合 0..1。 */
        val illumination: Double,
        val previousNew: Long,
        val nextNew: Long,
        val nextFull: Long,
    ) {
        val waxing: Boolean get() = phase < 0.5

        /** 日本で使われる呼び名。 */
        val name: String
            get() = when {
                age < 1.0 || age > SYNODIC - 1.0 -> "新月"
                age < 2.5 -> "繊月"
                age < 4.0 -> "三日月"
                age < 6.5 -> "夕月"
                age < 8.5 -> "上弦の月"
                age < 12.0 -> "十日夜の月"
                age < 13.5 -> "十三夜月"
                age < 14.5 -> "小望月"
                age < 16.0 -> "満月"
                age < 17.0 -> "十六夜"
                age < 18.0 -> "立待月"
                age < 19.0 -> "居待月"
                age < 20.5 -> "寝待月"
                age < 21.5 -> "更待月"
                age < 23.5 -> "下弦の月"
                age < 26.5 -> "有明月"
                else -> "晦日月"
            }
    }

    fun moon(nowMs: Long): MoonPhase {
        val nowJd = nowMs / 86_400_000.0 + JD_UNIX_EPOCH
        // 2000 年 1 月 6 日の新月を k = 0 とする通し番号。少し手前から数えて「直前の新月」を探す
        var k = floor((nowJd - 2451550.09766) / SYNODIC) - 1
        var prev = phaseJd(k, full = false)
        while (phaseJd(k + 1, full = false) <= nowJd) {
            k += 1
            prev = phaseJd(k, full = false)
        }
        while (prev > nowJd) {
            k -= 1
            prev = phaseJd(k, full = false)
        }
        val next = phaseJd(k + 1, full = false)
        val fullThis = phaseJd(k + 0.5, full = true)
        val nextFull = if (fullThis > nowJd) fullThis else phaseJd(k + 1.5, full = true)
        val phase = (nowJd - prev) / (next - prev)
        return MoonPhase(
            age = nowJd - prev,
            phase = phase,
            illumination = (1 - cos(2 * PI * phase)) / 2,
            previousNew = jdToMs(prev),
            nextNew = jdToMs(next),
            nextFull = jdToMs(nextFull),
        )
    }

    private fun jdToMs(jd: Double): Long = ((jd - JD_UNIX_EPOCH) * 86_400_000.0).toLong()

    /** 通し番号 [k]（新月は整数、満月は .5）の瞬間のユリウス日（Meeus 49.1 と表 49.A）。 */
    private fun phaseJd(k: Double, full: Boolean): Double {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t
        val jde = 2451550.09766 + SYNODIC * k + 0.00015437 * t2 - 0.000000150 * t3 + 0.00000000073 * t4
        val e = 1 - 0.002516 * t - 0.0000074 * t2
        val m = rad(2.5534 + 29.10535670 * k - 0.0000014 * t2 - 0.00000011 * t3)
        val mp = rad(201.5643 + 385.81693528 * k + 0.0107582 * t2 + 0.00001238 * t3 - 0.000000058 * t4)
        val f = rad(160.7108 + 390.67050284 * k - 0.0016118 * t2 - 0.00000227 * t3 + 0.000000011 * t4)
        val om = rad(124.7746 - 1.56375588 * k + 0.0020672 * t2 + 0.00000215 * t3)
        val c = if (full) {
            -0.40614 * sin(mp) + 0.17302 * e * sin(m) + 0.01614 * sin(2 * mp) + 0.01043 * sin(2 * f) +
                0.00734 * e * sin(mp - m) - 0.00515 * e * sin(mp + m) + 0.00209 * e * e * sin(2 * m)
        } else {
            -0.40720 * sin(mp) + 0.17241 * e * sin(m) + 0.01608 * sin(2 * mp) + 0.01039 * sin(2 * f) +
                0.00739 * e * sin(mp - m) - 0.00514 * e * sin(mp + m) + 0.00208 * e * e * sin(2 * m)
        }
        val common = -0.00111 * sin(mp - 2 * f) - 0.00057 * sin(mp + 2 * f) + 0.00056 * e * sin(2 * mp + m) -
            0.00042 * sin(3 * mp) + 0.00042 * e * sin(m + 2 * f) + 0.00038 * e * sin(m - 2 * f) -
            0.00024 * e * sin(2 * mp - m) - 0.00017 * sin(om) - 0.00007 * sin(mp + 2 * m) +
            0.00004 * sin(2 * mp - 2 * f) + 0.00004 * sin(3 * m) + 0.00003 * sin(mp + m - 2 * f) +
            0.00003 * sin(2 * mp + 2 * f) - 0.00003 * sin(mp + m + 2 * f) + 0.00003 * sin(mp - m + 2 * f) -
            0.00002 * sin(mp - m - 2 * f) - 0.00002 * sin(3 * mp + m) + 0.00002 * sin(4 * mp)
        return jde + c + common
    }

    private fun rad(deg: Double) = (deg % 360.0) * PI / 180.0
}
