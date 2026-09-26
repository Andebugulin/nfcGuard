package com.andebugulin.nfcguard.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.data.ConfigManager
import com.andebugulin.nfcguard.testing.cancelViewModel
import com.andebugulin.nfcguard.testing.enableAccessibilityService
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.resetAppStateRepository
import com.andebugulin.nfcguard.testing.schedule
import com.andebugulin.nfcguard.testing.setDetectorState
import com.andebugulin.nfcguard.testing.tag
import com.andebugulin.nfcguard.ui.TestTags
import com.andebugulin.nfcguard.ui.home.SettingsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The settings sheet — permission rows, the anti-bypass toggle, the blocking
 * method readout and the data section.
 *
 * It survives on the JVM because it holds no text field of its own; only
 * `ChallengeDurationDialog`, which it opens, does, and a Compose dialog
 * containing an `OutlinedTextField` never reaches idle under Robolectric. That
 * one dialog is therefore covered on device, and everything else here.
 *
 * `SettingsDialog` runs an endless 2-second permission-refresh loop, so the
 * auto-advancing clock never reaches idle — same manual-clock treatment as
 * HomeScreen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp")
class SettingsDialogTest {

    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var vm: GuardianViewModel
    private var dismissed = 0

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetAppStateRepository()
        setDetectorState("isRunning", false)
        vm = GuardianViewModel(app)
    }

    @After fun tearDown() {
        cancelViewModel(vm)
        Dispatchers.resetMain()
    }

    private fun settle() = repeat(4) { compose.mainClock.advanceTimeByFrame() }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MinimalistTheme {
                val state by vm.appState.collectAsState()
                SettingsDialog(viewModel = vm, appState = state, onDismiss = { dismissed++ })
            }
        }
        settle()
    }

    private fun isDisplayed(text: String) = runCatching {
        compose.onAllNodesWithText(text).onFirst().assertIsDisplayed()
    }.isSuccess

    /**
     * The sheet's body is a tall `verticalScroll` column, so most rows exist
     * without being on screen.
     *
     * Only attempted when the node is not already visible: `performScrollToNode`
     * searches inside the scroll container and throws for anything outside it —
     * the dialog title, its DONE button, and the challenge dialog all live
     * outside — and repeatedly retrying a doomed scroll exhausts the test heap.
     */
    private fun scrollTo(text: String) {
        if (isDisplayed(text)) return
        runCatching {
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
            // The semantics ScrollBy action animates, and the clock is paused —
            // advancing frames alone leaves the scroll half-finished.
            compose.mainClock.advanceTimeBy(1_000)
            settle()
        }
    }

    private fun assertVisible(text: String) {
        scrollTo(text)
        compose.onAllNodesWithText(text).onFirst().assertIsDisplayed()
    }

    private fun tap(text: String) {
        scrollTo(text)
        compose.onAllNodesWithText(text).onFirst().performClick()
        settle()
    }

    /**
     * "SAFE REGIME" is also the settings section heading, so it cannot mark the
     * challenge. "GIVE UP" appears only inside the challenge dialog.
     */
    private fun challengeIsOpen() =
        compose.onAllNodesWithText("GIVE UP").fetchSemanticsNodes().isNotEmpty()

    /** The switch scrolls out of view once a later row has been scrolled to. */
    private fun toggle() {
        compose.onNodeWithTag(TestTags.Settings.SAFE_REGIME_TOGGLE).performClick()
        settle()
    }

    // ---------------- permissions ----------------

    @Test fun `renders the settings sheet with its permission section`() {
        show()
        assertVisible("SETTINGS")
        assertVisible("PERMISSIONS")
        assertVisible("USAGE ACCESS")
        assertVisible("DISPLAY OVER APPS")
    }

    @Test fun `a granted permission reads as granted`() {
        grantOverlayPermission()
        show()

        // DISPLAY OVER APPS is the one a Robolectric shadow can actually grant.
        assertVisible("GRANTED")
    }

    @Test fun `an ungranted permission invites the user to enable it`() {
        show()
        scrollTo("TAP TO ENABLE")
        compose.onAllNodesWithText("TAP TO ENABLE", substring = true).onFirst().assertIsDisplayed()
    }

    // ---------------- anti-bypass toggle ----------------

    @Test fun `anti-bypass protection is on by default`() {
        show()
        assertVisible("ANTI-BYPASS PROTECTION")
        compose.onNodeWithTag(TestTags.Settings.SAFE_REGIME_TOGGLE).assertIsOn()
    }

    @Test fun `with nothing active it can be switched off directly`() {
        show()

        toggle()

        compose.onNodeWithTag(TestTags.Settings.SAFE_REGIME_TOGGLE).assertIsOff()
        assertEquals(false, vm.safeRegimeEnabled.value)
    }

    /**
     * The point of the setting: with a mode active, switching the guard off is
     * itself a bypass, so it goes through the attention challenge first.
     */
    @Test fun `with a mode active switching it off requires the challenge`() {
        vm.importConfig(ConfigManager.ExportData(
            1, listOf(mode(id = "m1", name = "Deep Work")), emptyList(), emptyList()
        ))
        vm.activateMode("m1")
        show()

        toggle()

        assertEquals("the challenge should be in the way", true, challengeIsOpen())
        assertEquals("it must not switch off before the challenge", true, vm.safeRegimeEnabled.value)
    }

    @Test fun `giving up that challenge leaves the protection on`() {
        vm.importConfig(ConfigManager.ExportData(
            1, listOf(mode(id = "m1", name = "Deep Work")), emptyList(), emptyList()
        ))
        vm.activateMode("m1")
        show()
        toggle()

        tap("GIVE UP")

        assertEquals(true, vm.safeRegimeEnabled.value)
        compose.onNodeWithTag(TestTags.Settings.SAFE_REGIME_TOGGLE).assertIsOn()
    }

    @Test fun `switching it back on is immediate and never challenged`() {
        show()
        toggle()                                    // off
        assertEquals(false, vm.safeRegimeEnabled.value)

        toggle()                                    // on again

        assertEquals(true, vm.safeRegimeEnabled.value)
        assertEquals("turning it back on is never gated", false, challengeIsOpen())
    }

    @Test fun `the toggle explains which state it is in`() {
        show()
        assertVisible("Risky actions need a timed challenge.")

        toggle()
        assertEquals(false, vm.safeRegimeEnabled.value)

        assertVisible("Off. Nothing is protected.")
    }

    /**
     * The duration row sits outside the `if (safeRegimeEnabled)` block, so it
     * stays on screen with protection switched off. That is deliberate enough —
     * the setting persists and applies again on re-enable — but it is pinned
     * here so the placement is a decision rather than an accident.
     */
    @Test fun `the challenge duration stays configurable with protection off`() {
        show()
        toggle()
        assertEquals(false, vm.safeRegimeEnabled.value)

        assertVisible("CHALLENGE DURATION")
        assertVisible("1:30")
    }

    // ---------------- blocking method ----------------

    @Test fun `without accessibility it reports the overlay method`() {
        show()
        assertVisible("OVERLAY MODE")
    }

    @Test fun `with accessibility granted it reports the force-close method`() {
        // The sheet reads the Settings.Secure grant, not the live bound flag.
        enableAccessibilityService(app)
        show()
        assertVisible("FORCE-CLOSE MODE")
    }

    // ---------------- data ----------------

    @Test fun `the data section counts what would be exported`() {
        vm.importConfig(ConfigManager.ExportData(
            1,
            listOf(mode(id = "m1", name = "Deep Work"), mode(id = "m2", name = "Sleep")),
            listOf(schedule(id = "s1", name = "Work", modeIds = listOf("m1"))),
            listOf(tag(id = "t1", name = "Desk key"))
        ))
        show()

        assertVisible("2 modes  -  1 schedules  -  1 tags")
    }

    @Test fun `exporting asks which format first`() {
        show()

        compose.onNodeWithTag(TestTags.Settings.EXPORT).performClick().also { settle() }

        assertVisible("EXPORT FORMAT")
        assertVisible("JSON")
        assertVisible("YAML")
    }

    @Test fun `DONE closes the sheet`() {
        show()

        compose.onNodeWithTag(TestTags.Settings.DONE).performClick().also { settle() }

        assertEquals(1, dismissed)
    }
}
