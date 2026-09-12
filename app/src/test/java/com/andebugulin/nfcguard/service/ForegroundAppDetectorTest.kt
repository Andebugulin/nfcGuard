package com.andebugulin.nfcguard.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The three detection strategies, in priority order, plus the timestamp
 * comparison in `resolveFromUsageEvents` that the source comments call
 * "load-bearing" — Chrome paused by our own overlay reproduces it.
 *
 * The last test documents the fallback that caused issue #13.
 */
@RunWith(RobolectricTestRunner::class)
class ForegroundAppDetectorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val usm: UsageStatsManager
        get() = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private fun setStatic(name: String, value: Any?) {
        val f = ForegroundDetectorService::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(null, value)
    }

    @Before fun reset() {
        setStatic("isRunning", false)
        setStatic("lastDetectedPackage", null)
        setStatic("lastDetectedTime", 0L)
    }

    @After fun tearDown() = reset()

    private fun addEvent(pkg: String, at: Long, type: Int) =
        shadowOf(usm).addEvent(pkg, at, type)

    // ─── strategy 1: accessibility ────────────────────────────────────────

    @Test fun `uses a fresh accessibility reading`() {
        setStatic("isRunning", true)
        setStatic("lastDetectedPackage", "com.fresh")
        setStatic("lastDetectedTime", System.currentTimeMillis())

        assertEquals("com.fresh", ForegroundAppDetector(context).current())
    }

    @Test fun `ignores a stale accessibility reading older than 5s`() {
        setStatic("isRunning", true)
        setStatic("lastDetectedPackage", "com.stale")
        setStatic("lastDetectedTime", System.currentTimeMillis() - 6_000)

        // No usage events seeded either → nothing to report.
        assertNull(ForegroundAppDetector(context).current())
    }

    @Test fun `ignores accessibility data when the service is not running`() {
        setStatic("isRunning", false)
        setStatic("lastDetectedPackage", "com.notrunning")
        setStatic("lastDetectedTime", System.currentTimeMillis())

        assertNull(ForegroundAppDetector(context).current())
    }

    // ─── strategy 2: usage events ─────────────────────────────────────────

    @Test fun `returns the most recently resumed app`() {
        val now = System.currentTimeMillis()
        addEvent("com.old", now - 20_000, UsageEvents.Event.ACTIVITY_RESUMED)
        addEvent("com.current", now - 1_000, UsageEvents.Event.ACTIVITY_RESUMED)

        assertEquals("com.current", ForegroundAppDetector(context).current())
    }

    @Test fun `an app resumed after being paused is still foreground`() {
        // The load-bearing case: same package is both last-resumed and
        // last-paused. Comparing identity alone would wrongly return null.
        val now = System.currentTimeMillis()
        addEvent("com.chrome", now - 5_000, UsageEvents.Event.ACTIVITY_RESUMED)
        addEvent("com.chrome", now - 4_000, UsageEvents.Event.ACTIVITY_PAUSED)
        addEvent("com.chrome", now - 1_000, UsageEvents.Event.ACTIVITY_RESUMED)

        assertEquals("com.chrome", ForegroundAppDetector(context).current())
    }

    @Test fun `an app paused after its last resume is not foreground`() {
        val now = System.currentTimeMillis()
        addEvent("com.gone", now - 5_000, UsageEvents.Event.ACTIVITY_RESUMED)
        addEvent("com.gone", now - 1_000, UsageEvents.Event.ACTIVITY_PAUSED)

        assertNull(ForegroundAppDetector(context).current())
    }

    @Test fun `accessibility takes priority over usage events`() {
        val now = System.currentTimeMillis()
        addEvent("com.fromevents", now - 1_000, UsageEvents.Event.ACTIVITY_RESUMED)
        setStatic("isRunning", true)
        setStatic("lastDetectedPackage", "com.fromaccessibility")
        setStatic("lastDetectedTime", now)

        assertEquals("com.fromaccessibility", ForegroundAppDetector(context).current())
    }

    @Test fun `reports nothing when no source has data`() {
        assertNull(ForegroundAppDetector(context).current())
    }
}
