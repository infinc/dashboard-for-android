package app.walldash.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.tu

private val CardShape = RoundedCornerShape(18.dp)

/** カード 1 枚の枠。見出し（左）と注記（右上の角）、残りの高さが本文。 */
@Composable
fun WdCard(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    titleColor: Color = Wd.Text3,
    borderColor: Color = Wd.Border,
    headerEnd: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 背景画像の上では面を透かす（枠線と文字はそのまま）
    val alpha = Wd.cardAlpha
    Column(
        modifier
            .clip(CardShape)
            .background(Brush.verticalGradient(listOf(Wd.Surface2.copy(alpha = alpha), Wd.Surface.copy(alpha = alpha))))
            .border(1.dp, borderColor, CardShape)
            .drawWithContent {
                drawContent()
                val inset = 12.dp.toPx()
                drawLine(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Wd.Ink.copy(alpha = if (Wd.palette.light) 0.05f else 0.13f), Color.Transparent),
                        startX = inset,
                        endX = size.width - inset,
                    ),
                    Offset(inset, 0.5f),
                    Offset(size.width - inset, 0.5f),
                    strokeWidth = 1f,
                )
            }
            .padding(horizontal = 15.dp, vertical = 13.dp),
    ) {
        CardHeader(
            title = {
                Text(
                    title,
                    color = titleColor,
                    fontSize = 12.5f.tu,
                    letterSpacing = 0.14.em,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            end = {
                when {
                    headerEnd != null -> headerEnd()
                    !note.isNullOrEmpty() -> Text(note, color = Wd.Text3, fontSize = 12.tu, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
    }
}

/**
 * 見出しの行。見出しは左端、注記は必ず右端（カードの右上の角）に置く。
 *
 * Row と weight の組み合わせだと、見出しと注記の長さによって幅の配分が偏り、注記が右端に届かなかったり
 * 見出しが途中で省略されたりする。ここでは両方の自然な幅を先に測り、
 * 収まるならそのまま左右の端へ、収まらないときだけ見出しに幅の半分（見出しが短ければその分）を残して注記を縮める。
 */
@Composable
private fun CardHeader(title: @Composable () -> Unit, end: @Composable () -> Unit) {
    Layout(
        content = {
            Box { title() }
            Box { end() }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val (titleM, endM) = measurables
        val titleFull = titleM.maxIntrinsicWidth(constraints.maxHeight)
        val endFull = endM.maxIntrinsicWidth(constraints.maxHeight)
        val room = (width - gap).coerceAtLeast(0)
        val titleW: Int
        val endW: Int
        if (titleFull + endFull <= room) {
            titleW = titleFull
            endW = endFull
        } else {
            titleW = minOf(titleFull, maxOf(room / 2, room - endFull))
            endW = room - titleW
        }
        val t = titleM.measure(Constraints(maxWidth = titleW))
        val e = endM.measure(Constraints(maxWidth = endW.coerceAtLeast(0)))
        val height = maxOf(t.height, e.height)
        layout(width, height) {
            t.placeRelative(0, (height - t.height) / 2)
            e.placeRelative(width - e.width, (height - e.height) / 2)
        }
    }
}

/** 本文が無いときの案内文。 */
@Composable
fun EmptyText(text: String, color: Color = Wd.Text3) {
    Text(text, color = color, fontSize = 14.tu, lineHeight = 1.8.em)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Wd.BorderSoft))
}

/** Web 版と同じ SVG パスから作るアイコン（24 x 24）。 */
object WdIcons {
    val Refresh = icon("M12 6V3L8 7l4 4V8a4.5 4.5 0 1 1-4.5 4.5H5.5A6.5 6.5 0 1 0 12 6")
    val Gear = icon(
        "M19.4 13a7.8 7.8 0 0 0 0-2l2-1.6a.5.5 0 0 0 .1-.6l-1.9-3.3a.5.5 0 0 0-.6-.2l-2.4 1a7.6 7.6 0 0 0-1.7-1" +
            "L14.5 2.5a.5.5 0 0 0-.5-.4h-3.8a.5.5 0 0 0-.5.4L9.3 5.3a7.6 7.6 0 0 0-1.7 1l-2.4-1a.5.5 0 0 0-.6.2" +
            "L2.7 8.8a.5.5 0 0 0 .1.6L4.8 11a7.8 7.8 0 0 0 0 2l-2 1.6a.5.5 0 0 0-.1.6l1.9 3.3a.5.5 0 0 0 .6.2" +
            "l2.4-1a7.6 7.6 0 0 0 1.7 1l.4 2.8a.5.5 0 0 0 .5.4h3.8a.5.5 0 0 0 .5-.4l.4-2.8a7.6 7.6 0 0 0 1.7-1" +
            "l2.4 1a.5.5 0 0 0 .6-.2l1.9-3.3a.5.5 0 0 0-.1-.6zM12 15.5A3.5 3.5 0 1 1 15.5 12 3.5 3.5 0 0 1 12 15.5",
    )
    val Browse: ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes("M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z"),
        stroke = SolidColor(Color.White),
        strokeLineWidth = 1.7f,
        strokeLineCap = StrokeCap.Round,
    ).addPath(
        pathData = addPathNodes("M16.6 7.4 10.4 10.4 7.4 16.6 13.6 13.6z"),
        fill = SolidColor(Color.White),
    ).build()
    // 各アイコンは Android の PathParser が「z の後の相対 m」の基準点を取り違えるため、
    // 2 つ目以降の subpath は必ず絶対座標の M で始めること（相対 m だと崩れて描画される）。
    val Previous = icon("M7 6h2v12H7zM17 6v12l-8-6z")
    val Pause = icon("M7.5 6h3v12h-3zM13.5 6h3v12h-3z")
    val Play = icon("M8 5l11 7-11 7z")
    val Next = icon("M15 6h2v12h-2zM7 6l8 6-8 6z")
    val Back = icon("M15.5 5l-7 7 7 7-1.4 1.4L5.7 12l8.4-8.4z")
    val Forward = icon("M8.5 5l7 7-7 7 1.4 1.4 8.4-8.4-8.4-8.4z")
    val More = icon("M12 7.5a1.8 1.8 0 1 0 0-3.6 1.8 1.8 0 0 0 0 3.6zM12 13.8a1.8 1.8 0 1 0 0-3.6 1.8 1.8 0 0 0 0 3.6zM12 20.1a1.8 1.8 0 1 0 0-3.6 1.8 1.8 0 0 0 0 3.6z")
    val Menu = icon("M4 6.5h16v1.8H4zM4 11.1h16v1.8H4zM4 15.7h16v1.8H4z")
    val Close = icon("M6.4 5 12 10.6 17.6 5 19 6.4 13.4 12l5.6 5.6-1.4 1.4-5.6-5.6L6.4 19 5 17.6 10.6 12 5 6.4z")

    private fun icon(d: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(d), fill = SolidColor(Color.White)).build()
}

/** 数字の桁を揃えた文字スタイル（tabular-nums）。 */
val Tabular: TextStyle
    @Composable @ReadOnlyComposable
    get() = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
