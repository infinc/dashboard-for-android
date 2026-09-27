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

    /**
     * 並べる順と、24 列のうち何列ぶん使うか（[span]）、それ以上縮めると中身が崩れる幅（[min]）。
     * [min] は 1280x800dp の横向きのタブレット（1 列 ≒ 52dp）で、文字の省略や重なりが出ない所まで実際に縮めて決めた。
     */
    enum class Card(val span: Int, val min: Int, val label: String, val isShown: (DisplayConfig) -> Boolean) {
        CLOCK(8, 6, "時刻", { it.showClock }),
        WEATHER(8, 7, "天気", { it.showWeather }),
        DISASTER(8, 7, "防災", { it.showDisaster }),
        MEMO(10, 5, "LINE メモ", { it.showMemo }),
        HOURLY(9, 7, "時間別予報", { it.showHourly }),
        SPOTIFY(5, 5, "Spotify", { it.showSpotify }),
        WIFI(6, 6, "Wi-Fi", { it.showWifi }),
        STATS(10, 7, "端末状態", { it.showDeviceStats }),
        NEWS(8, 5, "ニュース", { it.showFeed }),
        DAILY(12, 6, "週間予報", { it.showDaily }),
        TIMER(6, 5, "タイマー", { it.showTimer }),
        WORD(6, 4, "今日の単語", { it.showWord }),
        // 後から足したカード（既定は非表示）
        ANALOG_CLOCK(6, 4, "アナログ時計", { it.showAnalogClock }),
        CALENDAR(9, 6, "予定表", { it.showCalendar }),
        TRAIN(9, 6, "運行情報", { it.showTrain }),
        RADAR(8, 5, "雨雲レーダー", { it.showRadar }),
        SUN_MOON(8, 7, "日の出・月", { it.showSunMoon }),
        COUNTDOWN(8, 6, "カウントダウン", { it.showCountdown }),
        TODAY(8, 6, "今日は何の日", { it.showToday }),
        STOCKS(10, 6, "株価", { it.showStocks }),
    }

    fun card(name: String): Card? = Card.entries.firstOrNull { it.name == name }

    const val COLUMNS = 24
    /** これより幅の狭い端末（スマホなど）と縦向きは 2 列にして縦にスクロールさせる。 */
    const val COMPACT_WIDTH_DP = 840
    const val GAP_DP = 12
    /** 「カードの配置」で並べられる行の数（横向き）。 */
    const val LAYOUT_ROWS = 4
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
    val DAILY_MIN_SPAN = Card.DAILY.min

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
     * 並べ方。利用者が配置を決めていれば（[DisplayConfig.cardLayout]）その幅のまま並べ、行の右端の余りは空けておく
     * （カードの無い行は飛ばす）。
     * 決めていなければ自動で並べる（[autoRows]）。
     * 縦向き・幅の狭い端末は、どちらでも同じ順番で 2 列に並べ直す。
     */
    fun rows(d: DisplayConfig, compact: Boolean, maxRows: Int = Int.MAX_VALUE): List<List<Pair<Card, Int>>> {
        if (d.cardLayout.isEmpty()) return autoRows(d, compact, maxRows)
        val custom = arranged(d, maxRows)
        if (compact) return pack(custom.flatten().map { (card, _) -> card to compactSpan(card) })
        return custom.filter { it.isNotEmpty() }
    }

    /**
     * 自動の並べ方。横向きで [maxRows] 行に収まらないときは、週間予報を元の幅の半分（[DAILY_MIN_SPAN]）まで 1 列ずつ縮め、
     * 空いた列に後ろのカードを並べる。縮める幅はできるだけ小さくし、並び順はできるだけ保つ
     * （まずは順番のまま詰め、それでも入らなければ後ろのカードを週間予報の行へ戻す）。
     * 半分まで縮めても収まらなければ、縮めない元の並びを返す（呼ぶ側が「収まらない」と扱う）。
     */
    fun autoRows(d: DisplayConfig, compact: Boolean, maxRows: Int = Int.MAX_VALUE): List<List<Pair<Card, Int>>> {
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
        return rows(d, compact, if (compact) Int.MAX_VALUE else rowLimit(d, area))
    }

    /** 横向きで並べてよい行数。利用者の配置は設定画面の編集に合わせて [LAYOUT_ROWS] 行まで。 */
    fun rowLimit(d: DisplayConfig, area: Area): Int =
        if (d.cardLayout.isEmpty()) maxRows(area) else minOf(LAYOUT_ROWS, maxRows(area))

    // ------------------------------------------------------------ 利用者の配置

    /** 保存されている配置を読む（知らない名前・重複・範囲外の幅は落とす／丸める）。表示の有無は見ない。 */
    fun slots(d: DisplayConfig): List<List<Pair<Card, Int>>> {
        val seen = mutableSetOf<Card>()
        return d.cardLayout.map { row ->
            row.mapNotNull { slot ->
                val card = card(slot.card) ?: return@mapNotNull null
                if (!seen.add(card)) return@mapNotNull null
                card to slot.span.coerceIn(card.min, COLUMNS)
            }
        }
    }

    fun toSlots(rows: List<List<Pair<Card, Int>>>): List<List<CardSlot>> =
        trimEnd(rows).map { row -> row.map { (card, span) -> CardSlot(card.name, span) } }

    /**
     * 末尾の空の行を落とす。途中の空の行は残す（設定画面の 4 行のどこに置いたかを保つため。ダッシュボードは [rows] で飛ばす）。
     */
    fun <T> trimEnd(rows: List<List<T>>): List<List<T>> = rows.dropLastWhile { it.isEmpty() }

    /**
     * 利用者の配置を、いま表示するカードに合わせて整える。
     * 非表示のカードは抜き（その分は空く）、行の幅が 24 列を超えていれば縮める。空になった行もその位置に残す。
     * 配置に無いのに表示するカード（古い設定など）は [place] で空いた所へ入れ、入らなければ最後に行を足す。
     */
    fun arranged(d: DisplayConfig, maxRows: Int = LAYOUT_ROWS): List<List<Pair<Card, Int>>> {
        val show = shown(d).toSet()
        var rows = trimEnd(slots(d).map { row -> fitRow(row.filter { it.first in show }) })
        val placed = rows.flatten().map { it.first }.toSet()
        shown(d).filter { it !in placed }.forEach { card ->
            rows = place(rows, card, minOf(maxRows, LAYOUT_ROWS)) ?: (rows + listOf(listOf(card to card.span)))
        }
        return rows
    }

    /** 行の合計が 24 列を超えていれば、最小の幅より広いカードを広い順に 1 列ずつ縮める（最小の合計が超えるならそのまま）。 */
    private fun fitRow(row: List<Pair<Card, Int>>): List<Pair<Card, Int>> {
        val spans = row.map { it.second }.toMutableList()
        while (spans.sum() > COLUMNS) {
            val i = spans.indices.filter { spans[it] > row[it].first.min }.maxByOrNull { spans[it] - row[it].first.min } ?: break
            spans[i]--
        }
        return row.mapIndexed { i, (card, _) -> card to spans[i] }
    }

    /**
     * [card] を配置に入れる。入れられなければ null。
     * 1. 元の幅がそのまま入る空きのある行の右端へ
     * 2. 行が [limit] 行に満たなければ、新しい行へ元の幅で
     * 3. 同じ行のほかのカードを最小の幅まで縮めれば入る行のうち、いちばん余裕のある行へ（[squeeze]）
     */
    fun place(rows: List<List<Pair<Card, Int>>>, card: Card, limit: Int): List<List<Pair<Card, Int>>>? {
        fun free(row: List<Pair<Card, Int>>) = COLUMNS - row.sumOf { it.second }
        fun slack(row: List<Pair<Card, Int>>) = COLUMNS - row.sumOf { it.first.min }
        fun with(index: Int, row: List<Pair<Card, Int>>) = rows.toMutableList().also { it[index] = row }

        rows.indexOfFirst { free(it) >= card.span }.takeIf { it >= 0 }?.let { return with(it, rows[it] + (card to card.span)) }
        if (rows.size < limit) return rows + listOf(listOf(card to card.span))
        val index = rows.indices.filter { slack(rows[it]) >= card.min }.maxByOrNull { slack(rows[it]) } ?: return null
        return with(index, squeeze(rows[index], card))
    }

    /**
     * 行の [at] 番目に [card] を入れる。空きが最小の幅に足りなければ、ほかのカードを（最小の幅を超えている分の多い順に 1 列ずつ）縮める。
     * そのあと、空きと、ほかのカードの最小の幅を超えている分から、新しいカードが [want] 列になるまで少しずつ分けてもらう
     * （1 枚だけが広いまま新しいカードが最小になる、という偏りを避ける）。
     */
    private fun squeeze(row: List<Pair<Card, Int>>, card: Card, want: Int = card.span, at: Int = row.size): List<Pair<Card, Int>> {
        val spans = row.map { it.second }.toMutableList()
        fun excess(i: Int) = spans[i] - row[i].first.min
        fun widest() = spans.indices.maxByOrNull(::excess)
        while (COLUMNS - spans.sum() < card.min) spans[widest()!!]--
        var span = card.min
        while (span < want) {
            if (COLUMNS - spans.sum() - span > 0) {
                span++
                continue
            }
            val i = widest() ?: break
            if (excess(i) <= span - card.min + 1) break
            spans[i]--
            span++
        }
        return row.mapIndexed { i, (c, _) -> c to spans[i] }.toMutableList().also { it.add(at.coerceIn(0, row.size), card to span) }
    }

    /**
     * 設定画面でカードを別の場所へ動かす（[fromRow] 行の [fromIndex] 番目を、[toRow] 行の [toIndex] 番目へ）。
     * [toIndex] は、動かすカードを抜いたあとの行での位置。
     * 同じ行の中なら順番だけ入れ替える。別の行へは今の幅のまま入れ、空きが足りなければ行きの行のカードを最小の幅まで縮める。
     * 行きの行のカードをすべて最小の幅にしても入らなければ null（呼ぶ側が理由を出して元に戻す）。
     * 元の行はその分だけ空く。
     */
    fun move(
        rows: List<List<Pair<Card, Int>>>,
        fromRow: Int,
        fromIndex: Int,
        toRow: Int,
        toIndex: Int,
    ): List<List<Pair<Card, Int>>>? {
        val grid = MutableList(maxOf(rows.size, toRow + 1)) { rows.getOrElse(it) { emptyList() }.toMutableList() }
        val (card, span) = grid[fromRow].removeAt(fromIndex)
        val target = grid[toRow]
        val at = toIndex.coerceIn(0, target.size)
        when {
            toRow == fromRow || COLUMNS - target.sumOf { it.second } >= span -> target.add(at, card to span)
            COLUMNS - target.sumOf { it.first.min } >= card.min -> grid[toRow] = squeeze(target, card, span, at).toMutableList()
            else -> return null
        }
        return trimEnd(grid)
    }

    /**
     * 設定画面で行 [row] の [index] 番目のカードの右の壁を [delta] 列ぶん動かす。
     * 右へ: まず右隣のカードを最小の幅まで縮め、それでも足りなければ行の右端の空きを使う（右隣ごと右へずれる）。
     * 左へ: 自分を最小の幅まで縮め、その分を右隣へ渡す（右端のカードなら空きになる）。
     */
    fun resize(row: List<Pair<Card, Int>>, index: Int, delta: Int): List<Pair<Card, Int>> {
        val spans = row.map { it.second }.toMutableList()
        val next = index + 1
        if (delta > 0) {
            var grow = delta
            if (next < row.size) {
                val take = minOf(grow, spans[next] - row[next].first.min).coerceAtLeast(0)
                spans[next] -= take
                spans[index] += take
                grow -= take
            }
            spans[index] += minOf(grow, (COLUMNS - spans.sum()).coerceAtLeast(0))
        } else if (delta < 0) {
            val shrink = minOf(-delta, spans[index] - row[index].first.min).coerceAtLeast(0)
            spans[index] -= shrink
            if (next < row.size) spans[next] += shrink
        }
        return row.mapIndexed { i, (card, _) -> card to spans[i] }
    }

    /** 設定画面の「カードの配置」に出す並び（横向き）。自動のときは自動の並びを、そのまま幅つきで見せる。 */
    fun editorRows(d: DisplayConfig, area: Area): List<List<Pair<Card, Int>>> =
        if (d.cardLayout.isEmpty()) autoRows(d, compact = false, maxRows = maxRows(area)) else arranged(d, rowLimit(d, area))

    data class Fit(val fits: Boolean, val rows: Int, val maxRows: Int, val compact: Boolean)

    /**
     * いまの画面に [d] のカードが収まるか。
     * 縦向き・幅の狭い端末は元から縦にスクロールさせる作りなので、常に「収まる」とする。
     * 週間予報を縮めて収まるなら「収まる」（[autoRows] を参照）。
     */
    fun fit(d: DisplayConfig, area: Area): Fit {
        val compact = isCompact(area.screenWidthDp, area.screenHeightDp)
        val limit = rowLimit(d, area)
        val rows = if (compact) rows(d, false, limit).size else rows(d, area).size
        return Fit(compact || rows <= limit, rows, limit, compact)
    }

    /** [adjust] の結果。[message] が null なら [display] をそのまま使ってよい。 */
    data class Adjusted(val display: DisplayConfig, val message: String?)

    /**
     * [before] から [after] へ変えるときに、カードの配置を合わせる。
     *
     * - 配置を決めていれば、非表示にしたカードを抜き、表示にしたカードを [place] で入れる。
     * - 自動のままで収まるなら、自動のまま。収まらなければ、いまの自動の並びを利用者の配置に切り替えて、
     *   ほかのカードを最小の幅まで縮めて入れる（週間予報を縮めるだけの従来の計算より多く入る）。
     * - どれだけ縮めても入らないときは [Adjusted.message] に理由を入れる（呼ぶ側が追加を止める）。
     *
     * 縦向き・幅の狭い端末では収まるかを問わない（縦にスクロールする）。入らないカードは最後の行に足しておく。
     */
    fun adjust(before: DisplayConfig, after: DisplayConfig, area: Area): Adjusted {
        val compact = isCompact(area.screenWidthDp, area.screenHeightDp)
        val added = shown(after) - shown(before).toSet()
        if (after.cardLayout.isEmpty() && (added.isEmpty() || fit(after, area).fits)) return Adjusted(after, null)

        val limit = if (after.cardLayout.isEmpty()) minOf(LAYOUT_ROWS, maxRows(area)) else rowLimit(after, area)
        val show = shown(after).toSet()
        var rows = (if (after.cardLayout.isEmpty()) autoRows(before, false, maxRows(area)) else slots(after))
            .map { row -> fitRow(row.filter { it.first in show }) }
            .let(::trimEnd)
        val failed = mutableListOf<Card>()
        val placed = rows.flatten().map { it.first }.toSet()
        shown(after).filter { it !in placed }.forEach { card ->
            val next = place(rows, card, limit)
            if (next != null) rows = next else {
                failed += card
                rows = rows + listOf(listOf(card to card.span))
            }
        }
        val display = after.copy(cardLayout = toSlots(rows))
        // カードを増やしていない変更（減らす・幅を変える）は、元から収まっていなくても止めない
        if (compact || added.isEmpty() || (failed.isEmpty() && rows.count { it.isNotEmpty() } <= limit)) return Adjusted(display, null)
        val names = (failed.ifEmpty { added }).joinToString("」「") { it.label }
        return Adjusted(
            display,
            "「$names」は、ほかのカードをいちばん狭い幅まで縮めても画面に入りません" +
                "（この画面に並べられるのは ${limit} 行までです）。ほかのカードを非表示にしてから、もう一度表示してください。",
        )
    }

    /** 配置を「自動」へ戻せるか。利用者の配置でだけ入っていたカードがあると、自動では収まらないことがある。 */
    fun autoMessage(d: DisplayConfig, area: Area): String? {
        val auto = d.copy(cardLayout = emptyList())
        val fit = fit(auto, area)
        if (fit.fits) return null
        return "自動の並べ方では、いまのカードが ${fit.rows} 行になり画面に収まりません（この画面に並べられるのは ${fit.maxRows} 行までです）。" +
            "ほかのカードを非表示にしてから、もう一度お試しください。"
    }
}
