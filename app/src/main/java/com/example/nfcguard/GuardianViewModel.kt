package com.example.nfcguard

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
    val nfcTagId: String? = null
)

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
    val activeModes: Set<String> = emptySet()
)

class GuardianViewModel : ViewModel() {
    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState

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

        // Don't spam service starts - only ensure it's running
        ensureServiceRunning()


        viewModelScope.launch {
            while (isActive) {
                delay(5000)  // Check every 5 seconds
                loadState()
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
    }

    private fun updateBlockerService() {
        val activeModes = _appState.value.modes.filter {
            _appState.value.activeModes.contains(it.id)
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
                BlockerService.start(context, allAllowedApps, BlockMode.ALLOW_SELECTED, activeModes.map { it.id }.toSet())
            } else {
                val allBlockedApps = mutableSetOf<String>()
                activeModes.forEach { mode ->
                    allBlockedApps.addAll(mode.blockedApps)
                }
                BlockerService.start(context, allBlockedApps, BlockMode.BLOCK_SELECTED, activeModes.map { it.id }.toSet())
            }
        } else {
            // Keep service running even with no active modes to handle schedules
            BlockerService.start(context, emptySet(), BlockMode.BLOCK_SELECTED, emptySet())
        }
    }

    fun addMode(name: String, blockedApps: List<String>, blockMode: BlockMode = BlockMode.BLOCK_SELECTED, nfcTagId: String? = null) {
        viewModelScope.launch {
            val newMode = Mode(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                blockedApps = blockedApps,
                blockMode = blockMode,
                nfcTagId = nfcTagId
            )
            _appState.value = _appState.value.copy(
                modes = _appState.value.modes + newMode
            )
            saveState()
        }
    }

    fun updateMode(id: String, name: String, blockedApps: List<String>, blockMode: BlockMode, nfcTagId: String?) {
        viewModelScope.launch {
            _appState.value = _appState.value.copy(
                modes = _appState.value.modes.map { mode ->
                    if (mode.id == id) mode.copy(
                        name = name,
                        blockedApps = blockedApps,
                        blockMode = blockMode,
                        nfcTagId = nfcTagId
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
                }
            )
            saveState()
        }
    }

    fun activateMode(modeId: String) {
        viewModelScope.launch {
            _appState.value = _appState.value.copy(
                activeModes = _appState.value.activeModes + modeId
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

    fun addNfcTag(tagId: String, name: String) {
        viewModelScope.launch {
            if (_appState.value.nfcTags.any { it.id == tagId }) {
                return@launch
            }

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
                    if (mode.nfcTagId == tagId) mode.copy(nfcTagId = null) else mode
                }
            )
            saveState()
        }
    }

    fun handleNfcTag(tagId: String) {
        viewModelScope.launch {
            val calendar = java.util.Calendar.getInstance()
            val today = "${calendar.get(java.util.Calendar.YEAR)}-${calendar.get(java.util.Calendar.DAY_OF_YEAR)}"
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

            _appState.value.activeModes.forEach { modeId ->
                val mode = _appState.value.modes.find { it.id == modeId }

                if (mode != null) {
                    if (mode.nfcTagId != null) {
                        if (mode.nfcTagId == tagId) {
                            modesToDeactivate.add(modeId)

                            _appState.value.schedules.forEach { schedule ->
                                if (schedule.linkedModeIds.contains(modeId)) {
                                    val dayTime = schedule.timeSlot.getTimeForDay(currentDayOfWeek)
                                    if (dayTime != null) {
                                        val startTime = dayTime.startHour * 60 + dayTime.startMinute
                                        if (currentTime >= startTime) {
                                            val scheduleKey = "disabled_${schedule.id}_${currentDayOfWeek}_${dayTime.startHour}_${dayTime.startMinute}_$today"
                                            prefs.edit().putBoolean(scheduleKey, true).apply()
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        modesToDeactivate.add(modeId)

                        _appState.value.schedules.forEach { schedule ->
                            if (schedule.linkedModeIds.contains(modeId)) {
                                val dayTime = schedule.timeSlot.getTimeForDay(currentDayOfWeek)
                                if (dayTime != null) {
                                    val startTime = dayTime.startHour * 60 + dayTime.startMinute
                                    if (currentTime >= startTime) {
                                        val scheduleKey = "disabled_${schedule.id}_${currentDayOfWeek}_${dayTime.startHour}_${dayTime.startMinute}_$today"
                                        prefs.edit().putBoolean(scheduleKey, true).apply()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (modesToDeactivate.isNotEmpty()) {
                _appState.value = _appState.value.copy(
                    activeModes = _appState.value.activeModes - modesToDeactivate
                )
                saveState()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }
}