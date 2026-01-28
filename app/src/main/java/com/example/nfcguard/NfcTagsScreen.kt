package com.example.nfcguard

import androidx.compose.foundation.background
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
    onBack: () -> Unit
) {
    val appState by viewModel.appState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingTagId by remember { mutableStateOf<String?>(null) }
    var editingTag by remember { mutableStateOf<NfcTag?>(null) }
    var showDeleteDialog by remember { mutableStateOf<NfcTag?>(null) }

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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                }
                Text(
                    "NFC TAGS",
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    fontSize = 24.sp,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
            }

            // Info banner
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                shape = RoundedCornerShape(0.dp),
                color = Color(0xFF0A0A0A)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFF808080),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Register NFC tags to lock specific modes",
                        fontSize = 11.sp,
                        color = Color(0xFF808080),
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
                            tint = Color(0xFF404040),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "NO NFC TAGS",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF404040),
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Register tags to create secure locks",
                            fontSize = 11.sp,
                            color = Color(0xFF404040),
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = {
                                pendingTagId = null
                                showAddDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black
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
                                containerColor = Color(0xFF0A0A0A),
                                contentColor = Color.White
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
            onDismiss = {
                showAddDialog = false
                pendingTagId = null
            },
            onSave = { tagId, name ->
                viewModel.addNfcTag(tagId, name)
                showAddDialog = false
                pendingTagId = null
            }
        )
    }

    editingTag?.let { tag ->
        NfcTagEditDialog(
            tag = tag,
            onDismiss = { editingTag = null },
            onSave = { name ->
                viewModel.updateNfcTag(tag.id, name)
                editingTag = null
            }
        )
    }

    showDeleteDialog?.let { tag ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor = Color(0xFF0A0A0A),
            title = {
                Text(
                    "DELETE NFC TAG?",
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            },
            text = {
                Column {
                    Text(
                        "This will remove the tag and unlink it from all modes.",
                        color = Color(0xFF808080)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteNfcTag(tag.id)
                    showDeleteDialog = null
                }) {
                    Text("DELETE", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("CANCEL", color = Color(0xFF808080))
                }
            },
            shape = RoundedCornerShape(0.dp)
        )
    }
}

@Composable
fun NfcTagCard(
    tag: NfcTag,
    modes: List<Mode>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = Color(0xFF0A0A0A)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Nfc,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    tag.name.uppercase(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "ID: ${tag.id.take(16)}...",
                fontSize = 9.sp,
                color = Color(0xFF606060),
                letterSpacing = 0.5.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )

            Spacer(Modifier.height(12.dp))

            // Linked modes
            val linkedModes = modes.filter { it.nfcTagId == tag.id }
            if (linkedModes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "UNLOCKS:",
                        fontSize = 10.sp,
                        color = Color(0xFF808080),
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
                                tint = Color(0xFF606060),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                mode.name.uppercase(),
                                fontSize = 10.sp,
                                color = Color(0xFF606060),
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            } else {
                Text(
                    "NOT LINKED TO ANY MODES",
                    fontSize = 10.sp,
                    color = Color(0xFF404040),
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onEdit) {
                    Text("RENAME", fontSize = 11.sp, color = Color.White, letterSpacing = 1.sp)
                }
                TextButton(onClick = onDelete) {
                    Text("DELETE", fontSize = 11.sp, color = Color(0xFF808080), letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
fun NfcTagRegistrationDialog(
    scannedTagId: String?,
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A0A0A),
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
                        color = Color.Black
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Nfc,
                                contentDescription = null,
                                tint = Color(0xFF808080),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "TAP NFC TAG",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF808080),
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Hold your NFC tag near the device",
                                fontSize = 11.sp,
                                color = Color(0xFF606060),
                                letterSpacing = 0.5.sp
                            )
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
                                tint = Color(0xFF4CAF50)
                            )
                            Column {
                                Text(
                                    "TAG DETECTED",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50),
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
                        onValueChange = { name = it },
                        placeholder = { Text("TAG NAME (e.g., 'OFFICE KEY')", fontSize = 12.sp, letterSpacing = 1.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Black,
                            unfocusedContainerColor = Color.Black,
                            focusedIndicatorColor = Color.White,
                            unfocusedIndicatorColor = Color(0xFF404040),
                            cursorColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(0.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && tagId.isNotBlank()) {
                        onSave(tagId, name)
                    }
                },
                enabled = name.isNotBlank() && tagId.isNotBlank()
            ) {
                Text("REGISTER", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Color(0xFF808080), letterSpacing = 1.sp)
            }
        },
        shape = RoundedCornerShape(0.dp)
    )
}

@Composable
fun NfcTagEditDialog(
    tag: NfcTag,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(tag.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A0A0A),
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
                onValueChange = { name = it },
                placeholder = { Text("TAG NAME", fontSize = 12.sp, letterSpacing = 1.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Black,
                    unfocusedContainerColor = Color.Black,
                    focusedIndicatorColor = Color.White,
                    unfocusedIndicatorColor = Color(0xFF404040),
                    cursorColor = Color.White,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(name)
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("SAVE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Color(0xFF808080), letterSpacing = 1.sp)
            }
        },
        shape = RoundedCornerShape(0.dp)
    )
}