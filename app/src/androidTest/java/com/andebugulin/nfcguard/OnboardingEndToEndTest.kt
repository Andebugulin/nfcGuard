package com.andebugulin.nfcguard

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.andebugulin.nfcguard.data.Permissions
import com.andebugulin.nfcguard.harness.GuardianHarness
import com.andebugulin.nfcguard.harness.HomeRobot
import com.andebugulin.nfcguard.harness.OnboardingRobot
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * First-run setup, on a real device.
 *
 * `OnboardingFlowTest` covers the flow's logic under Robolectric, where every
 * permission is whatever the shadow says. The two things it *cannot* check are
 * exactly the two that were broken, so they belong here:
 *
 *  - **The permissions page reports the real device.** The old dialog chain
 *    fired `startActivity` and advanced its queue in the same breath, so it
 *    never re-read anything and could mark setup complete with nothing
 *    granted. `scripts/grant-test-permissions.sh` grants usage access and
 *    overlay through appops, so those rows must independently come back
 *    GRANTED here — proof the page is reading the platform, not its own
 *    optimism.
 *  - **The pages can be swiped.** They are a `HorizontalPager` now because
 *    they always looked like one; a gesture test needs a real touch stream.
 */
class OnboardingEndToEndTest {

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var harness: GuardianHarness

    @Before fun setUp() {
        harness = GuardianHarness(compose)
        harness.resetToFirstRun()
    }

    @After fun tearDown() {
        harness.close()
        harness.resetToOnboardedHome()
    }

    @Test fun theTourCanBeSwipedAsWellAsStepped() {
        harness.launch()

        val onboarding = OnboardingRobot(compose).assertOnFirstPage()

        onboarding.swipeForward()
        onboarding.assertVisible("PICK WHAT'S BLOCKED")

        onboarding.swipeForward()
        onboarding.assertVisible("TURN ON BY THEMSELVES")

        onboarding.swipeBack()
        onboarding.assertVisible("PICK WHAT'S BLOCKED")
    }

    /**
     * The regression that mattered: a row goes green because the permission is
     * held, not because a button was pressed.
     */
    @Test fun thePermissionsPageReflectsWhatTheDeviceActuallyGrants() {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        assumeTrue(
            "run scripts/grant-test-permissions.sh first",
            Permissions.hasUsageStats(context) && Permissions.hasOverlay(context)
        )

        harness.launch()

        val onboarding = OnboardingRobot(compose).assertOnFirstPage()
        onboarding.walkTour().assertOnPermissions()

        onboarding.assertGranted("USAGE ACCESS")
        onboarding.assertGranted("DISPLAY OVER APPS")
    }

    /** CHECK AGAIN re-probes rather than trusting whatever was read on entry. */
    @Test fun checkAgainReReadsPermissionState() {
        harness.launch()

        val onboarding = OnboardingRobot(compose).assertOnFirstPage()
        onboarding.walkTour().assertOnPermissions()

        onboarding.recheckPermissions().assertOnPermissions()
    }

    /**
     * Nothing blocks: a user may look around before granting anything, and
     * setup must then stay done rather than reappearing on the next launch.
     */
    @Test fun setupCompletesAndDoesNotComeBack() {
        harness.launch()

        OnboardingRobot(compose)
            .assertOnFirstPage()
            .walkTour()
            .assertOnPermissions()
            .getStarted()

        HomeRobot(compose).assertOnHome()

        val prefs = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
            .getSharedPreferences("guardian_prefs", android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("has_seen_onboarding", false))
        assertTrue(prefs.getBoolean("initial_permissions_granted", false))

        // Relaunching lands on Home, not back in the tour.
        harness.close()
        harness.launch()
        HomeRobot(compose).assertOnHome()
        assertFalse(
            "setup must not reappear",
            com.andebugulin.nfcguard.ui.onboarding.needsOnboarding(
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation().targetContext
            )
        )
    }
}
