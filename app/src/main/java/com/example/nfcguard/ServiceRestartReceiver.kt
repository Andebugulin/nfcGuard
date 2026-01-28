package com.example.nfcguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.Json

class ServiceRestartReceiver : BroadcastReceiver() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("SERVICE_RESTART", "Attempting to restart BlockerService")

        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val stateJson = prefs.getString("app_state", null)

        if (stateJson != null) {
            try {
                val appState = json.decodeFromString<AppState>(stateJson)

                if (appState.activeModes.isNotEmpty() && !BlockerService.isRunning()) {
                    android.util.Log.d("SERVICE_RESTART", "Restarting service with ${appState.activeModes.size} active modes")

                    val activeModes = appState.modes.filter { appState.activeModes.contains(it.id) }
                    val hasAllowMode = activeModes.any { it.blockMode == BlockMode.ALLOW_SELECTED }

                    val appsToBlock = if (hasAllowMode) {
                        val allAllowedApps = mutableSetOf<String>()
                        activeModes.forEach { mode ->
                            if (mode.blockMode == BlockMode.ALLOW_SELECTED) {
                                allAllowedApps.addAll(mode.blockedApps)
                            }
                        }
                        allAllowedApps
                    } else {
                        val allBlockedApps = mutableSetOf<String>()
                        activeModes.forEach { mode ->
                            allBlockedApps.addAll(mode.blockedApps)
                        }
                        allBlockedApps
                    }

                    BlockerService.start(
                        context,
                        appsToBlock,
                        if (hasAllowMode) BlockMode.ALLOW_SELECTED else BlockMode.BLOCK_SELECTED,
                        appState.activeModes
                    )
                } else {
                    android.util.Log.d("SERVICE_RESTART", "Service already running or no active modes")
                }
            } catch (e: Exception) {
                android.util.Log.e("SERVICE_RESTART", "Error: ${e.message}", e)
            }
        }
    }
}