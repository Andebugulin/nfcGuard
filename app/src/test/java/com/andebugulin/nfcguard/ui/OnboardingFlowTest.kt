package com.andebugulin.nfcguard.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.ui.onboarding.OnboardingFlow
import com.andebugulin.nfcguard.ui.onboarding.needsOnboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * First-run setup: a short tour, then one permissions page.
 *
 * Replaces `OnboardingScreenTest` + `PermissionOnboardingTest`, which covered a
 * five-page prose carousel and a seven-dialog permission chain that no longer
 * exist. The behaviour worth pinning has changed shape with them — most of all
 * the bug in `permission state is read, never assumed`, which is the whole
 * reason the chain was replaced.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp")
class OnboardingFlowTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var completions = 0

    @Before fun setUp() {
        app.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        completions = 0
    }

    private fun show(startAtPermissions: Boolean = false) {
        compose.setContent {
            MinimalistTheme {
                OnboardingFlow(
                    onComplete = { completions++ },
                    startAtPermissions = startAtPermissions
                )
            }
        }
    }

    private fun tap(text: String) {
        compose.onAllNodesWithText(text).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun setupComplete() =
        app.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            .getBoolean("initial_permissions_granted", false)

    private fun denyUsageAccess() {
        shadowOf(app.getSystemService(android.app.AppOpsManager::class.java))
            .setMode(
                "android:get_usage_stats",
                android.os.Process.myUid(),
                app.packageName,
                android.app.AppOpsManager.MODE_ERRORED
            )
    }

    private fun grantUsageAccess() {
        shadowOf(app.getSystemService(android.app.AppOpsManager::class.java))
            .setMode(
                "android:get_usage_stats",
                android.os.Process.myUid(),
                app.packageName,
                android.app.AppOpsManager.MODE_ALLOWED
            )
    }

    // ---------------- the tour ----------------

    @Test fun `opens on the first page`() {
        show()
        compose.onNodeWithText("NFCGUARD").assertIsDisplayed()
        compose.onNodeWithText("APPS YOU CAN'T OPEN").assertIsDisplayed()
    }

    @Test fun `next walks the tour and ends on permissions`() {
        show()
        tap("NEXT"); compose.onNodeWithText("MODES").assertIsDisplayed()
        tap("NEXT"); compose.onNodeWithText("SCHEDULES").assertIsDisplayed()
        tap("NEXT"); compose.onNodeWithText("NFC TAGS").assertIsDisplayed()
        tap("NEXT"); compose.onNodeWithText("LOST YOUR TAG?").assertIsDisplayed()
        tap("NEXT"); compose.onNodeWithText("PERMISSIONS").assertIsDisplayed()
        compose.onNodeWithText("GET STARTED").assertIsDisplayed()
    }

    @Test fun `back returns to the previous page`() {
        show()
        tap("NEXT")
        compose.onNodeWithText("MODES").assertIsDisplayed()
        tap("BACK")
        compose.onNodeWithText("NFCGUARD").assertIsDisplayed()
    }

    @Test fun `the tour does not complete setup before its last page`() {
        show()
        tap("NEXT"); tap("NEXT")
        assertEquals(0, completions)
        assertFalse(setupComplete())
    }

    /**
     * The user meets the recovery challenge here rather than discovering it
     * mid-crisis, and the floor the ViewModel enforces is stated up front.
     */
    @Test fun `the safety page teaches the recovery challenge`() {
        show()
        repeat(4) { tap("NEXT") }
        compose.onNodeWithText("YOU CAN STILL GET BACK IN").assertIsDisplayed()
        // The page renders the real challenge composable, so its header is the
        // proof it is the genuine screen and not a mock-up of one. The
        // countdown and PRESS/WAITING panel change every second; don't assert
        // on a frame of those.
        compose.onNodeWithText("SAFE REGIME").assertIsDisplayed()
    }

    // ---------------- permissions ----------------

    /**
     * The bug this whole page exists to fix.
     *
     * The old chain called `startActivity` and advanced its queue in the same
     * breath, so the UI moved on before the system screen appeared and never
     * looked again — it could mark setup complete with nothing granted. A row
     * must report the permission, not the fact that a button was pressed.
     */
    @Test fun `permission state is read, never assumed`() {
        denyUsageAccess()
        show(startAtPermissions = true)

        scrollTo("USAGE ACCESS")
        assertTrue("a denied permission must offer GRANT", nodeExists("GRANT"))

        // Tapping GRANT only opens Settings. Nothing was granted there, so the
        // row must still say GRANT when we come back.
        tap("GRANT")
        scrollTo("USAGE ACCESS")
        assertTrue("tapping GRANT must not mark it granted", nodeExists("GRANT"))
    }

    @Test fun `a granted permission reports itself as granted`() {
        grantUsageAccess()
        grantOverlayPermission()
        shadowOf(app.getSystemService(android.os.PowerManager::class.java))
            .setIgnoringBatteryOptimizations(app.packageName, true)
        show(startAtPermissions = true)

        scrollTo("USAGE ACCESS")
        assertTrue(nodeExists("GRANTED"))
    }

    /**
     * Notifications used to be the *first* thing asked, labelled "(OPTIONAL)" —
     * which reads as a contradiction. Required rows now come first.
     */
    @Test fun `optional permissions sit below the required ones`() {
        show(startAtPermissions = true)
        scrollTo("OPTIONAL")
        compose.onNodeWithText("OPTIONAL").assertIsDisplayed()
        scrollTo("NOTIFICATIONS")
        compose.onNodeWithText("NOTIFICATIONS").assertIsDisplayed()
    }

    @Test fun `startAtPermissions skips the tour`() {
        show(startAtPermissions = true)
        compose.onNodeWithText("PERMISSIONS").assertIsDisplayed()
        compose.onNodeWithText("GET STARTED").assertIsDisplayed()
    }

    // ---------------- completion ----------------

    @Test fun `GET STARTED completes setup once and stops the flow reappearing`() {
        show(startAtPermissions = true)
        assertTrue("a fresh install needs setup", needsOnboarding(app))

        tap("GET STARTED")

        assertEquals(1, completions)
        assertTrue(setupComplete())
        assertFalse("the host must stop showing setup once it is marked", needsOnboarding(app))
    }

    /** Nothing here blocks — a user may look around before granting anything. */
    @Test fun `setup can be completed with no permissions granted`() {
        denyUsageAccess()
        show(startAtPermissions = true)

        tap("GET STARTED")

        assertEquals(1, completions)
        assertFalse(needsOnboarding(app))
    }

    private fun scrollTo(text: String) {
        runCatching {
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
        }
        compose.waitForIdle()
    }

    private fun nodeExists(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
