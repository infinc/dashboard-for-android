package app.walldash.ui.dashboard

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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import app.walldash.data.SpotifyState
import app.walldash.ui.common.Tabular
import app.walldash.ui.common.WdIcons
import app.walldash.ui.theme.tu
import kotlinx.coroutines.delay

/**
 * Spotify の再生中の曲を画面いっぱいに出す。
 * 背景はジャケットの色から作り、中央にジャケット、左下に曲名とアーティスト名、下の真ん中に操作ボタン
 * （前の曲・再生／一時停止・次の曲。アーティスト名と同じくらいの大きさ）、右下にボタンと同じ高さ・大きさで再生時間。左上の「<」か端末の戻る操作で閉じる。
 * 右上の小さなボタンで「画面を暗くしない」を切り替える（[keepAwake]）。
 * 操作ボタン・右上のボタン・再生時間は、[IDLE_HIDE_MS] 触られなければ溶けるように消え、どこかに触れると戻る。
 */
@Composable
fun NowPlayingScreen(
    sp: SpotifyState?,
    album: Pair<String, ImageBitmap>?,
    now: Long,
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    onControl: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
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
    val base = remember(cover?.first) { cover?.second?.let(::coverColor) ?: FALLBACK }
    val top by animateColorAsState(base.shade(0.34f), tween(800), label = "top")
    val bottom by animateColorAsState(base.shade(0.17f), tween(800), label = "bottom")

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
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
        Crossfade(cover, Modifier.align(Alignment.Center), animationSpec = tween(500), label = "cover") { c ->
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

        // 戻る: 「<」だけ（押せる範囲は指の大きさにする）
        Box(
            Modifier.align(Alignment.TopStart).padding(14.dp).size(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WdIcons.Back, "戻る", tint = Color.White, modifier = Modifier.size(30.dp))
        }

        // 曲名は操作ボタンより上の行なので、右端まで 1 行で使う。右端まで届く長い曲名だけ折り返す（省略はしない）。
        // アーティスト名は操作ボタンと同じ高さなので、ボタンに重ならない幅まで
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 56.dp, end = 56.dp, bottom = 44.dp)) {
            Text(
                sp?.trackName ?: "再生中の曲はありません",
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
                ControlButton(WdIcons.Previous, "前の曲", shown) { onControl("previous") }
                if (sp.playing) ControlButton(WdIcons.Pause, "一時停止", shown) { onControl("pause") }
                else ControlButton(WdIcons.Play, "再生", shown) { onControl("play") }
                ControlButton(WdIcons.Next, "次の曲", shown) { onControl("next") }
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
            if (on) "暗くしない：オン" else "暗くしない：オフ",
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

private val FALLBACK = Color(0xFF3A4150)

/**
 * 同じ色合いのまま、明るさだけを [value]（HSV の V）にする。
 * 淡い色のジャケットでも色が分かるよう彩度を少し上げ、派手になりすぎないよう上限を設ける（灰色に近いものは灰色のまま）。
 */
private fun Color.shade(value: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(), hsv)
    val saturation = if (hsv[1] < 0.08f) hsv[1] else (hsv[1] * 1.5f).coerceIn(0.3f, 0.7f)
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], saturation, value)))
}

/**
 * ジャケットの代表色。縮めた画像の画素を色相で 12 に分け、鮮やかな画素の多い色相の平均をとる。
 * 白黒に近いジャケットは、全体の平均（ほぼ灰色）にする。
 */
private fun coverColor(image: ImageBitmap): Color = runCatching {
    val small = android.graphics.Bitmap.createScaledBitmap(image.asAndroidBitmap(), 32, 32, true)
    val pixels = IntArray(32 * 32)
    small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
    val weight = DoubleArray(12)
    val sum = Array(12) { DoubleArray(3) }
    val all = DoubleArray(3)
    val hsv = FloatArray(3)
    pixels.forEach { p ->
        val r = android.graphics.Color.red(p)
        val g = android.graphics.Color.green(p)
        val b = android.graphics.Color.blue(p)
        all[0] += r.toDouble(); all[1] += g.toDouble(); all[2] += b.toDouble()
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        if (hsv[1] < 0.25f || hsv[2] < 0.2f) return@forEach
        val bucket = (hsv[0] / 30f).toInt().coerceIn(0, 11)
        val w = (hsv[1] * hsv[2]).toDouble()
        weight[bucket] += w
        sum[bucket][0] += r * w; sum[bucket][1] += g * w; sum[bucket][2] += b * w
    }
    val best = weight.indices.maxBy { weight[it] }
    if (weight[best] < pixels.size * 0.02) {
        Color((all[0] / pixels.size).toInt(), (all[1] / pixels.size).toInt(), (all[2] / pixels.size).toInt())
    } else {
        val w = weight[best]
        Color((sum[best][0] / w).toInt(), (sum[best][1] / w).toInt(), (sum[best][2] / w).toInt())
    }
}.getOrDefault(FALLBACK)
