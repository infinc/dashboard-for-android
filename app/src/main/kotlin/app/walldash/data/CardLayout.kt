package app.walldash.data

import android.content.Context
import kotlin.math.floor
import kotlin.math.max

/**
 * ダッシュボードのカードの並べ方と、画面に収まるかどうかの判定。
 *
 * 画面（DashboardScreen）の並べ方と、設定で「カードを増やしてよいか」の検査
 * （アプリの設定画面・Web の設定画面・SettingsController.saveAll）が同じ計算を使うよう、ここに 1 つだけ置く。
 */
object CardLayout {

    /** 並べる順と、24 列のうち何列ぶん使うか。 */
    enum class Card(val span: Int, val label: String, val isShown: (DisplayConfig) -> Boolean) {
        CLOCK(8, "時刻", { it.showClock }),
        WEATHER(8, "天気", { it.showWeather }),
        DISASTER(8, "防災", { it.showDisaster }),
        MEMO(10, "LINE メモ", { it.showMemo }),
        HOURLY(9, "時間別予報", { it.showHourly }),
        SPOTIFY(5, "Spotify", { it.showSpotify }),
        WIFI(6, "Wi-Fi", { it.showWifi }),
        STATS(10, "端末状態", { it.showDeviceStats }),
        NEWS(8, "ニュース", { it.showFeed }),
        DAILY(12, "週間予報", { it.showDaily }),
        TIMER(6, "タイマー", { it.showTimer }),
        WORD(6, "今日の単語", { it.showWord }),
        // 後から足したカード（既定は非表示）
        ANALOG_CLOCK(6, "アナログ時計", { it.showAnalogClock }),
        CALENDAR(9, "予定表", { it.showCalendar }),
        TRAIN(9, "運行情報", { it.showTrain }),
        RADAR(8, "雨雲レーダー", { it.showRadar }),
        SUN_MOON(8, "日の出・月", { it.showSunMoon }),
        COUNTDOWN(8, "カウントダウン", { it.showCountdown }),
        TODAY(8, "今日は何の日", { it.showToday }),
        STOCKS(10, "株価", { it.showStocks }),
    }

    const val COLUMNS = 24
    /** これより幅の狭い端末（スマホなど）と縦向きは 2 列にして縦にスクロールさせる。 */
    const val COMPACT_WIDTH_DP = 840
    const val GAP_DP = 12
    /** 画面下のフッター（出典・ボタン・更新時刻）の高さの見積もり。実測前の推定にだけ使う。 */
    private const val FOOTER_DP = 36

    /**
     * カードを並べられる高さと画面の大きさ（dp）。ダッシュボードの画面が実際に測った値を [measured] に入れる。
     * 画面をまだ一度も描いていないとき（Web の設定画面だけ開いている等）は端末の画面の大きさから推定する。
     */
    data class Area(val heightDp: Int, val screenWidthDp: Int, val screenHeightDp: Int)

    @Volatile
    var measured: Area? = null

    fun area(context: Context): Area = measured ?: context.resources.configuration.let {
        Area(it.screenHeightDp - GAP_DP * 2 - FOOTER_DP, it.screenWidthDp, it.screenHeightDp)
    }

    fun shown(d: DisplayConfig): List<Card> = Card.entries.filter { it.isShown(d) }

    fun isCompact(screenWidthDp: Int, screenHeightDp: Int): Boolean =
        screenHeightDp > screenWidthDp || screenWidthDp < COMPACT_WIDTH_DP

    fun compactSpan(card: Card) = if (card == Card.HOURLY) COLUMNS else COLUMNS / 2

    /**
     * 横向きで 1 行に要る最低の高さ。
     * カードの文字や図は画面の高さに比例させている（ui/theme の vh）ので、下限も画面の高さに比例させる。
     * 既定の全カード（4 行）が、画面の高さ 600dp 以上の横向きの端末で収まる値にしてある。
     */
    fun minRowDp(screenHeightDp: Int): Int = (screenHeightDp * 0.2f).toInt().coerceIn(120, 160)

    fun maxRows(area: Area): Int =
        max(1, (area.heightDp + GAP_DP) / (minRowDp(area.screenHeightDp) + GAP_DP))

    /** 週間予報を縮めてよい下限（元の幅の半分）。中身は横にスクロールする。 */
    val DAILY_MIN_SPAN = Card.DAILY.span / 2

    /**
     * 行に詰め、行ごとの合計を 24 列ちょうどにする。
     * 非表示のカードが空けた列は、同じ行に残ったカードへ元の幅に比例して配る（端数は最大剰余法）。
     *
     * [backfill] を渡すと、そのカードのある行に空きが残っている間は、今の行に入らなかった後ろのカードをそこへ戻して詰める
     * （週間予報を縮めて空けた列を、後ろのカードで埋めるため）。
     */
    fun <T> pack(items: List<Pair<T, Int>>, backfill: T? = null): List<List<Pair<T, Int>>> {
        val rows = mutableListOf<MutableList<Pair<T, Int>>>()
        val used = mutableListOf<Int>()
        var home = -1
        items.forEach { item ->
            val target = when {
                rows.isNotEmpty() && used.last() + item.second <= COLUMNS -> rows.lastIndex
                home >= 0 && used[home] + item.second <= COLUMNS -> home
                else -> {
                    rows += mutableListOf<Pair<T, Int>>()
                    used += 0
                    rows.lastIndex
                }
            }
            rows[target] += item
            used[target] += item.second
            if (backfill != null && item.first == backfill) home = target
        }
        return rows.map { row ->
            val total = row.sumOf { it.second }
            val extra = COLUMNS - total
            if (extra <= 0) return@map row
            val exact = row.map { extra.toDouble() * it.second / total }
            val spans = row.mapIndexed { i, it -> it.second + floor(exact[i]).toInt() }.toMutableList()
            val left = extra - exact.sumOf { floor(it).toInt() }
            exact.indices.sortedByDescending { exact[it] - floor(exact[it]) }.take(left).forEach { spans[it] += 1 }
            row.mapIndexed { i, it -> it.first to spans[i] }
        }
    }

    /**
     * 並べ方。横向きで [maxRows] 行に収まらないときは、週間予報を元の幅の半分（[DAILY_MIN_SPAN]）まで 1 列ずつ縮め、
     * 空いた列に後ろのカードを並べる。縮める幅はできるだけ小さくし、並び順はできるだけ保つ
     * （まずは順番のまま詰め、それでも入らなければ後ろのカードを週間予報の行へ戻す）。
     * 半分まで縮めても収まらなければ、縮めない元の並びを返す（呼ぶ側が「収まらない」と扱う）。
     */
    fun rows(d: DisplayConfig, compact: Boolean, maxRows: Int = Int.MAX_VALUE): List<List<Pair<Card, Int>>> {
        val cards = shown(d)
        if (compact) return pack(cards.map { it to compactSpan(it) })
        val plain = pack(cards.map { it to it.span })
        if (plain.size <= maxRows || Card.DAILY !in cards) return plain
        for (span in Card.DAILY.span downTo DAILY_MIN_SPAN) {
            val items = cards.map { it to if (it == Card.DAILY) span else it.span }
            if (span < Card.DAILY.span) pack(items).let { if (it.size <= maxRows) return it }
            pack(items, backfill = Card.DAILY).let { if (it.size <= maxRows) return it }
        }
        return plain
    }

    /** いまの画面での並べ方（[rows] に画面の向きと行数の上限を渡す）。 */
    fun rows(d: DisplayConfig, area: Area): List<List<Pair<Card, Int>>> {
        val compact = isCompact(area.screenWidthDp, area.screenHeightDp)
        return rows(d, compact, if (compact) Int.MAX_VALUE else maxRows(area))
    }

    data class Fit(val fits: Boolean, val rows: Int, val maxRows: Int, val compact: Boolean)

    /**
     * いまの画面に [d] のカードが収まるか。
     * 縦向き・幅の狭い端末は元から縦にスクロールさせる作りなので、常に「収まる」とする。
     * 週間予報を縮めて収まるなら「収まる」（[rows] を参照）。
     */
    fun fit(d: DisplayConfig, area: Area): Fit {
        val compact = isCompact(area.screenWidthDp, area.screenHeightDp)
        val rows = rows(d, area).size
        val maxRows = maxRows(area)
        return Fit(compact || rows <= maxRows, rows, maxRows, compact)
    }

    /**
     * [before] から [after] へ変えると画面に収まらなくなるなら、利用者に見せる説明を返す（収まるなら null）。
     * カードを増やしていない変更（減らす・並びはそのまま）は、元から収まっていなくても止めない。
     */
    fun overflowMessage(before: DisplayConfig, after: DisplayConfig, area: Area): String? {
        val added = shown(after) - shown(before).toSet()
        if (added.isEmpty()) return null
        val fit = fit(after, area)
        if (fit.fits) return null
        val shrink = if (Card.DAILY in shown(after)) "週間予報を半分の幅まで縮めても、" else ""
        return "「${added.joinToString("」「") { it.label }}」を表示すると、${shrink}カードが ${fit.rows} 行になり画面に収まりません" +
            "（この画面に並べられるのは ${fit.maxRows} 行までです）。ほかのカードを非表示にしてから、もう一度表示してください。"
    }
}
