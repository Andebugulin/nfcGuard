package com.andebugulin.nfcguard.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.ui.onboarding.FeatureShowcaseDialog
import com.andebugulin.nfcguard.ui.onboarding.isShowcaseSeen
import com.andebugulin.nfcguard.ui.onboarding.markShowcaseSeen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The first-run popups, third on TESTS.md's gap list.
 *
 * Two things matter here beyond "it renders": the dialog must be a no-op on the
 * screens that have no showcase (its `else -> return` happens *before* any
 * composition), and "seen" must be sticky per screen — a popup that reappears
 * every visit is worse than none.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class FeatureShowcaseTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        app.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun show(screen: Screen, onContinue: () -> Unit = {}) {
        compose.setContent { MinimalistTheme { FeatureShowcaseDialog(screen, onContinue) } }
    }

    @Test fun `the modes showcase explains both polarities`() {
        show(Screen.MODES)
        compose.onAllNodesWithText("MODES").onFirst().assertIsDisplayed()
        compose.onNodeWithText("BLOCK", substring = true).assertIsDisplayed()
        compose.onNodeWithText("ALLOW ONLY", substring = true).assertIsDisplayed()
        compose.onNodeWithText("GOT IT").assertIsDisplayed()
    }

    @Test fun `the schedules showcase explains automation`() {
        show(Screen.SCHEDULES)
        compose.onAllNodesWithText("SCHEDULES").onFirst().assertIsDisplayed()
        compose.onNodeWithText("automatically", substring = true).assertIsDisplayed()
    }

    @Test fun `the nfc showcase adds the recovery tips the other screens do not get`() {
        show(Screen.NFC_TAGS)
        compose.onAllNodesWithText("NFC TAGS").onFirst().assertIsDisplayed()
        // The escape hatch for a lost tag is the one thing a user locked out of
        // their phone needs, so it is pinned rather than left to drift.
        compose.onNodeWithText("Emergency Reset", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Anti-Bypass Protection", substring = true).assertIsDisplayed()
    }

    @Test fun `GOT IT dismisses through the caller`() {
        var continued = 0
        show(Screen.MODES) { continued++ }

        compose.onNodeWithText("GOT IT").performClick()

        assertEquals(1, continued)
    }

    @Test fun `screens with no showcase render nothing at all`() {
        show(Screen.HOME)
        compose.onAllNodesWithText("GOT IT").fetchSemanticsNodes().let {
            assertTrue("HOME has no showcase to show", it.isEmpty())
        }
    }

    @Test fun `a screen with no showcase counts as already seen, so nothing is ever queued`() {
        assertTrue(isShowcaseSeen(app, Screen.HOME))
        assertTrue(isShowcaseSeen(app, Screen.INFO))
    }

    @Test fun `each screen remembers its own showcase independently`() {
        assertFalse(isShowcaseSeen(app, Screen.MODES))
        assertFalse(isShowcaseSeen(app, Screen.NFC_TAGS))

        markShowcaseSeen(app, Screen.MODES)

        assertTrue(isShowcaseSeen(app, Screen.MODES))
        assertFalse("marking one must not mark the others", isShowcaseSeen(app, Screen.NFC_TAGS))
    }

    @Test fun `marking a screen with no showcase is harmless`() {
        markShowcaseSeen(app, Screen.HOME)
        assertTrue(isShowcaseSeen(app, Screen.HOME))
    }
}
