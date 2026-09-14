package com.andebugulin.nfcguard.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.andebugulin.nfcguard.ui.info.InfoScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * InfoScreen is where users go to file bug reports, so the reporting entry
 * points must render even when there is no network (it fetches star counts).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class InfoScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun show(onBack: () -> Unit = {}) =
        compose.setContent { MinimalistTheme { InfoScreen(onBack = onBack) } }

    @Test fun `renders without a network connection`() {
        show()
        compose.onNodeWithText("GUARDIAN").assertIsDisplayed()
    }

    // The page is a LazyColumn, so anything below the fold is not composed
    // until scrolled to — scroll first, then assert.
    private fun scrollTo(text: String) =
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))

    @Test fun `explains how blocking works`() {
        show()
        scrollTo("HOW BLOCKING WORKS")
        compose.onNodeWithText("HOW BLOCKING WORKS").assertIsDisplayed()
    }

    @Test fun `offers the bug-report entry point`() {
        show()
        scrollTo("REPORT A PROBLEM")
        compose.onNodeWithText("REPORT A PROBLEM").assertIsDisplayed()
    }

    @Test fun `documents both enforcement strategies`() {
        show()
        scrollTo("OVERLAY MODE")
        compose.onNodeWithText("OVERLAY MODE").assertIsDisplayed()
        scrollTo("FORCE-CLOSE MODE")
        compose.onNodeWithText("FORCE-CLOSE MODE").assertIsDisplayed()
    }

    @Test fun `the VIEW button opens the event log`() {
        show()
        scrollTo("VIEW")

        compose.onNodeWithText("VIEW").performClick()
        compose.waitForIdle()

        // The dialog itself is covered by LogViewerDialogTest; this pins the
        // route to it, which is what a user follows when asked for logs.
        compose.onAllNodesWithText("EVENT LOG", substring = true).onFirst().assertIsDisplayed()
    }
}
