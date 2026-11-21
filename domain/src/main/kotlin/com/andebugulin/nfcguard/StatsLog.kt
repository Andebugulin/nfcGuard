package com.andebugulin.nfcguard

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One calendar day of on-device stats.
 *
 * [blockedAttempts] counts, per package, how many times a blocked app was
 * brought to the foreground (one count per attempt, not per check tick).
 */
@Serializable
data class DayStats(
    val blockedAttempts: Map<String, Int> = emptyMap(),
    val nfcUnlocks: Int = 0,
    val recoveries: Int = 0
) {
    val attemptCount: Int get() = blockedAttempts.values.sum()
}

/**
 * Everything the stats screen knows, keyed by ISO date (`2026-11-21`) in
 * the device's zone. Kept apart from [AppState] on purpose: it is written
 * far more often than config, must never trigger the `StateSyncer` side
 * effects, and is not part of config export.
 */
@Serializable
data class StatsLog(
    val days: Map<String, DayStats> = emptyMap()
)

/** How one count compares with the same count a week earlier. */
enum class Change { FEWER, SIMILAR, MORE }

/** Attempts on one blocked app, this week against last week up to the same weekday. */
data class AppTrend(val packageName: String, val lastWeek: Int, val thisWeek: Int) {
    val change: Change get() = StatsLogic.compare(lastWeek, thisWeek)
}

/**
 * What the stats screen shows: this calendar week and the one before,
 * Monday first. Comparisons are like for like: this week so far against
 * last week up to the same weekday, so a Monday is not "better" than a
 * whole previous week just by being short.
 */
data class StatsSummary(
    /** Monday to Sunday; days after today are empty. */
    val thisWeek: List<DayStats>,
    /** Monday to Sunday of the previous week. */
    val lastWeek: List<DayStats>,
    /** 0 = Monday .. 6 = Sunday. */
    val todayIndex: Int,
    val attemptsThisWeek: Int,
    /** Last week Monday through the same weekday as today. Null with no history for last week. */
    val attemptsLastWeekSoFar: Int?,
    /** Apps attempted in either week, by their larger count, highest first. */
    val apps: List<AppTrend>
)

/** Pure state math for [StatsLog]. Callers pass today's date. */
object StatsLogic {

    fun recordBlockedAttempt(log: StatsLog, packageName: String, today: LocalDate): StatsLog =
        log.updateDay(today) { day ->
            val counts = day.blockedAttempts
            day.copy(blockedAttempts = counts + (packageName to (counts[packageName] ?: 0) + 1))
        }

    fun recordNfcUnlock(log: StatsLog, today: LocalDate): StatsLog =
        log.updateDay(today) { it.copy(nfcUnlocks = it.nfcUnlocks + 1) }

    fun recordRecovery(log: StatsLog, today: LocalDate): StatsLog =
        log.updateDay(today) { it.copy(recoveries = it.recoveries + 1) }

    /**
     * Fewer is any drop. Similar is the same or up by at most 1 or a
     * quarter, whichever is larger, so 5 -> 6 reads as noise, not a relapse.
     */
    fun compare(last: Int, now: Int): Change = when {
        now < last -> Change.FEWER
        now - last <= maxOf(1, last / 4) -> Change.SIMILAR
        else -> Change.MORE
    }

    fun summarize(log: StatsLog, today: LocalDate): StatsSummary {
        val monday = mondayOf(today)
        val todayIndex = today.dayOfWeek.value - 1
        fun week(start: LocalDate) = (0L until 7L).map { log.days[start.plusDays(it).toString()] ?: DayStats() }

        val thisWeek = week(monday)
        val lastWeek = week(monday.minusWeeks(1))
        val lastSoFar = lastWeek.take(todayIndex + 1)
        val hasLastWeek = lastWeek.any { it != DayStats() }

        fun perApp(days: List<DayStats>): Map<String, Int> {
            val counts = mutableMapOf<String, Int>()
            days.forEach { d -> d.blockedAttempts.forEach { (pkg, n) -> counts[pkg] = (counts[pkg] ?: 0) + n } }
            return counts
        }
        val now = perApp(thisWeek)
        val before = perApp(lastSoFar)
        val apps = (now.keys + before.keys)
            .map { AppTrend(it, lastWeek = before[it] ?: 0, thisWeek = now[it] ?: 0) }
            .sortedByDescending { maxOf(it.lastWeek, it.thisWeek) }

        return StatsSummary(
            thisWeek = thisWeek,
            lastWeek = lastWeek,
            todayIndex = todayIndex,
            attemptsThisWeek = thisWeek.sumOf { it.attemptCount },
            attemptsLastWeekSoFar = if (hasLastWeek) lastSoFar.sumOf { it.attemptCount } else null,
            apps = apps
        )
    }

    private fun mondayOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())

    private fun StatsLog.updateDay(today: LocalDate, change: (DayStats) -> DayStats): StatsLog {
        val key = today.toString()
        return copy(days = days + (key to change(days[key] ?: DayStats()))).pruned(today)
    }

    /** Only this week and last week are ever shown, so only they are kept. */
    private fun StatsLog.pruned(today: LocalDate): StatsLog {
        val oldestKept = mondayOf(today).minusWeeks(1).toString()
        // ISO dates sort lexicographically in date order.
        return if (days.keys.all { it >= oldestKept }) this
        else copy(days = days.filterKeys { it >= oldestKept })
    }
}
