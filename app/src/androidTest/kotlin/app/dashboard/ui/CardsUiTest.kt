package app.dashboard.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dashboard.data.GithubDay
import app.dashboard.data.GithubPublic
import app.dashboard.data.GithubRepo
import app.dashboard.data.GithubState
import app.dashboard.data.ShipStream
import app.dashboard.i18n.Lang
import app.dashboard.ui.dashboard.CalculatorCard
import app.dashboard.ui.dashboard.GithubCard
import app.dashboard.ui.dashboard.ShipCard
import app.dashboard.ui.map.MapFrame
import app.dashboard.ui.map.MapMath
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * カードの UI テスト（Compose UI Test。実機かエミュレーターで `./gradlew connectedDebugAndroidTest`）。
 * 通信はしない（状態を直接渡す）。
 */
@RunWith(AndroidJUnit4::class)
class CardsUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /**
     * 壁掛けの端末はロック画面のままのことが多い（ダッシュボード自身はロック画面の上に出る）。
     * テストの画面もロック画面の上に出して、ロックを外さずに動かせるようにする。
     */
    @Before
    fun showOverLockScreen() {
        rule.activityRule.scenario.onActivity {
            it.setShowWhenLocked(true)
            it.setTurnScreenOn(true)
        }
    }

    @After
    fun reset() {
        Lang.current = Lang.JA
    }

    private fun key(label: String) = rule.onNode(hasText(label) and hasClickAction())

    @Test
    fun calculatorMultipliesBeforeAdding() {
        rule.setContent { CalculatorCard(Modifier.size(420.dp, 300.dp)) }
        listOf("1", "2", "+", "3", "×", "2", "=").forEach { key(it).performClick() }
        rule.onNodeWithText("18").assertIsDisplayed()
        rule.onNodeWithText("12 + 3 × 2 =").assertIsDisplayed()
    }

    @Test
    fun calculatorShowsDivideByZeroInEnglish() {
        Lang.current = Lang.EN
        rule.setContent { CalculatorCard(Modifier.size(420.dp, 300.dp)) }
        rule.onNodeWithText("Calculator").assertIsDisplayed()
        listOf("5", "÷", "0", "=").forEach { key(it).performClick() }
        rule.onNodeWithText("Can't divide by 0").assertIsDisplayed()
    }

    private val github = GithubState(
        user = "octocat", followers = 1200, stars = 3456, days = 30, commits = 42, pulls = 7, issues = 3,
        calendar = (1..60).map { GithubDay("2026-08-%02d".format((it - 1) % 28 + 1), it % 5, it % 5) },
        yearTotal = 999,
        repos = listOf(GithubRepo("hello-world", 2500, language = "Kotlin"), GithubRepo("spoon-knife", 900)),
        fetchedAt = 1L,
    )

    @Test
    fun githubCardShowsTheChosenItems() {
        val c = GithubPublic(user = "octocat", showIssues = false)
        rule.setContent { GithubCard(github, c, now = 60_000L, modifier = Modifier.size(560.dp, 340.dp)) }
        rule.onNodeWithText("42").assertIsDisplayed()
        rule.onNodeWithText("コミット").assertIsDisplayed()
        rule.onNodeWithText("3,456").assertIsDisplayed()
        rule.onNodeWithText("hello-world").assertIsDisplayed()
        // Issue は出さない設定
        rule.onNodeWithText("Issue").assertDoesNotExist()
    }

    @Test
    fun githubCardAsksForAUserName() {
        Lang.current = Lang.EN
        rule.setContent { GithubCard(null, GithubPublic(user = ""), now = 0L, modifier = Modifier.size(560.dp, 340.dp)) }
        rule.onNodeWithText("Enter a user name in Settings → GitHub").assertIsDisplayed()
    }

    @Test
    fun shipCardExplainsTheMissingKeyAndHasMapButtons() {
        val zoom = 10
        val frame = MapFrame(
            zoom = zoom, centerX = MapMath.x(139.7, zoom), centerY = MapMath.y(35.4, zoom),
            homeX = MapMath.x(139.7, zoom), homeY = MapMath.y(35.4, zoom), minZoom = 3, maxZoom = 14,
        )
        var zoomed = 0
        rule.setContent {
            ShipCard(frame, emptyList(), ShipStream.Status.NoKey, 0L, { _, _ -> }, { zoomed += it }, {}, {}, Modifier.size(420.dp, 300.dp))
        }
        rule.onNodeWithText("設定の「船舶」で aisstream.io の API キーを入れてください").assertIsDisplayed()
        rule.onNodeWithContentDescription("拡大").assertIsEnabled()
        // 地点から動かしていないので「現在地に戻る」は押せない
        rule.onNodeWithContentDescription("現在地に戻る").assertIsNotEnabled()
        rule.onNode(hasText("＋") and hasClickAction()).performClick()
        rule.runOnIdle { check(zoomed == 1) }
    }
}
