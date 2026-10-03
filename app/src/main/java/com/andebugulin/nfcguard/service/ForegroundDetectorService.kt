package com.andebugulin.nfcguard.service

import com.andebugulin.nfcguard.data.AppLogger
import com.andebugulin.nfcguard.data.AppStateRepository
import com.andebugulin.nfcguard.receiver.ScheduleAlarmReceiver
import com.andebugulin.nfcguard.sync.StateSyncer

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
        AppLogger.init(this)
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // No package filter — we need to see all app switches
            notificationTimeout = 100
            // We do NOT need window content
            flags = 0
        }
        serviceInfo = info
        instance = this
        isRunning = true
        android.util.Log.d("FG_DETECTOR", "ForegroundDetectorService connected")
        AppLogger.log("SERVICE", "ForegroundDetectorService connected")

        // Issue #10: a force-stop puts the app in stopped state — pending
        // alarms are cancelled and implicit broadcasts are withheld, so
        // neither the 15-minute watchdog nor BootReceiver can bring blocking
        // back. An enabled AccessibilityService is the one component the
        // system rebinds on its own, which makes this the only dependable
        // recovery hook. Restore enforcement from persisted state.
        try {
            ScheduleAlarmReceiver.catchUpMissedStarts(this)
            val state = AppStateRepository.getInstance(this).current
            StateSyncer.sync(this, state)
            ScheduleAlarmReceiver.scheduleWatchdog(this)
            AppLogger.log(
                "SERVICE",
                "Blocking restored on accessibility connect " +
                    "(${state.activeModes.size} active modes)"
            )
        } catch (e: Exception) {
            AppLogger.log("SERVICE", "Restore on accessibility connect failed: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Ignore system UI overlays (keyboard, status bar, etc.)
            if (pkg == "com.android.systemui") return
            // NOTE: We intentionally do NOT filter our own package here.
            // BlockerService.checkCurrentApp() already has `currentApp == packageName → ALLOW`.
            // Filtering here would cause lastDetectedPackage to go stale when the user
            // is inside nfcGuard, eventually falling through to UsageStatsManager which
            // can return a previously-blocked app → spurious force-close.
            // The old overlay concern (overlay window triggering TYPE_WINDOW_STATE_CHANGED)
            // is moot: when accessibility is ON, we use force-close mode (no overlay).
            lastDetectedPackage = pkg
            lastDetectedTime = System.currentTimeMillis()
            healIfDue(lastDetectedTime)
        }
    }

    /**
     * HyperOS can kill nfcGuard and drop its alarms while leaving this
     * service bound, so [onServiceConnected] never runs again and a schedule
     * start silently waits for something else to wake the app. Every app
     * switch reaches us, which makes this the place to notice: start any
     * schedule whose start was missed, and if the blocker service is gone,
     * restore it (which also re-arms the alarms). Throttled so a busy user
     * costs one state read per [HEAL_INTERVAL_MS].
     */
    private fun healIfDue(now: Long) {
        if (now - lastHealAt < HEAL_INTERVAL_MS) return
        lastHealAt = now
        try {
            ScheduleAlarmReceiver.catchUpMissedStarts(this)
            val state = AppStateRepository.getInstance(this).current
            val hasWork = state.activeModes.isNotEmpty() || state.schedules.isNotEmpty()
            if (hasWork && !BlockerService.isRunning()) {
                AppLogger.log("SERVICE", "Blocker service missing on app switch, restoring")
                StateSyncer.sync(this, state)
            }
        } catch (e: Exception) {
            AppLogger.log("SERVICE", "Heal on app switch failed: ${e.message}")
        }
    }

    override fun onInterrupt() {
        android.util.Log.w("FG_DETECTOR", "ForegroundDetectorService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        android.util.Log.d("FG_DETECTOR", "ForegroundDetectorService destroyed")
    }

    companion object {
        private const val HEAL_INTERVAL_MS = 15_000L

        @Volatile
        private var lastHealAt = 0L

        @Volatile
        var lastDetectedPackage: String? = null
            private set

        @Volatile
        var lastDetectedTime: Long = 0L
            private set

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var instance: ForegroundDetectorService? = null

        /**
         * Use AccessibilityService's performGlobalAction to send user HOME.
         * More reliable than launching a HOME Intent from a background service,
         * especially on MIUI/HyperOS which blocks background activity starts.
         *
         * Returns true if the action was dispatched, false if service unavailable.
         */
        fun goHome(): Boolean {
            return try {
                instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false
            } catch (e: Exception) {
                android.util.Log.e("FG_DETECTOR", "goHome failed: ${e.message}")
                false
            }
        }

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
