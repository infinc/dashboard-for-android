package app.walldash.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.walldash.data.DisasterState
import app.walldash.data.DisplayConfig
import app.walldash.data.SpotifyState
import app.walldash.data.TyphoonInfo
import app.walldash.data.WifiState
import app.walldash.data.DeviceStats
import app.walldash.ui.common.EmptyText
import app.walldash.ui.common.Hairline
import app.walldash.ui.common.Tabular
import app.walldash.ui.common.WdCard
import app.walldash.ui.common.WdIcons
import app.walldash.ui.theme.LocalAccent
import app.walldash.ui.theme.Wd
import app.walldash.ui.theme.tu
import app.walldash.ui.theme.vh
import app.walldash.ui.theme.vhText
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------- 防災

@Composable
fun DisasterCard(
    d: DisasterState?,
    enabled: Boolean,
    display: DisplayConfig,
    kmoniBase: ImageBitmap?,
    kmoni: DashboardViewModel.KmoniFrame,
    modifier: Modifier,
) {
    val active = d != null && (d.activeAreas.isNotEmpty() || d.tsunami.isNotEmpty())
    WdCard(
        "防災",
        modifier,
        note = listOfNotNull(d?.officeName, d?.areaName).joinToString(" "),
        titleColor = if (active) Wd.Amber else Wd.Text3,
        borderColor = if (active) Wd.Amber.copy(alpha = 0.5f) else Wd.Border,
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    !enabled -> EmptyText("防災情報の取得が無効です。設定画面の「防災」で有効にしてください。")
                    d == null || !d.available -> EmptyText("気象庁の情報を取得できていません。")
                    else -> DisasterBody(d, display)
                }
            }
            if (display.disasterShowKmoni) Kmoni(kmoniBase, kmoni, Modifier.width(112.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun DisasterBody(d: DisasterState, display: DisplayConfig) {
    val typhoons = if (display.disasterShowTyphoon) d.typhoons else emptyList()
    val volcanoes = if (display.disasterShowVolcano) d.volcanoes else emptyList()
    val scroll = rememberScrollState()
    val signature = listOf(d.headline, d.activeAreas, d.tsunami, typhoons, volcanoes).hashCode()
    // 中身が変わったら先頭へ戻す（壁掛けで下までスクロールされたままだと津波・警報が隠れる）
    LaunchedEffect(signature) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            if (d.tsunami.isNotEmpty()) {
                Section("津波", Wd.Red)
                d.tsunami.forEach { NameRow(it.title ?: "津波情報", hhmm(it.reportedAt), Wd.Red, Wd.Text3) }
            }
            when {
                d.areaName == null -> Text(
                    "天気の地点から市町村を決められません。設定画面の「場所」で国内の地点を選んでください。",
                    color = Wd.Text3, fontSize = 12.5f.tu, lineHeight = 1.45.em,
                )
                d.activeAreas.isEmpty() -> Text("発表中の警報・注意報はありません。", color = Wd.Text3, fontSize = 12.5f.tu, lineHeight = 1.45.em)
                else -> {
                    Text(d.headline ?: "${d.areaName}に発表中", fontSize = 12.5f.tu, lineHeight = 1.45.em, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    d.activeAreas.forEach { a ->
                        Row(Modifier.padding(top = 5.dp)) {
                            Text(a.name, color = Wd.Text2, fontSize = 12.tu, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Spacer(Modifier.width(7.dp))
                            Text(
                                a.kinds.joinToString("・"),
                                color = if (a.severe) Wd.Red else Wd.Amber,
                                fontSize = 12.tu,
                                fontWeight = if (a.severe) FontWeight.SemiBold else FontWeight.Normal,
                                lineHeight = 1.45.em,
                            )
                        }
                    }
                }
            }
            if (typhoons.isNotEmpty()) {
                Section("台風", Wd.Text3)
                typhoons.forEach { TyphoonRows(it) }
            }
            if (volcanoes.isNotEmpty()) {
                Section("噴火  ${volcanoes.size} 件", Wd.Text3)
                volcanoes.forEach { v ->
                    NameRow(v.name, v.level, if (v.severe) Wd.Text else Wd.Text2, if (v.severe) Wd.Red else Wd.Text3)
                }
            }
        }
        d.quakes.firstOrNull()?.let { q ->
            Hairline(Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                val n = q.maxIntensity?.firstOrNull()?.digitToIntOrNull() ?: 0
                val (bg, fg) = when {
                    n >= 5 -> Wd.Red.copy(alpha = 0.25f) to Wd.Red
                    n >= 3 -> Wd.Amber.copy(alpha = 0.25f) to Wd.Amber
                    else -> Wd.Border to Wd.Text
                }
                Text(
                    "震度 ${q.maxIntensity ?: "—"}",
                    color = fg,
                    fontSize = 11.5f.tu,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(q.epicenter.orEmpty(), fontSize = 13.tu, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(7.dp))
                Text("M${q.magnitude ?: "—"} ・ ${hhmm(q.occurredAt)}", color = Wd.Text3, fontSize = 12.tu, style = Tabular)
            }
        }
    }
}

@Composable
private fun Section(title: String, color: Color) {
    Hairline(Modifier.padding(top = 9.dp))
    Text(title, color = color, fontSize = 10.5f.tu, letterSpacing = 0.12.em, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun NameRow(name: String, state: String, nameColor: Color, stateColor: Color) {
    Row(Modifier.padding(top = 4.dp)) {
        Text(name, color = nameColor, fontSize = 12.tu, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(7.dp))
        Text(state, color = stateColor, fontSize = 12.tu, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TyphoonRows(t: TyphoonInfo) {
    val course = t.course?.let { it + "へ" + (t.speedKmh?.let { s -> " $s km/h" } ?: "") }
    Column(Modifier.padding(top = 4.dp)) {
        NameRow(
            listOfNotNull(t.number, t.name).joinToString(" ").ifEmpty { "台風" },
            listOfNotNull(t.scale, t.intensity).joinToString("・"),
            Wd.Amber,
            Wd.Text2,
        )
        Text(
            listOfNotNull(t.location, t.pressureHpa?.let { "$it hPa" }, course).joinToString(" ・ "),
            color = Wd.Text3, fontSize = 11.5f.tu,
        )
        Text(
            listOfNotNull(t.maxWindMps?.let { "最大風速 $it m/s" }, t.gustMps?.let { "瞬間 $it m/s" }).joinToString(" ／ "),
            color = Wd.Text3, fontSize = 11.5f.tu,
        )
    }
}

/** 元画像は白地なので、暗い壁に合わせて反転（CSS の invert(1) brightness(.82) contrast(1.1)）。 */
private val KMONI_BASE_FILTER = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.902f, 0f, 0f, 0f, 217.3f,
            0f, -0.902f, 0f, 0f, 217.3f,
            0f, 0f, -0.902f, 0f, 217.3f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

@Composable
private fun Kmoni(base: ImageBitmap?, frame: DashboardViewModel.KmoniFrame, modifier: Modifier) {
    Box(modifier) {
        base?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alpha = 0.55f, colorFilter = KMONI_BASE_FILTER) }
        frame.image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        Text(
            frame.label,
            color = if (frame.failed) Wd.Red else Wd.Text3,
            fontSize = 10.tu,
            style = Tabular,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Wd.Bg.copy(alpha = 0.85f))))
                .padding(start = 2.dp, top = 8.dp, bottom = 1.dp),
        )
    }
}

// ---------------------------------------------------------------- Wi-Fi

private val LEVEL_LABEL = listOf("非常に弱い", "弱い", "普通", "強い", "非常に強い")
private val LEVEL_COLOR = listOf(Wd.Red, Wd.Red, Wd.Amber, Wd.Green, Wd.Green)
private val SSID_MSG = mapOf(
    "permission_required" to "権限が必要",
    "location_services_off" to "位置情報サービスを ON に",
    "unavailable" to "取得できません",
)

@Composable
fun WifiCard(wifi: WifiState?, stats: DeviceStats?, rssiHistory: List<Int?>, showGlobe: Boolean, modifier: Modifier) {
    val accent = LocalAccent.current
    WdCard("Wi-Fi リンク速度", modifier, note = listOfNotNull(wifi?.ipAddress, wifi?.band).joinToString(" ・ ")) {
        if (wifi == null) return@WdCard
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.height(18.dp), verticalAlignment = Alignment.Bottom) {
                        repeat(4) { i ->
                            Box(
                                Modifier.padding(end = 3.dp).width(4.dp).height((5 + i * 4).dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(if (i < wifi.signalLevel) accent else Wd.Border),
                            )
                        }
                    }
                    Spacer(Modifier.width(7.dp))
                    Speeds(wifi, Modifier.weight(1f))
                }
                Column(Modifier.padding(top = 4.dp)) {
                    WifiRow("通信量", traffic(stats))
                    WifiRow(
                        "SSID",
                        if (wifi.ssidStatus == "ok" && wifi.ssid != null) wifi.ssid else SSID_MSG[wifi.ssidStatus] ?: "—",
                        if (wifi.ssidStatus == "ok") Wd.Text else Wd.Amber,
                    )
                    val level = wifi.signalLevel.coerceIn(0, 4)
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text("強度", color = Wd.Text3, fontSize = 12.tu)
                        Spacer(Modifier.weight(1f))
                        if (wifi.rssiDbm == null) Text("—", fontSize = 12.tu) else {
                            Text("${wifi.rssiDbm} dBm", fontSize = 12.tu, fontWeight = FontWeight.SemiBold, style = Tabular)
                            Text(" (${LEVEL_LABEL[level]})", color = LEVEL_COLOR[level], fontSize = 11.tu, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Sparkline(rssiHistory, Modifier.fillMaxWidth().height(16.dp).padding(top = 3.dp))
            }
            if (showGlobe) Globe(Modifier.size(78.dp))
        }
    }
}

@Composable
private fun Speeds(wifi: WifiState, modifier: Modifier) {
    val rx = wifi.rxLinkSpeedMbps
    val tx = wifi.txLinkSpeedMbps
    Column(modifier) {
        if (rx == null || tx == null) {
            SpeedRow("", rx ?: tx ?: wifi.linkSpeedMbps, vhText(4f, 22f, 32f))
        } else {
            SpeedRow("↓ 下り", rx, vhText(2.35f, 15f, 20f))
            SpeedRow("↑ 上り", tx, vhText(2.35f, 15f, 20f))
        }
    }
}

@Composable
private fun SpeedRow(label: String, value: Int?, size: androidx.compose.ui.unit.TextUnit) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, color = Wd.Text3, fontSize = 11.tu, maxLines = 1)
        Text(
            value?.toString() ?: "—",
            fontSize = size,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            lineHeight = 1.1.em,
            style = Tabular,
            modifier = Modifier.weight(1f),
        )
        Text(" Mbps", color = Wd.Text2, fontSize = 10.5f.tu, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun WifiRow(label: String, value: String, color: Color = Wd.Text) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(label, color = Wd.Text3, fontSize = 12.tu)
        Spacer(Modifier.width(10.dp))
        Text(
            value, color = color, fontSize = 12.tu, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = Tabular,
        )
    }
}

/** 実際に流れている通信量。下りと上りで桁が違っても読み違えないよう、単位は大きい方にそろえる。 */
private fun traffic(s: DeviceStats?): String {
    val rx = s?.rxBitsPerSec ?: return "計測中…"
    val tx = s.txBitsPerSec ?: return "計測中…"
    val mbps = max(rx, tx) >= 1_000_000
    val div = if (mbps) 1_000_000.0 else 1_000.0
    fun rate(v: Double) = if (v >= 10) v.roundToInt().toString() else ((v * 10).roundToInt() / 10.0).toString()
    return "↓${rate(rx / div)} ↑${rate(tx / div)} " + if (mbps) "Mbps" else "kbps"
}

@Composable
private fun Sparkline(history: List<Int?>, modifier: Modifier) {
    val accent = LocalAccent.current
    if (history.count { it != null } < 3) {
        Spacer(modifier)
        return
    }
    Canvas(modifier) {
        val step = size.width / (history.size - 1)
        val path = Path()
        var started = false
        history.forEachIndexed { i, v ->
            v ?: return@forEachIndexed
            val y = size.height * (1 - (v.coerceIn(-90, -30) + 90) / 60f)
            if (!started) { path.moveTo(i * step, y); started = true } else path.lineTo(i * step, y)
        }
        drawPath(path, accent.copy(alpha = 0.55f), style = Stroke(1.6f * density))
    }
}

/** 海岸線の帯を円でくり抜いた中で左へ流し、回っているように見せる（1 周 18 秒）。 */
@Composable
private fun Globe(modifier: Modifier) {
    val land = remember {
        Path().apply {
            fillType = PathFillType.EvenOdd
            GlobeData.LAND.forEach { addPath(PathParser().parsePathString(it).toPath()) }
        }
    }
    val spin by rememberInfiniteTransition(label = "globe").animateFloat(
        0f, GlobeData.STRIP_WIDTH,
        infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    Canvas(modifier) {
        scale(size.minDimension / 48f, pivot = Offset.Zero) {
            val center = Offset(24f, 24f)
            drawCircle(Color.White.copy(alpha = 0.06f), 19f, center)
            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, 19f)) }) {
                for (copy in -1..1) {
                    translate(copy * GlobeData.STRIP_WIDTH - spin, 0f) {
                        drawPath(land, Color.White.copy(alpha = 0.72f))
                        drawCircle(Color(0x4DFF4D4D), 2.3f, Offset(GlobeData.MARK_X, GlobeData.MARK_Y))
                        drawCircle(Color(0xFFFF4D4D), 1f, Offset(GlobeData.MARK_X, GlobeData.MARK_Y))
                    }
                }
                val grid = Color.White.copy(alpha = 0.22f)
                drawOval(grid, Offset(5f, 17.5f), Size(38f, 13f), style = Stroke(0.8f))
                drawLine(grid, Offset(5f, 24f), Offset(43f, 24f), 0.8f)
            }
            drawCircle(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.2f),
                    0.55f to Color.White.copy(alpha = 0f),
                    1f to Color.Black.copy(alpha = 0.55f),
                    center = Offset(5f + 38f * 0.35f, 5f + 38f * 0.30f),
                    radius = 38f * 0.75f,
                ),
                19f,
                center,
            )
            drawCircle(Color.White.copy(alpha = 0.42f), 19f, center, style = Stroke(1f))
        }
    }
}

// ---------------------------------------------------------------- Spotify

@Composable
fun SpotifyCard(
    sp: SpotifyState?,
    enabled: Boolean,
    connected: Boolean,
    display: DisplayConfig,
    album: Pair<String, ImageBitmap>?,
    now: Long,
    onControl: (String) -> Unit,
    modifier: Modifier,
) {
    val note = when {
        !enabled || !connected || sp == null || !sp.available -> null
        sp.trackName == null -> "停止中"
        sp.playing -> "再生中"
        else -> "一時停止中"
    }
    WdCard("Spotify", modifier, note = note, titleColor = Wd.Green, borderColor = Wd.Green.copy(alpha = 0.5f)) {
        when {
            !enabled || !connected -> Idle("Spotify は未連携です。\n設定画面から連携してください。")
            sp == null || !sp.available -> Idle(sp?.lastError?.let { "取得できません: $it" } ?: "接続中…")
            sp.trackName == null -> Idle("再生中の曲はありません。")
            else -> {
                val showCtl = display.spotifyShowControls
                val showTime = display.spotifyShowProgress
                val big = !showCtl && !showTime
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        val cover = if (big) vh(12f, 64.dp, 100.dp) else vh(9f, 56.dp, 74.dp)
                        Box(
                            Modifier.size(cover).clip(RoundedCornerShape(10.dp)).background(Wd.Surface2)
                                .border(1.dp, Wd.BorderSoft, RoundedCornerShape(10.dp)),
                        ) {
                            album?.takeIf { it.first == sp.albumImageUrl }?.let {
                                Image(it.second, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                        }
                        Spacer(Modifier.width(if (big) 14.dp else 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                sp.trackName,
                                fontSize = if (big) vhText(2.5f, 15f, 21f) else vhText(1.9f, 13f, 16f),
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 1.3.em,
                                maxLines = if (big) 3 else 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                sp.artistName.orEmpty(),
                                color = Wd.Text2,
                                fontSize = if (big) vhText(1.7f, 12f, 15f) else 11.5f.tu,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = if (big) 6.dp else 4.dp),
                            )
                        }
                    }
                    if (!big) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (showTime) progress(sp, now) else "", color = Wd.Text3, fontSize = 11.5f.tu, style = Tabular, modifier = Modifier.weight(1f))
                            if (showCtl) {
                                ControlButton(WdIcons.Previous) { onControl("previous") }
                                if (sp.playing) ControlButton(WdIcons.Pause) { onControl("pause") }
                                else ControlButton(WdIcons.Play) { onControl("play") }
                                ControlButton(WdIcons.Next) { onControl("next") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Idle(text: String) {
    Text(text, color = Wd.Text3, fontSize = 13.tu, lineHeight = 1.7.em)
}

@Composable
private fun ControlButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp, 34.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Wd.Text2, modifier = Modifier.size(22.dp))
    }
}

/** 再生位置は 5 秒おきにしか届かないので、受け取ってからの経過を足して毎秒進める。 */
private fun progress(sp: SpotifyState, now: Long): String {
    var pos = sp.progressMs ?: return ""
    if (sp.playing && sp.fetchedAt > 0) pos += max(0L, now - sp.fetchedAt)
    sp.durationMs?.let { pos = minOf(pos, it) }
    fun mmss(ms: Long): String {
        val total = (ms / 1000.0).roundToInt().coerceAtLeast(0)
        return "${total / 60}:%02d".format(total % 60)
    }
    return mmss(pos) + (sp.durationMs?.let { " / " + mmss(it) } ?: "")
}
