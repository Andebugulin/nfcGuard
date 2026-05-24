package com.andebugulin.nfcguard

/**
 * Pure transformation logic for schedule alarm transitions.
 *
 * Mirrors the structure of [NfcUnlockLogic]: side effects (alarm
 * scheduling, prefs writes, logging) live in [ScheduleAlarmReceiver];
 * everything here is `(state, args) -> state`.
 */
object ScheduleTransitions {

    // ─── Schedule activation ────────────────────────────────────────────────

    sealed class ScheduleActivationResult {
        abstract val newState: AppState

        /** Schedule with the given id does not exist in state. No-op. */
        data class ScheduleNotFound(override val newState: AppState) : ScheduleActivationResult()

        /**
         * Schedule applied. [activatedModeIds] are the modes that actually
         * activated (after conflict and existence filtering).
         * [conflictSkippedModeIds] is purely informational for the caller's
         * log output — those modes were left untouched.
         */
        data class Applied(
            override val newState: AppState,
            val activatedModeIds: Set<String>,
            val conflictSkippedModeIds: Set<String>
        ) : ScheduleActivationResult()
    }

    /**
     * Activate a schedule's linked modes.
     *
     * For each linked mode: skip if mode is missing, skip if it would
     * conflict (BLOCK vs ALLOW) with currently-active modes. Surviving
     * modes are added to activeModes. The schedule itself is marked
     * active and removed from deactivatedSchedules.
     *
     * Activated modes get their `timedModeReactivations` and
     * `pausedModeRemainingMs` entries cleared (the schedule takes over
     * any pending reactivation).
     *
     * Note: the conflict check uses `currentlyActiveModes` as it was at
     * the start — it does NOT re-check after each mode is added. A
     * schedule that links both BLOCK_SELECTED and ALLOW_SELECTED modes
     * applied against an empty active set will produce mixed-polarity
     * state. This is preserved current behavior.
     */
    fun applyScheduleActivation(
        state: AppState,
        scheduleId: String
    ): ScheduleActivationResult {
        val schedule = state.schedules.find { it.id == scheduleId }
            ?: return ScheduleActivationResult.ScheduleNotFound(state)

        val currentlyActive = state.modes.filter { state.activeModes.contains(it.id) }

        val activated = mutableSetOf<String>()
        val conflictSkipped = mutableSetOf<String>()

        schedule.linkedModeIds.forEach { modeId ->
            val mode = state.modes.find { it.id == modeId } ?: return@forEach
            val conflicts = currentlyActive.isNotEmpty() &&
                currentlyActive.any { it.blockMode != mode.blockMode }
            if (conflicts) {
                conflictSkipped.add(modeId)
            } else {
                activated.add(modeId)
            }
        }

        val newState = state.copy(
            activeModes = state.activeModes + activated,
            activeSchedules = state.activeSchedules + scheduleId,
            deactivatedSchedules = state.deactivatedSchedules - scheduleId,
            timedModeReactivations = state.timedModeReactivations - activated,
            pausedModeRemainingMs = state.pausedModeRemainingMs - activated
        )

        return ScheduleActivationResult.Applied(newState, activated, conflictSkipped)
    }

    // ─── Schedule deactivation ──────────────────────────────────────────────

    sealed class ScheduleDeactivationResult {
        abstract val newState: AppState

        data class ScheduleNotFound(override val newState: AppState) : ScheduleDeactivationResult()

        /**
         * Schedule deactivated. [deactivatedModeIds] are the modes
         * actually removed from active; [keptDueToUserTimerModeIds] are
         * the modes that had a user-set timer and were left alive.
         */
        data class Applied(
            override val newState: AppState,
            val deactivatedModeIds: Set<String>,
            val keptDueToUserTimerModeIds: Set<String>
        ) : ScheduleDeactivationResult()
    }

    /**
     * End a schedule. Linked modes are removed from active UNLESS they
     * have an entry in `timedModeDeactivations` — a user-set timer
     * takes priority over the schedule's end time.
     *
     * The schedule itself is removed from both activeSchedules and
     * deactivatedSchedules (the schedule cycle ends cleanly either way).
     */
    fun applyScheduleDeactivation(
        state: AppState,
        scheduleId: String
    ): ScheduleDeactivationResult {
        val schedule = state.schedules.find { it.id == scheduleId }
            ?: return ScheduleDeactivationResult.ScheduleNotFound(state)

        val (deactivate, keep) = schedule.linkedModeIds.partition {
            !state.timedModeDeactivations.containsKey(it)
        }
        val deactivateSet = deactivate.toSet()
        val keepSet = keep.toSet()

        val newState = state.copy(
            activeModes = state.activeModes - deactivateSet,
            activeSchedules = state.activeSchedules - scheduleId,
            deactivatedSchedules = state.deactivatedSchedules - scheduleId
        )

        return ScheduleDeactivationResult.Applied(newState, deactivateSet, keepSet)
    }

    // ─── Timed mode deactivation ────────────────────────────────────────────

    sealed class TimedDeactivationResult {
        abstract val newState: AppState

        /** Mode is already inactive — receiver should no-op. */
        data class AlreadyInactive(override val newState: AppState) : TimedDeactivationResult()

        /**
         * Mode deactivated. [deactivatedScheduleIds] are schedules that
         * cascaded off because this was their last active linked mode.
         */
        data class Applied(
            override val newState: AppState,
            val deactivatedScheduleIds: Set<String>
        ) : TimedDeactivationResult()
    }

    /**
     * Deactivate a mode whose user-set timer expired. Cascades to
     * schedules: any active schedule that linked this mode AND whose
     * other linked modes are all already inactive gets marked
     * deactivated.
     */
    fun applyTimedModeDeactivation(
        state: AppState,
        modeId: String
    ): TimedDeactivationResult {
        if (!state.activeModes.contains(modeId)) {
            return TimedDeactivationResult.AlreadyInactive(state)
        }

        val schedulesToDeactivate = state.schedules
            .filter { schedule ->
                schedule.linkedModeIds.contains(modeId) &&
                    state.activeSchedules.contains(schedule.id) &&
                    schedule.linkedModeIds.all { linkedId ->
                        linkedId == modeId || !state.activeModes.contains(linkedId)
                    }
            }
            .map { it.id }
            .toSet()

        val newState = state.copy(
            activeModes = state.activeModes - modeId,
            activeSchedules = state.activeSchedules - schedulesToDeactivate,
            deactivatedSchedules = state.deactivatedSchedules + schedulesToDeactivate,
            manuallyActivatedModes = state.manuallyActivatedModes - modeId,
            timedModeDeactivations = state.timedModeDeactivations - modeId
        )

        return TimedDeactivationResult.Applied(newState, schedulesToDeactivate)
    }
}
