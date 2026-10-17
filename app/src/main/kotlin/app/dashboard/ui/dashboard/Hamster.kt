package app.dashboard.ui.dashboard

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
import androidx.compose.ui.geometry.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import app.dashboard.ui.common.TICK_MS
import app.dashboard.ui.common.awaitTick
import app.dashboard.ui.common.awaitUncovered
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * 画面下の回し車とハムスター。
 * 意匠は Uiverse.io の Nawsome 作「Loader」（MIT License）。形・色・動きの値は元の CSS のまま。
 * 動き: 走る・止まる・休む・走り出す（車の中）／歩く・立ち止まる（車の外）。外を歩き終えたら必ず車へ戻り、走り出す。
 *
 * 止まるとき: 足を止めると、回っていた車がそのままハムスターを右へ運び上げ、重みで時計回りに戻る。
 * そこから振り子のように左右へ揺れ、だんだん小さくなって止まる（[Motion.step] の減衰する振り子）。
 * 走り出すとき: 足を動かし始めると左の坂を上がり、その重みで車が反時計回りに回り始める。
 * 車に運ばれて右へ動き、足は動かしたまま、だんだん真ん中へ戻る。
 */

private enum class Mode { RUN, STOPPING, REST, STARTING, WALK, STAND }

/**
 * 回し車とハムスターの動きの状態。毎フレーム [step] で進める。
 * Compose の状態にはしない（描き直しは約 30 コマ/秒の時計の更新に任せ、古い端末の負担を増やさない）。
 */
private class Motion {
    /** 車の角度（度。負が反時計回り）。 */
    var wheel = 0f
    /** 車の中でのハムスターの位置（車の中心まわりの角度、度。正 = 左の坂、負 = 右）。 */
    var tilt = 0f
    /** [tilt] の速さ（度/秒）。止まるときの振り子に使う。 */
    var tiltSpeed = 0f
    /** 足の動きの時計（ms）。足を止めている間は進まない。 */
    var legs = 0L
    private var legRate = 1f
    private var since = 0L
    /** 止まる・走り出すの動きが終わったか。 */
    var done = false

    fun begin(mode: Mode, now: Long) {
        since = now
        done = false
        // 足を止めた瞬間の車の勢いのまま、ハムスターは右へ運ばれる
        if (mode == Mode.STOPPING) tiltSpeed = RUN_SPIN * CARRY
    }

    fun step(mode: Mode, now: Long, dt: Float) {
        when (mode) {
            Mode.RUN -> {
                legRate = 1f
                wheel += RUN_SPIN * dt
                tilt = 0f
            }
            Mode.STOPPING -> {
                legRate = 0f
                // 車とハムスターがいっしょに動く振り子。重みで真下へ戻ろうとし、軸の摩擦で揺れが小さくなる
                val acc = -SWING_K * sin(Math.toRadians(tilt.toDouble())).toFloat() * (180f / PI.toFloat()) - SWING_DAMP * tiltSpeed
                tiltSpeed += acc * dt
                tilt += tiltSpeed * dt
                wheel += tiltSpeed * dt
                val e = now - since
                if (e > 1500 && abs(tilt) < 0.4f && abs(tiltSpeed) < 4f || e > 9000) {
                    tilt = 0f
                    tiltSpeed = 0f
                    done = true
                }
            }
            Mode.STARTING -> {
                val e = (now - since) / 1000f
                if (e < LEAN_S) {
                    // 足を動かし始めて、左の坂を上がる（車はまだ動かない）
                    legRate = 0.55f + 0.45f * e / LEAN_S
                    tilt = LEAN * EASE.transform(e / LEAN_S)
                } else {
                    // 重みで車が反時計回りに回り始め、ハムスターは右へ運ばれてから、だんだん真ん中へ戻る
                    val u = e - LEAN_S
                    legRate = 1f
                    wheel += RUN_SPIN * EASE.transform(min(1f, u / SPIN_UP_S)) * dt
                    tilt = LEAN * exp(-0.7f * u) * cos(2.4f * u)
                    if (u > 4.5f) {
                        tilt = 0f
                        done = true
                    }
                }
            }
            Mode.REST, Mode.STAND -> legRate = 0f
            Mode.WALK -> legRate = 1f
        }
        legs += (dt * 1000f * legRate).toLong()
        wheel %= 360f
    }
}

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
/** 走っているときの車の速さ（度/秒。負が反時計回り）。元の CSS の 1 回転/秒。 */
private const val RUN_SPIN = -360f
/** 足を止めた瞬間に、車の勢いのうちどれだけでハムスターが運ばれるか。 */
private const val CARRY = 0.8f
/** 止まるときの振り子の強さ（1/秒²）と、揺れの減り方（1/秒）。揺れ幅は最初 40 度ほど、6〜7 秒で止まる。 */
private const val SWING_K = 40f
private const val SWING_DAMP = 1.4f
/** 走り出すときに左の坂を上がる角度（度）と時間（秒）、車が走る速さに達するまでの時間（秒）。 */
private const val LEAN = 32f
private const val LEAN_S = 0.9f
private const val SPIN_UP_S = 1.4f

@Composable
fun Hamster(modifier: Modifier = Modifier) {
    var mode by remember { mutableStateOf(Mode.RUN) }
    var x by remember { mutableFloatStateOf(0f) }
    var dir by remember { mutableIntStateOf(-1) }
    var time by remember { mutableLongStateOf(0L) }
    val motion = remember { Motion() }
    val width = LocalConfiguration.current.screenWidthDp.toFloat()
    val rest by animateFloatAsState(if (mode == Mode.REST) 1f else 0f, tween(800), label = "rest")

    LaunchedEffect(Unit) {
        var prev = 0L
        while (true) {
            awaitUncovered()
            withFrameMillis {
                // 動きは毎フレーム進め、描き直しはほかの絵と同じ刻み（約 30 コマ/秒、TICK_MS）にする
                motion.step(mode, it, if (prev == 0L) 0f else (it - prev).coerceAtMost(100) / 1000f)
                prev = it
                if (it / TICK_MS != time / TICK_MS) time = it
            }
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
                awaitUncovered()
                prev = awaitTick(prev) { now ->
                    val step = WALK_SPEED * ((now - prev).coerceAtMost(100) / 1000f)
                    x = if (abs(target - x) <= step) target else x + dir * step
                }
            }
        }
        fun spot(): Float {
            repeat(6) {
                val c = lo + Random.nextFloat() * (hi - lo)
                if (abs(c - x) > 90) return c
            }
            return if (x > (lo + hi) / 2) lo else hi
        }
        /** 止まる・走り出すの動きを、終わるまで見届ける。 */
        suspend fun play(next: Mode) {
            motion.begin(next, withFrameMillis { it })
            mode = next
            while (!motion.done) {
                awaitUncovered()
                withFrameMillis { }
            }
        }
        x = 0f
        while (true) {
            mode = Mode.RUN
            delay(Random.nextLong(18_000, 40_000))
            play(Mode.STOPPING)
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
            play(Mode.STARTING)
        }
    }

    // 自分の層で描く（描き直しのたびに、下のフッターなどまで描き直さない）
    Canvas(modifier.size(78.dp).graphicsLayer()) {
        drawHamster(time, mode, x, dir, rest, motion)
    }
}

/** キーフレームが 12.5% ごとに A と B を行き来する動き。[eased] は ease-in-out。 */
private fun swing(t: Long, periodMs: Long, a: Float, b: Float, eased: Boolean): Float {
    val sub = (t % periodMs).toFloat() / periodMs * 8f % 2f
    val f = if (sub < 1f) sub else 2f - sub
    return a + (b - a) * (if (eased) EASE.transform(f) else f)
}

private fun DrawScope.drawHamster(t: Long, mode: Mode, x: Float, dir: Int, rest: Float, motion: Motion) {
    val em = size.width / 12f
    val outside = mode == Mode.WALK || mode == Mode.STAND
    val dur = if (mode == Mode.WALK) 1700L else 1000L
    val moving = mode == Mode.RUN || mode == Mode.WALK || mode == Mode.STARTING

    if (!outside) {
        spoke(em, motion.wheel)
        hamster(t, motion.legs, mode, dur, moving, em, x, dir, rest, motion.tilt)
        wheel(em)
    } else {
        spoke(em, motion.wheel)
        wheel(em)
        hamster(t, motion.legs, mode, dur, moving, em, x, dir, rest, 0f)
    }
}

private fun DrawScope.wheel(em: Float) {
    drawCircle(WHEEL, 5.875f * em, center, style = Stroke(0.25f * em))
}

private fun DrawScope.spoke(em: Float, turn: Float) {
    rotate(turn, center) {
        val (bar) = cached(listOf("spoke", em, center)) {
            listOf(
                Path.combine(
                    PathOperation.Intersect,
                    Path().apply { addOval(Rect(center, 5.94f * em)) },
                    Path().apply { addRect(Rect(Offset(center.x - 5.94f * em, center.y - 0.355f * em), androidx.compose.ui.geometry.Size(11.88f * em, 0.71f * em))) },
                ),
            )
        }
        drawPath(bar, SPOKE)
        drawCircle(WHEEL, 0.576f * em, center)
    }
}

/** [legs] は足の動きの時計（足を止めると進まない）、[tilt] は車の中心まわりにずらす角度（正 = 左の坂）。 */
private fun DrawScope.hamster(t: Long, legs: Long, mode: Mode, dur: Long, moving: Boolean, em: Float, x: Float, dir: Int, rest: Float, tilt: Float) {
    val boxX = 2.5f * em
    val boxY = 6f * em
    val originX = 3.5f * em
    val outside = mode == Mode.WALK || mode == Mode.STAND
    val walkX = (BASE_X + x).dp.toPx()
    val walkY = BASE_Y.dp.toPx()
    withTransform({
        if (!outside) rotate(tilt, center)
        translate(boxX + originX, boxY)
        if (outside) {
            translate(walkX, walkY)
            scale(if (dir > 0) -1f else 1f, 1f, Offset.Zero)
        } else {
            val half = (legs % 1000L) / 1000f * 2f
            val running = if (moving) 4f + (0f - 4f) * EASE.transform(if (half < 1) half else 2 - half) else 0f
            rotate(running * (1 - rest), Offset.Zero)
            translate(-0.8f * em, (1.85f + 0.3f * rest) * em)
        }
        translate(-originX, 0f)
    }) {
        body(t, legs, mode, dur, moving, em)
    }
}

private fun DrawScope.body(t: Long, legs: Long, mode: Mode, dur: Long, moving: Boolean, em: Float) {
    // 止まるときは足をその場で止める（足の時計が進まないので、最後の形のまま）
    fun limb(runA: Float, runB: Float, rested: Float, standing: Float): Float = when (mode) {
        Mode.REST -> rested
        Mode.STAND -> standing
        else -> swing(legs, dur, runA, runB, eased = false)
    }
    val bodyRot = if (moving) swing(legs, dur, 0f, -2f, eased = true) else 0f
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
        tail(em, if (moving) swing(legs, dur, 30f, 10f, eased = false) else 18f)

        val bodyBox = RoundRect(
            Rect(0f, 0f, 4.5f * em, 3f * em),
            topLeft = CornerRadius(0.5f * 4.5f * em, 0.15f * 3f * em),
            topRight = CornerRadius(0.3f * 4.5f * em, 0.6f * 3f * em),
            bottomRight = CornerRadius(0.5f * 4.5f * em, 0.4f * 3f * em),
            bottomLeft = CornerRadius(0.3f * 4.5f * em, 0.4f * 3f * em),
        )
        shape(bodyBox, CREAM, listOf(Triple(0.1f * em, 0.75f * em, ORANGE), Triple(0.15f * em, -0.5f * em, ORANGE_LIGHT)))

        head(t, legs, dur, moving, em)
        frontLimb(em, limb(-30f, 50f, 22f, 14f), CREAM, PINK)
        backLimb(em, limb(20f, -60f, -14f, -8f), CREAM, PINK)
    }
}

private fun DrawScope.head(t: Long, legs: Long, dur: Long, moving: Boolean, em: Float) {
    val rot = if (moving) swing(legs, dur, 0f, 8f, eased = true) else 0f
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

            val earRot = if (moving) swing(legs, dur, 0f, 12f, eased = true) else 0f
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
            val (furPath, pawPath) = cached(listOf("front", w, h)) {
                split(polygon(w, h, 0f to 0f, 1f to 0f, 0.7f to 0.8f, 0.6f to 1f, 0f to 1f, 0.4f to 0.8f), w, h, 0.8f)
            }
            drawPath(furPath, fur)
            drawPath(pawPath, paw)
        }
    }
}

/** 後ろ足。上端が丸く、下 10% が肉球の色。 */
private fun DrawScope.backLimb(em: Float, deg: Float, fur: Color, paw: Color) {
    rotate(deg, Offset(3.55f * em, 1.75f * em)) {
        translate(2.8f * em, 1f * em) {
            val w = 1.5f * em
            val h = 2.5f * em
            val (furPath, pawPath) = cached(listOf("back", w, h)) {
                val top = Path().apply {
                    addRoundRect(RoundRect(Rect(0f, 0f, w, h), CornerRadius(0.75f * em), CornerRadius(0.75f * em), CornerRadius.Zero, CornerRadius.Zero))
                }
                val poly = polygon(w, h, 0f to 0f, 1f to 0f, 1f to 0.3f, 0.7f to 0.9f, 0.7f to 1f, 0.3f to 1f, 0.4f to 0.9f, 0f to 0.3f)
                split(Path.combine(PathOperation.Intersect, top, poly), w, h, 0.9f)
            }
            drawPath(furPath, fur)
            drawPath(pawPath, paw)
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

/**
 * 形はカードの大きさ（em）だけで決まり、動きは座標の変換で付けるので、図形の合成（Path.combine）の結果を使い回す。
 * 毎コマ合成し直すと、それだけで主スレッドがかなり重くなる。
 */
private val pathCache = HashMap<Any, List<Path>>()

private fun cached(key: Any, build: () -> List<Path>): List<Path> =
    pathCache[key] ?: build().also {
        if (pathCache.size > 64) pathCache.clear()
        pathCache[key] = it
    }

/** [path] を高さの [at] の割合で上下に分ける（上 = 毛、下 = 肉球）。 */
private fun split(path: Path, w: Float, h: Float, at: Float): List<Path> = listOf(
    Path.combine(PathOperation.Intersect, path, Path().apply { addRect(Rect(0f, 0f, w, h * at)) }),
    Path.combine(PathOperation.Intersect, path, Path().apply { addRect(Rect(0f, h * at, w, h)) }),
)

/** 塗りと内側の影（CSS の inset box-shadow）。影は先に書いたものが上に来る。 */
private fun DrawScope.shape(box: RoundRect, fill: Color, insets: List<Triple<Float, Float, Color>>) {
    // 影 = 形から、ずらした形をくり抜いたもの
    val paths = cached(box to insets) {
        val path = Path().apply { addRoundRect(box) }
        listOf(path) + insets.asReversed().map { (dx, dy, _) ->
            Path.combine(PathOperation.Difference, path, Path().apply { addRoundRect(box.translate(Offset(dx, dy))) })
        }
    }
    drawPath(paths[0], fill)
    insets.asReversed().forEachIndexed { i, (_, _, color) -> drawPath(paths[i + 1], color) }
}
