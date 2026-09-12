package com.andebugulin.nfcguard.sync

import com.andebugulin.nfcguard.AppState
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.resetAppStateRepository
import com.andebugulin.nfcguard.testing.schedule

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * `StateSyncer` is the only place allowed to dispatch `BlockerService.start`,
 * so the exact arguments it computes are load-bearing: ALLOW/BLOCK polarity,
 * the app union, and the keep-alive-vs-stop decision.
 */
@RunWith(RobolectricTestRunner::class)
class StateSyncerTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        resetAppStateRepository()
        grantOverlayPermission()
        shadowOf(app).clearStartedServices()
    }

    /** Last service Intent StateSyncer dispatched, or null if it started none. */
    private fun startedService(): Intent? {
        var last: Intent? = null
        while (true) last = shadowOf(app).nextStartedService ?: break
        return last
    }

    private fun apps(i: Intent) = i.getStringArrayListExtra("blocked_apps")!!.toSet()
    private fun blockMode(i: Intent) = i.getStringExtra("block_mode")
    private fun activeIds(i: Intent) = i.getStringArrayListExtra("active_mode_ids")!!.toSet()

    // ─── service start / stop decision ────────────────────────────────────

    @Test fun `nothing active and nothing pending stops the service`() {
        StateSyncer.sync(app, AppState())

        assertNull("no service should start", startedService())
        assertNotNull("service should be stopped", shadowOf(app).nextStoppedService)
    }

    @Test fun `no active modes but a schedule keeps the service alive`() {
        StateSyncer.sync(app, AppState(schedules = listOf(schedule())))

        val intent = startedService()
        assertNotNull("service must stay alive to watch the schedule", intent)
        assertEquals(emptySet<String>(), apps(intent!!))
        assertEquals(emptySet<String>(), activeIds(intent))
    }

    @Test fun `no active modes but a pending reactivation keeps the service alive`() {
        StateSyncer.sync(app, AppState(timedModeReactivations = mapOf("m1" to 123L)))
        assertNotNull(startedService())
    }

    // ─── polarity and app union ───────────────────────────────────────────

    @Test fun `single BLOCK mode passes its apps through as BLOCK_SELECTED`() {
        StateSyncer.sync(app, AppState(
            modes = listOf(mode(id = "m1", apps = listOf("com.a", "com.b"))),
            activeModes = setOf("m1")
        ))

        val intent = startedService()!!
        assertEquals("BLOCK_SELECTED", blockMode(intent))
        assertEquals(setOf("com.a", "com.b"), apps(intent))
        assertEquals(setOf("m1"), activeIds(intent))
    }

    @Test fun `two active BLOCK modes union their blocklists`() {
        StateSyncer.sync(app, AppState(
            modes = listOf(
                mode(id = "m1", apps = listOf("com.a")),
                mode(id = "m2", apps = listOf("com.b"))
            ),
            activeModes = setOf("m1", "m2")
        ))

        assertEquals(setOf("com.a", "com.b"), apps(startedService()!!))
    }

    @Test fun `ALLOW mode wins over BLOCK and contributes only its own apps`() {
        StateSyncer.sync(app, AppState(
            modes = listOf(
                mode(id = "block", apps = listOf("com.blocked"), blockMode = BlockMode.BLOCK_SELECTED),
                mode(id = "allow", apps = listOf("com.allowed"), blockMode = BlockMode.ALLOW_SELECTED)
            ),
            activeModes = setOf("block", "allow")
        ))

        val intent = startedService()!!
        assertEquals("ALLOW_SELECTED", blockMode(intent))
        assertEquals(
            "apps from the BLOCK mode must not leak into the allowlist",
            setOf("com.allowed"), apps(intent)
        )
    }

    @Test fun `inactive modes do not contribute apps`() {
        StateSyncer.sync(app, AppState(
            modes = listOf(
                mode(id = "on", apps = listOf("com.on")),
                mode(id = "off", apps = listOf("com.off"))
            ),
            activeModes = setOf("on")
        ))

        assertEquals(setOf("com.on"), apps(startedService()!!))
    }

    // ─── timed alarm diffing ──────────────────────────────────────────────

    private fun alarmCount() =
        shadowOf(app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.size

    @Test fun `a newly added deactivation schedules an alarm`() {
        val before = AppState(modes = listOf(mode()), activeModes = setOf("m1"))
        val after = before.copy(timedModeDeactivations = mapOf("m1" to System.currentTimeMillis() + 60_000))

        StateSyncer.sync(app, before, after)
        assert(alarmCount() > 0) { "expected a timed deactivation alarm" }
    }

    @Test fun `an unchanged deactivation does not reschedule`() {
        val at = System.currentTimeMillis() + 60_000
        val state = AppState(
            modes = listOf(mode()), activeModes = setOf("m1"),
            timedModeDeactivations = mapOf("m1" to at)
        )
        // old == new for the map entry → no TimedAlarms call for it
        StateSyncer.sync(app, state, state)
        // Watchdog/schedule alarms may exist; assert only that this is stable.
        val first = alarmCount()
        StateSyncer.sync(app, state, state)
        assertEquals(first, alarmCount())
    }

    @Test fun `sync is idempotent for the same state`() {
        val state = AppState(modes = listOf(mode()), activeModes = setOf("m1"))
        StateSyncer.sync(app, state)
        val a = startedService()
        StateSyncer.sync(app, state)
        val b = startedService()
        assertEquals(apps(a!!), apps(b!!))
        assertEquals(blockMode(a), blockMode(b))
    }
}
