package com.andebugulin.nfcguard

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.andebugulin.nfcguard.harness.GuardianHarness
import com.andebugulin.nfcguard.harness.HomeRobot
import com.andebugulin.nfcguard.harness.NfcTagsRobot
import com.andebugulin.nfcguard.harness.UnlockDialogRobot
import com.andebugulin.nfcguard.nfc.MockNfcTag
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.tag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The NFC tap flow, end to end through the real Activity.
 *
 * This is the gap TESTS.md listed as unfixable ("requires physically tapping a
 * tag; no harness can simulate it"). It is closeable after all: the NFC stack
 * hands the Activity an `ACTION_TECH_DISCOVERED` intent carrying a `Tag`
 * parcelable, and [MockNfcTag] fabricates one. Everything downstream of that
 * intent — the hex encoding, the wrong-tag guard, `MainNavigation`'s routing
 * `LaunchedEffect`, the unlock dialog, and the resulting state write — is the
 * app's own code running unmodified.
 *
 * What is still *not* covered: the radio itself and `enableForegroundDispatch`.
 * Those belong to the platform.
 *
 * An empty Compose rule (rather than `createAndroidComposeRule`) because state
 * and prefs must be seeded *before* the Activity launches — `MainNavigation`
 * reads the onboarding flags into `remember` during first composition.
 */
class NfcTapEndToEndTest {

    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var harness: GuardianHarness

    private val rightTag = "04a1b2c3"
    private val wrongTag = "0bad0bad"

    @Before fun setUp() {
        assumeTrue(
            "mock NFC taps need: adb shell settings put global hidden_api_policy 1 " +
                "(overloads seen: ${MockNfcTag.describeFactories()})",
            MockNfcTag.isSupported()
        )
        harness = GuardianHarness(compose)
        harness.resetToOnboardedHome()
    }

    @After fun tearDown() {
        harness.close()
        harness.resetToOnboardedHome()
    }

    @Test fun aSimulatedTapRegistersANewTag() {
        harness.launch()

        HomeRobot(compose)
            .assertOnHome()
            .openNfcTags()
            .beginRegistration()

        harness.tapNfcTag(rightTag)

        NfcTagsRobot(compose)
            .assertTagDetected()
            .nameTag("Desk key")
            .confirmRegistration()
            .assertTagListed("Desk key")

        assertEquals(listOf(rightTag), harness.state.nfcTags.map { it.id })
    }

    @Test fun theRightTagOpensTheUnlockDialogAndReleasesTheMode() {
        harness.seedConfig(
            modes = listOf(mode(id = "m1", name = "Deep Work", tagIds = listOf(rightTag))),
            tags = listOf(tag(id = rightTag, name = "Desk key", modeIds = listOf("m1")))
        )
        harness.launch()

        HomeRobot(compose).openModes().activate("Deep Work").back()
        assertTrue("mode should be active before the tap", "m1" in harness.state.activeModes)

        harness.tapNfcTag(rightTag)

        UnlockDialogRobot(compose).assertShown().confirmUnlock()

        assertFalse("the tap should release the mode", "m1" in harness.state.activeModes)
    }

    @Test fun cancellingTheUnlockDialogLeavesTheModeActive() {
        harness.seedConfig(
            modes = listOf(mode(id = "m1", name = "Deep Work", tagIds = listOf(rightTag))),
            tags = listOf(tag(id = rightTag, name = "Desk key", modeIds = listOf("m1")))
        )
        harness.launch()
        HomeRobot(compose).openModes().activate("Deep Work").back()

        harness.tapNfcTag(rightTag)
        UnlockDialogRobot(compose).assertShown().cancel()

        assertTrue("cancel must not unlock", "m1" in harness.state.activeModes)
    }

    @Test fun theWrongTagWarnsAndDoesNotOfferAnUnlock() {
        harness.seedConfig(
            modes = listOf(mode(id = "m1", name = "Deep Work", tagIds = listOf(rightTag))),
            tags = listOf(tag(id = rightTag, name = "Desk key", modeIds = listOf("m1")))
        )
        harness.launch()
        HomeRobot(compose).openModes().activate("Deep Work").back()

        harness.tapNfcTag(wrongTag)

        HomeRobot(compose).assertVisible("WRONG TAG")
        UnlockDialogRobot(compose).assertNotShown()
        assertTrue("a wrong tag must not unlock", "m1" in harness.state.activeModes)
    }

    /**
     * Tapping the tag while the app is closed is the common real-world case,
     * and it takes a different path: `onCreate`'s `handleNfcIntent` rather than
     * `onNewIntent`. This one goes through a genuine AMS launch, so it also
     * covers the delivery that [GuardianHarness.tapNfcTag] deliberately skips.
     */
    @Test fun aTapWakesTheAppFromColdAndStillOffersTheUnlock() {
        harness.seedConfig(
            modes = listOf(mode(id = "m1", name = "Deep Work", tagIds = listOf(rightTag))),
            tags = listOf(tag(id = rightTag, name = "Desk key", modeIds = listOf("m1")))
        )
        harness.launch()
        HomeRobot(compose).openModes().activate("Deep Work")
        assertTrue("m1" in harness.state.activeModes)

        harness.tapNfcTagFromColdStart(rightTag)

        UnlockDialogRobot(compose).assertShown().confirmUnlock()
        assertFalse("a cold-start tap must unlock too", "m1" in harness.state.activeModes)
    }

    @Test fun aTapWithNothingActiveIsIgnored() {
        harness.seedConfig(
            modes = listOf(mode(id = "m1", name = "Deep Work", tagIds = listOf(rightTag))),
            tags = listOf(tag(id = rightTag, name = "Desk key", modeIds = listOf("m1")))
        )
        harness.launch()

        harness.tapNfcTag(rightTag)

        UnlockDialogRobot(compose).assertNotShown()
        HomeRobot(compose).assertOnHome()
    }
}
