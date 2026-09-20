package com.andebugulin.nfcguard.service

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.data.AppStateRepository
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.resetAppStateRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * The service's own lifecycle — the part of `BlockerService` its collaborators'
 * suites cannot reach.
 *
 * TESTS.md had this down as effectively untestable, on the grounds that
 * starting the service kills the process. That is true of *instrumentation*:
 * Android answers a late `startForeground` with
 * `ForegroundServiceDidNotStartInTimeException` and takes the app down with it.
 * It is not true on the JVM, where Robolectric drives the lifecycle directly
 * and no foreground-service deadline applies.
 *
 * What that buys: the intent contract `StateSyncer` writes is pinned at both
 * ends (`StateSyncerTest` asserts what is sent, this asserts what is read), the
 * notification the user actually sees is asserted rather than assumed, and the
 * restart-on-death guard is checked in both directions.
 */
@RunWith(RobolectricTestRunner::class)
class BlockerServiceLifecycleTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val notificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val alarmManager
        get() = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @Before fun setUp() {
        resetAppStateRepository()
        grantOverlayPermission()
    }

    /** The extras exactly as `BlockerService.start` writes them. */
    private fun intentFor(
        blockedApps: Set<String> = setOf("com.example.social"),
        blockMode: BlockMode = BlockMode.BLOCK_SELECTED,
        activeModeIds: Set<String> = setOf("m1"),
        manuallyActivated: Set<String> = setOf("m1"),
        timedDeactivations: Map<String, Long> = emptyMap(),
        modeNames: Map<String, String> = mapOf("m1" to "Deep Work"),
        timedReactivations: Map<String, Long> = emptyMap()
    ) = Intent(app, BlockerService::class.java).apply {
        putStringArrayListExtra("blocked_apps", ArrayList(blockedApps))
        putExtra("block_mode", blockMode.name)
        putStringArrayListExtra("active_mode_ids", ArrayList(activeModeIds))
        putStringArrayListExtra("manually_activated_mode_ids", ArrayList(manuallyActivated))
        putExtra("timed_mode_deactivations", HashMap(timedDeactivations))
        putExtra("mode_names", HashMap(modeNames))
        putExtra("timed_mode_reactivations", HashMap(timedReactivations))
    }

    private fun start(intent: Intent = intentFor()): ServiceController<BlockerService> =
        Robolectric.buildService(BlockerService::class.java, intent).create().startCommand(0, 0)

    private fun postedNotification(): Notification =
        requireNotNull(shadowOf(notificationManager).getNotification(1)) {
            "the service posted no notification"
        }

    private fun title() = postedNotification().extras.getString(Notification.EXTRA_TITLE)
    private fun text() = postedNotification().extras.getString(Notification.EXTRA_TEXT)

    // ---------------- lifecycle ----------------

    @Test fun `creating the service marks it running`() {
        val controller = Robolectric.buildService(BlockerService::class.java).create()

        assertTrue(BlockerService.isRunning())
        controller.destroy()
    }

    @Test fun `creating the service goes to the foreground with a notification`() {
        val controller = Robolectric.buildService(BlockerService::class.java).create()

        assertNotNull(
            "a foreground service must post a notification or Android kills it",
            shadowOf(controller.get()).lastForegroundNotification
        )
        controller.destroy()
    }

    /**
     * START_STICKY is what gets the service recreated after the system kills
     * it — the difference between blocking surviving memory pressure and
     * silently stopping.
     */
    @Test fun `the service asks to be restarted if the system kills it`() {
        val service = Robolectric.buildService(BlockerService::class.java, intentFor()).create().get()

        val result = service.onStartCommand(intentFor(), 0, 0)

        assertEquals(Service.START_STICKY, result)
    }

    @Test fun `destroying the service stops it reporting as running`() {
        val controller = start()

        controller.destroy()

        assertFalse(BlockerService.isRunning())
    }

    // ---------------- what the user sees ----------------

    @Test fun `with a mode active the notification says so`() {
        start()

        assertEquals("nfcGuard ACTIVE", title())
    }

    @Test fun `with nothing active it reports that it is only watching`() {
        start(intentFor(activeModeIds = emptySet(), manuallyActivated = emptySet()))

        assertEquals("nfcGuard MONITORING", title())
        assertEquals("Waiting for scheduled modes", text())
    }

    /** An NFC unlock leaves the mode paused rather than off; the notification distinguishes them. */
    @Test fun `a mode paused by an NFC unlock is reported as paused, not off`() {
        start(
            intentFor(
                activeModeIds = emptySet(),
                manuallyActivated = emptySet(),
                timedReactivations = mapOf("m1" to System.currentTimeMillis() + 600_000)
            )
        )

        assertEquals("nfcGuard PAUSED", title())
    }

    @Test fun `the notification counts manual and scheduled modes separately`() {
        start(
            intentFor(
                activeModeIds = setOf("m1", "m2"),
                manuallyActivated = setOf("m1"),
                modeNames = mapOf("m1" to "Deep Work", "m2" to "Sleep")
            )
        )

        val text = requireNotNull(text())
        assertTrue("expected a mode count, got: $text", text.contains("2 MODES"))
        assertTrue("expected the manual one counted, got: $text", text.contains("1 manual"))
        assertTrue("expected the scheduled one counted, got: $text", text.contains("1 scheduled"))
    }

    // ---------------- surviving death ----------------

    private fun seedActiveMode() = runBlocking {
        AppStateRepository.getInstance(app).update {
            it.copy(modes = listOf(mode(id = "m1", name = "Deep Work")), activeModes = setOf("m1"))
        }
    }

    private fun scheduledAlarmCount() = shadowOf(alarmManager).scheduledAlarms.size

    @Test fun `swiping the app away schedules the service back`() {
        seedActiveMode()
        val controller = start()
        shadowOf(alarmManager).scheduledAlarms.clear()

        controller.get().onTaskRemoved(null)

        assertTrue(
            "blocking must survive the user swiping the task away",
            scheduledAlarmCount() > 0
        )
        controller.destroy()
    }

    /**
     * The other direction, and the one that matters for battery and for not
     * surprising the user: with nothing active there is nothing to restart.
     */
    @Test fun `with nothing active it does not resurrect itself`() {
        val controller = start(intentFor(activeModeIds = emptySet(), manuallyActivated = emptySet()))
        shadowOf(alarmManager).scheduledAlarms.clear()

        controller.get().onTaskRemoved(null)

        assertEquals(0, scheduledAlarmCount())
        controller.destroy()
    }

    @Test fun `being destroyed while a mode is active also schedules a restart`() {
        seedActiveMode()
        val controller = start()
        shadowOf(alarmManager).scheduledAlarms.clear()

        controller.destroy()

        assertTrue(scheduledAlarmCount() > 0)
    }
}
