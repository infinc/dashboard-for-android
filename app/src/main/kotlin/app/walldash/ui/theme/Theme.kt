package app.walldash.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

object Wd {
    val Bg = Color(0xFF0A0C10)
    val Surface = Color(0xFF12161D)
    val Surface2 = Color(0xFF161B24)
    val Border = Color(0xFF222A35)
    val BorderSoft = Color(0xFF1A212B)
    val Text = Color(0xFFE8EEF5)
    val Text2 = Color(0xFF93A1B1)
    val Text3 = Color(0xFF5B6774)
    val Axis = Color(0xFF7C8B9B)
    val Green = Color(0xFF3DDC97)
    val Amber = Color(0xFFFFB347)
    val Red = Color(0xFFFF6B6B)
    val Violet = Color(0xFF8B7CFF)
    val Cyan = Color(0xFF4DD4FF)
}

val LocalAccent = compositionLocalOf { Wd.Cyan }

fun colorOf(hex: String, fallback: Color = Wd.Cyan): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

/** 白へ寄せた色。暗い面に置く小さな数字用（暗めのアクセントでも読めるように）。 */
fun Color.lighten(t: Float = 0.42f): Color =
    Color(red + (1 - red) * t, green + (1 - green) * t, blue + (1 - blue) * t, alpha)

/**
 * 端末の文字の大きさの設定に左右されない文字サイズ（dp と同じ実寸）。
 * 壁掛けの画面は 1 画面に収める前提で組んでいるので、拡大されると溢れる。
 */
val Float.tu: TextUnit
    @Composable @ReadOnlyComposable
    get() = with(LocalDensity.current) { this@tu.dp.toSp() }

val Int.tu: TextUnit
    @Composable @ReadOnlyComposable
    get() = toFloat().tu

/** 画面の高さに比例させた寸法（CSS の clamp(min, N vh, max) と同じ）。 */
@Composable
@ReadOnlyComposable
fun vh(percent: Float, min: Dp, max: Dp): Dp =
    (LocalConfiguration.current.screenHeightDp * percent / 100f).dp.coerceIn(min, max)

@Composable
@ReadOnlyComposable
fun vhText(percent: Float, min: Float, max: Float): TextUnit =
    (LocalConfiguration.current.screenHeightDp * percent / 100f).coerceIn(min, max).tu

@Composable
fun WalldashTheme(accent: Color, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Wd.Bg,
        secondary = accent,
        background = Wd.Bg,
        onBackground = Wd.Text,
        surface = Wd.Surface,
        onSurface = Wd.Text,
        surfaceVariant = Wd.Surface2,
        onSurfaceVariant = Wd.Text2,
        outline = Wd.Border,
        outlineVariant = Wd.BorderSoft,
        error = Wd.Red,
        surfaceContainer = Wd.Surface2,
        surfaceContainerHigh = Wd.Surface2,
        surfaceContainerHighest = Wd.Surface2,
    )
    MaterialTheme(colorScheme = scheme) {
        // Material の既定（行の高さ 24・字間 0.5）は壁掛けの小さな文字には大きすぎるので、素の値にする
        CompositionLocalProvider(
            LocalAccent provides accent,
            LocalContentColor provides Wd.Text,
            LocalTextStyle provides TextStyle(color = Wd.Text, fontSize = 14.tu),
            content = content,
        )
    }
}
