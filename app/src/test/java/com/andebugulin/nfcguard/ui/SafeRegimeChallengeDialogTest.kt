package com.andebugulin.nfcguard.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.andebugulin.nfcguard.ui.safety.SafeRegimeChallengeDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The anti-bypass gate. Its whole purpose is to be hard to get past, so the
 * behaviour worth pinning is that it does NOT complete early and that giving
 * up is always available.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class SafeRegimeChallengeDialogTest {

    @get:Rule val compose = createComposeRule()

    private fun show(
        onComplete: () -> Unit = {},
        onCancel: () -> Unit = {},
        seconds: Int = 90
    ) = compose.setContent {
        MinimalistTheme {
            SafeRegimeChallengeDialog(
                actionDescription = "disable safe regime",
                onComplete = onComplete,
                onCancel = onCancel,
                totalDurationSeconds = seconds
            )
        }
    }

    @Test fun `announces itself and the action being guarded`() {
        show()
        compose.onNodeWithText("SAFE REGIME").assertIsDisplayed()
    }

    @Test fun `offers a way out`() {
        show()
        compose.onNodeWithText("GIVE UP").assertIsDisplayed()
    }

    @Test fun `giving up cancels rather than completes`() {
        var completed = false
        var cancelled = false
        show(onComplete = { completed = true }, onCancel = { cancelled = true })

        compose.onNodeWithText("GIVE UP").performClick()

        assertEquals(true, cancelled)
        assertFalse("giving up must never satisfy the challenge", completed)
    }

    @Test fun `does not complete immediately on open`() {
        var completed = false
        show(onComplete = { completed = true })
        compose.waitForIdle()
        assertFalse("the gate must not be satisfiable without waiting", completed)
    }
}
