package com.example.nfcguard

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: GuardianViewModel,
    onNavigate: (Screen) -> Unit
) {
    val appState by viewModel.appState.collectAsState()
    val context = LocalContext.current

    var showEmergencyDialog by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var showTagSelectionDialog by remember { mutableStateOf(false) }
    var confirmText by remember { mutableStateOf("") }
    var countdown by remember { mutableStateOf(60) }
    var selectedTagsToDelete by remember { mutableStateOf(setOf<String>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GuardianTheme.BackgroundPrimary)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with Info icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "GUARDIAN",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = GuardianTheme.TextPrimary,
                    letterSpacing = 2.sp
                )
                if (appState.activeModes.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${appState.activeModes.size} MODE${if (appState.activeModes.size > 1) "S" else ""} ACTIVE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = GuardianTheme.TextSecondary,
                        letterSpacing = 1.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Nfc,
                            contentDescription = null,
                            tint = GuardianTheme.IconSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            "TAP NFC TO UNLOCK",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = GuardianTheme.TextSecondary,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Emergency Reset",
                    tint = GuardianTheme.IconPrimary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            showEmergencyDialog = true
                        }
                )

                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Info",
                    tint = GuardianTheme.IconPrimary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onNavigate(Screen.INFO)
                        }
                )
            }


        }

        Spacer(Modifier.height(8.dp))

        // Navigation Cards
        NavigationCard(
            title = "MODES",
            subtitle = "${appState.modes.size} CREATED",
            icon = Icons.Default.Block,
            onClick = { onNavigate(Screen.MODES) }
        )

        NavigationCard(
            title = "SCHEDULES",
            subtitle = "${appState.schedules.size} CONFIGURED",
            icon = Icons.Default.Schedule,
            onClick = { onNavigate(Screen.SCHEDULES) }
        )

        NavigationCard(
            title = "NFC TAGS",
            subtitle = "${appState.nfcTags.size} REGISTERED",
            icon = Icons.Default.Nfc,
            onClick = { onNavigate(Screen.NFC_TAGS) }
        )

        Spacer(Modifier.weight(1f))

        // Active Modes Summary
        if (appState.activeModes.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(0.dp),
                color = GuardianTheme.TextPrimary
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "ACTIVE NOW",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = GuardianTheme.BackgroundSurface,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    appState.modes.filter { appState.activeModes.contains(it.id) }.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                mode.name.uppercase(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.BackgroundSurface,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // Emergency Reset Dialogs
    if (showEmergencyDialog) {
        EmergencyWarningDialog(
            onDismiss = { showEmergencyDialog = false },
            onConfirm = {
                showEmergencyDialog = false
                showConfirmDialog = true
            }
        )
    }

    if (showConfirmDialog) {
        EmergencyConfirmDialog(
            confirmText = confirmText,
            onConfirmTextChange = { confirmText = it },
            countdown = countdown,
            onCountdownTick = { countdown = it },
            onDismiss = {
                showConfirmDialog = false
                confirmText = ""
                countdown = 60
            },
            onConfirm = {
                showConfirmDialog = false
                showTagSelectionDialog = true
                confirmText = ""
                countdown = 60
            }
        )
    }

    if (showTagSelectionDialog) {
        TagSelectionDialog(
            nfcTags = appState.nfcTags,
            selectedTags = selectedTagsToDelete,
            onTagToggle = { tagId ->
                selectedTagsToDelete = if (selectedTagsToDelete.contains(tagId)) {
                    selectedTagsToDelete - tagId
                } else {
                    selectedTagsToDelete + tagId
                }
            },
            onDismiss = {
                showTagSelectionDialog = false
                selectedTagsToDelete = emptySet()
            },
            onConfirm = {
                // Deactivate all modes
                appState.activeModes.forEach { modeId ->
                    viewModel.deactivateMode(modeId)
                }

                // Delete selected NFC tags
                selectedTagsToDelete.forEach { tagId ->
                    viewModel.deleteNfcTag(tagId)
                }

                showTagSelectionDialog = false
                selectedTagsToDelete = emptySet()
            }
        )
    }
}

@Composable
fun NavigationCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = GuardianTheme.BackgroundSurface,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = GuardianTheme.IconPrimary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = GuardianTheme.TextPrimary,
                    letterSpacing = 1.sp
                )
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 1.sp
                )
            }
            Icon(
                Icons.Default.ArrowForward,
                contentDescription = null,
                tint = GuardianTheme.IconSecondary
            )
        }
    }
}

@Composable
fun EmergencyWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GuardianTheme.ButtonSecondary,
        tonalElevation = 0.dp,
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
                    "LOST NFC TAG?",
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = GuardianTheme.Error
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "This will help you:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = GuardianTheme.TextPrimary,
                    letterSpacing = 0.5.sp
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "• Deactivate all active modes",
                        fontSize = 13.sp,
                        color = Color(0xFFCCCCCC),
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        "• Delete lost NFC tags",
                        fontSize = 13.sp,
                        color = Color(0xFFCCCCCC),
                        letterSpacing = 0.5.sp
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = GuardianTheme.ErrorDark
                ) {
                    Text(
                        "Your modes and schedules will NOT be deleted",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = GuardianTheme.Success,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = GuardianTheme.Error
                )
            ) {
                Text("CONTINUE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFF808080)
                )
            ) {
                Text("CANCEL", letterSpacing = 1.sp)
            }
        },
        shape = RoundedCornerShape(0.dp)
    )
}

@Composable
fun EmergencyConfirmDialog(
    confirmText: String,
    onConfirmTextChange: (String) -> Unit,
    countdown: Int,
    onCountdownTick: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            kotlinx.coroutines.delay(1000)
            onCountdownTick(countdown - 1)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GuardianTheme.ButtonSecondary,
        tonalElevation = 0.dp,
        title = {
            Text(
                "FINAL CONFIRMATION",
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = GuardianTheme.Error
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (countdown > 0) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                countdown.toString(),
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Black,
                                color = GuardianTheme.Error
                            )
                            Text(
                                "Waiting...",
                                fontSize = 11.sp,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                } else {
                    Text(
                        "Type exactly:  RESET ALL DATA",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = GuardianTheme.TextPrimary,
                        letterSpacing = 0.5.sp
                    )

                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = onConfirmTextChange,
                        placeholder = {
                            Text(
                                "Type here...",
                                fontSize = 12.sp,
                                letterSpacing = 1.sp
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = GuardianTheme.BackgroundSurface,
                            unfocusedContainerColor = GuardianTheme.BackgroundSurface,
                            focusedIndicatorColor = if (confirmText == "RESET ALL DATA") GuardianTheme.Error else Color.White,
                            unfocusedIndicatorColor = GuardianTheme.BorderSubtle,
                            cursorColor = GuardianTheme.InputCursor,
                            focusedTextColor = GuardianTheme.InputText,
                            unfocusedTextColor = GuardianTheme.InputText
                        ),
                        shape = RoundedCornerShape(0.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (confirmText.isNotEmpty() && confirmText != "RESET ALL DATA") {
                        Text(
                            "Text doesn't match",
                            fontSize = 11.sp,
                            color = GuardianTheme.Error,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = countdown == 0 && confirmText == "RESET ALL DATA",
                colors = ButtonDefaults.textButtonColors(
                    contentColor = GuardianTheme.Error,
                    disabledContentColor = Color(0xFF404040)
                )
            ) {
                Text("NEXT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFF808080)
                )
            ) {
                Text("CANCEL", letterSpacing = 1.sp)
            }
        },
        shape = RoundedCornerShape(0.dp)
    )
}

@Composable
fun TagSelectionDialog(
    nfcTags: List<NfcTag>,
    selectedTags: Set<String>,
    onTagToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GuardianTheme.ButtonSecondary,
        tonalElevation = 0.dp,
        title = {
            Text(
                "SELECT LOST TAGS",
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                color = GuardianTheme.TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Select which NFC tags you lost:",
                    fontSize = 13.sp,
                    color = Color(0xFFCCCCCC),
                    letterSpacing = 0.5.sp
                )

                if (nfcTags.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface
                    ) {
                        Text(
                            "No NFC tags registered",
                            fontSize = 12.sp,
                            color = GuardianTheme.TextSecondary,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        nfcTags.forEach { tag ->
                            Surface(
                                onClick = { onTagToggle(tag.id) },
                                shape = RoundedCornerShape(0.dp),
                                color = if (selectedTags.contains(tag.id)) Color.White else GuardianTheme.BackgroundSurface
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        tag.name.uppercase(),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedTags.contains(tag.id)) Color.Black else Color.White,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (selectedTags.contains(tag.id)) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = GuardianTheme.SuccessBackground
                ) {
                    Text(
                        "This will deactivate ALL modes and delete selected tags only",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = GuardianTheme.Success,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = GuardianTheme.Error
                )
            ) {
                Text("CONFIRM", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFF808080)
                )
            ) {
                Text("CANCEL", letterSpacing = 1.sp)
            }
        },
        shape = RoundedCornerShape(0.dp)
    )
}