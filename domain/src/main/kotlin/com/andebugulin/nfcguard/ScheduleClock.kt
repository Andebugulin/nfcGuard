package com.andebugulin.nfcguard

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The one place that knows how schedule times map onto real time.
 *
 * Schedule days are 1 = Monday .. 7 = Sunday. A [DayTime] whose end is not
 * after its start is overnight: it ends on the following day, so
 * `22:00 - 07:00` runs into the next morning and `09:00 - 00:00` ends at
 * midnight. Everything that asks "is this schedule running now?", "until
 * when?" or "when does its next alarm fire?" goes through here, so the
 * overnight rule lives in exactly one place.
 */
object ScheduleClock {

    const val MINUTES_PER_DAY = 24 * 60

    /** Schedule day (1 = Monday .. 7 = Sunday) for a `Calendar.DAY_OF_WEEK`. */
    fun dayOf(calendarDayOfWeek: Int): Int = when (calendarDayOfWeek) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        else -> 7
    }

    /** A point in time as schedules see it. */
    data class Moment(val day: Int, val minuteOfDay: Int)

    fun momentOf(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): Moment {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = millis }
        return Moment(
            dayOf(cal.get(Calendar.DAY_OF_WEEK)),
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        )
    }

    fun previousDay(day: Int): Int = if (day == 1) 7 else day - 1

    val DayTime.startMinuteOfDay: Int get() = startHour * 60 + startMinute
    val DayTime.endMinuteOfDay: Int get() = endHour * 60 + endMinute

    /** Ends on the following day. Only meaningful for schedules with an end time. */
    val DayTime.isOvernight: Boolean get() = endMinuteOfDay <= startMinuteOfDay

    /**
     * The occurrence of a schedule running at a moment: its time slot and how
     * many minutes ago it started (always at least 0).
     */
    data class Occurrence(val slot: DayTime, val minutesSinceStart: Int)

    /**
     * The occurrence of [schedule] running on schedule day [day] at
     * [minuteOfDay], or null when it is not running. Without an end time a
     * schedule runs from its start until midnight, as far as the clock is
     * concerned (it stays on until the user turns it off).
     */
    fun occurrenceAt(schedule: Schedule, day: Int, minuteOfDay: Int): Occurrence? {
        schedule.timeSlot.getTimeForDay(day)?.let { today ->
            val start = today.startMinuteOfDay
            if (minuteOfDay >= start) {
                val running = !schedule.hasEndTime || today.isOvernight ||
                    minuteOfDay < today.endMinuteOfDay
                if (running) return Occurrence(today, minuteOfDay - start)
            }
        }
        if (!schedule.hasEndTime) return null
        val yesterday = schedule.timeSlot.getTimeForDay(previousDay(day)) ?: return null
        if (yesterday.isOvernight && minuteOfDay < yesterday.endMinuteOfDay) {
            return Occurrence(yesterday, minuteOfDay + MINUTES_PER_DAY - yesterday.startMinuteOfDay)
        }
        return null
    }

    fun isRunning(schedule: Schedule, day: Int, minuteOfDay: Int): Boolean =
        occurrenceAt(schedule, day, minuteOfDay) != null

    /**
     * End of the occurrence running now as minute of day, or null when the
     * schedule is not running or has no end time. Used for "UNTIL hh:mm".
     */
    fun runningEndMinute(schedule: Schedule, day: Int, minuteOfDay: Int): Int? {
        if (!schedule.hasEndTime) return null
        return occurrenceAt(schedule, day, minuteOfDay)?.slot?.endMinuteOfDay
    }

    /**
     * When the schedules holding [modeId] on will let it go, as minute of
     * day, or null when no running schedule with an end time links it. The
     * first end alarm deactivates the mode, so with several running
     * schedules the soonest end wins, counted across midnight.
     */
    fun modeHeldUntil(state: AppState, modeId: String, moment: Moment): Int? =
        state.schedules
            .filter { modeId in it.linkedModeIds }
            .mapNotNull { runningEndMinute(it, moment.day, moment.minuteOfDay) }
            .minByOrNull { (it - moment.minuteOfDay + MINUTES_PER_DAY) % MINUTES_PER_DAY }

    /**
     * True when an end alarm of [schedule] is still ahead today: it is
     * running now, or today's slot has not started yet.
     */
    fun hasEndAhead(schedule: Schedule, moment: Moment): Boolean {
        if (!schedule.hasEndTime) return false
        if (isRunning(schedule, moment.day, moment.minuteOfDay)) return true
        val today = schedule.timeSlot.getTimeForDay(moment.day) ?: return false
        return moment.minuteOfDay < today.startMinuteOfDay
    }

    /**
     * Next moment strictly after [nowMillis] at which [slot]'s start (or end,
     * when [end] is true) falls, in [timeZone]. An overnight end falls on the
     * day after the slot's own day.
     */
    fun nextTrigger(
        slot: DayTime,
        end: Boolean,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val minute = if (end) slot.endMinuteOfDay else slot.startMinuteOfDay
        val dayOffset = if (end && slot.isOvernight) 1 else 0
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, minute / 60)
            set(Calendar.MINUTE, minute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Eight days covers "today, already passed" plus a full week ahead.
        repeat(8) {
            val slotDay = Calendar.getInstance(timeZone).apply {
                timeInMillis = cal.timeInMillis
                add(Calendar.DAY_OF_MONTH, -dayOffset)
            }
            if (dayOf(slotDay.get(Calendar.DAY_OF_WEEK)) == slot.day && cal.timeInMillis > nowMillis) {
                return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        error("No trigger within eight days for day ${slot.day}")
    }

    /** "hh:mm" for a minute of day; 24:00 is shown as 00:00. */
    fun format(minuteOfDay: Int): String {
        val m = minuteOfDay % MINUTES_PER_DAY
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
    }
}
