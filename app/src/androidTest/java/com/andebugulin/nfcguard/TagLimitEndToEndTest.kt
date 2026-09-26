package com.andebugulin.nfcguard

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.andebugulin.nfcguard.harness.GuardianHarness
import com.andebugulin.nfcguard.harness.HomeRobot
import com.andebugulin.nfcguard.harness.ModesRobot
import com.andebugulin.nfcguard.harness.UnlockDialogRobot
import com.andebugulin.nfcguard.nfc.MockNfcTag
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.tag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Where per-tag unlock caps are **set** — `TagLimitConfigDialog` in the mode
 * editor.
 *
 * This closes an asymmetry: `DialogFlowsEndToEndTest` pins that a cap is
 * *enforced* at unlock time, but nothing covered the screen that writes
 * `tagUnlockLimits` in the first place. A bug storing the wrong key or the
 * wrong number would have sailed through the whole suite, because the
 * enforcement tests seed the limit directly rather than going through the UI.
 *
 * The last test closes the loop end to end: a cap set through the editor is
 * the cap the unlock dialog honours.
 *
 * On device because the dialog carries HOURS/MINUTES text fields, which never
 * let a Robolectric dialog reach idle — see TESTS.md.
 */
class TagLimitEndToEndTest {

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

    /** A mode already linked to one tag, so the editor opens with a row to configure. */
    private fun seedLinkedMode(limits: Map<String, Long?> = emptyMap()) = harness.seedConfig(
        modes = listOf(
            mode(
                id = "m1", name = "Deep Work",
                apps = listOf("com.example.social"),
                tagIds = listOf(tagId),
                limits = limits
            )
        ),
        tags = listOf(tag(id = tagId, name = "Desk key"))
    )

    private fun openEditor() =
        HomeRobot(compose).openModes().openEditor("m1").assertOnEditor()

    private fun storedLimit() = harness.state.modes.first { it.id == "m1" }.tagUnlockLimits

    @Test fun aLinkedTagStartsWithNoLimit() {
        seedLinkedMode()
        harness.launch()

        openEditor().assertTagLimit(tagId, "PERMANENT")
    }

    /**
     * The ordinary save path, with no warning in the way: the wildcard row is
     * left permanent, so capping the named tag still leaves a way out and SAVE
     * commits directly. (Capping *every* selected tag is the warned case —
     * see [aModeWhereEveryTagIsCappedWarnsBeforeSaving].)
     */
    @Test fun aLimitSetOnATagIsPersisted() {
        seedLinkedMode()
        harness.launch()

        openEditor()
            .toggleTag("ANY")
            .openLimitFor(tagId)
            .chooseLimited()
            .setLimitHours("0")
            .setLimitMinutes("30")
            .applyLimit()
            .save()

        assertEquals(30L, storedLimit()[tagId])
        assertNull("the wildcard was left permanent", storedLimit()["ANY"])
    }

    @Test fun aStoredLimitIsShownBackAsHoursAndMinutes() {
        seedLinkedMode(limits = mapOf(tagId to 90L))
        harness.launch()

        openEditor().assertTagLimit(tagId, "1H 30M")
    }

    @Test fun reopeningTheDialogPrefillsTheStoredLimit() {
        seedLinkedMode(limits = mapOf(tagId to 90L))
        harness.launch()

        openEditor().openLimitFor(tagId).assertLimitFields(hours = "1", minutes = "30")
    }

    @Test fun switchingBackToPermanentClearsTheLimit() {
        seedLinkedMode(limits = mapOf(tagId to 30L))
        harness.launch()

        openEditor()
            .openLimitFor(tagId)
            .choosePermanent()
            .applyLimit()
            .save()

        assertNull("choosing permanent must remove the cap", storedLimit()[tagId])
    }

    @Test fun cancellingTheLimitDialogKeepsTheStoredValue() {
        seedLinkedMode(limits = mapOf(tagId to 30L))
        harness.launch()

        openEditor()
            .openLimitFor(tagId)
            .choosePermanent()
            .cancelLimit()
            .save()

        assertEquals("cancel must not apply the change", 30L, storedLimit()[tagId])
    }

    @Test fun theAnyTagWildcardCarriesItsOwnLimit() {
        seedLinkedMode()
        harness.launch()

        openEditor()
            .toggleTag("ANY")
            .openLimitFor("ANY")
            .chooseLimited()
            .setLimitHours("0")
            .setLimitMinutes("45")
            .applyLimit()
            .save()

        assertEquals(
            "the wildcard is stored under the literal ANY key",
            45L, storedLimit()["ANY"]
        )
    }

    /**
     * With every selected tag capped there is no way to switch the mode off
     * permanently until its schedule ends. That is a legitimate thing to want,
     * but not by accident — so it warns first.
     */
    @Test fun aModeWhereEveryTagIsCappedWarnsBeforeSaving() {
        seedLinkedMode()
        harness.launch()

        openEditor()
            .openLimitFor(tagId)
            .chooseLimited()
            .setLimitHours("0")
            .setLimitMinutes("30")
            .applyLimit()
            .save()
            .assertNoPermanentUnlockWarning()
    }

    @Test fun backingOutOfThatWarningDoesNotSave() {
        seedLinkedMode()
        harness.launch()

        openEditor()
            .openLimitFor(tagId)
            .chooseLimited()
            .setLimitHours("0")
            .setLimitMinutes("30")
            .applyLimit()
            .save()
            .assertNoPermanentUnlockWarning()
            .dismissWarning()

        assertTrue("nothing should be written until SAVE ANYWAY", storedLimit().isEmpty())
    }

    @Test fun confirmingThatWarningSavesTheInescapableMode() {
        seedLinkedMode()
        harness.launch()

        openEditor()
            .openLimitFor(tagId)
            .chooseLimited()
            .setLimitHours("0")
            .setLimitMinutes("30")
            .applyLimit()
            .save()
            .saveAnyway()

        assertEquals(30L, storedLimit()[tagId])
    }

    /**
     * The whole mechanism, both ends: a cap entered in the editor is the cap the
     * unlock dialog enforces when the tag is tapped. Neither half proves this on
     * its own — the enforcement tests seed `tagUnlockLimits` directly, and the
     * editor tests above stop at what was persisted.
     */
    @Test fun aCapSetInTheEditorIsTheCapEnforcedAtUnlock() {
        assumeTrue(
            "mock NFC taps need: adb shell settings put global hidden_api_policy 1",
            MockNfcTag.isSupported()
        )
        seedLinkedMode()
        harness.launch()

        openEditor()
            .openLimitFor(tagId)
            .chooseLimited()
            .setLimitHours("0")
            .setLimitMinutes("30")
            .applyLimit()
            .save()
            .saveAnyway()

        // Saving the editor lands back on ModesScreen, not Home — the old
        // text selector tapped the screen's own "MODES" heading and did
        // nothing, which happened to look like success.
        ModesRobot(compose).activate("m1").back()
        harness.tapNfcTag(tagId)

        val before = System.currentTimeMillis()
        UnlockDialogRobot(compose).assertShown().assertTimedOnly().confirmUnlock()

        val deadline = requireNotNull(harness.state.timedModeReactivations["m1"])
        assertTrue(
            "the editor's 30-minute cap must bound the unlock, got ${deadline - before}ms",
            deadline - before <= 30 * 60_000L + 10_000
        )
    }
}
