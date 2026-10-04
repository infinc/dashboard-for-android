package app.dashboard.data

import android.util.Log
import app.dashboard.i18n.L
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * GitHub カード。ユーザーの公開の情報を 30 分ごとに取る。
 *
 * - プロフィール（フォロワー・公開リポジトリ数）: `GET /users/{user}`
 * - リポジトリ（スターの多い順・スターの合計）: `GET /users/{user}/repos`（フォークは除く）
 * - 期間のコミット・プルリクエスト・Issue の数: 検索 API の `total_count`
 * - コントリビューションの絵: プロフィールのページと同じ `github.com/users/{user}/contributions`（API に無いため HTML を読む）
 *
 * トークンは任意。無くても公開の情報は読める（API は 1 時間に 60 回・検索は 1 分に 10 回まで。1 回の更新で 5 回使う）。
 */
class GithubRepository(private val client: HttpClient, private val configStore: ConfigStore) {

    @Volatile
    var state: GithubState = GithubState()
        private set

    private var lastAttemptAt = 0L
    private var lastKey: Any? = null
    /** 見回りと「全て保存」の取り直しが同時に取りに行かないようにする。 */
    private val lock = kotlinx.coroutines.sync.Mutex()

    suspend fun refreshIfDue(wanted: Boolean) {
        if (!wanted) return
        lock.lock()
        try { refreshIfDueLocked() } finally { lock.unlock() }
    }

    private suspend fun refreshIfDueLocked() {
        val c = configStore.get().github
        val key = Triple(c.user, c.token, c.days)
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAttemptAt < (if (state.lastError != null) RETRY_MS else INTERVAL_MS)) return
        lastKey = key
        refresh(c)
    }

    suspend fun refreshNow() {
        lock.lock()
        try { refresh(configStore.get().github) } finally { lock.unlock() }
    }

    private suspend fun refresh(c: GithubConfig) {
        lastAttemptAt = System.currentTimeMillis()
        val user = c.user.trim()
        if (user.isEmpty()) {
            state = GithubState()
            return
        }
        if (!USER.matches(user)) {
            state = GithubState(user = user, lastError = L("ユーザー名の形が正しくありません", "The user name is not valid"))
            return
        }
        try {
            state = load(user, c.token?.trim()?.takeIf { it.isNotEmpty() }, c.days)
        } catch (e: Exception) {
            Log.w(TAG, "GitHub の取得に失敗", e)
            // 前の値は残してエラーだけ添える（同じ人なら）
            state = (if (state.user == user) state else GithubState(user = user, days = c.days)).copy(lastError = describe(e))
        }
    }

    private suspend fun load(user: String, token: String?, days: Int): GithubState {
        fun HttpRequestBuilder.auth() {
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            header("User-Agent", USER_AGENT)
            if (token != null) header("Authorization", "Bearer $token")
        }
        val profile = Http.json.parseToJsonElement(client.get("$API/users/$user") { auth() }.bodyAsText()).jsonObject
        val repos = Http.json.parseToJsonElement(
            client.get("$API/users/$user/repos") {
                auth()
                parameter("per_page", 100)
                parameter("sort", "pushed")
                parameter("type", "owner")
            }.bodyAsText(),
        ).jsonArray.mapNotNull { it as? JsonObject }.filter { it["fork"]?.jsonPrimitive?.booleanOrNull != true }
        val since = LocalDate.now().minusDays(days.toLong()).toString()
        // トークンがあれば GraphQL で、プロフィールの数え方と同じ（フォークやミラーを数えない）正確な数を取る。だめなら検索 API へ
        val graph = if (token == null) null else runCatching { graphql(user, token, days) }
            .onFailure { Log.w(TAG, "GitHub の GraphQL に失敗。検索 API で数える", it) }.getOrNull()
        suspend fun count(kind: String, q: String): Int? = runCatching {
            val body = client.get("$API/search/$kind") {
                auth()
                parameter("q", q)
                parameter("per_page", 1)
            }.bodyAsText()
            Http.json.parseToJsonElement(body).jsonObject["total_count"]?.jsonPrimitive?.intOrNull
        }.onFailure { Log.w(TAG, "GitHub の検索に失敗: $kind", it) }.getOrNull()
        val commits = graph?.commits ?: count("commits", "author:$user author-date:>=$since")
        val pulls = graph?.pulls ?: count("issues", "author:$user type:pr created:>=$since")
        val issues = graph?.issues ?: count("issues", "author:$user type:issue created:>=$since")
        val calendar = graph?.calendar?.takeIf { it.isNotEmpty() } ?: runCatching {
            parseContributions(client.get("$WEB/users/$user/contributions") {
                header("User-Agent", USER_AGENT)
                // 既定の Accept（application/json）だと 406 で断られる
                header("Accept", "text/html")
            }.bodyAsText())
        }.onFailure { Log.w(TAG, "コントリビューションの取得に失敗", it) }.getOrDefault(emptyList())
        val repoList = repos.map { r ->
            GithubRepo(
                name = r["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                stars = r["stargazers_count"]?.jsonPrimitive?.intOrNull ?: 0,
                forks = r["forks_count"]?.jsonPrimitive?.intOrNull ?: 0,
                language = r["language"]?.jsonPrimitive?.contentOrNull,
                description = r["description"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return GithubState(
            user = user,
            name = profile["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            followers = profile["followers"]?.jsonPrimitive?.intOrNull,
            publicRepos = profile["public_repos"]?.jsonPrimitive?.intOrNull,
            stars = repoList.sumOf { it.stars },
            days = days,
            commits = commits,
            pulls = pulls,
            issues = issues,
            calendar = calendar,
            yearTotal = calendar.takeIf { it.isNotEmpty() }?.sumOf { it.count },
            repos = repoList.sortedWith(compareByDescending<GithubRepo> { it.stars }.thenByDescending { it.forks }).take(MAX_REPOS),
            fetchedAt = System.currentTimeMillis(),
            lastError = if (commits == null && pulls == null && issues == null) L("検索 API の回数の上限です（少し待つと戻ります）", "Search API rate limit reached (will recover shortly)") else null,
        )
    }

    private class Counts(val commits: Int?, val pulls: Int?, val issues: Int?, val calendar: List<GithubDay>)

    /** トークンがあるときだけ使う GraphQL（contributionsCollection）。 */
    private suspend fun graphql(user: String, token: String, days: Int): Counts {
        val to = java.time.OffsetDateTime.now()
        val from = to.minusDays(days.toLong())
        val query = "query(\$login:String!,\$from:DateTime!,\$to:DateTime!){user(login:\$login){" +
            "range:contributionsCollection(from:\$from,to:\$to){totalCommitContributions totalPullRequestContributions totalIssueContributions}" +
            "year:contributionsCollection{contributionCalendar{weeks{contributionDays{date contributionCount contributionLevel}}}}}}"
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("query", kotlinx.serialization.json.JsonPrimitive(query))
            put("variables", kotlinx.serialization.json.buildJsonObject {
                put("login", kotlinx.serialization.json.JsonPrimitive(user))
                put("from", kotlinx.serialization.json.JsonPrimitive(from.toString()))
                put("to", kotlinx.serialization.json.JsonPrimitive(to.toString()))
            })
        }.toString()
        val body = client.post("$API/graphql") {
            header("Authorization", "Bearer $token")
            header("User-Agent", USER_AGENT)
            setBody(io.ktor.http.content.TextContent(payload, io.ktor.http.ContentType.Application.Json))
        }.bodyAsText()
        return parseGraphql(body)
    }

    private fun describe(e: Exception): String = when {
        e is ResponseException && e.response.status.value == 404 -> L("ユーザーが見つかりません", "User not found")
        e is ResponseException && e.response.status.value == 401 -> L("トークンが正しくありません", "The token is not valid")
        e is ResponseException && e.response.status.value in listOf(403, 429) ->
            L("API の回数の上限です。しばらく待つか、トークンを設定してください", "API rate limit reached. Wait a while or set a token")
        e is ResponseException -> L("取得できません（${e.response.status.value}）", "Unavailable (${e.response.status.value})")
        else -> L("取得できません", "Unavailable") + (e.message?.let { " — $it" } ?: "")
    }

    companion object {
        private const val TAG = "GithubRepository"
        private const val API = "https://api.github.com"
        private const val WEB = "https://github.com"
        private const val USER_AGENT = "Dashboard/0.1 (Android)"
        private const val INTERVAL_MS = 30 * 60_000L
        private const val RETRY_MS = 5 * 60_000L
        private const val MAX_REPOS = 8
        /** GitHub のユーザー名（英数字とハイフン、39 文字まで）。 */
        private val USER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")

        private val DAY = Regex("""<td\b[^>]*\bdata-date="(\d{4}-\d{2}-\d{2})"[^>]*>""")
        private val ATTR_ID = Regex("""\bid="([^"]+)"""")
        private val ATTR_LEVEL = Regex("""\bdata-level="(\d)"""")
        private val TIP = Regex("""<tool-tip\b[^>]*\bfor="([^"]+)"[^>]*>([^<]*)</tool-tip>""")
        private val COUNT = Regex("""^(\d[\d,]*)""")

        private val LEVELS = mapOf("NONE" to 0, "FIRST_QUARTILE" to 1, "SECOND_QUARTILE" to 2, "THIRD_QUARTILE" to 3, "FOURTH_QUARTILE" to 4)

        /** GraphQL の応答を読む。errors があれば例外（検索 API へ切り替える）。 */
        private fun parseGraphql(body: String): Counts {
            val root = Http.json.parseToJsonElement(body).jsonObject
            if (root["errors"] != null) error(root["errors"].toString().take(200))
            val u = root["data"]?.jsonObject?.get("user") as? JsonObject ?: error("no user")
            val range = u["range"]?.jsonObject
            val days = u["year"]?.jsonObject?.get("contributionCalendar")?.jsonObject?.get("weeks")?.jsonArray.orEmpty()
                .flatMap { it.jsonObject["contributionDays"]?.jsonArray.orEmpty() }
                .mapNotNull { d ->
                    val o = d.jsonObject
                    GithubDay(
                        o["date"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        o["contributionCount"]?.jsonPrimitive?.intOrNull ?: 0,
                        LEVELS[o["contributionLevel"]?.jsonPrimitive?.contentOrNull] ?: 0,
                    )
                }
            return Counts(
                range?.get("totalCommitContributions")?.jsonPrimitive?.intOrNull,
                range?.get("totalPullRequestContributions")?.jsonPrimitive?.intOrNull,
                range?.get("totalIssueContributions")?.jsonPrimitive?.intOrNull,
                days,
            )
        }

        /**
         * `github.com/users/{user}/contributions` の HTML から 1 日ずつ読む。
         * 各日は `<td data-date data-level id>`、その日の数は `<tool-tip for="{id}">3 contributions on …</tool-tip>`（無い日は "No contributions"）。
         */
        fun parseContributions(html: String): List<GithubDay> {
            val tips = TIP.findAll(html).associate { m ->
                m.groupValues[1] to (COUNT.find(m.groupValues[2].trim())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0)
            }
            return DAY.findAll(html).map { m ->
                val tag = m.value
                val id = ATTR_ID.find(tag)?.groupValues?.get(1)
                val level = ATTR_LEVEL.find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                GithubDay(m.groupValues[1], id?.let { tips[it] } ?: 0, level.coerceIn(0, 4))
            }.distinctBy { it.date }.sortedBy { it.date }.toList()
        }
    }
}
