package com.andebugulin.nfcguard.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.andebugulin.nfcguard.ui.modes.UnlockDurationDialog
import com.andebugulin.nfcguard.ui.modes.UnlockModeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The dialog an NFC tap opens. It carries more logic than any other dialog in
 * the app — it resolves an *effective* limit across the selected modes, hides
 * the permanent option when any of them is capped, and clamps the typed
 * duration. A mode's `tagUnlockLimits` is the user's own commitment device, so
 * a bug that lets a capped mode be unlocked permanently defeats the point of
 * the app.
 *
 * Driven directly rather than through a tap, so each limit combination is one
 * cheap JVM test; the tap that reaches it is covered end-to-end on device by
 * `NfcTapEndToEndTest`.
 *
 * Only the *uncapped* paths are here. A capped mode opens on the timed option,
 * which renders the HOURS/MINUTES text fields — and a dialog containing an
 * OutlinedTextField never reaches idle under Robolectric (Compose recomposes
 * until Espresso throws AppNotIdleException; an identical dialog without a
 * field is fine, and `autoAdvance = false` does not help because the stall is
 * in composition rather than the clock). The capping rules, which are the ones
 * actually holding the user's commitment device in place, are therefore pinned
 * on a real device in `DialogFlowsEndToEndTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class UnlockDurationDialogTest {

    @get:Rule val compose = createComposeRule()

    private var confirmedMillis: Long? = null
    private var confirmedModes: Set<String>? = null
    private var dismissed = 0
    private var confirmCalls = 0

    /**
     * Hand-driven clock: the HOURS/MINUTES fields blink a cursor forever, so an
     * auto-advancing clock never reaches idle and Espresso eventually throws
     * AppNotIdleException. Same pattern HomeScreenTest uses for its endless
     * LaunchedEffect loops.
     */
    private fun settle() = repeat(4) { compose.mainClock.advanceTimeByFrame() }

    private fun show(vararg modes: UnlockModeInfo) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MinimalistTheme {
                UnlockDurationDialog(
                    modes = modes.toList(),
                    onDismiss = { dismissed++ },
                    onConfirm = { millis, ids ->
                        confirmCalls++
                        confirmedMillis = millis
                        confirmedModes = ids
                    }
                )
            }
        }
        settle()
    }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performClick()
        settle()
    }

    private fun unlimited(id: String = "m1", name: String = "Deep Work") =
        UnlockModeInfo(id, name, null)

    @Test fun `a single mode is named in the dialog`() {
        show(unlimited())
        compose.onNodeWithText("DEEP WORK").assertIsDisplayed()
        compose.onNodeWithText("HOW LONG SHOULD IT STAY UNLOCKED?").assertIsDisplayed()
    }

    @Test fun `an unlimited mode may be unlocked permanently`() {
        show(unlimited())
        compose.onNodeWithText("PERMANENTLY").assertIsDisplayed()
    }

    @Test fun `confirming a permanent unlock reports no deadline`() {
        show(unlimited())
        tap("PERMANENTLY")
        tap("UNLOCK")

        assertEquals(1, confirmCalls)
        assertNull("permanent means no reactivation time", confirmedMillis)
        assertEquals(setOf("m1"), confirmedModes)
    }

    @Test fun `all offered modes are selected by default`() {
        show(unlimited("m1", "Deep Work"), unlimited("m2", "Sleep"))
        tap("UNLOCK")
        assertEquals(setOf("m1", "m2"), confirmedModes)
    }

    @Test fun `deselecting a mode leaves it locked`() {
        show(unlimited("m1", "Deep Work"), unlimited("m2", "Sleep"))

        tap("SLEEP")
        tap("UNLOCK")

        assertEquals(setOf("m1"), confirmedModes)
    }

    @Test fun `the last selected mode cannot be deselected into an empty unlock`() {
        show(unlimited("m1", "Deep Work"), unlimited("m2", "Sleep"))

        tap("SLEEP")
        tap("DEEP WORK")
        tap("UNLOCK")

        assertEquals(
            "unlocking nothing is not a meaningful outcome, so one stays selected",
            setOf("m1"), confirmedModes
        )
    }

    @Test fun `cancelling dismisses without unlocking anything`() {
        show(unlimited())
        tap("CANCEL")

        assertEquals(1, dismissed)
        assertEquals(0, confirmCalls)
    }
}
