package com.andebugulin.nfcguard.service

import com.andebugulin.nfcguard.AppState
import com.andebugulin.nfcguard.data.AppStateRepository
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.resetAppStateRepository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Regression tests for issue #10 (force-stop bypass on OneUI).
 *
 * A force-stop puts the app in stopped state: pending alarms are cancelled
 * and implicit broadcasts are withheld, so neither the 15-minute watchdog nor
 * `BootReceiver` can revive blocking. An enabled AccessibilityService is the
 * one component Android rebinds by itself, which makes `onServiceConnected`
 * the only dependable recovery hook — it must restore enforcement.
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundDetectorServiceTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        resetAppStateRepository()
        grantOverlayPermission()
        shadowOf(app).clearStartedServices()
    }

    private fun connectAccessibilityService() {
        val service = Robolectric.buildService(ForegroundDetectorService::class.java).create().get()
        // onServiceConnected is protected in AccessibilityService.
        val m = ForegroundDetectorService::class.java.getDeclaredMethod("onServiceConnected")
        m.isAccessible = true
        m.invoke(service)
    }

    private fun lastStartedService(): android.content.Intent? {
        var last: android.content.Intent? = null
        while (true) last = shadowOf(app).nextStartedService ?: break
        return last
    }

    @Test fun `reconnecting restores blocking for the persisted active mode`() {
        runBlocking {
            AppStateRepository.getInstance(app).update {
                AppState(modes = listOf(mode(id = "m1", apps = listOf("com.social"))),
                         activeModes = setOf("m1"))
            }
        }
        shadowOf(app).clearStartedServices()   // ignore the write's own sync

        connectAccessibilityService()

        val intent = lastStartedService()
        assertNotNull("accessibility reconnect must restart the blocker", intent)
        assertEquals(
            setOf("com.social"),
            intent!!.getStringArrayListExtra("blocked_apps")!!.toSet()
        )
        assertEquals(setOf("m1"), intent.getStringArrayListExtra("active_mode_ids")!!.toSet())
    }

    @Test fun `reconnecting with nothing active does not start the blocker`() {
        connectAccessibilityService()
        // No modes, schedules, or reactivations → StateSyncer stops instead.
        assertNotNull(shadowOf(app).nextStoppedService)
    }

    @Test fun `reconnecting marks the detector as running`() {
        connectAccessibilityService()
        assertEquals(true, ForegroundDetectorService.isRunning)
    }
}
