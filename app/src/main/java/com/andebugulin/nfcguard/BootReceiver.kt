package com.andebugulin.nfcguard

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

            AppLogger.log("BOOT", "Step 1: Rescheduling alarms")
            ScheduleAlarmReceiver.scheduleAllUpcomingAlarms(context)
            AppLogger.log("BOOT", "Step 1 done: Alarms rescheduled")

            try {
                val appState = AppStateRepository.getInstance(context).current
                AppLogger.log("BOOT", "State loaded: ${appState.modes.size} modes, ${appState.activeModes.size} active, ${appState.schedules.size} schedules")

                if (appState.activeModes.isEmpty()) {
                    AppLogger.log("BOOT", "No active modes to restore")
                    return
                }

                val activeModes = appState.modes.filter {
                    appState.activeModes.contains(it.id)
                }

                val hasAllowMode = activeModes.any { it.blockMode == BlockMode.ALLOW_SELECTED }

                if (hasAllowMode) {
                    val allAllowedApps = mutableSetOf<String>()
                    activeModes.forEach { mode ->
                        if (mode.blockMode == BlockMode.ALLOW_SELECTED) {
                            allAllowedApps.addAll(mode.blockedApps)
                        }
                    }
                    AppLogger.log("BOOT", "Restoring ALLOW_SELECTED: ${allAllowedApps.size} apps, modes=${appState.activeModes}")
                    BlockerService.start(
                        context,
                        allAllowedApps,
                        BlockMode.ALLOW_SELECTED,
                        appState.activeModes,
                        appState.manuallyActivatedModes,
                        appState.timedModeDeactivations,
                        timedModeReactivations = appState.timedModeReactivations
                    )
                } else {
                    val allBlockedApps = mutableSetOf<String>()
                    activeModes.forEach { mode ->
                        allBlockedApps.addAll(mode.blockedApps)
                    }
                    AppLogger.log("BOOT", "Restoring BLOCK_SELECTED: ${allBlockedApps.size} apps, modes=${appState.activeModes}")
                    BlockerService.start(
                        context,
                        allBlockedApps,
                        BlockMode.BLOCK_SELECTED,
                        appState.activeModes,
                        appState.manuallyActivatedModes,
                        appState.timedModeDeactivations,
                        timedModeReactivations = appState.timedModeReactivations
                    )
                }

                AppLogger.log("BOOT", "BlockerService started after boot")
                ScheduleAlarmReceiver.scheduleWatchdog(context)
            } catch (e: Exception) {
                AppLogger.log("BOOT", "ERROR: ${e.javaClass.simpleName} - ${e.message}")
                android.util.Log.e("BOOT_RECEIVER", "Error processing boot", e)
            }
        } else {
            AppLogger.log("BOOT", "Unhandled action: ${intent.action}")
        }
    }
}