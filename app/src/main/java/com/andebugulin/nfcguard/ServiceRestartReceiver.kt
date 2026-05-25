package com.andebugulin.nfcguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ServiceRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("SERVICE_RESTART", "═══════════════════════════════════════")
        android.util.Log.d("SERVICE_RESTART", "SERVICE RESTART RECEIVER TRIGGERED")
        android.util.Log.d("SERVICE_RESTART", "═══════════════════════════════════════")
        android.util.Log.d("SERVICE_RESTART", "⏰ Time: ${java.util.Date()}")
        android.util.Log.d("SERVICE_RESTART", "📱 Action: ${intent.action}")

        try {
            val appState = AppStateRepository.getInstance(context).current
            android.util.Log.d("SERVICE_RESTART", "✓ App state loaded; active modes: ${appState.activeModes.size}; service running: ${BlockerService.isRunning()}")

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

            android.util.Log.d("SERVICE_RESTART", "RESTARTING SERVICE via StateSyncer")
            StateSyncer.sync(context, appState)
            ScheduleAlarmReceiver.scheduleWatchdog(context)
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
