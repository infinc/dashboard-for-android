package app.dashboard.data

import app.dashboard.i18n.L
import kotlin.math.roundToInt

/**
 * 空気・体調の目安の計算と段階分け。どれも画面に出すだけの目安で、公式の発表の代わりにはならない。
 */
object Health {

    /** 段階。[level] は 0 から大きいほど注意。 */
    data class Band(val level: Int, val label: String)

    /**
     * 暑さ指数（WBGT）の推定。小野ら（2014）の式（環境省の熱中症予防情報サイトの推定値と同じ考え方）。
     * [ta] 気温（℃）、[rh] 相対湿度（%）、[srWm2] 全天日射（W/m²）、[ws] 風速（m/s）。
     */
    fun wbgt(ta: Double, rh: Double, srWm2: Double, ws: Double): Double {
        val sr = (srWm2 / 1000.0).coerceAtLeast(0.0)
        val w = 0.735 * ta + 0.0374 * rh + 0.00292 * ta * rh + 7.619 * sr - 4.557 * sr * sr - 0.0572 * ws - 4.064
        return (w * 10).roundToInt() / 10.0
    }

    /** 日本生気象学会の「日常生活に関する指針」の段階。 */
    fun wbgtBand(w: Double): Band = when {
        w >= 31 -> Band(4, L("危険", "Danger"))
        w >= 28 -> Band(3, L("厳重警戒", "Severe warning"))
        w >= 25 -> Band(2, L("警戒", "Warning"))
        w >= 21 -> Band(1, L("注意", "Caution"))
        else -> Band(0, L("ほぼ安全", "Almost safe"))
    }

    /** WHO の UV インデックスの段階。 */
    fun uvBand(uv: Double): Band = when {
        uv >= 11 -> Band(4, L("極端に強い", "Extreme"))
        uv >= 8 -> Band(3, L("非常に強い", "Very high"))
        uv >= 6 -> Band(2, L("強い", "High"))
        uv >= 3 -> Band(1, L("中程度", "Moderate"))
        else -> Band(0, L("弱い", "Low"))
    }

    /** PM2.5（µg/m³）。環境基準の日平均 35 と、注意喚起の目安 70 で分ける。 */
    fun pm25Band(pm: Double): Band = when {
        pm > 70 -> Band(3, L("注意喚起", "Alert"))
        pm > 35 -> Band(2, L("多い", "High"))
        pm > 15 -> Band(1, L("やや多い", "Moderate"))
        else -> Band(0, L("少ない", "Low"))
    }
}
