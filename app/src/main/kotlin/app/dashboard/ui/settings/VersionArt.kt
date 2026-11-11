package app.dashboard.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.dashboard.BuildConfig
import app.dashboard.i18n.L
import app.dashboard.ui.common.WdIcons
import app.dashboard.ui.theme.tu
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** バージョンの 3 つ目までの数（"v1.2.2-20261002" → "v1.2.2"）。 */
internal fun shortVersion(label: String): String =
    Regex("""v?\d+(\.\d+){0,2}""").find(label)?.value?.let { if (it.startsWith("v")) it else "v$it" } ?: label

private val RainBlue = Color(0xFFA9D8FF)
private val GlowCyan = Color(0xFF6FE3FF)
private val NightTop = Color(0xFF040814)
private val NightBottom = Color(0xFF102A4A)

/**
 * 雨の夜の街。にじんだ街の灯・斜めに降る雨・ビルの窓明かり・ときどき光る稲妻の上にバージョンを出し、
 * 手前のガラスには雨粒がついて、ときどき流れ落ちる。画面を押すと稲妻が光る。左上の「<」と戻る操作で閉じる。
 */
@Composable
internal fun VersionArt(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val version = remember { shortVersion(BuildConfig.VERSION_LABEL) }
    val scene = remember { RainScene() }
    var now by remember { mutableLongStateOf(0L) }
    var start by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos {
            if (start == 0L) start = it
            now = it
        }
    }
    val elapsed = (now - start) / 1e9f
    val flash = scene.flash(elapsed)

    Box(
        Modifier.fillMaxSize().background(NightTop)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { scene.strike(elapsed) },
    ) {
        Canvas(Modifier.fillMaxSize()) { scene.drawBack(this, elapsed) }

        // 開いた直後に、雨の向こうから浮かび上がる
        val intro = (elapsed / 1.6f).coerceIn(0f, 1f)
        val ease = 1f - (1f - intro) * (1f - intro)
        Column(
            Modifier.align(Alignment.Center).graphicsLayer {
                translationY = -size.height * 0.2f + (1f - ease) * 24.dp.toPx()
                alpha = ease
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "DASHBOARD",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 18.tu,
                fontWeight = FontWeight.Light,
                letterSpacing = 0.9.em,
                style = TextStyle(shadow = Shadow(GlowCyan, blurRadius = 12f)),
            )
            Text(
                version,
                fontSize = 128.tu,
                fontWeight = FontWeight.Thin,
                textAlign = TextAlign.Center,
                letterSpacing = 0.04.em,
                style = TextStyle(
                    brush = Brush.verticalGradient(
                        0f to Color.White,
                        0.6f to Color(0xFFCFEFFF),
                        1f to Color(0xFF7FB8E8),
                    ),
                    shadow = Shadow(GlowCyan.copy(alpha = 0.6f + 0.4f * flash), blurRadius = 30f + 40f * flash),
                ),
            )
            Text(
                "RAINY NIGHT",
                color = RainBlue.copy(alpha = 0.55f + 0.35f * sin(elapsed * 1.3f).let { it * it }),
                fontSize = 16.tu,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.7.em,
                style = TextStyle(shadow = Shadow(GlowCyan, blurRadius = 10f)),
            )
        }

        Canvas(Modifier.fillMaxSize()) { scene.drawFront(this, elapsed) }

        Box(
            Modifier.align(Alignment.TopStart).padding(14.dp).size(52.dp)
                .clip(RoundedCornerShape(26.dp)).background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WdIcons.Back, L("戻る", "Back"), tint = RainBlue, modifier = Modifier.size(30.dp))
        }
    }
}

/** 空・稲妻・ビル・にじんだ灯・雨（[drawBack]）と、ガラスの雨粒・稲妻の明るさ（[drawFront]）。形は開いたときに一度だけ決める。 */
private class RainScene {
    private class Streak(val x: Float, val speed: Float, val len: Float, val phase: Float, val alpha: Float)
    private class Bokeh(val x: Float, val y: Float, val r: Float, val color: Color, val phase: Float)
    private class Building(val x: Float, val w: Float, val h: Float)
    private class Drop(val x: Float, val r: Float, val period: Float, val phase: Float)

    private val rnd = Random(System.nanoTime())
    private val seed = rnd.nextInt(1000)

    private val streaks = List(150) {
        Streak(rnd.nextFloat() * 1.2f - 0.1f, 1.1f + rnd.nextFloat() * 0.9f, 0.04f + rnd.nextFloat() * 0.06f, rnd.nextFloat(), 0.15f + rnd.nextFloat() * 0.35f)
    }
    private val bokehColors = listOf(Color(0xFFFFB347), Color(0xFFFF6FA8), Color(0xFF6FE3FF), Color(0xFFFFF1C9), Color(0xFF9D8CFF))
    private val bokeh = List(34) {
        Bokeh(rnd.nextFloat(), 0.45f + rnd.nextFloat() * 0.4f, 18f + rnd.nextFloat() * 46f, bokehColors[rnd.nextInt(bokehColors.size)], rnd.nextFloat() * 6.28f)
    }
    private val buildings = buildList {
        var x = -0.02f
        while (x < 1.02f) {
            val bw = 0.035f + rnd.nextFloat() * 0.06f
            add(Building(x, bw, 0.1f + rnd.nextFloat() * 0.24f))
            x += bw + rnd.nextFloat() * 0.006f
        }
    }
    private val drops = List(26) { Drop(rnd.nextFloat(), 5f + rnd.nextFloat() * 9f, 5f + rnd.nextFloat() * 8f, rnd.nextFloat()) }

    // 稲妻（自動でときどき・押したとき）
    private var flashAt = -100f
    private var nextFlash = 3f + rnd.nextFloat() * 4f
    private var bolt: Path? = null

    fun strike(t: Float) {
        flashAt = t
        bolt = null
    }

    /** 稲妻の明るさ 0〜1（二度光ってから消える）。 */
    fun flash(t: Float): Float {
        if (t >= nextFlash) {
            strike(t)
            nextFlash = t + 6f + rnd.nextFloat() * 8f
        }
        val d = t - flashAt
        if (d < 0f || d > 1.5f) return 0f
        val first = exp(-d * 9f)
        val second = if (d > 0.18f) 0.8f * exp(-(d - 0.18f) * 6f) else 0f
        return (first + second).coerceAtMost(1f)
    }

    // ビルと窓は大きさが決まったら一度だけ作る
    private var cachedSize = Size.Zero
    private var skyline = Path()
    private var windows = Path()
    private var flickers: List<Rect> = emptyList()

    private fun buildSkyline(w: Float, h: Float, density: Float) {
        cachedSize = Size(w, h)
        skyline = Path()
        windows = Path()
        val flick = mutableListOf<Rect>()
        val cell = 11f * density
        val win = 4.5f * density
        buildings.forEachIndexed { b, bd ->
            val left = bd.x * w
            val top = h - bd.h * h
            skyline.addRect(Rect(left, top, left + bd.w * w, h))
            val cols = ((bd.w * w - cell * 0.5f) / cell).toInt()
            val rows = ((bd.h * h - cell) / cell).toInt()
            for (r in 0 until rows) for (c in 0 until cols) {
                val v = hash(b * 131 + r * 17 + c, seed)
                if (v > 0.24f) continue
                val rect = Rect(left + cell * 0.6f + c * cell, top + cell * 0.8f + r * cell, left + cell * 0.6f + c * cell + win, top + cell * 0.8f + r * cell + win * 1.3f)
                if (v < 0.025f && flick.size < 30) flick += rect else windows.addRect(rect)
            }
        }
        flickers = flick
    }

    fun drawBack(scope: DrawScope, t: Float) = with(scope) {
        val w = size.width
        val h = size.height
        if (cachedSize != size) buildSkyline(w, h, density)
        val f = flash(t)

        // 空（稲妻のときは明るくなる）
        drawRect(Brush.verticalGradient(0f to NightTop, 1f to NightBottom))
        if (f > 0f) drawRect(Brush.verticalGradient(0f to Color(0xFFB8D4FF).copy(alpha = 0.45f * f), 1f to Color(0xFF5A7FB8).copy(alpha = 0.2f * f)))

        // 稲妻の筋
        if (f > 0.05f) {
            val path = bolt ?: makeBolt(w, h * 0.68f).also { bolt = it }
            drawPath(path, GlowCyan.copy(alpha = 0.25f * f), style = Stroke(12f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, Color.White.copy(alpha = f), style = Stroke(2.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // にじんだ街の灯（ガラス越しのぼけ）
        bokeh.forEach { b ->
            val cx = b.x * w + sin(t * 0.15f + b.phase) * w * 0.01f
            val cy = b.y * h
            val r = b.r * density
            val a = 0.18f + 0.12f * sin(t * 0.8f + b.phase)
            drawCircle(
                Brush.radialGradient(0f to b.color.copy(alpha = a), 0.75f to b.color.copy(alpha = a * 0.8f), 1f to Color.Transparent, center = Offset(cx, cy), radius = r),
                r, Offset(cx, cy),
            )
        }

        // ビルの影と窓明かり（いくつかはときどき消えたりつく）
        drawPath(skyline, Color(0xFF050A16))
        if (f > 0f) drawPath(skyline, Color(0xFF2A3C5C).copy(alpha = 0.6f * f))
        drawPath(windows, Color(0xFFFFD58A).copy(alpha = 0.75f))
        flickers.forEachIndexed { i, r ->
            if (hash(i, floor(t / 2.5f + i * 0.37f).toInt()) > 0.4f) drawRect(Color(0xFFFFE6B0), r.topLeft, r.size)
        }
        // 足もとの濡れた路面の照り返し
        drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xFFFFB347).copy(alpha = 0.12f), startY = h * 0.85f, endY = h), Offset(0f, h * 0.85f), Size(w, h * 0.15f))

        // 斜めに降る雨（風で少し揺れる）
        val wind = 0.12f + 0.05f * sin(t * 0.4f)
        streaks.forEach { s ->
            val len = s.len * h
            val y = ((t * s.speed * 0.6f + s.phase) % 1f) * (h + len) - len
            val x = s.x * w + y * wind
            drawLine(RainBlue.copy(alpha = s.alpha + 0.3f * f), Offset(x, y), Offset(x + len * wind, y + len), strokeWidth = 1.2f * density, cap = StrokeCap.Round)
        }
    }

    fun drawFront(scope: DrawScope, t: Float) = with(scope) {
        val w = size.width
        val h = size.height
        // ガラスの雨粒（しばらく止まってから流れ落ち、別の場所にまたつく）
        drops.forEachIndexed { i, d ->
            val cycleF = t / d.period + d.phase
            val cycle = floor(cycleF).toInt()
            val p = cycleF - cycle
            val x = (d.x + hash(i, cycle) * 0.3f) % 1f * w
            val y0 = hash(i + 50, cycle) * h * 0.7f
            val slide = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
            val y = y0 + slide * slide * h * 0.9f
            val r = d.r * density * (1f - 0.35f * slide)
            val fade = if (p < 0.08f) p / 0.08f else 1f
            if (slide > 0f) {
                // 流れた跡は粒のすぐ上だけ（長く残すと縦の線に見える）
                val trailTop = maxOf(y0, y - 70f * density)
                drawLine(
                    Brush.verticalGradient(0f to Color.Transparent, 1f to Color.White.copy(alpha = 0.1f * fade), startY = trailTop, endY = y),
                    Offset(x, trailTop), Offset(x, y), strokeWidth = r * 0.5f, cap = StrokeCap.Round,
                )
            }
            drawCircle(
                Brush.radialGradient(0f to Color.White.copy(alpha = 0.03f), 0.75f to Color.White.copy(alpha = 0.14f * fade), 1f to Color.Transparent, center = Offset(x, y), radius = r),
                r, Offset(x, y),
            )
            drawCircle(Color.White.copy(alpha = 0.55f * fade), r * 0.22f, Offset(x - r * 0.35f, y - r * 0.35f))
        }
        // 稲妻のときは画面全体も一瞬白く
        val f = flash(t)
        if (f > 0f) drawRect(Color.White.copy(alpha = 0.18f * f))
    }

    private fun makeBolt(w: Float, bottom: Float): Path = Path().apply {
        var x = w * (0.15f + rnd.nextFloat() * 0.7f)
        var y = 0f
        moveTo(x, y)
        var branchAt = 0.3f + rnd.nextFloat() * 0.3f
        while (y < bottom) {
            y += bottom * (0.04f + rnd.nextFloat() * 0.05f)
            x += (rnd.nextFloat() - 0.5f) * w * 0.05f
            lineTo(x, y)
            if (y > bottom * branchAt) {
                branchAt = 2f
                var bx = x
                var by = y
                repeat(5) {
                    bx += (if (rnd.nextBoolean()) 1 else -1) * w * (0.01f + rnd.nextFloat() * 0.03f)
                    by += bottom * 0.05f
                    lineTo(bx, by)
                }
                moveTo(x, y)
            }
        }
    }

    private fun hash(a: Int, b: Int): Float {
        val v = sin(a * 12.9898f + b * 78.233f) * 43758.547f
        return v - floor(v)
    }
}
