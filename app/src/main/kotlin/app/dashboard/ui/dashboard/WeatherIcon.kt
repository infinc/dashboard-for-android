package app.dashboard.ui.dashboard

import app.dashboard.i18n.L
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import app.dashboard.ui.theme.Wd
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val SUN = Color(0xFFFFB347)
// 月と雪は白に近いので、ホワイトのテーマでは面に溶けないよう濃くする
private val MOON get() = if (Wd.palette.light) Color(0xFF8E9CB0) else Color(0xFFC7D2E0)
private val CLOUD = Color(0xFF8FA0B3)
private val RAIN = Color(0xFF4DD4FF)
private val SNOW get() = if (Wd.palette.light) Color(0xFF93B2CF) else Color(0xFFDDEBF7)
private val BOLT = Color(0xFFFFD166)

private val CLOUD_PATH: Path =
    PathParser().parsePathString("M11 24h26a9 9 0 0 0 .6-18 13 13 0 0 0-24.3-3.2A9.5 9.5 0 0 0 11 24z").toPath()
private val BOLT_PATH: Path = PathParser().parsePathString("M34 40l-9 12h7l-3 10 11-14h-7l4-8z").toPath()

/** WMO 天気コードのアイコン。64 x 64 の座標で描いて、渡された大きさに合わせる。 */
@Composable
fun WeatherIcon(code: Int?, isDay: Boolean, modifier: Modifier = Modifier) {
    val c = code ?: -1
    val moonPaths = remember { mutableMapOf<Float, Path>() }
    Canvas(modifier) {
        scale(size.minDimension / 64f, pivot = Offset.Zero) {
            fun sunOrMoon(cx: Float, cy: Float, r: Float) =
                if (isDay) sun(cx, cy, r) else drawPath(moonPaths.getOrPut(r) { moonPath(cx, cy, r + 1) }, MOON)
            when {
                c == 0 || c == 1 -> sunOrMoon(32f, 30f, 12f)
                c == 2 -> { sunOrMoon(24f, 22f, 9f); cloud(16f, 24f, 1.0f) }
                c == 3 -> cloud(14f, 20f, 1.15f)
                c == 45 || c == 48 -> {
                    cloud(14f, 14f, 1.1f)
                    drawLine(CLOUD, Offset(14f, 46f), Offset(50f, 46f), 3f, StrokeCap.Round)
                    drawLine(CLOUD, Offset(19f, 54f), Offset(45f, 54f), 3f, StrokeCap.Round)
                }
                c in 51..57 -> { cloud(14f, 12f, 1.05f); drops(42f, 3) }
                c in 61..67 || c in 80..82 -> { cloud(14f, 10f, 1.1f); drops(42f, 4) }
                c in 71..77 || c == 85 || c == 86 -> { cloud(14f, 10f, 1.1f); flakes(42f, 4) }
                c >= 95 -> { cloud(14f, 8f, 1.1f); drawPath(BOLT_PATH, BOLT) }
                else -> cloud(14f, 20f, 1.15f)
            }
        }
    }
}

private fun DrawScope.sun(cx: Float, cy: Float, r: Float) {
    for (i in 0 until 8) {
        val a = (PI / 4 * i).toFloat()
        drawLine(
            SUN,
            Offset(cx + cos(a) * (r + 4), cy + sin(a) * (r + 4)),
            Offset(cx + cos(a) * (r + 9), cy + sin(a) * (r + 9)),
            strokeWidth = 2.6f,
            cap = StrokeCap.Round,
        )
    }
    drawCircle(SUN, r, Offset(cx, cy))
}

private fun moonPath(cx: Float, cy: Float, r: Float): Path = PathParser().parsePathString(
    "M${cx + r * 0.45f} ${cy - r} a$r $r 0 1 0 ${r * 0.9f} ${r * 1.45f} " +
        "a${r * 0.85f} ${r * 0.85f} 0 1 1 -${r * 0.9f} -${r * 1.45f}z",
).toPath()

private fun DrawScope.cloud(x: Float, y: Float, s: Float) {
    translate(x, y) { scale(s, pivot = Offset.Zero) { drawPath(CLOUD_PATH, CLOUD) } }
}

private fun DrawScope.drops(y: Float, n: Int) {
    for (i in 0 until n) {
        val x = 20f + i * 10
        drawLine(RAIN, Offset(x, y), Offset(x - 3, y + 9), 3f, StrokeCap.Round)
    }
}

private fun DrawScope.flakes(y: Float, n: Int) {
    for (i in 0 until n) drawCircle(SNOW, 2.6f, Offset(20f + i * 10, y + 5))
}

private val WEATHER_LABELS get() = mapOf(
    0 to L("快晴", "Clear"), 1 to L("晴れ", "Sunny"), 2 to L("一部曇り", "Partly cloudy"), 3 to L("曇り", "Cloudy"),
    45 to L("霧", "Fog"), 48 to L("霧氷", "Rime fog"),
    51 to L("弱い霧雨", "Light drizzle"), 53 to L("霧雨", "Drizzle"), 55 to L("強い霧雨", "Heavy drizzle"), 56 to L("着氷性の霧雨", "Freezing drizzle"), 57 to L("着氷性の霧雨", "Freezing drizzle"),
    61 to L("弱い雨", "Light rain"), 63 to L("雨", "Rain"), 65 to L("強い雨", "Heavy rain"), 66 to L("着氷性の雨", "Freezing rain"), 67 to L("着氷性の雨", "Freezing rain"),
    71 to L("弱い雪", "Light snow"), 73 to L("雪", "Snow"), 75 to L("強い雪", "Heavy snow"), 77 to L("霧雪", "Snow grains"),
    80 to L("にわか雨", "Showers"), 81 to L("にわか雨", "Showers"), 82 to L("激しいにわか雨", "Heavy showers"),
    85 to L("にわか雪", "Snow showers"), 86 to L("強いにわか雪", "Heavy snow showers"),
    95 to L("雷雨", "Thunderstorm"), 96 to L("雷雨（ひょう）", "Thunderstorm (hail)"), 99 to L("雷雨（ひょう）", "Thunderstorm (hail)"),
)

fun weatherLabel(code: Int?): String = WEATHER_LABELS[code] ?: "—"
