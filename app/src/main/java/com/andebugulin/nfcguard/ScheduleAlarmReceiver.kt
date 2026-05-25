package com.andebugulin.nfcguard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.runBlocking
import java.util.Calendar

class ScheduleAlarmReceiver : BroadcastReceiver() {

    private fun ensureServiceRunning(context: Context) {
        if (BlockerService.isRunning()) {
            AppLogger.log("ALARM", "Watchdog: service alive ✓")
            return
        }

        val appState = AppStateRepository.getInstance(context).current
        if (appState.activeModes.isEmpty()) {
            AppLogger.log("ALARM", "Watchdog: no active modes, skip")
            return
        }

        AppLogger.log("ALARM", "Watchdog: SERVICE DEAD — restarting with ${appState.activeModes.size} active modes")
        StateSyncer.sync(context, appState)
    }

    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.init(context)  // Ensure logger is ready (receivers run independently)
        android.util.Log.d("SCHEDULE_ALARM", "=== ALARM RECEIVED ===")
        AppLogger.log("ALARM", "Alarm received: action=${intent.action} at ${java.util.Date()}")
        android.util.Log.d("SCHEDULE_ALARM", "Action: ${intent.action}")
        android.util.Log.d("SCHEDULE_ALARM", "Time: ${java.util.Date()}")

        when (intent.action) {
            ACTION_CHECK_SCHEDULE -> {
                AppLogger.log("ALARM", "Watchdog CHECK fired")
                ensureServiceRunning(context)
                // Self-chain: schedule the next watchdog
                scheduleWatchdog(context)
            }
            ACTION_ACTIVATE_SCHEDULE -> {
                val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
                val day = intent.getIntExtra(EXTRA_DAY, -1)
                android.util.Log.d("SCHEDULE_ALARM", "- ACTIVATE alarm fired")
                android.util.Log.d("SCHEDULE_ALARM", "Schedule ID: $scheduleId, Day: $day")
                if (day != -1) {
                    activateSpecificSchedule(context, scheduleId, day)
                    scheduleAlarmForSchedule(context, scheduleId, day, isStart = true, forNextWeek = true)
                }
            }
            ACTION_DEACTIVATE_SCHEDULE -> {
                val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
                val day = intent.getIntExtra(EXTRA_DAY, -1)
                android.util.Log.d("SCHEDULE_ALARM", "- DEACTIVATE alarm fired")
                android.util.Log.d("SCHEDULE_ALARM", "Schedule ID: $scheduleId, Day: $day")
                if (day != -1) {
                    deactivateSpecificSchedule(context, scheduleId)
                    scheduleAlarmForSchedule(context, scheduleId, day, isStart = false, forNextWeek = true)
                }
            }
            ACTION_TIMED_DEACTIVATE_MODE -> {
                val modeId = intent.getStringExtra("mode_id") ?: return
                android.util.Log.d("SCHEDULE_ALARM", "- TIMED DEACTIVATE alarm fired for mode $modeId")
                AppLogger.log("ALARM", "Timed deactivation alarm for mode $modeId")
                deactivateTimedMode(context, modeId)
            }
            ACTION_TIMED_REACTIVATE_MODE -> {
                val modeId = intent.getStringExtra("mode_id") ?: return
                android.util.Log.d("SCHEDULE_ALARM", "- TIMED REACTIVATE alarm fired for mode $modeId")
                AppLogger.log("ALARM", "Timed reactivation alarm for mode $modeId")
                reactivateTimedMode(context, modeId)
            }
        }
    }

    private fun activateSpecificSchedule(context: Context, scheduleId: String, day: Int) {
        android.util.Log.d("SCHEDULE_ALARM", ">>> Activating schedule $scheduleId for day $day")
        AppLogger.log("ALARM", "Activating schedule $scheduleId for day $day")
        val repo = AppStateRepository.getInstance(context)

        try {
            val result = runBlocking {
                repo.updateWith { state ->
                    val r = ScheduleTransitions.applyScheduleActivation(state, scheduleId)
                    r.newState to r
                }
            }
            when (result) {
                is ScheduleTransitions.ScheduleActivationResult.ScheduleNotFound -> {
                    android.util.Log.e("SCHEDULE_ALARM", "Schedule not found: $scheduleId")
                    AppLogger.log("ALARM", "ERROR: Schedule not found: $scheduleId")
                }
                is ScheduleTransitions.ScheduleActivationResult.Applied -> {
                    result.conflictSkippedModeIds.forEach { skipped ->
                        val name = result.newState.modes.find { it.id == skipped }?.name ?: skipped
                        android.util.Log.w("SCHEDULE_ALARM", "Skipping mode $name: BLOCK/ALLOW conflict with active modes")
                        AppLogger.log("ALARM", "CONFLICT: Skipping mode $name — BLOCK/ALLOW conflict")
                    }
                    AppLogger.log("ALARM", "Schedule activated: activeModes=${result.newState.activeModes}, activeSchedules=${result.newState.activeSchedules}")
                    android.util.Log.d("SCHEDULE_ALARM", "- Active modes updated to: ${result.newState.activeModes}")
                    android.util.Log.d("SCHEDULE_ALARM", "- Active schedules: ${result.newState.activeSchedules}")
                    // Service restart, alarm reschedule, and widget refresh
                    // are dispatched by AppStateRepository via StateSyncer.
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SCHEDULE_ALARM", "Error activating schedule: ${e.message}", e)
        }
    }

    private fun deactivateSpecificSchedule(context: Context, scheduleId: String) {
        android.util.Log.d("SCHEDULE_ALARM", ">>> Deactivating schedule $scheduleId")
        AppLogger.log("ALARM", "Deactivating schedule $scheduleId")
        val repo = AppStateRepository.getInstance(context)

        try {
            val result = runBlocking {
                repo.updateWith { state ->
                    val r = ScheduleTransitions.applyScheduleDeactivation(state, scheduleId)
                    r.newState to r
                }
            }
            when (result) {
                is ScheduleTransitions.ScheduleDeactivationResult.ScheduleNotFound -> {
                    android.util.Log.e("SCHEDULE_ALARM", "Schedule not found: $scheduleId")
                    AppLogger.log("ALARM", "ERROR: Schedule not found: $scheduleId")
                }
                is ScheduleTransitions.ScheduleDeactivationResult.Applied -> {
                    result.keptDueToUserTimerModeIds.forEach { kept ->
                        android.util.Log.d("SCHEDULE_ALARM", "Skipping mode $kept: has active user timer, keeping alive")
                        AppLogger.log("ALARM", "Skipping timed mode $kept — user timer takes priority over schedule end")
                    }
                    AppLogger.log("ALARM", "Schedule deactivated: removed=${result.deactivatedModeIds}, kept=${result.keptDueToUserTimerModeIds}, activeModes=${result.newState.activeModes}")
                    android.util.Log.d("SCHEDULE_ALARM", "- Active modes updated to: ${result.newState.activeModes}")
                    // Side effects dispatched by AppStateRepository via StateSyncer.
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SCHEDULE_ALARM", "Error deactivating schedule: ${e.message}", e)
        }
    }

    private fun deactivateTimedMode(context: Context, modeId: String) {
        android.util.Log.d("SCHEDULE_ALARM", ">>> Deactivating timed mode $modeId")
        AppLogger.log("ALARM", "Deactivating timed mode $modeId")
        val repo = AppStateRepository.getInstance(context)

        try {
            val result = runBlocking {
                repo.updateWith { state ->
                    val r = ScheduleTransitions.applyTimedModeDeactivation(state, modeId)
                    r.newState to r
                }
            }
            when (result) {
                is ScheduleTransitions.TimedDeactivationResult.AlreadyInactive ->
                    android.util.Log.d("SCHEDULE_ALARM", "Mode $modeId already inactive, skipping")
                is ScheduleTransitions.TimedDeactivationResult.Applied -> {
                    // Side effects dispatched by AppStateRepository via StateSyncer.
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SCHEDULE_ALARM", "Error deactivating timed mode: ${e.message}", e)
        }
    }

    private fun reactivateTimedMode(context: Context, modeId: String) {
        android.util.Log.d("SCHEDULE_ALARM", ">>> Reactivating timed mode $modeId")
        AppLogger.log("ALARM", "Reactivating timed mode $modeId")
        val repo = AppStateRepository.getInstance(context)

        try {
            // Capture pausedRemainingMs BEFORE the transform consumes it (for the log line below)
            val pausedRemainingMs = repo.current.pausedModeRemainingMs[modeId] ?: 0L
            val result = runBlocking {
                repo.updateWith { state ->
                    val r = NfcUnlockLogic.applyReactivation(state, modeId, System.currentTimeMillis())
                    r.newState to r
                }
            }

            when (result) {
                is NfcUnlockLogic.ReactivationResult.AlreadyActive ->
                    android.util.Log.d("SCHEDULE_ALARM", "Mode $modeId already active, just cleaning up reactivation timer")
                is NfcUnlockLogic.ReactivationResult.ModeNotFound ->
                    android.util.Log.d("SCHEDULE_ALARM", "Mode $modeId not found, cleaning up")
                is NfcUnlockLogic.ReactivationResult.Conflict -> {
                    android.util.Log.w("SCHEDULE_ALARM", "Reactivation conflict for ${result.modeName} — skipping")
                    AppLogger.log("ALARM", "CONFLICT: Skipping reactivation of ${result.modeName}")
                }
                is NfcUnlockLogic.ReactivationResult.Reactivated -> {
                    val mode = result.newState.modes.find { it.id == modeId }
                    AppLogger.log("ALARM", "Reactivating mode '${mode?.name}' after timed unlock expired")
                    if (result.restoredDeactivationAt != null) {
                        AppLogger.log("ALARM", "Restoring timed deactivation for '${mode?.name}': ${pausedRemainingMs / 60000}m remaining")
                    }
                    // All side effects (service, widget, alarms) dispatched by repo via StateSyncer.
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SCHEDULE_ALARM", "Error reactivating timed mode: ${e.message}", e)
        }
    }

    companion object {
        private const val ACTION_ACTIVATE_SCHEDULE = "com.andebugulin.nfcguard.ACTIVATE_SCHEDULE"
        private const val ACTION_DEACTIVATE_SCHEDULE = "com.andebugulin.nfcguard.DEACTIVATE_SCHEDULE"
        private const val ACTION_TIMED_DEACTIVATE_MODE = "com.andebugulin.nfcguard.TIMED_DEACTIVATE_MODE"
        private const val ACTION_TIMED_REACTIVATE_MODE = "com.andebugulin.nfcguard.TIMED_REACTIVATE_MODE"
        private const val EXTRA_SCHEDULE_ID = "schedule_id"
        private const val EXTRA_DAY = "day"

        private const val ACTION_CHECK_SCHEDULE = "com.andebugulin.nfcguard.CHECK_SCHEDULE"
        private const val WATCHDOG_INTERVAL_MS = 15L * 60 * 1000  // 15 minutes

        fun scheduleWatchdog(context: Context) {
            val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = ACTION_CHECK_SCHEDULE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 99999, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
            AppLogger.log("ALARM", "Watchdog scheduled for ${java.util.Date(triggerAt)}")
        }

        fun cancelWatchdog(context: Context) {
            val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = ACTION_CHECK_SCHEDULE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 99999, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(it)
            }
        }

        private fun scheduleAlarmForSchedule(
            context: Context,
            scheduleId: String,
            day: Int,
            isStart: Boolean,
            forNextWeek: Boolean = false
        ) {
            val appState = AppStateRepository.getInstance(context).current

            try {
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
                android.util.Log.d("SCHEDULE_ALARM", "- Scheduled ${if (isStart) "START" else "END"} for ${getDayName(day)} $timeStr")
                android.util.Log.d("SCHEDULE_ALARM", "   Will fire at: ${java.util.Date(calendar.timeInMillis)}")
                AppLogger.log("ALARM", "Scheduled ${if (isStart) "START" else "END"} for ${getDayName(day)} $timeStr at ${java.util.Date(calendar.timeInMillis)}")
            } catch (e: Exception) {
                android.util.Log.e("SCHEDULE_ALARM", "Error scheduling alarm: ${e.message}", e)
            }
        }

        fun scheduleAllUpcomingAlarms(context: Context) {
            android.util.Log.d("SCHEDULE_ALARM", "=== SCHEDULING ALL ALARMS ===")
            android.util.Log.d("SCHEDULE_ALARM", "Current time: ${java.util.Date()}")

            val appState = AppStateRepository.getInstance(context).current

            try {
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