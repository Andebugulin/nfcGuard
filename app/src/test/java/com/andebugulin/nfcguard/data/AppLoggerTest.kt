package com.andebugulin.nfcguard.data

import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.resetAppStateRepository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The in-app logger is what users attach to bug reports (issue #10 came with
 * one), so its rotation cap and report contents are user-facing behaviour.
 *
 * `buildFullReport` runs the permission probes, which is why it is exercised
 * across API levels — it was one of the five issue #12 crash sites.
 */
@RunWith(RobolectricTestRunner::class)
class AppLoggerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        resetAppStateRepository()
        grantOverlayPermission()
        AppLogger.init(context)
        AppLogger.clear()
    }

    @Test fun `logs are retained in order`() {
        AppLogger.log("NFC", "first")
        AppLogger.log("NFC", "second")

        val text = AppLogger.getLogText()
        assertTrue(text.contains("first"))
        assertTrue(text.contains("second"))
        assertTrue("order preserved", text.indexOf("first") < text.indexOf("second"))
    }

    @Test fun `the category is included so logcat filtering works`() {
        AppLogger.log("SCHEDULE", "alarm fired")
        assertTrue(AppLogger.getLogText().contains("SCHEDULE"))
    }

    @Test fun `entries are capped at 1000`() {
        repeat(1200) { AppLogger.log("TEST", "entry $it") }
        assertEquals(1000, AppLogger.getEntryCount())
    }

    @Test fun `the cap drops the oldest entries, not the newest`() {
        repeat(1200) { AppLogger.log("TEST", "entry $it") }

        val text = AppLogger.getLogText()
        assertTrue("newest kept", text.contains("entry 1199"))
        assertTrue("oldest dropped", !text.contains("entry 0 "))
    }

    @Test fun `clear drops prior entries and leaves an audit marker`() {
        AppLogger.log("TEST", "something")
        AppLogger.log("TEST", "something else")
        AppLogger.clear()

        // clear() deliberately records that it happened, so the buffer holds
        // exactly one entry afterwards — the marker, not the old lines.
        assertEquals(1, AppLogger.getEntryCount())
        val text = AppLogger.getLogText()
        assertTrue(text.contains("Logs cleared"))
        assertTrue(!text.contains("something"))
    }

    @Test
    @Config(sdk = [26, 28, 29, 34])
    fun `full report builds on every supported API level`() {
        // Regression for issue #12: this path probes usage-access permission.
        AppLogger.log("TEST", "context line")
        val report = AppLogger.buildFullReport(context)

        assertTrue(report.contains("NFCGUARD BUG REPORT"))
        assertTrue(report.contains("Usage Access:"))
        assertTrue(report.contains("Android:"))
    }
}
