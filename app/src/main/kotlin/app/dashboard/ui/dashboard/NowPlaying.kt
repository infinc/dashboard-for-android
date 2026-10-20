package app.dashboard.ui.dashboard

import app.dashboard.i18n.L
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlin.math.max
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import app.dashboard.data.LyricsRepository
import app.dashboard.data.SpotifyLayout
import app.dashboard.data.SpotifyState
import app.dashboard.ui.theme.colorOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.MutableState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntSize
import app.dashboard.ui.common.Tabular
import app.dashboard.ui.common.WdIcons
import app.dashboard.ui.theme.tu
import kotlinx.coroutines.delay

/**
 * Spotify の再生中の曲を画面いっぱいに出す。
 * 背景はジャケットに使われている色のグラデーション（[coverGradient]。動かない単色・漂う光・尖った光・選んだ 1 色の漂う光を設定の [background] で選ぶ）。
 * 部品（ジャケット・歌詞・曲名とアーティスト名・操作ボタン・再生時間・右上の切り替え・戻る）の位置・大きさ・表示は [layout]（設定の `spotify.layout`）に従う。
 * 左上の「<」か端末の戻る操作で閉じる。右上の小さなボタンで「焼き付き防止」（[burnIn]。この全画面の間だけ）と「画面を暗くしない」（[keepAwake]）を切り替える。
 * 「自動で消す」にした部品は、[SpotifyLayout.hideSeconds] 触られなければ溶けるように消え、どこかに触れると戻る。
 * ジャケットを押すとジャケットが歌詞を出すときの位置へ動き、歌詞（[loadLyrics]、LRCLIB）を出す。もう一度押すと戻る。
 *
 * [edit] があれば、設定の「全画面モードを編集する」の見本として描く（戻る操作を奪わない。部品ごとに枠・右上の＋・左下の歯車を付け、
 * 部品のドラッグで動かし、＋のドラッグで大きさを変える。出さない部品も薄く出す）。[lyricsOpen] は歌詞の開閉（編集画面が持つ）、
 * [shownOverride] は自動で消える部品の状態（null なら触られていない時間で決める）。
 */
@Composable
fun NowPlayingScreen(
    sp: SpotifyState?,
    album: Pair<String, ImageBitmap>?,
    now: Long,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    burnIn: Boolean,
    onBurnIn: (Boolean) -> Unit,
    onControl: (String) -> Unit,
    background: String,
    monoColor: String,
    layout: SpotifyLayout,
    loadLyrics: suspend (SpotifyState) -> Result<LyricsRepository.Lyrics?>,
    onBack: () -> Unit,
    edit: SpotifyEditHooks? = null,
    lyricsOpen: MutableState<Boolean>? = null,
    shownOverride: Boolean? = null,
) {
    val lo = layout
    BackHandler(enabled = edit == null, onBack = onBack)
    val lyricsAllowed = lo.lyricsMode != "off"
    val ownLyrics = remember { mutableStateOf(lo.lyricsMode == "open") }
    val lyricsState = lyricsOpen ?: ownLyrics
    val lyricsShown = lyricsAllowed && lyricsState.value
    val shift by animateFloatAsState(if (lyricsShown) 1f else 0f, tween(480, easing = FastOutSlowInEasing), label = "lyricsShift")
    // 触れるたびに数を進め、そこから hideSeconds 何もなければ隠す
    var touches by remember { mutableIntStateOf(0) }
    var idleShown by remember { mutableStateOf(true) }
    LaunchedEffect(touches, lo.hideSeconds, shownOverride) {
        idleShown = true
        if (shownOverride != null) return@LaunchedEffect
        delay(lo.hideSeconds * 1000L)
        idleShown = false
    }
    val shown = shownOverride ?: idleShown
    val melt by animateFloatAsState(
        if (shown) 1f else 0f,
        if (shown) tween(280, easing = FastOutSlowInEasing) else tween(1100, easing = LinearOutSlowInEasing),
        label = "melt",
    )
    /** 「自動で消す」にした部品だけ消える。 */
    fun Modifier.fade(auto: Boolean) = if (auto) melt(melt) else this
    fun visible(auto: Boolean) = !auto || shown
    val toggleLyrics = { if (lyricsAllowed) lyricsState.value = !lyricsState.value }

    val cover = album?.takeIf { sp?.albumImageUrl != null && it.first == sp.albumImageUrl }
    // 背景はジャケットの色で動くグラデーション（CoverGradient.kt）
    val palette = remember(cover?.first) { coverPalette(cover?.second) }
    val mono = remember(monoColor) { colorOf(monoColor, Color(0xFF3E6FB8)) }
    val holder = remember { CoordsHolder() }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .coverGradient(palette, background, mono)
            // 画面のどこに触れても（ボタンの上でも）操作の表示を戻す。触れた操作はそのままボタンにも届く
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed && !it.previousPressed }) touches++
                    }
                }
            }
            // 下のダッシュボードに触れさせない。ジャケットを出していないときは、画面を押して歌詞を出し入れする
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (!lo.coverShow) toggleLyrics()
            }
            .padding(EDGE)
            .onGloballyPositioned { holder.container = it },
    ) {
        val w = maxWidth
        val h = maxHeight
        val area = IntSize(constraints.maxWidth, constraints.maxHeight)
        @Composable
        fun Part(part: SpotifyPart, show: Boolean, x: () -> Float, y: () -> Float, modifier: Modifier, content: @Composable () -> Unit) =
            EditablePart(part, show, edit, holder, area, x, y, modifier, content)

        val side = min(h * (lo.coverSize / 100f), w)
        Part(
            SpotifyPart.COVER, lo.coverShow,
            { lerp(lo.coverX, lo.coverLyricsX, shift) }, { lerp(lo.coverY, lo.coverLyricsY, shift) },
            Modifier.fade(lo.coverAutoHide),
        ) {
            val radius = RoundedCornerShape(lo.coverRadius.dp)
            Crossfade(
                cover,
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = lyricsAllowed && visible(lo.coverAutoHide),
                    onClickLabel = L("歌詞", "Lyrics"),
                    onClick = toggleLyrics,
                ),
                animationSpec = tween(500),
                label = "cover",
            ) { c ->
                Box(
                    Modifier.size(side)
                        .shadow(28.dp, radius)
                        .clip(radius)
                        .background(Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (c != null) Image(c.second, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }

        if (shift > 0.01f && sp != null) {
            Part(
                SpotifyPart.LYRICS, true, { lo.lyricsX / 100f }, { lo.lyricsY / 100f },
                Modifier.graphicsLayer {
                    alpha = shift
                    translationX = (1 - shift) * 48.dp.toPx()
                }.fade(lo.lyricsAutoHide),
            ) {
                LyricsPanel(
                    sp,
                    loadLyrics,
                    lo,
                    Modifier.size(w * (lo.lyricsWidth / 100f), (h * (lo.lyricsHeight / 100f) - 18.dp).coerceAtLeast(60.dp)),
                )
            }
        }

        // 曲名とアーティスト名。曲名は長ければ折り返し（省略はしない）、アーティスト名は 1 行に収める
        val hasArtist = sp?.artistName != null && sp.trackName != null
        Part(SpotifyPart.TITLE, lo.titleShow || (lo.artistShow && hasArtist), { lo.titleX / 100f }, { lo.titleY / 100f }, Modifier.fade(lo.titleAutoHide)) {
            val align = textAlign(lo.titleAlign)
            val blockWidth = w * (lo.titleWidth / 100f)
            val horizontal = when (lo.titleAlign) {
                "center" -> Alignment.CenterHorizontally
                "right" -> Alignment.End
                else -> Alignment.Start
            }
            Column(Modifier.width(blockWidth), horizontalAlignment = horizontal) {
                // 編集画面では、出さないほうも薄く出して選べるようにする
                val faint = Modifier.graphicsLayer { alpha = 0.35f }
                val title: @Composable () -> Unit = {
                    if (lo.titleShow || edit != null) Text(
                        sp?.trackName ?: L("再生中の曲はありません", "Nothing is playing"),
                        color = Color.White,
                        fontSize = lo.titleSize.tu,
                        fontWeight = FontWeight(lo.titleWeight),
                        lineHeight = 1.25.em,
                        textAlign = align,
                        modifier = if (lo.titleShow) Modifier else faint,
                    )
                }
                val artist: @Composable () -> Unit = {
                    if (lo.artistShow || edit != null) sp?.artistName?.takeIf { sp.trackName != null }?.let {
                        Text(
                            it,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = lo.artistSize.tu,
                            fontWeight = FontWeight(lo.artistWeight),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = align,
                            modifier = Modifier.padding(vertical = 3.dp).widthIn(max = blockWidth * (lo.artistWidth / 100f))
                                .then(if (lo.artistShow) Modifier else faint),
                        )
                    }
                }
                if (lo.artistFirst) { artist(); title() } else { title(); artist() }
            }
        }

        // 戻る: 「<」だけ（押せる範囲は指の大きさにする）
        Part(SpotifyPart.BACK, true, { lo.backX / 100f }, { lo.backY / 100f }, Modifier.fade(lo.backAutoHide)) {
            val k = lo.backSize / 100f
            Box(
                Modifier.size(52.dp * k)
                    .clip(RoundedCornerShape(26.dp * k))
                    .clickable(enabled = edit == null && visible(lo.backAutoHide), onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(WdIcons.Back, L("戻る", "Back"), tint = Color.White, modifier = Modifier.size(30.dp * k))
            }
        }

        // 焼き付き防止・画面を暗くしない（右上に小さく）。消えている間は押せない
        Part(SpotifyPart.TOGGLES, lo.togglesShow, { lo.togglesX / 100f }, { lo.togglesY / 100f }, Modifier.fade(lo.togglesAutoHide)) {
            val k = lo.togglesSize / 100f
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp * k)) {
                PillToggle(
                    burnIn,
                    if (burnIn) L("焼き付き防止：オン", "Burn-in guard: on") else L("焼き付き防止：オフ", "Burn-in guard: off"),
                    WdIcons.Layers,
                    enabled = visible(lo.togglesAutoHide),
                    onToggle = { onBurnIn(!burnIn) },
                    modifier = Modifier,
                    scale = k,
                    radius = lo.togglesRadius,
                )
                PillToggle(
                    keepAwake,
                    if (keepAwake) L("暗くしない：オン", "Keep awake: on") else L("暗くしない：オフ", "Keep awake: off"),
                    WdIcons.Sun,
                    enabled = visible(lo.togglesAutoHide),
                    onToggle = { onKeepAwake(!keepAwake) },
                    modifier = Modifier,
                    scale = k,
                    radius = lo.togglesRadius,
                )
            }
        }

        if (sp?.trackName != null) {
            Part(SpotifyPart.CONTROLS, lo.controlsShow, { lo.controlsX / 100f }, { lo.controlsY / 100f }, Modifier.fade(lo.controlsAutoHide)) {
                val scale = lo.controlsSize / 100f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val on = visible(lo.controlsAutoHide)
                    ControlButton(WdIcons.Previous, L("前の曲", "Previous"), on, scale) { onControl("previous") }
                    if (sp.playing) ControlButton(WdIcons.Pause, L("一時停止", "Pause"), on, scale) { onControl("pause") }
                    else ControlButton(WdIcons.Play, L("再生", "Play"), on, scale) { onControl("play") }
                    ControlButton(WdIcons.Next, L("次の曲", "Next"), on, scale) { onControl("next") }
                }
            }
            // 再生時間。既定では操作ボタンと同じ高さの枠の真ん中に、記号と同じくらいの大きさの数字で
            Part(SpotifyPart.PROGRESS, lo.progressShow, { lo.progressX / 100f }, { lo.progressY / 100f }, Modifier.fade(lo.progressAutoHide)) {
                Box(Modifier.height(max(CONTROL_HEIGHT.value, lo.progressSize * 2.2f).dp), contentAlignment = Alignment.Center) {
                    Text(
                        progress(sp, now),
                        color = Color.White,
                        fontSize = lo.progressSize.tu,
                        style = Tabular,
                        maxLines = 1,
                    )
                }
            }
        }
        if (edit != null) EditHandles(edit, holder, area)
    }
}

/** 全画面の部品。設定の編集画面で、枠・＋・歯車を付ける単位。 */
enum class SpotifyPart { COVER, LYRICS, TITLE, CONTROLS, PROGRESS, TOGGLES, BACK }

/**
 * 全画面の編集画面からの受け口。[onMove] / [onResize] はドラッグを始めてからの移動量（全画面の座標の px）と、
 * ドラッグを始めたときの部品の大きさ・部品を置ける範囲の大きさ（px）を渡す。始めた時点の値は [onDragStart] で編集画面が控える。
 */
class SpotifyEditHooks(
    val selected: SpotifyPart?,
    val onDragStart: (SpotifyPart) -> Unit,
    val onMove: (SpotifyPart, Offset, IntSize, IntSize) -> Unit,
    val onResize: (SpotifyPart, Offset, IntSize, IntSize) -> Unit,
    val onSettings: (SpotifyPart) -> Unit,
)

/** 全画面の入れ物の座標（ドラッグの量を、動いていく部品ではなく動かない入れ物の座標で測るため）と、編集中の部品の位置。 */
private class CoordsHolder {
    var container: LayoutCoordinates? = null
    /** 部品ごとの枠（入れ物の座標の px）。編集のときだけ使う。 */
    val bounds = mutableStateMapOf<SpotifyPart, Rect>()
    /** ドラッグを始めたときの部品の大きさ。 */
    var startSize = IntSize.Zero
}

/**
 * 部品 1 つ。[x]・[y] の位置に置き、[edit] があれば細い枠を描いて、部品そのもののドラッグで動かす（＋と歯車は [EditHandles]）。
 * 出さない部品（[show] が false）は、編集のときだけ薄く出す。
 */
@Composable
private fun EditablePart(
    part: SpotifyPart,
    show: Boolean,
    edit: SpotifyEditHooks?,
    holder: CoordsHolder,
    area: IntSize,
    x: () -> Float,
    y: () -> Float,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (!show && edit == null) return
    if (edit == null) {
        Box(Modifier.placeAt(x, y).then(modifier)) { content() }
        return
    }
    val hooks by rememberUpdatedState(edit)
    val latestArea by rememberUpdatedState(area)
    DisposableEffect(part) { onDispose { holder.bounds.remove(part) } }
    val selected = edit.selected == part
    val line = if (selected) EDIT_ACCENT else Color.White.copy(alpha = 0.55f)
    Box(
        Modifier.placeAt(x, y)
            .onGloballyPositioned { me ->
                val box = holder.container
                if (box != null && box.isAttached && me.isAttached) holder.bounds[part] = box.localBoundingBoxOf(me, clipBounds = false)
            }
            .graphicsLayer { alpha = if (show) 1f else 0.35f }
            .then(modifier)
            .drawWithContent {
                drawContent()
                drawRect(line, style = Stroke(if (selected) 2.dp.toPx() else 1.dp.toPx()))
            }
            .containerDrag(holder, {
                holder.startSize = holder.bounds[part]?.let { IntSize(it.width.toInt(), it.height.toInt()) } ?: IntSize.Zero
                hooks.onDragStart(part)
            }) { hooks.onMove(part, it, holder.startSize, latestArea) },
    ) {
        content()
    }
}

/** 編集のときの＋（部品の右上の角。ドラッグで大きさ）と歯車（左下の角。その部品の設定）。入れ物の外にはみ出さないよう寄せる。 */
@Composable
private fun EditHandles(edit: SpotifyEditHooks, holder: CoordsHolder, area: IntSize) {
    val hooks by rememberUpdatedState(edit)
    val latestArea by rememberUpdatedState(area)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val handle = with(density) { HANDLE.toPx() }
    fun clampX(v: Float) = v.coerceIn(0f, (area.width - handle).coerceAtLeast(0f))
    fun clampY(v: Float) = v.coerceIn(0f, (area.height - handle).coerceAtLeast(0f))
    SpotifyPart.entries.forEach { part ->
        val r = holder.bounds[part] ?: return@forEach
        val selected = edit.selected == part
        val line = if (selected) EDIT_ACCENT else Color.White.copy(alpha = 0.8f)
        key(part) {
            Box(
                Modifier.offset { IntOffset(clampX(r.right - handle / 2).roundToInt(), clampY(r.top - handle / 2).roundToInt()) }
                    .size(HANDLE)
                    .containerDrag(holder, {
                        holder.startSize = holder.bounds[part]?.let { IntSize(it.width.toInt(), it.height.toInt()) } ?: IntSize.Zero
                        hooks.onDragStart(part)
                    }) { hooks.onResize(part, it, holder.startSize, latestArea) }
                    .drawBehind {
                        val c = center
                        val arm = size.width * 0.5f
                        val stroke = 3.dp.toPx()
                        drawCircle(Color.Black.copy(alpha = 0.35f), size.width * 0.36f)
                        drawLine(line, c.copy(x = c.x - arm), c.copy(x = c.x + arm), stroke, StrokeCap.Round)
                        drawLine(line, c.copy(y = c.y - arm), c.copy(y = c.y + arm), stroke, StrokeCap.Round)
                    },
            )
            Box(
                Modifier.offset { IntOffset(clampX(r.left - handle / 2).roundToInt(), clampY(r.bottom - handle / 2).roundToInt()) }
                    .size(HANDLE)
                    .clip(RoundedCornerShape(HANDLE / 2))
                    .background(if (selected) EDIT_ACCENT else Color(0xCC1B1F27))
                    .border(1.dp, line, RoundedCornerShape(HANDLE / 2))
                    .clickable { hooks.onSettings(part) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(WdIcons.Gear, L("設定", "Settings"), tint = if (selected) Color(0xFF04222C) else Color.White, modifier = Modifier.size(HANDLE * 0.6f))
            }
        }
    }
}

/**
 * ドラッグ。量は [holder] の入れ物の座標で、押し始めてからの合計（部品が動いても指とずれない）。
 */
private fun Modifier.containerDrag(holder: CoordsHolder, onStart: () -> Unit, onDrag: (Offset) -> Unit): Modifier = composed {
    val me = remember { CoordsHolder() }
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    onGloballyPositioned { me.container = it }.pointerInput(Unit) {
        var from = Offset.Zero
        fun inContainer(p: Offset): Offset {
            val own = me.container ?: return p
            val box = holder.container ?: return p
            return if (own.isAttached && box.isAttached) box.localPositionOf(own, p) else p
        }
        detectDragGestures(
            onDragStart = { from = inContainer(it); start() },
            onDrag = { change, _ ->
                change.consume()
                drag(inContainer(change.position) - from)
            },
        )
    }
}

/** 編集で選んでいる部品の色。 */
private val EDIT_ACCENT = Color(0xFF4DD4FF)

/** 編集画面の＋と歯車の大きさ。 */
private val HANDLE = 30.dp

/**
 * 親の中で、残りの余白の割合（[x]・[y]、0 が左上・1 が右下）の位置に置く。値は置くときに読むので、動かしても描き直しは置き直しだけで済む。
 * 自分の大きさは親いっぱいとして返し、中身は中身の大きさで測る（押せる範囲は中身だけ）。
 */
private fun Modifier.placeAt(x: () -> Float, y: () -> Float): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else p.width
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else p.height
    layout(width, height) {
        p.place(((width - p.width) * x().coerceIn(0f, 1f)).roundToInt(), ((height - p.height) * y().coerceIn(0f, 1f)).roundToInt())
    }
}

/** 0..100 の [a] から [b] へ、[t] の割合だけ進めた 0..1 の値。 */
private fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t) / 100f

private fun textAlign(align: String) = when (align) {
    "center" -> TextAlign.Center
    "right" -> TextAlign.End
    else -> TextAlign.Start
}

/** 全画面の縁の余白（部品はこの内側に置く）。 */
private val EDGE = 12.dp

/** 見本のジャケットの URL の代わり（プレビューは album に `PREVIEW_COVER to 画像` を渡す）。 */
const val PREVIEW_COVER = "preview"

/** 設定のプレビューに出す見本の曲（[fetchedAt] から再生が進む）。 */
fun previewSong(fetchedAt: Long) = SpotifyState(
    available = true,
    playing = true,
    trackName = L("見本の曲名", "Sample Song Title"),
    artistName = L("アーティスト名", "Artist Name"),
    albumName = "Sample Album",
    albumImageUrl = PREVIEW_COVER,
    progressMs = 83_000,
    durationMs = 236_000,
    fetchedAt = fetchedAt,
)

fun previewLyrics(lineMs: Long = 3_200L): LyricsRepository.Lyrics {
    val words = listOf(
        L("歌詞はこのように流れます", "Lyrics scroll like this"), L("いま歌っている行は明るく", "The current line is brighter"),
        L("ほかの行は薄く出ます", "Other lines are dimmed"), L("大きさや太さを変えられます", "Change the size and weight"),
        L("好きな場所に置けます", "Place them anywhere"), "", L("行の間も変えられます", "Line spacing is adjustable too"),
        L("出典は右下に残ります", "The source stays at the corner"),
    )
    // 再生位置（見本は 83 秒から進む）の前後に行を並べ、くり返し流す
    return LyricsRepository.Lyrics(
        lines = List(80) { i -> LyricsRepository.Line(70_000L + i * lineMs, words[i % words.size]) },
        synced = true,
    )
}

/** 歌詞の探し具合。 */
private sealed interface LyricsUi {
    data object Loading : LyricsUi
    data object NotFound : LyricsUi
    data class Failed(val message: String) : LyricsUi
    data class Found(val lyrics: LyricsRepository.Lyrics) : LyricsUi
}

/**
 * 右の歌詞。再生位置に合わせて、いま歌っている行を白く太くして欄の真ん中へ送る（ほかの行は薄く）。
 * 時刻の無い歌詞は、曲の長さから振った目安の時刻で同じように流す（[LyricsRepository.Lyrics.estimated]）。上下の端はぼかす。
 */
@Composable
private fun LyricsPanel(sp: SpotifyState, load: suspend (SpotifyState) -> Result<LyricsRepository.Lyrics?>, lo: SpotifyLayout, modifier: Modifier) {
    val latest by rememberUpdatedState(sp)
    val key = listOf(sp.trackName, sp.artistName, sp.albumName, sp.durationMs)
    val ui by produceState<LyricsUi>(LyricsUi.Loading, key) {
        value = LyricsUi.Loading
        if (latest.trackName == null) {
            value = LyricsUi.NotFound
            return@produceState
        }
        // 取得できなかったときは、歌詞を開いている間、間を空けて（5 秒から 1 分まで延ばしながら）探し直す
        var wait = 5_000L
        while (true) {
            val result = load(latest)
            value = result.fold(
                { if (it == null) LyricsUi.NotFound else LyricsUi.Found(it) },
                { LyricsUi.Failed(it.message ?: L("通信エラー", "Network error")) },
            )
            if (result.isSuccess) break
            delay(wait)
            wait = (wait * 2).coerceAtMost(60_000L)
        }
    }
    // 再生位置は 5 秒ごとにしか届かないので、受け取ってからの経過を足す。行の切り替わりに遅れないよう 0.2 秒ごとに見る
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(200)
        }
    }
    val fade = Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 0.14f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
        }
    Box(modifier) {
        Box(Modifier.fillMaxSize().then(fade)) {
            when (val u = ui) {
                LyricsUi.Loading -> LyricsNote(L("歌詞を探しています…", "Searching for lyrics…"), lo)
                LyricsUi.NotFound -> LyricsNote(L("この曲の歌詞は見つかりませんでした", "No lyrics found for this song"), lo)
                is LyricsUi.Failed -> LyricsNote(L("歌詞を取得できません: ${u.message}\nしばらくしてからもう一度探します", "Can't get lyrics: ${u.message}\nWill try again shortly"), lo)
                is LyricsUi.Found -> when {
                    u.lyrics.instrumental -> LyricsNote(L("♪ インストゥルメンタル", "♪ Instrumental"), lo)
                    // 時刻の無い歌詞も、曲の長さから振った目安の時刻で同じように流す
                    u.lyrics.synced || u.lyrics.estimated -> SyncedLyrics(u.lyrics.lines, positionMs(sp, tick), lo)
                    // 曲の長さが分からず時刻を振れなかったときだけ、並べて指でスクロールしてもらう
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 60.dp)) {
                        u.lyrics.lines.forEach { line ->
                            Text(
                                line.text.ifEmpty { " " },
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = lo.lyricsFontSize.tu,
                                fontWeight = FontWeight(lo.lyricsOtherWeight),
                                lineHeight = (lo.lyricsLineHeight / 100f).em,
                                textAlign = textAlign(lo.lyricsAlign),
                                modifier = Modifier.fillMaxWidth().padding(vertical = (lo.lyricsGap / 2f).dp),
                            )
                        }
                    }
                }
            }
        }
        // 出典は、端をぼかす範囲の外（欄の右下の外側）に。ここだけは設定で変えない
        Text(
            (ui as? LyricsUi.Found)?.lyrics.let {
                val source = L("歌詞: ${it?.source ?: LyricsRepository.LRCLIB}", "Lyrics: ${it?.source ?: LyricsRepository.LRCLIB}")
                if (it?.estimated == true) L("時刻の無い歌詞のため、位置は目安です ・ $source", "Lyrics have no timing, so positions are approximate · $source") else source
            },
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 10.tu,
            modifier = Modifier.align(Alignment.BottomEnd).offset(y = 18.dp),
        )
    }
}

@Composable
private fun LyricsNote(text: String, lo: SpotifyLayout) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 18.tu, textAlign = textAlign(lo.lyricsAlign), modifier = Modifier.fillMaxWidth())
    }
}

/** 時刻付きの歌詞。行の位置を測っておき、いまの行の真ん中が欄の真ん中に来るよう全体をずらす。 */
@Composable
private fun SyncedLyrics(lines: List<LyricsRepository.Line>, position: Long, lo: SpotifyLayout) {
    // 少し先回りして光らせる（届く再生位置が実際より遅れがちなため）
    val current = lines.indexOfLast { (it.timeMs ?: 0) <= position + 250 }
    val tops = remember(lines) { IntArray(lines.size) }
    val heights = remember(lines) { IntArray(lines.size) }
    var measured by remember(lines) { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val boxHeight = constraints.maxHeight
        val target = if (current < 0 || measured == 0) boxHeight / 2f - (heights.getOrElse(0) { 0 }) / 2f
        else boxHeight / 2f - (tops[current] + heights[current] / 2f)
        val offset by animateFloatAsState(target, tween(520, easing = FastOutSlowInEasing), label = "lyricsScroll")
        Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { translationY = offset }) {
            lines.forEachIndexed { i, line ->
                val on = i == current
                // 歌い終えた行は、ほかの行より少しだけ薄く
                val dim = lo.lyricsDim / 100f
                val alpha by animateFloatAsState(if (on) 1f else if (i < current) dim * 0.72f else dim, tween(300), label = "lyricAlpha")
                Text(
                    line.text.ifEmpty { "♪" },
                    color = Color.White.copy(alpha = alpha),
                    fontSize = lo.lyricsFontSize.tu,
                    fontWeight = FontWeight(if (on) lo.lyricsWeight else lo.lyricsOtherWeight),
                    lineHeight = (lo.lyricsLineHeight / 100f).em,
                    textAlign = textAlign(lo.lyricsAlign),
                    modifier = Modifier.fillMaxWidth().padding(vertical = (lo.lyricsGap / 2f).dp).onGloballyPositioned {
                        // 位置が変わったときだけ数え直す（ずらすたびに呼ばれるので、毎回数えると描き直しが止まらない）
                        val y = it.positionInParent().y.toInt()
                        if (tops[i] != y || heights[i] != it.size.height) {
                            tops[i] = y
                            heights[i] = it.size.height
                            measured++
                        }
                    },
                )
            }
        }
    }
}

/** 曲の頭からの再生位置（ミリ秒）。再生中は受け取ってからの経過を足す。 */
private fun positionMs(sp: SpotifyState, now: Long): Long {
    var pos = sp.progressMs ?: return 0
    if (sp.playing && sp.fetchedAt > 0) pos += max(0L, now - sp.fetchedAt)
    return sp.durationMs?.let { minOf(pos, it) } ?: pos
}

private val CONTROL_HEIGHT = 48.dp

/** 触られないまま、操作ボタンなどを消すまでの時間（Spotify 以外の全画面。Spotify は設定の秒数）。 */
internal const val IDLE_HIDE_MS = 5_000L

/**
 * 溶けるように消す。[p] が 1 で元のまま、0 で消える。
 * 薄れながら下へ垂れ、縦に少し伸びて横は細る（ぼかしは Android 12 未満で効かないので、形の変化で溶ける感じを出す）。
 */
internal fun Modifier.melt(p: Float): Modifier = graphicsLayer {
    val q = 1f - p
    alpha = p * p
    translationY = q * 18.dp.toPx()
    scaleY = 1f + q * 0.35f
    scaleX = 1f - q * 0.12f
    transformOrigin = TransformOrigin(0.5f, 0f)
}.blur(((1f - p) * 10).dp, BlurredEdgeTreatment.Unbounded)

/** 右上の「画面を暗くしない」。オンのときは白地、オフのときは枠だけ。時刻の全画面（[BigClockScreen]）でも使う。 */
@Composable
internal fun AwakeToggle(on: Boolean, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    PillToggle(on, if (on) L("暗くしない：オン", "Keep awake: on") else L("暗くしない：オフ", "Keep awake: off"), WdIcons.Sun, enabled, onToggle, modifier)
}

/** 右上の小さな切り替え。オンのときは白地、オフのときは枠だけ。 */
@Composable
internal fun PillToggle(on: Boolean, label: String, icon: ImageVector, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier, scale: Float = 1f, radius: Int = 16) {
    val bg by animateColorAsState(if (on) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.10f), tween(200), label = "awakeBg")
    val fg by animateColorAsState(if (on) Color(0xFF1B1F27) else Color.White.copy(alpha = 0.78f), tween(200), label = "awakeFg")
    Row(
        modifier.clip(RoundedCornerShape((radius * scale).dp))
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = if (on) 0f else 0.35f), RoundedCornerShape((radius * scale).dp))
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 10.dp * scale, vertical = 6.dp * scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp * scale))
        Text(
            label,
            color = fg,
            fontSize = (11 * scale).tu,
            maxLines = 1,
            modifier = Modifier.padding(start = 5.dp * scale),
        )
    }
}

/** 操作ボタン。既定（[scale] が 1）で記号はアーティスト名の文字（18）と同じくらいの大きさ、押せる範囲は指の大きさ。 */
@Composable
private fun ControlButton(icon: ImageVector, label: String, enabled: Boolean, scale: Float, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp * scale, CONTROL_HEIGHT * scale).clip(RoundedCornerShape(12.dp * scale)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp * scale))
    }
}

