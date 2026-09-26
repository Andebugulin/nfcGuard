package com.andebugulin.nfcguard.data

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.andebugulin.nfcguard.service.ForegroundDetectorService

/**
 * Single owner of "does nfcGuard hold permission X, and how does the user grant
 * it". Both the first-run permissions page and the Settings permission list read
 * from here, so the two can never disagree about what is actually granted.
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

    /** True when nfcGuard may draw the block overlay over other apps. */
    fun hasOverlay(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /** True when Android has been told not to doze nfcGuard's service off. */
    fun hasBatteryExemption(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /**
     * True when notifications may be posted. Below API 33 there is no runtime
     * permission, so this is always true.
     */
    fun hasNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** True when the accessibility-based foreground detector is switched on. */
    fun hasAccessibility(context: Context): Boolean =
        runCatching { ForegroundDetectorService.isEnabled(context) }.getOrDefault(false)

    /**
     * Pixel and Samsung have a detection bug that only the accessibility path
     * works around, so there it is a requirement rather than a nicety.
     * See `ForegroundAppDetector` and `BlockerService`'s enforcer selection.
     */
    fun accessibilityIsRequired(): Boolean =
        Build.MANUFACTURER.equals("Google", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Samsung", ignoreCase = true)

    /** Every permission nfcGuard needs before blocking is dependable. */
    fun allEssentialGranted(context: Context): Boolean =
        hasUsageStats(context) &&
            hasOverlay(context) &&
            hasBatteryExemption(context) &&
            (!accessibilityIsRequired() || hasAccessibility(context))

    // ─── How the user grants each one ──────────────────────────────────────

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun overlayIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )

    fun batteryIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    /** Some OEMs ignore the per-app battery intent; this screen always exists. */
    fun batteryFallbackIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun accessibilityIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
}
