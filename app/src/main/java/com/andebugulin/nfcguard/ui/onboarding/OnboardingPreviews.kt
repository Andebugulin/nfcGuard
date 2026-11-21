package com.andebugulin.nfcguard.ui.onboarding

import com.andebugulin.nfcguard.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.components.GuardianType
import com.andebugulin.nfcguard.ui.schedules.ScheduleState
import com.andebugulin.nfcguard.ui.schedules.ScheduleCard
import com.andebugulin.nfcguard.ui.modes.ModeCard
import com.andebugulin.nfcguard.ui.components.ScaledDown
import com.andebugulin.nfcguard.TimeSlot
import com.andebugulin.nfcguard.Schedule
import com.andebugulin.nfcguard.NfcTag
import com.andebugulin.nfcguard.Mode
import com.andebugulin.nfcguard.DayTime
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.AppTrend
import com.andebugulin.nfcguard.DayStats
import com.andebugulin.nfcguard.StatsSummary
import com.andebugulin.nfcguard.ui.stats.Apps
import com.andebugulin.nfcguard.ui.stats.Headline
import com.andebugulin.nfcguard.ui.stats.Legend
import com.andebugulin.nfcguard.ui.stats.WeekChart
import com.andebugulin.nfcguard.ui.stats.total
import androidx.compose.foundation.layout.height

/** How much smaller the real cards render here than on their own screens. */
private const val CARD_SCALE = 0.74f

/**
 * The onboarding visuals.
 *
 * Two rules here, both learned the hard way.
 *
 * **Show the real components.** [ModesPreview] and [SchedulesPreview] render
 * the app's own [ModeCard] and [ScheduleCard], shrunk by [ScaledDown]. An
 * intermediate version drew simplified look-alikes instead; they read as
 * diagrams of the app rather than the app, and they drift the moment the real
 * cards change.
 *
 * **Show every state at once, not one at a time.** These were animated
 * originally, cycling a single card through its states. The cards differ in
 * height, so the page heading visibly jumped on each swap, and continuous
 * animation is a poor bet on the low-end phones this app is most useful on.
 *
 * The one place motion earns its keep is [NfcPreview] (its own file), where
 * the *gesture* is the thing being explained and no still image conveys it.
 */

/**
 * What the app is for, said once.
 *
 * This page used to animate a grid of squares going dark, which read as
 * decoration rather than explanation — it never said what nfcGuard actually
 * does. Three lines do, and they double as a map of the three pages that
 * follow.
 */
@Composable
fun PurposePreview(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        PurposeLine(Icons.Default.Block, stringResource(R.string.modes_block), stringResource(R.string.onb_the_apps_that_pull_you))
        PurposeLine(Icons.Default.Nfc, stringResource(R.string.onb_lock), stringResource(R.string.onb_it_behind_a_physical_tag))
        PurposeLine(Icons.Default.LockOpen, stringResource(R.string.onb_tap), stringResource(R.string.onb_the_tag_when_you_really))
    }
}

@Composable
private fun PurposeLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    word: String,
    rest: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = GuardianTheme.IconPrimary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(word, style = GuardianType.Title, color = GuardianTheme.TextPrimary)
            Text(rest, style = GuardianType.EmptyHint, color = GuardianTheme.TextSecondary)
        }
    }
}

/**
 * Three real [ModeCard]s, shrunk: set up, running, and temporarily unlocked.
 *
 * An earlier pass replaced these with hand-drawn stand-ins, which was a
 * mistake — a diagram of a card teaches you a diagram. These are the exact
 * composable the Modes screen uses, with the same fonts, badges and colours,
 * just rendered smaller by [ScaledDown] so all three states fit at once.
 * Whatever the app looks like, this page looks like it too, for free.
 */
@Composable
fun ModesPreview(modifier: Modifier = Modifier) {
    val now = remember { System.currentTimeMillis() }
    val deepWork = stringResource(R.string.onb_deep_work)
    val kitchenTag = stringResource(R.string.onb_kitchen_tag)
    val mode = remember(deepWork) {
        Mode(
            id = "preview",
            name = deepWork,
            blockedApps = List(7) { "app.$it" },
            blockMode = BlockMode.BLOCK_SELECTED,
            nfcTagIds = listOf("tag")
        )
    }
    val tags = remember(kitchenTag) { listOf(NfcTag(id = "tag", name = kitchenTag)) }

    ScaledDown(scale = CARD_SCALE, modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Configured, waiting to be switched on.
            ModeCard(
                mode = mode, isActive = false, nfcTags = tags,
                onActivate = {}, onEdit = {}, onDelete = {}, onUnpause = {}
            )
            // Switched on by hand.
            ModeCard(
                mode = mode, isActive = true, isManual = true, nfcTags = tags,
                onActivate = {}, onEdit = {}, onDelete = {}, onUnpause = {}
            )
            // Unlocked with a tag, coming back by itself.
            ModeCard(
                mode = mode, isActive = false, isPaused = true,
                pausedUntil = now + 15 * 60_000L, now = now, nfcTags = tags,
                onActivate = {}, onEdit = {}, onDelete = {}, onUnpause = {}
            )
        }
    }
}

/**
 * One real [ScheduleCard], configured and running.
 *
 * The card already states its days, its hours and what it turns on, so there
 * is nothing left to explain around it.
 */
@Composable
fun SchedulesPreview(modifier: Modifier = Modifier) {
    val workHours = stringResource(R.string.onb_work_hours)
    val deepWork = stringResource(R.string.onb_deep_work)
    val schedule = remember(workHours) {
        Schedule(
            id = "preview",
            name = workHours,
            timeSlot = TimeSlot((1..5).map { DayTime(it, 9, 0, 17, 0) }),
            linkedModeIds = listOf("m"),
            hasEndTime = true
        )
    }
    val modes = remember(deepWork) {
        listOf(Mode(id = "m", name = deepWork, blockedApps = listOf("a")))
    }

    ScaledDown(scale = CARD_SCALE, modifier = modifier.fillMaxWidth()) {
        ScheduleCard(
            schedule = schedule,
            modes = modes,
            scheduleState = ScheduleState.ACTIVE,
            isInTimeRange = true,
            onActivate = {}, onEdit = {}, onDelete = {}
        )
    }
}

/**
 * The real stats pieces, shrunk, over a made-up Thursday: the headline,
 * this week's chart with its legend, and the apps compared week on week.
 * Last week's chart is left out to keep the page short; the app rows
 * already carry the comparison.
 *
 * The app "package names" are display names: [InstalledApps] falls back to
 * the package name for anything not installed, so they render as written.
 */
@Composable
fun StatsPreview(modifier: Modifier = Modifier) {
    val summary = remember {
        fun day(stops: Int, tags: Int = 0) = DayStats(blockedAttempts = mapOf("x" to stops), nfcUnlocks = tags)
        StatsSummary(
            thisWeek = listOf(day(4), day(3, tags = 1), day(3), day(1), DayStats(), DayStats(), DayStats()),
            lastWeek = List(7) { DayStats() },
            todayIndex = 3,
            attemptsThisWeek = 11,
            attemptsLastWeekSoFar = 16,
            apps = listOf(
                AppTrend("YouTube", lastWeek = 12, thisWeek = 5),
                AppTrend("Instagram", lastWeek = 4, thisWeek = 4),
                AppTrend("Reddit", lastWeek = 0, thisWeek = 2)
            )
        )
    }

    ScaledDown(scale = CARD_SCALE, modifier = modifier.fillMaxWidth()) {
        Column {
            Headline(summary)
            Spacer(Modifier.height(32.dp))
            WeekChart(
                stringResource(R.string.stats_this_week),
                summary.thisWeek,
                max = summary.thisWeek.maxOf { it.total },
                todayIndex = summary.todayIndex
            )
            Spacer(Modifier.height(16.dp))
            Legend(summary.thisWeek)
            Spacer(Modifier.height(32.dp))
            Apps(summary.apps)
        }
    }
}
