package com.andebugulin.nfcguard

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Resolves the package name of the foreground app.
 *
 * Three strategies, tried in order:
 *
 *  1. **AccessibilityService event** (primary, if enabled).
 *     [ForegroundDetectorService] receives `TYPE_WINDOW_STATE_CHANGED`
 *     events in real time. This is the only reliable source on Pixel
 *     and some Samsung devices where `UsageStatsManager` misreports on
 *     recents → app transitions. Trusted only if the last event was
 *     within 5 seconds.
 *
 *  2. **`UsageStatsManager.queryEvents`** (fallback).
 *     Walk the last 60 seconds of `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED`
 *     events and pick the most recent resume that wasn't subsequently
 *     paused (or where the resume happened *after* the pause — see the
 *     timestamp comparison in [resolveFromUsageEvents], it's load-bearing).
 *
 *  3. **`UsageStatsManager.queryUsageStats`** (last resort).
 *     The most-recently-used package across the last 5 minutes.
 *
 * Returns `null` only if no source produced an answer.
 */
class ForegroundAppDetector(context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    fun current(): String? {
        accessibilitySource()?.let { return it }
        resolveFromUsageEvents()?.let { return it }
        return resolveFromUsageStatsFallback()
    }

    private fun accessibilitySource(): String? {
        if (!ForegroundDetectorService.isRunning) return null
        val pkg = ForegroundDetectorService.lastDetectedPackage ?: return null
        val age = System.currentTimeMillis() - ForegroundDetectorService.lastDetectedTime
        if (age >= ACCESSIBILITY_FRESHNESS_MS) return null
        android.util.Log.v(TAG, "Accessibility source: $pkg (${age}ms ago)")
        return pkg
    }

    private fun resolveFromUsageEvents(): String? {
        val now = System.currentTimeMillis()
        return try {
            val events = usageStatsManager.queryEvents(now - USAGE_EVENTS_WINDOW_MS, now)

            var lastResumedApp: String? = null
            var lastResumedTime = 0L
            var lastPausedApp: String? = null
            var lastPausedTime = 0L
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (event.timeStamp >= lastResumedTime) {
                            lastResumedApp = event.packageName
                            lastResumedTime = event.timeStamp
                        }
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        if (event.timeStamp >= lastPausedTime) {
                            lastPausedApp = event.packageName
                            lastPausedTime = event.timeStamp
                        }
                    }
                }
            }

            // Load-bearing: the obvious check `lastResumedApp != lastPausedApp`
            // returns null when the same app is BOTH the last-resumed and
            // last-paused (e.g. Chrome paused by our overlay, then no new
            // resume event followed). Compare timestamps instead — if the
            // resume happened after the pause, the app is still foreground.
            if (lastResumedApp != null) {
                val stillForeground = lastResumedApp != lastPausedApp ||
                    lastResumedTime >= lastPausedTime
                if (stillForeground) return lastResumedApp
            }
            null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "queryEvents failed: ${e.message}")
            null
        }
    }

    private fun resolveFromUsageStatsFallback(): String? {
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - USAGE_STATS_LOOKBACK_MS,
            now
        )
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    private companion object {
        const val TAG = "FG_DETECTOR"
        const val ACCESSIBILITY_FRESHNESS_MS = 5_000L
        const val USAGE_EVENTS_WINDOW_MS = 60_000L
        const val USAGE_STATS_LOOKBACK_MS = 5L * 60 * 1000
    }
}
