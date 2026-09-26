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
     * Issue #12 crashed on the handoff off the last onboarding page. Setup is
     * now one flow — the tour ends *on* the permissions page instead of handing
     * off to a separate dialog chain — so this walks the tour, checks it lands
     * there, and asserts GET STARTED reaches Home rather than the app dying.
     */
    @Test fun aFirstRunWalksOnboardingAndReachesHome() {
        harness.resetToFirstRun()
        harness.launch()

        val onboarding = OnboardingRobot(compose).assertOnFirstPage()
        onboarding.walkTour()
        onboarding.assertOnPermissions()

        onboarding.getStarted()

        HomeRobot(compose).assertOnHome()
    }
}
