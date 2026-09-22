package app.walldash.ui.dashboard

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.boundingRect
import androidx.compose.ui.geometry.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.random.Random

/*
 * 画面下の回し車とハムスター。
 * 意匠は Uiverse.io の Nawsome 作「Loader」（MIT License）。形・色・動きの値は元の CSS のまま。
 * 動きは 4 つだけ: 走る・休む（車の中）／歩く・立ち止まる（車の外）。外を歩き終えたら必ず車へ戻る。
 */

private enum class Mode { RUN, REST, WALK, STAND }

private val ORANGE = Color(0xFFF38C25)
private val ORANGE_LIGHT = Color(0xFFFACC9E)
private val CREAM = Color(0xFFFCE6CF)
private val PINK = Color(0xFFFBB6B6)
private val PINK_DARK = Color(0xFFF98686)
private val WHEEL = Color(0xFF999999)
private val SPOKE = Color(0xFFA6A6A6)
private val EASE = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** 車の中にいるときの位置（px）。CSS の translate(-0.8em, 1.85em) に当たる。 */
private const val BASE_X = -5.2f
private const val BASE_Y = 13f
private const val WALK_SPEED = 32f

@Composable
fun Hamster(modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf(Mode.RUN) }
    var x by remember { mutableFloatStateOf(0f) }
    var dir by remember { mutableIntStateOf(-1) }
    var time by remember { mutableLongStateOf(0L) }
    val width = LocalConfiguration.current.screenWidthDp.toFloat()
    val rest by animateFloatAsState(if (mode == Mode.REST) 1f else 0f, tween(800), label = "rest")

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { if (it - last >= 33) { time = it; last = it } }
        }
    }

    LaunchedEffect(width) {
        val lo = -(width / 2 - 70)
        val hi = width / 2 - 250
        suspend fun walkTo(target: Float) {
            mode = Mode.WALK
            dir = if (target < x) -1 else 1
            var prev = withFrameMillis { it }
            while (abs(target - x) > 0.5f) {
                val now = withFrameMillis { it }
                val step = WALK_SPEED * ((now - prev).coerceAtMost(100) / 1000f)
                prev = now
                x = if (abs(target - x) <= step) target else x + dir * step
            }
        }
        fun spot(): Float {
            repeat(6) {
                val c = lo + Random.nextFloat() * (hi - lo)
                if (abs(c - x) > 90) return c
            }
            return if (x > (lo + hi) / 2) lo else hi
        }
        x = 0f
        while (true) {
            mode = Mode.RUN
            delay(Random.nextLong(18_000, 40_000))
            if (Random.nextBoolean()) {
                mode = Mode.REST
                delay(Random.nextLong(10_000, 24_000))
            } else {
                repeat(2 + Random.nextInt(3)) {
                    walkTo(spot())
                    mode = Mode.STAND
                    delay(Random.nextLong(2_500, 6_500))
                }
                walkTo(0f)
            }
        }
    }

    Canvas(modifier.size(78.dp)) {
        drawHamster(time, mode, x, dir, rest)
    }
}

/** キーフレームが 12.5% ごとに A と B を行き来する動き。[eased] は ease-in-out。 */
private fun swing(t: Long, periodMs: Long, a: Float, b: Float, eased: Boolean): Float {
    val sub = (t % periodMs).toFloat() / periodMs * 8f % 2f
    val f = if (sub < 1f) sub else 2f - sub
    return a + (b - a) * (if (eased) EASE.transform(f) else f)
}

private fun DrawScope.drawHamster(t: Long, mode: Mode, x: Float, dir: Int, rest: Float) {
    val em = size.width / 12f
    val outside = mode == Mode.WALK || mode == Mode.STAND
    val dur = if (mode == Mode.WALK) 1700L else 1000L
    val moving = mode == Mode.RUN || mode == Mode.WALK

    val spokeTurn = if (mode == Mode.RUN) -360f * (t % 1000L) / 1000f else 0f
    if (!outside) {
        spoke(em, spokeTurn)
        hamster(t, mode, dur, moving, em, x, dir, rest)
        wheel(em)
    } else {
        spoke(em, 0f)
        wheel(em)
        hamster(t, mode, dur, moving, em, x, dir, rest)
    }
}

private fun DrawScope.wheel(em: Float) {
    drawCircle(WHEEL, 5.875f * em, center, style = Stroke(0.25f * em))
}

private fun DrawScope.spoke(em: Float, turn: Float) {
    rotate(turn, center) {
        clipPath(Path().apply { addOval(Rect(center, 5.94f * em)) }) {
            drawRect(SPOKE, Offset(center.x - 5.94f * em, center.y - 0.355f * em), androidx.compose.ui.geometry.Size(11.88f * em, 0.71f * em))
        }
        drawCircle(WHEEL, 0.576f * em, center)
    }
}

private fun DrawScope.hamster(t: Long, mode: Mode, dur: Long, moving: Boolean, em: Float, x: Float, dir: Int, rest: Float) {
    val boxX = 2.5f * em
    val boxY = 6f * em
    val originX = 3.5f * em
    val outside = mode == Mode.WALK || mode == Mode.STAND
    val walkX = (BASE_X + x).dp.toPx()
    val walkY = BASE_Y.dp.toPx()
    withTransform({
        translate(boxX + originX, boxY)
        if (outside) {
            translate(walkX, walkY)
            scale(if (dir > 0) -1f else 1f, 1f, Offset.Zero)
        } else {
            val half = (t % 1000L) / 1000f * 2f
            val running = 4f + (0f - 4f) * EASE.transform(if (half < 1) half else 2 - half)
            rotate(running * (1 - rest), Offset.Zero)
            translate(-0.8f * em, (1.85f + 0.3f * rest) * em)
        }
        translate(-originX, 0f)
    }) {
        body(t, mode, dur, moving, em)
    }
}

private fun DrawScope.body(t: Long, mode: Mode, dur: Long, moving: Boolean, em: Float) {
    fun limb(runA: Float, runB: Float, rested: Float, standing: Float): Float = when (mode) {
        Mode.REST -> rested
        Mode.STAND -> standing
        else -> swing(t, dur, runA, runB, eased = false)
    }
    val bodyRot = if (moving) swing(t, dur, 0f, -2f, eased = true) else 0f
    val breath = if (moving) 0f else {
        val half = (t % 2800L) / 2800f * 2f
        EASE.transform(if (half < 1) half else 2 - half)
    }
    val pivot = Offset(0.765f * em, 1.5f * em)

    withTransform({
        translate(2f * em, 0.25f * em)
        rotate(bodyRot, pivot)
        scale(1f + 0.02f * breath, 1f + 0.05f * breath, pivot)
    }) {
        frontLimb(em, limb(50f, -30f, 18f, 10f), ORANGE_LIGHT, PINK_DARK)
        backLimb(em, limb(-60f, 20f, -18f, -12f), ORANGE_LIGHT, PINK_DARK)
        tail(em, if (moving) swing(t, dur, 30f, 10f, eased = false) else 18f)

        val bodyBox = RoundRect(
            Rect(0f, 0f, 4.5f * em, 3f * em),
            topLeft = CornerRadius(0.5f * 4.5f * em, 0.15f * 3f * em),
            topRight = CornerRadius(0.3f * 4.5f * em, 0.6f * 3f * em),
            bottomRight = CornerRadius(0.5f * 4.5f * em, 0.4f * 3f * em),
            bottomLeft = CornerRadius(0.3f * 4.5f * em, 0.4f * 3f * em),
        )
        shape(bodyBox, CREAM, listOf(Triple(0.1f * em, 0.75f * em, ORANGE), Triple(0.15f * em, -0.5f * em, ORANGE_LIGHT)))

        head(t, dur, moving, em)
        frontLimb(em, limb(-30f, 50f, 22f, 14f), CREAM, PINK)
        backLimb(em, limb(20f, -60f, -14f, -8f), CREAM, PINK)
    }
}

private fun DrawScope.head(t: Long, dur: Long, moving: Boolean, em: Float) {
    val rot = if (moving) swing(t, dur, 0f, 8f, eased = true) else 0f
    rotate(rot, Offset(0.75f * em, 1.25f * em)) {
        translate(-2f * em, 0f) {
            val w = 2.75f * em
            val h = 2.5f * em
            val box = RoundRect(
                Rect(0f, 0f, w, h),
                topLeft = CornerRadius(0.7f * w, 0.4f * h),
                topRight = CornerRadius(0.3f * w, 0.25f * h),
                bottomRight = CornerRadius(0f, 0f),
                bottomLeft = CornerRadius(1f * w, 0.6f * h),
            )
            shape(box, ORANGE, listOf(Triple(0f, -0.25f * em, ORANGE_LIGHT), Triple(0.75f * em, -1.55f * em, CREAM)))

            val earRot = if (moving) swing(t, dur, 0f, 12f, eased = true) else 0f
            rotate(earRot, Offset(2.625f * em, 0.3125f * em)) {
                val ear = RoundRect(Rect(2.25f * em, -0.25f * em, 3f * em, 0.5f * em), CornerRadius(0.375f * em))
                shape(ear, PINK, listOf(Triple(-0.25f * em, 0f, ORANGE)))
            }

            val p = (t % dur).toFloat() / dur
            val blink = when {
                p < 0.9f -> 1f
                p < 0.95f -> 1f - (p - 0.9f) / 0.05f
                else -> (p - 0.95f) / 0.05f
            }
            scale(1f, blink, Offset(1.5f * em, 0.625f * em)) {
                drawCircle(Color.Black, 0.25f * em, Offset(1.5f * em, 0.625f * em))
            }
            drawOval(PINK_DARK, Offset(0f, 0.75f * em), androidx.compose.ui.geometry.Size(0.2f * em, 0.25f * em))
        }
    }
}

/** 前足。幅 1em・高さ 1.5em の多角形で、下 20% が肉球の色。 */
private fun DrawScope.frontLimb(em: Float, deg: Float, fur: Color, paw: Color) {
    rotate(deg, Offset(1f * em, 2f * em)) {
        translate(0.5f * em, 2f * em) {
            val w = 1f * em
            val h = 1.5f * em
            val poly = polygon(w, h, 0f to 0f, 1f to 0f, 0.7f to 0.8f, 0.6f to 1f, 0f to 1f, 0.4f to 0.8f)
            clipPath(poly) {
                drawRect(fur, size = androidx.compose.ui.geometry.Size(w, h * 0.8f))
                drawRect(paw, Offset(0f, h * 0.8f), androidx.compose.ui.geometry.Size(w, h * 0.2f))
            }
        }
    }
}

/** 後ろ足。上端が丸く、下 10% が肉球の色。 */
private fun DrawScope.backLimb(em: Float, deg: Float, fur: Color, paw: Color) {
    rotate(deg, Offset(3.55f * em, 1.75f * em)) {
        translate(2.8f * em, 1f * em) {
            val w = 1.5f * em
            val h = 2.5f * em
            val top = Path().apply {
                addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(0.75f * em), CornerRadius(0.75f * em), CornerRadius.Zero, CornerRadius.Zero))
            }
            val poly = polygon(w, h, 0f to 0f, 1f to 0f, 1f to 0.3f, 0.7f to 0.9f, 0.7f to 1f, 0.3f to 1f, 0.4f to 0.9f, 0f to 0.3f)
            clipPath(Path.combine(PathOperation.Intersect, top, poly)) {
                drawRect(fur, size = androidx.compose.ui.geometry.Size(w, h * 0.9f))
                drawRect(paw, Offset(0f, h * 0.9f), androidx.compose.ui.geometry.Size(w, h * 0.1f))
            }
        }
    }
}

private fun DrawScope.tail(em: Float, deg: Float) {
    rotate(deg, Offset(4.25f * em, 1.75f * em)) {
        val box = RoundRect(
            Rect(4f * em, 1.5f * em, 5f * em, 2f * em),
            topLeft = CornerRadius(0.25f * em),
            topRight = CornerRadius(0.5f * em, 0.25f * em),
            bottomRight = CornerRadius(0.5f * em, 0.25f * em),
            bottomLeft = CornerRadius(0.25f * em),
        )
        shape(box, PINK, listOf(Triple(0f, -0.2f * em, PINK_DARK)))
    }
}

private fun polygon(w: Float, h: Float, vararg pts: Pair<Float, Float>): Path = Path().apply {
    pts.forEachIndexed { i, (px, py) -> if (i == 0) moveTo(px * w, py * h) else lineTo(px * w, py * h) }
    close()
}

/** 塗りと内側の影（CSS の inset box-shadow）。影は先に書いたものが上に来る。 */
private fun DrawScope.shape(box: RoundRect, fill: Color, insets: List<Triple<Float, Float, Color>>) {
    val path = Path().apply { addRoundRect(box) }
    drawPath(path, fill)
    clipPath(path) {
        val bounds = Path().apply { addRect(box.boundingRect.inflate(box.width)) }
        insets.asReversed().forEach { (dx, dy, color) ->
            val moved = Path().apply { addRoundRect(box.translate(Offset(dx, dy))) }
            drawPath(Path.combine(PathOperation.Difference, bounds, moved), color)
        }
    }
}
