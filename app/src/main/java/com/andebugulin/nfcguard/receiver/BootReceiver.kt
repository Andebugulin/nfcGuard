package com.andebugulin.nfcguard.receiver

import com.andebugulin.nfcguard.data.AppLogger
import com.andebugulin.nfcguard.data.AppStateRepository
import com.andebugulin.nfcguard.sync.StateSyncer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Init logger (may already be initialized, that's fine)
        AppLogger.init(context)
        AppLogger.log("BOOT", "BootReceiver triggered: action=${intent.action}")

        android.util.Log.d("BOOT_RECEIVER", "BOOT RECEIVER TRIGGERED")
        android.util.Log.d("BOOT_RECEIVER", "Action: ${intent.action}")
        android.util.Log.d("BOOT_RECEIVER", "Time: ${java.util.Date()}")

        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                val appState = AppStateRepository.getInstance(context).current
                AppLogger.log("BOOT", "State loaded: ${appState.modes.size} modes, ${appState.activeModes.size} active, ${appState.schedules.size} schedules")
                // One call restores everything: service start (or empty-keep-alive
                // for schedule monitoring), schedule alarms, widget refresh.
                StateSyncer.sync(context, appState)
                ScheduleAlarmReceiver.scheduleWatchdog(context)
                AppLogger.log("BOOT", "Boot restoration complete")
            } catch (e: Exception) {
                AppLogger.log("BOOT", "ERROR: ${e.javaClass.simpleName} - ${e.message}")
                android.util.Log.e("BOOT_RECEIVER", "Error processing boot", e)
            }
        } else {
            AppLogger.log("BOOT", "Unhandled action: ${intent.action}")
        }
    }
}
