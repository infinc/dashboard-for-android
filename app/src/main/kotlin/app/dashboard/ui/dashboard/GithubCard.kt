package app.dashboard.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dashboard.data.GithubDay
import app.dashboard.data.GithubPublic
import app.dashboard.data.GithubState
import app.dashboard.i18n.L
import app.dashboard.ui.common.EmptyText
import app.dashboard.ui.common.Tabular
import app.dashboard.ui.common.WdCard
import app.dashboard.ui.theme.Wd
import app.dashboard.ui.theme.tu
import app.dashboard.ui.theme.vhText
import java.util.Locale

/** GitHub のコントリビューションの色（0〜4 段）。テーマに合わせて GitHub と同じ緑を使う。 */
private fun levelColor(level: Int): Color = if (Wd.palette.light) {
    listOf(Color(0xFFEBEDF0), Color(0xFF9BE9A8), Color(0xFF40C463), Color(0xFF30A14E), Color(0xFF216E39))[level.coerceIn(0, 4)]
} else {
    listOf(Wd.Surface2, Color(0xFF0E4429), Color(0xFF006D32), Color(0xFF26A641), Color(0xFF39D353))[level.coerceIn(0, 4)]
}

/**
 * GitHub カード。直近のコミット・プルリクエスト・Issue の数、コントリビューションの絵、スターの多いリポジトリ、フォロワーとスターの合計。
 * 出すものは設定の「GitHub」で選ぶ（[GithubPublic] の show*）。
 */
@Composable
fun GithubCard(s: GithubState?, c: GithubPublic, now: Long, modifier: Modifier) {
    val note = when {
        c.user.isBlank() -> null
        s == null || s.fetchedAt == 0L -> "@${c.user}"
        else -> L("@${c.user} ・ ", "@${c.user} · ") + relative(s.fetchedAt, now)
    }
    WdCard("GitHub", modifier, note = note) {
        when {
            c.user.isBlank() -> EmptyText(L("設定の「GitHub」でユーザー名を入れてください", "Enter a user name in Settings → GitHub"))
            s == null || s.user != c.user.trim() -> EmptyText(L("取得中…", "Loading…"))
            s.fetchedAt == 0L -> EmptyText(s.lastError ?: L("取得中…", "Loading…"), if (s.lastError != null) Wd.Red else Wd.Text3)
            else -> GithubBody(s, c)
        }
    }
}

@Composable
private fun GithubBody(s: GithubState, c: GithubPublic) {
    Column(Modifier.fillMaxWidth().clipToBounds(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val stats = buildList {
            if (c.showCommits) add(L("コミット", "Commits") to s.commits)
            if (c.showPulls) add(L("プルリク", "PRs") to s.pulls)
            if (c.showIssues) add("Issue" to s.issues)
            if (c.showProfile) {
                add(L("スター", "Stars") to s.stars)
                add(L("フォロワー", "Followers") to s.followers)
            }
        }
        if (stats.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                stats.forEach { (label, value) ->
                    Column {
                        Text(value?.let { "%,d".format(Locale.US, it) } ?: "—", style = Tabular, fontSize = vhText(2.6f, 16f, 24f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(label, color = Wd.Text3, fontSize = 11.tu, maxLines = 1)
                    }
                }
            }
            if (c.showCommits || c.showPulls || c.showIssues) {
                Text(
                    L("コミット・プルリク・Issue は直近 ${s.days} 日", "Commits, PRs and issues: last ${s.days} days"),
                    color = Wd.Text3, fontSize = 10.5f.tu, maxLines = 1,
                )
            }
        }
        if (c.showGraph && s.calendar.isNotEmpty()) {
            ContributionGraph(s.calendar)
            s.yearTotal?.let {
                Text(L("この 1 年で %,d 件のコントリビューション", "%,d contributions in the last year").format(Locale.US, it), color = Wd.Text3, fontSize = 10.5f.tu, maxLines = 1)
            }
        }
        if (c.showRepos && s.repos.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                s.repos.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, fontSize = 13.tu, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        r.language?.let { Text(it, color = Wd.Text3, fontSize = 11.tu, maxLines = 1, modifier = Modifier.padding(start = 8.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Text("★ %,d".format(Locale.US, r.stars), color = Wd.Amber, style = Tabular, fontSize = 12.tu, maxLines = 1)
                    }
                }
            }
        }
        s.lastError?.let { Text(it, color = Wd.Amber, fontSize = 11.tu, maxLines = 2) }
    }
}

/** 週ごとの縦 7 マス（日曜が上）。幅に入るだけ、新しい週から並べる。 */
@Composable
private fun ContributionGraph(days: List<GithubDay>) {
    val weeks = remember(days) { weeksOf(days) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 2.dp
        val cell = ((maxWidth + gap) / 53 - gap).coerceIn(6.dp, 11.dp)
        val count = ((maxWidth + gap) / (cell + gap)).toInt().coerceIn(1, weeks.size)
        val shown = weeks.takeLast(count)
        val colors = (0..4).map { levelColor(it) }
        Canvas(Modifier.width((cell + gap) * shown.size - gap).height((cell + gap) * 7 - gap)) {
            val c = cell.toPx()
            val g = gap.toPx()
            shown.forEachIndexed { x, week ->
                week.forEach { (dow, day) ->
                    drawRoundRect(
                        colors[day.level], Offset(x * (c + g), dow * (c + g)), Size(c, c), CornerRadius(c * 0.22f),
                    )
                }
            }
        }
    }
}

/** 日ごとの並びを、日曜始まりの週（曜日の番号 0〜6 と日）の並びにする。 */
internal fun weeksOf(days: List<GithubDay>): List<List<Pair<Int, GithubDay>>> {
    val out = mutableListOf<MutableList<Pair<Int, GithubDay>>>()
    days.forEach { d ->
        val date = runCatching { java.time.LocalDate.parse(d.date) }.getOrNull() ?: return@forEach
        val dow = date.dayOfWeek.value % 7
        if (out.isEmpty() || dow == 0) out += mutableListOf<Pair<Int, GithubDay>>()
        out.last() += dow to d
    }
    return out
}
