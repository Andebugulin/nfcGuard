package com.andebugulin.nfcguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end schedule scenarios that exercise the full state-machine
 * sequence — start alarm → end alarm, or composed with manual/NFC paths.
 * Unit tests in `ScheduleTransitionsTest` cover each function in isolation;
 * these prove the composition is correct.
 */
class ScheduleFlowTest {

    private fun mode(
        id: String,
        blockMode: BlockMode = BlockMode.BLOCK_SELECTED
    ) = Mode(id = id, name = id, blockedApps = listOf("com.x"), blockMode = blockMode)

    private fun dailySchedule(id: String, linkedModeIds: List<String>): Schedule = Schedule(
        id = id, name = id,
        timeSlot = TimeSlot((1..7).map { DayTime(it, 9, 0, 17, 0) }),
        linkedModeIds = linkedModeIds, hasEndTime = true
    )

    @Test
    fun full_schedule_lifecycle_activates_then_deactivates_cleanly() {
        val m = mode("m1")
        val s = dailySchedule("s1", listOf("m1"))
        val initial = AppState(modes = listOf(m), schedules = listOf(s))

        // Start alarm fires
        val afterStart = (ScheduleTransitions.applyScheduleActivation(initial, "s1")
            as ScheduleTransitions.ScheduleActivationResult.Applied).newState
        assertEquals(setOf("m1"), afterStart.activeModes)
        assertEquals(setOf("s1"), afterStart.activeSchedules)

        // End alarm fires
        val afterEnd = (ScheduleTransitions.applyScheduleDeactivation(afterStart, "s1")
            as ScheduleTransitions.ScheduleDeactivationResult.Applied).newState
        assertTrue(afterEnd.activeModes.isEmpty())
        assertTrue(afterEnd.activeSchedules.isEmpty())
        assertFalse(afterEnd.deactivatedSchedules.contains("s1")) // round-trip leaves clean state
    }

    @Test
    fun user_timer_outlasts_schedule_end_so_mode_keeps_running() {
        // Schedule starts → activates m1. While S is active, user activates m1
        // again with an explicit 30-minute timer. Schedule end fires — m1
        // should NOT be deactivated, because the user's timer takes priority.
        val now = 1_000_000L
        val m = mode("m1")
        val s = dailySchedule("s1", listOf("m1"))
        val initial = AppState(modes = listOf(m), schedules = listOf(s))

        val afterStart = (ScheduleTransitions.applyScheduleActivation(initial, "s1")
            as ScheduleTransitions.ScheduleActivationResult.Applied).newState

        val userDeadline = now + 30 * 60_000L
        val afterUserActivation = (ModeActivationLogic.applyModeActivation(afterStart, "m1", userDeadline)
            as ModeActivationLogic.ActivateModeResult.Activated).newState
        assertEquals(userDeadline, afterUserActivation.timedModeDeactivations["m1"])

        val afterEnd = (ScheduleTransitions.applyScheduleDeactivation(afterUserActivation, "s1")
            as ScheduleTransitions.ScheduleDeactivationResult.Applied).newState

        // Mode kept alive by user timer; schedule itself is gone.
        assertTrue(afterEnd.activeModes.contains("m1"))
        assertFalse(afterEnd.activeSchedules.contains("s1"))
        assertEquals(userDeadline, afterEnd.timedModeDeactivations["m1"])
    }

    @Test
    fun mode_timed_deactivation_during_active_schedule_cascades_schedule_off() {
        // Schedule active, mode A driven by it with a 5-min user timer. When
        // the timer fires (applyTimedModeDeactivation), mode goes off AND the
        // schedule cascades to deactivatedSchedules because A was its only
        // active linked mode.
        val now = 1_000_000L
        val m = mode("m1")
        val s = dailySchedule("s1", listOf("m1"))
        val afterStart = (ScheduleTransitions.applyScheduleActivation(
            AppState(modes = listOf(m), schedules = listOf(s)), "s1"
        ) as ScheduleTransitions.ScheduleActivationResult.Applied).newState

        val withUserTimer = (ModeActivationLogic.applyModeActivation(
            afterStart, "m1", now + 5 * 60_000L
        ) as ModeActivationLogic.ActivateModeResult.Activated).newState

        val afterTimerFires = (ScheduleTransitions.applyTimedModeDeactivation(withUserTimer, "m1")
            as ScheduleTransitions.TimedDeactivationResult.Applied)
        assertEquals(setOf("s1"), afterTimerFires.deactivatedScheduleIds)
        assertFalse(afterTimerFires.newState.activeModes.contains("m1"))
        assertFalse(afterTimerFires.newState.activeSchedules.contains("s1"))
        assertTrue(afterTimerFires.newState.deactivatedSchedules.contains("s1"))
    }

    @Test
    fun schedule_restart_clears_deactivatedSchedules_flag() {
        // User NFC-unlocks a mode during a schedule, so the schedule moves to
        // deactivatedSchedules. The next day (or later in the same day) the
        // schedule's start alarm fires again. The flag must be cleared so the
        // schedule cycle is clean.
        val now = 1_000_000L
        val m = mode("m1", BlockMode.BLOCK_SELECTED).copy(nfcTagIds = listOf("tag-1"))
        val s = dailySchedule("s1", listOf("m1"))

        // Day 1 morning: schedule active, mode active
        val day1Morning = (ScheduleTransitions.applyScheduleActivation(
            AppState(modes = listOf(m), schedules = listOf(s)), "s1"
        ) as ScheduleTransitions.ScheduleActivationResult.Applied).newState

        // User permanently NFC-unlocks during the day
        val pending = NfcUnlockLogic.computePendingUnlock(day1Morning, "tag-1", 1, 10 * 60)!!
        val afterUnlock = NfcUnlockLogic.applyUnlock(
            day1Morning, pending, null, reactivateAtMillis = null,
            now = now, currentDayOfWeek = 1, currentMinuteOfDay = 10 * 60
        ).newState
        assertTrue(afterUnlock.deactivatedSchedules.contains("s1"))
        assertFalse(afterUnlock.activeModes.contains("m1"))

        // Day 2 morning: start alarm fires again
        val day2Morning = (ScheduleTransitions.applyScheduleActivation(afterUnlock, "s1")
            as ScheduleTransitions.ScheduleActivationResult.Applied).newState

        // Schedule is active again, mode re-activated, deactivated flag gone
        assertTrue(day2Morning.activeSchedules.contains("s1"))
        assertFalse(day2Morning.deactivatedSchedules.contains("s1"))
        assertTrue(day2Morning.activeModes.contains("m1"))
    }

    @Test
    fun two_schedules_sharing_a_mode_only_cascade_active_one() {
        // S1 and S2 both link mode A. Only S1 is currently active.
        // Mode A deactivates (manually) — only S1 should cascade off.
        val m = mode("m1")
        val s1 = dailySchedule("s1", listOf("m1"))
        val s2 = dailySchedule("s2", listOf("m1"))
        val state = AppState(
            modes = listOf(m),
            schedules = listOf(s1, s2),
            activeModes = setOf("m1"),
            activeSchedules = setOf("s1") // s2 not active
        )

        val result = ModeActivationLogic.applyModeDeactivation(state, "m1")
        assertEquals(setOf("s1"), result.deactivatedScheduleIds) // only s1
        assertFalse(result.newState.activeSchedules.contains("s1"))
        assertTrue(result.newState.deactivatedSchedules.contains("s1"))
        assertFalse(result.newState.deactivatedSchedules.contains("s2")) // s2 unchanged
    }
}
