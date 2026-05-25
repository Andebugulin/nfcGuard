package com.andebugulin.nfcguard

import android.content.Context

/**
 * Single dispatch point for state → platform side effects.
 *
 * Three concerns, all idempotent:
 *   1. (Re)start [BlockerService] computed from `state.activeModes`,
 *      `blockMode`, blocked-apps union, and active-mode metadata; or
 *      stop the service when there's truly nothing left to do.
 *   2. Reschedule all schedule start/end alarms via
 *      [ScheduleAlarmReceiver.scheduleAllUpcomingAlarms].
 *   3. Refresh widget(s) via [GuardianWidget.notifyAllWidgets].
 *
 * Wired into [AppStateRepository.update] / [updateWith] so every
 * successful write fires the side effects exactly once. Callers that
 * need to force the same side effects without mutating state — boot,
 * service watchdog, service-restart receiver — call [sync] directly.
 *
 * Per-mode timed-deactivation / timed-reactivation alarms are NOT
 * handled here; those still flow through the caller that initiated
 * the transition. Diffing the state for per-mode alarm scheduling is
 * a possible follow-up but adds enough complexity it's worth its own
 * commit.
 */
object StateSyncer {

    /**
     * Apply the platform side effects implied by [state].
     * Callable from any thread; the dispatched side effects (Service
     * start, alarm scheduling via AlarmManager, widget update via
     * AppWidgetManager) each take care of their own threading.
     */
    fun sync(context: Context, state: AppState) {
        applyBlockerServiceFor(context, state)
        ScheduleAlarmReceiver.scheduleAllUpcomingAlarms(context)
        GuardianWidget.notifyAllWidgets(context)
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
