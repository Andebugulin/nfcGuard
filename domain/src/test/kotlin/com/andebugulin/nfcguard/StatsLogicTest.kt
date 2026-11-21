package com.andebugulin.nfcguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StatsLogicTest {

    // 2026-11-18 is a Wednesday: Monday is the 16th, last Monday the 9th.
    private val today = LocalDate.of(2026, 11, 18)
    private val monday = LocalDate.of(2026, 11, 16)

    private fun attempts(log: StatsLog, pkg: String, date: LocalDate, n: Int = 1): StatsLog {
        var l = log
        repeat(n) { l = StatsLogic.recordBlockedAttempt(l, pkg, date) }
        return l
    }

    @Test fun blockedAttempts_countPerPackagePerDay() {
        var log = attempts(StatsLog(), "a", today, 2)
        log = attempts(log, "b", today)
        assertEquals(mapOf("a" to 2, "b" to 1), log.days["2026-11-18"]!!.blockedAttempts)
    }

    @Test fun weeksRunMondayToSunday_withTodayIndexed() {
        val log = attempts(attempts(StatsLog(), "a", monday), "a", monday.minusDays(1), 4) // Sunday before
        val s = StatsLogic.summarize(log, today)
        assertEquals(2, s.todayIndex)
        assertEquals(7, s.thisWeek.size)
        assertEquals(1, s.thisWeek[0].attemptCount)
        assertEquals(4, s.lastWeek[6].attemptCount)
        assertEquals(1, s.attemptsThisWeek)
    }

    @Test fun lastWeekIsComparedOnlyUpToTheSameWeekday() {
        var log = attempts(StatsLog(), "a", monday.minusWeeks(1), 3)       // last Monday: counts
        log = attempts(log, "a", monday.minusWeeks(1).plusDays(5), 10)      // last Saturday: not yet
        val s = StatsLogic.summarize(log, today)
        assertEquals(3, s.attemptsLastWeekSoFar)
        assertEquals(listOf(AppTrend("a", lastWeek = 3, thisWeek = 0)), s.apps)
    }

    @Test fun noHistoryForLastWeek_meansNoComparison() {
        assertNull(StatsLogic.summarize(attempts(StatsLog(), "a", today), today).attemptsLastWeekSoFar)
    }

    @Test fun apps_includeOnesThatDroppedToZero_rankedByLargerWeek() {
        var log = attempts(StatsLog(), "yt", monday.minusWeeks(1), 20)
        log = attempts(log, "yt", monday, 9)
        log = attempts(log, "ig", monday.minusWeeks(1), 5)
        log = attempts(log, "ig", today, 6)
        log = attempts(log, "chrome", today, 2)
        log = attempts(log, "gone", monday.minusWeeks(1), 7)

        val s = StatsLogic.summarize(log, today)
        assertEquals(listOf("yt", "gone", "ig", "chrome"), s.apps.map { it.packageName })
        assertEquals(listOf(Change.FEWER, Change.FEWER, Change.SIMILAR, Change.MORE), s.apps.map { it.change })
    }

    @Test fun compare_thresholds() {
        assertEquals(Change.FEWER, StatsLogic.compare(20, 9))
        assertEquals(Change.SIMILAR, StatsLogic.compare(5, 5))
        assertEquals(Change.SIMILAR, StatsLogic.compare(5, 6))
        assertEquals(Change.SIMILAR, StatsLogic.compare(0, 1))
        assertEquals(Change.SIMILAR, StatsLogic.compare(20, 25))
        assertEquals(Change.MORE, StatsLogic.compare(20, 26))
        assertEquals(Change.MORE, StatsLogic.compare(3, 20))
    }

    @Test fun onlyThisAndLastWeekAreKept() {
        var log = StatsLogic.recordNfcUnlock(StatsLog(), monday.minusWeeks(1).minusDays(1))
        log = StatsLogic.recordNfcUnlock(log, monday.minusWeeks(1))
        log = StatsLogic.recordNfcUnlock(log, today)
        assertEquals(setOf("2026-11-09", "2026-11-18"), log.days.keys)
    }
}
