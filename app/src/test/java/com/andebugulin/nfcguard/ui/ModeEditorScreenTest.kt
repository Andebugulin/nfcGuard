package com.andebugulin.nfcguard.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.tag
import com.andebugulin.nfcguard.ui.modes.ModeEditorScreen
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The editor is where a mode's polarity and tag locks are set — the two
 * settings that decide what `StateSyncer` later hands the service.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp")
class ModeEditorScreenTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private var savedApps: List<String>? = null
    private var savedBlockMode: BlockMode? = null

    /**
     * The picker lists whatever answers a LAUNCHER query, so the device's app
     * list has to be seeded. Robolectric's package manager reports none by
     * default, which would leave the picker legitimately empty.
     */
    @Before fun installApps() {
        installLauncherApp("com.example.chatter", "Chatter")
        installLauncherApp("com.example.notes", "Notes")
        // A critical app that the picker must refuse to offer.
        installLauncherApp("com.android.settings", "Settings")
    }

    private fun installLauncherApp(pkg: String, label: String) {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.MainActivity"
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    nonLocalizedLabel = label
                }
            }
            nonLocalizedLabel = label
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            resolveInfo
        )
    }

    private fun show(
        m: com.andebugulin.nfcguard.Mode = mode(id = "m1", name = "Focus", apps = emptyList()),
        tags: List<com.andebugulin.nfcguard.NfcTag> = listOf(tag(id = "t1", name = "Desk")),
        onSave: (List<String>, BlockMode, List<String>, Map<String, Long?>) -> Unit =
            { apps, blockMode, _, _ -> savedApps = apps; savedBlockMode = blockMode }
    ) = compose.setContent {
        MinimalistTheme {
            ModeEditorScreen(
                mode = m,
                availableNfcTags = tags,
                allModes = listOf(m),
                onBack = {},
                onSave = onSave
            )
        }
    }

    @Test fun `renders the editor for a mode`() {
        show()
        compose.waitForIdle()
        compose.onNodeWithText("BLOCK").assertIsDisplayed()
    }

    @Test fun `offers both blocking polarities`() {
        show()
        compose.waitForIdle()
        compose.onNodeWithText("BLOCK").assertIsDisplayed()
        compose.onNodeWithText("ALLOW ONLY").assertIsDisplayed()
    }

    @Test fun `surfaces the NFC tag lock section`() {
        show()
        compose.waitForIdle()
        compose.onNodeWithText("NFC TAG LOCK").assertIsDisplayed()
    }

    @Test fun `renders an allowlist mode without crashing`() {
        show(m = mode(id = "m2", name = "Allowlist", blockMode = BlockMode.ALLOW_SELECTED))
        compose.waitForIdle()
        compose.onNodeWithText("ALLOW ONLY").assertIsDisplayed()
    }

    // ---------------- the app picker ----------------
    //
    // Everything below was previously untested: the suite asserted the editor
    // rendered, not that an app could actually be chosen. App names render
    // `.uppercase()`, and the picker loads off the main thread, so the list has
    // to be waited for rather than assumed.

    private fun awaitPicker() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("CHATTER").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tap(text: String) {
        compose.onAllNodesWithText(text).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun search(query: String) {
        compose.onNode(hasSetTextAction()).performTextInput(query)
        compose.waitForIdle()
    }

    @Test fun `lists the installed apps`() {
        show()
        awaitPicker()

        compose.onAllNodesWithText("CHATTER").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("NOTES").onFirst().assertIsDisplayed()
    }

    /** Blocking Settings would trap the user out of the system UI they need. */
    @Test fun `never offers a critical system app`() {
        show()
        awaitPicker()

        compose.onAllNodesWithText("SETTINGS").assertCountEquals(0)
    }

    @Test fun `search narrows the list`() {
        show()
        awaitPicker()

        search("Notes")

        compose.onAllNodesWithText("NOTES").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("CHATTER").assertCountEquals(0)
    }

    @Test fun `search ignores case`() {
        show()
        awaitPicker()

        search("cHaT")

        compose.onAllNodesWithText("CHATTER").onFirst().assertIsDisplayed()
    }

    @Test fun `a mode with no apps cannot be saved`() {
        show()
        awaitPicker()

        compose.onNodeWithText("Select at least one app to save this mode").assertIsDisplayed()
        compose.onNodeWithText("SAVE").assertIsNotEnabled()
    }

    @Test fun `choosing an app enables saving and lists it as selected`() {
        show()
        awaitPicker()

        tap("CHATTER")

        compose.onNodeWithText("SELECTED (1)").assertIsDisplayed()
        compose.onNodeWithText("SAVE").assertIsEnabled()
    }

    @Test fun `an app can be deselected again`() {
        show()
        awaitPicker()
        tap("CHATTER")
        compose.onNodeWithText("SELECTED (1)").assertIsDisplayed()

        tap("CHATTER")

        compose.onAllNodesWithText("SELECTED (1)").assertCountEquals(0)
        compose.onNodeWithText("SAVE").assertIsNotEnabled()
    }

    @Test fun `saving reports the chosen apps by package name`() {
        show()
        awaitPicker()
        tap("CHATTER")

        tap("SAVE")

        assertEquals(listOf("com.example.chatter"), savedApps)
    }

    @Test fun `several apps can be chosen at once`() {
        show()
        awaitPicker()

        tap("CHATTER")
        tap("NOTES")
        tap("SAVE")

        assertEquals(
            listOf("com.example.chatter", "com.example.notes"),
            savedApps?.sorted()
        )
    }

    @Test fun `a mode saves as a blocklist by default`() {
        show()
        awaitPicker()
        tap("CHATTER")

        tap("SAVE")

        assertEquals(BlockMode.BLOCK_SELECTED, savedBlockMode)
    }

    /** The polarity decides whether the picked apps are the blocked set or the only allowed one. */
    @Test fun `switching to ALLOW ONLY saves the opposite polarity`() {
        show()
        awaitPicker()
        tap("CHATTER")

        tap("ALLOW ONLY")
        tap("SAVE")

        assertEquals(BlockMode.ALLOW_SELECTED, savedBlockMode)
    }

    @Test fun `an existing mode opens with its apps already selected`() {
        show(m = mode(id = "m1", name = "Focus", apps = listOf("com.example.chatter")))
        awaitPicker()

        compose.onNodeWithText("SELECTED (1)").assertIsDisplayed()
        compose.onNodeWithText("SAVE").assertIsEnabled()
    }
}
