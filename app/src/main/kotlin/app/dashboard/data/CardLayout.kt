package app.dashboard.data

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
    enum class Card(
        val span: Int,
        val min: Int,
        val label: String,
        /** [DisplayConfig] の表示の項目の名前（Web の設定画面が、足す・外すときに切り替えるチェックボックスを探す）。 */
        val flag: String,
        val isShown: (DisplayConfig) -> Boolean,
        /** 表示・非表示を切り替えた設定を返す（「カードの配置」でドラッグして足す・外すため）。 */
        val show: (DisplayConfig, Boolean) -> DisplayConfig,
    ) {
        CLOCK(8, 6, "時刻", "showClock", { it.showClock }, { d, on -> d.copy(showClock = on) }),
        WEATHER(8, 7, "天気", "showWeather", { it.showWeather }, { d, on -> d.copy(showWeather = on) }),
        DISASTER(8, 7, "防災", "showDisaster", { it.showDisaster }, { d, on -> d.copy(showDisaster = on) }),
        MEMO(10, 5, "LINE メモ", "showMemo", { it.showMemo }, { d, on -> d.copy(showMemo = on) }),
        HOURLY(9, 7, "時間別予報", "showHourly", { it.showHourly }, { d, on -> d.copy(showHourly = on) }),
        SPOTIFY(5, 5, "Spotify", "showSpotify", { it.showSpotify }, { d, on -> d.copy(showSpotify = on) }),
        WIFI(6, 6, "Wi-Fi", "showWifi", { it.showWifi }, { d, on -> d.copy(showWifi = on) }),
        STATS(10, 7, "端末状態", "showDeviceStats", { it.showDeviceStats }, { d, on -> d.copy(showDeviceStats = on) }),
        NEWS(8, 5, "ニュース", "showFeed", { it.showFeed }, { d, on -> d.copy(showFeed = on) }),
        DAILY(12, 6, "週間予報", "showDaily", { it.showDaily }, { d, on -> d.copy(showDaily = on) }),
        TIMER(6, 5, "タイマー", "showTimer", { it.showTimer }, { d, on -> d.copy(showTimer = on) }),
        WORD(6, 4, "今日の単語", "showWord", { it.showWord }, { d, on -> d.copy(showWord = on) }),
        // 後から足したカード（既定は非表示）
        ANALOG_CLOCK(6, 4, "アナログ時計", "showAnalogClock", { it.showAnalogClock }, { d, on -> d.copy(showAnalogClock = on) }),
        CALENDAR(9, 6, "予定表", "showCalendar", { it.showCalendar }, { d, on -> d.copy(showCalendar = on) }),
        TRAIN(9, 6, "運行情報", "showTrain", { it.showTrain }, { d, on -> d.copy(showTrain = on) }),
        RADAR(8, 5, "雨雲レーダー", "showRadar", { it.showRadar }, { d, on -> d.copy(showRadar = on) }),
        SUN_MOON(8, 7, "日の出・月", "showSunMoon", { it.showSunMoon }, { d, on -> d.copy(showSunMoon = on) }),
        COUNTDOWN(8, 6, "カウントダウン", "showCountdown", { it.showCountdown }, { d, on -> d.copy(showCountdown = on) }),
        TODAY(8, 6, "今日は何の日", "showToday", { it.showToday }, { d, on -> d.copy(showToday = on) }),
        STOCKS(10, 6, "株価", "showStocks", { it.showStocks }, { d, on -> d.copy(showStocks = on) }),
        CALCULATOR(6, 5, "計算機", "showCalculator", { it.showCalculator }, { d, on -> d.copy(showCalculator = on) }),
        PHOTOS(8, 5, "写真", "showPhotos", { it.showPhotos }, { d, on -> d.copy(showPhotos = on) }),
    }

    /**
     * 配置の 1 枚。[span] は 24 列のうち何列か、[height] は何行ぶんの高さか。
     * 縦に伸ばしたカード（[height] が 2 以上）の下の行では、その列を飛ばして左から並べる（[positions]）。
     */
    data class Tile(val card: Card, val span: Int, val height: Int = 1)

    /** 置き場所の決まった 1 枚（[row] 行目の [col] 列目から）。 */
    data class Placed(val card: Card, val row: Int, val col: Int, val span: Int, val height: Int)

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

    private fun tiles(rows: List<List<Pair<Card, Int>>>): List<List<Tile>> = rows.map { row -> row.map { (card, span) -> Tile(card, span) } }

    /**
     * 並べ方。利用者が配置を決めていれば（[DisplayConfig.cardLayout]）その幅・高さのまま並べ、行の右端の余りは空けておく
     * （カードの無い行は [grid] が飛ばす）。
     * 決めていなければ自動で並べる（[autoRows]）。
     * 縦向き・幅の狭い端末は、どちらでも同じ順番で 2 列に並べ直す（高さは使わない）。
     */
    fun rows(d: DisplayConfig, compact: Boolean, maxRows: Int = Int.MAX_VALUE): List<List<Tile>> {
        if (d.cardLayout.isEmpty()) return autoRows(d, compact, maxRows)
        val custom = arranged(d, maxRows)
        if (compact) return tiles(pack(custom.flatten().map { it.card to compactSpan(it.card) }))
        return custom
    }

    /**
     * 自動の並べ方。横向きで [maxRows] 行に収まらないときは、週間予報を元の幅の半分（[DAILY_MIN_SPAN]）まで 1 列ずつ縮め、
     * 空いた列に後ろのカードを並べる。縮める幅はできるだけ小さくし、並び順はできるだけ保つ
     * （まずは順番のまま詰め、それでも入らなければ後ろのカードを週間予報の行へ戻す）。
     * 半分まで縮めても収まらなければ、縮めない元の並びを返す（呼ぶ側が「収まらない」と扱う）。
     */
    fun autoRows(d: DisplayConfig, compact: Boolean, maxRows: Int = Int.MAX_VALUE): List<List<Tile>> {
        val cards = shown(d)
        if (compact) return tiles(pack(cards.map { it to compactSpan(it) }))
        val plain = pack(cards.map { it to it.span })
        if (plain.size <= maxRows || Card.DAILY !in cards) return tiles(plain)
        for (span in Card.DAILY.span downTo DAILY_MIN_SPAN) {
            val items = cards.map { it to if (it == Card.DAILY) span else it.span }
            if (span < Card.DAILY.span) pack(items).let { if (it.size <= maxRows) return tiles(it) }
            pack(items, backfill = Card.DAILY).let { if (it.size <= maxRows) return tiles(it) }
        }
        return tiles(plain)
    }

    /** いまの画面での並べ方（[rows] に画面の向きと行数の上限を渡す）。 */
    fun rows(d: DisplayConfig, area: Area): List<List<Tile>> {
        val compact = isCompact(area.screenWidthDp, area.screenHeightDp)
        return rows(d, compact, if (compact) Int.MAX_VALUE else rowLimit(d, area))
    }

    /** ダッシュボードに描く位置。[rows] 行ぶんの高さに、[cards] を置く。 */
    data class Grid(val cards: List<Placed>, val rows: Int)

    /**
     * いまの画面でのカードの位置。カードが 1 枚も掛かっていない行は詰める（縦に伸ばしたカードが掛かっている行は残す）。
     * 高さの組み合わせが成り立たない配置（古い設定など）は、高さを使わずに並べる。
     */
    fun grid(d: DisplayConfig, area: Area): Grid {
        val rows = rows(d, area)
        val placed = positions(rows, Int.MAX_VALUE) ?: positions(flat(rows), Int.MAX_VALUE)!!
        val used = (0 until (placed.maxOfOrNull { it.row + it.height } ?: 0)).filter { r -> placed.any { r in it.row until it.row + it.height } }
        val index = used.withIndex().associate { (i, r) -> r to i }
        return Grid(placed.map { it.copy(row = index.getValue(it.row)) }, used.size)
    }

    /** 横向きで並べてよい行数。利用者の配置は設定画面の編集に合わせて [LAYOUT_ROWS] 行まで。 */
    fun rowLimit(d: DisplayConfig, area: Area): Int =
        if (d.cardLayout.isEmpty()) maxRows(area) else minOf(LAYOUT_ROWS, maxRows(area))

    // ------------------------------------------------------------ 位置と高さ

    /**
     * 行ごとの並びから、各カードの位置を決める。行の中では左から順に詰め、上の行から縦に伸びてきたカードの列は飛ばす
     * （飛ばした手前は空く）。右端（24 列）を越えるカードがあるか、[limit] 行より下へ伸びるカードがあれば null。
     */
    fun positions(rows: List<List<Tile>>, limit: Int): List<Placed>? {
        val blocked = HashMap<Int, MutableList<IntRange>>()
        val out = mutableListOf<Placed>()
        rows.forEachIndexed { r, row ->
            var x = 0
            row.forEach { t ->
                if (r + t.height > limit) return null
                // 自分が掛かる行のどこかで、上から伸びてきたカードに重なるなら、その右へずらす
                while (true) {
                    val hit = (r until r + t.height).flatMap { blocked[it].orEmpty() }
                        .filter { it.first < x + t.span && x <= it.last }
                        .maxOfOrNull { it.last + 1 } ?: break
                    x = hit
                }
                if (x + t.span > COLUMNS) return null
                out += Placed(t.card, r, x, t.span, t.height)
                for (below in r + 1 until r + t.height) blocked.getOrPut(below) { mutableListOf() } += x until x + t.span
                x += t.span
            }
        }
        return out
    }

    fun fits(rows: List<List<Tile>>, limit: Int = LAYOUT_ROWS): Boolean = positions(rows, limit) != null

    /** 高さを使わない並び（どれも 1 行）。 */
    fun flat(rows: List<List<Tile>>): List<List<Tile>> = rows.map { row -> row.map { it.copy(height = 1) } }

    /** [r] 行目のうち、上の行から縦に伸びてきたカードが使っている列の数。 */
    private fun reserved(rows: List<List<Tile>>, r: Int): Int =
        (0 until minOf(r, rows.size)).sumOf { q -> rows[q].filter { q + it.height > r }.sumOf { it.span } }

    /** [r] 行目に並べられる列の数（24 列から、上から伸びてきたカードの分を除く）。 */
    private fun capacity(rows: List<List<Tile>>, r: Int) = COLUMNS - reserved(rows, r)

    /**
     * 設定画面で行 [r] の [index] 番目のカードの下の壁を動かし、高さを [height] 行にする。
     * 下の行に空きが足りない（下の行のカードが右端から押し出される）か、[limit] 行を越えるなら、入る所までにとどめる。
     */
    fun setHeight(rows: List<List<Tile>>, r: Int, index: Int, height: Int, limit: Int = LAYOUT_ROWS): List<List<Tile>> {
        val now = rows[r][index].height
        var h = height.coerceIn(1, limit - r)
        while (h != now) {
            val next = rows.toMutableList().also { list -> list[r] = list[r].toMutableList().also { it[index] = it[index].copy(height = h) } }
            if (fits(next, limit)) return next
            h += if (h > now) -1 else 1
        }
        return rows
    }

    // ------------------------------------------------------------ 利用者の配置

    /** 保存されている配置を読む（知らない名前・重複・範囲外の幅と高さは落とす／丸める）。表示の有無は見ない。 */
    fun slots(d: DisplayConfig): List<List<Tile>> {
        val seen = mutableSetOf<Card>()
        return d.cardLayout.map { row ->
            row.mapNotNull { slot ->
                val card = card(slot.card) ?: return@mapNotNull null
                if (!seen.add(card)) return@mapNotNull null
                Tile(card, slot.span.coerceIn(card.min, COLUMNS), slot.height.coerceIn(1, LAYOUT_ROWS))
            }
        }
    }

    fun toSlots(rows: List<List<Tile>>): List<List<CardSlot>> =
        trimEnd(rows).map { row -> row.map { CardSlot(it.card.name, it.span, it.height) } }

    /**
     * 末尾の空の行を落とす。途中の空の行は残す（設定画面の 4 行のどこに置いたかを保つため。ダッシュボードは [grid] で飛ばす）。
     */
    fun <T> trimEnd(rows: List<List<T>>): List<List<T>> = rows.dropLastWhile { it.isEmpty() }

    /**
     * 利用者の配置を、いま表示するカードに合わせて整える。
     * 非表示のカードは抜き（その分は空く）、行の幅が 24 列を超えていれば縮める。空になった行もその位置に残す。
     * 配置に無いのに表示するカード（古い設定など）は [place] で空いた所へ入れ、入らなければ最後に行を足す。
     * 高さの組み合わせが成り立たなくなったら、高さを使わない並びにする。
     */
    fun arranged(d: DisplayConfig, maxRows: Int = LAYOUT_ROWS): List<List<Tile>> {
        val show = shown(d).toSet()
        var rows = trimEnd(slots(d).map { row -> fitRow(row.filter { it.card in show }) })
        val limit = minOf(maxRows, LAYOUT_ROWS)
        val placed = rows.flatten().map { it.card }.toSet()
        shown(d).filter { it !in placed }.forEach { card ->
            rows = place(rows, card, limit) ?: (rows + listOf(listOf(Tile(card, card.span))))
        }
        return if (fits(rows, maxOf(limit, rows.size))) rows else flat(rows)
    }

    /** 行の合計が [cap] 列を超えていれば、最小の幅より広いカードを広い順に 1 列ずつ縮める（最小の合計が超えるならそのまま）。 */
    private fun fitRow(row: List<Tile>, cap: Int = COLUMNS): List<Tile> {
        val spans = row.map { it.span }.toMutableList()
        while (spans.sum() > cap) {
            val i = spans.indices.filter { spans[it] > row[it].card.min }.maxByOrNull { spans[it] - row[it].card.min } ?: break
            spans[i]--
        }
        return row.mapIndexed { i, t -> t.copy(span = spans[i]) }
    }

    /**
     * [card] を配置に入れる。入れられなければ null。
     * 1. 元の幅がそのまま入る空きのある行の右端へ
     * 2. 行が [limit] 行に満たなければ、新しい行へ元の幅で
     * 3. 同じ行のほかのカードを最小の幅まで縮めれば入る行のうち、いちばん余裕のある行へ（[squeeze]）
     * 縦に伸ばしたカードの列は使えない。入れた結果の位置が成り立たない候補は飛ばす。
     */
    fun place(rows: List<List<Tile>>, card: Card, limit: Int): List<List<Tile>>? {
        fun free(r: Int) = capacity(rows, r) - rows[r].sumOf { it.span }
        fun slack(r: Int) = capacity(rows, r) - rows[r].sumOf { it.card.min }
        fun with(index: Int, row: List<Tile>) = rows.toMutableList().also { it[index] = row }

        val candidates = sequence {
            rows.indices.filter { free(it) >= card.span }.forEach { yield(with(it, rows[it] + Tile(card, card.span))) }
            if (rows.size < limit) yield(rows + listOf(listOf(Tile(card, card.span))))
            rows.indices.filter { slack(it) >= card.min }.sortedByDescending { slack(it) }
                .forEach { yield(with(it, squeeze(rows[it], card, cap = capacity(rows, it)))) }
        }
        return candidates.firstOrNull { fits(it, maxOf(limit, rows.size)) }
    }

    /**
     * 行の [at] 番目に [card] を入れる。空きが最小の幅に足りなければ、ほかのカードを（最小の幅を超えている分の多い順に 1 列ずつ）縮める。
     * そのあと、空きと、ほかのカードの最小の幅を超えている分から、新しいカードが [want] 列になるまで少しずつ分けてもらう
     * （1 枚だけが広いまま新しいカードが最小になる、という偏りを避ける）。[cap] はその行に並べられる列の数。
     */
    private fun squeeze(
        row: List<Tile>,
        card: Card,
        want: Int = card.span,
        at: Int = row.size,
        height: Int = 1,
        cap: Int = COLUMNS,
    ): List<Tile> {
        val spans = row.map { it.span }.toMutableList()
        fun excess(i: Int) = spans[i] - row[i].card.min
        fun widest() = spans.indices.maxByOrNull(::excess)
        while (cap - spans.sum() < card.min) spans[widest()!!]--
        var span = card.min
        while (span < want) {
            if (cap - spans.sum() - span > 0) {
                span++
                continue
            }
            val i = widest() ?: break
            if (excess(i) <= span - card.min + 1) break
            spans[i]--
            span++
        }
        return row.mapIndexed { i, t -> t.copy(span = spans[i]) }.toMutableList()
            .also { it.add(at.coerceIn(0, row.size), Tile(card, span, height)) }
    }

    /**
     * 行 [toRow] の [toIndex] 番目に [tile] を入れる（[rows] には入っていない前提）。
     * 今の幅のまま入れ、空きが足りなければ行きの行のカードを最小の幅まで縮める。高さが下の行とぶつかるなら 1 行にする。
     * どうしても入らなければ null。
     */
    private fun putAt(rows: List<List<Tile>>, tile: Tile, toRow: Int, toIndex: Int, limit: Int): List<List<Tile>>? {
        val grid = MutableList(maxOf(rows.size, toRow + 1)) { rows.getOrElse(it) { emptyList() } }
        val target = grid[toRow]
        val at = toIndex.coerceIn(0, target.size)
        val cap = capacity(grid, toRow)
        fun attempt(t: Tile): List<List<Tile>>? {
            val row = when {
                cap - target.sumOf { it.span } >= t.span -> target.toMutableList().also { it.add(at, t) }
                cap - target.sumOf { it.card.min } >= t.card.min -> squeeze(target, t.card, t.span, at, t.height, cap)
                else -> return null
            }
            return trimEnd(grid.toMutableList().also { it[toRow] = row }).takeIf { fits(it, limit) }
        }
        return attempt(tile.copy(height = tile.height.coerceAtMost(limit - toRow).coerceAtLeast(1)))
            ?: if (tile.height > 1) attempt(tile.copy(height = 1)) else null
    }

    /**
     * 設定画面でカードを別の場所へ動かす（[fromRow] 行の [fromIndex] 番目を、[toRow] 行の [toIndex] 番目へ）。
     * [toIndex] は、動かすカードを抜いたあとの行での位置。
     * 同じ行の中なら順番だけ入れ替える。別の行へは今の幅のまま入れ、空きが足りなければ行きの行のカードを最小の幅まで縮める。
     * 行きの行のカードをすべて最小の幅にしても入らなければ null（呼ぶ側が理由を出して元に戻す）。
     * 元の行はその分だけ空く。
     */
    fun move(
        rows: List<List<Tile>>,
        fromRow: Int,
        fromIndex: Int,
        toRow: Int,
        toIndex: Int,
        limit: Int = LAYOUT_ROWS,
    ): List<List<Tile>>? {
        val grid = rows.toMutableList()
        val tile = grid[fromRow][fromIndex]
        grid[fromRow] = grid[fromRow].filterIndexed { i, _ -> i != fromIndex }
        if (toRow == fromRow) {
            val row = grid[toRow].toMutableList().also { it.add(toIndex.coerceIn(0, it.size), tile) }
            val next = trimEnd(grid.also { it[toRow] = row })
            return next.takeIf { fits(it, limit) } ?: putAt(remove(rows, fromRow, fromIndex), tile.copy(height = 1), toRow, toIndex, limit)
        }
        return putAt(grid, tile, toRow, toIndex, limit)
    }

    /** 設定画面で、使っていないカード [card] を行 [toRow] の [toIndex] 番目へ足す（元の幅で。足りなければほかを縮める）。入らなければ null。 */
    fun insert(rows: List<List<Tile>>, card: Card, toRow: Int, toIndex: Int, limit: Int = LAYOUT_ROWS): List<List<Tile>>? =
        putAt(rows, Tile(card, card.span), toRow, toIndex, limit)

    /** 設定画面で、行 [r] の [index] 番目のカードを外す（その分は空く）。 */
    fun remove(rows: List<List<Tile>>, r: Int, index: Int): List<List<Tile>> =
        trimEnd(rows.toMutableList().also { list -> list[r] = list[r].filterIndexed { i, _ -> i != index } })

    /**
     * 設定画面で行 [r] の [index] 番目のカードの右の壁を [delta] 列ぶん動かす。
     * 右へ: まず右隣のカードを最小の幅まで縮め、それでも足りなければ行の右端の空きを使う（右隣ごと右へずれる）。
     * 左へ: 自分を最小の幅まで縮め、その分を右隣へ渡す（右端のカードなら空きになる）。
     * 縦に伸ばしたカードがあって位置が成り立たなくなるなら、成り立つ所までにとどめる。
     */
    fun resize(rows: List<List<Tile>>, r: Int, index: Int, delta: Int, limit: Int = LAYOUT_ROWS): List<List<Tile>> {
        var step = delta
        while (step != 0) {
            val next = rows.toMutableList().also { it[r] = resizeRow(rows[r], index, step) }
            if (fits(next, limit)) return next
            step -= Integer.signum(step)
        }
        return rows
    }

    private fun resizeRow(row: List<Tile>, index: Int, delta: Int): List<Tile> {
        val spans = row.map { it.span }.toMutableList()
        val next = index + 1
        if (delta > 0) {
            var grow = delta
            if (next < row.size) {
                val take = minOf(grow, spans[next] - row[next].card.min).coerceAtLeast(0)
                spans[next] -= take
                spans[index] += take
                grow -= take
            }
            spans[index] += minOf(grow, (COLUMNS - spans.sum()).coerceAtLeast(0))
        } else if (delta < 0) {
            val shrink = minOf(-delta, spans[index] - row[index].card.min).coerceAtLeast(0)
            spans[index] -= shrink
            if (next < row.size) spans[next] += shrink
        }
        return row.mapIndexed { i, t -> t.copy(span = spans[i]) }
    }

    /** 設定画面の「カードの配置」に出す並び（横向き）。自動のときは自動の並びを、そのまま幅つきで見せる。 */
    fun editorRows(d: DisplayConfig, area: Area): List<List<Tile>> =
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
        val rows = if (compact) rows(d, false, limit).count { it.isNotEmpty() } else grid(d, area).rows
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
            .map { row -> fitRow(row.filter { it.card in show }) }
            .let(::trimEnd)
        if (!fits(rows, maxOf(limit, rows.size))) rows = flat(rows)
        val failed = mutableListOf<Card>()
        val placed = rows.flatten().map { it.card }.toSet()
        shown(after).filter { it !in placed }.forEach { card ->
            val next = place(rows, card, limit)
            if (next != null) rows = next else {
                failed += card
                rows = rows + listOf(listOf(Tile(card, card.span)))
            }
        }
        val display = after.copy(cardLayout = toSlots(rows))
        // カードを増やしていない変更（減らす・幅を変える）は、元から収まっていなくても止めない
        if (compact || added.isEmpty() || (failed.isEmpty() && fits(rows, limit))) return Adjusted(display, null)
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
