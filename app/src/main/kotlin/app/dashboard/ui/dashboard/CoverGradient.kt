package app.dashboard.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.withFrameNanos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Spotify の全画面の背景。ジャケットに使われている色（[coverPalette]）の大きな光の玉を何個か重ね、
 * それぞれをゆっくり、決まった周期を持たずに漂わせる（[wander]）。曲が変わると色だけがゆっくり移る。
 * 曲名などの白い文字が読めるよう、色は明るさを抑え、下に向かって少し暗くする。
 */
@Composable
fun Modifier.coverGradient(palette: List<Color>): Modifier {
    // 開くたびに違う動きにする（同じ動きを繰り返さない）
    val seed = remember { Random.nextInt() }
    val start = remember { Random.nextFloat() * 1000f }
    val time = remember { mutableFloatStateOf(start) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last == 0L) last = t
                // 古いタブレットでも重くならないよう、およそ 30 コマ／秒で進める
                if (t - last >= 33_000_000L) {
                    time.floatValue += (t - last) / 1e9f
                    last = t
                }
            }
        }
    }
    // 地はジャケットのいちばん暗い色（黒や紺が多いジャケットなら暗い紺の地になる）
    val bg by animateColorAsState(palette.minBy { it.luminance() }.tone(0.24f), tween(COLOR_MS), label = "gradBg")
    val blobs = List(BLOBS) { i ->
        animateColorAsState(palette[i % palette.size].tone(BLOB_TONES[i]), tween(COLOR_MS), label = "grad$i")
    }
    return drawBehind {
        drawRect(bg)
        val t = time.floatValue
        blobs.forEachIndexed { i, c -> blob(c, seed + i * 7919, t) }
        // 下ほど暗く（左下の曲名・下の操作ボタンを読みやすく）
        drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.06f), 0.55f to Color.Black.copy(alpha = 0.16f), 1f to Color.Black.copy(alpha = 0.50f)))
    }
}

/** 光の玉を 1 つ。位置と大きさを [wander] で漂わせる。 */
private fun DrawScope.blob(color: State<Color>, seed: Int, t: Float) {
    val c = color.value
    val big = max(size.width, size.height)
    val x = size.width * (0.5f + 0.55f * wander(seed, t / 9f) + 0.12f * wander(seed + 1, t / 3.1f))
    val y = size.height * (0.5f + 0.6f * wander(seed + 2, t / 8f) + 0.12f * wander(seed + 3, t / 2.7f))
    val r = big * (0.42f + 0.14f * wander(seed + 4, t / 6.3f))
    val center = Offset(x, y)
    drawCircle(
        Brush.radialGradient(
            0f to c,
            0.45f to c.copy(alpha = 0.6f),
            1f to c.copy(alpha = 0f),
            center = center,
            radius = r,
        ),
        r,
        center,
    )
}

/**
 * -1..1 をなめらかにさまよう値。整数ごとに乱数の点を置き、Catmull-Rom でつなぐ（途中で止まらず、周期も無い）。
 */
private fun wander(seed: Int, t: Float): Float {
    val i = floor(t).toInt()
    val f = t - i
    val p0 = knot(seed, i - 1)
    val p1 = knot(seed, i)
    val p2 = knot(seed, i + 1)
    val p3 = knot(seed, i + 2)
    val v = 0.5f * (2 * p1 + (-p0 + p2) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f * f + (-p0 + 3 * p1 - 3 * p2 + p3) * f * f * f)
    return v.coerceIn(-1f, 1f)
}

/** [seed] と [i] から決まる -1..1 の乱数（同じ組なら同じ値）。 */
private fun knot(seed: Int, i: Int): Float {
    var h = seed * 374761393 + i * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 32767.5f - 1f
}

/**
 * 色合いは保ったまま、明るさをおよそ [value] に、彩度をほどほどにそろえる（灰色に近いものは灰色のまま）。
 * 元の色が暗いほど少し暗めにして、暗いジャケットは暗い雰囲気のままにする。
 */
private fun Color.tone(value: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(), hsv)
    val saturation = if (hsv[1] < 0.08f) hsv[1] else (hsv[1] * 1.4f).coerceIn(0.35f, 0.85f)
    val v = value * (0.7f + 0.3f * (hsv[2] * 2.5f).coerceAtMost(1f))
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], saturation, v)))
}

/**
 * ジャケットに使われている色（多い順に最大 5 色）。縮めた画像の画素を色相で 12 に分け、画素の多い色相から順に平均をとる。
 * 暗い紺や肌色のようなくすんだ色も拾う（真っ黒・灰色に近い画素だけ除く）。重みは彩度の平方根と明るさで、暗い画素は軽くする。
 * 色の少ないジャケットは、見つかった色の明るさ違い・少し色相をずらした色で埋める。白黒に近いジャケットは灰色の濃淡にする。
 */
fun coverPalette(image: ImageBitmap?): List<Color> = runCatching {
    image ?: return@runCatching FALLBACK_PALETTE
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
        if (hsv[1] < 0.12f || hsv[2] < 0.05f) return@forEach
        val bucket = (hsv[0] / 30f).toInt().coerceIn(0, 11)
        val w = sqrt(hsv[1].toDouble()) * (hsv[2] * 3.0 + 0.15).coerceAtMost(1.0)
        weight[bucket] += w
        sum[bucket][0] += r * w; sum[bucket][1] += g * w; sum[bucket][2] += b * w
    }
    val found = weight.indices
        .filter { weight[it] >= pixels.size * 0.015 }
        .sortedByDescending { weight[it] }
        .take(5)
        .map { Color((sum[it][0] / weight[it]).toInt(), (sum[it][1] / weight[it]).toInt(), (sum[it][2] / weight[it]).toInt()) }
    if (found.isEmpty()) {
        val gray = Color((all[0] / pixels.size).toInt(), (all[1] / pixels.size).toInt(), (all[2] / pixels.size).toInt())
        return@runCatching listOf(gray, gray.hueShift(0f, 1.25f), gray.hueShift(0f, 0.8f))
    }
    // 色が 3 つに満たなければ、いちばん多い色の色相を少しずらして足す
    found + listOf(found[0].hueShift(-24f, 1.15f), found[0].hueShift(24f, 0.85f)).take(max(0, 3 - found.size))
}.getOrDefault(FALLBACK_PALETTE)

/** 色相を [degrees] 回し、明るさを [scale] 倍にした色。 */
private fun Color.hueShift(degrees: Float, scale: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(), hsv)
    val h = ((hsv[0] + degrees) % 360f + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(h, hsv[1], (hsv[2] * scale).coerceIn(0f, 1f))))
}

/** ジャケットが無いときの色。 */
private val FALLBACK_PALETTE = listOf(Color(0xFF3A4150), Color(0xFF2E3A52), Color(0xFF4A3F5C))

/** 光の玉の数と、それぞれの明るさ（HSV の V）。 */
private const val BLOBS = 5
private val BLOB_TONES = floatArrayOf(0.80f, 0.66f, 0.74f, 0.60f, 0.76f)

/** 曲が変わったときに色が移る時間。 */
private const val COLOR_MS = 1500
