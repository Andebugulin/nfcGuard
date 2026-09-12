package com.andebugulin.nfcguard

import android.app.KeyguardManager
import android.content.Context
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.andebugulin.nfcguard.data.Permissions
import com.andebugulin.nfcguard.service.ForegroundDetectorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preflight: asserts the device is actually in the state the rest of the
 * instrumented suite assumes. A failure here means the harness is
 * misconfigured, not that the app is broken — so it runs first and reports
 * precisely which grant is missing.
 */
class EnvironmentTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun deviceIsSupported() {
        assertTrue("minSdk is 26", Build.VERSION.SDK_INT >= 26)
    }

    @Test fun usageAccessIsGranted() {
        assertTrue(
            "grant with: adb shell appops set ${context.packageName} GET_USAGE_STATS allow",
            Permissions.hasUsageStats(context)
        )
    }

    @Test fun overlayPermissionIsGranted() {
        assertTrue(
            "grant with: adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow",
            Settings.canDrawOverlays(context)
        )
    }

    @Test fun batteryOptimizationIsExempt() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        assertTrue(
            "grant with: adb shell dumpsys deviceidle whitelist +${context.packageName}",
            pm.isIgnoringBatteryOptimizations(context.packageName)
        )
    }

    @Test fun accessibilityServiceIsEnabled() {
        assertTrue(
            "enable nfcGuard in Settings > Accessibility",
            ForegroundDetectorService.isEnabled(context)
        )
    }

    @Test fun deviceHasNfcHardware() {
        assertNotNull("this device reports no NFC adapter", NfcAdapter.getDefaultAdapter(context))
    }

    @Test fun screenIsOnAndUnlockedForUiTests() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertTrue("screen must be awake", pm.isInteractive)
        assertEquals("device must be unlocked", false, km.isKeyguardLocked)
    }
}
