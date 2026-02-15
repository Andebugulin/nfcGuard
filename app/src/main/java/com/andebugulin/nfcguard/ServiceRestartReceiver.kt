package com.andebugulin.nfcguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.Json

class ServiceRestartReceiver : BroadcastReceiver() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("SERVICE_RESTART", "═══════════════════════════════════════")
        android.util.Log.d("SERVICE_RESTART", "SERVICE RESTART RECEIVER TRIGGERED")
        android.util.Log.d("SERVICE_RESTART", "═══════════════════════════════════════")
        android.util.Log.d("SERVICE_RESTART", "⏰ Time: ${java.util.Date()}")
        android.util.Log.d("SERVICE_RESTART", "📱 Action: ${intent.action}")

        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val stateJson = prefs.getString("app_state", null)

        if (stateJson == null) {
            android.util.Log.w("SERVICE_RESTART", "⚠️  No app state found")
            android.util.Log.d("SERVICE_RESTART", "   Cannot restart service without state")
            android.util.Log.d("SERVICE_RESTART", "═══════════════════════════════════════")
            return
        }

        try {
            val appState = json.decodeFromString<AppState>(stateJson)
            android.util.Log.d("SERVICE_RESTART", "✓ App state loaded")
            android.util.Log.d("SERVICE_RESTART", "🎯 Active modes: ${appState.activeModes.size}")
            android.util.Log.d("SERVICE_RESTART", "   IDs: ${appState.activeModes.joinToString(", ")}")
            android.util.Log.d("SERVICE_RESTART", "🔧 Service running: ${BlockerService.isRunning()}")

            if (appState.activeModes.isEmpty()) {
                android.util.Log.d("SERVICE_RESTART", "   No active modes, nothing to restart")
                android.util.Log.d("SERVICE_RESTART", "═══════════════════════════════════════")
                return
            }

            if (BlockerService.isRunning()) {
                android.util.Log.d("SERVICE_RESTART", "   Service already running, skipping restart")
                android.util.Log.d("SERVICE_RESTART", "═══════════════════════════════════════")
                return
            }

            android.util.Log.d("SERVICE_RESTART", "───────────────────────────────────────")
            android.util.Log.d("SERVICE_RESTART", "RESTARTING SERVICE")
            android.util.Log.d("SERVICE_RESTART", "───────────────────────────────────────")
            android.util.Log.d("SERVICE_RESTART", "📋 Active modes to restore: ${appState.activeModes.size}")

            val activeModes = appState.modes.filter { appState.activeModes.contains(it.id) }

            android.util.Log.d("SERVICE_RESTART", "📊 Mode details:")
            activeModes.forEach { mode ->
                android.util.Log.d("SERVICE_RESTART", "   • ${mode.name}")
                android.util.Log.d("SERVICE_RESTART", "     - Block mode: ${mode.blockMode}")
                android.util.Log.d("SERVICE_RESTART", "     - Apps count: ${mode.blockedApps.size}")
            }

            val hasAllowMode = activeModes.any { it.blockMode == BlockMode.ALLOW_SELECTED }
            android.util.Log.d("SERVICE_RESTART", "🔧 Has ALLOW mode: $hasAllowMode")

            val appsToBlock = if (hasAllowMode) {
                val allAllowedApps = mutableSetOf<String>()
                activeModes.forEach { mode ->
                    if (mode.blockMode == BlockMode.ALLOW_SELECTED) {
                        android.util.Log.d("SERVICE_RESTART", "   Collecting from ALLOW mode: ${mode.name}")
                        allAllowedApps.addAll(mode.blockedApps)
                    }
                }
                android.util.Log.d("SERVICE_RESTART", "✓ Total allowed apps: ${allAllowedApps.size}")
                allAllowedApps
            } else {
                val allBlockedApps = mutableSetOf<String>()
                activeModes.forEach { mode ->
                    android.util.Log.d("SERVICE_RESTART", "   Collecting from BLOCK mode: ${mode.name}")
                    allBlockedApps.addAll(mode.blockedApps)
                }
                android.util.Log.d("SERVICE_RESTART", "✓ Total blocked apps: ${allBlockedApps.size}")
                allBlockedApps
            }

            BlockerService.start(
                context,
                appsToBlock,
                if (hasAllowMode) BlockMode.ALLOW_SELECTED else BlockMode.BLOCK_SELECTED,
                appState.activeModes
            )

            android.util.Log.d("SERVICE_RESTART", "✓✓✓ SERVICE RESTART COMPLETE ✓✓✓")
        } catch (e: Exception) {
            android.util.Log.e("SERVICE_RESTART", "❌❌❌ RESTART FAILED ❌❌❌")
            android.util.Log.e("SERVICE_RESTART", "Error type: ${e.javaClass.simpleName}")
            android.util.Log.e("SERVICE_RESTART", "Error message: ${e.message}")
            android.util.Log.e("SERVICE_RESTART", "Stack trace:", e)
        }

        android.util.Log.d("SERVICE_RESTART", "═══════════════════════════════════════")
    }
}