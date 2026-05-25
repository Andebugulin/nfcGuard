package com.andebugulin.nfcguard

import kotlinx.serialization.Serializable

/**
 * Domain data types. All `@Serializable` so the whole [AppState] graph
 * can be persisted by `AppStateRepository` and exported by `ConfigManager`
 * without per-class serializers.
 *
 * Pure Kotlin — no Android imports. These types are the prerequisite
 * for moving the state machine into a `:domain` Gradle module.
 */

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
    /** tagId -> max unlock duration in minutes (null = permanent). Key "ANY" matches any tag. */
    val tagUnlockLimits: Map<String, Long?> = emptyMap()
) {
    /** Resolved tag list: migrates the legacy single-tag field automatically. */
    val effectiveNfcTagIds: List<String>
        get() = if (nfcTagIds.isNotEmpty()) nfcTagIds
        else if (@Suppress("DEPRECATION") nfcTagId != null) listOf(@Suppress("DEPRECATION") nfcTagId!!)
        else emptyList()

    /** Returns the limit for a specific tag, prioritizing exact match over the "ANY" wildcard. */
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
    /** Schedules that activated their modes. */
    val activeSchedules: Set<String> = emptySet(),
    /** Schedules manually deactivated by the user (won't auto-reactivate this cycle). */
    val deactivatedSchedules: Set<String> = emptySet(),
    /** Modes activated by user tap (not by schedule). */
    val manuallyActivatedModes: Set<String> = emptySet(),
    /** modeId -> epoch millis when it should auto-deactivate. */
    val timedModeDeactivations: Map<String, Long> = emptyMap(),
    /** modeId -> epoch millis when it should auto-reactivate after NFC unlock. */
    val timedModeReactivations: Map<String, Long> = emptyMap(),
    /** modeId -> remaining deactivation ms saved when mode was paused by NFC unlock. */
    val pausedModeRemainingMs: Map<String, Long> = emptyMap()
)

/** Pending NFC unlock awaiting user duration choice. Not persisted; UI-only. */
data class PendingUnlock(
    val modeIds: Set<String>,
    val schedulesToDeactivate: Set<String>,
    /** Tag that was scanned, used to apply its specific limit. */
    val tagId: String? = null,
    /** Most restrictive limit among all modes being unlocked. */
    val maxLimitMinutes: Long? = null,
    /** Per-mode limit: modeId -> limit (null = permanent). */
    val modeLimits: Map<String, Long?> = emptyMap()
)

/** Result of attempting to activate a mode from the UI. */
enum class ActivationResult {
    SUCCESS,
    BLOCK_MODE_CONFLICT,
    MODE_NOT_FOUND
}
