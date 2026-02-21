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

    private fun ensureServiceRunning(context: Context) {
        if (BlockerService.isRunning()) {
            AppLogger.log("ALARM", "Watchdog: service alive ✓")
            return
        }

        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val stateJson = prefs.getString("app_state", null) ?: return

        try {
            val appState = json.decodeFromString<AppState>(stateJson)
            if (appState.activeModes.isEmpty()) {
                AppLogger.log("ALARM", "Watchdog: no active modes, skip")
                return
            }

            AppLogger.log("ALARM", "Watchdog: SERVICE DEAD — restarting with ${appState.activeModes.size} active modes")
            updateBlockerService(context, appState)
        } catch (e: Exception) {
            AppLogger.log("ALARM", "Watchdog restart error: ${e.message}")
        }
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
        }
    }

    private fun activateSpecificSchedule(context: Context, scheduleId: String, day: Int) {
        android.util.Log.d("SCHEDULE_ALARM", ">>> Activating schedule $scheduleId for day $day")
        AppLogger.log("ALARM", "Activating schedule $scheduleId for day $day")
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val stateJson = prefs.getString("app_state", null)

        if (stateJson == null) {
            android.util.Log.e("SCHEDULE_ALARM", "No app state found!")
            AppLogger.log("ALARM", "ERROR: No app state in SharedPreferences!")
            return
        }

        try {
            val appState = json.decodeFromString<AppState>(stateJson)
            val schedule = appState.schedules.find { it.id == scheduleId }

            if (schedule == null) {
                android.util.Log.e("SCHEDULE_ALARM", "Schedule not found: $scheduleId")
                AppLogger.log("ALARM", "ERROR: Schedule not found: $scheduleId")
                return
            }

            android.util.Log.d("SCHEDULE_ALARM", "Found schedule: ${schedule.name}")
            android.util.Log.d("SCHEDULE_ALARM", "Linked modes: ${schedule.linkedModeIds}")

            // FIX #2: Check for BLOCK/ALLOW conflict before activating
            val currentlyActiveModes = appState.modes.filter { appState.activeModes.contains(it.id) }
            val modesToActivate = schedule.linkedModeIds.filter { modeId ->
                val mode = appState.modes.find { it.id == modeId }
                if (mode == null) return@filter false
                // Skip if there's a block mode conflict with currently active modes
                if (currentlyActiveModes.isNotEmpty() && currentlyActiveModes.any { it.blockMode != mode.blockMode }) {
                    android.util.Log.w("SCHEDULE_ALARM", "Skipping mode ${mode.name}: BLOCK/ALLOW conflict with active modes")
                    AppLogger.log("ALARM", "CONFLICT: Skipping mode ${mode.name} — BLOCK/ALLOW conflict")
                    return@filter false
                }
                true
            }

            val newActiveModes = appState.activeModes + modesToActivate
            val newActiveSchedules = appState.activeSchedules + scheduleId
            val newDeactivatedSchedules = appState.deactivatedSchedules - scheduleId

            val newState = appState.copy(
                activeModes = newActiveModes,
                activeSchedules = newActiveSchedules,
                deactivatedSchedules = newDeactivatedSchedules
            )
            AppLogger.log("ALARM", "Schedule activated: activeModes=$newActiveModes, activeSchedules=$newActiveSchedules")
            val newStateJson = json.encodeToString(newState)
            prefs.edit().putString("app_state", newStateJson).apply()

            android.util.Log.d("SCHEDULE_ALARM", "- Active modes updated to: $newActiveModes")

            android.util.Log.d("SCHEDULE_ALARM", "- Active schedules: $newActiveSchedules")
            updateBlockerService(context, newState)
        } catch (e: Exception) {
            android.util.Log.e("SCHEDULE_ALARM", "Error activating schedule: ${e.message}", e)
        }
    }

    private fun deactivateSpecificSchedule(context: Context, scheduleId: String) {
        android.util.Log.d("SCHEDULE_ALARM", ">>> Deactivating schedule $scheduleId")
        AppLogger.log("ALARM", "Deactivating schedule $scheduleId")
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val stateJson = prefs.getString("app_state", null)

        if (stateJson == null) {
            android.util.Log.e("SCHEDULE_ALARM", "No app state found!")
            AppLogger.log("ALARM", "ERROR: No app state in SharedPreferences!")
            return
        }

        try {
            val appState = json.decodeFromString<AppState>(stateJson)
            val schedule = appState.schedules.find { it.id == scheduleId }

            if (schedule == null) {
                android.util.Log.e("SCHEDULE_ALARM", "Schedule not found: $scheduleId")
                AppLogger.log("ALARM", "ERROR: Schedule not found: $scheduleId")
                return
            }

            android.util.Log.d("SCHEDULE_ALARM", "Deactivating modes: ${schedule.linkedModeIds}")

            // Skip modes that have a user-set timer — user's explicit duration takes priority
            val modesToDeactivate = schedule.linkedModeIds.filter { modeId ->
                val hasTimer = appState.timedModeDeactivations.containsKey(modeId)
                if (hasTimer) {
                    android.util.Log.d("SCHEDULE_ALARM", "Skipping mode $modeId: has active user timer, keeping alive")
                    AppLogger.log("ALARM", "Skipping timed mode $modeId — user timer takes priority over schedule end")
                }
                !hasTimer
            }.toSet()

            val newActiveModes = appState.activeModes - modesToDeactivate
            val newActiveSchedules = appState.activeSchedules - scheduleId
            val newDeactivatedSchedules = appState.deactivatedSchedules - scheduleId

            val newState = appState.copy(
                activeModes = newActiveModes,
                activeSchedules = newActiveSchedules,
                deactivatedSchedules = newDeactivatedSchedules
            )
            AppLogger.log("ALARM", "Schedule deactivated: removed=${modesToDeactivate}, kept=${schedule.linkedModeIds.toSet() - modesToDeactivate}, activeModes=$newActiveModes")
            val newStateJson = json.encodeToString(newState)
            prefs.edit().putString("app_state", newStateJson).apply()

            android.util.Log.d("SCHEDULE_ALARM", "- Active modes updated to: $newActiveModes")

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

        val modeNamesMap = appState.modes.associate { it.id to it.name }

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
                BlockerService.start(context, allAllowedApps, BlockMode.ALLOW_SELECTED, activeModes.map { it.id }.toSet(),
                    manuallyActivatedModeIds = appState.manuallyActivatedModes,
                    timedModeDeactivations = appState.timedModeDeactivations,
                    modeNames = modeNamesMap)
            } else {
                val allBlockedApps = mutableSetOf<String>()
                activeModes.forEach { mode ->
                    allBlockedApps.addAll(mode.blockedApps)
                }
                android.util.Log.d("SCHEDULE_ALARM", "Starting service in BLOCK mode")
                BlockerService.start(context, allBlockedApps, BlockMode.BLOCK_SELECTED, activeModes.map { it.id }.toSet(),
                    manuallyActivatedModeIds = appState.manuallyActivatedModes,
                    timedModeDeactivations = appState.timedModeDeactivations,
                    modeNames = modeNamesMap)
            }
            scheduleWatchdog(context)
        } else {
            if (appState.schedules.isNotEmpty()) {
                android.util.Log.d("SCHEDULE_ALARM", "No active modes, keeping service for schedules")
                BlockerService.start(context, emptySet(), BlockMode.BLOCK_SELECTED, emptySet())
            } else {
                android.util.Log.d("SCHEDULE_ALARM", "No modes or schedules, stopping service")
                BlockerService.stop(context)
                cancelWatchdog(context)
            }
        }
    }

    private fun deactivateTimedMode(context: Context, modeId: String) {
        android.util.Log.d("SCHEDULE_ALARM", ">>> Deactivating timed mode $modeId")
        AppLogger.log("ALARM", "Deactivating timed mode $modeId")
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        val stateJson = prefs.getString("app_state", null) ?: return

        try {
            val appState = json.decodeFromString<AppState>(stateJson)
            if (!appState.activeModes.contains(modeId)) {
                android.util.Log.d("SCHEDULE_ALARM", "Mode $modeId already inactive, skipping")
                return
            }

            // Find schedules that linked this mode and are currently active
            val schedulesToMark = appState.schedules
                .filter { schedule ->
                    schedule.linkedModeIds.contains(modeId) &&
                            appState.activeSchedules.contains(schedule.id)
                }
                .map { it.id }
                .toSet()

            val schedulesToDeactivate = schedulesToMark.filter { scheduleId ->
                val schedule = appState.schedules.find { it.id == scheduleId }
                schedule?.linkedModeIds?.all { linkedModeId ->
                    linkedModeId == modeId || !appState.activeModes.contains(linkedModeId)
                } ?: true
            }.toSet()

            val newState = appState.copy(
                activeModes = appState.activeModes - modeId,
                activeSchedules = appState.activeSchedules - schedulesToDeactivate,
                deactivatedSchedules = appState.deactivatedSchedules + schedulesToDeactivate,
                manuallyActivatedModes = appState.manuallyActivatedModes - modeId,
                timedModeDeactivations = appState.timedModeDeactivations - modeId
            )

            val newStateJson = json.encodeToString(newState)
            prefs.edit().putString("app_state", newStateJson).apply()

            updateBlockerService(context, newState)
        } catch (e: Exception) {
            android.util.Log.e("SCHEDULE_ALARM", "Error deactivating timed mode: ${e.message}", e)
        }
    }

    companion object {
        private const val ACTION_ACTIVATE_SCHEDULE = "com.andebugulin.nfcguard.ACTIVATE_SCHEDULE"
        private const val ACTION_DEACTIVATE_SCHEDULE = "com.andebugulin.nfcguard.DEACTIVATE_SCHEDULE"
        private const val ACTION_TIMED_DEACTIVATE_MODE = "com.andebugulin.nfcguard.TIMED_DEACTIVATE_MODE"
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

            val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            val stateJson = prefs.getString("app_state", null)

            if (stateJson == null) {
                android.util.Log.e("SCHEDULE_ALARM", "No app state found!")
                AppLogger.log("ALARM", "ERROR: No app state in SharedPreferences!")
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