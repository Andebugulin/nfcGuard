package com.andebugulin.nfcguard.ui.modes

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
                ScreenHeader(title = "MODES", onBack = onBack)

                // Modes list
                if (appState.modes.isEmpty()) {
                    EmptyState(label = "NO MODES") {
                        GuardianButton(
                            label = "CREATE MODE",
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
                                    "+ NEW MODE",
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
                        "DELETE MODE?",
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
                                "${mode.blockedApps.size} app${if (mode.blockedApps.size != 1) "s" else ""}",
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
                                    "LINKED SCHEDULES AFFECTED:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = GuardianTheme.Warning,
                                    letterSpacing = 1.sp
                                )
                                linkedSchedules.forEach { sched ->
                                    val remainingModes = sched.linkedModeIds.count { it != mode.id }
                                    Text(
                                        "\u2022 ${sched.name.uppercase()} ($remainingModes mode${if (remainingModes != 1) "s" else ""} remaining)",
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
                            "This action cannot be undone",
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
                        "DELETE",
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
                    Text("CANCEL", letterSpacing = 1.sp)
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
                            BLOCK_MODE_CONFLICT_MESSAGE
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
                        "${mode.blockedApps.size} APPS \u00B7 ${if (mode.blockMode == BlockMode.BLOCK_SELECTED) "BLOCK" else "ALLOW ONLY"}",
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
                                "LINKED TO: ${nfcTags.joinToString(", ") { it.name.uppercase() }}",
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
                                    val endStr = String.format("%02d:%02d", endCal.get(java.util.Calendar.HOUR_OF_DAY), endCal.get(java.util.Calendar.MINUTE))
                                    val prefix = if (isManual) "MANUAL" else "ACTIVE"
                                    "$prefix \u00B7 ${remaining.coerceAtLeast(0)}M LEFT \u00B7 UNTIL $endStr"
                                }
                                isManual -> "MANUAL \u00B7 NFC TO UNLOCK"
                                else -> {
                                    if (scheduleEndTime != null) "BY SCHEDULE \u00B7 UNTIL $scheduleEndTime"
                                    else "ACTIVATED BY SCHEDULE"
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
                                    if (remaining != null) "PAUSED \u00B7 RE-ENABLES IN ${remaining.coerceAtLeast(0)}M" else "PAUSED \u00B7 PERMANENTLY",
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
                        Text("ACTIVATE", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                } else if (isActive) {
                    Text(
                        "ACTIVE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = GuardianTheme.BackgroundSurface,
                        letterSpacing = 1.sp
                    )
                } else if (isPaused) {
                    Text(
                        "PAUSED",
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
                                "RE-ENABLE",
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
                            Text("EDIT", fontSize = 11.sp, color = GuardianTheme.TextPrimary, letterSpacing = 1.sp)
                        }
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier.testTag(TestTags.Modes.delete(mode.id))
                        ) {
                            Text("DELETE", fontSize = 11.sp, color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
                        }
                    }
                }
            }
        }
    }
}
