package com.andebugulin.nfcguard

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.andebugulin.nfcguard.harness.GuardianHarness
import com.andebugulin.nfcguard.harness.HomeRobot
import com.andebugulin.nfcguard.harness.OnboardingRobot
import com.andebugulin.nfcguard.testing.mode
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Navigation and persistence through the real Activity on a real device.
 *
 * The Robolectric screen suites compose one screen at a time with a ViewModel
 * they construct themselves, so they cannot see the seams between screens:
 * `MainNavigation`'s `when (currentScreen)` dispatch, the BackHandler that
 * sends sub-screens Home instead of exiting, the onboarding gate, and whether
 * a mode created through the UI actually survives in `AppStateRepository`.
 * That is what this covers.
 */
class AppNavigationEndToEndTest {

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var harness: GuardianHarness

    @Before fun setUp() {
        harness = GuardianHarness(compose)
        harness.resetToOnboardedHome()
    }

    @After fun tearDown() {
        harness.close()
        harness.resetToOnboardedHome()
    }

    @Test fun anOnboardedLaunchLandsOnHome() {
        harness.launch()
        HomeRobot(compose).assertOnHome()
    }

    @Test fun everySubScreenOpensFromHomeAndBackReturnsThere() {
        harness.seedConfig(modes = listOf(mode(id = "m1", name = "Deep Work")))
        harness.launch()

        val home = HomeRobot(compose)

        home.openModes().assertOnModes().back()
        home.assertOnHome()

        home.openSchedules().assertOnSchedules().back()
        home.assertOnHome()

        home.openNfcTags().assertOnNfcTags().back()
        home.assertOnHome()
    }

    @Test fun aModeCreatedThroughTheUiIsPersisted() {
        harness.launch()

        HomeRobot(compose)
            .openModes()
            .createMode("Deep Work", withApp = harness.aBlockableApp())
            .assertModeListed("Deep Work")

        assertTrue(
            "the mode should reach the repository, not just the screen",
            harness.state.modes.any { it.name == "Deep Work" }
        )
    }

    @Test fun aModeActivatedThroughTheUiIsPersistedAsActive() {
        harness.seedConfig(modes = listOf(mode(id = "m1", name = "Deep Work")))
        harness.launch()

        HomeRobot(compose).openModes().activate("m1")

        assertTrue("m1" in harness.state.activeModes)
        assertTrue(
            "a UI activation is a manual activation",
            "m1" in harness.state.manuallyActivatedModes
        )
    }

    /**
     * Issue #12 crashed exactly here — on the handoff out of the last carousel
     * page — so this walks the whole carousel and asserts the permission flow
     * takes over rather than the app dying.
     */
    @Test fun aFirstRunWalksOnboardingAndHandsOffToThePermissionFlow() {
        harness.resetToFirstRun()
        harness.launch()

        val onboarding = OnboardingRobot(compose).assertOnFirstPage()
        repeat(4) { onboarding.next() }
        onboarding.getStarted()

        onboarding.assertVisible("WELCOME TO GUARDIAN")
    }
}
