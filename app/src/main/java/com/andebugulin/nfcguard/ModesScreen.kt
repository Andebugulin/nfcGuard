package com.andebugulin.nfcguard

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModesScreen(
    viewModel: GuardianViewModel,
    onBack: () -> Unit
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
                    contentColor = Color(0xFFFF8888),
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
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = GuardianTheme.IconPrimary)
                    }
                    Text(
                        "MODES",
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontSize = 24.sp,
                        color = GuardianTheme.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Modes list
                if (appState.modes.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "NO MODES",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextDisabled,
                                letterSpacing = 2.sp
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { showAddDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GuardianTheme.ButtonPrimary,
                                    contentColor = GuardianTheme.ButtonPrimaryText
                                ),
                                shape = RoundedCornerShape(0.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Text(
                                    "CREATE MODE",
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(appState.modes, key = { it.id }) { mode ->
                            ModeCard(
                                mode = mode,
                                isActive = appState.activeModes.contains(mode.id),
                                isPaused = appState.timedModeReactivations.containsKey(mode.id),
                                isManual = appState.manuallyActivatedModes.contains(mode.id),
                                timedUntil = appState.timedModeDeactivations[mode.id],
                                pausedUntil = appState.timedModeReactivations[mode.id],
                                now = now,
                                nfcTags = (appState.nfcTags + NfcTag("ANY", "ANY")).filter { mode.effectiveNfcTagIds.contains(it.id) },
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
                            color = Color(0xFFFF8888),
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
                    shape = RoundedCornerShape(0.dp)
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
                        contentColor = Color(0xFF808080)
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
                val cal = java.util.Calendar.getInstance()
                val currentDay = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                    java.util.Calendar.MONDAY -> 1; java.util.Calendar.TUESDAY -> 2
                    java.util.Calendar.WEDNESDAY -> 3; java.util.Calendar.THURSDAY -> 4
                    java.util.Calendar.FRIDAY -> 5; java.util.Calendar.SATURDAY -> 6
                    java.util.Calendar.SUNDAY -> 7; else -> 1
                }
                val currentTime = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                appState.schedules.any { schedule ->
                    schedule.linkedModeIds.contains(mode.id) && schedule.hasEndTime &&
                            schedule.timeSlot.getTimeForDay(currentDay)?.let { dt ->
                                val end = dt.endHour * 60 + dt.endMinute
                                // Schedule will deactivate this mode if:
                                // - currently active (start <= now < end), OR
                                // - upcoming today (now < start, so end alarm is still ahead)
                                currentTime < end
                            } == true
                }
            },
            onDismiss = { showActivationOptionsDialog = null },
            onActivate = { timedUntilMillis ->
                showActivationOptionsDialog = null
                val result = viewModel.activateMode(mode.id, timedUntilMillis)
                if (result == ActivationResult.BLOCK_MODE_CONFLICT) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Cannot mix BLOCK and ALLOW ONLY modes. Deactivate current modes first."
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
                }
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
            isPaused -> Color(0xFFFFF9C4) // Light yellow for paused state
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
                        color = if (isActive || isPaused) GuardianTheme.TextTertiary else GuardianTheme.TextTertiary,
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
                                tint = if (isActive || isPaused) GuardianTheme.TextTertiary else GuardianTheme.TextTertiary
                            )
                            Text(
                                "LINKED TO: ${nfcTags.joinToString(", ") { it.name.uppercase() }}",
                                fontSize = 10.sp,
                                color = if (isActive || isPaused) GuardianTheme.TextTertiary else GuardianTheme.TextTertiary,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                    
                    if (isActive || isPaused) {
                        Spacer(Modifier.height(4.dp))
                        
                        if (isActive) {
                            val isTimed = timedUntil != null
                            val sourceLabel = when {
                                isManual && isTimed -> {
                                    val remaining = ((timedUntil ?: 0) - now) / 60000
                                    "MANUAL \u00B7 ${remaining.coerceAtLeast(0)}M LEFT"
                                }
                                isManual -> "MANUAL \u00B7 NFC TO UNLOCK"
                                else -> "ACTIVATED BY SCHEDULE"
                            }
                            val sourceIcon = when {
                                isManual && isTimed -> Icons.Default.Timer
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
                                    tint = Color(0xFF555555)
                                )
                                Text(
                                    sourceLabel,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF555555),
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
                                    Icons.Default.PauseCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = Color(0xFFFBC02D) // Yellow-700
                                )
                                Text(
                                    if (remaining != null) "PAUSED \u00B7 ${remaining.coerceAtLeast(0)}M LEFT" else "PAUSED \u00B7 PERMANENTLY",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFBC02D),
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }

                if (!isActive && !isPaused) {
                    Button(
                        onClick = onActivate,
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
                        color = Color(0xFFFBC02D),
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
                                tint = Color(0xFFD4A017)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "UN-PAUSE", 
                                fontSize = 11.sp, 
                                color = Color(0xFFD4A017), 
                                fontWeight = FontWeight.Bold, 
                                letterSpacing = 1.sp
                            )
                        }
                    } else {
                        TextButton(onClick = onEdit) {
                            Text("EDIT", fontSize = 11.sp, color = GuardianTheme.TextPrimary, letterSpacing = 1.sp)
                        }
                        TextButton(onClick = onDelete) {
                            Text("DELETE", fontSize = 11.sp, color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModeNameDialog(
    existingNames: List<String> = emptyList(),  // FIX #6
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    // FIX #6: Check for duplicate names
    val nameExists = existingNames.any { it.equals(name.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GuardianTheme.BackgroundSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.border(
            width = GuardianTheme.DialogBorderWidth,
            color = GuardianTheme.DialogBorderInfo,
            shape = RoundedCornerShape(0.dp)
        ),
        title = {
            Text(
                "NEW MODE",
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 30) name = it },  // FIX #7: Max length
                    placeholder = { Text("MODE NAME", fontSize = 12.sp, letterSpacing = 1.sp) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = GuardianTheme.InputBackground,
                        unfocusedContainerColor = GuardianTheme.InputBackground,
                        focusedIndicatorColor = GuardianTheme.BorderFocused,
                        unfocusedIndicatorColor = GuardianTheme.BorderSubtle,
                        cursorColor = GuardianTheme.InputCursor,
                        focusedTextColor = GuardianTheme.InputText,
                        unfocusedTextColor = GuardianTheme.InputText
                    ),
                    shape = RoundedCornerShape(0.dp),
                    supportingText = {
                        // FIX #6: Duplicate name feedback
                        if (nameExists && name.isNotBlank()) {
                            Text(
                                "A mode with this name already exists",
                                fontSize = 10.sp,
                                color = GuardianTheme.Error,
                                letterSpacing = 0.5.sp
                            )
                        } else {
                            Text(
                                "${name.length}/30",
                                fontSize = 10.sp,
                                color = GuardianTheme.TextTertiary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && !nameExists) onSave(name.trim()) },
                enabled = name.isNotBlank() && !nameExists  // FIX #6
            ) {
                Text("CREATE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
            }
        },
    )
}

@Composable
fun ActivationOptionsDialog(
    mode: Mode,
    hasLinkedSchedules: Boolean,
    onDismiss: () -> Unit,
    onActivate: (timedUntilMillis: Long?) -> Unit
) {
    var selectedOption by remember { mutableStateOf(0) } // 0 = until schedule/tag, 1 = timed
    var timedHours by remember { mutableStateOf("0") }
    var timedMinutes by remember { mutableStateOf("30") }

    // Compute normalized total minutes from hours + minutes input
    val totalMinutes = run {
        val h = timedHours.toLongOrNull() ?: 0L
        val m = timedMinutes.toLongOrNull() ?: 0L
        h * 60 + m
    }
    // Display normalized for user clarity (e.g. 0h 130m → "2H 10M")
    val normalizedH = totalMinutes / 60
    val normalizedM = totalMinutes % 60

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GuardianTheme.BackgroundSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.border(
            width = GuardianTheme.DialogBorderWidth,
            color = GuardianTheme.DialogBorderInfo,
            shape = RoundedCornerShape(0.dp)
        ),
        title = {
            Text(
                "ACTIVATE ${mode.name.uppercase()}",
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontSize = 14.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "HOW SHOULD THIS MODE END?",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 1.sp
                )

                // Option 1: Until schedule/tag
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = if (selectedOption == 0) Color.White else Color(0xFF1A1A1A),
                    onClick = { selectedOption = 0 }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (hasLinkedSchedules) "UNTIL SCHEDULE ENDS / NFC TAG" else "UNTIL NFC TAG",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedOption == 0) Color.Black else Color.White,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (hasLinkedSchedules)
                                "Mode will deactivate when a linked schedule ends, or when you tap an NFC tag"
                            else
                                "Mode stays active until you tap an NFC tag to unlock",
                            fontSize = 10.sp,
                            color = if (selectedOption == 0) Color(0xFF555555) else GuardianTheme.TextTertiary,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Option 2: Timed
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = if (selectedOption == 1) Color.White else Color(0xFF1A1A1A),
                    onClick = { selectedOption = 1 }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "FOR A SET DURATION",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedOption == 1) Color.Black else Color.White,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (hasLinkedSchedules)
                                "Mode will stay active for this duration even if a schedule ends sooner"
                            else
                                "Mode will automatically deactivate after the time expires",
                            fontSize = 10.sp,
                            color = if (selectedOption == 1) Color(0xFF555555) else GuardianTheme.TextTertiary,
                            letterSpacing = 0.5.sp
                        )

                        if (selectedOption == 1) {
                            Spacer(Modifier.height(12.dp))

                            // Quick presets
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    Triple("0", "15", "15M"),
                                    Triple("0", "30", "30M"),
                                    Triple("1", "0", "1H"),
                                    Triple("2", "0", "2H")
                                ).forEach { (h, m, label) ->
                                    val isSelected = timedHours == h && timedMinutes == m
                                    Surface(
                                        shape = RoundedCornerShape(0.dp),
                                        color = if (isSelected) Color.Black else Color(0xFFEEEEEE),
                                        onClick = { timedHours = h; timedMinutes = m }
                                    ) {
                                        Text(
                                            label,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color.White else Color.Black,
                                            letterSpacing = 1.sp,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Hours + Minutes inputs
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = timedHours,
                                    onValueChange = { newValue ->
                                        if (newValue.all { it.isDigit() } && newValue.length <= 3) {
                                            timedHours = newValue
                                        }
                                    },
                                    placeholder = { Text("0", fontSize = 11.sp) },
                                    label = { Text("HOURS", fontSize = 8.sp, letterSpacing = 1.sp) },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.White,
                                        unfocusedContainerColor = Color(0xFFF0F0F0),
                                        focusedIndicatorColor = Color.Black,
                                        unfocusedIndicatorColor = Color(0xFFCCCCCC),
                                        cursorColor = Color.Black,
                                        focusedTextColor = Color.Black,
                                        unfocusedTextColor = Color.Black,
                                        focusedLabelColor = Color(0xFF555555),
                                        unfocusedLabelColor = Color(0xFF888888)
                                    ),
                                    shape = RoundedCornerShape(0.dp),
                                    modifier = Modifier.width(72.dp),
                                    singleLine = true
                                )
                                Text(
                                    ":",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF555555)
                                )
                                OutlinedTextField(
                                    value = timedMinutes,
                                    onValueChange = { newValue ->
                                        if (newValue.all { it.isDigit() } && newValue.length <= 3) {
                                            timedMinutes = newValue
                                        }
                                    },
                                    placeholder = { Text("30", fontSize = 11.sp) },
                                    label = { Text("MINUTES", fontSize = 8.sp, letterSpacing = 1.sp) },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.White,
                                        unfocusedContainerColor = Color(0xFFF0F0F0),
                                        focusedIndicatorColor = Color.Black,
                                        unfocusedIndicatorColor = Color(0xFFCCCCCC),
                                        cursorColor = Color.Black,
                                        focusedTextColor = Color.Black,
                                        unfocusedTextColor = Color.Black,
                                        focusedLabelColor = Color(0xFF555555),
                                        unfocusedLabelColor = Color(0xFF888888)
                                    ),
                                    shape = RoundedCornerShape(0.dp),
                                    modifier = Modifier.width(80.dp),
                                    singleLine = true
                                )
                            }

                            // Show normalized result if minutes overflow
                            if (totalMinutes > 0 && ((timedMinutes.toLongOrNull() ?: 0) >= 60)) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "= ${normalizedH}H ${normalizedM}M",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF555555),
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedOption == 1) {
                        val deactivateAt = System.currentTimeMillis() + (totalMinutes * 60 * 1000)
                        onActivate(deactivateAt)
                    } else {
                        onActivate(null)
                    }
                },
                enabled = selectedOption == 0 || totalMinutes > 0
            ) {
                Text("ACTIVATE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
            }
        },
    )
}

@Composable
fun UnlockDurationDialog(
    modeNames: List<String>,
    maxLimitMinutes: Long? = null,
    onDismiss: () -> Unit,
    onConfirm: (reactivateAtMillis: Long?) -> Unit
) {
    // If there's a limit, permanent is not an option. Default to timed.
    val initialOption = if (maxLimitMinutes != null) 1 else 0
    var selectedOption by remember { mutableStateOf(initialOption) }
    
    // Default duration: 5m, capped by limit
    val defaultMins = if (maxLimitMinutes != null) minOf(5L, maxLimitMinutes) else 5L
    var timedHours by remember { mutableStateOf((defaultMins / 60).toString()) }
    var timedMinutes by remember { mutableStateOf((defaultMins % 60).toString()) }

    val totalMinutes = run {
        val h = timedHours.toLongOrNull() ?: 0L
        val m = timedMinutes.toLongOrNull() ?: 0L
        h * 60 + m
    }
    
    // The actual duration used, capped by maxLimitMinutes
    val cappedMinutes = if (maxLimitMinutes != null) totalMinutes.coerceAtMost(maxLimitMinutes) else totalMinutes
    
    val normalizedH = cappedMinutes / 60
    val normalizedM = cappedMinutes % 60

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GuardianTheme.BackgroundSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.border(
            width = GuardianTheme.DialogBorderWidth,
            color = GuardianTheme.DialogBorderInfo,
            shape = RoundedCornerShape(0.dp)
        ),
        title = {
            Text(
                "UNLOCK MODE${if (modeNames.size > 1) "S" else ""}",
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontSize = 14.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    modeNames.joinToString(", ") { it.uppercase() },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 1.sp
                )

                Text(
                    "HOW LONG SHOULD IT STAY UNLOCKED?",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 1.sp
                )

                // Option 1: Permanent unlock (only if no limit)
                if (maxLimitMinutes == null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = if (selectedOption == 0) Color.White else Color(0xFF1A1A1A),
                        onClick = { selectedOption = 0 }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "PERMANENTLY",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedOption == 0) Color.Black else Color.White,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Mode stays off until re-enabled by schedule or manually",
                                fontSize = 10.sp,
                                color = if (selectedOption == 0) Color(0xFF555555) else GuardianTheme.TextTertiary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                } else {
                    // Inform user why permanent unlock is disabled
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.WarningBackground.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.LockClock,
                                contentDescription = null,
                                tint = GuardianTheme.Warning,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "PERMANENT UNLOCK DISABLED\nTHIS TAG HAS A ${maxLimitMinutes}M LIMIT SET ON THIS MODE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = GuardianTheme.Warning,
                                letterSpacing = 1.sp,
                                lineHeight = 12.sp
                            )
                        }
                    }
                }

                // Option 2: Timed unlock
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = if (selectedOption == 1) Color.White else Color(0xFF1A1A1A),
                    onClick = { selectedOption = 1 }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "TEMPORARY BREAK",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedOption == 1) Color.Black else Color.White,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (maxLimitMinutes != null) "Unlocks for a limited time (max ${maxLimitMinutes / 60}H ${maxLimitMinutes % 60}M)"
                            else "Mode will automatically re-enable after the time expires",
                            fontSize = 10.sp,
                            color = if (selectedOption == 1) Color(0xFF555555) else GuardianTheme.TextTertiary,
                            letterSpacing = 0.5.sp
                        )

                        if (selectedOption == 1) {
                            Spacer(Modifier.height(12.dp))

                            // Quick presets
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    Triple("0", "5", "5M"),
                                    Triple("0", "15", "15M"),
                                    Triple("0", "30", "30M"),
                                    Triple("1", "0", "1H")
                                ).forEach { (h, m, label) ->
                                    val presetMins = h.toLong() * 60 + m.toLong()
                                    // Only show presets that are within limit
                                    if (maxLimitMinutes == null || presetMins <= maxLimitMinutes) {
                                        val isSelected = timedHours == h && timedMinutes == m
                                        Surface(
                                            shape = RoundedCornerShape(0.dp),
                                            color = if (isSelected) Color.Black else Color(0xFFEEEEEE),
                                            onClick = { timedHours = h; timedMinutes = m }
                                        ) {
                                            Text(
                                                label,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color.White else Color.Black,
                                                letterSpacing = 1.sp,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // Hours + Minutes inputs
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = timedHours,
                                    onValueChange = { timedHours = it.filter { c -> c.isDigit() }.take(2) },
                                    label = { Text("HOURS", fontSize = 9.sp, letterSpacing = 1.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Black,
                                        unfocusedBorderColor = Color(0xFFCCCCCC),
                                        focusedTextColor = Color.Black,
                                        unfocusedTextColor = Color.Black,
                                        focusedLabelColor = Color.Black,
                                        cursorColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(0.dp)
                                )
                                OutlinedTextField(
                                    value = timedMinutes,
                                    onValueChange = { timedMinutes = it.filter { c -> c.isDigit() }.take(3) },
                                    label = { Text("MINUTES", fontSize = 9.sp, letterSpacing = 1.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Black,
                                        unfocusedBorderColor = Color(0xFFCCCCCC),
                                        focusedTextColor = Color.Black,
                                        unfocusedTextColor = Color.Black,
                                        focusedLabelColor = Color.Black,
                                        cursorColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(0.dp)
                                )
                            }

                            if (cappedMinutes > 0) {
                                val limitWarning = if (maxLimitMinutes != null && totalMinutes > maxLimitMinutes) " (CAPPED BY TAG LIMIT)" else ""
                                Text(
                                    "WILL RE-ENABLE IN ${normalizedH}H ${normalizedM}M$limitWarning",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (limitWarning.isNotEmpty()) GuardianTheme.Warning else Color(0xFF555555),
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedOption == 1 && cappedMinutes > 0) {
                        val reactivateAt = System.currentTimeMillis() + (cappedMinutes * 60 * 1000)
                        onConfirm(reactivateAt)
                    } else if (selectedOption == 0) {
                        onConfirm(null)
                    }
                },
                enabled = (selectedOption == 0 && maxLimitMinutes == null) || (selectedOption == 1 && cappedMinutes > 0)
            ) {
                Text("UNLOCK", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
            }
        },
    )
}
