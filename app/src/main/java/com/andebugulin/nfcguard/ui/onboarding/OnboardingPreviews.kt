package com.andebugulin.nfcguard.ui.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.components.GuardianType
import kotlinx.coroutines.delay
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
 * The one place motion earns its keep is [NfcPreview], where the *gesture* is
 * the thing being explained and no still image conveys it.
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
        PurposeLine(Icons.Default.Block, "BLOCK", "the apps that pull you in")
        PurposeLine(Icons.Default.Nfc, "LOCK", "it behind a physical tag")
        PurposeLine(Icons.Default.LockOpen, "TAP", "the tag when you really need it")
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
    val mode = remember {
        Mode(
            id = "preview",
            name = "Deep work",
            blockedApps = List(7) { "app.$it" },
            blockMode = BlockMode.BLOCK_SELECTED,
            nfcTagIds = listOf("tag")
        )
    }
    val tags = remember { listOf(NfcTag(id = "tag", name = "Kitchen tag")) }

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
    val schedule = remember {
        Schedule(
            id = "preview",
            name = "Work hours",
            timeSlot = TimeSlot((1..5).map { DayTime(it, 9, 0, 17, 0) }),
            linkedModeIds = listOf("m"),
            hasEndTime = true
        )
    }
    val modes = remember {
        listOf(Mode(id = "m", name = "Deep work", blockedApps = listOf("a")))
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
 * The tap itself.
 *
 * Tag and phone start apart with the phone locked, meet in the middle, and the
 * phone opens. No counter: an earlier version ticked a duration down here,
 * which drew the eye away from the gesture and explained a detail nobody needs
 * before their first unlock.
 */
@Composable
fun NfcPreview(modifier: Modifier = Modifier) {
    var together by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1400)
            together = 1 - together
        }
    }

    val progress by animateFloatAsState(
        targetValue = together.toFloat(),
        animationSpec = tween(800),
        label = "approach"
    )
    val gap by animateDpAsState(
        targetValue = if (together == 1) 0.dp else 56.dp,
        animationSpec = tween(800),
        label = "gap"
    )
    val unlocked = progress > 0.9f

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Nfc,
                contentDescription = null,
                tint = GuardianTheme.NfcIcon,
                modifier = Modifier
                    .size(44.dp)
                    .offset(x = -gap)
            )
            Spacer(Modifier.width(24.dp))
            Box(
                Modifier
                    .offset(x = gap)
                    .width(72.dp)
                    .height(116.dp)
                    .border(
                        2.dp,
                        if (unlocked) GuardianTheme.Success else GuardianTheme.TextPrimary,
                        RoundedCornerShape(0.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (unlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (unlocked) GuardianTheme.Success else GuardianTheme.TextSecondary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Text(
            if (unlocked) "UNLOCKED" else "HOLD THE TAG TO THE PHONE",
            style = GuardianType.Meta,
            color = if (unlocked) GuardianTheme.Success else GuardianTheme.TextTertiary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}
