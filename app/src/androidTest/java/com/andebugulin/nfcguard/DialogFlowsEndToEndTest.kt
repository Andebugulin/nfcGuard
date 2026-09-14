package com.andebugulin.nfcguard

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.andebugulin.nfcguard.harness.GuardianHarness
import com.andebugulin.nfcguard.harness.HomeRobot
import com.andebugulin.nfcguard.harness.NfcTagsRobot
import com.andebugulin.nfcguard.harness.SettingsRobot
import com.andebugulin.nfcguard.harness.UnlockDialogRobot
import com.andebugulin.nfcguard.nfc.MockNfcTag
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.tag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The dialog branches that need typing, and therefore cannot run on the JVM.
 *
 * A Compose dialog containing an `OutlinedTextField` never reaches idle under
 * Robolectric: composition spins until Espresso throws `AppNotIdleException`,
 * in any graphics mode, and `mainClock.autoAdvance = false` does not help
 * because the stall is in composition rather than the clock. An otherwise
 * identical dialog with no text field is fine — which is why the delete and
 * activation dialogs stayed in the Robolectric screen suites and these did not.
 *
 * The validation pinned here is not cosmetic. `tagUnlockLimits` is the user's
 * own commitment device: if the unlock dialog ever offers a permanent unlock
 * for a capped mode, or honours a duration longer than the cap, the app's
 * central promise is broken.
 */
class DialogFlowsEndToEndTest {

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var harness: GuardianHarness
    private val tagId = "04a1b2c3"

    @Before fun setUp() {
        harness = GuardianHarness(compose)
        harness.resetToOnboardedHome()
    }

    @After fun tearDown() {
        harness.close()
        harness.resetToOnboardedHome()
    }

    // ---------------- mode name dialog ----------------

    @Test fun theCreateDialogWillNotSubmitAnEmptyName() {
        harness.launch()
        HomeRobot(compose).openModes().openAddDialog().assertCannotCreate()
    }

    @Test fun theCreateDialogEnablesCreateOnceANameIsTyped() {
        harness.launch()
        HomeRobot(compose).openModes()
            .openAddDialog()
            .typeModeName("Deep Work")
            .assertCanCreate()
    }

    @Test fun theCreateDialogRefusesADuplicateNameRegardlessOfCase() {
        harness.seedConfig(modes = listOf(mode(id = "m1", name = "Deep Work")))
        harness.launch()

        HomeRobot(compose).openModes()
            .openAddDialog()
            .typeModeName("deep work")
            .assertDuplicateNameRejected()
    }

    @Test fun namingAModeOpensTheEditorRatherThanSavingItStraightAway() {
        harness.launch()

        HomeRobot(compose).openModes()
            .openAddDialog()
            .typeModeName("Deep Work")
            .confirmCreate()

        // A mode with no apps would block nothing, so nothing is persisted
        // until the editor saves.
        assertEquals(emptyList<String>(), harness.state.modes.map { it.name })
        HomeRobot(compose).assertVisible("SEARCH APPS...")
    }

    // ---------------- tag registration dialog ----------------

    @Test fun registrationCannotBeConfirmedBeforeATagIsPresented() {
        harness.launch()
        HomeRobot(compose).openNfcTags().beginRegistration().assertCannotRegister()
    }

    @Test fun anAlreadyRegisteredTagIsRefused() {
        assumeMockTags()
        harness.seedConfig(tags = listOf(tag(id = tagId, name = "Desk key")))
        harness.launch()

        HomeRobot(compose).openNfcTags().beginRegistration()
        harness.tapNfcTag(tagId)

        NfcTagsRobot(compose).assertAlreadyRegistered().assertCannotRegister()
        assertEquals("nothing new should be stored", 1, harness.state.nfcTags.size)
    }

    @Test fun aDuplicateTagNameIsRefused() {
        assumeMockTags()
        harness.seedConfig(tags = listOf(tag(id = "0badbeef", name = "Desk key")))
        harness.launch()

        HomeRobot(compose).openNfcTags().beginRegistration()
        harness.tapNfcTag(tagId)

        NfcTagsRobot(compose).nameTag("Desk key").assertDuplicateNameRejected()
    }

    @Test fun cancellingRegistrationStoresNothing() {
        assumeMockTags()
        harness.launch()

        HomeRobot(compose).openNfcTags().beginRegistration()
        harness.tapNfcTag(tagId)
        NfcTagsRobot(compose).nameTag("Desk key").cancelDialog()

        assertEquals(emptyList<String>(), harness.state.nfcTags.map { it.id })
    }

    @Test fun aTagCanBeRenamed() {
        harness.seedConfig(tags = listOf(tag(id = tagId, name = "Old name")))
        harness.launch()

        HomeRobot(compose).openNfcTags().renameTag(tagId, "New name")

        assertEquals(listOf("New name"), harness.state.nfcTags.map { it.name })
    }

    // ---------------- unlock dialog, capped paths ----------------

    private fun activateCappedMode(limitMinutes: Long) {
        harness.seedConfig(
            modes = listOf(
                mode(
                    id = "m1", name = "Deep Work",
                    tagIds = listOf(tagId),
                    limits = mapOf(tagId to limitMinutes)
                )
            ),
            tags = listOf(tag(id = tagId, name = "Desk key", modeIds = listOf("m1")))
        )
        harness.launch()
        HomeRobot(compose).openModes().activate("m1").back()
        harness.tapNfcTag(tagId)
    }

    @Test fun aCappedModeIsNeverOfferedAPermanentUnlock() {
        assumeMockTags()
        activateCappedMode(15)

        UnlockDialogRobot(compose).assertShown().assertTimedOnly()
    }

    @Test fun aCappedModeUnlocksOnlyWithinItsLimit() {
        assumeMockTags()
        activateCappedMode(15)

        val before = System.currentTimeMillis()
        UnlockDialogRobot(compose).assertShown().confirmUnlock()

        val deadline = harness.state.timedModeReactivations["m1"]
        assertTrue("expected a scheduled reactivation, got $deadline", deadline != null)
        assertTrue(
            "the mode must come back inside its 15-minute cap, got ${deadline!! - before}ms",
            deadline - before in 1..(15 * 60_000L + 10_000)
        )
    }

    @Test fun aDurationTypedBeyondTheCapIsClampedToTheCap() {
        assumeMockTags()
        activateCappedMode(15)

        val before = System.currentTimeMillis()
        UnlockDialogRobot(compose).assertShown()
            .setHours("5")          // five hours against a fifteen-minute cap
            .confirmUnlock()

        val deadline = requireNotNull(harness.state.timedModeReactivations["m1"])
        assertTrue(
            "the cap must win over what was typed, got ${deadline - before}ms",
            deadline - before <= 15 * 60_000L + 10_000
        )
    }

    @Test fun anUncappedModeMayStillBeUnlockedPermanently() {
        assumeMockTags()
        harness.seedConfig(
            modes = listOf(mode(id = "m1", name = "Deep Work", tagIds = listOf(tagId))),
            tags = listOf(tag(id = tagId, name = "Desk key", modeIds = listOf("m1")))
        )
        harness.launch()
        HomeRobot(compose).openModes().activate("m1").back()
        harness.tapNfcTag(tagId)

        UnlockDialogRobot(compose).assertShown().assertPermanentOffered().confirmUnlock()

        assertTrue(
            "a permanent unlock schedules no reactivation",
            harness.state.timedModeReactivations["m1"] == null
        )
    }

    /**
     * Two modes on one tag, one of them capped. The dialog then has to reconcile
     * per-mode limits, which is where a mistake would quietly hand the user a
     * permanent unlock on a mode they had capped.
     */
    private fun activateMixedLimitModes() {
        harness.seedConfig(
            modes = listOf(
                mode(id = "m1", name = "Deep Work", tagIds = listOf(tagId)),
                mode(
                    id = "m2", name = "Sleep",
                    tagIds = listOf(tagId), limits = mapOf(tagId to 30L)
                )
            ),
            tags = listOf(tag(id = tagId, name = "Desk key", modeIds = listOf("m1", "m2")))
        )
        harness.launch()
        val modes = HomeRobot(compose).openModes()
        modes.activate("m1")
        modes.activate("m2")
        modes.back()
        harness.tapNfcTag(tagId)
    }

    @Test fun severalModesAreListedForSelectionWithTheirOwnLimits() {
        assumeMockTags()
        activateMixedLimitModes()

        UnlockDialogRobot(compose).assertShown()
            .assertVisible("SELECT MODES TO UNLOCK")
            .assertVisible("DEEP WORK")
            .assertVisible("SLEEP")
            .assertVisible("PERMANENT")
            .assertVisible("30M MAX")
    }

    @Test fun theMostRestrictiveLimitGovernsAMixedSelection() {
        assumeMockTags()
        activateMixedLimitModes()

        // Deep Work alone would allow a permanent unlock; Sleep's 30-minute cap
        // must still win while both are selected.
        UnlockDialogRobot(compose).assertShown().assertPermanentNotOffered()
    }

    @Test fun droppingTheCappedModeFromTheSelectionRestoresThePermanentOption() {
        assumeMockTags()
        activateMixedLimitModes()

        UnlockDialogRobot(compose).assertShown()
            .assertPermanentNotOffered()
            .deselectMode("m2")
            .assertPermanentOffered()
    }

    // ---------------- the challenge duration dialog ----------------

    /**
     * The 1:30 floor is the app's own protection against a user weakening the
     * anti-bypass gate in a weak moment, so the UI must refuse to go under it —
     * not merely coerce afterwards.
     */
    @Test fun theChallengeDurationCannotBeSetBelowTheFloor() {
        harness.launch()

        HomeRobot(compose).openSettings()
            .openChallengeDuration()
            .setMinutes("0")
            .setSeconds("30")
            .assertBelowMinimumWarned()
            .assertCannotApply()
    }

    @Test fun theChallengeDurationCanBeRaised() {
        harness.launch()

        HomeRobot(compose).openSettings()
            .openChallengeDuration()
            .setMinutes("3")
            .setSeconds("0")
            .assertCanApply()
            .applyDuration()
            .assertVisible("3:00")
    }

    @Test fun exactlyTheFloorIsAccepted() {
        harness.launch()

        HomeRobot(compose).openSettings()
            .openChallengeDuration()
            .setMinutes("1")
            .setSeconds("30")
            .assertCanApply()
    }

    private fun assumeMockTags() = assumeTrue(
        "mock NFC taps need: adb shell settings put global hidden_api_policy 1",
        MockNfcTag.isSupported()
    )
}
