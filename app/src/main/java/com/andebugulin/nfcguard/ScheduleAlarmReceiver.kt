package com.andebugulin.nfcguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.Calendar

class ScheduleAlarmReceiver : BroadcastReceiver() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("SCHEDULE_ALARM", "=== ALARM RECEIVED ===")
        android.util.Log.d("SCHEDULE_ALARM", "Action: ${intent.action}")
        android.util.Log.d("SCHEDULE_ALARM", "Time: ${java.util.Date()}")

        when (intent.action) {
            ACTION_ACTIVATE_SCHEDULE -> {
                val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
                val day = intent.getIntExtra(EXTRA_DAY, -1)
                android.util.Log.d("SCHEDULE_ALARM", "ðŸ”¥ ACTIVATE alarm fired")
                android.util.Log.d("SCHEDULE_ALARM", "Schedule ID: $scheduleId, Day: $day")
                if (day != -1) {
                    activateSpecificSchedule(context, scheduleId, day)
                    // Reschedule THIS SPECIFIC alarm for next week
                    scheduleAlarmForSchedule(context, scheduleId, day, isStart = true, forNextWeek = true)
                }
            }
            ACTION_DEACTIVATE_SCHEDULE -> {
                val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
                val day = intent.getIntExtra(EXTRA_DAY, -1)
                android.util.Log.d("SCHEDULE_ALARM", "ðŸ”¥ DEACTIVATE alarm fired")
                android.util.Log.d("SCHEDULE_ALARM", "Schedule ID: $scheduleId, Day: $day")
                if (day != -1) {
                    deactivateSpecificSchedule(context, scheduleId)
                    // Reschedule THIS SPECIFIC alarm for next week
                    scheduleAlarmForSchedule(context, scheduleId, day, isStart = false, forNextWeek = true)
                }
            }
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                android.util.Log.d("SCHEDULE_ALARM", "Device booted/updated, rescheduling alarms")
                scheduleAllUpcomingAlarms(context)
            }
        }
    }

    private fun activateSpecificSchedule(context: Context, scheduleId: String, day: Int) {
        android.util.Log.d("SCHEDULE_ALARM", ">>> Activating schedule $scheduleId for day $day")
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val stateJson = prefs.getString("app_state", null)

        if (stateJson == null) {
            android.util.Log.e("SCHEDULE_ALARM", "No app state found!")
            return
        }

        try {
            val appState = json.decodeFromString<AppState>(stateJson)
            val schedule = appState.schedules.find { it.id == scheduleId }

            if (schedule == null) {
                android.util.Log.e("SCHEDULE_ALARM", "Schedule not found: $scheduleId")
                return
            }

            android.util.Log.d("SCHEDULE_ALARM", "Found schedule: ${schedule.name}")
            android.util.Log.d("SCHEDULE_ALARM", "Linked modes: ${schedule.linkedModeIds}")

            val newActiveModes = appState.activeModes + schedule.linkedModeIds
            val newActiveSchedules = appState.activeSchedules + scheduleId
            val newDeactivatedSchedules = appState.deactivatedSchedules - scheduleId

            val newState = appState.copy(
                activeModes = newActiveModes,
                activeSchedules = newActiveSchedules,
                deactivatedSchedules = newDeactivatedSchedules
            )
            val newStateJson = json.encodeToString(newState)
            prefs.edit().putString("app_state", newStateJson).apply()

            android.util.Log.d("SCHEDULE_ALARM", "✓ Active modes updated to: $newActiveModes")

            android.util.Log.d("SCHEDULE_ALARM", "✓ Active schedules: $newActiveSchedules")
            updateBlockerService(context, newState)
        } catch (e: Exception) {
            android.util.Log.e("SCHEDULE_ALARM", "Error activating schedule: ${e.message}", e)
        }
    }

    private fun deactivateSpecificSchedule(context: Context, scheduleId: String) {
        android.util.Log.d("SCHEDULE_ALARM", ">>> Deactivating schedule $scheduleId")
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val stateJson = prefs.getString("app_state", null)

        if (stateJson == null) {
            android.util.Log.e("SCHEDULE_ALARM", "No app state found!")
            return
        }

        try {
            val appState = json.decodeFromString<AppState>(stateJson)
            val schedule = appState.schedules.find { it.id == scheduleId }

            if (schedule == null) {
                android.util.Log.e("SCHEDULE_ALARM", "Schedule not found: $scheduleId")
                return
            }

            android.util.Log.d("SCHEDULE_ALARM", "Deactivating modes: ${schedule.linkedModeIds}")

            val newActiveModes = appState.activeModes - schedule.linkedModeIds.toSet()
            val newActiveSchedules = appState.activeSchedules - scheduleId
            val newDeactivatedSchedules = appState.deactivatedSchedules - scheduleId

            val newState = appState.copy(
                activeModes = newActiveModes,
                activeSchedules = newActiveSchedules,
                deactivatedSchedules = newDeactivatedSchedules
            )
            val newStateJson = json.encodeToString(newState)
            prefs.edit().putString("app_state", newStateJson).apply()

            android.util.Log.d("SCHEDULE_ALARM", "✓ Active modes updated to: $newActiveModes")

            updateBlockerService(context, newState)
        } catch (e: Exception) {
            android.util.Log.e("SCHEDULE_ALARM", "Error deactivating schedule: ${e.message}", e)
        }
    }

    private fun updateBlockerService(context: Context, appState: AppState) {
        android.util.Log.d("SCHEDULE_ALARM", ">>> Updating BlockerService")
        android.util.Log.d("SCHEDULE_ALARM", "Active modes: ${appState.activeModes}")

        val activeModes = appState.modes.filter {
            appState.activeModes.contains(it.id)
        }

        if (activeModes.isNotEmpty()) {
            val hasAllowMode = activeModes.any { it.blockMode == BlockMode.ALLOW_SELECTED }

            if (hasAllowMode) {
                val allAllowedApps = mutableSetOf<String>()
                activeModes.forEach { mode ->
                    if (mode.blockMode == BlockMode.ALLOW_SELECTED) {
                        allAllowedApps.addAll(mode.blockedApps)
                    }
                }
                android.util.Log.d("SCHEDULE_ALARM", "Starting service in ALLOW mode")
                BlockerService.start(context, allAllowedApps, BlockMode.ALLOW_SELECTED, activeModes.map { it.id }.toSet())
            } else {
                val allBlockedApps = mutableSetOf<String>()
                activeModes.forEach { mode ->
                    allBlockedApps.addAll(mode.blockedApps)
                }
                android.util.Log.d("SCHEDULE_ALARM", "Starting service in BLOCK mode")
                BlockerService.start(context, allBlockedApps, BlockMode.BLOCK_SELECTED, activeModes.map { it.id }.toSet())
            }
        } else {
            android.util.Log.d("SCHEDULE_ALARM", "Stopping service (no active modes)")
            BlockerService.stop(context)
        }
    }

    companion object {
        private const val ACTION_ACTIVATE_SCHEDULE = "com.andebugulin.nfcguard.ACTIVATE_SCHEDULE"
        private const val ACTION_DEACTIVATE_SCHEDULE = "com.andebugulin.nfcguard.DEACTIVATE_SCHEDULE"
        private const val EXTRA_SCHEDULE_ID = "schedule_id"
        private const val EXTRA_DAY = "day"

        private fun scheduleAlarmForSchedule(
            context: Context,
            scheduleId: String,
            day: Int,
            isStart: Boolean,
            forNextWeek: Boolean = false
        ) {
            val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            val stateJson = prefs.getString("app_state", null) ?: return
            val json = Json { ignoreUnknownKeys = true }

            try {
                val appState = json.decodeFromString<AppState>(stateJson)
                val schedule = appState.schedules.find { it.id == scheduleId } ?: return
                val dayTime = schedule.timeSlot.getTimeForDay(day) ?: return

                val calendarDay = when (day) {
                    1 -> Calendar.MONDAY
                    2 -> Calendar.TUESDAY
                    3 -> Calendar.WEDNESDAY
                    4 -> Calendar.THURSDAY
                    5 -> Calendar.FRIDAY
                    6 -> Calendar.SATURDAY
                    7 -> Calendar.SUNDAY
                    else -> return
                }

                val calendar = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_WEEK, calendarDay)
                    set(Calendar.HOUR_OF_DAY, if (isStart) dayTime.startHour else dayTime.endHour)
                    set(Calendar.MINUTE, if (isStart) dayTime.startMinute else dayTime.endMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)

                    if (timeInMillis <= System.currentTimeMillis() || forNextWeek) {
                        add(Calendar.WEEK_OF_YEAR, 1)
                    }
                }

                val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                    action = if (isStart) ACTION_ACTIVATE_SCHEDULE else ACTION_DEACTIVATE_SCHEDULE
                    putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                    putExtra(EXTRA_DAY, day)
                }

                val requestCode = (scheduleId.hashCode() + day + if (isStart) 0 else 10000)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                }

                val timeStr = String.format("%02d:%02d",
                    if (isStart) dayTime.startHour else dayTime.endHour,
                    if (isStart) dayTime.startMinute else dayTime.endMinute
                )
                android.util.Log.d("SCHEDULE_ALARM", "✓ Scheduled ${if (isStart) "START" else "END"} for ${getDayName(day)} $timeStr")
                android.util.Log.d("SCHEDULE_ALARM", "   Will fire at: ${java.util.Date(calendar.timeInMillis)}")
            } catch (e: Exception) {
                android.util.Log.e("SCHEDULE_ALARM", "Error scheduling alarm: ${e.message}", e)
            }
        }

        fun scheduleAllUpcomingAlarms(context: Context) {
            android.util.Log.d("SCHEDULE_ALARM", "=== SCHEDULING ALL ALARMS ===")
            android.util.Log.d("SCHEDULE_ALARM", "Current time: ${java.util.Date()}")

            val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            val stateJson = prefs.getString("app_state", null)

            if (stateJson == null) {
                android.util.Log.e("SCHEDULE_ALARM", "No app state found!")
                return
            }

            val json = Json { ignoreUnknownKeys = true }

            try {
                val appState = json.decodeFromString<AppState>(stateJson)

                android.util.Log.d("SCHEDULE_ALARM", "Found ${appState.schedules.size} schedules")

                // Cancel all existing alarms first
                cancelAllAlarms(context, appState)

                // Schedule new alarms for each schedule
                for (schedule in appState.schedules) {
                    android.util.Log.d("SCHEDULE_ALARM", "Scheduling alarms for: ${schedule.name}")

                    for (dayTime in schedule.timeSlot.dayTimes) {
                        // Schedule START alarm
                        scheduleAlarmForSchedule(context, schedule.id, dayTime.day, isStart = true)

                        // Schedule END alarm if hasEndTime
                        if (schedule.hasEndTime) {
                            scheduleAlarmForSchedule(context, schedule.id, dayTime.day, isStart = false)
                        }
                    }
                }

                android.util.Log.d("SCHEDULE_ALARM", "=== ALL ALARMS SCHEDULED ===")
            } catch (e: Exception) {
                android.util.Log.e("SCHEDULE_ALARM", "Error scheduling alarms: ${e.message}", e)
            }
        }

        fun cancelAllAlarms(context: Context, appState: AppState) {
            android.util.Log.d("SCHEDULE_ALARM", "Cancelling all existing alarms...")
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            for (schedule in appState.schedules) {
                for (dayTime in schedule.timeSlot.dayTimes) {
                    // Cancel START alarm
                    val startRequestCode = (schedule.id.hashCode() + dayTime.day)
                    val startIntent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                        action = ACTION_ACTIVATE_SCHEDULE
                        putExtra(EXTRA_SCHEDULE_ID, schedule.id)
                        putExtra(EXTRA_DAY, dayTime.day)
                    }
                    val startPendingIntent = PendingIntent.getBroadcast(
                        context,
                        startRequestCode,
                        startIntent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    startPendingIntent?.let { alarmManager.cancel(it) }

                    // Cancel END alarm
                    val endRequestCode = (schedule.id.hashCode() + dayTime.day + 10000)
                    val endIntent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                        action = ACTION_DEACTIVATE_SCHEDULE
                        putExtra(EXTRA_SCHEDULE_ID, schedule.id)
                        putExtra(EXTRA_DAY, dayTime.day)
                    }
                    val endPendingIntent = PendingIntent.getBroadcast(
                        context,
                        endRequestCode,
                        endIntent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    endPendingIntent?.let { alarmManager.cancel(it) }
                }
            }
        }

        private fun getDayName(day: Int): String = when (day) {
            1 -> "MON"
            2 -> "TUE"
            3 -> "WED"
            4 -> "THU"
            5 -> "FRI"
            6 -> "SAT"
            7 -> "SUN"
            else -> "???"
        }
    }
}