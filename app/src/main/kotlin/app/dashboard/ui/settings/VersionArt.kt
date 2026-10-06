package app.dashboard.ui.settings

import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.random.Random

/** バージョンの 3 つ目までの数（"v1.2.2-20261002" → "v1.2.2"）。 */
internal fun shortVersion(label: String): String =
    Regex("""v?\d+(\.\d+){0,2}""").find(label)?.value?.let { if (it.startsWith("v")) it else "v$it" } ?: label

private const val GLYPHS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜｦﾝ0123456789Z:.=*+-<>¦|"
private val MatrixGreen = Color(0xFF00FF66)

/** 黒地に緑の文字が降りそそぐ中、真ん中にバージョンを出す。左上の「<」と戻る操作で閉じる。 */
@Composable
internal fun VersionArt(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val version = remember { shortVersion(BuildConfig.VERSION_LABEL) }
    val rain = remember { MatrixRain() }
    var now by remember { mutableLongStateOf(0L) }
    var start by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos {
            if (start == 0L) start = it
            now = it
        }
    }
    val elapsed = (now - start) / 1e9f

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Canvas(Modifier.fillMaxSize()) {
            rain.draw(this, now)
            // 真ん中の文字が読めるように、中央だけ暗く落とす
            drawRect(
                Brush.radialGradient(
                    0f to Color.Black.copy(alpha = 0.92f),
                    0.55f to Color.Black.copy(alpha = 0.6f),
                    1f to Color.Transparent,
                    center = center,
                    radius = size.minDimension * 0.55f,
                ),
            )
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                decode("DASHBOARD", elapsed, 0.2f, now),
                color = MatrixGreen.copy(alpha = 0.8f),
                fontSize = 22.tu,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.em,
                style = TextStyle(shadow = Shadow(MatrixGreen, blurRadius = 16f)),
            )
            Text(
                decode(version, elapsed, 0.6f, now),
                color = Color(0xFFD9FFE6),
                fontSize = 120.tu,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                style = TextStyle(shadow = Shadow(MatrixGreen, Offset.Zero, blurRadius = 48f)),
            )
            val cursor = if ((elapsed * 2).toInt() % 2 == 0) "_" else " "
            Text(
                "> system online$cursor",
                color = MatrixGreen.copy(alpha = 0.7f),
                fontSize = 18.tu,
                fontFamily = FontFamily.Monospace,
            )
        }
        Box(
            Modifier.align(Alignment.TopStart).padding(14.dp).size(52.dp)
                .clip(RoundedCornerShape(26.dp)).background(Color.Black.copy(alpha = 0.6f))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WdIcons.Back, L("戻る", "Back"), tint = MatrixGreen, modifier = Modifier.size(30.dp))
        }
    }
}

/** [delay] 秒後から左の文字から順に確定していく。まだの文字は乱数の記号でちらつかせる。 */
private fun decode(text: String, elapsed: Float, delay: Float, now: Long): String {
    val perChar = 0.12f
    val seed = Random(now / 50_000_000)
    return buildString {
        text.forEachIndexed { i, c ->
            val fixedAt = delay + i * perChar + 0.35f
            append(
                when {
                    elapsed >= fixedAt || c == ' ' -> c
                    elapsed < delay + i * perChar * 0.5f -> ' '
                    else -> GLYPHS[seed.nextInt(GLYPHS.length)]
                },
            )
        }
    }
}

/** 列ごとに先頭の文字が下へ流れ、尾が薄くなっていく雨。1 枠の中の文字はときどき入れ替わる。 */
private class MatrixRain {
    private class Drop(var head: Float, var speed: Float, var length: Int)

    private val rnd = Random(System.nanoTime())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private var cols = 0
    private var rows = 0
    private var cell = 0f
    private var glyphs = arrayOf<CharArray>()
    private var drops = arrayOf<Drop>()
    private var last = 0L

    private fun newDrop(rows: Int, anywhere: Boolean) = Drop(
        head = if (anywhere) rnd.nextFloat() * rows * 1.5f - rows * 0.5f else -rnd.nextFloat() * rows * 0.6f,
        speed = 8f + rnd.nextFloat() * 22f,
        length = 8 + rnd.nextInt(max(1, rows - 6)),
    )

    private fun layout(w: Float, h: Float, density: Float) {
        cell = 18f * density
        cols = (w / cell).toInt() + 1
        rows = (h / cell).toInt() + 1
        glyphs = Array(cols) { CharArray(rows) { GLYPHS[rnd.nextInt(GLYPHS.length)] } }
        drops = Array(cols) { newDrop(rows, anywhere = true) }
        paint.textSize = cell * 0.9f
    }

    fun draw(scope: DrawScope, now: Long) = with(scope) {
        if (cols != (size.width / (18f * density)).toInt() + 1 || rows != (size.height / (18f * density)).toInt() + 1) layout(size.width, size.height, density)
        val dt = if (last == 0L || now == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
        last = now
        repeat((cols * rows * dt * 0.6f).toInt() + 1) {
            glyphs[rnd.nextInt(cols)][rnd.nextInt(rows)] = GLYPHS[rnd.nextInt(GLYPHS.length)]
        }
        val baseline = cell * 0.8f
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            for (x in 0 until cols) {
                val d = drops[x]
                d.head += d.speed * dt
                if (d.head - d.length > rows) drops[x] = newDrop(rows, anywhere = false)
                val headRow = floor(d.head).toInt()
                val cx = x * cell + cell / 2
                for (i in 0 until d.length) {
                    val row = headRow - i
                    if (row < 0 || row >= rows) continue
                    val fade = 1f - i.toFloat() / d.length
                    paint.color = when (i) {
                        0 -> Color(0xFFE6FFEE).toArgb()
                        1 -> Color(0xFF9DFFC0).toArgb()
                        else -> MatrixGreen.copy(alpha = fade * fade * 0.85f).toArgb()
                    }
                    if (i == 0) paint.setShadowLayer(cell * 0.5f, 0f, 0f, MatrixGreen.toArgb()) else paint.clearShadowLayer()
                    c.drawText(glyphs[x], row, 1, cx, row * cell + baseline, paint)
                }
            }
            paint.clearShadowLayer()
        }
    }
}
