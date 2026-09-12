package com.andebugulin.nfcguard.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The onboarding carousel is the path issue #12 crashed on: its final
 * "GET STARTED" persists `has_seen_onboarding` and hands over to Home.
 *
 * Compose UI tests run under Robolectric here, so they stay on the JVM and
 * need no device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class OnboardingScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun start(onComplete: () -> Unit = {}) {
        compose.setContent { MinimalistTheme { OnboardingScreen(onComplete = onComplete) } }
    }

    @Test fun `opens on the welcome page`() {
        start()
        compose.onNodeWithText("GUARDIAN").assertIsDisplayed()
        compose.onNodeWithText("DIGITAL WELLBEING").assertIsDisplayed()
    }

    @Test fun `next advances through the carousel`() {
        start()
        compose.onNodeWithText("NEXT").performClick()
        compose.onNodeWithText("MODES").assertIsDisplayed()

        compose.onNodeWithText("NEXT").performClick()
        compose.onNodeWithText("NFC LOCKS").assertIsDisplayed()

        compose.onNodeWithText("NEXT").performClick()
        compose.onNodeWithText("SCHEDULES").assertIsDisplayed()
    }

    @Test fun `the final page offers GET STARTED instead of NEXT`() {
        start()
        repeat(4) { compose.onNodeWithText("NEXT").performClick() }

        compose.onNodeWithText("READY").assertIsDisplayed()
        compose.onNodeWithText("GET STARTED").assertIsDisplayed()
    }

    @Test fun `GET STARTED completes onboarding exactly once`() {
        var completions = 0
        start(onComplete = { completions++ })

        repeat(4) { compose.onNodeWithText("NEXT").performClick() }
        compose.onNodeWithText("GET STARTED").performClick()

        assertEquals(1, completions)
    }

    @Test fun `onboarding is not completed before the last page`() {
        var completed = false
        start(onComplete = { completed = true })

        compose.onNodeWithText("NEXT").performClick()
        compose.onNodeWithText("NEXT").performClick()

        assertFalse(completed)
    }

    @Test fun `back returns to the previous page`() {
        start()
        compose.onNodeWithText("NEXT").performClick()
        compose.onNodeWithText("MODES").assertIsDisplayed()

        compose.onNodeWithText("BACK").performClick()
        compose.onNodeWithText("GUARDIAN").assertIsDisplayed()
    }
}
