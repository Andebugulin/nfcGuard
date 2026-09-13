package com.andebugulin.nfcguard.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.andebugulin.nfcguard.ui.schedules.ModernTimePickerDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The clock picker behind every schedule start/end time.
 *
 * It survives on the JVM — unlike `ScheduleEditorDialog`, which opens it — only
 * because it holds no text field: a Compose dialog containing an
 * `OutlinedTextField` never reaches idle under Robolectric (see TESTS.md).
 * Driving it in isolation therefore costs milliseconds, and keeps the slower
 * on-device schedule tests focused on the editor around it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ModernTimePickerDialogTest {

    @get:Rule val compose = createComposeRule()

    private var confirmed: Pair<Int, Int>? = null
    private var dismissed = 0

    private fun show(hour: Int = 9, minute: Int = 30) {
        compose.setContent {
            MinimalistTheme {
                ModernTimePickerDialog(
                    initialHour = hour,
                    initialMinute = minute,
                    onDismiss = { dismissed++ },
                    onConfirm = { h, m -> confirmed = h to m }
                )
            }
        }
    }

    @Test fun `opens on the hour, showing the time it was given`() {
        show(hour = 9, minute = 30)

        compose.onNodeWithText("SELECT HOUR").assertIsDisplayed()
        compose.onAllNodesWithText("09").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("30").onFirst().assertIsDisplayed()
    }

    @Test fun `tapping the minute field switches to minute selection`() {
        show(hour = 9, minute = 30)

        compose.onAllNodesWithText("30").onFirst().performClick()

        compose.onNodeWithText("SELECT MINUTE").assertIsDisplayed()
    }

    @Test fun `tapping the hour field switches back to hour selection`() {
        show(hour = 9, minute = 30)
        compose.onAllNodesWithText("30").onFirst().performClick()

        compose.onAllNodesWithText("09").onFirst().performClick()

        compose.onNodeWithText("SELECT HOUR").assertIsDisplayed()
    }

    @Test fun `SET returns the time unchanged when nothing was adjusted`() {
        show(hour = 9, minute = 30)

        compose.onNodeWithText("SET").performClick()

        assertEquals(9 to 30, confirmed)
    }

    @Test fun `CANCEL dismisses without confirming a time`() {
        show(hour = 9, minute = 30)

        compose.onNodeWithText("CANCEL").performClick()

        assertEquals(1, dismissed)
        assertNull("cancel must not report a time", confirmed)
    }

    /**
     * Both gestures set the time, and both now land on the number they are
     * aimed at: the face rounds to the nearest mark, so a label sits in the
     * middle of its own catchment rather than on its edge.
     */
    @Test fun `dragging onto a number on the clock face selects it`() {
        show(hour = 9, minute = 0)

        compose.onAllNodesWithText("3").onFirst().performTouchInput {
            down(Offset(centerX - 60f, centerY))
            moveTo(center)
            up()
        }

        compose.onNodeWithText("SET").performClick()
        assertEquals(3, confirmed?.first)
    }

    /**
     * Regression proof for the tap fix. The face used to read
     * `detectDragGestures` only, so a plain tap — the first thing most people
     * try on a clock picker — did nothing at all.
     */
    @Test fun `tapping a number on the clock face selects it`() {
        show(hour = 9, minute = 0)

        compose.onAllNodesWithText("3").onFirst().performClick()

        compose.onNodeWithText("SET").performClick()
        assertEquals(3, confirmed?.first)
    }

    /**
     * The other half of the same fix, and the reason it had to ship with the
     * tap: rounding centres each number's band on its label. While the face was
     * drag-only a half-slot offset was invisible — the indicator snaps and the
     * user keeps adjusting — but a tap commits in one shot, and a press on "3"
     * used to set 2.
     */
    @Test fun `tapping a minute mark selects that minute`() {
        show(hour = 7, minute = 0)
        compose.onAllNodesWithText("00").onFirst().performClick()   // switch to minutes

        compose.onAllNodesWithText("15").onFirst().performClick()

        compose.onNodeWithText("SET").performClick()
        assertEquals(7 to 15, confirmed)
    }
}
