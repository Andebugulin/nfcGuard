package com.example.nfcguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.Json

class BootReceiver : BroadcastReceiver() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            // Restart schedule monitoring with AlarmManager
            ScheduleAlarmReceiver.scheduleAllUpcomingAlarms(context)

            // Check if any modes should be active and restart BlockerService
            val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            val stateJson = prefs.getString("app_state", null)

            if (stateJson != null) {
                try {
                    val appState = json.decodeFromString<AppState>(stateJson)

                    if (appState.activeModes.isNotEmpty()) {
                        val activeModes = appState.modes.filter {
                            appState.activeModes.contains(it.id)
                        }

                        // Check if ANY mode is ALLOW_SELECTED
                        val hasAllowMode = activeModes.any { it.blockMode == BlockMode.ALLOW_SELECTED }

                        if (hasAllowMode) {
                            // Collect ALL allowed apps
                            val allAllowedApps = mutableSetOf<String>()
                            activeModes.forEach { mode ->
                                if (mode.blockMode == BlockMode.ALLOW_SELECTED) {
                                    allAllowedApps.addAll(mode.blockedApps)
                                }
                            }
                            BlockerService.start(context, allAllowedApps, BlockMode.ALLOW_SELECTED, appState.activeModes)
                        } else {
                            // BLOCK_SELECTED mode: Collect all blocked apps
                            val allBlockedApps = mutableSetOf<String>()
                            activeModes.forEach { mode ->
                                allBlockedApps.addAll(mode.blockedApps)
                            }
                            BlockerService.start(context, allBlockedApps, BlockMode.BLOCK_SELECTED, appState.activeModes)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BOOT_RECEIVER", "Error: ${e.message}", e)

                }
            }
        }
    }
}