package com.andebugulin.nfcguard.ui.modes

import com.andebugulin.nfcguard.ui.formatDuration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import com.andebugulin.nfcguard.R
import androidx.compose.ui.res.stringResource
import com.andebugulin.nfcguard.ScheduleClock
import com.andebugulin.nfcguard.ActivationResult
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.Mode
import com.andebugulin.nfcguard.NfcTag
import com.andebugulin.nfcguard.Schedule
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.GuardianViewModel
import com.andebugulin.nfcguard.ui.TestTags
import com.andebugulin.nfcguard.ui.home.HomeScreen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.andebugulin.nfcguard.ui.components.ScreenHeader
import com.andebugulin.nfcguard.ui.components.EmptyState
import com.andebugulin.nfcguard.ui.components.GuardianButton
import com.andebugulin.nfcguard.ui.BLOCK_MODE_CONFLICT_MESSAGE
import androidx.compose.runtime.MutableState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModesScreen(
    viewModel: GuardianViewModel,
    onBack: () -> Unit,
    // Passed through to the editor so a tag can be registered mid-edit.
    // Nullable so the per-screen Robolectric tests can compose this without
    // standing up the Activity's NFC plumbing.
    scannedNfcTagId: MutableState<String?>? = null,
    nfcRegistrationMode: MutableState<Boolean>? = null
) {
    val context = LocalContext.current
    val appState by viewModel.appState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf<Mode?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Mode?>(null) }
    var showActivationOptionsDialog by remember { mutableStateOf<Mode?>(null) }

    // Tick every 30s so timer countdowns in ModeCards stay fresh
    var timeTick by remember { mutableStateOf(0L) }
    LaunchedEffect(appState.timedModeDeactivations, appState.timedModeReactivations) {
        while (appState.timedModeDeactivations.isNotEmpty() || appState.timedModeReactivations.isNotEmpty()) {
            kotlinx.coroutines.delay(30_000)
            timeTick = System.currentTimeMillis()
        }
    }
    val now = timeTick.let { System.currentTimeMillis() }

    // FIX #2: Snackbar for block mode conflict feedback
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(GuardianTheme.BackgroundPrimary)
        ) {
            Column(Modifier.fillMaxSize()) {
                ScreenHeader(title = stringResource(R.string.home_modes), onBack = onBack)

                // Modes list
                if (appState.modes.isEmpty()) {
                    EmptyState(label = stringResource(R.string.modes_no_modes)) {
                        GuardianButton(
                            label = stringResource(R.string.modes_create_mode),
                            onClick = { showAddDialog = true },
                            modifier = Modifier.testTag(TestTags.Modes.ADD)
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(appState.modes, key = { it.id }) { mode ->
                            // Compute schedule end time for this mode
                            val scheduleEndStr = ScheduleClock.modeHeldUntil(
                                appState, mode.id, ScheduleClock.momentOf(now)
                            )?.let(ScheduleClock::format)

                            ModeCard(
                                mode = mode,
                                isActive = appState.activeModes.contains(mode.id),
                                isPaused = appState.timedModeReactivations.containsKey(mode.id),
                                isManual = appState.manuallyActivatedModes.contains(mode.id),
                                timedUntil = appState.timedModeDeactivations[mode.id],
                                pausedUntil = appState.timedModeReactivations[mode.id],
                                now = now,
                                nfcTags = (appState.nfcTags + NfcTag("ANY", "ANY")).filter { mode.nfcTagIds.contains(it.id) },
                                scheduleEndTime = scheduleEndStr,
                                onActivate = {
                                    showActivationOptionsDialog = mode
                                },
                                onEdit = { selectedMode = mode },
                                onDelete = { showDeleteDialog = mode },
                                onUnpause = { viewModel.reactivateMode(mode.id) }
                            )
                        }

                        item {
                            Button(
                                onClick = { showAddDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GuardianTheme.BackgroundSurface,
                                    contentColor = GuardianTheme.ButtonSecondaryText
                                ),
                                shape = RoundedCornerShape(0.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag(TestTags.Modes.ADD)
                            ) {
                                Text(
                                    stringResource(R.string.modes_new_mode_2),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ModeNameDialog(
            existingNames = appState.modes.map { it.name },  // FIX #6
            onDismiss = { showAddDialog = false }
        ) { name ->
            showAddDialog = false
            selectedMode = Mode(java.util.UUID.randomUUID().toString(), name, emptyList())
        }
    }

    showDeleteDialog?.let { mode ->
        // FIX #9: Find linked schedules to warn user
        val linkedSchedules = appState.schedules.filter { it.linkedModeIds.contains(mode.id) }

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
                        stringResource(R.string.modes_delete_mode),
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
                                mode.name.uppercase(),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                pluralStringResource(R.plurals.modes_app_count, mode.blockedApps.size, mode.blockedApps.size),
                                fontSize = 11.sp,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    // FIX #9: Warn about linked schedules
                    if (linkedSchedules.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(0.dp),
                            color = GuardianTheme.WarningBackground
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    stringResource(R.string.modes_linked_schedules_affected),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = GuardianTheme.Warning,
                                    letterSpacing = 1.sp
                                )
                                linkedSchedules.forEach { sched ->
                                    val remainingModes = sched.linkedModeIds.count { it != mode.id }
                                    Text(
                                        pluralStringResource(R.plurals.modes_schedule_remaining_modes, remainingModes, sched.name.uppercase(), remainingModes),
                                        fontSize = 11.sp,
                                        color = GuardianTheme.Warning,
                                        letterSpacing = 0.5.sp
                                    )
                                }
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
                        viewModel.deleteMode(mode.id)
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardianTheme.Error,
                        contentColor = GuardianTheme.ButtonSecondaryText
                    ),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.testTag(TestTags.Modes.DELETE_CONFIRM)
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
            },
        )
    }

    // Activation Options Dialog (Feature 3)
    showActivationOptionsDialog?.let { mode ->
        ActivationOptionsDialog(
            mode = mode,
            hasLinkedSchedules = run {
                val moment = ScheduleClock.momentOf(System.currentTimeMillis())
                // A schedule will deactivate this mode if one of its end
                // alarms is still ahead today.
                appState.schedules.any { schedule ->
                    mode.id in schedule.linkedModeIds && ScheduleClock.hasEndAhead(schedule, moment)
                }
            },
            onDismiss = { showActivationOptionsDialog = null },
            onActivate = { timedUntilMillis ->
                showActivationOptionsDialog = null
                val result = viewModel.activateMode(mode.id, timedUntilMillis)
                if (result == ActivationResult.BLOCK_MODE_CONFLICT) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(BLOCK_MODE_CONFLICT_MESSAGE)
                        )
                    }
                }
            }
        )
    }

    selectedMode?.let { mode ->
        Box(modifier = Modifier.fillMaxSize()) {
            ModeEditorScreen(
                mode = mode,
                availableNfcTags = appState.nfcTags,
                allModes = appState.modes,  // FIX #8: pass all modes for NFC usage indicator
                onBack = { selectedMode = null },
                onSave = { apps, blockMode, nfcTagIds, tagUnlockLimits ->
                    if (appState.modes.any { it.id == mode.id }) {
                        viewModel.updateMode(mode.id, mode.name, apps, blockMode, nfcTagIds, tagUnlockLimits)
                    } else {
                        viewModel.addMode(mode.name, apps, blockMode, nfcTagIds, tagUnlockLimits)
                    }
                    selectedMode = null
                },
                scannedNfcTagId = scannedNfcTagId,
                nfcRegistrationMode = nfcRegistrationMode,
                onRegisterTag = viewModel::addNfcTag
            )
        }
    }
}

@Composable
fun ModeCard(
    mode: Mode,
    isActive: Boolean,
    isPaused: Boolean = false,
    isManual: Boolean = false,
    timedUntil: Long? = null,
    pausedUntil: Long? = null,
    now: Long = System.currentTimeMillis(),
    nfcTags: List<NfcTag>,
    scheduleEndTime: String? = null,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onUnpause: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = when {
            isActive -> Color.White
            isPaused -> GuardianTheme.Warning // Match HomeScreen yellow panel
            else -> GuardianTheme.BackgroundSurface
        }
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        mode.name.uppercase(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive || isPaused) Color.Black else Color.White,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.modes_card_summary, mode.blockedApps.size, stringResource(if (mode.blockMode == BlockMode.BLOCK_SELECTED) R.string.modes_block else R.string.modes_allow_only)),
                        fontSize = 10.sp,
                        color = if (isPaused) GuardianTheme.WarningAccentDim else GuardianTheme.TextTertiary,
                        letterSpacing = 1.sp
                    )
                    if (nfcTags.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Nfc,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = if (isPaused) GuardianTheme.WarningAccentDim else GuardianTheme.TextTertiary
                            )
                            Text(
                                stringResource(R.string.modes_linked_to, nfcTags.joinToString(", ") { it.name.uppercase() }),
                                fontSize = 10.sp,
                                color = if (isPaused) GuardianTheme.WarningAccentDim else GuardianTheme.TextTertiary,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    if (isActive || isPaused) {
                        Spacer(Modifier.height(4.dp))

                        if (isActive) {
                            val isTimed = timedUntil != null
                            val sourceLabel = when {
                                isTimed -> {
                                    val remaining = ((timedUntil ?: 0) - now) / 60000
                                    val endCal = java.util.Calendar.getInstance().apply {
                                        timeInMillis = timedUntil ?: 0
                                    }
                                    val endStr = ScheduleClock.format(endCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + endCal.get(java.util.Calendar.MINUTE))
                                    val prefix = if (isManual) stringResource(R.string.home_manual) else stringResource(R.string.home_active)
                                    stringResource(R.string.home_timed_label, prefix, formatDuration(remaining), endStr)
                                }
                                isManual -> stringResource(R.string.modes_manual_u00b7_nfc_to_unlock)
                                else -> {
                                    if (scheduleEndTime != null) stringResource(R.string.home_by_schedule_until, scheduleEndTime)
                                    else stringResource(R.string.modes_activated_by_schedule)
                                }
                            }
                            val sourceIcon = when {
                                isTimed -> Icons.Default.Timer
                                isManual -> Icons.Default.TouchApp
                                else -> Icons.Default.Schedule
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    sourceIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = GuardianTheme.OnLightSurfaceSecondaryText
                                )
                                Text(
                                    sourceLabel,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GuardianTheme.OnLightSurfaceSecondaryText,
                                    letterSpacing = 1.sp
                                )
                            }
                        } else if (isPaused) {
                            val remaining = if (pausedUntil != null) ((pausedUntil - now) / 60000) else null
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.LockOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = Color.Black
                                )
                                Text(
                                    if (remaining != null) stringResource(R.string.modes_paused_reenables_in, formatDuration(remaining)) else stringResource(R.string.modes_paused_permanently),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GuardianTheme.WarningAccentDim,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }

                if (!isActive && !isPaused) {
                    Button(
                        onClick = onActivate,
                        modifier = Modifier.testTag(TestTags.Modes.activate(mode.id)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GuardianTheme.ButtonPrimary,
                            contentColor = GuardianTheme.ButtonPrimaryText
                        ),
                        shape = RoundedCornerShape(0.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(stringResource(R.string.modes_activate), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                } else if (isActive) {
                    Text(
                        stringResource(R.string.home_active),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = GuardianTheme.BackgroundSurface,
                        letterSpacing = 1.sp
                    )
                } else if (isPaused) {
                    Text(
                        stringResource(R.string.modes_paused),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = GuardianTheme.WarningAccentDim,
                        letterSpacing = 1.sp
                    )
                }
            }

            if (!isActive) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isPaused) {
                        TextButton(
                            onClick = onUnpause,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.Black
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.home_re_enable),
                                fontSize = 11.sp,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    } else {
                        TextButton(
                            onClick = onEdit,
                            modifier = Modifier.testTag(TestTags.Modes.edit(mode.id))
                        ) {
                            Text(stringResource(R.string.modes_edit), fontSize = 11.sp, color = GuardianTheme.TextPrimary, letterSpacing = 1.sp)
                        }
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.testTag(TestTags.Modes.delete(mode.id))
                        ) {
                            Text(stringResource(R.string.modes_delete), fontSize = 11.sp, color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
                        }
                    }
                }
            }
        }
    }
}
