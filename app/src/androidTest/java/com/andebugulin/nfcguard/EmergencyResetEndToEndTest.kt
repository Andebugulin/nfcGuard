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
 * It had no coverage at all when these tests were written; the sole mention of
 * it anywhere in the suites was a first-run tip (since removed).
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
            tag(id = "t1", name = "Desk key"),
            tag(id = "t2", name = "Kitchen key")
        )
    )

    // ---------------- reaching it ----------------

    @Test fun recoveryIsReachableFromHomeAndStatesItsOutcomeUpFront() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .assertRecoveryShown()
            .assertChallengeSkipped()
            // The outcome is on the screen before anything is committed — the
            // old flow never said plainly that modes were about to switch off.
            .assertVisible("No modes are active.")
    }

    @Test fun cancellingChangesNothing() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .assertRecoveryShown()
            .cancelRecovery()
            .assertRecoveryNotShown()

        assertEquals(listOf("t1", "t2"), harness.state.nfcTags.map { it.id })
    }

    // ---------------- the gate ----------------

    @Test fun withNothingActiveTheChallengeIsSkipped() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .selectLostTag("t2")
            // Nothing is being bypassed when no mode is on, so making the user
            // sit through 90 seconds would be friction for its own sake.
            .confirmReset()
            .assertChallengeSkipped()

        assertEquals(listOf("t1"), harness.state.nfcTags.map { it.id })
    }

    /**
     * The challenge now runs *after* the user has chosen and confirmed, so it
     * gates the commit rather than the question. Nothing may change until it
     * completes.
     */
    @Test fun withAModeActiveTheChallengeGatesTheCommit() {
        seedTwoTags()
        harness.launch()
        HomeRobot(compose).openModes().activate("m1").back()
        assertTrue("m1" in harness.state.activeModes)

        HomeRobot(compose).openEmergencyReset()
            .selectLostTag("t2")
            .confirmReset()
            .assertChallengeRequired()

        assertTrue("nothing may be released before the challenge", "m1" in harness.state.activeModes)
        assertEquals(
            "no tag may be forgotten before the challenge",
            listOf("t1", "t2"), harness.state.nfcTags.map { it.id }
        )
    }

    /**
     * The strict property: the recovery gate is unconditional, and does *not*
     * read `safe_regime_enabled`. That setting lives outside `AppState`
     * precisely so an imported config cannot weaken the safety challenge — a
     * gate that a toggle could switch off would undo that.
     */
    @Test fun theChallengeIsStillRequiredWhenSafeRegimeIsTurnedOff() {
        seedTwoTags()
        harness.disableSafeRegime()
        harness.launch()
        HomeRobot(compose).openModes().activate("m1").back()

        HomeRobot(compose).openEmergencyReset()
            .confirmReset()
            .assertChallengeRequired()

        assertTrue("m1" in harness.state.activeModes)
    }

    @Test fun givingUpTheChallengeLeavesEveryModeAndTagUntouched() {
        seedTwoTags()
        harness.launch()
        HomeRobot(compose).openModes().activate("m1").back()

        HomeRobot(compose).openEmergencyReset()
            .selectLostTag("t2")
            .confirmReset()
            .assertChallengeRequired()
            .giveUpChallenge()

        assertTrue("giving up must not release the mode", "m1" in harness.state.activeModes)
        assertEquals(listOf("t1", "t2"), harness.state.nfcTags.map { it.id })
    }

    // ---------------- choosing what was lost ----------------

    @Test fun onlyTheTagsMarkedAsLostAreForgotten() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .selectLostTag("t2")
            .confirmReset()

        assertEquals(
            "the tag the user still has must survive",
            listOf("t1"), harness.state.nfcTags.map { it.id }
        )
    }

    @Test fun confirmingWithNothingSelectedForgetsNothing() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .confirmReset()

        assertEquals(listOf("t1", "t2"), harness.state.nfcTags.map { it.id })
    }

    @Test fun cancellingAfterSelectingForgetsNothing() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .selectLostTag("t2")
            .cancelRecovery()

        assertEquals(listOf("t1", "t2"), harness.state.nfcTags.map { it.id })
    }

    @Test fun aDeselectedTagIsSparedAgain() {
        seedTwoTags()
        harness.launch()

        HomeRobot(compose).openEmergencyReset()
            .selectLostTag("t2")
            .selectLostTag("t2")   // toggled back off
            .confirmReset()

        assertEquals(listOf("t1", "t2"), harness.state.nfcTags.map { it.id })
    }

    // The one path deliberately NOT automated: actually *passing* the challenge.
    //
    // It is a 90-second attention gate whose duration floor is raise-only, and
    // it cannot be shortened from a test — the countdown runs on real time, so
    // pausing Compose's clock only freezes it. A test that sits through it adds
    // ~100s to every run for one assertion, which is not a trade worth making.
    // The gate itself — required, skipped, given up, and nothing changing on any
    // of those paths — is covered above in milliseconds.
}
