package com.andebugulin.nfcguard.ui

import com.andebugulin.nfcguard.ActivationResult
import com.andebugulin.nfcguard.AppState
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.data.AppLogger
import com.andebugulin.nfcguard.data.AppStateRepository
import com.andebugulin.nfcguard.data.ConfigManager
import com.andebugulin.nfcguard.Mode
import com.andebugulin.nfcguard.ModeActivationLogic
import com.andebugulin.nfcguard.NfcTag
import com.andebugulin.nfcguard.NfcUnlockLogic
import com.andebugulin.nfcguard.PendingUnlock
import com.andebugulin.nfcguard.Schedule
import com.andebugulin.nfcguard.service.BlockerService
import com.andebugulin.nfcguard.sync.StateSyncer
import com.andebugulin.nfcguard.TimeSlot
import com.andebugulin.nfcguard.ui.schedules.SchedulesScreen

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class GuardianViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application
    private val repo: AppStateRepository = AppStateRepository.getInstance(application)

    val appState: StateFlow<AppState> = repo.state

    /** Safe Regime — stored separately from AppState to prevent bypass via config import */
    private val _safeRegimeEnabled = MutableStateFlow(true)
    val safeRegimeEnabled: StateFlow<Boolean> = _safeRegimeEnabled

    /** Anti-bypass challenge wait time, in seconds. Floor is 90s (raise-only) so
     *  it can be made harder but never weakened. Stored separately from AppState. */
    private val _challengeDurationSeconds = MutableStateFlow(CHALLENGE_DEFAULT_SECONDS)
    val challengeDurationSeconds: StateFlow<Int> = _challengeDurationSeconds

    /** Pending NFC unlock awaiting user duration choice */
    private val _pendingUnlock = MutableStateFlow<PendingUnlock?>(null)
    val pendingUnlock: StateFlow<PendingUnlock?> = _pendingUnlock

    init {
        // Safe Regime is intentionally outside AppState (so config import
        // can't disable the safety challenge). Direct prefs access.
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        _safeRegimeEnabled.value = prefs.getBoolean("safe_regime_enabled", true)
        _challengeDurationSeconds.value =
            prefs.getInt("safe_regime_challenge_seconds", CHALLENGE_DEFAULT_SECONDS)
                .coerceAtLeast(CHALLENGE_MIN_SECONDS)

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
        val currentState = repo.current
        if (currentState.activeModes.isNotEmpty() || currentState.schedules.isNotEmpty()) {
            if (!BlockerService.isRunning()) {
                StateSyncer.sync(context, currentState)
            }
        }
    }

    fun setSafeRegimeEnabled(enabled: Boolean) {
        _safeRegimeEnabled.value = enabled
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("safe_regime_enabled", enabled).apply()
    }

    /** Set the anti-bypass challenge wait time. Coerced to the [CHALLENGE_MIN_SECONDS]
     *  floor so the challenge can be lengthened but never shortened past it. */
    fun setChallengeDurationSeconds(seconds: Int) {
        val coerced = seconds.coerceAtLeast(CHALLENGE_MIN_SECONDS)
        _challengeDurationSeconds.value = coerced
        val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("safe_regime_challenge_seconds", coerced).apply()
    }

    /**
     * Apply [transform] to the persisted state atomically. The repo
     * fires the standard side effects (service restart, schedule alarms,
     * widget refresh) via StateSyncer; no per-method dispatch needed.
     */
    private suspend fun mutate(transform: (AppState) -> AppState): AppState =
        repo.update(transform)

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
                    manuallyActivatedModes = state.manuallyActivatedModes - id,
                    timedModeDeactivations = state.timedModeDeactivations - id,
                    timedModeReactivations = state.timedModeReactivations - id,
                    pausedModeRemainingMs = state.pausedModeRemainingMs - id
                )
            }
            // Per-mode alarms are diffed from state by StateSyncer — no manual
            // schedule/cancel calls needed.
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
                AppLogger.log("MODE", "Activating: '${mode?.name}' (${mode?.blockMode}, ${mode?.blockedApps?.size} apps, nfc=${mode?.nfcTagIds?.ifEmpty { listOf("any") }}, timed=${timedUntilMillis != null})")
                viewModelScope.launch { mutate { result.newState } }
                ActivationResult.SUCCESS
            }
        }
    }

    fun deactivateMode(modeId: String) {
        val modeName = repo.current.modes.find { it.id == modeId }?.name ?: "unknown"
        AppLogger.log("MODE", "Deactivating: '$modeName' (id=$modeId)")
        viewModelScope.launch {
            mutate { ModeActivationLogic.applyModeDeactivation(it, modeId).newState }
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
            val newTag = NfcTag(id = tagId, name = name)
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

    /**
     * Link [tagId] so that tapping it unlocks [modeIds], and only [modeIds].
     *
     * `Mode.nfcTagIds` is the single source of truth for this relationship —
     * the mode editor writes it from one side and the tag screen from the
     * other, so this walks every mode and adds or removes the tag to match.
     *
     * A newly linked tag gets no `tagUnlockLimits` entry, i.e. permanent
     * unlock. That is deliberate: it is the permissive default, and it keeps
     * `ModeEditorScreen`'s "no permanent unlock way" guard satisfiable rather
     * than tripping it behind the user's back.
     */
    fun setModesForTag(tagId: String, modeIds: Set<String>) {
        viewModelScope.launch {
            mutate { state ->
                state.copy(
                    modes = state.modes.map { mode ->
                        val shouldLink = mode.id in modeIds
                        val isLinked = mode.nfcTagIds.contains(tagId)
                        when {
                            shouldLink && !isLinked ->
                                mode.copy(nfcTagIds = mode.nfcTagIds + tagId)
                            !shouldLink && isLinked ->
                                mode.copy(
                                    nfcTagIds = mode.nfcTagIds.filter { it != tagId },
                                    tagUnlockLimits = mode.tagUnlockLimits - tagId
                                )
                            else -> mode
                        }
                    }
                )
            }
        }
    }

    /**
     * Add [tagIds] to every mode the schedule switches on.
     *
     * A schedule has no tag relationship of its own — it activates modes, and
     * modes are what tags unlock. This is the shortcut that spares the user
     * opening each linked mode in turn; it only ever adds, so a tag already
     * linked to one of the modes is left alone along with its unlock limit.
     */
    fun linkTagsToScheduleModes(scheduleId: String, tagIds: Set<String>) {
        if (tagIds.isEmpty()) return
        viewModelScope.launch {
            mutate { state ->
                val modeIds = state.schedules
                    .find { it.id == scheduleId }?.linkedModeIds.orEmpty().toSet()
                state.copy(
                    modes = state.modes.map { mode ->
                        if (mode.id in modeIds) {
                            mode.copy(nfcTagIds = (mode.nfcTagIds + tagIds).distinct())
                        } else {
                            mode
                        }
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
                        if (mode.nfcTagIds.contains(tagId)) mode.copy(
                            nfcTagIds = mode.nfcTagIds.filter { it != tagId }
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
            // Per-mode alarms (cancel deactivations for the unlocked modes,
            // schedule reactivations if temporary) are diffed by StateSyncer.
        }
    }

    /** User dismissed the unlock dialog — do nothing, modes stay active */
    fun dismissUnlock() {
        _pendingUnlock.value = null
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
                    val restoredAt = result.restoredDeactivationAt
                    if (restoredAt != null) {
                        val remainingMs = restoredAt - now
                        AppLogger.log("TIMER", "Restoring timed deactivation for '${mode?.name}': ${remainingMs / 60000}m remaining")
                    }
                }
            }

            mutate { result.newState }
            // Restored deactivation alarm is diffed from state by StateSyncer.
        }
    }

    fun importConfig(data: ConfigManager.ExportData, mergeMode: Boolean = false) {
        viewModelScope.launch {
            mutate { state ->
                // ConfigManager has already normalized data.modes (legacy
                // nfcTagId consolidated into nfcTagIds), so no per-mode
                // normalization needed here.
                val afterImport = if (mergeMode) {
                    val importModeMap = data.modes.associateBy { it.id }
                    val importScheduleMap = data.schedules.associateBy { it.id }
                    val importTagMap = data.nfcTags.associateBy { it.id }
                    val existingModeIds = state.modes.map { it.id }.toSet()
                    val existingScheduleIds = state.schedules.map { it.id }.toSet()
                    val existingTagIds = state.nfcTags.map { it.id }.toSet()

                    // For existing items: fully replace with imported version.
                    // For new items: append.
                    val mergedModes = state.modes.map { existing -> importModeMap[existing.id] ?: existing } +
                        data.modes.filter { it.id !in existingModeIds }
                    val mergedSchedules = state.schedules.map { existing -> importScheduleMap[existing.id] ?: existing } +
                        data.schedules.filter { it.id !in existingScheduleIds }
                    val mergedTags = state.nfcTags.map { existing -> importTagMap[existing.id] ?: existing } +
                        data.nfcTags.filter { it.id !in existingTagIds }

                    state.copy(modes = mergedModes, schedules = mergedSchedules, nfcTags = mergedTags)
                } else {
                    // Replace: overwrite all config, reset runtime state.
                    state.copy(
                        modes = data.modes,
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

                // Clean up orphaned tag references after import (a mode might
                // reference a tag id that no longer exists in nfcTags).
                val validTagIds = afterImport.nfcTags.map { it.id }.toSet()
                afterImport.copy(
                    modes = afterImport.modes.map { mode ->
                        val cleaned = mode.nfcTagIds.filter { it in validTagIds || it == "ANY" }
                        val cleanedLimits = mode.tagUnlockLimits.filterKeys { it in validTagIds || it == "ANY" }
                        if (cleaned != mode.nfcTagIds || cleanedLimits != mode.tagUnlockLimits) {
                            mode.copy(nfcTagIds = cleaned, tagUnlockLimits = cleanedLimits)
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
                viewModelScope.launch { mutate { result.newState } }
                ActivationResult.SUCCESS
            }
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

    companion object {
        /**
         * Floor for the anti-bypass challenge, in seconds.
         *
         * Separate from [CHALLENGE_DEFAULT_SECONDS]: the default is what a new
         * install gets, the floor is how far a user may wind it down. They were
         * one constant, which meant lowering the floor would also have lowered
         * what everybody starts on.
         */
        const val CHALLENGE_MIN_SECONDS = 60

        /** What a fresh install starts with. */
        const val CHALLENGE_DEFAULT_SECONDS = 90
    }

}
