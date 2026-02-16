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
                                nfcTag = appState.nfcTags.find { it.id == mode.nfcTagId },
                                onActivate = {
                                    // FIX #2: Handle activation result
                                    val result = viewModel.activateMode(mode.id)
                                    if (result == ActivationResult.BLOCK_MODE_CONFLICT) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                "Cannot mix BLOCK and ALLOW ONLY modes. Deactivate current modes first."
                                            )
                                        }
                                    }
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