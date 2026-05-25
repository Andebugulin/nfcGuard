package com.andebugulin.nfcguard

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Serializable
data class DayTime(
    val day: Int,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int
)

@Serializable
data class TimeSlot(
    val dayTimes: List<DayTime>
) {
    val days: List<Int> get() = dayTimes.map { it.day }
    val startHour: Int get() = dayTimes.firstOrNull()?.startHour ?: 9
    val startMinute: Int get() = dayTimes.firstOrNull()?.startMinute ?: 0
    val endHour: Int get() = dayTimes.firstOrNull()?.endHour ?: 23
    val endMinute: Int get() = dayTimes.firstOrNull()?.endMinute ?: 59
    fun getTimeForDay(day: Int): DayTime? = dayTimes.find { it.day == day }
}

@Serializable
data class Mode(
    val id: String,
    val name: String,
    val blockedApps: List<String>,
    val blockMode: BlockMode = BlockMode.BLOCK_SELECTED,
    @Deprecated("Use nfcTagIds instead. Kept for migration from older versions.")
    val nfcTagId: String? = null,
    val nfcTagIds: List<String> = emptyList(),
    val tagUnlockLimits: Map<String, Long?> = emptyMap() // tagId -> max unlock duration in minutes (null = permanent). Key "ANY" for any other tag.
) {
    /** Resolved tag list: migrates legacy single-tag field automatically. */
    val effectiveNfcTagIds: List<String>
        get() = if (nfcTagIds.isNotEmpty()) nfcTagIds
        else if (@Suppress("DEPRECATION") nfcTagId != null) listOf(@Suppress("DEPRECATION") nfcTagId!!)
        else emptyList()

    /** Returns the limit for a specific tag, prioritizing exact match over wildcard. */
    fun getLimitForTag(tagId: String): Long? {
        if (tagUnlockLimits.containsKey(tagId)) return tagUnlockLimits[tagId]
        if (tagUnlockLimits.containsKey("ANY")) return tagUnlockLimits["ANY"]
        return null
    }
}

@Serializable
data class Schedule(
    val id: String,
    val name: String,
    val timeSlot: TimeSlot,
    val linkedModeIds: List<String>,
    val hasEndTime: Boolean = false
)

@Serializable
data class NfcTag(
    val id: String,
    val name: String,
    val linkedModeIds: List<String> = emptyList()
)

@Serializable
enum class BlockMode {
    BLOCK_SELECTED,
    ALLOW_SELECTED
}

@Serializable
data class AppState(
    val modes: List<Mode> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val nfcTags: List<NfcTag> = emptyList(),
    val activeModes: Set<String> = emptySet(),
    val activeSchedules: Set<String> = emptySet(), // Schedules that activated their modes
    val deactivatedSchedules: Set<String> = emptySet(), // Schedules manually deactivated by user
    val manuallyActivatedModes: Set<String> = emptySet(), // Modes activated by user tap (not by schedule)
    val timedModeDeactivations: Map<String, Long> = emptyMap(), // modeId -> epoch millis when it should auto-deactivate
    val timedModeReactivations: Map<String, Long> = emptyMap(), // modeId -> epoch millis when it should auto-reactivate after NFC unlock
    val pausedModeRemainingMs: Map<String, Long> = emptyMap() // modeId -> remaining deactivation ms saved when mode was paused
)

/** Pending NFC unlock awaiting user duration choice (not persisted) */
data class PendingUnlock(
    val modeIds: Set<String>,
    val schedulesToDeactivate: Set<String>,
    val tagId: String? = null, // Store which tag was scanned to apply its specific limit
    val maxLimitMinutes: Long? = null, // The most restrictive limit among all modes being unlocked
    val modeLimits: Map<String, Long?> = emptyMap() // per-mode limit: modeId -> limit (null = permanent)
)

/** Result of attempting to activate a mode */
enum class ActivationResult {
    SUCCESS,
    BLOCK_MODE_CONFLICT,
    MODE_NOT_FOUND
}

class GuardianViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application
    private val repo: AppStateRepository = AppStateRepository.getInstance(application)

    val appState: StateFlow<AppState> = repo.state

    /** Safe Regime — stored separately from AppState to prevent bypass via config import */
    private val _safeRegimeEnabled = MutableStateFlow(true)
    val safeRegimeEnabled: StateFlow<Boolean> = _safeRegimeEnabled

    /** Pending NFC unlock awaiting user duration choice */
    private val _pendingUnlock = MutableStateFlow<PendingUnlock?>(null)
    val pendingUnlock: StateFlow<PendingUnlock?> = _pendingUnlock

    init {
        // Safe Regime is intentionally outside AppState (so config import
        // can't disable the safety challenge). Direct prefs access.
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        _safeRegimeEnabled.value = prefs.getBoolean("safe_regime_enabled", true)

        ensureServiceRunning()

        viewModelScope.launch {
            while (isActive) {
                delay(5000)  // Check every 5 seconds
                checkTimedDeactivations()
                checkTimedReactivations()
            }
        }
    }

    private fun ensureServiceRunning() {
        // Only start service if there are active modes OR schedules
        val currentState = repo.current
        if (currentState.activeModes.isNotEmpty() || currentState.schedules.isNotEmpty()) {
            if (!BlockerService.isRunning()) {
                updateBlockerService()
            }
        }
    }

    fun setSafeRegimeEnabled(enabled: Boolean) {
        _safeRegimeEnabled.value = enabled
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("safe_regime_enabled", enabled).apply()
    }

    /**
     * Apply [transform] to the persisted state atomically, then run the
     * standard side effects (service restart, alarm scheduling, widget
     * refresh). All mutating methods go through this helper.
     */
    private suspend fun mutate(transform: (AppState) -> AppState): AppState {
        val newState = repo.update(transform)
        updateBlockerService()
        ScheduleAlarmReceiver.scheduleAllUpcomingAlarms(context)
        GuardianWidget.notifyAllWidgets(context)
        return newState
    }

    private fun updateBlockerService() {
        val activeModes = repo.current.modes.filter {
            repo.current.activeModes.contains(it.id)
        }

        val modeNamesMap = repo.current.modes.associate { it.id to it.name }

        AppLogger.log("SERVICE", "updateBlockerService: ${activeModes.size} active modes, ids=${repo.current.activeModes}")

        if (activeModes.isNotEmpty()) {
            val hasAllowMode = activeModes.any { it.blockMode == BlockMode.ALLOW_SELECTED }

            if (hasAllowMode) {
                val allAllowedApps = mutableSetOf<String>()
                activeModes.forEach { mode ->
                    if (mode.blockMode == BlockMode.ALLOW_SELECTED) {
                        allAllowedApps.addAll(mode.blockedApps)
                    }
                }
                AppLogger.log("SERVICE", "Starting ALLOW_SELECTED with ${allAllowedApps.size} allowed apps")
                BlockerService.start(
                    context,
                    allAllowedApps,
                    BlockMode.ALLOW_SELECTED,
                    activeModes.map { it.id }.toSet(),
                    manuallyActivatedModeIds = repo.current.manuallyActivatedModes,
                    timedModeDeactivations = repo.current.timedModeDeactivations,
                    modeNames = modeNamesMap,
                    timedModeReactivations = repo.current.timedModeReactivations
                )
            } else {
                val allBlockedApps = mutableSetOf<String>()
                activeModes.forEach { mode ->
                    allBlockedApps.addAll(mode.blockedApps)
                }
                AppLogger.log("SERVICE", "Starting BLOCK_SELECTED with ${allBlockedApps.size} blocked apps")
                BlockerService.start(
                    context,
                    allBlockedApps,
                    BlockMode.BLOCK_SELECTED,
                    activeModes.map { it.id }.toSet(),
                    manuallyActivatedModeIds = repo.current.manuallyActivatedModes,
                    timedModeDeactivations = repo.current.timedModeDeactivations,
                    modeNames = modeNamesMap,
                    timedModeReactivations = repo.current.timedModeReactivations
                )
            }
        } else {
            AppLogger.log("SERVICE", "No active modes — starting empty service for schedule monitoring")
            // Keep service running even with no active modes to handle schedules
            BlockerService.start(
                context,
                emptySet(),
                BlockMode.BLOCK_SELECTED,
                emptySet(),
                timedModeReactivations = repo.current.timedModeReactivations
            )
        }
    }

    fun addMode(name: String, blockedApps: List<String>, blockMode: BlockMode = BlockMode.BLOCK_SELECTED, nfcTagIds: List<String> = emptyList(), tagUnlockLimits: Map<String, Long?> = emptyMap()) {
        viewModelScope.launch {
            val newMode = Mode(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                blockedApps = blockedApps,
                blockMode = blockMode,
                nfcTagIds = nfcTagIds,
                tagUnlockLimits = tagUnlockLimits
            )
            mutate { it.copy(modes = it.modes + newMode) }
        }
    }

    fun updateMode(id: String, name: String, blockedApps: List<String>, blockMode: BlockMode, nfcTagIds: List<String>, tagUnlockLimits: Map<String, Long?> = emptyMap()) {
        viewModelScope.launch {
            mutate { state ->
                state.copy(
                    modes = state.modes.map { mode ->
                        if (mode.id == id) mode.copy(
                            name = name,
                            blockedApps = blockedApps,
                            blockMode = blockMode,
                            nfcTagId = null,
                            nfcTagIds = nfcTagIds,
                            tagUnlockLimits = tagUnlockLimits
                        ) else mode
                    }
                )
            }
        }
    }

    fun deleteMode(id: String) {
        viewModelScope.launch {
            mutate { state ->
                state.copy(
                    modes = state.modes.filter { it.id != id },
                    activeModes = state.activeModes - id,
                    schedules = state.schedules.map { schedule ->
                        schedule.copy(linkedModeIds = schedule.linkedModeIds.filter { it != id })
                    },
                    nfcTags = state.nfcTags.map { tag ->
                        tag.copy(linkedModeIds = tag.linkedModeIds.filter { it != id })
                    },
                    manuallyActivatedModes = state.manuallyActivatedModes - id,
                    timedModeDeactivations = state.timedModeDeactivations - id,
                    timedModeReactivations = state.timedModeReactivations - id,
                    pausedModeRemainingMs = state.pausedModeRemainingMs - id
                )
            }
            cancelTimedDeactivation(id)
            cancelTimedReactivation(id)
        }
    }

    fun activateMode(modeId: String, timedUntilMillis: Long? = null): ActivationResult {
        val result = ModeActivationLogic.applyModeActivation(repo.current, modeId, timedUntilMillis)
        return when (result) {
            is ModeActivationLogic.ActivateModeResult.ModeNotFound -> ActivationResult.MODE_NOT_FOUND
            is ModeActivationLogic.ActivateModeResult.Conflict -> {
                val mode = repo.current.modes.find { it.id == modeId }
                AppLogger.log("MODE", "CONFLICT: Cannot activate '${result.modeName}' (${mode?.blockMode}) — conflicts with active modes")
                ActivationResult.BLOCK_MODE_CONFLICT
            }
            is ModeActivationLogic.ActivateModeResult.Activated -> {
                val mode = repo.current.modes.find { it.id == modeId }
                AppLogger.log("MODE", "Activating: '${mode?.name}' (${mode?.blockMode}, ${mode?.blockedApps?.size} apps, nfc=${mode?.effectiveNfcTagIds?.ifEmpty { listOf("any") }}, timed=${timedUntilMillis != null})")
                viewModelScope.launch {
                    mutate { result.newState }
                    cancelTimedReactivation(modeId)
                    if (timedUntilMillis != null) {
                        scheduleTimedDeactivation(modeId, timedUntilMillis)
                    }
                }
                ActivationResult.SUCCESS
            }
        }
    }

    fun deactivateMode(modeId: String) {
        val modeName = repo.current.modes.find { it.id == modeId }?.name ?: "unknown"
        AppLogger.log("MODE", "Deactivating: '$modeName' (id=$modeId)")
        viewModelScope.launch {
            mutate { ModeActivationLogic.applyModeDeactivation(it, modeId).newState }
            cancelTimedDeactivation(modeId)
            cancelTimedReactivation(modeId)
        }
    }

    fun markScheduleDeactivated(scheduleId: String) {
        viewModelScope.launch {
            mutate { it.copy(deactivatedSchedules = it.deactivatedSchedules + scheduleId) }
        }
    }

    fun clearScheduleDeactivation(scheduleId: String) {
        viewModelScope.launch {
            mutate { it.copy(deactivatedSchedules = it.deactivatedSchedules - scheduleId) }
        }
    }

    fun addSchedule(name: String, timeSlot: TimeSlot, linkedModeIds: List<String>, hasEndTime: Boolean) {
        viewModelScope.launch {
            val newSchedule = Schedule(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                timeSlot = timeSlot,
                linkedModeIds = linkedModeIds,
                hasEndTime = hasEndTime
            )
            mutate { it.copy(schedules = it.schedules + newSchedule) }
        }
    }

    fun updateSchedule(id: String, name: String, timeSlot: TimeSlot, linkedModeIds: List<String>, hasEndTime: Boolean) {
        viewModelScope.launch {
            mutate { state ->
                state.copy(
                    schedules = state.schedules.map { schedule ->
                        if (schedule.id == id) schedule.copy(
                            name = name,
                            timeSlot = timeSlot,
                            linkedModeIds = linkedModeIds,
                            hasEndTime = hasEndTime
                        ) else schedule
                    }
                )
            }
        }
    }

    fun deleteSchedule(id: String) {
        viewModelScope.launch {
            mutate { it.copy(schedules = it.schedules.filter { s -> s.id != id }) }
        }
    }

    // FIX #3: Returns false if tag already registered
    fun addNfcTag(tagId: String, name: String): Boolean {
        if (repo.current.nfcTags.any { it.id == tagId }) {
            return false
        }
        viewModelScope.launch {
            val newTag = NfcTag(
                id = tagId,
                name = name,
                linkedModeIds = emptyList()
            )
            mutate { it.copy(nfcTags = it.nfcTags + newTag) }
        }
        return true
    }

    fun updateNfcTag(tagId: String, name: String) {
        viewModelScope.launch {
            mutate { state ->
                state.copy(
                    nfcTags = state.nfcTags.map { tag ->
                        if (tag.id == tagId) tag.copy(name = name) else tag
                    }
                )
            }
        }
    }

    fun deleteNfcTag(tagId: String) {
        viewModelScope.launch {
            mutate { state ->
                state.copy(
                    nfcTags = state.nfcTags.filter { it.id != tagId },
                    modes = state.modes.map { mode ->
                        if (mode.effectiveNfcTagIds.contains(tagId)) mode.copy(
                            nfcTagId = null,
                            nfcTagIds = mode.effectiveNfcTagIds.filter { it != tagId }
                        ) else mode
                    }
                )
            }
        }
    }

    fun handleNfcTag(tagId: String) {
        viewModelScope.launch {
            AppLogger.log("NFC", "handleNfcTag: tagId=$tagId, activeModes=${repo.current.activeModes}")
            val calendar = java.util.Calendar.getInstance()
            val currentDayOfWeek = NfcUnlockLogic.calendarDayToScheduleDay(calendar.get(java.util.Calendar.DAY_OF_WEEK))
            val currentMinuteOfDay = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)

            val pending = NfcUnlockLogic.computePendingUnlock(
                state = repo.current,
                tagId = tagId,
                currentDayOfWeek = currentDayOfWeek,
                currentMinuteOfDay = currentMinuteOfDay
            ) ?: return@launch

            AppLogger.log("NFC", "Pending unlock: modes=${pending.modeIds}, schedules=${pending.schedulesToDeactivate}, limit=${pending.maxLimitMinutes}")
            _pendingUnlock.value = pending
        }
    }

    /** User confirmed unlock duration from dialog. null = permanent, otherwise epoch millis to reactivate.
     *  selectedModeIds = which modes to actually unlock (subset of pending.modeIds). null = all. */
    fun confirmUnlock(reactivateAtMillis: Long? = null, selectedModeIds: Set<String>? = null) {
        val pending = _pendingUnlock.value ?: return
        _pendingUnlock.value = null

        viewModelScope.launch {
            val currentState = repo.current
            val calendar = java.util.Calendar.getInstance()
            val currentDayOfWeek = NfcUnlockLogic.calendarDayToScheduleDay(calendar.get(java.util.Calendar.DAY_OF_WEEK))
            val currentMinuteOfDay = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
            val now = System.currentTimeMillis()

            val result = NfcUnlockLogic.applyUnlock(
                state = currentState,
                pending = pending,
                selectedModeIds = selectedModeIds,
                reactivateAtMillis = reactivateAtMillis,
                now = now,
                currentDayOfWeek = currentDayOfWeek,
                currentMinuteOfDay = currentMinuteOfDay
            )

            AppLogger.log("NFC", "Confirming unlock: modes=${result.unlockedModeIds} (of ${pending.modeIds}), reactivate=${reactivateAtMillis != null}")
            result.unlockedModeIds.forEach { modeId ->
                val remaining = result.newState.pausedModeRemainingMs[modeId]
                if (remaining != null && remaining > 0) {
                    AppLogger.log("TIMER", "Saving remaining ${remaining / 60000}m for mode $modeId")
                }
            }

            mutate { result.newState }

            result.unlockedModeIds.forEach { cancelTimedDeactivation(it) }

            if (reactivateAtMillis != null) {
                result.unlockedModeIds.forEach { modeId ->
                    scheduleTimedReactivation(modeId, reactivateAtMillis)
                }
            }
        }
    }

    /** User dismissed the unlock dialog — do nothing, modes stay active */
    fun dismissUnlock() {
        _pendingUnlock.value = null
    }

    /** Schedule a timed reactivation alarm via AlarmManager */
    private fun scheduleTimedReactivation(modeId: String, reactivateAtMillis: Long) {
        try {
            val intent = android.content.Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = "com.andebugulin.nfcguard.TIMED_REACTIVATE_MODE"
                putExtra("mode_id", modeId)
            }
            val requestCode = ("reactivate_$modeId").hashCode()
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    reactivateAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    android.app.AlarmManager.RTC_WAKEUP,
                    reactivateAtMillis,
                    pendingIntent
                )
            }
            AppLogger.log("TIMER", "Scheduled timed reactivation for mode $modeId at ${java.util.Date(reactivateAtMillis)}")
        } catch (e: Exception) {
            AppLogger.log("TIMER", "Error scheduling timed reactivation: ${e.message}")
        }
    }

    /** Cancel a timed reactivation alarm */
    private fun cancelTimedReactivation(modeId: String) {
        try {
            val intent = android.content.Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = "com.andebugulin.nfcguard.TIMED_REACTIVATE_MODE"
                putExtra("mode_id", modeId)
            }
            val requestCode = ("reactivate_$modeId").hashCode()
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                alarmManager.cancel(it)
            }
        } catch (e: Exception) {
            AppLogger.log("TIMER", "Error cancelling timed reactivation: ${e.message}")
        }
    }

    /** Check for expired timed reactivations and re-enable modes (called from polling loop) */
    private fun checkTimedReactivations() {
        val currentState = repo.current
        if (currentState.timedModeReactivations.isEmpty()) return

        val now = System.currentTimeMillis()
        val expired = currentState.timedModeReactivations.filter { (_, deadline) -> now >= deadline }
        if (expired.isNotEmpty()) {
            AppLogger.log("TIMER", "Timed reactivation: ${expired.keys}")
            expired.keys.forEach { modeId -> reactivateMode(modeId) }
        }
    }

    /** Reactivate a mode after timed unlock expires */
    fun reactivateMode(modeId: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val result = NfcUnlockLogic.applyReactivation(repo.current, modeId, now)

            when (result) {
                is NfcUnlockLogic.ReactivationResult.ModeNotFound -> {
                    // Mode gone — just clear the orphan reactivation entry
                }
                is NfcUnlockLogic.ReactivationResult.AlreadyActive -> {
                    // Schedule (or other path) already re-activated it; just clean up
                }
                is NfcUnlockLogic.ReactivationResult.Conflict -> {
                    AppLogger.log("TIMER", "Reactivation conflict for '${result.modeName}' — skipping, clearing timer")
                }
                is NfcUnlockLogic.ReactivationResult.Reactivated -> {
                    val mode = repo.current.modes.find { it.id == modeId }
                    AppLogger.log("TIMER", "Reactivating mode '${mode?.name}' after timed unlock")
                    if (result.restoredDeactivationAt != null) {
                        val remainingMs = result.restoredDeactivationAt - now
                        AppLogger.log("TIMER", "Restoring timed deactivation for '${mode?.name}': ${remainingMs / 60000}m remaining")
                    }
                }
            }

            mutate { result.newState }

            if (result is NfcUnlockLogic.ReactivationResult.Reactivated && result.restoredDeactivationAt != null) {
                scheduleTimedDeactivation(modeId, result.restoredDeactivationAt)
            }
        }
    }

    fun importConfig(data: ConfigManager.ExportData, mergeMode: Boolean = false) {
        viewModelScope.launch {
            mutate { state ->
                val afterImport = if (mergeMode) {
                    // For existing items: fully replace with imported version (restores apps, block mode, schedule links, etc.)
                    // For new items: add them with nfcTagId migration applied
                    val importModeMap = data.modes.associateBy { it.id }
                    val importScheduleMap = data.schedules.associateBy { it.id }
                    val importTagMap = data.nfcTags.associateBy { it.id }
                    val existingModeIds = state.modes.map { it.id }.toSet()
                    val existingScheduleIds = state.schedules.map { it.id }.toSet()
                    val existingTagIds = state.nfcTags.map { it.id }.toSet()

                    val mergedModes = state.modes.map { existing ->
                        val imported = importModeMap[existing.id]
                        if (imported != null) {
                            imported.copy(nfcTagId = null, nfcTagIds = imported.effectiveNfcTagIds)
                        } else existing
                    } + data.modes.filter { it.id !in existingModeIds }.map { m ->
                        m.copy(nfcTagId = null, nfcTagIds = m.effectiveNfcTagIds)
                    }

                    val mergedSchedules = state.schedules.map { existing ->
                        importScheduleMap[existing.id] ?: existing
                    } + data.schedules.filter { it.id !in existingScheduleIds }

                    val mergedTags = state.nfcTags.map { existing ->
                        importTagMap[existing.id] ?: existing
                    } + data.nfcTags.filter { it.id !in existingTagIds }

                    state.copy(modes = mergedModes, schedules = mergedSchedules, nfcTags = mergedTags)
                } else {
                    // Replace: overwrite all config, reset runtime state
                    val normalizedModes = data.modes.map { m ->
                        m.copy(nfcTagId = null, nfcTagIds = m.effectiveNfcTagIds)
                    }
                    state.copy(
                        modes = normalizedModes,
                        schedules = data.schedules,
                        nfcTags = data.nfcTags,
                        activeModes = emptySet(),
                        activeSchedules = emptySet(),
                        deactivatedSchedules = emptySet(),
                        manuallyActivatedModes = emptySet(),
                        timedModeDeactivations = emptyMap(),
                        timedModeReactivations = emptyMap(),
                        pausedModeRemainingMs = emptyMap()
                    )
                }

                // FIX #11: Clean up orphaned nfcTagIds references after import
                val validTagIds = afterImport.nfcTags.map { it.id }.toSet()
                afterImport.copy(
                    modes = afterImport.modes.map { mode ->
                        val cleaned = mode.effectiveNfcTagIds.filter { it in validTagIds || it == "ANY" }
                        val cleanedLimits = mode.tagUnlockLimits.filterKeys { it in validTagIds || it == "ANY" }
                        if (cleaned != mode.effectiveNfcTagIds || cleanedLimits != mode.tagUnlockLimits) {
                            mode.copy(nfcTagId = null, nfcTagIds = cleaned, tagUnlockLimits = cleanedLimits)
                        } else mode
                    }
                )
            }
        }
    }

    /** Activate a schedule manually from the SchedulesScreen.
     *  This activates the schedule's linked modes and marks the schedule as active,
     *  so the end-alarm will properly deactivate everything. */
    fun activateScheduleManually(scheduleId: String): ActivationResult {
        val result = ModeActivationLogic.applyManualScheduleActivation(repo.current, scheduleId)
        return when (result) {
            is ModeActivationLogic.ManualScheduleActivationResult.ScheduleNotFound ->
                ActivationResult.MODE_NOT_FOUND
            is ModeActivationLogic.ManualScheduleActivationResult.Conflict ->
                ActivationResult.BLOCK_MODE_CONFLICT
            is ModeActivationLogic.ManualScheduleActivationResult.Activated -> {
                val schedule = repo.current.schedules.find { it.id == scheduleId }
                AppLogger.log("SCHEDULE", "Manually activating schedule '${schedule?.name}' with ${schedule?.linkedModeIds?.size} modes")
                viewModelScope.launch {
                    mutate { result.newState }
                    schedule?.linkedModeIds?.forEach { cancelTimedReactivation(it) }
                }
                ActivationResult.SUCCESS
            }
        }
    }

    /** Schedule a timed deactivation alarm via AlarmManager for reliability */
    private fun scheduleTimedDeactivation(modeId: String, deactivateAtMillis: Long) {
        try {
            val intent = android.content.Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = "com.andebugulin.nfcguard.TIMED_DEACTIVATE_MODE"
                putExtra("mode_id", modeId)
            }
            val requestCode = ("timed_$modeId").hashCode()
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    deactivateAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    android.app.AlarmManager.RTC_WAKEUP,
                    deactivateAtMillis,
                    pendingIntent
                )
            }
            AppLogger.log("TIMER", "Scheduled timed deactivation for mode $modeId at ${java.util.Date(deactivateAtMillis)}")
        } catch (e: Exception) {
            AppLogger.log("TIMER", "Error scheduling timed deactivation: ${e.message}")
        }
    }

    /** Cancel a timed deactivation alarm */
    private fun cancelTimedDeactivation(modeId: String) {
        try {
            val intent = android.content.Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = "com.andebugulin.nfcguard.TIMED_DEACTIVATE_MODE"
                putExtra("mode_id", modeId)
            }
            val requestCode = ("timed_$modeId").hashCode()
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                alarmManager.cancel(it)
            }
        } catch (e: Exception) {
            AppLogger.log("TIMER", "Error cancelling timed deactivation: ${e.message}")
        }
    }

    /** Check for expired timed modes and deactivate them (called from polling loop) */
    private fun checkTimedDeactivations() {
        val currentState = repo.current
        if (currentState.timedModeDeactivations.isEmpty()) return

        val now = System.currentTimeMillis()
        val expired = currentState.timedModeDeactivations.filter { (_, deadline) -> now >= deadline }
        if (expired.isNotEmpty()) {
            AppLogger.log("TIMER", "Timed deactivation: ${expired.keys}")
            expired.keys.forEach { modeId -> deactivateMode(modeId) }
        }
    }

}