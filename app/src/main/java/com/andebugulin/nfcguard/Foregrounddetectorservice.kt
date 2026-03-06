package com.andebugulin.nfcguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent

/**
 * Lightweight AccessibilityService that only listens for TYPE_WINDOW_STATE_CHANGED
 * to reliably detect which app is in the foreground.
 *
 * This exists because UsageStatsManager is blind to recents→app transitions
 * on Pixel (and possibly other AOSP-based) devices. The accessibility service
 * gets real-time window change events regardless of navigation mode.
 *
 * The service does NOT read window content, text, or any user data.
 * It only stores the package name of the most recently focused window.
 */
class ForegroundDetectorService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // No package filter — we need to see all app switches
            notificationTimeout = 100
            // We do NOT need window content
            flags = 0
        }
        serviceInfo = info
        isRunning = true
        android.util.Log.d("FG_DETECTOR", "ForegroundDetectorService connected")
        AppLogger.log("SERVICE", "ForegroundDetectorService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Ignore system UI overlays (keyboard, status bar, etc.)
// Ignore system UI overlays (keyboard, status bar, etc.)
            if (pkg == "com.android.systemui") return
            // Ignore our own overlay — it triggers TYPE_WINDOW_STATE_CHANGED
            // when shown, which would make BlockerService think the user opened
            // Guardian and hide the overlay immediately.
            if (pkg == "com.andebugulin.nfcguard") return
            lastDetectedPackage = pkg
            lastDetectedTime = System.currentTimeMillis()
        }
    }

    override fun onInterrupt() {
        android.util.Log.w("FG_DETECTOR", "ForegroundDetectorService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        android.util.Log.d("FG_DETECTOR", "ForegroundDetectorService destroyed")
    }

    companion object {
        @Volatile
        var lastDetectedPackage: String? = null
            private set

        @Volatile
        var lastDetectedTime: Long = 0L
            private set

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Check if the accessibility service is enabled in system settings.
         */
        fun isEnabled(context: Context): Boolean {
            val serviceName = "${context.packageName}/${ForegroundDetectorService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                if (colonSplitter.next().equals(serviceName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
    }
}