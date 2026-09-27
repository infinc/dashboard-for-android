package app.walldash.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.walldash.data.CardLayout
import app.walldash.data.DisplayConfig
import app.walldash.ui.theme.LocalAccent
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.tu
import kotlin.math.abs
import kotlin.math.roundToInt

private typealias Rows = List<List<CardLayout.Tile>>

private val ROW_HEIGHT = 74.dp
private val ROW_GAP = 8.dp
private val CARD_GAP = 6.dp
/** 右の壁のつまみを掴める範囲（壁から左右それぞれ）。 */
private val GRAB = 22.dp
/** 下の壁のつまみを掴める範囲（壁から上下それぞれ）。 */
private val GRAB_BOTTOM = 14.dp

/** 使っていないカードの置き場（編集欄の下）。 */
private val TRAY_GAP = 20.dp
private val TRAY_LABEL = 26.dp
private val CHIP_WIDTH = 128.dp
private val CHIP_HEIGHT = 46.dp
private val CHIP_GAP = 8.dp

/**
 * 動かしている最中のカード。[row] / [index] は配置の中の元の場所（置き場から持ってきたカードは [row] が -1）。
 * [at] は指の位置、[grab] はカードの左上から見た掴んだ位置（どちらも px）。
 */
private data class Moving(val card: CardLayout.Card, val row: Int, val index: Int, val at: Offset, val grab: Offset) {
    val fromTray: Boolean get() = row < 0
}

/**
 * 離したらどうなるか。[row] 行の [index] 番目へ入る（[col] は差し込む位置の線の列）。[fits] が false なら入らない。
 * [remove] なら枠の外で、離すとカードを外す。
 */
private data class Drop(val row: Int, val index: Int, val col: Int, val fits: Boolean, val remove: Boolean = false)

/**
 * 「カードの配置」。横向きの画面の 4 行を縮めて描く。
 * - カードの右の壁（つまみ）を左右にドラッグすると幅が変わる（[CardLayout.resize]）。24 列単位で吸い付き、最小の幅より狭くならない。
 * - カードの下の壁（つまみ）を上下にドラッグすると高さが変わる（[CardLayout.setHeight]）。下の行に空きがある所までしか伸びない。
 * - カードを長押しして動かすと、同じ行の中の順番や、別の行へ入れ替えられる（[CardLayout.move]）。枠の外で離すと外す（非表示にする）。
 * - 下の「使っていないカード」を長押しして枠の中へ動かすと、そのカードを足す（[CardLayout.insert]）。
 * 行の右端や、行そのものが空いても、そのまま保存できる（ダッシュボードでも空きになる。カードの無い行は詰める）。
 */
@Composable
fun LayoutPane(display: DisplayConfig, onChange: (DisplayConfig) -> Unit) {
    val context = LocalContext.current
    val area = CardLayout.area(context)
    val rows = CardLayout.editorRows(display, area)
    val custom = display.cardLayout.isNotEmpty()
    val unused = CardLayout.Card.entries.filter { !it.isShown(display) }
    var blocked by remember { mutableStateOf<Pair<String, String>?>(null) }

    PaneTitle(
        "カードの配置",
        "横向きのダッシュボードを縮めて描いています。カードの右の壁（つまみ）を左右にドラッグすると幅が、下の壁を上下にドラッグすると高さが、" +
            "カードを長押ししてから動かすと置き場所（ほかの行・同じ行の順番）が変わります。",
    )

    Field(hint = "行は ${CardLayout.LAYOUT_ROWS} 行で固定です。幅は画面を ${CardLayout.COLUMNS} 等分した列の単位、高さは行の単位で変わります。") {
        LayoutEditor(
            rows = rows,
            unused = unused,
            onChange = { onChange(display.copy(cardLayout = CardLayout.toSlots(it))) },
            onAdd = { next, card -> onChange(card.show(display, true).copy(cardLayout = CardLayout.toSlots(next))) },
            onRemove = { next, card -> onChange(card.show(display, false).copy(cardLayout = CardLayout.toSlots(next))) },
            onRejected = { card ->
                blocked = "ここには入りません" to
                    "「${card.label}」は、行き先の行のカードをいちばん狭い幅まで縮めても入りません（最小の幅 ${card.min} 列）。" +
                    "ほかの行を選ぶか、先に行き先の行のカードを動かしてください。"
            },
        )
    }

    Field {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton("自動の並べ方に戻す", {
                val message = CardLayout.autoMessage(display, area)
                if (message != null) blocked = "自動の並べ方に戻せません" to message else onChange(display.copy(cardLayout = emptyList()))
            }, enabled = custom)
            StatusText(if (custom) "自分で決めた配置です" else "いまは自動で並べています（幅や場所を動かすと、自分で決めた配置になります）")
        }
    }

    Notice("カードの間の壁を動かすと、隣のカードとの間で幅をやり取りします。行の右端の壁を左へ動かすと右に余白ができ、余白はそのまま保存されます（ダッシュボードでも空いたままになります）。")
    Notice("カードの下の壁を下へ動かすと、カードが下の行まで伸びます。下の行のカードはその列を避けて右へずれるので、下の行に空きがあるときだけ伸ばせます。自動の並べ方では、どのカードも 1 行の高さです。")
    Notice("カードを長押しすると持ち上がります。そのまま動かして、入れたい行の入れたい位置で離してください。行き先の行に空きが足りなければ、その行のカードを最小の幅まで縮めて入れます。それでも入らない行では、差し込む位置の線が赤くなり、離しても元に戻ります。")
    Notice("カードを枠の外まで動かして離すと、そのカードをダッシュボードから外します（非表示になり、下の「使っていないカード」に移ります）。「使っていないカード」を長押しして枠の中へ動かすと、そのカードを足せます。")
    Notice("カードは、それ以上狭めると中身が崩れる幅（最小の幅）より狭くはできません。カードを表示するとき、空きが足りなければほかのカードを最小の幅まで縮めて入れます。それでも入らないときは、そのカードは表示できません（理由をお知らせします）。")
    Notice("縦向きの画面では、この配置の順番のまま 2 列に並べます（幅と高さは使いません）。")

    blocked?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { blocked = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun LayoutEditor(
    rows: Rows,
    unused: List<CardLayout.Card>,
    onChange: (Rows) -> Unit,
    onAdd: (Rows, CardLayout.Card) -> Unit,
    onRemove: (Rows, CardLayout.Card) -> Unit,
    onRejected: (CardLayout.Card) -> Unit,
) {
    val accent = LocalAccent.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val count = maxOf(CardLayout.LAYOUT_ROWS, rows.size)
    val latestRows by rememberUpdatedState(rows)
    val latestUnused by rememberUpdatedState(unused)
    val latestChange by rememberUpdatedState(onChange)
    val latestAdd by rememberUpdatedState(onAdd)
    val latestRemove by rememberUpdatedState(onRemove)
    val latestRejected by rememberUpdatedState(onRejected)
    /** 幅か高さを変えているカード（行と、何枚目か）。 */
    var resizing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var moving by remember { mutableStateOf<Moving?>(null) }
    var drop by remember { mutableStateOf<Drop?>(null) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        val column = width / CardLayout.COLUMNS
        val gridHeight = ROW_HEIGHT * count + ROW_GAP * (count - 1)
        val perLine = maxOf(1, ((width + CHIP_GAP) / (CHIP_WIDTH + CHIP_GAP)).toInt())
        val lines = maxOf(1, (unused.size + perLine - 1) / perLine)
        val trayTop = gridHeight + TRAY_GAP + TRAY_LABEL
        val totalHeight = trayTop + CHIP_HEIGHT * lines + CHIP_GAP * (lines - 1)

        val columnPx = with(density) { column.toPx() }
        val widthPx = with(density) { width.toPx() }
        val pitchPx = with(density) { (ROW_HEIGHT + ROW_GAP).toPx() }
        val rowPx = with(density) { ROW_HEIGHT.toPx() }
        val gridPx = with(density) { gridHeight.toPx() }
        val trayTopPx = with(density) { trayTop.toPx() }
        val chipPitchX = with(density) { (CHIP_WIDTH + CHIP_GAP).toPx() }
        val chipPitchY = with(density) { (CHIP_HEIGHT + CHIP_GAP).toPx() }
        val chipW = with(density) { CHIP_WIDTH.toPx() }
        val chipH = with(density) { CHIP_HEIGHT.toPx() }
        val inset = with(density) { 6.dp.toPx() }
        val guide = Wd.BorderSoft

        /** カードの縮図の上下（px）。縦に伸ばしたカードは下の行の分まで。 */
        fun top(p: CardLayout.Placed) = p.row * pitchPx + inset
        fun bottom(p: CardLayout.Placed) = (p.row + p.height - 1) * pitchPx + rowPx - inset

        /** 指の位置から、離したときにどうなるかを決める（動かすカードを抜いた並びでの位置）。 */
        fun dropAt(m: Moving): Drop {
            val current = latestRows
            val outside = m.at.y < 0 || m.at.y > gridPx || m.at.x < 0 || m.at.x > widthPx
            if (outside) return Drop(-1, 0, 0, fits = !m.fromTray, remove = !m.fromTray)
            val base = if (m.fromTray) current else CardLayout.remove(current, m.row, m.index)
            val row = (m.at.y / pitchPx).toInt().coerceIn(0, count - 1)
            val placed = (CardLayout.positions(base, Int.MAX_VALUE) ?: CardLayout.positions(CardLayout.flat(base), Int.MAX_VALUE)!!)
                .filter { it.row == row }
            // 指がカードの中心より右にあるカードの数 = 差し込む位置
            val before = placed.filter { (it.col + it.span / 2f) * columnPx < m.at.x }
            val index = before.size
            val col = before.lastOrNull()?.let { it.col + it.span } ?: 0
            val next = if (m.fromTray) CardLayout.insert(current, m.card, row, index)
            else CardLayout.move(current, m.row, m.index, row, index)
            return Drop(row, index, col, next != null)
        }

        // 掴む位置は編集欄全体の座標で判定する（動いていくカードやつまみに付けると、その座標系でずれが返ってくる）
        val gestures = Modifier.pointerInput(columnPx, count, perLine) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val current = latestRows
                val pos = down.position

                // 使っていないカードの置き場: 長押ししてから枠の中へ動かすと足す
                if (pos.y >= trayTopPx) {
                    val i = ((pos.y - trayTopPx) / chipPitchY).toInt() * perLine + (pos.x / chipPitchX).toInt()
                    val inChip = (pos.x % chipPitchX) <= chipW && ((pos.y - trayTopPx) % chipPitchY) <= chipH
                    val card = latestUnused.getOrNull(i)?.takeIf { inChip && pos.x < perLine * chipPitchX } ?: return@awaitEachGesture
                    val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val left = (i % perLine) * chipPitchX
                    val chipTop = trayTopPx + (i / perLine) * chipPitchY
                    var m = Moving(card, -1, -1, long.position, Offset(long.position.x - left, long.position.y - chipTop))
                    moving = m
                    drop = dropAt(m)
                    val finished = drag(long.id) { change ->
                        change.consume()
                        m = m.copy(at = change.position)
                        moving = m
                        drop = dropAt(m)
                    }
                    val target = drop
                    moving = null
                    drop = null
                    if (!finished || target == null || target.row < 0) return@awaitEachGesture
                    val next = CardLayout.insert(latestRows, card, target.row, target.index)
                    if (next == null) latestRejected(card) else latestAdd(next, card)
                    return@awaitEachGesture
                }

                if (pos.y > gridPx) return@awaitEachGesture
                val placed = CardLayout.positions(current, Int.MAX_VALUE) ?: CardLayout.positions(CardLayout.flat(current), Int.MAX_VALUE)!!
                fun indexOf(p: CardLayout.Placed) = current[p.row].indexOfFirst { it.card == p.card }

                // 右の壁のつまみ（横に動かしたら幅を変える）と、下の壁のつまみ（縦に動かしたら高さを変える）
                val grabPx = GRAB.toPx()
                val grabBottomPx = GRAB_BOTTOM.toPx()
                val right = placed.filter { pos.y in top(it)..bottom(it) && abs((it.col + it.span) * columnPx - pos.x) <= grabPx }
                    .minByOrNull { abs((it.col + it.span) * columnPx - pos.x) }
                val below = placed.filter { pos.x in it.col * columnPx..(it.col + it.span) * columnPx && abs(bottom(it) - pos.y) <= grabBottomPx }
                    .minByOrNull { abs(bottom(it) - pos.y) }
                val horizontal = right != null &&
                    (below == null || abs((right.col + right.span) * columnPx - pos.x) <= abs(bottom(below) - pos.y))
                if (horizontal && right != null) {
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                    val r = right.row
                    val index = indexOf(right)
                    resizing = r to index
                    var moved = start.position.x - down.position.x
                    fun apply() {
                        val next = CardLayout.resize(current, r, index, (moved / columnPx).roundToInt())
                        if (next != latestRows) latestChange(next)
                    }
                    apply()
                    horizontalDrag(start.id) { change ->
                        moved += change.positionChange().x
                        change.consume()
                        apply()
                    }
                    resizing = null
                    return@awaitEachGesture
                }
                if (below != null) {
                    val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                    val r = below.row
                    val index = indexOf(below)
                    resizing = r to index
                    var moved = start.position.y - down.position.y
                    fun apply() {
                        val next = CardLayout.setHeight(current, r, index, below.height + (moved / pitchPx).roundToInt())
                        if (next != latestRows) latestChange(next)
                    }
                    apply()
                    verticalDrag(start.id) { change ->
                        moved += change.positionChange().y
                        change.consume()
                        apply()
                    }
                    resizing = null
                    return@awaitEachGesture
                }

                // カード: 長押ししてから動かすと、別の行・同じ行の別の位置へ。枠の外で離すと外す
                val hit = placed.firstOrNull { pos.x in it.col * columnPx..(it.col + it.span) * columnPx && pos.y in top(it)..bottom(it) }
                    ?: return@awaitEachGesture
                val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                var m = Moving(hit.card, hit.row, indexOf(hit), long.position, Offset(long.position.x - hit.col * columnPx, long.position.y - top(hit)))
                moving = m
                drop = dropAt(m)
                val finished = drag(long.id) { change ->
                    change.consume()
                    m = m.copy(at = change.position)
                    moving = m
                    drop = dropAt(m)
                }
                val target = drop
                moving = null
                drop = null
                if (!finished || target == null) return@awaitEachGesture
                if (target.remove) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    latestRemove(CardLayout.remove(latestRows, m.row, m.index), m.card)
                    return@awaitEachGesture
                }
                if (target.row == m.row && target.index == m.index) return@awaitEachGesture
                val next = CardLayout.move(latestRows, m.row, m.index, target.row, target.index)
                if (next == null) latestRejected(m.card) else latestChange(next)
            }
        }

        Box(Modifier.fillMaxWidth().height(totalHeight).then(gestures)) {
            val m = moving
            // 動かしている最中は、元の場所からそのカードを抜いて描く（差し込む位置の線と揃えるため）
            val shown = if (m != null && !m.fromTray) CardLayout.remove(rows, m.row, m.index) else rows
            val placed = CardLayout.positions(shown, Int.MAX_VALUE) ?: CardLayout.positions(CardLayout.flat(shown), Int.MAX_VALUE)!!
            for (r in 0 until count) {
                val target = drop?.takeIf { it.row == r }
                Box(
                    Modifier.offset(y = (ROW_HEIGHT + ROW_GAP) * r).fillMaxWidth().height(ROW_HEIGHT)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (target != null) accent.copy(alpha = 0.06f) else Wd.Surface.copy(alpha = 0.6f))
                        .border(1.dp, if (target != null) accent.copy(alpha = 0.5f) else Wd.BorderSoft, RoundedCornerShape(10.dp))
                        .drawBehind {
                            for (i in 1 until CardLayout.COLUMNS) {
                                val x = i * columnPx
                                drawLine(guide, Offset(x, size.height * 0.35f), Offset(x, size.height * 0.65f), strokeWidth = 1f)
                            }
                        },
                ) {
                    // いちばん広い空き（上から伸びてきたカードの下も使っている扱い）に、空いている列の数を出す
                    val used = BooleanArray(CardLayout.COLUMNS)
                    placed.filter { r in it.row until it.row + it.height }.forEach { p -> for (c in p.col until p.col + p.span) used[c] = true }
                    val gap = widestGap(used)
                    if (gap != null && gap.second >= 2 && target == null) {
                        Text(
                            if (used.none { it }) "空いている行" else "余白 ${gap.second} 列",
                            color = Wd.Text3,
                            fontSize = 11.tu,
                            maxLines = 1,
                            modifier = Modifier.align(Alignment.CenterStart).offset(x = column * gap.first + 10.dp),
                        )
                    }
                    // 離したら入る位置の線（入らない行では赤）
                    if (target != null) {
                        Box(
                            Modifier.align(Alignment.CenterStart).offset(x = column * target.col - 2.dp)
                                .size(4.dp, ROW_HEIGHT - 16.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (target.fits) accent else Wd.Red),
                        )
                    }
                }
            }
            placed.forEach { p ->
                val index = shown[p.row].indexOfFirst { it.card == p.card }
                LayoutCard(
                    p.card, p.span, p.height,
                    Modifier.offset(x = column * p.col + CARD_GAP / 2, y = (ROW_HEIGHT + ROW_GAP) * p.row + 6.dp)
                        .size(column * p.span - CARD_GAP, cardHeight(p.height)),
                    highlight = m == null && resizing == p.row to index,
                    grip = m == null,
                )
            }

            // 使っていないカードの置き場
            Text(
                if (unused.isEmpty()) "使っていないカードはありません" else "使っていないカード（長押しして上の枠へ動かすと足せます）",
                color = if (drop?.remove == true) Wd.Red else Wd.Text2,
                fontSize = 12.tu,
                modifier = Modifier.offset(y = gridHeight + TRAY_GAP),
            )
            unused.forEachIndexed { i, card ->
                if (m != null && m.fromTray && m.card == card) return@forEachIndexed
                TrayChip(
                    card,
                    Modifier.offset(x = (CHIP_WIDTH + CHIP_GAP) * (i % perLine), y = trayTop + (CHIP_HEIGHT + CHIP_GAP) * (i / perLine))
                        .size(CHIP_WIDTH, CHIP_HEIGHT),
                )
            }

            // 指に付いてくるカード（枠の外では「離すと外す」）
            if (m != null) {
                val tile = if (m.fromTray) CardLayout.Tile(m.card, m.card.span) else rows[m.row][m.index]
                val removing = drop?.remove == true
                LayoutCard(
                    tile.card, tile.span, if (m.fromTray || removing) 1 else tile.height,
                    Modifier
                        .offset { IntOffset((m.at.x - m.grab.x).roundToInt(), (m.at.y - m.grab.y).roundToInt()) }
                        .size(column * tile.span - CARD_GAP, cardHeight(if (m.fromTray || removing) 1 else tile.height))
                        .alpha(0.92f),
                    highlight = true,
                    grip = false,
                    removing = removing,
                )
            }
        }
    }
}

private fun cardHeight(height: Int): Dp = ROW_HEIGHT * height + ROW_GAP * (height - 1) - 12.dp

/** いちばん広い空き（始まりの列と幅）。空きが無ければ null。 */
private fun widestGap(used: BooleanArray): Pair<Int, Int>? {
    var best: Pair<Int, Int>? = null
    var start = -1
    for (c in 0..used.size) {
        val free = c < used.size && !used[c]
        if (free && start < 0) start = c
        if (!free && start >= 0) {
            if (best == null || c - start > best.second) best = start to c - start
            start = -1
        }
    }
    return best
}

@Composable
private fun LayoutCard(
    card: CardLayout.Card,
    span: Int,
    height: Int,
    modifier: Modifier,
    highlight: Boolean,
    grip: Boolean,
    removing: Boolean = false,
) {
    val accent = LocalAccent.current
    val atMin = span <= card.min
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (removing) Wd.Red.copy(alpha = 0.18f) else Wd.Surface2)
            .border(1.dp, if (removing) Wd.Red else if (highlight) accent else Wd.Border, RoundedCornerShape(8.dp))
            .padding(start = 9.dp, end = 14.dp, top = 7.dp, bottom = 6.dp),
    ) {
        Column {
            Text(card.label, fontSize = 12.5f.tu, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    removing -> "離すと外します"
                    height > 1 -> "$span 列 × $height 行" + if (atMin) "（最小）" else ""
                    atMin -> "$span 列（最小）"
                    else -> "$span 列"
                },
                color = if (removing) Wd.Red else if (atMin) Wd.Amber else Wd.Text3,
                fontSize = 11.tu,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (grip) {
            // 右の壁のつまみ（幅）
            Box(
                Modifier.align(Alignment.CenterEnd).offset(x = 9.dp)
                    .size(4.dp, 28.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (highlight) accent else Wd.Text3),
            )
            // 下の壁のつまみ（高さ）
            Box(
                Modifier.align(Alignment.BottomCenter).offset(y = 4.dp)
                    .size(28.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (highlight) accent else Wd.Text3),
            )
        }
    }
}

/** 「使っていないカード」の 1 枚。 */
@Composable
private fun TrayChip(card: CardLayout.Card, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Wd.Surface.copy(alpha = 0.6f))
            .border(1.dp, Wd.BorderSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Column {
            Text(card.label, color = Wd.Text2, fontSize = 12.tu, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${card.span} 列", color = Wd.Text3, fontSize = 10.5f.tu, maxLines = 1)
        }
    }
}
