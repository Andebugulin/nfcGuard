package com.andebugulin.nfcguard

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.andebugulin.nfcguard.harness.GuardianHarness
import com.andebugulin.nfcguard.harness.HomeRobot
import com.andebugulin.nfcguard.harness.ModeEditorRobot
import com.andebugulin.nfcguard.harness.NfcTagsRobot
import com.andebugulin.nfcguard.harness.ScheduleTagsRobot
import com.andebugulin.nfcguard.nfc.MockNfcTag
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.schedule
import com.andebugulin.nfcguard.testing.tag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Linking tags to modes from every direction the UI now offers.
 *
 * `Mode.nfcTagIds` is the single source of truth for this relationship, but
 * three different screens write to it: the mode editor (as it always did), the
 * tag card's LINK MODES (new — the card could previously only *display* what a
 * tag opened), and a schedule card's TAGS shortcut (new — it fans the chosen
 * tags out across the modes that schedule activates).
 *
 * Every assertion reads back through the repository rather than the screen,
 * because the bug worth catching here is a write landing in the wrong place —
 * three writers against one field is exactly where a second source of truth
 * would creep back in. `NfcTag.linkedModeIds` was deleted for that reason.
 *
 * On device rather than Robolectric: these are cross-screen flows through the
 * real Activity, and the registration path needs a simulated NFC tap.
 */
class TagLinkingEndToEndTest {

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var harness: GuardianHarness
    private val deskTag = "04a1b2c3"
    private val kitchenTag = "0b1c2d3e"

    @Before fun setUp() {
        harness = GuardianHarness(compose)
        harness.resetToOnboardedHome()
    }

    @After fun tearDown() {
        harness.close()
        harness.resetToOnboardedHome()
    }

    private fun tagsOf(modeId: String) =
        harness.state.modes.first { it.id == modeId }.nfcTagIds

    // ---------------- from the tag side ----------------

    @Test fun aTagCanBeLinkedToAModeFromTheTagCard() {
        harness.seedConfig(
            modes = listOf(mode(id = "m1", name = "Deep Work", apps = listOf("com.example.social"))),
            tags = listOf(tag(id = deskTag, name = "Desk key"))
        )
        harness.launch()

        HomeRobot(compose).openNfcTags()
            .assertUnlocksNothing()
            .openLinkModes(deskTag)
            .toggleLinkedMode("m1")
            .saveLinks()

        assertEquals(
            "the tag screen must write through to Mode.nfcTagIds",
            listOf(deskTag), tagsOf("m1")
        )
    }

    @Test fun unlinkingFromTheTagCardRemovesIt() {
        harness.seedConfig(
            modes = listOf(
                mode(id = "m1", name = "Deep Work", apps = listOf("com.example.social"),
                     tagIds = listOf(deskTag))
            ),
            tags = listOf(tag(id = deskTag, name = "Desk key"))
        )
        harness.launch()

        HomeRobot(compose).openNfcTags()
            .assertUnlocks("Deep Work")
            .openLinkModes(deskTag)
            .toggleLinkedMode("m1")   // was on, now off
            .saveLinks()

        assertEquals(emptyList<String>(), tagsOf("m1"))
    }

    @Test fun cancellingTheModePickerChangesNothing() {
        harness.seedConfig(
            modes = listOf(mode(id = "m1", name = "Deep Work", apps = listOf("com.example.social"))),
            tags = listOf(tag(id = deskTag, name = "Desk key"))
        )
        harness.launch()

        HomeRobot(compose).openNfcTags()
            .openLinkModes(deskTag)
            .toggleLinkedMode("m1")
            .cancelLinks()

        assertEquals(emptyList<String>(), tagsOf("m1"))
    }

    /** Linking one tag must not disturb another mode's links. */
    @Test fun linkingLeavesOtherModesAlone() {
        harness.seedConfig(
            modes = listOf(
                mode(id = "m1", name = "Deep Work", apps = listOf("com.example.social")),
                mode(id = "m2", name = "Sleep", apps = listOf("com.example.news"),
                     tagIds = listOf(kitchenTag))
            ),
            tags = listOf(tag(id = deskTag, name = "Desk key"), tag(id = kitchenTag, name = "Kitchen"))
        )
        harness.launch()

        HomeRobot(compose).openNfcTags()
            .openLinkModes(deskTag)
            .toggleLinkedMode("m1")
            .saveLinks()

        assertEquals(listOf(deskTag), tagsOf("m1"))
        assertEquals("m2 must be untouched", listOf(kitchenTag), tagsOf("m2"))
    }

    // ---------------- from inside the mode editor ----------------

    /**
     * Wanting a tag mid-edit used to be a dead end: the only register button
     * lived on the NFC Tags screen, so the user had to abandon the editor.
     */
    @Test fun aTagCanBeRegisteredWithoutLeavingTheModeEditor() {
        assumeTrue(
            "mock NFC taps need: adb shell settings put global hidden_api_policy 1 " +
                "(overloads seen: ${MockNfcTag.describeFactories()})",
            MockNfcTag.isSupported()
        )
        harness.seedConfig(
            modes = listOf(mode(id = "m1", name = "Deep Work", apps = listOf("com.example.social")))
        )
        harness.launch()

        val editor = HomeRobot(compose).openModes().openEditor("m1")
        editor.assertCanRegisterTags().beginTagRegistration()

        harness.tapNfcTag(deskTag)

        NfcTagsRobot(compose)
            .assertTagDetected()
            .nameTag("Desk key")
            .confirmRegistration()

        editor.save()

        assertEquals(
            "the new tag must be registered",
            listOf(deskTag), harness.state.nfcTags.map { it.id }
        )
        assertTrue(
            "a tag registered from the editor should already be selected for that mode",
            deskTag in tagsOf("m1")
        )
    }

    // ---------------- from the schedule side ----------------

    /**
     * A schedule has no tag of its own; the shortcut writes into the modes it
     * activates. That is the whole reason `Schedule` gained no new field.
     */
    @Test fun theScheduleShortcutTagsEveryModeItActivates() {
        harness.seedConfig(
            modes = listOf(
                mode(id = "m1", name = "Deep Work", apps = listOf("com.example.social")),
                mode(id = "m2", name = "Sleep", apps = listOf("com.example.news"))
            ),
            schedules = listOf(schedule(id = "s1", name = "Work", modeIds = listOf("m1", "m2"))),
            tags = listOf(tag(id = deskTag, name = "Desk key"))
        )
        harness.launch()

        HomeRobot(compose).openSchedules()
        ScheduleTagsRobot(compose)
            .open("s1")
            .assertShown()
            .toggleTag(deskTag)
            .add()

        assertEquals(listOf(deskTag), tagsOf("m1"))
        assertEquals(listOf(deskTag), tagsOf("m2"))
    }

    /** It only ever adds, so an existing link and its cap survive. */
    @Test fun theScheduleShortcutNeverStripsAnExistingTag() {
        harness.seedConfig(
            modes = listOf(
                mode(id = "m1", name = "Deep Work", apps = listOf("com.example.social"),
                     tagIds = listOf(kitchenTag), limits = mapOf(kitchenTag to 30L))
            ),
            schedules = listOf(schedule(id = "s1", name = "Work", modeIds = listOf("m1"))),
            tags = listOf(tag(id = deskTag, name = "Desk key"), tag(id = kitchenTag, name = "Kitchen"))
        )
        harness.launch()

        HomeRobot(compose).openSchedules()
        ScheduleTagsRobot(compose).open("s1").toggleTag(deskTag).add()

        val m1 = harness.state.modes.first { it.id == "m1" }
        assertTrue("the pre-existing tag must survive", kitchenTag in m1.nfcTagIds)
        assertTrue("the new tag must be added", deskTag in m1.nfcTagIds)
        assertEquals("its unlock cap must survive too", 30L, m1.tagUnlockLimits[kitchenTag])
    }

    /** With no tags registered there is nothing to offer, so the action hides. */
    @Test fun theScheduleShortcutIsHiddenWithNoTags() {
        harness.seedConfig(
            modes = listOf(mode(id = "m1", name = "Deep Work", apps = listOf("com.example.social"))),
            schedules = listOf(schedule(id = "s1", name = "Work", modeIds = listOf("m1")))
        )
        harness.launch()

        HomeRobot(compose).openSchedules()
        ScheduleTagsRobot(compose).assertNotOffered("s1")
    }
}
