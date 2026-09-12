package com.andebugulin.nfcguard.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.tag
import com.andebugulin.nfcguard.ui.modes.ModeEditorScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The editor is where a mode's polarity and tag locks are set — the two
 * settings that decide what `StateSyncer` later hands the service.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ModeEditorScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun show(
        m: com.andebugulin.nfcguard.Mode = mode(id = "m1", name = "Focus"),
        tags: List<com.andebugulin.nfcguard.NfcTag> = listOf(tag(id = "t1", name = "Desk")),
        onSave: (List<String>, BlockMode, List<String>, Map<String, Long?>) -> Unit =
            { _, _, _, _ -> }
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
}
