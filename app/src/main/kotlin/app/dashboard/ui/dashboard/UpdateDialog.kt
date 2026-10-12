package app.dashboard.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dashboard.AppGraph
import app.dashboard.BuildConfig
import app.dashboard.data.AppUpdater
import app.dashboard.data.ReleaseNotes
import app.dashboard.data.UpdateState
import app.dashboard.data.WhatsNew
import app.dashboard.i18n.L
import app.dashboard.ui.settings.ActionButton
import app.dashboard.ui.theme.LocalAccent
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import kotlinx.coroutines.launch

/**
 * 新しい版の案内（画面の真ん中）。起動時に新しい版が見つかったときと、フッターのダウンロードのボタンで開く。
 * 「アップデート」でダウンロードして、終わったらシステムのインストーラーを開く。「しない」で閉じるだけ。
 */
@Composable
fun UpdateDialog(graph: AppGraph) {
    val updater = graph.updater
    val state by updater.state.collectAsStateWithLifecycle()
    DialogFrame(onBack = updater::dismiss) {
        val (title, color) = when (state) {
            is UpdateState.Downloading -> L("ダウンロードしています", "Downloading") to Wd.Text
            is UpdateState.Ready -> L("ダウンロードが終わりました", "Download complete") to Wd.Text
            is UpdateState.Failed -> L("アップデートできませんでした", "Update failed") to Wd.Red
            is UpdateState.UpToDate -> L("最新のバージョンです", "You're up to date") to Wd.Text
            else -> L("新しいバージョンが公開されました", "A new version is available") to Wd.Text
        }
        Text(title, color = color, fontSize = 19.tu, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        UpdateDetails(updater, state)
        // 何が変わるか（リリースの説明文）。ダウンロード中・済みも同じ版のノートを出したままにする
        val release = when (val s = state) {
            is UpdateState.Available -> s.release
            is UpdateState.Downloading -> s.release
            is UpdateState.Ready -> s.release
            is UpdateState.Failed -> s.release
            else -> null
        }
        if (release != null) NotesBox(release.notes, Modifier.weight(1f, fill = false).padding(top = 14.dp))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            when (state) {
                is UpdateState.Available, is UpdateState.Ready, is UpdateState.Failed -> {
                    ActionButton(L("しない", "Not now"), updater::dismiss)
                    ActionButton(
                        when (state) {
                            is UpdateState.Ready -> L("インストール", "Install")
                            is UpdateState.Failed -> L("再試行", "Retry")
                            else -> L("アップデート", "Update")
                        },
                        { graph.scope.launch { updater.downloadAndInstall() } },
                        primary = true,
                    )
                }
                // ダウンロードは閉じても続く（終わればインストーラーが開く）
                else -> ActionButton(L("閉じる", "Close"), updater::dismiss)
            }
        }
    }
}

/** 更新のあとに初めて起動したときの「何が変わったか」（画面の真ん中）。 */
@Composable
fun WhatsNewDialog(graph: AppGraph, whatsNew: WhatsNew) {
    val updater = graph.updater
    DialogFrame(onBack = updater::dismissWhatsNew) {
        Text(L("${whatsNew.label} に更新しました", "Updated to ${whatsNew.label}"), fontSize = 19.tu, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        DetailText(L("このバージョンで変わったこと", "What's new in this version"))
        NotesBox(whatsNew.notes, Modifier.weight(1f, fill = false).padding(top = 14.dp))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ActionButton(L("閉じる", "Close"), updater::dismissWhatsNew, primary = true)
        }
    }
}

/** 真ん中の箱。暗い幕で下のダッシュボードに触れさせない（幕を押しても閉じない。ボタンか戻る操作で閉じる）。 */
@Composable
private fun DialogFrame(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(min = 340.dp, max = 560.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Wd.Surface)
                .border(1.dp, LocalAccent.current.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .padding(horizontal = 26.dp, vertical = 22.dp),
            content = content,
        )
    }
}

/** リリースノート。長ければこの枠の中だけ縦にスクロールする（ボタンは常に見える）。 */
@Composable
private fun NotesBox(notes: String, modifier: Modifier = Modifier) {
    val lines = remember(notes) { ReleaseNotes.parse(notes) }
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Wd.Bg.copy(alpha = 0.6f))
            .border(1.dp, Wd.BorderSoft, RoundedCornerShape(12.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (lines.isEmpty()) {
            Text(L("リリースノートはありません", "No release notes"), color = Wd.Text3, fontSize = 13.tu)
        }
        lines.forEachIndexed { i, line ->
            when {
                line.heading -> Text(
                    line.text, color = LocalAccent.current, fontSize = 13.tu, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = if (i == 0) 0.dp else 10.dp, bottom = 2.dp),
                )
                line.bullet -> Row(Modifier.padding(start = (14 * (line.depth - 1)).dp, top = 2.dp)) {
                    Text(if (line.depth > 1) "–" else "・", color = Wd.Text3, fontSize = 13.tu, modifier = Modifier.width(16.dp))
                    Text(line.text, color = Wd.Text, fontSize = 13.tu, lineHeight = 1.5.em)
                }
                else -> Text(line.text, color = Wd.Text2, fontSize = 13.tu, lineHeight = 1.5.em, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** 版・大きさ・進み具合・失敗の理由。設定の「情報」でも使う。 */
@Composable
fun UpdateDetails(updater: AppUpdater, state: UpdateState) {
    val current = BuildConfig.VERSION_LABEL
    when (state) {
        is UpdateState.Available -> DetailText(versionLine(current, state.release.label, state.release.size))
        is UpdateState.Downloading -> {
            DetailText(versionLine(current, state.release.label, state.release.size))
            val total = if (state.total > 0) state.total else state.release.size
            val k = if (total > 0) (state.done.toFloat() / total).coerceIn(0f, 1f) else 0f
            Spacer(Modifier.height(10.dp))
            ProgressBar(k)
            DetailText("${(k * 100).toInt()}%  ・  ${mb(state.done)} / ${mb(total)}")
        }
        is UpdateState.Ready -> {
            // 許可の画面から戻ったときに読み直す
            var canInstall by remember { mutableStateOf(updater.canInstall()) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canInstall = updater.canInstall() }
            DetailText(versionLine(current, state.release.label, state.release.size))
            DetailText(
                if (canInstall) L("「インストール」を押すと、Android のインストールの画面が開きます。設定はそのまま残ります。", "Tap Install to open Android's installer. Your settings are kept.")
                else L("先に「この提供元のアプリを許可」をオンにする画面が開きます。オンにして戻ったら、もう一度「インストール」を押してください。", "First, a screen opens to turn on \"Allow from this source\". Turn it on, come back and tap Install again."),
            )
        }
        is UpdateState.Failed -> DetailText(state.message, Wd.Red)
        is UpdateState.UpToDate -> DetailText(L("最新です", "You're up to date"), Wd.Green)
        UpdateState.Checking -> DetailText(L("確認しています…", "Checking…"))
        UpdateState.Idle -> DetailText(L("いまのバージョン: ", "Current version: ") + current)
    }
}

@Composable
private fun DetailText(text: String, color: Color = Wd.Text2) {
    Text(text, color = color, fontSize = 14.tu, lineHeight = 1.55.em, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ProgressBar(k: Float) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Wd.Border)) {
        Box(Modifier.fillMaxWidth(k).height(6.dp).background(LocalAccent.current))
    }
}

private fun versionLine(current: String, next: String, size: Long): String =
    "$current  →  $next" + if (size > 0) L("  （${mb(size)}）", "  (${mb(size)})") else ""

private fun mb(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)
