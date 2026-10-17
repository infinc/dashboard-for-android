package app.dashboard.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.dashboard.AppGraph
import app.dashboard.data.Config
import app.dashboard.data.SaveAllRequest
import app.dashboard.data.SpotifyLayout
import app.dashboard.data.SpotifyPatch
import app.dashboard.i18n.L
import app.dashboard.ui.dashboard.NowPlayingScreen
import app.dashboard.ui.dashboard.PREVIEW_COVER
import app.dashboard.ui.dashboard.SpotifyEditHooks
import app.dashboard.ui.dashboard.SpotifyPart
import app.dashboard.ui.dashboard.previewLyrics
import app.dashboard.ui.dashboard.previewSong
import app.dashboard.ui.theme.LocalAccent
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Spotify の全画面の編集（設定の「Spotify」→「全画面モードを編集する」）。ダッシュボードと設定の上に画面いっぱいで重ねる。
 * 見本の曲で全画面をそのまま描き（[NowPlayingScreen] の編集モード）、部品ごとの細い枠・右上の＋（ドラッグで大きさ）・
 * 左下の歯車（その部品の設定）で変える。部品そのもののドラッグで位置を動かす。歯車を押すと見本が縮んで左へ寄り、右にその部品の設定が開く（右上の × で戻る）。
 * 見本は実際には何もしないが、ジャケットを押すと見本の歌詞が出て、再生・一時停止や右上の切り替えは見た目だけ切り替わる。
 * 終えるときは、保存するかどうかを尋ねる（保存は `spotify.background`・`monoColor`・`layout` だけを送る）。
 */
@Composable
fun SpotifyEditor(graph: AppGraph, config: Config, onClose: () -> Unit) {
    val original = remember { Triple(config.spotify.background, config.spotify.monoColor, config.spotify.layout) }
    var background by remember { mutableStateOf(original.first) }
    var monoColor by remember { mutableStateOf(original.second) }
    var layout by remember { mutableStateOf(original.third) }
    /** 右に開いている設定（部品、または null で「背景と全体」）。[panelOpen] が false なら閉じている。 */
    var panel by remember { mutableStateOf<SpotifyPart?>(null) }
    var panelOpen by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // 見本の状態（実際には何もしない）
    val lyricsOpen = remember { mutableStateOf(layout.lyricsMode == "open") }
    var playing by remember { mutableStateOf(true) }
    var burnIn by remember { mutableStateOf(true) }
    var keepAwake by remember { mutableStateOf(false) }
    var hiddenPreview by remember { mutableStateOf(false) }
    var fetchedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
            if (now - fetchedAt > 140_000) fetchedAt = now
        }
    }
    val cover = remember { PREVIEW_COVER to sampleCover() }
    val density = LocalDensity.current

    /** ドラッグを始めたときの見た目（量は始めてからの合計で来るので、ここからの差で決める）。 */
    var dragFrom by remember { mutableStateOf(layout) }

    fun openPanel(part: SpotifyPart?) {
        panel = part
        panelOpen = true
    }

    val hooks = SpotifyEditHooks(
        selected = if (panelOpen) panel else null,
        onDragStart = { dragFrom = layout },
        onMove = { part, d, size, area -> layout = moved(dragFrom, part, lyricsOpen.value, d, size, area) },
        onResize = { part, d, size, area -> layout = resized(dragFrom, part, lyricsOpen.value, d, size, area, with(density) { 18.dp.toPx() }) },
        onSettings = { openPanel(it) },
    )

    BackHandler {
        if (panelOpen) panelOpen = false else confirmExit = true
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFF06080B))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val p by animateFloatAsState(if (panelOpen) 1f else 0f, tween(420, easing = FastOutSlowInEasing), label = "editorPanel")
        val shrink = 0.58f
        val gap = 16.dp
        // 見本。設定を開くと縮んで左へ寄る
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val s = 1f - (1f - shrink) * p
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
                translationX = gap.toPx() * p
                shape = RoundedCornerShape(14.dp * p)
                clip = p > 0f
            },
        ) {
            NowPlayingScreen(
                previewSong(fetchedAt).copy(playing = playing), cover, now,
                keepAwake = keepAwake, onKeepAwake = { keepAwake = it }, burnIn = burnIn, onBurnIn = { burnIn = it },
                onControl = { if (it == "play") playing = true else if (it == "pause") playing = false },
                background = background, monoColor = monoColor, layout = layout,
                loadLyrics = { Result.success(previewLyrics()) }, onBack = {},
                edit = hooks, lyricsOpen = lyricsOpen, shownOverride = !hiddenPreview,
            )
        }

        // 上の帯（どの状態でも上の真ん中。設定を開いているときは見本の上の空いたところ）
        Column(
            Modifier.align(Alignment.TopStart).padding(top = 10.dp)
                .padding(start = if (panelOpen) gap else 0.dp)
                .width(if (panelOpen) maxWidth * shrink else maxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xE61B1F27))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BarButton(L("背景と全体", "Background & general"), panelOpen && panel == null) { openPanel(null) }
                BarButton(L("消えた状態", "Auto-hidden"), hiddenPreview) { hiddenPreview = !hiddenPreview }
                BarButton(L("終了", "Done"), false, strong = true) { confirmExit = true }
            }
            Text(
                status.ifEmpty { L("部品をドラッグで移動・右上の＋で大きさ・左下の歯車で設定", "Drag a part to move it · + to resize · gear for its settings") },
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.tu,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x991B1F27)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        // 右の設定
        AnimatedVisibility(
            panelOpen,
            Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(420)) { it } + fadeIn(tween(300)),
            exit = slideOutHorizontally(tween(360)) { it } + fadeOut(tween(250)),
        ) {
            Column(
                Modifier.width(maxWidth * (1f - shrink) - gap * 3).fillMaxHeight().padding(vertical = gap).padding(end = gap)
                    .clip(RoundedCornerShape(14.dp)).background(Wd.Surface)
                    .border(1.dp, Wd.Border, RoundedCornerShape(14.dp)),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(panel?.label() ?: L("背景と全体", "Background & general"), fontSize = 16.tu, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable { panelOpen = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", fontSize = 24.tu, color = Wd.Text2)
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp)) {
                    val set: (SpotifyLayout) -> Unit = { layout = it.sanitized() }
                    when (panel) {
                        null -> GeneralFields(background, { background = it }, monoColor, { monoColor = it }, layout, set)
                        else -> PartFields(panel!!, layout, set)
                    }
                }
            }
        }
    }

    if (confirmExit) {
        val changed = Triple(background, monoColor, layout) != original
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(L("全画面モードの編集を終えます", "Finish editing full-screen mode")) },
            text = {
                Text(
                    if (changed) L("変更を保存しますか？", "Save your changes?")
                    else L("変更はありません。保存しますか？", "Nothing has changed. Save anyway?"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    status = L("保存中…", "Saving…")
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                graph.settings.saveAll(SaveAllRequest(spotify = SpotifyPatch(background = background, monoColor = monoColor, layout = layout)))
                            }
                        }.onSuccess { onClose() }
                            .onFailure { status = L("保存できません: ${it.message}", "Couldn't save: ${it.message}") }
                    }
                }) { Text(L("保存して終了", "Save and finish")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmExit = false }) { Text(L("キャンセル", "Cancel")) }
                    TextButton(onClick = { confirmExit = false; onClose() }) { Text(L("保存せずに終了", "Finish without saving"), color = Wd.Red) }
                }
            },
        )
    }
}

@Composable
private fun BarButton(label: String, on: Boolean, strong: Boolean = false, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Text(
        label,
        color = when {
            strong -> Wd.OnAccent
            on -> accent
            else -> Color.White
        },
        fontSize = 13.tu,
        fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(16.dp))
            .background(if (strong) accent else Color.Transparent)
            .border(1.dp, if (on || strong) accent else Color.White.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun SpotifyPart.label() = when (this) {
    SpotifyPart.COVER -> L("ジャケット", "Cover")
    SpotifyPart.LYRICS -> L("歌詞", "Lyrics")
    SpotifyPart.TITLE -> L("曲名・アーティスト名", "Title and artist")
    SpotifyPart.CONTROLS -> L("操作ボタン", "Control buttons")
    SpotifyPart.PROGRESS -> L("再生時間", "Playback time")
    SpotifyPart.TOGGLES -> L("焼き付き防止・暗くしない", "Burn-in guard & keep awake")
    SpotifyPart.BACK -> L("戻るボタン", "Back button")
}

// ------------------------------------------------------------ ドラッグ

/** 部品の位置（0..100）。ジャケットは歌詞を出しているかで別の値。 */
private fun position(lo: SpotifyLayout, part: SpotifyPart, lyrics: Boolean): Pair<Int, Int> = when (part) {
    SpotifyPart.COVER -> if (lyrics) lo.coverLyricsX to lo.coverLyricsY else lo.coverX to lo.coverY
    SpotifyPart.LYRICS -> lo.lyricsX to lo.lyricsY
    SpotifyPart.TITLE -> lo.titleX to lo.titleY
    SpotifyPart.CONTROLS -> lo.controlsX to lo.controlsY
    SpotifyPart.PROGRESS -> lo.progressX to lo.progressY
    SpotifyPart.TOGGLES -> lo.togglesX to lo.togglesY
    SpotifyPart.BACK -> lo.backX to lo.backY
}

private fun SpotifyLayout.withPosition(part: SpotifyPart, lyrics: Boolean, x: Int, y: Int): SpotifyLayout = when (part) {
    SpotifyPart.COVER -> if (lyrics) copy(coverLyricsX = x, coverLyricsY = y) else copy(coverX = x, coverY = y)
    SpotifyPart.LYRICS -> copy(lyricsX = x, lyricsY = y)
    SpotifyPart.TITLE -> copy(titleX = x, titleY = y)
    SpotifyPart.CONTROLS -> copy(controlsX = x, controlsY = y)
    SpotifyPart.PROGRESS -> copy(progressX = x, progressY = y)
    SpotifyPart.TOGGLES -> copy(togglesX = x, togglesY = y)
    SpotifyPart.BACK -> copy(backX = x, backY = y)
}

/** 置ける余白 [free] の中で [percent] の位置にあった部品を [delta] だけ動かしたときの位置（0..100）。 */
private fun shifted(percent: Int, free: Float, delta: Float): Int =
    if (free <= 0f) percent else ((free * percent / 100f + delta) / free * 100f).roundToInt().coerceIn(0, 100)

/** 部品そのもののドラッグ。始めたときの見た目 [from] から [d] だけ動かす。 */
private fun moved(from: SpotifyLayout, part: SpotifyPart, lyrics: Boolean, d: Offset, size: IntSize, area: IntSize): SpotifyLayout {
    val (x, y) = position(from, part, lyrics)
    return from.withPosition(
        part, lyrics,
        shifted(x, (area.width - size.width).toFloat(), d.x),
        shifted(y, (area.height - size.height).toFloat(), d.y),
    ).sanitized()
}

/**
 * 右上の＋のドラッグ。始めたときの見た目 [from] から、右上の角を [d] だけ動かしたときの大きさにする。
 * 左下の角はそのままの場所に残るよう、位置も合わせて直す。[lyricsInset] は歌詞の欄の下の出典の分の高さ（px）。
 */
private fun resized(from: SpotifyLayout, part: SpotifyPart, lyrics: Boolean, d: Offset, size: IntSize, area: IntSize, lyricsInset: Float): SpotifyLayout {
    val w = size.width.toFloat().coerceAtLeast(1f)
    val h = size.height.toFloat().coerceAtLeast(1f)
    val aw = area.width.toFloat().coerceAtLeast(1f)
    val ah = area.height.toFloat().coerceAtLeast(1f)
    // 縦横の伸びの平均（右へ・上へ広げると大きくなる）
    val f = (((w + d.x) / w + (h - d.y) / h) / 2f).coerceIn(0.2f, 5f)
    var nw: Float
    var nh: Float
    val next = when (part) {
        SpotifyPart.COVER -> {
            val side = (w + (d.x - d.y) / 2f).coerceAtLeast(40f)
            nw = side; nh = side
            from.copy(coverSize = (side / ah * 100f).roundToInt())
        }
        SpotifyPart.LYRICS -> {
            nw = (w + d.x).coerceAtLeast(40f); nh = (h - d.y).coerceAtLeast(40f)
            from.copy(lyricsWidth = (nw / aw * 100f).roundToInt(), lyricsHeight = ((nh + lyricsInset) / ah * 100f).roundToInt())
        }
        SpotifyPart.TITLE -> {
            val v = ((h - d.y) / h).coerceIn(0.2f, 5f)
            nw = (w + d.x).coerceAtLeast(40f); nh = h * v
            from.copy(
                titleWidth = (nw / aw * 100f).roundToInt(),
                titleSize = (from.titleSize * v).roundToInt(),
                artistSize = (from.artistSize * v).roundToInt(),
            )
        }
        SpotifyPart.CONTROLS -> { nw = w * f; nh = h * f; from.copy(controlsSize = (from.controlsSize * f).roundToInt()) }
        SpotifyPart.PROGRESS -> { nw = w * f; nh = h * f; from.copy(progressSize = (from.progressSize * f).roundToInt()) }
        SpotifyPart.TOGGLES -> { nw = w * f; nh = h * f; from.copy(togglesSize = (from.togglesSize * f).roundToInt()) }
        SpotifyPart.BACK -> { nw = w * f; nh = h * f; from.copy(backSize = (from.backSize * f).roundToInt()) }
    }.sanitized()
    // 左下の角を動かさない
    val (x, y) = position(from, part, lyrics)
    val left = (aw - w) * x / 100f
    val bottom = (ah - h) * y / 100f + h
    nw = nw.coerceAtMost(aw)
    nh = nh.coerceAtMost(ah)
    val nx = if (aw - nw <= 0f) x else (left / (aw - nw) * 100f).roundToInt().coerceIn(0, 100)
    val ny = if (ah - nh <= 0f) y else ((bottom - nh) / (ah - nh) * 100f).roundToInt().coerceIn(0, 100)
    return next.withPosition(part, lyrics, nx, ny)
}

// ------------------------------------------------------------ 右の設定

private val WEIGHTS
    @Composable get() = listOf(
        300 to L("細い (300)", "Light (300)"),
        400 to L("標準 (400)", "Regular (400)"),
        500 to L("やや太い (500)", "Medium (500)"),
        600 to L("太め (600)", "Semibold (600)"),
        700 to L("太い (700)", "Bold (700)"),
        800 to L("とても太い (800)", "Extra bold (800)"),
        900 to L("いちばん太い (900)", "Black (900)"),
    )

private val ALIGNS
    @Composable get() = listOf("left" to L("左揃え", "Left"), "center" to L("中央揃え", "Center"), "right" to L("右揃え", "Right"))

/** 目盛りの点の無いつまみ（細かい刻みだと点が詰まって読めないため）。値は [step] に丸める。 */
@Composable
private fun NumberSlider(label: String, value: Int, min: Int, max: Int, step: Int = 1, unit: String = "", onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Wd.Text2, fontSize = 13.tu, modifier = Modifier.weight(1f))
            Text("$value$unit", color = LocalAccent.current, fontSize = 13.tu, fontWeight = FontWeight.SemiBold)
        }
        androidx.compose.material3.Slider(
            value = value.toFloat(),
            onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
        )
    }
}

@Composable
private fun Position(x: Int, y: Int, onX: (Int) -> Unit, onY: (Int) -> Unit, prefix: String = "") {
    NumberSlider(prefix + L("横の位置（0 = 左端・100 = 右端）", "Horizontal (0 = left, 100 = right)"), x, 0, 100, onChange = onX)
    NumberSlider(prefix + L("縦の位置（0 = 上端・100 = 下端）", "Vertical (0 = top, 100 = bottom)"), y, 0, 100, onChange = onY)
}

@Composable
private fun AutoHide(on: Boolean, onChange: (Boolean) -> Unit) =
    SwitchRow(L("自動で消す（触られないと消える）", "Auto-hide (fades when untouched)"), on, onChange)

@Composable
private fun Weight(label: String, value: Int, onChange: (Int) -> Unit) = Field(label) { Select(WEIGHTS, value, onChange) }

/** 部品ごとの設定。 */
@Composable
private fun PartFields(part: SpotifyPart, lo: SpotifyLayout, set: (SpotifyLayout) -> Unit) {
    when (part) {
        SpotifyPart.COVER -> {
            SwitchRow(L("表示する", "Show"), lo.coverShow, { set(lo.copy(coverShow = it)) })
            AutoHide(lo.coverAutoHide) { set(lo.copy(coverAutoHide = it)) }
            NumberSlider(L("大きさ（画面の高さに対して）", "Size (of the screen height)"), lo.coverSize, 15, 100, unit = "%") { set(lo.copy(coverSize = it)) }
            NumberSlider(L("角の丸み", "Corner radius"), lo.coverRadius, 0, 60, unit = "dp") { set(lo.copy(coverRadius = it)) }
            Position(lo.coverX, lo.coverY, { set(lo.copy(coverX = it)) }, { set(lo.copy(coverY = it)) })
            Position(lo.coverLyricsX, lo.coverLyricsY, { set(lo.copy(coverLyricsX = it)) }, { set(lo.copy(coverLyricsY = it)) }, prefix = L("歌詞を出しているとき: ", "With lyrics: "))
            Hint(L("ジャケットを押すと見本の歌詞が出ます。歌詞を出している間に動かすと、歌詞を出しているときの位置が変わります。", "Tap the cover to show sample lyrics. Moving it while lyrics are shown changes its position with lyrics."))
        }
        SpotifyPart.LYRICS -> {
            Field(L("歌詞の出し方", "How lyrics appear")) {
                Select(
                    listOf(
                        "tap" to L("ジャケットを押すと出す（ジャケットが無ければ画面を押す）", "Tap the cover to show (or the screen without a cover)"),
                        "open" to L("開いたときから出しておく（押すと隠す）", "Shown from the start (tap to hide)"),
                        "off" to L("出さない", "Don't show"),
                    ),
                    lo.lyricsMode, { set(lo.copy(lyricsMode = it)) },
                )
            }
            AutoHide(lo.lyricsAutoHide) { set(lo.copy(lyricsAutoHide = it)) }
            NumberSlider(L("文字の大きさ", "Text size"), lo.lyricsFontSize, 12, 60) { set(lo.copy(lyricsFontSize = it)) }
            Weight(L("いま歌っている行の太さ", "Weight of the current line"), lo.lyricsWeight) { set(lo.copy(lyricsWeight = it)) }
            Weight(L("ほかの行の太さ", "Weight of other lines"), lo.lyricsOtherWeight) { set(lo.copy(lyricsOtherWeight = it)) }
            NumberSlider(L("行間（行と行の間）", "Space between lines"), lo.lyricsGap, 0, 40, unit = "dp") { set(lo.copy(lyricsGap = it)) }
            NumberSlider(L("折り返した行の高さ", "Wrapped line height"), lo.lyricsLineHeight, 100, 250, step = 5, unit = "%") { set(lo.copy(lyricsLineHeight = it)) }
            NumberSlider(L("ほかの行の濃さ", "Other lines' opacity"), lo.lyricsDim, 10, 100, unit = "%") { set(lo.copy(lyricsDim = it)) }
            Field(L("揃え", "Alignment")) { Select(ALIGNS, lo.lyricsAlign, { set(lo.copy(lyricsAlign = it)) }) }
            NumberSlider(L("欄の幅（画面の幅に対して）", "Box width (of the screen width)"), lo.lyricsWidth, 20, 100, unit = "%") { set(lo.copy(lyricsWidth = it)) }
            NumberSlider(L("欄の高さ（画面の高さに対して）", "Box height (of the screen height)"), lo.lyricsHeight, 15, 100, unit = "%") { set(lo.copy(lyricsHeight = it)) }
            Position(lo.lyricsX, lo.lyricsY, { set(lo.copy(lyricsX = it)) }, { set(lo.copy(lyricsY = it)) })
            Hint(L("歌詞の右下の出典（「歌詞: LRCLIB」など）は変えられません。", "The lyrics source at the bottom right (such as \"Lyrics: LRCLIB\") can't be changed."))
        }
        SpotifyPart.TITLE -> {
            SwitchRow(L("曲名を表示する", "Show the title"), lo.titleShow, { set(lo.copy(titleShow = it)) })
            SwitchRow(L("アーティスト名を表示する", "Show the artist"), lo.artistShow, { set(lo.copy(artistShow = it)) })
            AutoHide(lo.titleAutoHide) { set(lo.copy(titleAutoHide = it)) }
            SwitchRow(L("アーティスト名を曲名の上に出す", "Put the artist above the title"), lo.artistFirst, { set(lo.copy(artistFirst = it)) })
            NumberSlider(L("曲名の文字の大きさ", "Title text size"), lo.titleSize, 12, 80) { set(lo.copy(titleSize = it)) }
            Weight(L("曲名の太さ", "Title weight"), lo.titleWeight) { set(lo.copy(titleWeight = it)) }
            NumberSlider(L("アーティスト名の文字の大きさ", "Artist text size"), lo.artistSize, 10, 60) { set(lo.copy(artistSize = it)) }
            Weight(L("アーティスト名の太さ", "Artist weight"), lo.artistWeight) { set(lo.copy(artistWeight = it)) }
            Field(L("揃え", "Alignment")) { Select(ALIGNS, lo.titleAlign, { set(lo.copy(titleAlign = it)) }) }
            NumberSlider(L("まとまりの幅（画面の幅に対して）", "Block width (of the screen width)"), lo.titleWidth, 20, 100, unit = "%") { set(lo.copy(titleWidth = it)) }
            NumberSlider(L("アーティスト名の欄の幅（まとまりの幅に対して）", "Artist width (of the block width)"), lo.artistWidth, 20, 100, unit = "%") { set(lo.copy(artistWidth = it)) }
            Position(lo.titleX, lo.titleY, { set(lo.copy(titleX = it)) }, { set(lo.copy(titleY = it)) })
            Hint(L("＋を上下に動かすと文字の大きさ、左右に動かすと幅が変わります。長いアーティスト名は「…」で切ります。", "Drag + up/down to change the text size, left/right for the width. Long artist names end with \"…\"."))
        }
        SpotifyPart.CONTROLS -> {
            SwitchRow(L("表示する", "Show"), lo.controlsShow, { set(lo.copy(controlsShow = it)) })
            AutoHide(lo.controlsAutoHide) { set(lo.copy(controlsAutoHide = it)) }
            NumberSlider(L("大きさ", "Size"), lo.controlsSize, 50, 250, step = 5, unit = "%") { set(lo.copy(controlsSize = it)) }
            Position(lo.controlsX, lo.controlsY, { set(lo.copy(controlsX = it)) }, { set(lo.copy(controlsY = it)) })
        }
        SpotifyPart.PROGRESS -> {
            SwitchRow(L("表示する", "Show"), lo.progressShow, { set(lo.copy(progressShow = it)) })
            AutoHide(lo.progressAutoHide) { set(lo.copy(progressAutoHide = it)) }
            NumberSlider(L("文字の大きさ", "Text size"), lo.progressSize, 10, 48) { set(lo.copy(progressSize = it)) }
            Position(lo.progressX, lo.progressY, { set(lo.copy(progressX = it)) }, { set(lo.copy(progressY = it)) })
        }
        SpotifyPart.TOGGLES -> {
            SwitchRow(L("表示する", "Show"), lo.togglesShow, { set(lo.copy(togglesShow = it)) })
            AutoHide(lo.togglesAutoHide) { set(lo.copy(togglesAutoHide = it)) }
            NumberSlider(L("大きさ", "Size"), lo.togglesSize, 50, 250, step = 5, unit = "%") { set(lo.copy(togglesSize = it)) }
            NumberSlider(L("角の丸み", "Corner radius"), lo.togglesRadius, 0, 30, unit = "dp") { set(lo.copy(togglesRadius = it)) }
            Position(lo.togglesX, lo.togglesY, { set(lo.copy(togglesX = it)) }, { set(lo.copy(togglesY = it)) })
            Hint(L("「焼き付き防止」は全画面の間だけ効きます。閉じると「画面の明るさ」の設定どおりに戻ります。", "The burn-in guard switch only applies while the full screen is open. Closing it returns to the Brightness settings."))
        }
        SpotifyPart.BACK -> {
            AutoHide(lo.backAutoHide) { set(lo.copy(backAutoHide = it)) }
            NumberSlider(L("大きさ", "Size"), lo.backSize, 50, 250, step = 5, unit = "%") { set(lo.copy(backSize = it)) }
            Position(lo.backX, lo.backY, { set(lo.copy(backX = it)) }, { set(lo.copy(backY = it)) })
            Hint(L("戻るボタンは消せません。消えていても、端末の戻る操作で閉じられます。", "The back button can't be removed. Even when hidden, the device's back gesture closes the screen."))
        }
    }
}

/** 「背景と全体」。背景・1 色の背景の色・消すまでの秒数・部品の表示・既定に戻す。 */
@Composable
private fun GeneralFields(
    background: String,
    onBackground: (String) -> Unit,
    monoColor: String,
    onMonoColor: (String) -> Unit,
    lo: SpotifyLayout,
    set: (SpotifyLayout) -> Unit,
) {
    Field(L("背景", "Background"), L("「単色（選んだ色）」のほかは、ジャケットから拾った色で描きます。", "All but \"Single color\" use colors picked from the album cover.")) {
        Select(
            listOf(
                "still" to L("単色（動かない、ほんの少しのグラデーション）", "Solid (still, a subtle gradient)"),
                "flow" to L("ぼやけた光（ゆっくり漂う）", "Soft glow (slowly drifting)"),
                "spike" to L("尖った光（ぼやけた縦の光が揺れる）", "Spikes (soft vertical shards swaying)"),
                "mono" to L("単色（選んだ色、ぼやけた光がゆっくり漂う）", "Single color (your color, soft glows drifting)"),
            ),
            background, onBackground,
        )
    }
    if (background == "mono") MonoColorField(monoColor, onMonoColor)
    NumberSlider(L("自動で消すまで（触られないまま）", "Auto-hide after no touch for"), lo.hideSeconds, 2, 60, unit = L(" 秒", " s")) { set(lo.copy(hideSeconds = it)) }
    Hint(L("どの部品を自動で消すかは、部品ごとの歯車で選びます。上の「消えた状態」で、消えたときの見た目を確かめられます。", "Choose which parts auto-hide with each part's gear. \"Auto-hidden\" above shows how it looks."))
    androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
    Field(L("部品の表示", "Parts shown")) {
        SwitchRow(L("ジャケット", "Cover"), lo.coverShow, { set(lo.copy(coverShow = it)) })
        SwitchRow(L("曲名", "Title"), lo.titleShow, { set(lo.copy(titleShow = it)) })
        SwitchRow(L("アーティスト名", "Artist"), lo.artistShow, { set(lo.copy(artistShow = it)) })
        SwitchRow(L("操作ボタン", "Control buttons"), lo.controlsShow, { set(lo.copy(controlsShow = it)) })
        SwitchRow(L("再生時間", "Playback time"), lo.progressShow, { set(lo.copy(progressShow = it)) })
        SwitchRow(L("焼き付き防止・暗くしない", "Burn-in guard & keep awake"), lo.togglesShow, { set(lo.copy(togglesShow = it)) })
        SwitchRow(L("歌詞", "Lyrics"), lo.lyricsMode != "off", { set(lo.copy(lyricsMode = if (it) "tap" else "off")) })
    }
    Hint(L("出さない部品も、編集中は薄く出ます（歯車で設定を開けます）。", "Hidden parts still appear faintly while editing (open their settings with the gear)."))
    androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
    Field(L("元に戻す", "Reset")) {
        ActionButton(L("見た目を既定に戻す", "Reset the look to default"), { set(SpotifyLayout()) })
    }
}

/** 1 色の背景の色の候補。 */
private val MONO_SWATCHES
    @Composable get() = listOf(
        "#3E6FB8" to L("青", "Blue"),
        "#2E8C8C" to L("青緑", "Teal"),
        "#3F8F4F" to L("緑", "Green"),
        "#B8892E" to L("金", "Gold"),
        "#C0602E" to L("橙", "Orange"),
        "#B83E4F" to L("赤", "Red"),
        "#B04A8C" to L("桃", "Pink"),
        "#6A4AB8" to L("紫", "Purple"),
        "#4A5A78" to L("鉛", "Slate"),
        "#8A8A8A" to L("灰", "Gray"),
    )

/** 1 色の背景の色を選ぶ欄（候補か #RRGGBB）。 */
@Composable
private fun MonoColorField(value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Field(
        L("背景の色", "Background color"),
        L("この色の明るい・暗いだけで、ぼやけた光をゆっくり漂わせます。候補から選ぶか、#RRGGBB で入れてください。", "Soft glows drift slowly using only lighter and darker shades of this color. Pick one or enter #RRGGBB."),
    ) {
        // 欄が狭いので 3 つずつ並べる
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MONO_SWATCHES.chunked(3).forEach { row -> ColorSwatches(row, value, onChange) }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(160.dp)) {
                Input(text, {
                    text = it
                    app.dashboard.data.monoColorOrNull(it)?.let(onChange)
                }, placeholder = "#3E6FB8")
            }
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(app.dashboard.ui.theme.colorOf(value)).border(1.dp, Wd.Border, RoundedCornerShape(18.dp)))
        }
    }
}

/** 見本のジャケット（斜めのグラデーションに円を 1 つ）。 */
private fun sampleCover(): ImageBitmap {
    val size = 96
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFF6A3FB5.toInt(), 0xFFE0794A.toInt(), android.graphics.Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    paint.shader = null
    paint.color = 0x55FFFFFF
    canvas.drawCircle(size * 0.62f, size * 0.4f, size * 0.22f, paint)
    return bitmap.asImageBitmap()
}
