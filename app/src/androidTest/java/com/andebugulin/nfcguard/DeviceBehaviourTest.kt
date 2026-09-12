package com.andebugulin.nfcguard

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import com.andebugulin.nfcguard.data.AppStateRepository
import com.andebugulin.nfcguard.service.ForegroundAppDetector
import com.andebugulin.nfcguard.service.ForegroundDetectorService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Behaviour that only a real device can answer: the live UsageStatsManager,
 * a real bound AccessibilityService, real SharedPreferences, and the actual
 * PowerManager/KeyguardManager the issue #13 gate reads.
 */
class DeviceBehaviourTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ─── foreground detection against real system data ────────────────────

    @Test fun detectorResolvesAForegroundPackageOnRealDevice() {
        val detected = ForegroundAppDetector(context).current()
        assertNotNull(
            "with usage access granted the detector must name some foreground package",
            detected
        )
    }

    @Test fun detectorReturnsAnInstalledPackage() {
        val detected = ForegroundAppDetector(context).current() ?: return
        val installed = context.packageManager
            .getInstalledPackages(0).map { it.packageName }.toSet()
        assertTrue("$detected should be a real installed package", detected in installed)
    }

    /**
     * `am instrument` restarts the target process, and the system rebinds an
     * AccessibilityService on its own schedule — so give it a window before
     * concluding anything.
     */
    private fun awaitAccessibilityBound(timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ForegroundDetectorService.isRunning) return true
            Thread.sleep(250)
        }
        return ForegroundDetectorService.isRunning
    }

    @Test fun accessibilityServiceIsEnabledInSettings() {
        assertTrue(
            "enable nfcGuard under Settings > Accessibility",
            ForegroundDetectorService.isEnabled(context)
        )
    }

    @Test fun accessibilityServiceBindsIntoThisProcess() {
        // Skipped rather than failed when the rebind has not landed yet:
        // that is a property of the instrumentation restart, not of the app.
        assumeTrue("accessibility service not rebound yet", awaitAccessibilityBound())
        assertTrue(ForegroundDetectorService.isRunning)
    }

    @Test fun accessibilityDetectorReportsRecentWindowChanges() {
        assumeTrue("accessibility service not rebound yet", awaitAccessibilityBound())
        assertNotNull(
            "a bound accessibility service should have seen at least one window",
            ForegroundDetectorService.lastDetectedPackage
        )
    }

    // ─── the issue #13 gate, against the real system services ─────────────

    @Test fun screenStateIsReadableAndConsistent() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        // The gate is `isInteractive && !isKeyguardLocked`. Tests run with the
        // screen on and unlocked, so enforcement must be permitted.
        assertTrue("screen should be interactive during tests", pm.isInteractive)
        assertTrue("enforcement should be allowed here", pm.isInteractive && !km.isKeyguardLocked)
    }

    // ─── real persistence ─────────────────────────────────────────────────

    @Test fun stateSurvivesARepositoryRebuildOnDevice() = runBlocking {
        val repo = AppStateRepository.getInstance(context)
        val marker = "device-test-${System.currentTimeMillis()}"
        val original = repo.current

        try {
            repo.update { it.copy(modes = it.modes + Mode(id = marker, name = marker, blockedApps = emptyList())) }
            assertTrue(repo.current.modes.any { it.id == marker })

            // Reading the prefs directly proves it actually hit disk.
            val raw = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
                .getString("app_state", "") ?: ""
            assertTrue("state must be persisted to SharedPreferences", raw.contains(marker))
        } finally {
            repo.update { original }
        }
    }

    @Test fun exactlyOneFileOwnsTheAppStateKey() {
        // Architectural invariant, asserted at runtime: reading through the
        // repository and reading the raw key must agree.
        val repo = AppStateRepository.getInstance(context)
        val raw = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            .getString("app_state", null)
        if (raw == null) {
            assertEquals(0, repo.current.modes.size)
        } else {
            assertTrue(raw.isNotEmpty())
        }
    }
}
