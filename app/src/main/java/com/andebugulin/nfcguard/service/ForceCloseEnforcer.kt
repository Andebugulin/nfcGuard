package com.andebugulin.nfcguard.service

import com.andebugulin.nfcguard.BlockDecider
import com.andebugulin.nfcguard.data.AppLogger

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Block a foreground app by sending the user to HOME and killing the
 * app's background processes.
 *
 * Preferred over [OverlayEnforcer] when [ForegroundDetectorService] is
 * running, because (a) we can ask the accessibility service to dispatch
 * HOME (bypasses MIUI's background-activity-start restriction) and
 * (b) the SYSTEM_ALERT_WINDOW overlay races badly with accessibility's
 * own window-state events on Samsung and some MIUI builds.
 *
 * Owns a 3-second cooldown so we don't spam HOME + toast every polling
 * tick while accessibility catches up (~3-4s of stale events).
 */
class ForceCloseEnforcer(private val context: Context) : Enforcer {

    private var lastClosedApp: String? = null
    private var lastCloseTime: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun block(packageName: String) {
        val now = System.currentTimeMillis()
        if (lastClosedApp == packageName && (now - lastCloseTime) < COOLDOWN_MS) {
            android.util.Log.d(TAG, "Cooldown active for $packageName, skipping")
            return
        }
        forceClose(packageName)
        lastClosedApp = packageName
        lastCloseTime = now
    }

    override suspend fun onAllowed(currentApp: String, isLauncher: Boolean) {
        // Only reset cooldown when the user is in a REAL app — not the
        // launcher and not a critical system app. The launcher is a
        // transient state during HOME animation; clearing the cooldown
        // there means the next spurious a11y event for the blocked app
        // triggers another force-close and ejects the user from whatever
        // they opened next.
        val isCritical = currentApp in BlockDecider.CRITICAL_SYSTEM_APPS
        if (lastClosedApp != null && !isLauncher && !isCritical) {
            android.util.Log.d(TAG, "Cooldown reset: user in real app $currentApp")
            lastClosedApp = null
            lastCloseTime = 0L
        }
    }

    override fun onDestroy() {
        // Nothing to release
    }

    private fun forceClose(packageName: String) {
        android.util.Log.d(TAG, "FORCE-CLOSING: $packageName")
        AppLogger.log("SERVICE", "FORCE-CLOSE: $packageName")

        // 1. HOME. Prefer the AccessibilityService (works around MIUI's
        //    BAL restrictions); fall back to a HOME intent.
        var sentHome = false
        if (ForegroundDetectorService.isRunning) {
            sentHome = ForegroundDetectorService.goHome()
            if (sentHome) android.util.Log.d(TAG, "Sent HOME via AccessibilityService")
        }
        if (!sentHome) {
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(homeIntent)
                android.util.Log.d(TAG, "Sent HOME via Intent fallback")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to launch home: ${e.message}")
            }
        }

        // 2. Kill the blocked app's background processes.
        // NOTE: killBackgroundProcesses only kills bg services, not the
        // foreground activity. The app may remain in recents. If the user
        // reopens it, the next polling tick (after the cooldown expires)
        // will force-close again.
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            android.util.Log.d(TAG, "Killed background processes: $packageName")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to kill $packageName: ${e.message}")
        }

        // 3. Toast on the main thread.
        mainHandler.post {
            try {
                android.widget.Toast.makeText(
                    context,
                    "BLOCKED — go to nfcGuard & tap NFC tag to unlock",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Toast failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "FORCE_CLOSE_ENFORCER"
        const val COOLDOWN_MS = 3_000L
    }
}
