package com.andebugulin.nfcguard.ui

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.testing.enableAccessibilityService
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.ui.onboarding.PermissionOnboarding
import com.andebugulin.nfcguard.ui.onboarding.shouldShowOnboarding
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
import org.robolectric.util.ReflectionHelpers

/**
 * The permission flow, which is a dialog state machine rather than a screen.
 *
 * Only its first step had any coverage — the e2e onboarding test walks the
 * carousel and asserts the handoff lands on WELCOME. Everything past that was
 * untested, and this is the neighbourhood issue #12 lived in, so the step
 * transitions are pinned against the real permission state that drives them.
 *
 * It runs on the JVM because none of these dialogs holds a text field; the
 * grant buttons fire Settings intents, which Robolectric records rather than
 * dispatching.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp")
class PermissionOnboardingTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var done = 0

    @Before fun setUp() {
        app.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun show() {
        compose.setContent { MinimalistTheme { PermissionOnboarding(onDone = { done++ }) } }
    }

    private fun tap(text: String) {
        compose.onAllNodesWithText(text).onFirst().performClick()
        compose.waitForIdle()
    }

    /** Everything the flow can ask for, already granted. */
    private fun grantEverything() {
        grantOverlayPermission()
        shadowOf(app.getSystemService(android.os.PowerManager::class.java))
            .setIgnoringBatteryOptimizations(app.packageName, true)
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(android.app.AppOpsManager::class.java))
            .setMode(
                "android:get_usage_stats",
                android.os.Process.myUid(),
                app.packageName,
                android.app.AppOpsManager.MODE_ALLOWED
            )
    }

    /** Robolectric's default appops mode is ALLOWED, so this must be explicit. */
    private fun denyUsageAccess() {
        shadowOf(app.getSystemService(android.app.AppOpsManager::class.java))
            .setMode(
                "android:get_usage_stats",
                android.os.Process.myUid(),
                app.packageName,
                android.app.AppOpsManager.MODE_ERRORED
            )
    }

    private fun initialPermissionsMarked() =
        app.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            .getBoolean("initial_permissions_granted", false)

    // ---------------- entry ----------------

    @Test fun `the flow opens on the welcome step`() {
        show()
        compose.onNodeWithText("WELCOME TO nfcGuard").assertIsDisplayed()
        compose.onNodeWithText("CONTINUE").assertIsDisplayed()
        compose.onNodeWithText("SKIP").assertIsDisplayed()
    }

    @Test fun `skipping the whole flow records it as done and does not ask again`() {
        show()

        tap("SKIP")

        assertEquals(1, done)
        assertTrue(initialPermissionsMarked())
        app.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("has_seen_onboarding", true).commit()
        assertFalse(
            "the host must stop showing the flow once it is marked",
            shouldShowOnboarding(app)
        )
    }

    // ---------------- the permission queue ----------------

    @Test fun `a missing permission is explained before it is requested`() {
        grantEverything()
        denyUsageAccess()
        show()

        tap("CONTINUE")

        compose.onNodeWithText("USAGE ACCESS").assertIsDisplayed()   // titles render .uppercase()
        compose.onNodeWithText("GRANT").assertIsDisplayed()
    }

    @Test fun `the queue advances through every missing permission in turn`() {
        // Notifications and battery granted; usage access and overlay denied.
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(android.os.PowerManager::class.java))
            .setIgnoringBatteryOptimizations(app.packageName, true)
        denyUsageAccess()
        grantOverlayPermissionOff()
        show()

        tap("CONTINUE")
        compose.onNodeWithText("USAGE ACCESS").assertIsDisplayed()   // titles render .uppercase()

        tap("SKIP")
        compose.onNodeWithText("DISPLAY OVER APPS").assertIsDisplayed()

        tap("SKIP")
        // Queue exhausted → the pause-app reminder.
        compose.onNodeWithText("IMPORTANT: DISABLE 'PAUSE APP IF UNUSED'").assertIsDisplayed()
    }

    @Test fun `granting a permission opens the right settings screen`() {
        grantEverything()
        grantOverlayPermissionOff()
        show()
        tap("CONTINUE")
        compose.onNodeWithText("DISPLAY OVER APPS").assertIsDisplayed()

        tap("GRANT")

        val started = shadowOf(app).nextStartedActivity
        assertEquals(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            started?.action
        )
    }

    @Test fun `with everything already granted it goes straight to the pause reminder`() {
        grantEverything()
        show()

        tap("CONTINUE")

        compose.onNodeWithText("IMPORTANT: DISABLE 'PAUSE APP IF UNUSED'").assertIsDisplayed()
    }

    // ---------------- the tail ----------------

    @Test fun `clearing the pause reminder records the flow as complete`() {
        grantEverything()
        show()
        tap("CONTINUE")

        tap("OK")

        assertTrue(
            "the user has been through the flow, so it must not reappear",
            initialPermissionsMarked()
        )
    }

    @Test fun `a Pixel is told accessibility is required, not optional`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")
        grantEverything()
        show()
        tap("CONTINUE")

        tap("OK")

        compose.onNodeWithText("PIXEL DEVICE DETECTED").assertIsDisplayed()
        compose.onNodeWithText("OPEN SETTINGS").assertIsDisplayed()
    }

    @Test fun `a Samsung gets the same required treatment`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Samsung")
        grantEverything()
        show()
        tap("CONTINUE")

        tap("OK")

        compose.onNodeWithText("SAMSUNG DEVICE DETECTED").assertIsDisplayed()
        compose.onNodeWithText("OPEN SETTINGS").assertIsDisplayed()
    }

    @Test fun `any other device is offered accessibility as a recommendation`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Xiaomi")
        grantEverything()
        show()
        tap("CONTINUE")

        tap("OK")

        compose.onNodeWithText("IMPROVE RELIABILITY").assertIsDisplayed()
        compose.onNodeWithText("ENABLE").assertIsDisplayed()
    }

    @Test fun `the accessibility step is skipped when the service is already on`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Xiaomi")
        grantEverything()
        enableAccessibilityService(app)
        show()
        tap("CONTINUE")

        tap("OK")

        assertEquals("the flow should simply finish", 1, done)
    }

    @Test fun `the accessibility recommendation is not shown twice`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Xiaomi")
        grantEverything()
        app.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("accessibility_recommendation_shown", true).commit()
        show()
        tap("CONTINUE")

        tap("OK")

        assertEquals(1, done)
    }

    private fun grantOverlayPermissionOff() =
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
}
