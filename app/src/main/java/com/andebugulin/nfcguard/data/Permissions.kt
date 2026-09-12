package com.andebugulin.nfcguard.data

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Single owner of permission checks that need API-level handling.
 *
 * [AppOpsManager.unsafeCheckOpNoThrow] only exists from API 29. On older
 * devices it raises `NoSuchMethodError` — an [Error], not an [Exception], so
 * the `catch (Exception)` blocks that previously wrapped each call site did
 * not stop the crash. With `minSdk = 26` that hard-crashed every Android 8
 * and 9 device on first render of Home (issue #12, Galaxy S8 / API 28).
 */
object Permissions {

    /** True when the user has granted Usage Access to this app. */
    fun hasUsageStats(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        // Throwable, not Exception: the whole point of this helper.
        false
    }
}
