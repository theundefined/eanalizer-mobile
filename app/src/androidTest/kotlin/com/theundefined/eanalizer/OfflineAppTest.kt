package com.theundefined.eanalizer

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrElse
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theundefined.eanalizer.ui.zl
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check of the app on local files only: the synthetic Enea CSVs
 * (`src/sharedTest/fixtures`) are put where imported files live, the app runs in local-only mode
 * (never contacts Enea; CI also disables the emulator's network) and must show the same costs as
 * the Python eanalizer for the default analysis (G11, last 365 days = 2025).
 */
@RunWith(AndroidJUnit4::class)
class OfflineAppTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: Context = instrumentation.targetContext
    private val golden =
        JSONObject(
            instrumentation.context.assets.open("eanalizer-golden.json").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        )
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)

    /** eanalizer total cost of [tariff] for 2025 (= the default "last 365 days"). */
    private fun cost2025(tariff: String) =
        golden
            .getJSONObject("tariffs")
            .getJSONObject(tariff)
            .getJSONObject("year2025")
            .getDouble("totalCost")

    private fun nodes(text: String): SemanticsNodeInteractionCollection =
        compose.onAllNodesWithText(text, substring = true)

    /** The screen's list (the outermost scrollable node). */
    private fun list() = compose.onAllNodes(hasScrollAction()).onFirst()

    private fun scrollTo(text: String): Boolean =
        runCatching { list().performScrollToNode(hasText(text, substring = true)) }.isSuccess

    /** Texts on screen, for failure messages. */
    private fun screenTexts(): String =
        runCatching {
                compose
                    .onAllNodes(hasText("", substring = true), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
                    .joinToString(" | ")
            }
            .getOrElse { "<$it>" }

    /**
     * Waits until [text] is shown, scrolling the list to it (lazy items off screen don't exist).
     */
    private fun waitFor(text: String, timeoutMs: Long = 60_000) {
        try {
            compose.waitUntil(timeoutMs) {
                nodes(text).fetchSemanticsNodes().isNotEmpty() || scrollTo(text)
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("'$text' not shown; screen: ${screenTexts()}", e)
        }
    }

    private fun prepare(prefs: String? = null) {
        val data = File(app.filesDir, "enea").apply { deleteRecursively() }
        File(app.filesDir, "prices").deleteRecursively()
        val imported = File(data, "imported").apply { mkdirs() }
        for (name in listOf("2024.csv", "2025.csv")) {
            instrumentation.context.assets.open("enea/$name").use { input ->
                File(imported, name).outputStream().use { input.copyTo(it) }
            }
        }
        app.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean("local_only", true)
            .apply { if (prefs != null) putString("analysis_prefs", prefs) }
            .commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    /** Scrolls the main list to the report [title] and opens it. */
    private fun open(title: Int) {
        list().performScrollToNode(hasText(str(title)))
        nodes(str(title)).onFirst().performClick()
    }

    @Test
    fun showsCostsFromLocalFilesWithoutEnea() {
        prepare()
        waitFor(str(R.string.data_range, "2024-10-01", "2025-12-31"))
        nodes(str(R.string.local_only_card)).assertCountEquals(1)
        // Local-only mode offers no Enea login.
        compose.onAllNodesWithText(str(R.string.login)).assertCountEquals(0)
        waitFor(zl(cost2025("G11")))

        open(R.string.screen_compare)
        for (tariff in listOf("G11", "G12", "G12w")) waitFor(zl(cost2025(tariff)))
    }

    @Test
    fun everyReportOpens() {
        prepare()
        waitFor(zl(cost2025("G11")))
        val reports =
            listOf(
                R.string.screen_compare,
                R.string.screen_bills,
                R.string.screen_storage,
                R.string.screen_extraload,
                R.string.screen_monthly,
                R.string.screen_yoy,
                R.string.screen_profile,
                R.string.screen_power,
                R.string.screen_selfuse,
                R.string.screen_data,
                R.string.screen_rce,
            )
        for (title in reports) {
            open(title)
            compose.waitForIdle()
            pressBack()
            // Back on the main screen (its top bar has the settings button).
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithContentDescription(str(R.string.settings))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
    }

    @Test
    fun netBillingWithoutPricesDoesNotCrash() {
        // Net-billing needs PSE prices; offline the analysis still completes and says so.
        prepare("""{"mode":"NET_BILLING"}""")
        waitFor(str(R.string.nb_prices_unavailable))
        waitFor(str(R.string.data_range, "2024-10-01", "2025-12-31"))
    }
}
