package com.andebugulin.nfcguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.Json

class BootReceiver : BroadcastReceiver() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("BOOT_RECEIVER", "═══════════════════════════════════════")
        android.util.Log.d("BOOT_RECEIVER", "BOOT RECEIVER TRIGGERED")
        android.util.Log.d("BOOT_RECEIVER", "═══════════════════════════════════════")
        android.util.Log.d("BOOT_RECEIVER", "📱 Action: ${intent.action}")
        android.util.Log.d("BOOT_RECEIVER", "⏰ Time: ${java.util.Date()}")

        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            android.util.Log.d("BOOT_RECEIVER", "───────────────────────────────────────")
            android.util.Log.d("BOOT_RECEIVER", "STEP 1: Restarting schedule monitoring")
            android.util.Log.d("BOOT_RECEIVER", "───────────────────────────────────────")
            ScheduleAlarmReceiver.scheduleAllUpcomingAlarms(context)
            android.util.Log.d("BOOT_RECEIVER", "✓ Schedule alarms configured")

            android.util.Log.d("BOOT_RECEIVER", "───────────────────────────────────────")
            android.util.Log.d("BOOT_RECEIVER", "STEP 2: Checking for active modes")
            android.util.Log.d("BOOT_RECEIVER", "───────────────────────────────────────")

            val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            val stateJson = prefs.getString("app_state", null)

            if (stateJson == null) {
                android.util.Log.w("BOOT_RECEIVER", "⚠️  No app state found in preferences")
                android.util.Log.d("BOOT_RECEIVER", "═══════════════════════════════════════")
                return
            }

            try {
                val appState = json.decodeFromString<AppState>(stateJson)
                android.util.Log.d("BOOT_RECEIVER", "✓ App state loaded successfully")
                android.util.Log.d("BOOT_RECEIVER", "📊 Total modes: ${appState.modes.size}")
                android.util.Log.d("BOOT_RECEIVER", "🎯 Active modes: ${appState.activeModes.size}")
                android.util.Log.d("BOOT_RECEIVER", "   Active mode IDs: ${appState.activeModes.joinToString(", ")}")

                if (appState.activeModes.isEmpty()) {
                    android.util.Log.d("BOOT_RECEIVER", "   No active modes to restore")
                    android.util.Log.d("BOOT_RECEIVER", "═══════════════════════════════════════")
                    return
                }

                android.util.Log.d("BOOT_RECEIVER", "───────────────────────────────────────")
                android.util.Log.d("BOOT_RECEIVER", "STEP 3: Analyzing active modes")
                android.util.Log.d("BOOT_RECEIVER", "───────────────────────────────────────")

                val activeModes = appState.modes.filter {
                    appState.activeModes.contains(it.id)
                }
                android.util.Log.d("BOOT_RECEIVER", "📋 Active mode details:")
                activeModes.forEach { mode ->
                    android.util.Log.d("BOOT_RECEIVER", "   • ${mode.name}")
                    android.util.Log.d("BOOT_RECEIVER", "     - Mode: ${mode.blockMode}")
                    android.util.Log.d("BOOT_RECEIVER", "     - Apps: ${mode.blockedApps.size}")
                }

                val hasAllowMode = activeModes.any { it.blockMode == BlockMode.ALLOW_SELECTED }
                android.util.Log.d("BOOT_RECEIVER", "🔧 Has ALLOW mode: $hasAllowMode")

                android.util.Log.d("BOOT_RECEIVER", "───────────────────────────────────────")
                android.util.Log.d("BOOT_RECEIVER", "STEP 4: Starting BlockerService")
                android.util.Log.d("BOOT_RECEIVER", "───────────────────────────────────────")

                if (hasAllowMode) {
                    val allAllowedApps = mutableSetOf<String>()
                    activeModes.forEach { mode ->
                        if (mode.blockMode == BlockMode.ALLOW_SELECTED) {
                            android.util.Log.d("BOOT_RECEIVER", "   Adding allowed apps from: ${mode.name}")
                            allAllowedApps.addAll(mode.blockedApps)
                        }
                    }
                    android.util.Log.d("BOOT_RECEIVER", "✓ Total allowed apps: ${allAllowedApps.size}")
                    android.util.Log.d("BOOT_RECEIVER", "   Apps: ${allAllowedApps.joinToString(", ")}")

                    BlockerService.start(context, allAllowedApps, BlockMode.ALLOW_SELECTED, appState.activeModes)
                } else {
                    val allBlockedApps = mutableSetOf<String>()
                    activeModes.forEach { mode ->
                        android.util.Log.d("BOOT_RECEIVER", "   Adding blocked apps from: ${mode.name}")
                        allBlockedApps.addAll(mode.blockedApps)
                    }
                    android.util.Log.d("BOOT_RECEIVER", "✓ Total blocked apps: ${allBlockedApps.size}")
                    android.util.Log.d("BOOT_RECEIVER", "   Apps: ${allBlockedApps.joinToString(", ")}")

                    BlockerService.start(context, allBlockedApps, BlockMode.BLOCK_SELECTED, appState.activeModes)
                }

                android.util.Log.d("BOOT_RECEIVER", "✓ BlockerService started successfully")
            } catch (e: Exception) {
                android.util.Log.e("BOOT_RECEIVER", "❌❌❌ ERROR PROCESSING BOOT ❌❌❌")
                android.util.Log.e("BOOT_RECEIVER", "Error type: ${e.javaClass.simpleName}")
                android.util.Log.e("BOOT_RECEIVER", "Error message: ${e.message}")
                android.util.Log.e("BOOT_RECEIVER", "Stack trace:", e)
            }
        } else {
            android.util.Log.w("BOOT_RECEIVER", "⚠️  Unhandled action: ${intent.action}")
        }

        android.util.Log.d("BOOT_RECEIVER", "═══════════════════════════════════════")
    }
}