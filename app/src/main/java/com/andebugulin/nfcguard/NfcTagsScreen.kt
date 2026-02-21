package com.andebugulin.nfcguard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcTagsScreen(
    viewModel: GuardianViewModel,
    scannedNfcTagId: MutableState<String?>,
    nfcRegistrationMode: MutableState<Boolean>,
    onBack: () -> Unit
) {
    val appState by viewModel.appState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingTagId by remember { mutableStateOf<String?>(null) }
    var editingTag by remember { mutableStateOf<NfcTag?>(null) }
    var showDeleteDialog by remember { mutableStateOf<NfcTag?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf<NfcTag?>(null) }
    var deleteConfirmText by remember { mutableStateOf("") }
    var deleteCountdown by remember { mutableStateOf(60) }

    // Signal to MainActivity that we're registering — skip wrong-tag validation
    LaunchedEffect(showAddDialog) {
        nfcRegistrationMode.value = showAddDialog
    }
    DisposableEffect(Unit) {
        onDispose { nfcRegistrationMode.value = false }
    }

    LaunchedEffect(scannedNfcTagId.value) {
        val scannedId = scannedNfcTagId.value
        if (scannedId != null) {
            android.util.Log.d("NFC_TAGS_SCREEN", "Received scanned tag: $scannedId, dialog open: $showAddDialog")
            if (showAddDialog) {
                pendingTagId = scannedId
                android.util.Log.d("NFC_TAGS_SCREEN", "Set pending tag ID: $scannedId")
            }
            scannedNfcTagId.value = null // Clear after processing
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(GuardianTheme.BackgroundPrimary)) {
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
                    "NFC TAGS",
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    fontSize = 24.sp,
                    color = GuardianTheme.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
            }

            // Info banner
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                shape = RoundedCornerShape(0.dp),
                color = GuardianTheme.BackgroundSurface
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = GuardianTheme.IconSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Register NFC tags to lock specific modes",
                        fontSize = 11.sp,
                        color = GuardianTheme.TextSecondary,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (appState.nfcTags.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Nfc,
                            contentDescription = null,
                            tint = GuardianTheme.IconDisabled,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "NO NFC TAGS",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = GuardianTheme.TextDisabled,
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Register tags to create secure locks",
                            fontSize = 11.sp,
                            color = GuardianTheme.TextDisabled,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = {
                                pendingTagId = null
                                showAddDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GuardianTheme.ButtonPrimary,
                                contentColor = GuardianTheme.ButtonPrimaryText
                            ),
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "REGISTER TAG",
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(appState.nfcTags.size) { index ->
                        NfcTagCard(
                            tag = appState.nfcTags[index],
                            modes = appState.modes,
                            activeModes = appState.activeModes,
                            onEdit = { editingTag = appState.nfcTags[index] },
                            onDelete = { showDeleteDialog = appState.nfcTags[index] }
                        )
                    }

                    item {
                        Button(
                            onClick = {
                                pendingTagId = null
                                showAddDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GuardianTheme.BackgroundSurface,
                                contentColor = GuardianTheme.ButtonSecondaryText
                            ),
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Icon(Icons.Default.Nfc, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "+ REGISTER TAG",
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        NfcTagRegistrationDialog(
            scannedTagId = pendingTagId,
            existingTagIds = appState.nfcTags.map { it.id }.toSet(),  // FIX #3
            existingNames = appState.nfcTags.map { it.name },  // FIX #6
            onDismiss = {
                showAddDialog = false
                pendingTagId = null
            },
            onSave = { tagId, name ->
                // FIX #3: addNfcTag now returns false if duplicate
                val added = viewModel.addNfcTag(tagId, name)
                if (added) {
                    showAddDialog = false
                    pendingTagId = null
                }
                // If not added, the dialog already shows the duplicate warning
            }
        )
    }

    editingTag?.let { tag ->
        NfcTagEditDialog(
            tag = tag,
            existingNames = appState.nfcTags.filter { it.id != tag.id }.map { it.name },  // FIX #6
            onDismiss = { editingTag = null },
            onSave = { name ->
                viewModel.updateNfcTag(tag.id, name)
                editingTag = null
            }
        )
    }

    showDeleteDialog?.let { tag ->
        val linkedModes = appState.modes.filter { it.nfcTagId == tag.id }
        val hasActiveMode = linkedModes.any { appState.activeModes.contains(it.id) }

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
                        "DELETE NFC TAG?",
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
                                tag.name.uppercase(),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${linkedModes.size} linked mode${if (linkedModes.size != 1) "s" else ""}",
                                fontSize = 11.sp,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    if (hasActiveMode) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(0.dp),
                            color = GuardianTheme.WarningBackground
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "ANTI-CHEAT PROTECTION:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = GuardianTheme.Warning,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    "This tag has active modes. Extra confirmation required to prevent cheating.",
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
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "This will:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF8888),
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                "\u2022 Remove the NFC tag",
                                fontSize = 11.sp,
                                color = Color(0xFFFF8888),
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                "\u2022 Unlink from all modes",
                                fontSize = 11.sp,
                                color = Color(0xFFFF8888),
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (hasActiveMode) {
                            showDeleteDialog = null
                            showDeleteConfirmDialog = tag
                            deleteCountdown = 60
                        } else {
                            viewModel.deleteNfcTag(tag.id)
                            showDeleteDialog = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardianTheme.Error,
                        contentColor = GuardianTheme.ButtonSecondaryText
                    ),
                    shape = RoundedCornerShape(0.dp)
                ) {
                    Text(
                        if (hasActiveMode) "CONTINUE" else "DELETE",
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

    // Confirmation dialog for active tags (anti-cheat)
    showDeleteConfirmDialog?.let { tag ->
        val scope = rememberCoroutineScope()

        LaunchedEffect(deleteCountdown) {
            if (deleteCountdown > 0) {
                kotlinx.coroutines.delay(1000)
                deleteCountdown--
            }
        }

        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = null
                deleteConfirmText = ""
                deleteCountdown = 60
            },
            containerColor = GuardianTheme.ButtonSecondary,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.border(
                width = GuardianTheme.DialogBorderWidth,
                color = GuardianTheme.DialogBorderDelete,
                shape = RoundedCornerShape(0.dp)
            ),
            title = {
                Text(
                    "FINAL CONFIRMATION",
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = GuardianTheme.Error
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (deleteCountdown > 0) {
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
                                    deleteCountdown.toString(),
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
                            "Type exactly:  DELETE TAG",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = GuardianTheme.TextPrimary,
                            letterSpacing = 0.5.sp
                        )

                        OutlinedTextField(
                            value = deleteConfirmText,
                            onValueChange = { deleteConfirmText = it },
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
                                focusedIndicatorColor = if (deleteConfirmText == "DELETE TAG") GuardianTheme.Error else Color.White,
                                unfocusedIndicatorColor = GuardianTheme.BorderSubtle,
                                cursorColor = GuardianTheme.InputCursor,
                                focusedTextColor = GuardianTheme.InputText,
                                unfocusedTextColor = GuardianTheme.InputText
                            ),
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        if (deleteConfirmText.isNotEmpty() && deleteConfirmText != "DELETE TAG") {
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
                Button(
                    onClick = {
                        viewModel.deleteNfcTag(tag.id)
                        showDeleteConfirmDialog = null
                        deleteConfirmText = ""
                        deleteCountdown = 60
                    },
                    enabled = deleteCountdown == 0 && deleteConfirmText == "DELETE TAG",
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardianTheme.Error,
                        contentColor = GuardianTheme.ButtonSecondaryText,
                        disabledContainerColor = Color.Black,
                        disabledContentColor = Color(0xFF808080)
                    ),
                    shape = RoundedCornerShape(0.dp)
                ) {
                    Text(
                        "DELETE NOW",
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = null
                        deleteConfirmText = ""
                        deleteCountdown = 60
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color(0xFF808080)
                    )
                ) {
                    Text("CANCEL", letterSpacing = 1.sp)
                }
            },
        )
    }
}

@Composable
fun NfcTagCard(
    tag: NfcTag,
    modes: List<Mode>,
    activeModes: Set<String>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val linkedModes = modes.filter { it.nfcTagId == tag.id }
    val hasActiveMode = linkedModes.any { activeModes.contains(it.id) }
    val backgroundColor = if (hasActiveMode) Color.White else GuardianTheme.BackgroundSurface
    val textColor = if (hasActiveMode) Color.Black else Color.White
    val iconColor = if (hasActiveMode) Color.Black else Color.White
    val subtleColor = if (hasActiveMode) GuardianTheme.TextTertiary else GuardianTheme.TextTertiary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = backgroundColor
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Nfc,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        tag.name.uppercase(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        letterSpacing = 1.sp
                    )
                }

                if (hasActiveMode) {
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color.White)
                            )
                            Text(
                                "ACTIVE",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "ID: ${tag.id.take(16)}...",
                fontSize = 9.sp,
                color = subtleColor,
                letterSpacing = 0.5.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )

            Spacer(Modifier.height(12.dp))

            // Linked modes
            if (linkedModes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "UNLOCKS:",
                        fontSize = 10.sp,
                        color = subtleColor,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )
                    linkedModes.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = subtleColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                mode.name.uppercase(),
                                fontSize = 10.sp,
                                color = subtleColor,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            } else {
                Text(
                    "NOT LINKED TO ANY MODES",
                    fontSize = 10.sp,
                    color = GuardianTheme.TextDisabled,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onEdit) {
                    Text("RENAME", fontSize = 11.sp, color = textColor, letterSpacing = 1.sp)
                }
                TextButton(onClick = onDelete) {
                    Text("DELETE", fontSize = 11.sp, color = if (hasActiveMode) Color(0xFF808080) else Color(0xFF808080), letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
fun NfcTagRegistrationDialog(
    scannedTagId: String?,
    existingTagIds: Set<String> = emptySet(),  // FIX #3
    existingNames: List<String> = emptyList(),  // FIX #6
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var tagId by remember { mutableStateOf(scannedTagId ?: "") }

    // Update tagId when scannedTagId changes
    LaunchedEffect(scannedTagId) {
        if (scannedTagId != null) {
            android.util.Log.d("NFC_REGISTRATION_DIALOG", "Received tag: $scannedTagId")
            tagId = scannedTagId
        }
    }

    // FIX #3: Check if this tag is already registered
    val isDuplicate = tagId.isNotBlank() && existingTagIds.contains(tagId)
    // FIX #6: Check if name already exists
    val nameExists = name.isNotBlank() && existingNames.any { it.equals(name.trim(), ignoreCase = true) }

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
                "REGISTER NFC TAG",
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (tagId.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Nfc,
                                contentDescription = null,
                                tint = GuardianTheme.IconSecondary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "TAP NFC TAG",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Hold your NFC tag near the device",
                                fontSize = 11.sp,
                                color = GuardianTheme.TextTertiary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                } else if (isDuplicate) {
                    // FIX #3: Show duplicate tag warning
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.ErrorDark
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = GuardianTheme.Error
                            )
                            Column {
                                Text(
                                    "TAG ALREADY REGISTERED",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GuardianTheme.Error,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    "This NFC tag is already in your list. Try a different tag.",
                                    fontSize = 9.sp,
                                    color = Color(0xFFFF8888),
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    tagId.take(16) + "...",
                                    fontSize = 9.sp,
                                    color = Color(0xFFFF8888),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = Color(0xFF1A4D1A)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = GuardianTheme.Success
                            )
                            Column {
                                Text(
                                    "TAG DETECTED",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GuardianTheme.Success,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    tagId.take(16) + "...",
                                    fontSize = 9.sp,
                                    color = Color(0xFF81C784),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { if (it.length <= 30) name = it },  // FIX #7: Max length
                        placeholder = { Text("TAG NAME (e.g., 'OFFICE KEY')", fontSize = 12.sp, letterSpacing = 1.sp) },
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
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            // FIX #6: Duplicate name feedback
                            if (nameExists) {
                                Text(
                                    "A tag with this name already exists",
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && tagId.isNotBlank() && !isDuplicate && !nameExists) {
                        onSave(tagId, name.trim())
                    }
                },
                // FIX #3 + #6: Disable when duplicate tag or duplicate name
                enabled = name.isNotBlank() && tagId.isNotBlank() && !isDuplicate && !nameExists
            ) {
                Text("REGISTER", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
fun NfcTagEditDialog(
    tag: NfcTag,
    existingNames: List<String> = emptyList(),  // FIX #6
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(tag.name) }

    // FIX #6: Check for duplicate names (excluding current name)
    val nameExists = name.isNotBlank() &&
            !name.trim().equals(tag.name, ignoreCase = true) &&
            existingNames.any { it.equals(name.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GuardianTheme.BackgroundSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.border(
            width = GuardianTheme.DialogBorderWidth,
            color = GuardianTheme.DialogBorderEdit,
            shape = RoundedCornerShape(0.dp)
        ),
        title = {
            Text(
                "RENAME NFC TAG",
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 30) name = it },  // FIX #7: Max length
                placeholder = { Text("TAG NAME", fontSize = 12.sp, letterSpacing = 1.sp) },
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
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    if (nameExists) {
                        Text(
                            "A tag with this name already exists",
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
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && !nameExists) {
                        onSave(name.trim())
                    }
                },
                enabled = name.isNotBlank() && !nameExists  // FIX #6
            ) {
                Text("SAVE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
            }
        },
    )
}