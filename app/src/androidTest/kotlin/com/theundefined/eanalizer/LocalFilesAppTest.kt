package com.theundefined.eanalizer

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.theundefined.eanalizer.ui.zl
import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * End-to-end check of the app on local files only, in Polish: the synthetic Enea CSVs
 * (`src/sharedTest/fixtures`) are put where imported files live and the app runs in local-only
 * mode, so it never contacts Enea. It must show the same costs as the Python eanalizer for the
 * default analysis (G11, last 365 days = 2025). Public PSE prices (RCE/RCEm) are downloaded as in
 * real use; one test switches the network off to check the app copes without them. Every test saves
 * screenshots (see [screenshot]).
 */
@RunWith(AndroidJUnit4::class)
class LocalFilesAppTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val testName = TestName()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: Context = instrumentation.targetContext
    private val polish = Locale.forLanguageTag("pl-PL")
    /** Resources in the app's language (Polish), for expected texts. */
    private val res: Context =
        app.createConfigurationContext(
            Configuration(app.resources.configuration).apply { setLocale(polish) }
        )
    private val golden =
        JSONObject(
            instrumentation.context.assets.open("eanalizer-golden.json").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        )
    private var scenario: ActivityScenario<MainActivity>? = null
    private var shots = 0
    private var offline = false

    private fun str(id: Int, vararg args: Any) = res.getString(id, *args)

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
     * Polls between idle states instead of acting inside `waitUntil`.
     */
    private fun waitFor(text: String, timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            compose.waitForIdle()
            if (nodes(text).fetchSemanticsNodes().isNotEmpty() || scrollTo(text)) return
            if (System.currentTimeMillis() > deadline)
                throw AssertionError("'$text' not shown; screen: ${screenTexts()}")
            Thread.sleep(500)
        }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
            // Reading to the end waits for the command to finish.
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
    }

    private fun setAirplaneMode(on: Boolean) {
        shell("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
        offline = on
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
        // Polish UI (the app's main language) whatever the emulator's language; amounts are
        // formatted with the default locale.
        Locale.setDefault(polish)
        if (Build.VERSION.SDK_INT >= 33)
            app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList(polish)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        if (offline) setAirplaneMode(false)
    }

    /**
     * Saves a PNG of the screen as `<test>-<n>-<name>.png` into AGP's additional test output
     * (pulled to `app/build/outputs/connected_android_test_additional_output/`, CI artifact
     * `ui-screenshots`), so a run shows what the app displayed. Waits briefly for background work
     * (progress indicators) first.
     */
    private fun screenshot(name: String) {
        runCatching {
            compose.waitUntil(15_000) {
                compose
                    .onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                    .fetchSemanticsNodes()
                    .isEmpty()
            }
        }
        compose.waitForIdle()
        val dir =
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: File(app.getExternalFilesDir(null), "screenshots")
        dir.mkdirs()
        val file = File(dir, "%s-%02d-%s.png".format(testName.methodName, ++shots, name))
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        screenshot("main")
        waitFor(zl(cost2025("G11")))
        screenshot("main-summary")

        open(R.string.screen_compare)
        for (tariff in listOf("G11", "G12", "G12w")) waitFor(zl(cost2025(tariff)))
        screenshot("compare")
    }

    @Test
    fun everyReportOpens() {
        prepare()
        waitFor(zl(cost2025("G11")))
        val reports =
            listOf(
                R.string.screen_bills to "bills",
                R.string.screen_storage to "storage",
                R.string.screen_extraload to "extraload",
                R.string.screen_monthly to "monthly",
                R.string.screen_yoy to "yoy",
                R.string.screen_profile to "profile",
                R.string.screen_power to "power",
                R.string.screen_selfuse to "selfuse",
                R.string.screen_data to "data",
            )
        for ((title, name) in reports) {
            open(title)
            screenshot(name)
            // The app's own back arrow: Espresso's pressBack needs window focus, which a system
            // dialog (e.g. a launcher ANR on a slow CI emulator) can take away.
            compose.onAllNodesWithContentDescription(str(R.string.back)).onFirst().performClick()
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
    fun rceReportDownloadsPsePrices() {
        prepare()
        waitFor(zl(cost2025("G11")))
        open(R.string.screen_rce)
        nodes(str(R.string.rce_load, "2025-01-01", "2025-12-31")).onFirst().performClick()
        // A year of hourly prices: one PSE request per day.
        waitFor(str(R.string.rce_balance), timeoutMs = 240_000)
        nodes(str(R.string.rce_failed_days, 0).substringBefore("0")).assertCountEquals(0)
        screenshot("rce")
    }

    @Test
    fun netBillingValuesDepositWithRcem() {
        prepare("""{"mode":"NET_BILLING"}""")
        waitFor(str(R.string.nb_deposit_value))
        nodes(str(R.string.nb_prices_unavailable)).assertCountEquals(0)
        screenshot("netbilling")
    }

    @Test
    fun netBillingWithoutNetworkDoesNotCrash() {
        // Without PSE prices the analysis still completes and says so.
        setAirplaneMode(true)
        prepare("""{"mode":"NET_BILLING"}""")
        waitFor(str(R.string.nb_prices_unavailable))
        screenshot("netbilling-offline")
        waitFor(str(R.string.data_range, "2024-10-01", "2025-12-31"))
    }
}
