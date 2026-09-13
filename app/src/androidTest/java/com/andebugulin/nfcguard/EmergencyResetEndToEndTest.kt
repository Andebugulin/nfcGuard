package com.andebugulin.nfcguard

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.andebugulin.nfcguard.harness.GuardianHarness
import com.andebugulin.nfcguard.harness.HomeRobot
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.tag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The lost-tag emergency reset — the only flow in the app that can legitimately
 * switch blocking off, and therefore the only one whose failure mode is "the
 * blocker was bypassed" rather than "a screen looked wrong".
 *
 * It had no coverage at all: the sole mention of "Emergency Reset" anywhere in
 * the suites was `FeatureShowcaseTest` asserting that a *tip* about it renders.
 *
 * The branch that matters is `HomeScreen.kt`'s decision on CONTINUE:
 *
 * ```
 * if (appState.activeModes.isNotEmpty()) showEmergencyChallenge = true
 * else                                   showTagSelectionDialog = true
 * ```
 *
 * If that ever inverts, or starts consulting the safe-regime toggle, the
 * attention challenge stops guarding anything — so both halves are pinned here,
 * along with the fact that nothing is deactivated or deleted on any path that
 * does not reach a confirmed tag selection.
 *
 * On device rather than Robolectric: the flow reaches `TagSelectionDialog`
 * through two other dialogs and the real challenge, and the suite has to press
 * the challenge's attention checks in real time.
 */
class EmergencyResetEndToEndTest {

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

    private fun seedTwoTags() = harness.seedConfig(
        modes = listOf(mode(id = "m1", name = "Deep Work")),
        tags = listOf(
            tag(id = "t1", name = "Desk key", modeIds = listOf("m1")),
            tag(id = "t2", name = "Kitchen key")
        )
    )

    // ---------------- reaching it ----------------

    @Test fun theResetIsReachableFromHomeAndExplainsItselfBeforeDoingAnything() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .assertWarningShown()
            .assertTagSelectionNotShown()
    }

    @Test fun cancellingTheWarningChangesNothing() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .assertWarningShown()
            .cancelWarning()
            .assertTagSelectionNotShown()

        assertEquals(listOf("t1", "t2"), harness.state.nfcTags.map { it.id })
    }

    // ---------------- the gate ----------------

    @Test fun withNothingActiveTheChallengeIsSkipped() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .continueFromWarning()
            // Nothing is being bypassed when no mode is on, so making the user
            // sit through 90 seconds would be friction for its own sake.
            .assertChallengeSkipped()
            .assertTagSelectionShown()
    }

    @Test fun withAModeActiveTheChallengeIsRequiredBeforeAnyTagCanBeChosen() {
        seedTwoTags()
        harness.launch()
        HomeRobot(compose).openModes().activate("Deep Work").back()
        assertTrue("m1" in harness.state.activeModes)

        HomeRobot(compose).openEmergencyReset()
            .continueFromWarning()
            .assertChallengeRequired()
            .assertTagSelectionNotShown()

        assertTrue("nothing may be released before the challenge", "m1" in harness.state.activeModes)
    }

    /**
     * The strict property: the emergency gate is unconditional, and does *not*
     * read `safe_regime_enabled`. That setting lives outside `AppState`
     * precisely so an imported config cannot weaken the safety challenge — a
     * gate that a toggle could switch off would undo that.
     */
    @Test fun theChallengeIsStillRequiredWhenSafeRegimeIsTurnedOff() {
        seedTwoTags()
        harness.disableSafeRegime()
        harness.launch()
        HomeRobot(compose).openModes().activate("Deep Work").back()

        HomeRobot(compose).openEmergencyReset()
            .continueFromWarning()
            .assertChallengeRequired()
            .assertTagSelectionNotShown()
    }

    @Test fun givingUpTheChallengeLeavesEveryModeAndTagUntouched() {
        seedTwoTags()
        harness.launch()
        HomeRobot(compose).openModes().activate("Deep Work").back()

        HomeRobot(compose).openEmergencyReset()
            .continueFromWarning()
            .assertChallengeRequired()
            .giveUpChallenge()
            .assertTagSelectionNotShown()

        assertTrue("giving up must not release the mode", "m1" in harness.state.activeModes)
        assertEquals(listOf("t1", "t2"), harness.state.nfcTags.map { it.id })
    }

    // ---------------- tag selection ----------------

    @Test fun onlyTheTagsMarkedAsLostAreDeleted() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .continueFromWarning()
            .assertTagSelectionShown()
            .selectLostTag("Kitchen key")
            .confirmReset()

        assertEquals(
            "the tag the user still has must survive",
            listOf("t1"), harness.state.nfcTags.map { it.id }
        )
    }

    @Test fun confirmingWithNothingSelectedDeletesNothing() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .continueFromWarning()
            .assertTagSelectionShown()
            .confirmReset()

        assertEquals(listOf("t1", "t2"), harness.state.nfcTags.map { it.id })
    }

    @Test fun cancellingTheTagSelectionDeletesNothing() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .continueFromWarning()
            .assertTagSelectionShown()
            .selectLostTag("Kitchen key")
            .cancelTagSelection()

        assertEquals(listOf("t1", "t2"), harness.state.nfcTags.map { it.id })
    }

    @Test fun aDeselectedTagIsSparedAgain() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .continueFromWarning()
            .assertTagSelectionShown()
            .selectLostTag("Kitchen key")
            .selectLostTag("Kitchen key")   // toggled back off
            .confirmReset()

        assertEquals(listOf("t1", "t2"), harness.state.nfcTags.map { it.id })
    }

    // ---------------- the whole chain ----------------

    /**
     * Slow — around 100 seconds — because it sits out the real 90-second
     * attention challenge, pressing each check as it opens. The duration floor
     * is raise-only, so it genuinely cannot be shortened from a test.
     *
     * It earns the wall-clock: it is the only test that proves the complete
     * chain, and in particular the part that can only be reached *through* the
     * challenge — that confirming deactivates **every** active mode, not just
     * the one linked to the deleted tag.
     */
    @Test fun passingTheChallengeReleasesEveryModeAndDeletesOnlyTheChosenTag() {
        harness.seedConfig(
            modes = listOf(
                mode(id = "m1", name = "Deep Work"),
                mode(id = "m2", name = "Sleep")
            ),
            tags = listOf(
                tag(id = "t1", name = "Desk key", modeIds = listOf("m1")),
                tag(id = "t2", name = "Kitchen key")
            )
        )
        harness.launch()
        val modes = HomeRobot(compose).openModes()
        modes.activate("Deep Work")
        modes.activate("Sleep")
        modes.back()
        assertEquals(setOf("m1", "m2"), harness.state.activeModes)

        HomeRobot(compose).openEmergencyReset()
            .continueFromWarning()
            .assertChallengeRequired()
            .passChallenge()
            .assertTagSelectionShown()
            .selectLostTag("Desk key")
            .confirmReset()

        assertEquals(
            "every mode is released, not only the one behind the lost tag",
            emptySet<String>(), harness.state.activeModes
        )
        assertEquals(listOf("t2"), harness.state.nfcTags.map { it.id })
    }
}
