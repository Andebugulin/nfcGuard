package com.andebugulin.nfcguard

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    val safeRegimeEnabled: Boolean = false
)

/** Pending NFC unlock awaiting user duration choice (not persisted) */
data class PendingUnlock(
    val modeIds: Set<String>,
    val schedulesToDeactivate: Set<String>,
    val tagId: String? = null, // Store which tag was scanned to apply its specific limit
    val maxLimitMinutes: Long? = null // The most restrictive limit among all modes being unlocked
)

/** Result of attempting to activate a mode */
enum class ActivationResult {
    SUCCESS,
    BLOCK_MODE_CONFLICT,
    MODE_NOT_FOUND
}

class GuardianViewModel : ViewModel() {
    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState

    /** Safe Regime — stored separately from AppState to prevent bypass via config import */
    private val _safeRegimeEnabled = MutableStateFlow(true)
    val safeRegimeEnabled: StateFlow<Boolean> = _safeRegimeEnabled

    /** Pending NFC unlock awaiting user duration choice */
    private val _pendingUnlock = MutableStateFlow<PendingUnlock?>(null)
    val pendingUnlock: StateFlow<PendingUnlock?> = _pendingUnlock

    private lateinit var prefs: SharedPreferences
    private lateinit var context: Context
    private val json = Json { ignoreUnknownKeys = true }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "app_state") {
            loadState()
        }
    }

    fun loadData(context: Context) {
        this.context = context
        prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        loadState()

        // Load safe regime preference (default: enabled)
        _safeRegimeEnabled.value = prefs.getBoolean("safe_regime_enabled", true)

        // Don't spam service starts - only ensure it's running
        ensureServiceRunning()


        viewModelScope.launch {
            while (isActive) {
                delay(5000)  // Check every 5 seconds
                loadState()
                checkTimedDeactivations()
                checkTimedReactivations()
            }
        }
    }

    private fun ensureServiceRunning() {
        // Only start service if there are active modes OR schedules
        val currentState = _appState.value
        if (currentState.activeModes.isNotEmpty() || currentState.schedules.isNotEmpty()) {
            if (!BlockerService.isRunning()) {
                updateBlockerService()
            }
        }
    }

    fun setSafeRegimeEnabled(enabled: Boolean) {
        _safeRegimeEnabled.value = enabled
        prefs.edit().putBoolean("safe_regime_enabled", enabled).apply()
    }

    private fun loadState() {
        val stateJson = prefs.getString("app_state", null)
        if (stateJson != null) {
            try {
                val newState = json.decodeFromString<AppState>(stateJson)
                if (newState != _appState.value) {
                    _appState.value = newState
                    // Only update service if state actually changed
                    updateBlockerService()
                }
            } catch (e: Exception) {
                _appState.value = AppState()
            }
        }
    }

    private fun saveState() {
        val stateJson = json.encodeToString(_appState.value)
        prefs.edit().putString("app_state", stateJson).apply()
        updateBlockerService()

        // IMPORTANT: Reschedule alarms when state changes
        ScheduleAlarmReceiver.scheduleAllUpcomingAlarms(context)

        // Refresh home screen widget
        GuardianWidget.notifyAllWidgets(context)
    }

    private fun updateBlockerService() {
        val activeModes = _appState.value.modes.filter {
            _appState.value.activeModes.contains(it.id)
        }

        val modeNamesMap = _appState.value.modes.associate { it.id to it.name }

        AppLogger.log("SERVICE", "updateBlockerService: ${activeModes.size} active modes, ids=${_appState.value.activeModes}")

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
                    manuallyActivatedModeIds = _appState.value.manuallyActivatedModes,
                    timedModeDeactivations = _appState.value.timedModeDeactivations,
                    modeNames = modeNamesMap,
                    timedModeReactivations = _appState.value.timedModeReactivations
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
                    manuallyActivatedModeIds = _appState.value.manuallyActivatedModes,
                    timedModeDeactivations = _appState.value.timedModeDeactivations,
                    modeNames = modeNamesMap,
                    timedModeReactivations = _appState.value.timedModeReactivations
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
                timedModeReactivations = _appState.value.timedModeReactivations
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
            _appState.value = _appState.value.copy(
                modes = _appState.value.modes + newMode
            )
            saveState()
        }
    }

    fun updateMode(id: String, name: String, blockedApps: List<String>, blockMode: BlockMode, nfcTagIds: List<String>, tagUnlockLimits: Map<String, Long?> = emptyMap()) {
        viewModelScope.launch {
            _appState.value = _appState.value.copy(
                modes = _appState.value.modes.map { mode ->
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
            saveState()
        }
    }

    fun deleteMode(id: String) {
        viewModelScope.launch {
            _appState.value = _appState.value.copy(
                modes = _appState.value.modes.filter { it.id != id },
                activeModes = _appState.value.activeModes - id,
                schedules = _appState.value.schedules.map { schedule ->
                    schedule.copy(linkedModeIds = schedule.linkedModeIds.filter { it != id })
                },
                nfcTags = _appState.value.nfcTags.map { tag ->
                    tag.copy(linkedModeIds = tag.linkedModeIds.filter { it != id })
                },
                manuallyActivatedModes = _appState.value.manuallyActivatedModes - id,
                timedModeDeactivations = _appState.value.timedModeDeactivations - id,
                timedModeReactivations = _appState.value.timedModeReactivations - id
            )
            cancelTimedDeactivation(id)
            cancelTimedReactivation(id)
            saveState()
        }
    }

    // FIX #2: Returns ActivationResult to indicate success or conflict
    fun activateMode(modeId: String, timedUntilMillis: Long? = null): ActivationResult {
        val currentState = _appState.value
        val modeToActivate = currentState.modes.find { it.id == modeId }
            ?: return ActivationResult.MODE_NOT_FOUND

        // Check for BLOCK/ALLOW conflict with currently active modes
        val currentlyActiveModes = currentState.modes.filter { currentState.activeModes.contains(it.id) }
        if (currentlyActiveModes.isNotEmpty()) {
            val hasConflict = currentlyActiveModes.any { it.blockMode != modeToActivate.blockMode }
            if (hasConflict) {
                AppLogger.log("MODE", "CONFLICT: Cannot activate '${modeToActivate.name}' (${modeToActivate.blockMode}) — conflicts with active modes")
                return ActivationResult.BLOCK_MODE_CONFLICT
            }
        }

        AppLogger.log("MODE", "Activating: '${modeToActivate.name}' (${modeToActivate.blockMode}, ${modeToActivate.blockedApps.size} apps, nfc=${modeToActivate.effectiveNfcTagIds.ifEmpty { listOf("any") }}, timed=${timedUntilMillis != null})")

        viewModelScope.launch {
            val newTimedDeactivations = if (timedUntilMillis != null) {
                currentState.timedModeDeactivations + (modeId to timedUntilMillis)
            } else {
                currentState.timedModeDeactivations
            }
            _appState.value = currentState.copy(
                activeModes = currentState.activeModes + modeId,
                manuallyActivatedModes = currentState.manuallyActivatedModes + modeId,
                timedModeDeactivations = newTimedDeactivations,
                timedModeReactivations = currentState.timedModeReactivations - modeId
            )
            saveState()

            // Cancel any pending reactivation alarm — mode is active now
            cancelTimedReactivation(modeId)

            // Schedule timed deactivation alarm for reliability
            if (timedUntilMillis != null) {
                scheduleTimedDeactivation(modeId, timedUntilMillis)
            }
        }
        return ActivationResult.SUCCESS
    }

    fun deactivateMode(modeId: String) {
        viewModelScope.launch {
            val currentState = _appState.value
            val modeName = currentState.modes.find { it.id == modeId }?.name ?: "unknown"
            AppLogger.log("MODE", "Deactivating: '$modeName' (id=$modeId)")

            // Find schedules that linked this mode and are currently active
            val schedulesToMark = currentState.schedules
                .filter { schedule ->
                    schedule.linkedModeIds.contains(modeId) &&
                            currentState.activeSchedules.contains(schedule.id)
                }
                .map { it.id }
                .toSet()

            // Only deactivate schedule if ALL its linked modes will be inactive
            val schedulesToDeactivate = schedulesToMark.filter { scheduleId ->
                val schedule = currentState.schedules.find { it.id == scheduleId }
                schedule?.linkedModeIds?.all { linkedModeId ->
                    linkedModeId == modeId || !currentState.activeModes.contains(linkedModeId)
                } ?: true
            }.toSet()

            _appState.value = currentState.copy(
                activeModes = currentState.activeModes - modeId,
                activeSchedules = currentState.activeSchedules - schedulesToDeactivate,
                deactivatedSchedules = currentState.deactivatedSchedules + schedulesToDeactivate,
                manuallyActivatedModes = currentState.manuallyActivatedModes - modeId,
                timedModeDeactivations = currentState.timedModeDeactivations - modeId,
                timedModeReactivations = currentState.timedModeReactivations - modeId
            )
            saveState()

            // Cancel timed deactivation alarm if any
            cancelTimedDeactivation(modeId)
            // Cancel timed reactivation alarm if any
            cancelTimedReactivation(modeId)
        }
    }

    fun markScheduleDeactivated(scheduleId: String) {
        viewModelScope.launch {
            _appState.value = _appState.value.copy(
                deactivatedSchedules = _appState.value.deactivatedSchedules + scheduleId
            )
            saveState()
        }
    }

    fun clearScheduleDeactivation(scheduleId: String) {
        viewModelScope.launch {
            _appState.value = _appState.value.copy(
                deactivatedSchedules = _appState.value.deactivatedSchedules - scheduleId
            )
            saveState()
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
            _appState.value = _appState.value.copy(
                schedules = _appState.value.schedules + newSchedule
            )
            saveState()
        }
    }

    fun updateSchedule(id: String, name: String, timeSlot: TimeSlot, linkedModeIds: List<String>, hasEndTime: Boolean) {
        viewModelScope.launch {
            _appState.value = _appState.value.copy(
                schedules = _appState.value.schedules.map { schedule ->
                    if (schedule.id == id) schedule.copy(
                        name = name,
                        timeSlot = timeSlot,
                        linkedModeIds = linkedModeIds,
                        hasEndTime = hasEndTime
                    ) else schedule
                }
            )
            saveState()
        }
    }

    fun deleteSchedule(id: String) {
        viewModelScope.launch {
            _appState.value = _appState.value.copy(
                schedules = _appState.value.schedules.filter { it.id != id }
            )
            saveState()
        }
    }

    // FIX #3: Returns false if tag already registered
    fun addNfcTag(tagId: String, name: String): Boolean {
        if (_appState.value.nfcTags.any { it.id == tagId }) {
            return false
        }
        viewModelScope.launch {
            val newTag = NfcTag(
                id = tagId,
                name = name,
                linkedModeIds = emptyList()
            )
            _appState.value = _appState.value.copy(
                nfcTags = _appState.value.nfcTags + newTag
            )
            saveState()
        }
        return true
    }

    fun updateNfcTag(tagId: String, name: String) {
        viewModelScope.launch {
            _appState.value = _appState.value.copy(
                nfcTags = _appState.value.nfcTags.map { tag ->
                    if (tag.id == tagId) tag.copy(name = name) else tag
                }
            )
            saveState()
        }
    }

    fun deleteNfcTag(tagId: String) {
        viewModelScope.launch {
            _appState.value = _appState.value.copy(
                nfcTags = _appState.value.nfcTags.filter { it.id != tagId },
                modes = _appState.value.modes.map { mode ->
                    if (mode.effectiveNfcTagIds.contains(tagId)) mode.copy(
                        nfcTagId = null,
                        nfcTagIds = mode.effectiveNfcTagIds.filter { it != tagId }
                    ) else mode
                }
            )
            saveState()
        }
    }

    fun handleNfcTag(tagId: String) {
        viewModelScope.launch {
            AppLogger.log("NFC", "handleNfcTag: tagId=$tagId, activeModes=${_appState.value.activeModes}")
            val calendar = java.util.Calendar.getInstance()
            val currentDayOfWeek = when (calendar.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> 1
                java.util.Calendar.TUESDAY -> 2
                java.util.Calendar.WEDNESDAY -> 3
                java.util.Calendar.THURSDAY -> 4
                java.util.Calendar.FRIDAY -> 5
                java.util.Calendar.SATURDAY -> 6
                java.util.Calendar.SUNDAY -> 7
                else -> 1
            }
            val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
            val currentMinute = calendar.get(java.util.Calendar.MINUTE)
            val currentTime = currentHour * 60 + currentMinute

            val modesToDeactivate = mutableSetOf<String>()
            val schedulesToDeactivate = mutableSetOf<String>()
            
            var aggregateLimit: Long? = null
            var limitInitialized = false

            _appState.value.activeModes.forEach { modeId ->
                val mode = _appState.value.modes.find { it.id == modeId }

                if (mode != null) {
                    val tagIds = mode.effectiveNfcTagIds
                    // Check if tag matches specific tag OR any tag wildcard "ANY"
                    val tagMatches = tagIds.contains(tagId) || tagIds.contains("ANY")
                    
                    if (tagIds.isNotEmpty()) {
                        if (tagMatches) {
                            modesToDeactivate.add(modeId)

                            // Track the most restrictive limit among all modes being unlocked
                            val modeLimit = mode.getLimitForTag(tagId)
                            if (!limitInitialized) {
                                aggregateLimit = modeLimit
                                limitInitialized = true
                            } else {
                                if (modeLimit != null) {
                                    aggregateLimit = if (aggregateLimit == null) modeLimit else minOf(aggregateLimit!!, modeLimit)
                                }
                            }

                            _appState.value.schedules.forEach { schedule ->
                                if (schedule.linkedModeIds.contains(modeId)) {
                                    val dayTime = schedule.timeSlot.getTimeForDay(currentDayOfWeek)
                                    if (dayTime != null) {
                                        val startTime = dayTime.startHour * 60 + dayTime.startMinute
                                        if (currentTime >= startTime) {
                                            if (_appState.value.activeSchedules.contains(schedule.id)) {
                                                schedulesToDeactivate.add(schedule.id)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // No NFC tags linked — any tag can unlock this mode (legacy behavior)
                        modesToDeactivate.add(modeId)

                        _appState.value.schedules.forEach { schedule ->
                            if (schedule.linkedModeIds.contains(modeId)) {
                                val dayTime = schedule.timeSlot.getTimeForDay(currentDayOfWeek)
                                if (dayTime != null) {
                                    val startTime = dayTime.startHour * 60 + dayTime.startMinute
                                    if (currentTime >= startTime) {
                                        if (_appState.value.activeSchedules.contains(schedule.id)) {
                                            schedulesToDeactivate.add(schedule.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (modesToDeactivate.isNotEmpty()) {
                AppLogger.log("NFC", "Pending unlock: modes=$modesToDeactivate, schedules=$schedulesToDeactivate, limit=$aggregateLimit")
                _pendingUnlock.value = PendingUnlock(
                    modeIds = modesToDeactivate,
                    schedulesToDeactivate = schedulesToDeactivate,
                    tagId = tagId,
                    maxLimitMinutes = aggregateLimit
                )
            }
        }
    }

    /** User confirmed unlock duration from dialog. null = permanent, otherwise epoch millis to reactivate. */
    fun confirmUnlock(reactivateAtMillis: Long? = null) {
        val pending = _pendingUnlock.value ?: return
        _pendingUnlock.value = null

        viewModelScope.launch {
            AppLogger.log("NFC", "Confirming unlock: modes=${pending.modeIds}, reactivate=${reactivateAtMillis != null}")

            val newReactivations = if (reactivateAtMillis != null) {
                _appState.value.timedModeReactivations + pending.modeIds.associateWith { reactivateAtMillis }
            } else {
                _appState.value.timedModeReactivations
            }

            _appState.value = _appState.value.copy(
                activeModes = _appState.value.activeModes - pending.modeIds,
                activeSchedules = _appState.value.activeSchedules - pending.schedulesToDeactivate,
                deactivatedSchedules = _appState.value.deactivatedSchedules + pending.schedulesToDeactivate,
                manuallyActivatedModes = _appState.value.manuallyActivatedModes - pending.modeIds,
                timedModeDeactivations = _appState.value.timedModeDeactivations - pending.modeIds,
                timedModeReactivations = newReactivations
            )
            saveState()

            // Cancel any timed deactivation alarms for unlocked modes
            pending.modeIds.forEach { cancelTimedDeactivation(it) }

            // Schedule reactivation alarms if timed
            if (reactivateAtMillis != null) {
                pending.modeIds.forEach { modeId ->
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
        val currentState = _appState.value
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
            val currentState = _appState.value
            val mode = currentState.modes.find { it.id == modeId } ?: return@launch
            if (currentState.activeModes.contains(modeId)) {
                // Already active (e.g. schedule re-activated it), just clean up the reactivation entry
                _appState.value = currentState.copy(
                    timedModeReactivations = currentState.timedModeReactivations - modeId
                )
                saveState()
                return@launch
            }

            // Check for BLOCK/ALLOW conflict before reactivating
            val currentlyActiveModes = currentState.modes.filter { currentState.activeModes.contains(it.id) }
            if (currentlyActiveModes.isNotEmpty() && currentlyActiveModes.any { it.blockMode != mode.blockMode }) {
                AppLogger.log("TIMER", "Reactivation conflict for '${mode.name}' — skipping, clearing timer")
                _appState.value = currentState.copy(
                    timedModeReactivations = currentState.timedModeReactivations - modeId
                )
                saveState()
                return@launch
            }

            AppLogger.log("TIMER", "Reactivating mode '${mode.name}' after timed unlock")
            _appState.value = currentState.copy(
                activeModes = currentState.activeModes + modeId,
                timedModeReactivations = currentState.timedModeReactivations - modeId
            )
            saveState()
        }
    }

    fun importConfig(data: ConfigManager.ExportData, mergeMode: Boolean = false) {
        viewModelScope.launch {
            if (mergeMode) {
                // Build lookup maps from import data
                val importModeMap = data.modes.associateBy { it.id }
                val importScheduleMap = data.schedules.associateBy { it.id }
                val importTagMap = data.nfcTags.associateBy { it.id }

                val existingModeIds = _appState.value.modes.map { it.id }.toSet()
                val existingScheduleIds = _appState.value.schedules.map { it.id }.toSet()
                val existingTagIds = _appState.value.nfcTags.map { it.id }.toSet()

                // For existing items: restore cross-references from import
                // For new items: add them
                val mergedModes = _appState.value.modes.map { existing ->
                    val imported = importModeMap[existing.id]
                    if (imported != null) {
                        // Restore NFC tag links and limits from import
                        existing.copy(
                            nfcTagId = null, 
                            nfcTagIds = imported.effectiveNfcTagIds,
                            tagUnlockLimits = imported.tagUnlockLimits
                        )
                    } else existing
                } + data.modes.filter { it.id !in existingModeIds }

                val mergedSchedules = _appState.value.schedules.map { existing ->
                    val imported = importScheduleMap[existing.id]
                    if (imported != null) {
                        // Restore linked mode IDs from import
                        existing.copy(linkedModeIds = imported.linkedModeIds)
                    } else existing
                } + data.schedules.filter { it.id !in existingScheduleIds }

                val mergedTags = _appState.value.nfcTags.map { existing ->
                    val imported = importTagMap[existing.id]
                    if (imported != null) {
                        // Restore linked mode IDs from import
                        existing.copy(linkedModeIds = imported.linkedModeIds)
                    } else existing
                } + data.nfcTags.filter { it.id !in existingTagIds }

                _appState.value = _appState.value.copy(
                    modes = mergedModes,
                    schedules = mergedSchedules,
                    nfcTags = mergedTags
                )
            } else {
                // Replace: overwrite all config, reset runtime state
                _appState.value = _appState.value.copy(
                    modes = data.modes,
                    schedules = data.schedules,
                    nfcTags = data.nfcTags,
                    activeModes = emptySet(),
                    activeSchedules = emptySet(),
                    deactivatedSchedules = emptySet(),
                    manuallyActivatedModes = emptySet(),
                    timedModeDeactivations = emptyMap(),
                    timedModeReactivations = emptyMap()
                )
            }

            // FIX #11: Clean up orphaned nfcTagIds references after import
            val validTagIds = _appState.value.nfcTags.map { it.id }.toSet()
            _appState.value = _appState.value.copy(
                modes = _appState.value.modes.map { mode ->
                    val cleaned = mode.effectiveNfcTagIds.filter { it in validTagIds || it == "ANY" }
                    if (cleaned != mode.effectiveNfcTagIds) {
                        mode.copy(nfcTagId = null, nfcTagIds = cleaned)
                    } else mode
                }
            )

            saveState()
        }
    }

    /** Activate a schedule manually from the SchedulesScreen.
     *  This activates the schedule's linked modes and marks the schedule as active,
     *  so the end-alarm will properly deactivate everything. */
    fun activateScheduleManually(scheduleId: String): ActivationResult {
        val currentState = _appState.value
        val schedule = currentState.schedules.find { it.id == scheduleId }
            ?: return ActivationResult.MODE_NOT_FOUND

        // Check for BLOCK/ALLOW conflict
        val currentlyActiveModes = currentState.modes.filter { currentState.activeModes.contains(it.id) }
        val modesToActivate = schedule.linkedModeIds.mapNotNull { modeId ->
            currentState.modes.find { it.id == modeId }
        }
        if (currentlyActiveModes.isNotEmpty() && modesToActivate.isNotEmpty()) {
            val hasConflict = modesToActivate.any { newMode ->
                currentlyActiveModes.any { it.blockMode != newMode.blockMode }
            }
            if (hasConflict) {
                return ActivationResult.BLOCK_MODE_CONFLICT
            }
        }

        AppLogger.log("SCHEDULE", "Manually activating schedule '${schedule.name}' with ${schedule.linkedModeIds.size} modes")

        viewModelScope.launch {
            _appState.value = currentState.copy(
                activeModes = currentState.activeModes + schedule.linkedModeIds.toSet(),
                activeSchedules = currentState.activeSchedules + scheduleId,
                deactivatedSchedules = currentState.deactivatedSchedules - scheduleId
            )
            saveState()
        }
        return ActivationResult.SUCCESS
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
        val currentState = _appState.value
        if (currentState.timedModeDeactivations.isEmpty()) return

        val now = System.currentTimeMillis()
        val expired = currentState.timedModeDeactivations.filter { (_, deadline) -> now >= deadline }
        if (expired.isNotEmpty()) {
            AppLogger.log("TIMER", "Timed deactivation: ${expired.keys}")
            expired.keys.forEach { modeId -> deactivateMode(modeId) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }
}
