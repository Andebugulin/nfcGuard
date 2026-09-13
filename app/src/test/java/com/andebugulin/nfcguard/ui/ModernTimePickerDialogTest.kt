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

    /** Centre of the screen-space band that selects [value], in the "3" label's local space. */
    private fun bandCentreFor(value: Int): Offset {
        val here = compose.onAllNodesWithText("3").onFirst().fetchSemanticsNode().boundsInRoot
        val next = compose.onAllNodesWithText("${value + 1}").onFirst().fetchSemanticsNode().boundsInRoot
        val mid = Offset((here.center.x + next.center.x) / 2f, (here.center.y + next.center.y) / 2f)
        return Offset(mid.x - here.left, mid.y - here.top)
    }

    /**
     * The face reads gestures with `detectDragGestures`, so the value follows
     * where a drag *ends*. Aimed at the middle of the band that selects 3 —
     * see the next test for why the "3" label itself is not that point.
     */
    @Test fun `dragging to a position on the clock face selects that hour`() {
        show(hour = 9, minute = 0)

        compose.onAllNodesWithText("3").onFirst().performTouchInput {
            down(Offset(centerX - 60f, centerY))
            moveTo(bandCentreFor(3))
            up()
        }

        compose.onNodeWithText("SET").performClick()
        assertEquals(3, confirmed?.first)
    }

    /**
     * Documents a real off-by-one rather than asserting it is right.
     *
     * The hit test truncates — `((angle / 360.0) * (maxValue + 1)).toInt()` —
     * so each number's catchment starts *at* its label and runs clockwise to
     * the next one. Landing precisely on "3" therefore lands on the boundary,
     * and sub-pixel error drops it into the band below. Rounding instead of
     * truncating would centre each band on its label.
     */
    @Test fun `landing exactly on a number can select the one before it`() {
        show(hour = 9, minute = 0)

        compose.onAllNodesWithText("3").onFirst().performTouchInput {
            down(Offset(centerX - 60f, centerY))
            moveTo(center)
            up()
        }

        compose.onNodeWithText("SET").performClick()
        assertEquals(
            "the label sits on a band boundary, so it reads one low",
            2, confirmed?.first
        )
    }

    /**
     * Documents a real interaction gap rather than asserting it is correct: the
     * face handles drags only, so a plain tap on a number — the first thing most
     * people try on a clock picker — leaves the time untouched.
     */
    @Test fun `a plain tap on the clock face does not change the time`() {
        show(hour = 9, minute = 0)

        compose.onAllNodesWithText("3").onFirst().performClick()

        compose.onNodeWithText("SET").performClick()
        assertEquals(9 to 0, confirmed)
    }
}
