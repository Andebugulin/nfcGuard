package com.andebugulin.nfcguard.ui.stats

import com.andebugulin.nfcguard.R
import com.andebugulin.nfcguard.AppTrend
import com.andebugulin.nfcguard.Change
import com.andebugulin.nfcguard.DayStats
import com.andebugulin.nfcguard.StatsLogic
import com.andebugulin.nfcguard.StatsSummary
import com.andebugulin.nfcguard.data.InstalledApps
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.GuardianViewModel
import com.andebugulin.nfcguard.ui.components.GuardianType
import com.andebugulin.nfcguard.ui.components.ScreenHeader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRightAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

private const val TOP_APP_LIMIT = 3

// Soft white: pure white bars glare on the black background.
private val StoppedColor = Color(0xFFD8D8D8)
private val TagColor = GuardianTheme.TerminalYellow
private val EmergencyColor = GuardianTheme.TerminalRed
private val AppNameColor = Color(0xFFB8B8B8)

private val Change.color: Color
    get() = when (this) {
        Change.FEWER -> GuardianTheme.TerminalGreen
        Change.SIMILAR -> GuardianTheme.TerminalYellow
        Change.MORE -> GuardianTheme.TerminalRed
    }

/**
 * Colour of last week's count in an app row: the side that was worse.
 * Down from red to green, flat all yellow, up from yellow to red.
 */
private val Change.fromColor: Color
    get() = when (this) {
        Change.FEWER -> GuardianTheme.TerminalRed
        Change.SIMILAR, Change.MORE -> GuardianTheme.TerminalYellow
    }

/**
 * How often nfcGuard stopped you this week and whether that is going down,
 * then this week and last week day by day (stops, tag unlocks and
 * emergencies stacked), and the apps behind it compared week on week.
 * No boxes or headings; spacing does the grouping.
 */
@Composable
fun StatsScreen(viewModel: GuardianViewModel, onBack: () -> Unit) {
    val log by viewModel.statsLog.collectAsState()
    val summary = remember(log) { StatsLogic.summarize(log, LocalDate.now()) }
    // One scale for both weeks, so their bars compare directly.
    val max = remember(summary) {
        (summary.thisWeek + summary.lastWeek).maxOf { it.total }.coerceAtLeast(1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(GuardianTheme.BackgroundPrimary)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        ScreenHeader(title = stringResource(R.string.stats_title), onBack = onBack)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 32.dp)
        ) {
            Headline(summary)

            Spacer(Modifier.height(48.dp))
            // Last week first, so this week sits right above the legend that counts it.
            WeekChart(stringResource(R.string.stats_last_week), summary.lastWeek, max, todayIndex = null)
            Spacer(Modifier.height(32.dp))
            WeekChart(stringResource(R.string.stats_this_week), summary.thisWeek, max, todayIndex = summary.todayIndex)
            Spacer(Modifier.height(20.dp))
            Legend(summary.thisWeek)

            if (summary.apps.isNotEmpty()) {
                Spacer(Modifier.height(48.dp))
                Apps(summary.apps.take(TOP_APP_LIMIT))
            }
        }
    }
}

internal val DayStats.total: Int get() = attemptCount + nfcUnlocks + recoveries

@Composable
internal fun Headline(summary: StatsSummary) {
    val now = summary.attemptsThisWeek
    Text(now.toString(), style = GuardianType.Countdown, color = GuardianTheme.TextPrimary)
    Text(
        pluralStringResource(R.plurals.stats_label, now),
        style = GuardianType.Label,
        color = GuardianTheme.TextSecondary
    )
    val last = summary.attemptsLastWeekSoFar ?: return
    Spacer(Modifier.height(8.dp))
    Text(
        when {
            now < last -> stringResource(R.string.stats_trend_down, last - now)
            now > last -> stringResource(R.string.stats_trend_up, now - last)
            else -> stringResource(R.string.stats_trend_same)
        },
        style = GuardianType.Label,
        color = when {
            now < last -> Change.FEWER.color
            now > last -> Change.MORE.color
            else -> GuardianTheme.TextTertiary
        }
    )
}

private val BarWidth = 10.dp
private val ChartHeight = 64.dp
/** A one-off tag unlock on a heavy day still has to read as a box. */
private val MinSegment = 4.dp
private val Stroke = 1.dp
/** Gap between stacked segments, so neighbouring outlines stay distinct. */
private val SegmentGap = 2.dp

/**
 * Monday to Sunday, one stacked bar of hollow segments per day: stops
 * (white) at the base, tag unlocks (yellow) and emergencies (red) above.
 * An empty day is a dot;
 * days still ahead in [todayIndex]'s week are blank.
 */
@Composable
internal fun WeekChart(label: String, days: List<DayStats>, max: Int, todayIndex: Int?) {
    val locale = Locale.getDefault()
    Text(label, style = GuardianType.Meta, color = GuardianTheme.TextTertiary)
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth().height(ChartHeight), verticalAlignment = Alignment.Bottom) {
        days.forEachIndexed { i, day ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                val isFuture = todayIndex != null && i > todayIndex
                when {
                    isFuture -> Unit
                    day.total == 0 -> Box(Modifier.size(BarWidth, 1.dp).background(GuardianTheme.TextDisabled))
                    else -> Column(Modifier.width(BarWidth), verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                        Segment(day.recoveries, max, EmergencyColor)
                        Segment(day.nfcUnlocks, max, TagColor)
                        Segment(day.attemptCount, max, StoppedColor)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        DayOfWeek.entries.forEachIndexed { i, dow ->
            Text(
                dow.getDisplayName(DateTextStyle.NARROW, locale),
                style = GuardianType.Meta,
                color = if (i == todayIndex) GuardianTheme.TextPrimary else GuardianTheme.TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun Segment(count: Int, max: Int, color: Color) {
    if (count == 0) return
    val height: Dp = maxOf(MinSegment, ChartHeight * (count.toFloat() / max))
    Box(Modifier.fillMaxWidth().height(height).border(Stroke, color))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Legend(week: List<DayStats>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LegendItem(StoppedColor, stringResource(R.string.stats_legend_stopped), week.sumOf { it.attemptCount })
        LegendItem(TagColor, stringResource(R.string.stats_legend_tag), week.sumOf { it.nfcUnlocks })
        LegendItem(EmergencyColor, stringResource(R.string.stats_legend_emergency), week.sumOf { it.recoveries })
    }
}

@Composable
private fun LegendItem(color: Color, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).border(Stroke, color))
        Spacer(Modifier.width(8.dp))
        Text("$label $count", style = GuardianType.Meta, color = GuardianTheme.TextSecondary)
    }
}

/** Last week's and this week's counts sit in fixed-width columns. */
private val CountColumn = 28.dp

private val CountStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

/**
 * "YouTube   21 → 9", coloured by direction (see [fromColor]). Monospace with fixed columns so the numbers and arrows line up
 * from row to row.
 */
@Composable
internal fun Apps(apps: List<AppTrend>) {
    val context = LocalContext.current
    // PackageManager lookups are too slow for the main thread.
    val names by produceState<List<String>?>(null, apps) {
        value = withContext(Dispatchers.IO) { apps.map { InstalledApps.label(context, it.packageName) } }
    }
    val resolved = names ?: return

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        apps.forEachIndexed { i, app ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    resolved[i],
                    style = GuardianType.Body,
                    color = AppNameColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    app.lastWeek.toString(),
                    style = CountStyle,
                    color = app.change.fromColor,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(CountColumn)
                )
                // An icon, not "→": monospace has no arrow glyph and the
                // fallback font draws it small and below the baseline.
                Icon(
                    Icons.AutoMirrored.Filled.ArrowRightAlt,
                    contentDescription = null,
                    tint = app.change.color,
                    modifier = Modifier.padding(horizontal = 6.dp).size(16.dp)
                )
                Text(
                    app.thisWeek.toString(),
                    style = CountStyle.copy(fontWeight = FontWeight.Bold),
                    color = app.change.color,
                    modifier = Modifier.width(CountColumn)
                )
            }
        }
    }
}
