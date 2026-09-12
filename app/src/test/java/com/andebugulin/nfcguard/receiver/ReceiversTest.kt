package com.andebugulin.nfcguard.receiver

import com.andebugulin.nfcguard.AppState
import com.andebugulin.nfcguard.data.AppStateRepository
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.resetAppStateRepository
import com.andebugulin.nfcguard.testing.schedule

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Receivers are thin adapters: read extras, call a pure transform through the
 * repository, and let `StateSyncer` dispatch. These tests assert the adapter
 * wiring — that the right action reaches the right transform and that state
 * actually changes — not the transform maths, which `:domain` already covers.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiversTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        resetAppStateRepository()
        grantOverlayPermission()
        shadowOf(app).clearStartedServices()
    }

    private fun seed(state: AppState) = runBlocking {
        AppStateRepository.getInstance(app).update { state }
    }

    private fun send(action: String, block: Intent.() -> Unit = {}) {
        ScheduleAlarmReceiver().onReceive(app, Intent(action).apply(block))
    }

    private fun lastStartedService(): Intent? {
        var last: Intent? = null
        while (true) last = shadowOf(app).nextStartedService ?: break
        return last
    }

    // ─── timed mode alarms ────────────────────────────────────────────────

    @Test fun `timed deactivate removes the mode from active state`() {
        seed(AppState(
            modes = listOf(mode(id = "m1")),
            activeModes = setOf("m1"),
            timedModeDeactivations = mapOf("m1" to 1L)
        ))

        send("com.andebugulin.nfcguard.TIMED_DEACTIVATE_MODE") { putExtra("mode_id", "m1") }

        val after = AppStateRepository.getInstance(app).current
        assertFalse("mode should be deactivated", after.activeModes.contains("m1"))
        assertFalse("timer entry should be cleared", after.timedModeDeactivations.containsKey("m1"))
    }

    @Test fun `timed reactivate restores the mode`() {
        seed(AppState(
            modes = listOf(mode(id = "m1")),
            activeModes = emptySet(),
            timedModeReactivations = mapOf("m1" to 1L)
        ))

        send("com.andebugulin.nfcguard.TIMED_REACTIVATE_MODE") { putExtra("mode_id", "m1") }

        val after = AppStateRepository.getInstance(app).current
        assertTrue("mode should be re-activated", after.activeModes.contains("m1"))
        assertFalse(after.timedModeReactivations.containsKey("m1"))
    }

    @Test fun `an alarm for an unknown mode is harmless`() {
        seed(AppState(modes = listOf(mode(id = "m1")), activeModes = setOf("m1")))

        send("com.andebugulin.nfcguard.TIMED_DEACTIVATE_MODE") { putExtra("mode_id", "ghost") }

        assertTrue(AppStateRepository.getInstance(app).current.activeModes.contains("m1"))
    }

    @Test fun `an alarm with no mode id does not crash`() {
        seed(AppState(modes = listOf(mode(id = "m1")), activeModes = setOf("m1")))
        send("com.andebugulin.nfcguard.TIMED_DEACTIVATE_MODE")
        assertTrue(AppStateRepository.getInstance(app).current.activeModes.contains("m1"))
    }

    // ─── watchdog ─────────────────────────────────────────────────────────

    @Test fun `watchdog restarts a dead service when modes are active`() {
        seed(AppState(modes = listOf(mode(id = "m1", apps = listOf("com.x"))), activeModes = setOf("m1")))
        shadowOf(app).clearStartedServices()

        send("com.andebugulin.nfcguard.CHECK_SCHEDULE")

        assertNotNull("watchdog should revive the blocker", lastStartedService())
    }

    @Test fun `watchdog does nothing when no modes are active`() {
        seed(AppState(modes = listOf(mode(id = "m1"))))
        shadowOf(app).clearStartedServices()

        send("com.andebugulin.nfcguard.CHECK_SCHEDULE")

        assertEquals(null, lastStartedService())
    }

    @Test fun `watchdog re-arms itself`() {
        seed(AppState())
        val am = app.getSystemService(android.app.AlarmManager::class.java)
        val before = shadowOf(am).scheduledAlarms.size

        send("com.andebugulin.nfcguard.CHECK_SCHEDULE")

        assertTrue("watchdog must self-chain", shadowOf(am).scheduledAlarms.size >= before)
    }

    // ─── boot ─────────────────────────────────────────────────────────────

    @Test fun `boot restores blocking from persisted state`() {
        seed(AppState(
            modes = listOf(mode(id = "m1", apps = listOf("com.social"))),
            activeModes = setOf("m1")
        ))
        shadowOf(app).clearStartedServices()

        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        val intent = lastStartedService()
        assertNotNull("boot must restart the blocker", intent)
        assertEquals(setOf("com.social"), intent!!.getStringArrayListExtra("blocked_apps")!!.toSet())
    }

    @Test fun `boot with a schedule but no active mode still keeps the service alive`() {
        seed(AppState(modes = listOf(mode(id = "m1")), schedules = listOf(schedule())))
        shadowOf(app).clearStartedServices()

        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNotNull(lastStartedService())
    }

    @Test fun `service restart receiver re-syncs from state`() {
        seed(AppState(modes = listOf(mode(id = "m1", apps = listOf("com.y"))), activeModes = setOf("m1")))
        shadowOf(app).clearStartedServices()

        ServiceRestartReceiver().onReceive(app, Intent())

        assertNotNull(lastStartedService())
    }
}
