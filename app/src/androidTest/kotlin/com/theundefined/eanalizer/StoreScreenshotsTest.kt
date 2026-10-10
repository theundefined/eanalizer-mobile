package com.theundefined.eanalizer

import android.app.LocaleManager
import android.app.UiModeManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Store screenshots (Google Play + project page) - NOT a regression test. Skipped unless the runner
 * gets `storeScreenshots=true` (workflow `screenshots.yml`, manual only). Everything runs on the
 * synthetic demo data (no Enea account, no network needed apart from public PSE prices).
 *
 * `night=true` switches the app to the dark theme; files are named `store_light_NN_name.png` /
 * `store_dark_NN_name.png`.
 */
// Per-app language (LocaleManager) exists since API 33.
@SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class StoreScreenshotsTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(OnDemandRule).around(composeRule)

    @Test
    fun tour() {
        waitForText(str(R.string.demo_start))
        composeRule.onNodeWithText(str(R.string.demo_start)).performClick()
        waitForText(str(R.string.demo_title))
        settle(3_000)
        shot("01_main")

        scrollToAndOpen(str(R.string.screen_compare))
        settle(2_000)
        shot("02_compare")
        back()

        scrollToAndOpen(str(R.string.screen_bills))
        settle(2_000)
        shot("03_bills")
        back()

        scrollToAndOpen(str(R.string.screen_monthly))
        settle(2_000)
        shot("04_monthly")
        back()

        scrollToAndOpen(str(R.string.screen_profile))
        settle(2_000)
        shot("05_profile")
        back()

        scrollToAndOpen(str(R.string.screen_storage))
        settle(2_000)
        shot("06_storage")
    }

    private fun str(id: Int, vararg args: Any) = targetContext.getString(id, *args)

    private fun waitForText(text: String, timeoutMs: Long = 20_000) {
        composeRule.waitUntil(timeoutMs) {
            composeRule
                .onAllNodes(hasText(text, substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    /** Opens a report from the main list; the entry may be off screen in the LazyColumn. */
    private fun scrollToAndOpen(title: String) {
        composeRule
            .onAllNodes(hasScrollToNodeAction())
            .onFirst()
            .performScrollToNode(hasText(title, substring = false))
        composeRule.onNodeWithText(title, substring = false).performClick()
        // The title is also the menu entry - the back button only exists on the report screen.
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodes(hasContentDescription(str(R.string.back)))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun back() {
        composeRule.onNodeWithContentDescription(str(R.string.back)).performClick()
        composeRule.waitForIdle()
    }

    private fun settle(millis: Long) {
        composeRule.waitForIdle()
        SystemClock.sleep(millis)
    }

    private fun shot(name: String) {
        composeRule.waitForIdle()
        takeScreenshot("store_${if (night) "dark" else "light"}_$name")
    }

    companion object {
        private val arguments
            get() = InstrumentationRegistry.getArguments()

        private val targetContext
            get() = InstrumentationRegistry.getInstrumentation().targetContext

        private val enabled: Boolean
            get() = arguments.getString("storeScreenshots") == "true"

        private val night: Boolean
            get() = arguments.getString("night") == "true"

        /**
         * Skips each test unless screenshots were requested - per test, not in @BeforeClass, so
         * ordinary instrumented runs report it as skipped predictably.
         */
        private val OnDemandRule = TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    assumeTrue("Screenshots on demand only (storeScreenshots=true)", enabled)
                    base.evaluate()
                }
            }
        }

        @BeforeClass
        @JvmStatic
        fun setUpDevice() {
            if (!enabled) return
            // Fresh state: no demo/login left from an earlier run.
            targetContext.getSharedPreferences("settings", 0).edit().clear().commit()
            applyLocaleAndTheme()
            cleanStatusBar()
        }

        /**
         * Polish UI regardless of the emulator language and the theme from `night`. Waits until the
         * configuration reaches the process, otherwise screenshots silently come out in English or
         * in the wrong theme.
         */
        private fun applyLocaleAndTheme() {
            targetContext.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags("pl-PL")
            targetContext
                .getSystemService(UiModeManager::class.java)
                .setApplicationNightMode(
                    if (night) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
                )
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                val config = targetContext.resources.configuration
                val isNight =
                    config.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES
                if (config.locales[0].language == "pl" && isNight == night) return
                SystemClock.sleep(200)
            }
            error("Language pl / theme night=$night did not reach the app in 10 s")
        }

        /** Clean screen: no system error dialogs, status bar in SystemUI demo mode. */
        private fun cleanStatusBar() {
            val demo = "am broadcast -a com.android.systemui.demo -e command"
            listOf(
                    // ANRs of the system (e.g. the launcher on a fresh emulator) would cover shots.
                    "settings put global hide_error_dialogs 1",
                    "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS",
                    "settings put global sysui_demo_allowed 1",
                    "$demo enter",
                    "$demo clock -e hhmm 1000",
                    "$demo battery -e level 100 -e plugged false",
                    "$demo network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4",
                    "$demo notifications -e visible false",
                )
                .forEach { shell(it) }
        }

        // Read the output to the end - only then the command has certainly run.
        private fun shell(command: String) {
            val pfd =
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }

        /**
         * Whole-device screenshot (UiAutomation, so menus and the status bar are included) into the
         * dir AGP collects as additional test output.
         */
        private fun takeScreenshot(name: String) {
            val base =
                arguments.getString("additionalTestOutputDir")?.let(::File)
                    ?: targetContext.getExternalFilesDir(null)
                    ?: targetContext.filesDir
            val dir = File(base, "screenshots").apply { mkdirs() }
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(dir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
