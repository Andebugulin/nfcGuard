package com.andebugulin.nfcguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduleClockTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun schedule(vararg slots: DayTime, hasEndTime: Boolean = true) = Schedule(
        id = "s", name = "s", timeSlot = TimeSlot(slots.toList()),
        linkedModeIds = listOf("m"), hasEndTime = hasEndTime
    )

    private fun slot(day: Int, sh: Int, eh: Int, sm: Int = 0, em: Int = 0) = DayTime(day, sh, sm, eh, em)

    /** Epoch millis in UTC for a calendar date and time. */
    private fun at(year: Int, month: Int, dayOfMonth: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear(); set(year, month - 1, dayOfMonth, hour, minute)
        }.timeInMillis

    // 2026-10-05 is a Monday.
    private val MON = 1; private val TUE = 2; private val SUN = 7

    // ─── occurrenceAt / isRunning ───────────────────────────────────────────

    @Test fun sameDaySlot_runsFromStartUntilEndExclusive() {
        val s = schedule(slot(MON, 9, 17))
        assertFalse(ScheduleClock.isRunning(s, MON, 9 * 60 - 1))
        assertTrue(ScheduleClock.isRunning(s, MON, 9 * 60))
        assertTrue(ScheduleClock.isRunning(s, MON, 17 * 60 - 1))
        assertFalse(ScheduleClock.isRunning(s, MON, 17 * 60))
    }

    @Test fun overnightSlot_runsIntoTheNextMorning() {
        val s = schedule(slot(MON, 22, 7))
        assertTrue(ScheduleClock.isRunning(s, MON, 23 * 60))
        val morning = ScheduleClock.occurrenceAt(s, TUE, 6 * 60)!!
        assertEquals(MON, morning.slot.day)
        assertEquals(8 * 60, morning.minutesSinceStart)
        assertFalse(ScheduleClock.isRunning(s, TUE, 7 * 60))
    }

    @Test fun overnightSlot_wrapsFromSundayToMonday() {
        val s = schedule(slot(SUN, 22, 7))
        assertTrue(ScheduleClock.isRunning(s, MON, 3 * 60))
    }

    @Test fun endAtMidnight_isAnOvernightEndAtZero() {
        val s = schedule(slot(MON, 9, 0))
        assertTrue(ScheduleClock.isRunning(s, MON, 23 * 60 + 59))
        assertFalse(ScheduleClock.isRunning(s, TUE, 0))
        assertEquals("00:00", ScheduleClock.format(ScheduleClock.runningEndMinute(s, MON, 12 * 60)!!))
    }

    @Test fun noEndTime_runsFromStartUntilMidnightOnly() {
        val s = schedule(slot(MON, 22, 7), hasEndTime = false)
        assertTrue(ScheduleClock.isRunning(s, MON, 23 * 60))
        assertFalse("an end time that is switched off does not run overnight",
            ScheduleClock.isRunning(s, TUE, 3 * 60))
        assertNull(ScheduleClock.runningEndMinute(s, MON, 23 * 60))
    }

    // ─── modeHeldUntil ──────────────────────────────────────────────────────

    @Test fun modeHeldUntil_ignoresSchedulesNotRunningToday() {
        val other = schedule(slot(TUE, 9, 12)).copy(id = "a")
        val today = schedule(slot(MON, 9, 17)).copy(id = "b")
        val state = AppState(schedules = listOf(other, today))
        assertEquals(17 * 60, ScheduleClock.modeHeldUntil(state, "m", ScheduleClock.Moment(MON, 10 * 60)))
    }

    @Test fun modeHeldUntil_soonestEndWinsAcrossMidnight() {
        val overnight = schedule(slot(MON, 20, 2)).copy(id = "a")
        val evening = schedule(slot(MON, 21, 23)).copy(id = "b")
        val state = AppState(schedules = listOf(overnight, evening))
        assertEquals(23 * 60, ScheduleClock.modeHeldUntil(state, "m", ScheduleClock.Moment(MON, 22 * 60)))
    }

    // ─── nextTrigger ────────────────────────────────────────────────────────

    @Test fun nextTrigger_laterToday() {
        val now = at(2026, 10, 5, 8)
        assertEquals(at(2026, 10, 5, 9), ScheduleClock.nextTrigger(slot(MON, 9, 17), false, now, utc))
    }

    @Test fun nextTrigger_isStrictlyAfterNow_soAFiredAlarmMovesAWeek() {
        val firedAt = at(2026, 10, 5, 9)
        assertEquals(at(2026, 10, 12, 9), ScheduleClock.nextTrigger(slot(MON, 9, 17), false, firedAt, utc))
    }

    @Test fun nextTrigger_overnightEndFallsOnTheNextDay() {
        val now = at(2026, 10, 5, 23)
        assertEquals(at(2026, 10, 6, 7), ScheduleClock.nextTrigger(slot(MON, 22, 7), true, now, utc))
    }

    @Test fun nextTrigger_sundayOvernightEndLandsOnMonday() {
        val saturday = at(2026, 10, 10, 12)
        assertEquals(at(2026, 10, 12, 7), ScheduleClock.nextTrigger(slot(SUN, 22, 7), true, saturday, utc))
    }

    @Test fun momentOf_mapsWeekdayAndMinute() {
        assertEquals(ScheduleClock.Moment(SUN, 13 * 60 + 5), ScheduleClock.momentOf(at(2026, 10, 11, 13, 5), utc))
    }

    // ─── missed transitions ─────────────────────────────────────────────────

    @Test fun missedStart_coversTheMorningHalfOfAnOvernightSchedule() {
        val state = AppState(schedules = listOf(schedule(slot(MON, 22, 7))))
        val now = at(2026, 10, 6, 3)
        assertEquals(listOf("s"), ScheduleTransitions.missedScheduleStarts(state, TUE, 3 * 60, now))
    }

    @Test fun missedStart_overnightAlreadyStartedLastNight_isNotMissed() {
        val state = AppState(
            schedules = listOf(schedule(slot(MON, 22, 7))),
            scheduleLastStartedAt = mapOf("s" to at(2026, 10, 5, 22))
        )
        val now = at(2026, 10, 6, 3)
        assertTrue(ScheduleTransitions.missedScheduleStarts(state, TUE, 3 * 60, now).isEmpty())
    }

    private fun activeSince(startedAt: Long, vararg slots: DayTime) = AppState(
        schedules = listOf(schedule(*slots)),
        activeSchedules = setOf("s"),
        scheduleLastStartedAt = mapOf("s" to startedAt)
    )

    private fun missedEnds(state: AppState, now: Long): List<String> {
        val m = ScheduleClock.momentOf(now, utc)
        return ScheduleTransitions.missedScheduleEnds(state, m.day, m.minuteOfDay, now, utc)
    }

    @Test fun missedEnd_afterTheEndPassed() {
        val state = activeSince(at(2026, 10, 5, 9), slot(MON, 9, 17))
        assertEquals(listOf("s"), missedEnds(state, at(2026, 10, 5, 18)))
    }

    @Test fun missedEnd_overnightEndPassedNextMorning() {
        val state = activeSince(at(2026, 10, 5, 22), slot(MON, 22, 7))
        assertTrue(missedEnds(state, at(2026, 10, 6, 6)).isEmpty())
        assertEquals(listOf("s"), missedEnds(state, at(2026, 10, 6, 8)))
    }

    @Test fun missedEnd_manualStartOutsideHoursRunsUntilTheNextEnd() {
        // Switched on by hand at 18:00, after Monday's 17:00 end.
        val state = activeSince(at(2026, 10, 5, 18), slot(MON, 9, 17), slot(TUE, 9, 17))
        assertTrue(missedEnds(state, at(2026, 10, 5, 20)).isEmpty())
        assertEquals(listOf("s"), missedEnds(state, at(2026, 10, 6, 17, 30)))
    }

    @Test fun missedEnd_withoutAStartOnRecord_assumesNothing() {
        val state = AppState(
            schedules = listOf(schedule(slot(MON, 9, 17))),
            activeSchedules = setOf("s")
        )
        assertTrue(missedEnds(state, at(2026, 10, 5, 18)).isEmpty())
    }

    @Test fun missedEnd_noEndTime_neverEnds() {
        val s = schedule(slot(MON, 9, 17), hasEndTime = false)
        val state = AppState(schedules = listOf(s), activeSchedules = setOf("s"),
            scheduleLastStartedAt = mapOf("s" to at(2026, 10, 5, 9)))
        assertTrue(missedEnds(state, at(2026, 10, 6, 18)).isEmpty())
    }

    @Test fun manualActivation_recordsItsStart() {
        val state = AppState(
            modes = listOf(Mode("m", "m", emptyList())),
            schedules = listOf(schedule(slot(MON, 9, 17)))
        )
        val result = ModeActivationLogic.applyManualScheduleActivation(state, "s", 42L)
            as ModeActivationLogic.ManualScheduleActivationResult.Activated
        assertEquals(42L, result.newState.scheduleLastStartedAt["s"])
    }
}
