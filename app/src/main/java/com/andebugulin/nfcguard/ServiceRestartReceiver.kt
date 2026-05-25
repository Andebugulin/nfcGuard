package com.andebugulin.nfcguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ServiceRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.init(context)
        AppLogger.log("SERVICE_RESTART", "Receiver triggered: action=${intent.action}")

        try {
            val appState = AppStateRepository.getInstance(context).current
            if (appState.activeModes.isEmpty()) {
                AppLogger.log("SERVICE_RESTART", "No active modes, nothing to restart")
                return
            }
            if (BlockerService.isRunning()) {
                AppLogger.log("SERVICE_RESTART", "Service already running, skipping restart")
                return
            }
            AppLogger.log("SERVICE_RESTART", "Restarting service via StateSyncer (${appState.activeModes.size} active modes)")
            StateSyncer.sync(context, appState)
            ScheduleAlarmReceiver.scheduleWatchdog(context)
        } catch (e: Exception) {
            AppLogger.log("SERVICE_RESTART", "Restart failed: ${e.javaClass.simpleName} — ${e.message}")
        }
    }
}
