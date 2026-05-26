package com.andebugulin.nfcguard

/**
 * Pure transformation logic for mode and manual-schedule activation.
 *
 * Note: there are TWO schedule-activation paths in the codebase with
 * different semantics by design.
 *
 * - [applyManualScheduleActivation] (this file) is STRICT: any
 *   BLOCK/ALLOW conflict with currently-active modes rejects the
 *   whole activation. Used when the user taps a schedule in the UI
 *   and is watching for feedback.
 * - [ScheduleTransitions.applyScheduleActivation] is PERMISSIVE:
 *   conflicting linked modes are skipped, the rest activate. Used
 *   when an alarm fires and the user is not present.
 */
object ModeActivationLogic {

    // ─── Single mode activation ─────────────────────────────────────────────

    sealed class ActivateModeResult {
        abstract val newState: AppState

        data class Activated(override val newState: AppState) : ActivateModeResult()
        data class ModeNotFound(override val newState: AppState) : ActivateModeResult()
        data class Conflict(
            override val newState: AppState,
            val modeName: String
        ) : ActivateModeResult()
    }

    /**
     * Activate a single mode manually (user tap from ModesScreen).
     *
     * Strict semantics: BLOCK/ALLOW conflict with any currently
     * active mode rejects the activation. Clears any pending
     * reactivation timer and paused-remaining entry for this mode.
     *
     * If [timedUntilMillis] is non-null, the mode is added to
     * `timedModeDeactivations` so the caller can schedule an alarm.
     */
    fun applyModeActivation(
        state: AppState,
        modeId: String,
        timedUntilMillis: Long?
    ): ActivateModeResult {
        val mode = state.modes.find { it.id == modeId }
            ?: return ActivateModeResult.ModeNotFound(state)

        val currentlyActive = state.modes.filter { state.activeModes.contains(it.id) }
        if (currentlyActive.isNotEmpty() && currentlyActive.any { it.blockMode != mode.blockMode }) {
            return ActivateModeResult.Conflict(state, mode.name)
        }

        val newTimedDeactivations = if (timedUntilMillis != null) {
            state.timedModeDeactivations + (modeId to timedUntilMillis)
        } else {
            state.timedModeDeactivations
        }

        val newState = state.copy(
            activeModes = state.activeModes + modeId,
            manuallyActivatedModes = state.manuallyActivatedModes + modeId,
            timedModeDeactivations = newTimedDeactivations,
            timedModeReactivations = state.timedModeReactivations - modeId,
            pausedModeRemainingMs = state.pausedModeRemainingMs - modeId
        )
        return ActivateModeResult.Activated(newState)
    }

    // ─── Single mode deactivation ───────────────────────────────────────────

    /**
     * Result of deactivating a mode.
     *
     * [deactivatedScheduleIds] are schedules that cascaded off because
     * this mode was their last active linked mode. The caller may want
     * to surface those to the user or just log them.
     */
    data class DeactivateModeResult(
        val newState: AppState,
        val deactivatedScheduleIds: Set<String>
    )

    /**
     * Deactivate a mode and cascade to its linked schedules.
     *
     * An active schedule cascades off only if ALL its linked modes
     * will be inactive after this deactivation. The schedule is moved
     * from `activeSchedules` to `deactivatedSchedules` so it won't
     * auto-reactivate in the current cycle.
     *
     * Clears manual flag, timed deactivation, timed reactivation, and
     * paused remaining for this mode.
     */
    fun applyModeDeactivation(state: AppState, modeId: String): DeactivateModeResult {
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
            timedModeDeactivations = state.timedModeDeactivations - modeId,
            timedModeReactivations = state.timedModeReactivations - modeId,
            pausedModeRemainingMs = state.pausedModeRemainingMs - modeId
        )
        return DeactivateModeResult(newState, schedulesToDeactivate)
    }

    // ─── Manual schedule activation ─────────────────────────────────────────

    sealed class ManualScheduleActivationResult {
        abstract val newState: AppState

        data class Activated(override val newState: AppState) : ManualScheduleActivationResult()
        data class ScheduleNotFound(override val newState: AppState) : ManualScheduleActivationResult()
        data class Conflict(override val newState: AppState) : ManualScheduleActivationResult()
    }

    /**
     * Activate a schedule's linked modes from a manual user tap.
     *
     * All-or-nothing: if ANY linked mode would conflict with
     * currently active modes, the whole activation is rejected. The
     * schedule is marked active and its `deactivatedSchedules` entry
     * cleared. Pending reactivation timers and paused-remaining for
     * the linked modes are cleared.
     */
    fun applyManualScheduleActivation(
        state: AppState,
        scheduleId: String
    ): ManualScheduleActivationResult {
        val schedule = state.schedules.find { it.id == scheduleId }
            ?: return ManualScheduleActivationResult.ScheduleNotFound(state)

        val currentlyActive = state.modes.filter { state.activeModes.contains(it.id) }
        val modesToActivate = schedule.linkedModeIds.mapNotNull { id ->
            state.modes.find { it.id == id }
        }
        if (currentlyActive.isNotEmpty() && modesToActivate.isNotEmpty()) {
            val hasConflict = modesToActivate.any { newMode ->
                currentlyActive.any { it.blockMode != newMode.blockMode }
            }
            if (hasConflict) return ManualScheduleActivationResult.Conflict(state)
        }

        val linkedModeIdSet = schedule.linkedModeIds.toSet()
        val newState = state.copy(
            activeModes = state.activeModes + linkedModeIdSet,
            activeSchedules = state.activeSchedules + scheduleId,
            deactivatedSchedules = state.deactivatedSchedules - scheduleId,
            timedModeReactivations = state.timedModeReactivations - linkedModeIdSet,
            pausedModeRemainingMs = state.pausedModeRemainingMs - linkedModeIdSet
        )
        return ManualScheduleActivationResult.Activated(newState)
    }
}
