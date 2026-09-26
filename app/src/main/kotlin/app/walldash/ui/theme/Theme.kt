package app.walldash.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** 画面全体の色の組。ダーク・ホワイトの 2 つを持ち、設定の「テーマ」で切り替える。 */
class Palette(
    val light: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val borderSoft: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val axis: Color,
    val green: Color,
    val amber: Color,
    val red: Color,
    val violet: Color,
    val cyan: Color,
    /** 図の線・目盛り・光沢に使う、面と反対側の色（ダークでは白、ホワイトでは黒）。透明度を付けて使う。 */
    val ink: Color,
)

val DarkPalette = Palette(
    light = false,
    bg = Color(0xFF0A0C10),
    surface = Color(0xFF12161D),
    surface2 = Color(0xFF161B24),
    border = Color(0xFF222A35),
    borderSoft = Color(0xFF1A212B),
    text = Color(0xFFE8EEF5),
    text2 = Color(0xFF93A1B1),
    text3 = Color(0xFF5B6774),
    axis = Color(0xFF7C8B9B),
    green = Color(0xFF3DDC97),
    amber = Color(0xFFFFB347),
    red = Color(0xFFFF6B6B),
    violet = Color(0xFF8B7CFF),
    cyan = Color(0xFF4DD4FF),
    ink = Color.White,
)

/** 明るい面でも読めるよう、強調色はダークより濃くしてある。 */
val LightPalette = Palette(
    light = true,
    bg = Color(0xFFEEF1F5),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF8FAFC),
    border = Color(0xFFD5DCE4),
    borderSoft = Color(0xFFE4E9EF),
    text = Color(0xFF111821),
    text2 = Color(0xFF465361),
    text3 = Color(0xFF7A8795),
    axis = Color(0xFF66737F),
    green = Color(0xFF0E9F63),
    amber = Color(0xFFD27A00),
    red = Color(0xFFE03C3C),
    violet = Color(0xFF6453DB),
    cyan = Color(0xFF0A95C4),
    ink = Color.Black,
)

/**
 * 画面で使う色。値は [palette] から読むので、テーマを変えると読んでいる所がすべて描き直される
 * （[palette] は Compose の状態なので、合成・描画のどちらから読んでも変化が追跡される）。
 */
object Wd {
    var palette: Palette by mutableStateOf(DarkPalette)

    /** 背景画像を出しているときのカードの不透明度。背景画像が無いときは 1。 */
    var cardAlpha: Float by mutableFloatStateOf(1f)

    val Bg get() = palette.bg
    val Surface get() = palette.surface
    val Surface2 get() = palette.surface2
    val Border get() = palette.border
    val BorderSoft get() = palette.borderSoft
    val Text get() = palette.text
    val Text2 get() = palette.text2
    val Text3 get() = palette.text3
    val Axis get() = palette.axis
    val Green get() = palette.green
    val Amber get() = palette.amber
    val Red get() = palette.red
    val Violet get() = palette.violet
    val Cyan get() = palette.cyan
    val Ink get() = palette.ink

    /** 強調色で塗った面（ボタンなど）に載せる文字の色。強調色はどれも明るいので、テーマによらず暗い色にする。 */
    val OnAccent = Color(0xFF0A0C10)
}

val LocalAccent = compositionLocalOf { DarkPalette.cyan }

fun colorOf(hex: String, fallback: Color = DarkPalette.cyan): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)

/**
 * 面の色と反対側へ寄せた色（ダークでは白へ、ホワイトでは黒へ）。
 * 面に置く小さな数字用（暗めのアクセントでも、明るい面の淡いアクセントでも読めるように）。
 */
fun Color.readable(t: Float = 0.42f): Color = if (Wd.palette.light) darken(t) else
    Color(red + (1 - red) * t, green + (1 - green) * t, blue + (1 - blue) * t, alpha)

fun Color.darken(t: Float): Color = Color(red * (1 - t), green * (1 - t), blue * (1 - t), alpha)

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

/**
 * [light] はホワイト基調にするか、[cardAlpha] は背景画像の上でカードを透かす度合い（背景画像が無ければ 1）。
 * ホワイトでは、明るい面の上で線や文字が薄くならないよう強調色を少し濃くして使う。
 */
@Composable
fun WalldashTheme(accent: Color, light: Boolean, cardAlpha: Float, content: @Composable () -> Unit) {
    Wd.palette = if (light) LightPalette else DarkPalette
    Wd.cardAlpha = cardAlpha
    val shown = if (light) accent.darken(0.22f) else accent
    val scheme = if (light) {
        lightColorScheme(
            primary = shown,
            onPrimary = Wd.OnAccent,
            secondary = shown,
            background = Wd.Bg,
            onBackground = Wd.Text,
            surface = Wd.Surface,
            onSurface = Wd.Text,
            surfaceVariant = Wd.Surface2,
            onSurfaceVariant = Wd.Text2,
            outline = Wd.Border,
            outlineVariant = Wd.BorderSoft,
            error = Wd.Red,
            surfaceContainer = Wd.Surface,
            surfaceContainerHigh = Wd.Surface,
            surfaceContainerHighest = Wd.Surface2,
        )
    } else {
        darkColorScheme(
            primary = shown,
            onPrimary = Wd.OnAccent,
            secondary = shown,
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
    }
    MaterialTheme(colorScheme = scheme) {
        // Material の既定（行の高さ 24・字間 0.5）は壁掛けの小さな文字には大きすぎるので、素の値にする
        CompositionLocalProvider(
            LocalAccent provides shown,
            LocalContentColor provides Wd.Text,
            LocalTextStyle provides TextStyle(color = Wd.Text, fontSize = 14.tu),
            content = content,
        )
    }
}
