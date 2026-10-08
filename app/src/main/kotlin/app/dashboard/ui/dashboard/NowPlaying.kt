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
import app.dashboard.data.SpotifyState
import app.dashboard.ui.common.Tabular
import app.dashboard.ui.common.WdIcons
import app.dashboard.ui.theme.tu
import kotlinx.coroutines.delay

/**
 * Spotify の再生中の曲を画面いっぱいに出す。
 * 背景はジャケットに使われている色が漂うグラデーション（[coverGradient]）、中央にジャケット、左下に曲名とアーティスト名、下の真ん中に操作ボタン
 * （前の曲・再生／一時停止・次の曲。アーティスト名と同じくらいの大きさ）、右下にボタンと同じ高さ・大きさで再生時間。左上の「<」か端末の戻る操作で閉じる。
 * 右上の小さなボタンで「画面を暗くしない」を切り替える（[keepAwake]）。
 * 操作ボタン・右上のボタン・再生時間は、[IDLE_HIDE_MS] 触られなければ溶けるように消え、どこかに触れると戻る。
 * ジャケットを押すとジャケットが左へ動き、右に歌詞（[loadLyrics]、LRCLIB）を出す。もう一度押すと歌詞を消して真ん中へ戻る。
 */
@Composable
fun NowPlayingScreen(
    sp: SpotifyState?,
    album: Pair<String, ImageBitmap>?,
    now: Long,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    onControl: (String) -> Unit,
    loadLyrics: suspend (SpotifyState) -> Result<LyricsRepository.Lyrics?>,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var lyricsOpen by remember { mutableStateOf(false) }
    val shift by animateFloatAsState(if (lyricsOpen) 1f else 0f, tween(480, easing = FastOutSlowInEasing), label = "lyricsShift")
    // 触れるたびに数を進め、そこから IDLE_HIDE_MS 何もなければ隠す
    var touches by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(touches) {
        shown = true
        delay(IDLE_HIDE_MS)
        shown = false
    }
    val melt by animateFloatAsState(
        if (shown) 1f else 0f,
        if (shown) tween(280, easing = FastOutSlowInEasing) else tween(1100, easing = LinearOutSlowInEasing),
        label = "melt",
    )
    val cover = album?.takeIf { sp?.albumImageUrl != null && it.first == sp.albumImageUrl }
    // 背景はジャケットの色で動くグラデーション（CoverGradient.kt）
    val palette = remember(cover?.first) { coverPalette(cover?.second) }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .coverGradient(palette)
            // 画面のどこに触れても（ボタンの上でも）操作の表示を戻す。触れた操作はそのままボタンにも届く
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed && !it.previousPressed }) touches++
                    }
                }
            }
            // 下のダッシュボードに触れさせない
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val side = min(maxHeight * 0.58f, maxWidth * 0.5f)
        val artistWidth = maxWidth / 2 - CONTROLS_HALF - 112.dp
        // 歌詞を出している間は、ジャケットを画面の左半分の真ん中へ
        val coverShift = maxWidth * 0.25f
        Crossfade(
            cover,
            Modifier.align(Alignment.Center)
                .graphicsLayer { translationX = -coverShift.toPx() * shift }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = L("歌詞", "Lyrics")) { lyricsOpen = !lyricsOpen },
            animationSpec = tween(500),
            label = "cover",
        ) { c ->
            Box(
                Modifier.size(side)
                    .shadow(28.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                if (c != null) Image(c.second, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }

        if (shift > 0.01f && sp != null) {
            val height = min(side + 40.dp, maxHeight - 300.dp).coerceAtLeast(160.dp)
            LyricsPanel(
                sp,
                loadLyrics,
                Modifier.align(Alignment.CenterStart)
                    .padding(start = maxWidth * 0.5f + 12.dp)
                    .size(maxWidth * 0.5f - 68.dp, height)
                    .graphicsLayer {
                        alpha = shift
                        translationX = (1 - shift) * 48.dp.toPx()
                    },
            )
        }

        // 戻る: 「<」だけ（押せる範囲は指の大きさにする）
        Box(
            Modifier.align(Alignment.TopStart).padding(14.dp).size(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WdIcons.Back, L("戻る", "Back"), tint = Color.White, modifier = Modifier.size(30.dp))
        }

        // 曲名は操作ボタンより上の行なので、右端まで 1 行で使う。右端まで届く長い曲名だけ折り返す（省略はしない）。
        // アーティスト名は操作ボタンと同じ高さなので、ボタンに重ならない幅まで
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 56.dp, end = 56.dp, bottom = 44.dp)) {
            Text(
                sp?.trackName ?: L("再生中の曲はありません", "Nothing is playing"),
                color = Color.White,
                fontSize = 30.tu,
                fontWeight = FontWeight.Bold,
                lineHeight = 1.25.em,
            )
            sp?.artistName?.takeIf { sp.trackName != null }?.let {
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 18.tu,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp).widthIn(max = artistWidth),
                )
            }
        }

        // 画面を暗くしない（右上に小さく）。消えている間は押せない
        AwakeToggle(
            keepAwake,
            enabled = shown,
            onToggle = { onKeepAwake(!keepAwake) },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 20.dp).melt(melt),
        )

        if (sp?.trackName != null) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).melt(melt),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlButton(WdIcons.Previous, L("前の曲", "Previous"), shown) { onControl("previous") }
                if (sp.playing) ControlButton(WdIcons.Pause, L("一時停止", "Pause"), shown) { onControl("pause") }
                else ControlButton(WdIcons.Play, L("再生", "Play"), shown) { onControl("play") }
                ControlButton(WdIcons.Next, L("次の曲", "Next"), shown) { onControl("next") }
            }
            // 再生時間は右下。操作ボタンと同じ高さの枠の真ん中に、記号と同じくらいの大きさの数字で
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 10.dp).height(CONTROL_HEIGHT).melt(melt),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    progress(sp, now),
                    color = Color.White,
                    // 数字の高さ（0.7em ほど）を、記号の高さ（26dp のアイコンの 12/24）にそろえる
                    fontSize = 17.tu,
                    style = Tabular,
                    maxLines = 1,
                )
            }
        }
    }
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
private fun LyricsPanel(sp: SpotifyState, load: suspend (SpotifyState) -> Result<LyricsRepository.Lyrics?>, modifier: Modifier) {
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
                LyricsUi.Loading -> LyricsNote(L("歌詞を探しています…", "Searching for lyrics…"))
                LyricsUi.NotFound -> LyricsNote(L("この曲の歌詞は見つかりませんでした", "No lyrics found for this song"))
                is LyricsUi.Failed -> LyricsNote(L("歌詞を取得できません: ${u.message}\nしばらくしてからもう一度探します", "Can't get lyrics: ${u.message}\nWill try again shortly"))
                is LyricsUi.Found -> when {
                    u.lyrics.instrumental -> LyricsNote(L("♪ インストゥルメンタル", "♪ Instrumental"))
                    // 時刻の無い歌詞も、曲の長さから振った目安の時刻で同じように流す
                    u.lyrics.synced || u.lyrics.estimated -> SyncedLyrics(u.lyrics.lines, positionMs(sp, tick))
                    // 曲の長さが分からず時刻を振れなかったときだけ、並べて指でスクロールしてもらう
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 60.dp)) {
                        u.lyrics.lines.forEach { line ->
                            Text(line.text.ifEmpty { " " }, color = Color.White.copy(alpha = 0.85f), fontSize = 20.tu, lineHeight = 1.5.em)
                        }
                    }
                }
            }
        }
        // 出典は、端をぼかす範囲の外（欄の右下の外側）に
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
private fun LyricsNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 18.tu)
    }
}

/** 時刻付きの歌詞。行の位置を測っておき、いまの行の真ん中が欄の真ん中に来るよう全体をずらす。 */
@Composable
private fun SyncedLyrics(lines: List<LyricsRepository.Line>, position: Long) {
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
                val alpha by animateFloatAsState(if (on) 1f else if (i < current) 0.32f else 0.45f, tween(300), label = "lyricAlpha")
                Text(
                    line.text.ifEmpty { "♪" },
                    color = Color.White.copy(alpha = alpha),
                    fontSize = 23.tu,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                    lineHeight = 1.35.em,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp).onGloballyPositioned {
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

/** 下の真ん中の操作ボタンの列の幅の半分（ボタン 3 つ）。アーティスト名の欄はここまで空ける。 */
private val CONTROLS_HALF = 84.dp + 24.dp

private val CONTROL_HEIGHT = 48.dp

/** 触られないまま、操作ボタンなどを消すまでの時間。 */
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
    val bg by animateColorAsState(if (on) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.10f), tween(200), label = "awakeBg")
    val fg by animateColorAsState(if (on) Color(0xFF1B1F27) else Color.White.copy(alpha = 0.78f), tween(200), label = "awakeFg")
    Row(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, Color.White.copy(alpha = if (on) 0f else 0.35f), RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WdIcons.Sun, null, tint = fg, modifier = Modifier.size(15.dp))
        Text(
            if (on) L("暗くしない：オン", "Keep awake: on") else L("暗くしない：オフ", "Keep awake: off"),
            color = fg,
            fontSize = 11.tu,
            maxLines = 1,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

/** 操作ボタン。記号はアーティスト名の文字（18）と同じくらいの大きさ、押せる範囲は指の大きさ。 */
@Composable
private fun ControlButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp, CONTROL_HEIGHT).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

