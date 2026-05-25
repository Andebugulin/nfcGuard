package com.andebugulin.nfcguard.sync

import com.andebugulin.nfcguard.AppState
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.data.AppLogger
import com.andebugulin.nfcguard.data.AppStateRepository
import com.andebugulin.nfcguard.receiver.ScheduleAlarmReceiver
import com.andebugulin.nfcguard.service.BlockerService
import com.andebugulin.nfcguard.widget.GuardianWidget

import android.content.Context

/**
 * Single dispatch point for state → platform side effects.
 *
 * Four concerns, all idempotent:
 *   1. (Re)start [BlockerService] computed from `state.activeModes`,
 *      `blockMode`, blocked-apps union, and active-mode metadata; or
 *      stop the service when there's truly nothing left to do.
 *   2. Reschedule all schedule start/end alarms via
 *      [ScheduleAlarmReceiver.scheduleAllUpcomingAlarms].
 *   3. Diff per-mode `timedModeDeactivations` and `timedModeReactivations`
 *      against the previous state and schedule/cancel alarms via
 *      [TimedAlarms] for each map entry that changed.
 *   4. Refresh widget(s) via [GuardianWidget.notifyAllWidgets].
 *
 * Wired into [AppStateRepository.update] / [updateWith] so every
 * successful write fires the side effects exactly once. Callers that
 * need to force the same side effects without mutating state — boot,
 * service watchdog, service-restart receiver — call the single-arg
 * [sync] overload, which treats the prior state as empty and so
 * re-schedules every per-mode alarm in the current state (correct on
 * boot, when the OS has dropped all our pending alarms).
 */
object StateSyncer {

    /**
     * Apply side effects when no prior state is meaningful (boot,
     * watchdog, service restart). Re-schedules every per-mode alarm
     * present in [state].
     */
    fun sync(context: Context, state: AppState) {
        sync(context, oldState = AppState(), newState = state)
    }

    /**
     * Apply side effects implied by the transition from [oldState] to
     * [newState]. Only per-mode alarm entries that changed (added,
     * removed, or rescheduled to a new time) result in AlarmManager
     * calls; unchanged entries are no-ops.
     */
    fun sync(context: Context, oldState: AppState, newState: AppState) {
        applyBlockerServiceFor(context, newState)
        ScheduleAlarmReceiver.scheduleAllUpcomingAlarms(context)
        syncTimedAlarms(context, oldState, newState)
        GuardianWidget.notifyAllWidgets(context)
    }

    private fun syncTimedAlarms(context: Context, old: AppState, new: AppState) {
        // Deactivations: schedule changed/new, cancel removed.
        new.timedModeDeactivations.forEach { (modeId, deadline) ->
            if (old.timedModeDeactivations[modeId] != deadline) {
                TimedAlarms.scheduleDeactivation(context, modeId, deadline)
            }
        }
        old.timedModeDeactivations.keys.forEach { modeId ->
            if (modeId !in new.timedModeDeactivations) {
                TimedAlarms.cancelDeactivation(context, modeId)
            }
        }
        // Reactivations: same pattern.
        new.timedModeReactivations.forEach { (modeId, deadline) ->
            if (old.timedModeReactivations[modeId] != deadline) {
                TimedAlarms.scheduleReactivation(context, modeId, deadline)
            }
        }
        old.timedModeReactivations.keys.forEach { modeId ->
            if (modeId !in new.timedModeReactivations) {
                TimedAlarms.cancelReactivation(context, modeId)
            }
        }
    }

    private fun applyBlockerServiceFor(context: Context, state: AppState) {
        val activeModes = state.modes.filter { state.activeModes.contains(it.id) }
        val modeNames = state.modes.associate { it.id to it.name }

        if (activeModes.isEmpty()) {
            // Keep the service alive while there's still something to wait
            // for (a schedule that might fire, or a paused mode that should
            // reactivate). Stop entirely when there is truly nothing left.
            val keepAlive = state.schedules.isNotEmpty() ||
                state.timedModeReactivations.isNotEmpty()
            if (keepAlive) {
                AppLogger.log("SYNC", "No active modes — keeping service alive for ${state.schedules.size} schedules / ${state.timedModeReactivations.size} pending reactivations")
                BlockerService.start(
                    context = context,
                    blockedApps = emptySet(),
                    blockMode = BlockMode.BLOCK_SELECTED,
                    activeModeIds = emptySet(),
                    timedModeReactivations = state.timedModeReactivations
                )
            } else {
                AppLogger.log("SYNC", "No active modes, schedules, or pending reactivations — stopping service")
                BlockerService.stop(context)
                ScheduleAlarmReceiver.cancelWatchdog(context)
            }
            return
        }

        val hasAllow = activeModes.any { it.blockMode == BlockMode.ALLOW_SELECTED }
        val apps: Set<String>
        val blockMode: BlockMode
        if (hasAllow) {
            // ALLOW_SELECTED takes precedence — only collect apps from ALLOW
            // modes (the union of their allowlists is what stays accessible)
            apps = activeModes
                .filter { it.blockMode == BlockMode.ALLOW_SELECTED }
                .flatMap { it.blockedApps }
                .toSet()
            blockMode = BlockMode.ALLOW_SELECTED
        } else {
            apps = activeModes.flatMap { it.blockedApps }.toSet()
            blockMode = BlockMode.BLOCK_SELECTED
        }

        AppLogger.log("SYNC", "Starting service: mode=$blockMode, ${apps.size} apps, activeModes=${state.activeModes}")
        BlockerService.start(
            context = context,
            blockedApps = apps,
            blockMode = blockMode,
            activeModeIds = activeModes.map { it.id }.toSet(),
            manuallyActivatedModeIds = state.manuallyActivatedModes,
            timedModeDeactivations = state.timedModeDeactivations,
            modeNames = modeNames,
            timedModeReactivations = state.timedModeReactivations
        )
    }
}
