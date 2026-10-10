package app.dashboard.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.dashboard.BuildConfig
import app.dashboard.i18n.L
import app.dashboard.ui.common.WdIcons
import app.dashboard.ui.theme.tu
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** バージョンの 3 つ目までの数（"v1.2.2-20261002" → "v1.2.2"）。 */
internal fun shortVersion(label: String): String =
    Regex("""v?\d+(\.\d+){0,2}""").find(label)?.value?.let { if (it.startsWith("v")) it else "v$it" } ?: label

private val NeonPink = Color(0xFFFF3DCB)
private val NeonCyan = Color(0xFF3DF2FF)
private val SunTop = Color(0xFFFFE36B)
private val SunBottom = Color(0xFFFF2E88)

/** 夕焼けの空・縞の入った太陽・手前へ流れるネオンの格子の上に、バージョンを出す。左上の「<」と戻る操作で閉じる。 */
@Composable
internal fun VersionArt(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val version = remember { shortVersion(BuildConfig.VERSION_LABEL) }
    val scene = remember { SunsetScene() }
    var now by remember { mutableLongStateOf(0L) }
    var start by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos {
            if (start == 0L) start = it
            now = it
        }
    }
    val elapsed = (now - start) / 1e9f
    val dropPx = with(LocalDensity.current) { 7.dp.toPx() }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF07011A))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Canvas(Modifier.fillMaxSize()) { scene.draw(this, elapsed) }

        // 開いた直後に上から落ちてきて、少し弾んで止まる
        val intro = (elapsed / 1.1f).coerceIn(0f, 1f)
        val bounce = 1f - (1f - intro) * (1f - intro) * (1f - intro)
        Column(
            Modifier.align(Alignment.Center).graphicsLayer {
                translationY = -size.height * 0.26f - (1f - bounce) * size.height * 0.6f
                alpha = intro
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text(
                "Dashboard",
                color = NeonPink,
                fontSize = 46.tu,
                fontFamily = FontFamily.Cursive,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.graphicsLayer { rotationZ = -6f; translationY = 18.dp.toPx() },
                style = TextStyle(shadow = Shadow(NeonPink, blurRadius = 28f)),
            )
            Text(
                version,
                fontSize = 120.tu,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                letterSpacing = 0.02.em,
                style = TextStyle(
                    // クロームの文字（上は空の色、真ん中に地平線、下は夕焼けの色）
                    brush = Brush.verticalGradient(
                        0f to Color(0xFFF4FBFF),
                        0.45f to Color(0xFF7FD8FF),
                        0.5f to Color(0xFF2A1250),
                        0.56f to Color(0xFFB04FD8),
                        1f to Color(0xFFFFC2EC),
                    ),
                    shadow = Shadow(Color(0xFF1B0638), Offset(0f, dropPx), blurRadius = 2f),
                ),
            )
            val blink = (elapsed * 1.6f).toInt() % 2 == 0
            Text(
                "NIGHT DRIVE",
                color = NeonCyan.copy(alpha = if (blink) 0.95f else 0.35f),
                fontSize = 18.tu,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.8.em,
                style = TextStyle(shadow = Shadow(NeonCyan, blurRadius = 14f)),
            )
        }
        Box(
            Modifier.align(Alignment.TopStart).padding(14.dp).size(52.dp)
                .clip(RoundedCornerShape(26.dp)).background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WdIcons.Back, L("戻る", "Back"), tint = NeonPink, modifier = Modifier.size(30.dp))
        }
    }
}

/** 空・星・太陽・山なみ・格子。星と山の形は開いたときに一度だけ決める。 */
private class SunsetScene {
    private class Star(val x: Float, val y: Float, val r: Float, val phase: Float)

    private val rnd = Random(System.nanoTime())
    private val stars = List(140) { Star(rnd.nextFloat(), rnd.nextFloat(), 0.6f + rnd.nextFloat() * 1.4f, rnd.nextFloat() * 6.28f) }
    private val ridge = List(24) { 0.25f + rnd.nextFloat() * 0.75f }

    fun draw(scope: DrawScope, t: Float) = with(scope) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.62f
        val cx = w / 2

        // 空
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF07011A), 0.45f to Color(0xFF2A0B4F), 0.85f to Color(0xFF7A1B6E), 1f to Color(0xFFFF4F8B),
                endY = horizon,
            ),
            size = Size(w, horizon),
        )
        // 星（上のほうだけ、ゆっくりまたたく）
        stars.forEach { s ->
            val y = s.y * horizon * 0.7f
            val a = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 1.7f + s.phase))
            drawCircle(Color.White.copy(alpha = a * (1f - y / horizon)), s.r * density, Offset(s.x * w, y))
        }

        // 太陽の後ろの光
        val r = minOf(w, h) * 0.3f
        val sunCenter = Offset(cx, horizon - r * 0.15f)
        drawCircle(
            Brush.radialGradient(0f to SunBottom.copy(alpha = 0.45f), 1f to Color.Transparent, center = sunCenter, radius = r * 2f),
            r * 2f, sunCenter,
        )
        // 太陽（下半分に、上へ流れていく縞のすき間を入れる）
        clipRect(bottom = horizon) {
            drawIntoCanvas { c ->
                val bounds = Rect(sunCenter.x - r, sunCenter.y - r, sunCenter.x + r, sunCenter.y + r)
                c.saveLayer(bounds, Paint())
                drawCircle(Brush.verticalGradient(0f to SunTop, 1f to SunBottom, startY = bounds.top, endY = bounds.bottom), r, sunCenter)
                val bands = 7
                val step = r / bands
                val stripeTop = sunCenter.y - r * 0.45f
                val shift = (t * 0.35f % 1f) * step
                for (i in 0..bands + 1) {
                    val y = stripeTop + i * step - shift
                    if (y < stripeTop - step * 0.5f) continue
                    val k = ((y - stripeTop) / (horizon - stripeTop)).coerceIn(0f, 1f)
                    drawRect(Color.Black, Offset(bounds.left, y), Size(r * 2, step * (0.12f + 0.55f * k)), blendMode = BlendMode.DstOut)
                }
                c.restore()
            }
        }

        // 山なみ（地平線のすぐ上の影）
        val mountains = Path().apply {
            moveTo(0f, horizon)
            ridge.forEachIndexed { i, v ->
                val x = w * i / (ridge.size - 1)
                val edge = 1f - sin(PI.toFloat() * i / (ridge.size - 1)) * 0.85f
                lineTo(x, horizon - h * 0.11f * v * edge)
            }
            lineTo(w, horizon)
            close()
        }
        drawPath(mountains, Brush.verticalGradient(0f to Color(0xFF3B0E5E), 1f to Color(0xFF12032A), startY = horizon - h * 0.11f, endY = horizon))

        // 地面
        drawRect(
            Brush.verticalGradient(0f to Color(0xFF1A0436), 1f to Color(0xFF05000F), startY = horizon, endY = h),
            Offset(0f, horizon), Size(w, h - horizon),
        )
        val ground = h - horizon
        // 横の線（遠いほど詰まり、手前へ流れてくる）
        val phase = t * 0.9f % 1f
        for (i in 0..18) {
            val z = i + 1f - phase
            if (z < 1f) continue
            val y = horizon + ground / z
            val a = (1f - i / 18f).let { it * it * it }
            drawLine(NeonPink.copy(alpha = 0.08f + 0.85f * a), Offset(0f, y), Offset(w, y), strokeWidth = (1f + 2f * a) * density)
        }
        // 縦の線（地平線の真ん中へ集まる）
        for (j in -24..24) {
            val bottomX = cx + j * w * 0.09f
            drawLine(
                Brush.verticalGradient(0f to Color.Transparent, 0.25f to NeonPink.copy(alpha = 0.4f), 1f to NeonPink, startY = horizon, endY = h),
                Offset(cx + j * w * 0.004f, horizon), Offset(bottomX, h), strokeWidth = 1.5f * density,
            )
        }
        // 地平線の光
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color(0xFFFF7AD9).copy(alpha = 0.7f), 1f to Color.Transparent,
                startY = horizon - 6 * density, endY = horizon + 6 * density),
            Offset(0f, horizon - 6 * density), Size(w, 12 * density),
        )
    }
}
