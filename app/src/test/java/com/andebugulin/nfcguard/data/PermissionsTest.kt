package com.andebugulin.nfcguard.data

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression tests for issue #12 (crash on Galaxy S8, API 28).
 *
 * `AppOpsManager.unsafeCheckOpNoThrow` only exists from API 29. Robolectric
 * loads the real `android-all` jar for each configured SDK, so on sdk 26/28
 * the method genuinely is absent and the pre-fix code raised
 * `NoSuchMethodError` — an Error, which the `catch (Exception)` around each
 * call site did not stop.
 *
 * Running the same assertions across the API range is the whole point: it is
 * what turns "works on my Pixel" into coverage from `minSdk = 26` upward.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun setUsageStatsMode(mode: Int) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
            mode
        )
    }

    @Test
    @Config(sdk = [26, 28, 29, 33, 34])
    fun `reports granted when the op is allowed, on every supported API level`() {
        setUsageStatsMode(AppOpsManager.MODE_ALLOWED)
        assertTrue(Permissions.hasUsageStats(context))
    }

    @Test
    @Config(sdk = [26, 28, 29, 33, 34])
    fun `reports denied when the op is not allowed, on every supported API level`() {
        setUsageStatsMode(AppOpsManager.MODE_ERRORED)
        assertFalse(Permissions.hasUsageStats(context))
    }
}
