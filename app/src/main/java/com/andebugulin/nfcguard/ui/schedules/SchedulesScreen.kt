package com.andebugulin.nfcguard.ui.schedules

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import java.time.format.TextStyle
import java.time.DayOfWeek
import com.andebugulin.nfcguard.R
import androidx.compose.ui.res.stringResource
import com.andebugulin.nfcguard.ScheduleClock
import com.andebugulin.nfcguard.ActivationResult
import com.andebugulin.nfcguard.AppState
import com.andebugulin.nfcguard.Mode
import com.andebugulin.nfcguard.Schedule
import com.andebugulin.nfcguard.TimeSlot
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.TestTags
import com.andebugulin.nfcguard.ui.GuardianViewModel
import com.andebugulin.nfcguard.ui.safety.SafeRegimeChallengeDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.andebugulin.nfcguard.ui.components.ScreenHeader
import com.andebugulin.nfcguard.ui.components.EmptyState
import com.andebugulin.nfcguard.ui.components.GuardianButton
import com.andebugulin.nfcguard.ui.BLOCK_MODE_CONFLICT_MESSAGE
import androidx.compose.foundation.layout.heightIn
import com.andebugulin.nfcguard.NfcTag
import com.andebugulin.nfcguard.ui.components.SelectableOption
import com.andebugulin.nfcguard.ui.components.DialogKind
import com.andebugulin.nfcguard.ui.components.GuardianDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState

enum class ScheduleState {
    NONE,        // Not in schedule time OR schedule ended
    ACTIVE,      // Schedule activated modes (currently running)
    DEACTIVATED  // User deactivated during schedule time
}

internal fun isInScheduleTime(schedule: Schedule): Boolean {
    val moment = ScheduleClock.momentOf(System.currentTimeMillis())
    return ScheduleClock.isRunning(schedule, moment.day, moment.minuteOfDay)
}

private fun getScheduleState(schedule: Schedule, appState: AppState): ScheduleState {
    val inScheduleTime = isInScheduleTime(schedule)

    if (!inScheduleTime) {
        return ScheduleState.NONE
    }

    // In schedule time - check if deactivated by user
    if (appState.deactivatedSchedules.contains(schedule.id)) {
        return ScheduleState.DEACTIVATED
    }

    // In schedule time and not deactivated - check if THIS SCHEDULE is active
    // Primary check: activeSchedules flag set by AlarmReceiver
    // Fallback: if linked modes are active, schedule is effectively active
    val inActiveSchedules = appState.activeSchedules.contains(schedule.id)
    val linkedModesActive = schedule.linkedModeIds.isNotEmpty() &&
            schedule.linkedModeIds.any { appState.activeModes.contains(it) }

    return if (inActiveSchedules || linkedModesActive) {
        ScheduleState.ACTIVE
    } else {
        ScheduleState.NONE
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(
    viewModel: GuardianViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appState by viewModel.appState.collectAsState()
    val safeRegimeEnabled by viewModel.safeRegimeEnabled.collectAsState()
    val challengeDuration by viewModel.challengeDurationSeconds.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<Schedule?>(null) }
    var taggingSchedule by remember { mutableStateOf<Schedule?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Schedule?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Safe Regime challenge state
    var showSafeRegimeChallenge by remember { mutableStateOf(false) }
    var challengeDescription by remember { mutableStateOf("") }
    var pendingChallengeAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Pending save from ScheduleEditorDialog (when challenge is needed on save)
    var pendingSaveName by remember { mutableStateOf("") }
    var pendingSaveTimeSlot by remember { mutableStateOf<TimeSlot?>(null) }
    var pendingSaveLinkedModeIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingSaveHasEndTime by remember { mutableStateOf(false) }
    var pendingSaveScheduleId by remember { mutableStateOf<String?>(null) } // null = create, non-null = update

    /** Request a safe regime challenge. If safe regime is off or no modes active, runs immediately. */
    fun requireChallengeOrRun(description: String, action: () -> Unit) {
        if (safeRegimeEnabled && appState.activeModes.isNotEmpty()) {
            challengeDescription = description
            pendingChallengeAction = action
            showSafeRegimeChallenge = true
        } else {
            action()
        }
    }

    // Show challenge dialog
    if (showSafeRegimeChallenge) {
        SafeRegimeChallengeDialog(
            actionDescription = challengeDescription,
            totalDurationSeconds = challengeDuration,
            onComplete = {
                showSafeRegimeChallenge = false
                pendingChallengeAction?.invoke()
                pendingChallengeAction = null
                // Also execute pending save if exists
                val ts = pendingSaveTimeSlot
                if (ts != null) {
                    val id = pendingSaveScheduleId
                    if (id != null) {
                        viewModel.updateSchedule(id, pendingSaveName, ts, pendingSaveLinkedModeIds, pendingSaveHasEndTime)
                    } else {
                        viewModel.addSchedule(pendingSaveName, ts, pendingSaveLinkedModeIds, pendingSaveHasEndTime)
                    }
                    pendingSaveTimeSlot = null
                    pendingSaveScheduleId = null
                    editingSchedule = null
                    showAddDialog = false
                }
            },
            onCancel = {
                showSafeRegimeChallenge = false
                pendingChallengeAction = null
                pendingSaveTimeSlot = null
                pendingSaveScheduleId = null
            }
        )
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = GuardianTheme.ErrorDark,
                    contentColor = GuardianTheme.ErrorText,
                    shape = RoundedCornerShape(0.dp)
                )
            }
        },
        containerColor = GuardianTheme.BackgroundPrimary
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).background(GuardianTheme.BackgroundPrimary)) {
            Column(Modifier.fillMaxSize()) {
                ScreenHeader(title = stringResource(R.string.home_schedules), onBack = onBack)

                if (appState.schedules.isEmpty()) {
                    EmptyState(
                        label = stringResource(R.string.sched_no_schedules),
                        // FIX #12: a schedule with nothing to switch on is useless,
                        // so say what to do first rather than offering a dead button.
                        secondary = stringResource(R.string.sched_create_at_least_one_mode).takeIf { appState.modes.isEmpty() }
                    ) {
                        GuardianButton(
                            label = if (appState.modes.isNotEmpty()) stringResource(R.string.sched_create_schedule) else stringResource(R.string.sched_create_modes_first),
                            onClick = { showAddDialog = true },
                            enabled = appState.modes.isNotEmpty(),
                            modifier = Modifier.testTag(TestTags.Schedules.ADD)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(appState.schedules.size) { index ->
                            val schedule = appState.schedules[index]
                            ScheduleCard(
                                schedule = schedule,
                                modes = appState.modes,
                                scheduleState = getScheduleState(schedule, appState),
                                isInTimeRange = isInScheduleTime(schedule),
                                onActivate = {
                                    val result = viewModel.activateScheduleManually(schedule.id)
                                    if (result == ActivationResult.BLOCK_MODE_CONFLICT) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(BLOCK_MODE_CONFLICT_MESSAGE)
                                            )
                                        }
                                    }
                                },
                                onLinkTags = if (appState.nfcTags.isEmpty()) null else {
                                    { taggingSchedule = schedule }
                                },
                                onEdit = {
                                    val isActive = getScheduleState(schedule, appState) == ScheduleState.ACTIVE
                                    if (safeRegimeEnabled && isActive) {
                                        requireChallengeOrRun(context.getString(R.string.sched_editing_an_active_schedule_could)) {
                                            editingSchedule = schedule
                                        }
                                    } else {
                                        editingSchedule = schedule
                                    }
                                },
                                onDelete = {
                                    val isActive = getScheduleState(schedule, appState) == ScheduleState.ACTIVE
                                    if (safeRegimeEnabled && isActive) {
                                        requireChallengeOrRun(context.getString(R.string.sched_deleting_an_active_schedule_could)) {
                                            showDeleteDialog = schedule
                                        }
                                    } else {
                                        showDeleteDialog = schedule
                                    }
                                }
                            )
                        }

                        item {
                            // FIX #12: Also disable "+ NEW SCHEDULE" if no modes
                            Button(
                                onClick = { showAddDialog = true },
                                enabled = appState.modes.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GuardianTheme.BackgroundSurface,
                                    contentColor = GuardianTheme.ButtonSecondaryText,
                                    disabledContainerColor = GuardianTheme.SurfaceDim,
                                    disabledContentColor = GuardianTheme.OnLightSurfaceSecondaryText
                                ),
                                shape = RoundedCornerShape(0.dp),
                                modifier = Modifier.testTag(TestTags.Schedules.ADD)
                                    .fillMaxWidth()
                                    .height(56.dp)
                            ) {
                                Text(
                                    if (appState.modes.isNotEmpty()) stringResource(R.string.sched_new_schedule_2) else stringResource(R.string.sched_create_modes_first),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    } // end Scaffold content

    taggingSchedule?.let { schedule ->
        ScheduleTagsDialog(
            schedule = schedule,
            modes = appState.modes,
            tags = appState.nfcTags,
            onDismiss = { taggingSchedule = null },
            onConfirm = { tagIds ->
                viewModel.linkTagsToScheduleModes(schedule.id, tagIds)
                taggingSchedule = null
            }
        )
    }

    if (showAddDialog) {
        ScheduleEditorDialog(
            modes = appState.modes,
            activeModeIds = appState.activeModes,
            safeRegimeEnabled = safeRegimeEnabled,
            existingNames = appState.schedules.map { it.name },  // FIX #6
            onDismiss = { showAddDialog = false },
            onSave = { name, timeSlot, linkedModeIds, hasEndTime ->
                val linksActiveMode = linkedModeIds.any { appState.activeModes.contains(it) }
                if (safeRegimeEnabled && linksActiveMode) {
                    // Store pending save and trigger challenge
                    pendingSaveName = name
                    pendingSaveTimeSlot = timeSlot
                    pendingSaveLinkedModeIds = linkedModeIds
                    pendingSaveHasEndTime = hasEndTime
                    pendingSaveScheduleId = null
                    challengeDescription = context.getString(R.string.sched_creating_a_schedule_linked_to)
                    pendingChallengeAction = null
                    showSafeRegimeChallenge = true
                } else {
                    viewModel.addSchedule(name, timeSlot, linkedModeIds, hasEndTime)
                    showAddDialog = false
                }
            }
        )
    }

    editingSchedule?.let { schedule ->
        ScheduleEditorDialog(
            existingSchedule = schedule,
            modes = appState.modes,
            activeModeIds = appState.activeModes,
            safeRegimeEnabled = safeRegimeEnabled,
            existingNames = appState.schedules.filter { it.id != schedule.id }.map { it.name },  // FIX #6
            onDismiss = { editingSchedule = null },
            onSave = { name, timeSlot, linkedModeIds, hasEndTime ->
                val scheduleActive = getScheduleState(schedule, appState) == ScheduleState.ACTIVE
                val linksActiveMode = linkedModeIds.any { appState.activeModes.contains(it) }
                if (safeRegimeEnabled && (scheduleActive || linksActiveMode)) {
                    pendingSaveName = name
                    pendingSaveTimeSlot = timeSlot
                    pendingSaveLinkedModeIds = linkedModeIds
                    pendingSaveHasEndTime = hasEndTime
                    pendingSaveScheduleId = schedule.id
                    challengeDescription = context.getString(R.string.sched_modifying_this_schedule_while_modes)
                    pendingChallengeAction = null
                    showSafeRegimeChallenge = true
                } else {
                    viewModel.updateSchedule(schedule.id, name, timeSlot, linkedModeIds, hasEndTime)
                    editingSchedule = null
                }
            }
        )
    }

    showDeleteDialog?.let { schedule ->
        val scheduleState = getScheduleState(schedule, appState)
        val scheduleIsActive = scheduleState == ScheduleState.ACTIVE

        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor = GuardianTheme.ButtonSecondary,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.border(
                width = GuardianTheme.DialogBorderWidth,
                color = GuardianTheme.DialogBorderDelete,
                shape = RoundedCornerShape(0.dp)
            ),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = GuardianTheme.Error,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        stringResource(R.string.sched_delete_schedule),
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        color = GuardianTheme.TextPrimary
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                schedule.name.uppercase(),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                pluralStringResource(R.plurals.common_linked_modes, schedule.linkedModeIds.size, schedule.linkedModeIds.size),
                                fontSize = 11.sp,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    if (scheduleIsActive && schedule.linkedModeIds.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(0.dp),
                            color = GuardianTheme.WarningBackground
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    stringResource(R.string.sched_important),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = GuardianTheme.Warning,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    stringResource(R.string.sched_linked_modes_will_stay_active),
                                    fontSize = 11.sp,
                                    color = GuardianTheme.Warning,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.ErrorDark
                    ) {
                        Text(
                            stringResource(R.string.modes_this_action_cannot_be_undone),
                            fontSize = 12.sp,
                            color = GuardianTheme.ErrorText,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSchedule(schedule.id)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardianTheme.Error,
                        contentColor = GuardianTheme.ButtonSecondaryText
                    ),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.testTag(TestTags.Schedules.DELETE_CONFIRM)
                ) {
                    Text(
                        stringResource(R.string.modes_delete),
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = null },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = GuardianTheme.TextSecondary
                    )
                ) {
                    Text(stringResource(R.string.home_cancel), letterSpacing = 1.sp)
                }
            }
        )
    }
}

@Composable
fun ScheduleCard(
    schedule: Schedule,
    modes: List<Mode>,
    scheduleState: ScheduleState,
    isInTimeRange: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLinkTags: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = GuardianTheme.BackgroundSurface
    ) {
        Column(Modifier.padding(20.dp)) {
            // Title with state indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    schedule.name.uppercase(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = GuardianTheme.TextPrimary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f)
                )

                when (scheduleState) {
                    ScheduleState.ACTIVE -> {
                        Surface(
                            shape = RoundedCornerShape(0.dp),
                            color = GuardianTheme.TextPrimary
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(GuardianTheme.BackgroundPrimary)
                                )
                                Text(
                                    stringResource(R.string.home_active),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    color = GuardianTheme.BackgroundSurface,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                    ScheduleState.DEACTIVATED -> {
                        Surface(
                            shape = RoundedCornerShape(0.dp),
                            color = GuardianTheme.BackgroundSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, GuardianTheme.TextDisabled)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(GuardianTheme.TextSecondary)
                                )
                                Text(
                                    stringResource(R.string.sched_deactivated),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    color = GuardianTheme.TextSecondary,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                    ScheduleState.NONE -> {
                        // No badge
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // Show each day's time
            schedule.timeSlot.dayTimes.sortedBy { it.day }.forEach { dayTime ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = GuardianTheme.IconSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        buildString {
                            append("${getShortDayName(dayTime.day)} ")
                            append(String.format("%02d:%02d", dayTime.startHour, dayTime.startMinute))
                            if (schedule.hasEndTime) {
                                append(" - ${String.format("%02d:%02d", dayTime.endHour, dayTime.endMinute)}")
                            }
                        },
                        fontSize = 11.sp,
                        color = GuardianTheme.TextSecondary,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (schedule.linkedModeIds.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    schedule.linkedModeIds.forEach { modeId ->
                        modes.find { it.id == modeId }?.let { mode ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = GuardianTheme.TextTertiary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    mode.name.uppercase(),
                                    fontSize = 10.sp,
                                    color = GuardianTheme.TextTertiary,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    stringResource(R.string.sched_no_modes_linked),
                    fontSize = 10.sp,
                    color = GuardianTheme.TextDisabled,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Show ACTIVATE button when schedule is in time range but not active (NONE or DEACTIVATED)
                if (isInTimeRange && scheduleState != ScheduleState.ACTIVE && schedule.linkedModeIds.isNotEmpty()) {
                    Button(
                        onClick = onActivate,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GuardianTheme.ButtonPrimary,
                            contentColor = GuardianTheme.ButtonPrimaryText
                        ),
                        shape = RoundedCornerShape(0.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(stringResource(R.string.modes_activate), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }

                if (onLinkTags != null) {
                    TextButton(
                        onClick = onLinkTags,
                        modifier = Modifier.testTag(TestTags.Schedules.linkTags(schedule.id))
                    ) {
                        Text(stringResource(R.string.sched_tags), fontSize = 11.sp, color = GuardianTheme.TextPrimary, letterSpacing = 1.sp)
                    }
                }
                TextButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag(TestTags.Schedules.edit(schedule.id))
                ) {
                    Text(stringResource(R.string.modes_edit), fontSize = 11.sp, color = GuardianTheme.TextPrimary, letterSpacing = 1.sp)
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag(TestTags.Schedules.delete(schedule.id))
                ) {
                    Text(stringResource(R.string.modes_delete), fontSize = 11.sp, color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
                }
            }
        }
    }
}

/**
 * Add tags to every mode this schedule switches on.
 *
 * A schedule has no tag of its own — it activates modes, and tags unlock modes.
 * Rather than invent a second relationship for the same idea, this writes
 * straight into the linked modes, which is what the user means by "unlock this
 * schedule". It only ever adds, so it cannot silently strip a tag that a mode
 * was relying on, and it lives on the card rather than in the editor because it
 * edits modes, not the schedule, and must not ride on the editor's save.
 */
@Composable
private fun ScheduleTagsDialog(
    schedule: Schedule,
    modes: List<Mode>,
    tags: List<NfcTag>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val linkedModes = modes.filter { it.id in schedule.linkedModeIds }
    var selected by remember(schedule.id) { mutableStateOf(emptySet<String>()) }

    GuardianDialog(
        title = stringResource(R.string.sched_unlock_tags),
        message = if (linkedModes.isEmpty()) {
            stringResource(R.string.sched_this_schedule_has_no_modes)
        } else {
            stringResource(R.string.sched_adds_to, linkedModes.joinToString(", ") { it.name.uppercase() })
        },
        detail = stringResource(R.string.sched_tags_are_linked_to_modes),
        kind = DialogKind.Edit,
        confirmLabel = stringResource(R.string.sched_add),
        onConfirm = { onConfirm(selected) },
        confirmEnabled = selected.isNotEmpty() && linkedModes.isNotEmpty(),
        confirmModifier = Modifier.testTag(TestTags.Schedules.TAGS_SAVE),
        dismissLabel = stringResource(R.string.home_cancel),
        onDismiss = onDismiss,
        dismissModifier = Modifier.testTag(TestTags.Schedules.TAGS_CANCEL)
    ) {
        if (linkedModes.isNotEmpty()) {
            Column(
                Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tags.forEach { tag ->
                    SelectableOption(
                        label = tag.name.uppercase(),
                        selected = tag.id in selected,
                        onSelect = {
                            selected = if (tag.id in selected) selected - tag.id
                            else selected + tag.id
                        },
                        modifier = Modifier.testTag(TestTags.Schedules.tagOption(tag.id))
                    )
                }
            }
        }
    }
}

/** Full weekday name in the user's language, upper-cased to match the UI. */
fun getDayName(day: Int): String {
    val locale = Locale.getDefault()
    return DayOfWeek.of(day).getDisplayName(TextStyle.FULL, locale).uppercase(locale)
}

/** Short weekday name ("MON", "LUN", "月") in the user's language. */
fun getShortDayName(day: Int): String {
    val locale = Locale.getDefault()
    return DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, locale).uppercase(locale)
}
