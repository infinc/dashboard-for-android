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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.withFrameNanos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Spotify の全画面の背景。色はどれもジャケットに使われている色（[coverPalette]）から取り、曲が変わると色だけがゆっくり移る。
 * [style] は設定の `spotify.background`:
 * - "still": 動かない。いちばん暗い色の地に、いちばん多い色をほんの少しだけ斜めに重ねる
 * - "flow": 大きな光の玉を何個か重ね、それぞれをゆっくり、決まった周期を持たずに漂わせる（[wander]）
 * - "spike": 上下から伸びる尖った光（三角形）を重ね、ぼかして揺らす（[spikes]）
 * 曲名などの白い文字が読めるよう、色は明るさを抑え、下に向かって少し暗くする。
 */
@Composable
fun Modifier.coverGradient(palette: List<Color>, style: String = "flow"): Modifier {
    // 開くたびに違う動きにする（同じ動きを繰り返さない）
    val seed = remember { Random.nextInt() }
    val start = remember { Random.nextFloat() * 1000f }
    val time = remember { mutableFloatStateOf(start) }
    val moving = style != "still"
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
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
    val count = if (style == "spike") SPIKES else BLOBS
    val colors = List(count) { i ->
        val tones = if (style == "spike") SPIKE_TONES else BLOB_TONES
        animateColorAsState(palette[i % palette.size].tone(tones[i]), tween(COLOR_MS), label = "grad$i")
    }
    val canvas = remember { SpikeCanvas() }
    return drawBehind {
        drawRect(bg)
        when (style) {
            "still" -> drawRect(
                Brush.linearGradient(
                    0f to colors[0].value.copy(alpha = 0.30f),
                    1f to Color.Transparent,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
            "spike" -> spikes(canvas, bg, colors, seed, time.floatValue)
            else -> colors.forEachIndexed { i, c -> blob(c, seed + i * 7919, time.floatValue) }
        }
        // 下ほど暗く（左下の曲名・下の操作ボタンを読みやすく）
        drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.06f), 0.55f to Color.Black.copy(alpha = 0.16f), 1f to Color.Black.copy(alpha = 0.50f)))
    }
}

/**
 * 尖った光の背景の下書き。小さな画像（横 [SPIKE_W] 画素）に三角形を描いてぼかし、画面いっぱいに引き伸ばす。
 * 引き伸ばすことで、ぼかしの重い処理をせずに「ぼやけてはいるが尖っている」形になり、古い Android でも同じに見える。
 * 画像と作業用の配列は使い回す。
 */
private class SpikeCanvas {
    var bitmap: android.graphics.Bitmap? = null
    var image: ImageBitmap? = null
    var pixels = IntArray(0)
    var work = IntArray(0)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val path = android.graphics.Path()

    fun ensure(w: Int, h: Int) {
        if (bitmap?.width == w && bitmap?.height == h) return
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap = b
        image = b.asImageBitmap()
        pixels = IntArray(w * h)
        work = IntArray(w * h)
    }
}

/**
 * 尖った光。下から上へ・上から下へ伸びる三角形を交互に重ねる。根元の位置・幅、先の高さ・左右の傾きを [wander] で揺らし、
 * 根元から先へ少し薄くする。描いたら横に強く・縦に弱くぼかし（[boxBlur]）、画面へ引き伸ばす。
 */
private fun DrawScope.spikes(canvas: SpikeCanvas, bg: Color, colors: List<State<Color>>, seed: Int, t: Float) {
    if (size.width <= 0f || size.height <= 0f) return
    val w = SPIKE_W
    val h = (w * size.height / size.width).toInt().coerceIn(16, 160)
    canvas.ensure(w, h)
    val bitmap = canvas.bitmap ?: return
    val c = android.graphics.Canvas(bitmap)
    c.drawColor(bg.toArgb())
    val paint = canvas.paint
    val path = canvas.path
    colors.forEachIndexed { i, state ->
        val s = seed + i * 7919
        val up = i % 2 == 0
        // 根元は横に散らし（等間隔に見えないよう少しずらす）、そこから揺らす。前に描くものほど細く
        val home = (i + 0.5f + 0.35f * knot(s, 0)) / colors.size
        val baseX = w * (home + 0.09f * wander(s, t / 11f) + 0.03f * wander(s + 1, t / 3.7f))
        val thin = 1f - 0.55f * i / colors.size
        val half = w * thin * (0.13f + 0.05f * knot(s, 1) + 0.04f * wander(s + 2, t / 7.3f))
        val tipX = baseX + w * (0.06f * wander(s + 3, t / 5.1f) + 0.025f * wander(s + 4, t / 1.9f))
        val reach = h * (0.70f + 0.18f * knot(s, 2) + 0.16f * wander(s + 5, t / 6.1f) + 0.05f * wander(s + 6, t / 2.3f))
        val baseY = if (up) h.toFloat() else 0f
        val tipY = if (up) h - reach else reach
        path.rewind()
        path.moveTo(baseX - half, baseY)
        path.lineTo(baseX + half, baseY)
        path.lineTo(tipX, tipY)
        path.close()
        val color = state.value
        paint.shader = android.graphics.LinearGradient(
            baseX, baseY, tipX, tipY,
            color.toArgb(), color.copy(alpha = 0.55f).toArgb(),
            android.graphics.Shader.TileMode.CLAMP,
        )
        c.drawPath(path, paint)
    }
    paint.shader = null
    bitmap.getPixels(canvas.pixels, 0, w, 0, 0, w, h)
    boxBlur(canvas.pixels, canvas.work, w, h, 2, horizontal = true)
    boxBlur(canvas.work, canvas.pixels, w, h, 1, horizontal = false)
    bitmap.setPixels(canvas.pixels, 0, w, 0, 0, w, h)
    val image = canvas.image ?: return
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(w, h),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        filterQuality = FilterQuality.Low,
    )
}

/** 半径 [r] の箱ぼかしを、横か縦の 1 方向だけ掛ける（端は端の画素を伸ばす）。[src] から [dst] へ。 */
private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val n = 2 * r + 1
    for (line in 0 until lines) {
        val at = { k: Int -> if (horizontal) line * w + k.coerceIn(0, len - 1) else k.coerceIn(0, len - 1) * w + line }
        var a = 0; var rr = 0; var g = 0; var b = 0
        for (k in -r..r) {
            val p = src[at(k)]
            a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (k in 0 until len) {
            dst[at(k)] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            val out = src[at(k - r)]
            val inn = src[at(k + r + 1)]
            a += (inn ushr 24) - (out ushr 24)
            rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            b += (inn and 0xFF) - (out and 0xFF)
        }
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

/** 尖った光の数と、それぞれの明るさ。下書きの画像の横の画素数。 */
private const val SPIKES = 11
private val SPIKE_TONES = floatArrayOf(0.78f, 0.70f, 0.82f, 0.62f, 0.74f, 0.66f, 0.80f, 0.60f, 0.72f, 0.76f, 0.64f)
private const val SPIKE_W = 160

/** 曲が変わったときに色が移る時間。 */
private const val COLOR_MS = 1500
