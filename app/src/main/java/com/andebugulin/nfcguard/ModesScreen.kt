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
    LaunchedEffect(appState.timedModeDeactivations) {
        while (appState.timedModeDeactivations.isNotEmpty()) {
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
                                isManual = appState.manuallyActivatedModes.contains(mode.id),
                                timedUntil = appState.timedModeDeactivations[mode.id],
                                now = now,
                                nfcTag = appState.nfcTags.find { it.id == mode.nfcTagId },
                                onActivate = {
                                    showActivationOptionsDialog = mode
                                },
                                onEdit = { selectedMode = mode },
                                onDelete = { showDeleteDialog = mode }
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
                onSave = { apps, blockMode, nfcTagId ->
                    if (appState.modes.any { it.id == mode.id }) {
                        viewModel.updateMode(mode.id, mode.name, apps, blockMode, nfcTagId)
                    } else {
                        viewModel.addMode(mode.name, apps, blockMode, nfcTagId)
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
    isManual: Boolean = false,
    timedUntil: Long? = null,
    now: Long = System.currentTimeMillis(),
    nfcTag: NfcTag?,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = if (isActive) Color.White else GuardianTheme.BackgroundSurface
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        mode.name.uppercase(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) Color.Black else Color.White,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${mode.blockedApps.size} APPS \u00B7 ${if (mode.blockMode == BlockMode.BLOCK_SELECTED) "BLOCK" else "ALLOW ONLY"}",
                        fontSize = 10.sp,
                        color = if (isActive) GuardianTheme.TextTertiary else GuardianTheme.TextTertiary,
                        letterSpacing = 1.sp
                    )
                    if (nfcTag != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Nfc,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = if (isActive) GuardianTheme.TextTertiary else GuardianTheme.TextTertiary
                            )
                            Text(
                                "LINKED TO: ${nfcTag.name.uppercase()}",
                                fontSize = 10.sp,
                                color = if (isActive) GuardianTheme.TextTertiary else GuardianTheme.TextTertiary,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                    // Show activation source when active
                    if (isActive) {
                        Spacer(Modifier.height(4.dp))
                        val isTimed = timedUntil != null
                        val sourceLabel = when {
                            isManual && isTimed -> {
                                val remaining = ((timedUntil ?: 0) - now) / 60000
                                "MANUAL · ${remaining.coerceAtLeast(0)}M LEFT"
                            }
                            isManual -> "MANUAL · NFC TO UNLOCK"
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
                    }
                }

                if (!isActive) {
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
                } else {
                    Text(
                        "ACTIVE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = GuardianTheme.BackgroundSurface,
                        letterSpacing = 1.sp
                    )
                }
            }

            if (!isActive) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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