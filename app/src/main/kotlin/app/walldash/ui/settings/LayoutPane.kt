package app.walldash.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
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
import androidx.compose.ui.unit.dp
import app.walldash.data.CardLayout
import app.walldash.data.DisplayConfig
import app.walldash.ui.theme.LocalAccent
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.tu
import kotlin.math.abs
import kotlin.math.roundToInt

private typealias Rows = List<List<Pair<CardLayout.Card, Int>>>

private val ROW_HEIGHT = 74.dp
private val ROW_GAP = 8.dp
private val CARD_GAP = 6.dp
/** 壁のつまみを掴める範囲（壁から左右それぞれ）。 */
private val GRAB = 22.dp

/** 動かしている最中のカード。[at] は指の位置、[grab] はカードの左上から見た掴んだ位置（どちらも px）。 */
private data class Moving(val row: Int, val index: Int, val at: Offset, val grab: Offset)

/** 離したら入る場所。[fits] が false なら、行きの行のカードを最小まで縮めても入らない。 */
private data class Drop(val row: Int, val index: Int, val fits: Boolean)

/**
 * 「カードの配置」。横向きの画面の 4 行を縮めて描く。
 * - カードの右の壁（つまみ）を左右にドラッグすると幅が変わる（[CardLayout.resize]）。24 列単位で吸い付き、最小の幅より狭くならない。
 * - カードを長押しして動かすと、同じ行の中の順番や、別の行へ入れ替えられる（[CardLayout.move]）。
 * 行の右端や、行そのものが空いても、そのまま保存できる（ダッシュボードでも空きになる。カードの無い行は詰める）。
 */
@Composable
fun LayoutPane(display: DisplayConfig, onChange: (DisplayConfig) -> Unit) {
    val context = LocalContext.current
    val area = CardLayout.area(context)
    val rows = CardLayout.editorRows(display, area)
    val custom = display.cardLayout.isNotEmpty()
    var blocked by remember { mutableStateOf<Pair<String, String>?>(null) }

    PaneTitle(
        "カードの配置",
        "横向きのダッシュボードを縮めて描いています。カードの右の壁（つまみ）を左右にドラッグすると幅が、カードを長押ししてから動かすと置き場所（ほかの行・同じ行の順番）が変わります。",
    )

    Field(hint = "行は ${CardLayout.LAYOUT_ROWS} 行で固定です。幅は画面を ${CardLayout.COLUMNS} 等分した列の単位で変わります。") {
        LayoutEditor(
            rows = rows,
            onChange = { onChange(display.copy(cardLayout = CardLayout.toSlots(it))) },
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
    Notice("カードを長押しすると持ち上がります。そのまま動かして、入れたい行の入れたい位置で離してください。行き先の行に空きが足りなければ、その行のカードを最小の幅まで縮めて入れます。それでも入らない行では、差し込む位置の線が赤くなり、離しても元に戻ります。")
    Notice("カードは、それ以上狭めると中身が崩れる幅（最小の幅）より狭くはできません。カードを表示するとき、空きが足りなければほかのカードを最小の幅まで縮めて入れます。それでも入らないときは、そのカードは表示できません（理由をお知らせします）。")
    Notice("縦向きの画面では、この配置の順番のまま 2 列に並べます（幅は使いません）。")

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
private fun LayoutEditor(rows: Rows, onChange: (Rows) -> Unit, onRejected: (CardLayout.Card) -> Unit) {
    val accent = LocalAccent.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val count = maxOf(CardLayout.LAYOUT_ROWS, rows.size)
    val latestRows by rememberUpdatedState(rows)
    val latestChange by rememberUpdatedState(onChange)
    val latestRejected by rememberUpdatedState(onRejected)
    /** 幅を変えている壁（行と、何枚目のカードの右の壁か）。 */
    var resizing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var moving by remember { mutableStateOf<Moving?>(null) }
    var drop by remember { mutableStateOf<Drop?>(null) }

    BoxWithConstraints(Modifier.fillMaxWidth().height(ROW_HEIGHT * count + ROW_GAP * (count - 1))) {
        val column = maxWidth / CardLayout.COLUMNS
        val columnPx = with(density) { column.toPx() }
        val pitchPx = with(density) { (ROW_HEIGHT + ROW_GAP).toPx() }
        val grabPx = with(density) { GRAB.toPx() }
        val guide = Wd.BorderSoft

        /** 指の位置から、離したときに入る行と位置を決める（動かすカードを抜いた並びでの位置）。 */
        fun dropAt(m: Moving): Drop {
            val current = latestRows
            val row = (m.at.y / pitchPx).toInt().coerceIn(0, count - 1)
            val without = current.getOrElse(row) { emptyList() }.filterIndexed { i, _ -> !(row == m.row && i == m.index) }
            // 指がカードの中心より右にあるカードの数 = 差し込む位置
            var edge = 0
            var index = 0
            without.forEach { (_, span) ->
                if ((edge + span / 2f) * columnPx < m.at.x) index++
                edge += span
            }
            return Drop(row, index, CardLayout.move(current, m.row, m.index, row, index) != null)
        }

        // 掴む位置は編集欄全体の座標で判定する（動いていくカードやつまみに付けると、その座標系でずれが返ってくる）
        val gestures = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val current = latestRows
                val r = (down.position.y / pitchPx).toInt()
                if (r !in 0 until count || down.position.y - r * pitchPx > ROW_HEIGHT.toPx()) return@awaitEachGesture
                val row = current.getOrElse(r) { emptyList() }
                val edges = row.runningFold(0) { at, (_, span) -> at + span }.drop(1)

                // 壁のつまみ: 横に動かしたら幅を変える
                val wall = edges.indices.filter { abs(edges[it] * columnPx - down.position.x) <= grabPx }
                    .minByOrNull { abs(edges[it] * columnPx - down.position.x) }
                if (wall != null) {
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                    resizing = r to wall
                    var moved = start.position.x - down.position.x
                    fun apply() {
                        val next = CardLayout.resize(row, wall, (moved / columnPx).roundToInt())
                        if (next != latestRows.getOrNull(r)) latestChange(latestRows.toMutableList().also { list ->
                            while (list.size <= r) list += emptyList<Pair<CardLayout.Card, Int>>()
                            list[r] = next
                        })
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

                // カード: 長押ししてから動かすと、別の行・同じ行の別の位置へ
                val index = edges.indexOfFirst { down.position.x < it * columnPx }
                if (index < 0) return@awaitEachGesture
                val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val left = (edges[index] - row[index].second) * columnPx
                var m = Moving(r, index, long.position, Offset(long.position.x - left, long.position.y - r * pitchPx))
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
                if (target.row == m.row && target.index == m.index) return@awaitEachGesture
                val next = CardLayout.move(latestRows, m.row, m.index, target.row, target.index)
                if (next == null) latestRejected(row[m.index].first) else latestChange(next)
            }
        }

        Box(Modifier.fillMaxSize().then(gestures)) {
            val m = moving
            for (r in 0 until count) {
                // 動かしている最中は、元の場所からそのカードを抜いて描く（差し込む位置の線と揃えるため）
                val row = rows.getOrElse(r) { emptyList() }
                val shown = if (m != null && m.row == r) row.filterIndexed { i, _ -> i != m.index } else row
                val top = (ROW_HEIGHT + ROW_GAP) * r
                val target = drop?.takeIf { it.row == r }
                Box(
                    Modifier.offset(y = top).fillMaxWidth().height(ROW_HEIGHT)
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
                    var at = 0
                    shown.forEachIndexed { i, (card, span) ->
                        LayoutCard(
                            card, span,
                            Modifier.offset(x = column * at + CARD_GAP / 2, y = 6.dp).size(column * span - CARD_GAP, ROW_HEIGHT - 12.dp),
                            highlight = resizing == r to i,
                            grip = m == null,
                        )
                        at += span
                    }
                    val free = CardLayout.COLUMNS - at
                    if (free >= 2 && target == null) {
                        Text(
                            if (shown.isEmpty()) "空いている行" else "余白 $free 列",
                            color = Wd.Text3,
                            fontSize = 11.tu,
                            maxLines = 1,
                            modifier = Modifier.align(Alignment.CenterStart).offset(x = column * at + 10.dp),
                        )
                    }
                    // 離したら入る位置の線（入らない行では赤）
                    if (target != null) {
                        val x = column * shown.take(target.index).sumOf { it.second }
                        Box(
                            Modifier.align(Alignment.CenterStart).offset(x = x - 2.dp)
                                .size(4.dp, ROW_HEIGHT - 16.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (target.fits) accent else Wd.Red),
                        )
                    }
                }
            }
            // 指に付いてくるカード
            if (m != null) {
                val (card, span) = rows[m.row][m.index]
                LayoutCard(
                    card, span,
                    Modifier
                        .offset { IntOffset((m.at.x - m.grab.x).roundToInt(), (m.at.y - m.grab.y).roundToInt() + 6.dp.roundToPx()) }
                        .size(column * span - CARD_GAP, ROW_HEIGHT - 12.dp)
                        .alpha(0.92f),
                    highlight = true,
                    grip = false,
                )
            }
        }
    }
}

@Composable
private fun LayoutCard(card: CardLayout.Card, span: Int, modifier: Modifier, highlight: Boolean, grip: Boolean) {
    val accent = LocalAccent.current
    val atMin = span <= card.min
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Wd.Surface2)
            .border(1.dp, if (highlight) accent else Wd.Border, RoundedCornerShape(8.dp))
            .padding(start = 9.dp, end = 14.dp, top = 7.dp, bottom = 6.dp),
    ) {
        Column {
            Text(card.label, fontSize = 12.5f.tu, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (atMin) "$span 列（最小）" else "$span 列",
                color = if (atMin) Wd.Amber else Wd.Text3,
                fontSize = 11.tu,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 右の壁のつまみ
        if (grip) {
            Box(
                Modifier.align(Alignment.CenterEnd).offset(x = 9.dp)
                    .size(4.dp, 28.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (highlight) accent else Wd.Text3),
            )
        }
    }
}
