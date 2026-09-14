package com.andebugulin.nfcguard.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.data.AppLogger
import com.andebugulin.nfcguard.testing.resetAppLogger
import com.andebugulin.nfcguard.ui.info.LogViewerDialog
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The event log a user is asked to attach to a bug report — the one route by
 * which a problem on someone else's phone becomes diagnosable here. If it shows
 * nothing, or the hand-off to the share sheet never fires, a report arrives with
 * no evidence in it.
 *
 * On the JVM: the dialog holds no text field, so it reaches idle under
 * Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp")
class LogViewerDialogTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private var dismissed = 0
    private var shared = 0

    @Before fun setUp() {
        AppLogger.init(context)
        resetAppLogger()
    }

    private fun show() = compose.setContent {
        MinimalistTheme {
            LogViewerDialog(onDismiss = { dismissed++ }, onShareFile = { shared++ })
        }
    }

    @Test fun `an empty log says so rather than showing a blank sheet`() {
        show()

        compose.onNodeWithText("EVENT LOG (0)").assertIsDisplayed()
        compose.onNodeWithText(
            "No events logged yet.\nUse the app normally and logs will be collected automatically."
        ).assertIsDisplayed()
    }

    @Test fun `logged events are counted in the header`() {
        AppLogger.log("NFC", "Tag scanned: 04a1b2c3")
        AppLogger.log("MODE", "Activated Deep Work")
        show()

        compose.onNodeWithText("EVENT LOG (2)").assertIsDisplayed()
    }

    @Test fun `an event is shown with its category and message`() {
        AppLogger.log("NFC", "Tag scanned: 04a1b2c3")
        show()

        // Entries render as "timestamp [CATEGORY] message" in one block.
        compose.onAllNodesWithText("[NFC] Tag scanned: 04a1b2c3", substring = true)
            .onFirst().assertIsDisplayed()
    }

    @Test fun `SAVE FILE hands the log to the caller to share`() {
        AppLogger.log("NFC", "Tag scanned")
        show()

        compose.onNodeWithText("SAVE FILE").performClick()

        assertEquals(1, shared)
        assertEquals("sharing must not close the dialog by itself", 0, dismissed)
    }

    @Test fun `CLOSE dismisses without sharing`() {
        show()

        compose.onNodeWithText("CLOSE").performClick()

        assertEquals(1, dismissed)
        assertEquals(0, shared)
    }

    @Test fun `the share button is offered even with nothing logged`() {
        show()
        // An empty log is still worth sending — it is itself evidence that
        // logging never started.
        compose.onNodeWithText("SAVE FILE").assertIsDisplayed()
    }
}
