package com.andebugulin.nfcguard

import java.util.Calendar

/**
 * Pure transformation logic for the NFC unlock state machine.
 *
 * Side effects (alarm scheduling, prefs writes, logging) stay in the
 * ViewModel; everything here is `(state, args) -> state` so it can be
 * unit-tested without Android.
 */
object NfcUnlockLogic {

    /** Schedule's day-of-week format: 1=Monday .. 7=Sunday. */
    fun calendarDayToScheduleDay(calendarDay: Int): Int = when (calendarDay) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        Calendar.SUNDAY -> 7
        else -> 1
    }

    /**
     * Decide which active modes a scanned tag can unlock and which active
     * schedules should be marked deactivated.
     *
     * Returns null if no active mode matches — caller should leave any
     * existing pending unlock untouched (matches legacy behavior).
     */
    fun computePendingUnlock(
        state: AppState,
        tagId: String,
        currentDayOfWeek: Int,
        currentMinuteOfDay: Int
    ): PendingUnlock? {
        val modesToDeactivate = mutableSetOf<String>()
        val schedulesToDeactivate = mutableSetOf<String>()
        val perModeLimits = mutableMapOf<String, Long?>()

        var aggregateLimit: Long? = null
        var limitInitialized = false

        state.activeModes.forEach { modeId ->
            val mode = state.modes.find { it.id == modeId } ?: return@forEach
            val tagIds = mode.effectiveNfcTagIds

            if (tagIds.isNotEmpty()) {
                val tagMatches = tagIds.contains(tagId) || tagIds.contains("ANY")
                if (!tagMatches) return@forEach
                modesToDeactivate.add(modeId)
                val modeLimit = mode.getLimitForTag(tagId)
                perModeLimits[modeId] = modeLimit
                if (!limitInitialized) {
                    aggregateLimit = modeLimit
                    limitInitialized = true
                } else if (modeLimit != null) {
                    aggregateLimit = if (aggregateLimit == null) modeLimit else minOf(aggregateLimit!!, modeLimit)
                }
            } else {
                // No tags linked: any tag unlocks (legacy behavior), no limit
                modesToDeactivate.add(modeId)
                perModeLimits[modeId] = null
            }

            // Mark active schedules linked to this mode that have already started today
            state.schedules.forEach inner@{ schedule ->
                if (!schedule.linkedModeIds.contains(modeId)) return@inner
                if (!state.activeSchedules.contains(schedule.id)) return@inner
                val dayTime = schedule.timeSlot.getTimeForDay(currentDayOfWeek) ?: return@inner
                val startTime = dayTime.startHour * 60 + dayTime.startMinute
                if (currentMinuteOfDay >= startTime) {
                    schedulesToDeactivate.add(schedule.id)
                }
            }
        }

        if (modesToDeactivate.isEmpty()) return null

        return PendingUnlock(
            modeIds = modesToDeactivate,
            schedulesToDeactivate = schedulesToDeactivate,
            tagId = tagId,
            maxLimitMinutes = aggregateLimit,
            modeLimits = perModeLimits
        )
    }

    /**
     * New state plus the modes actually unlocked. Caller schedules
     * reactivation alarms / cancels deactivation alarms based on
     * [unlockedModeIds].
     */
    data class UnlockResult(
        val newState: AppState,
        val unlockedModeIds: Set<String>
    )

    /**
     * Apply user-confirmed unlock to state.
     *
     * - [selectedModeIds] = null means unlock all modes in pending.
     * - [reactivateAtMillis] = null means permanent unlock.
     * - Modes with a running timed deactivation get their remaining time
     *   stashed in `pausedModeRemainingMs` to be restored on reactivation.
     * - A schedule is deactivated only if ALL its linked modes will be
     *   inactive after this unlock.
     */
    fun applyUnlock(
        state: AppState,
        pending: PendingUnlock,
        selectedModeIds: Set<String>?,
        reactivateAtMillis: Long?,
        now: Long,
        currentDayOfWeek: Int,
        currentMinuteOfDay: Int
    ): UnlockResult {
        val modeIdsToUnlock = selectedModeIds ?: pending.modeIds

        val schedulesToDeactivate = mutableSetOf<String>()
        modeIdsToUnlock.forEach { modeId ->
            state.schedules.forEach inner@{ schedule ->
                if (!schedule.linkedModeIds.contains(modeId)) return@inner
                if (!state.activeSchedules.contains(schedule.id)) return@inner
                val dayTime = schedule.timeSlot.getTimeForDay(currentDayOfWeek) ?: return@inner
                val startTime = dayTime.startHour * 60 + dayTime.startMinute
                if (currentMinuteOfDay < startTime) return@inner
                val allLinkedWillBeInactive = schedule.linkedModeIds.all { linkedId ->
                    modeIdsToUnlock.contains(linkedId) || !state.activeModes.contains(linkedId)
                }
                if (allLinkedWillBeInactive) {
                    schedulesToDeactivate.add(schedule.id)
                }
            }
        }

        val newReactivations = if (reactivateAtMillis != null) {
            state.timedModeReactivations + modeIdsToUnlock.associateWith { reactivateAtMillis }
        } else {
            state.timedModeReactivations
        }

        val newPausedRemaining = state.pausedModeRemainingMs.toMutableMap()
        modeIdsToUnlock.forEach { modeId ->
            val deadline = state.timedModeDeactivations[modeId] ?: return@forEach
            val remaining = (deadline - now).coerceAtLeast(0)
            if (remaining > 0) newPausedRemaining[modeId] = remaining
        }

        val newState = state.copy(
            activeModes = state.activeModes - modeIdsToUnlock,
            activeSchedules = state.activeSchedules - schedulesToDeactivate,
            deactivatedSchedules = state.deactivatedSchedules + schedulesToDeactivate,
            manuallyActivatedModes = state.manuallyActivatedModes - modeIdsToUnlock,
            timedModeDeactivations = state.timedModeDeactivations - modeIdsToUnlock,
            timedModeReactivations = newReactivations,
            pausedModeRemainingMs = newPausedRemaining
        )

        return UnlockResult(newState, modeIdsToUnlock)
    }

    sealed class ReactivationResult {
        abstract val newState: AppState

        data class ModeNotFound(override val newState: AppState) : ReactivationResult()
        data class AlreadyActive(override val newState: AppState) : ReactivationResult()
        data class Conflict(override val newState: AppState, val modeName: String) : ReactivationResult()

        /**
         * Mode reactivated. If [restoredDeactivationAt] is non-null, the
         * caller should schedule a deactivation alarm at that epoch millis
         * (mode had a running user timer when it was paused).
         */
        data class Reactivated(
            override val newState: AppState,
            val restoredDeactivationAt: Long?
        ) : ReactivationResult()
    }

    /**
     * Reactivate a mode whose temporary unlock has expired. Restores any
     * paused timed deactivation and any schedules that were deactivated
     * when the mode was paused.
     */
    fun applyReactivation(
        state: AppState,
        modeId: String,
        now: Long
    ): ReactivationResult {
        val withoutReactivationEntry = state.copy(
            timedModeReactivations = state.timedModeReactivations - modeId
        )

        val mode = state.modes.find { it.id == modeId }
            ?: return ReactivationResult.ModeNotFound(withoutReactivationEntry)

        if (state.activeModes.contains(modeId)) {
            return ReactivationResult.AlreadyActive(withoutReactivationEntry)
        }

        val currentlyActive = state.modes.filter { state.activeModes.contains(it.id) }
        if (currentlyActive.isNotEmpty() && currentlyActive.any { it.blockMode != mode.blockMode }) {
            return ReactivationResult.Conflict(withoutReactivationEntry, mode.name)
        }

        val remainingMs = state.pausedModeRemainingMs[modeId]
        val restoredDeadline = if (remainingMs != null && remainingMs > 0) now + remainingMs else null
        val newTimedDeactivations = if (restoredDeadline != null) {
            state.timedModeDeactivations + (modeId to restoredDeadline)
        } else state.timedModeDeactivations

        val schedulesToRestore = state.schedules
            .filter { schedule ->
                schedule.linkedModeIds.contains(modeId) &&
                    state.deactivatedSchedules.contains(schedule.id)
            }
            .map { it.id }
            .toSet()

        val newState = state.copy(
            activeModes = state.activeModes + modeId,
            timedModeReactivations = state.timedModeReactivations - modeId,
            timedModeDeactivations = newTimedDeactivations,
            pausedModeRemainingMs = state.pausedModeRemainingMs - modeId,
            activeSchedules = state.activeSchedules + schedulesToRestore,
            deactivatedSchedules = state.deactivatedSchedules - schedulesToRestore
        )

        return ReactivationResult.Reactivated(newState, restoredDeadline)
    }
}
