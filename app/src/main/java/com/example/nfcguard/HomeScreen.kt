package com.example.nfcguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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



import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner


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
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Auto-refresh permissions when activity resumes (user returns from system settings)
    var permissionCheckTrigger by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionCheckTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionsGranted = remember(permissionCheckTrigger) {
        val usageStatsOk = try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
        val overlayOk = Settings.canDrawOverlays(context)
        val batteryOk = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) { false }
        usageStatsOk && overlayOk && batteryOk
    }

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
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings & Permissions",
                    tint = if (permissionsGranted) GuardianTheme.IconPrimary else Color(0xFF8B0000),
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            permissionCheckTrigger++
                            showSettingsDialog = true
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
                if (appState.activeModes.isNotEmpty()) {
                    // Modes active — require countdown + confirmation
                    showConfirmDialog = true
                } else {
                    // No modes active — skip safety gate, go straight to tag selection
                    showTagSelectionDialog = true
                }
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

    if (showSettingsDialog) {
        SettingsDialog(
            viewModel = viewModel,
            appState = appState,
            onDismiss = {
                showSettingsDialog = false
                permissionCheckTrigger++ // recheck permissions when closing
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
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.border(
            width = GuardianTheme.DialogBorderWidth,
            color = GuardianTheme.DialogBorderWarning,
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
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.border(
            width = GuardianTheme.DialogBorderWidth,
            color = GuardianTheme.DialogBorderDelete,
            shape = RoundedCornerShape(0.dp)
        ),
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
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.border(
            width = GuardianTheme.DialogBorderWidth,
            color = GuardianTheme.DialogBorderWarning,
            shape = RoundedCornerShape(0.dp)
        ),
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
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    viewModel: GuardianViewModel,
    appState: AppState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var importMessage by remember { mutableStateOf<String?>(null) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var pendingImportData by remember { mutableStateOf<ConfigManager.ExportData?>(null) }
    var showExportFormatChooser by remember { mutableStateOf(false) }
    var showImportSafetyGate by remember { mutableStateOf(false) }
    var importCountdown by remember { mutableStateOf(60) }
    var importConfirmText by remember { mutableStateOf("") }

    // Auto-refresh permissions when activity resumes + periodic poll
    // (Battery optimization dialog stays in-app, so ON_RESUME doesn't fire for it)
    var permRefreshKey by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permRefreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            permRefreshKey++
        }
    }

    // Permission state - rechecked on every activity resume
    val usageStatsGranted = remember(permRefreshKey) {
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }
    val overlayGranted = remember(permRefreshKey) { Settings.canDrawOverlays(context) }
    val batteryGranted = remember(permRefreshKey) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) { false }
    }

    // Export launchers
    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                val jsonContent = ConfigManager.exportToJson(appState)
                context.contentResolver.openOutputStream(it)?.use { stream ->
                    stream.write(jsonContent.toByteArray())
                }
                importMessage = "Exported JSON successfully"
            } catch (e: Exception) {
                importMessage = "Export failed: ${e.message}"
            }
        }
    }

    val exportYamlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-yaml")
    ) { uri ->
        uri?.let {
            try {
                val yamlContent = ConfigManager.exportToYaml(appState)
                context.contentResolver.openOutputStream(it)?.use { stream ->
                    stream.write(yamlContent.toByteArray())
                }
                importMessage = "Exported YAML successfully"
            } catch (e: Exception) {
                importMessage = "Export failed: ${e.message}"
            }
        }
    }

    // Import launcher
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText() ?: ""
                val fileName = it.lastPathSegment ?: ""

                val data = if (fileName.endsWith(".yaml") || fileName.endsWith(".yml") ||
                    content.trimStart().startsWith("#") || content.trimStart().startsWith("version:") ||
                    content.trimStart().startsWith("modes:")) {
                    ConfigManager.importFromYaml(content)
                } else {
                    ConfigManager.importFromJson(content)
                }

                pendingImportData = data
                showImportConfirm = true
            } catch (e: Exception) {
                importMessage = "Import failed: ${e.message}"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GuardianTheme.ButtonSecondary,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.border(
            width = GuardianTheme.DialogBorderWidth,
            color = GuardianTheme.DialogBorderInfo,
            shape = RoundedCornerShape(0.dp)
        ),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = GuardianTheme.TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "SETTINGS",
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = GuardianTheme.TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ===== PERMISSIONS SECTION =====
                Text(
                    "PERMISSIONS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 2.sp
                )

                PermissionRow(
                    name = "USAGE ACCESS",
                    granted = usageStatsGranted,
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                )
                PermissionRow(
                    name = "DISPLAY OVER APPS",
                    granted = overlayGranted,
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"))
                        )
                    }
                )
                PermissionRow(
                    name = "BATTERY OPTIMIZATION",
                    granted = batteryGranted,
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = GuardianTheme.WarningBackground,
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            )
                        } catch (_: Exception) {}
                    }
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.OpenInNew, null, tint = GuardianTheme.Warning, modifier = Modifier.size(14.dp))
                            Text(
                                "DISABLE 'PAUSE APP IF UNUSED'",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.Warning,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Text(
                            "Recommended — prevents Android from hibernating Guardian in background",
                            fontSize = 9.sp,
                            color = Color(0xFF999966),
                            letterSpacing = 0.3.sp
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ===== DATA SECTION =====
                Text(
                    "DATA",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 2.sp
                )

                // Export button - opens format chooser
                Button(
                    onClick = { showExportFormatChooser = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardianTheme.BackgroundSurface,
                        contentColor = GuardianTheme.TextPrimary
                    ),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(16.dp))
                        Text("EXPORT CONFIG", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }

                // Import button
                Button(
                    onClick = {
                        if (appState.activeModes.isNotEmpty()) {
                            importCountdown = 60
                            importConfirmText = ""
                            showImportSafetyGate = true
                        } else {
                            importLauncher.launch(arrayOf("application/json", "application/x-yaml", "*/*"))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardianTheme.BackgroundSurface,
                        contentColor = GuardianTheme.TextPrimary
                    ),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(16.dp))
                        Text("IMPORT CONFIG", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }

                // Stats
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = GuardianTheme.BackgroundSurface
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${appState.modes.size} modes  ·  ${appState.schedules.size} schedules  ·  ${appState.nfcTags.size} tags",
                            fontSize = 10.sp,
                            color = GuardianTheme.TextSecondary,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Status message
                importMessage?.let { msg ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = if (msg.contains("success", ignoreCase = true)) GuardianTheme.SuccessBackground else GuardianTheme.ErrorDark
                    ) {
                        Text(
                            msg.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (msg.contains("success", ignoreCase = true)) GuardianTheme.Success else Color(0xFFFF8888),
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = GuardianTheme.TextPrimary)
            ) {
                Text("DONE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
    )

    // Export format chooser dialog
    if (showExportFormatChooser) {
        AlertDialog(
            onDismissRequest = { showExportFormatChooser = false },
            containerColor = GuardianTheme.ButtonSecondary,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.border(
                width = GuardianTheme.DialogBorderWidth,
                color = GuardianTheme.DialogBorderInfo,
                shape = RoundedCornerShape(0.dp)
            ),
            title = {
                Text("EXPORT FORMAT", fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = GuardianTheme.TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface,
                        onClick = {
                            showExportFormatChooser = false
                            exportJsonLauncher.launch("guardian_config.json")
                        }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "JSON",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                "Standard data format — compatible with most tools",
                                fontSize = 10.sp,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 0.3.sp
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface,
                        onClick = {
                            showExportFormatChooser = false
                            exportYamlLauncher.launch("guardian_config.yaml")
                        }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "YAML",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                "Human-readable format — easy to edit by hand",
                                fontSize = 10.sp,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 0.3.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showExportFormatChooser = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF808080))
                ) {
                    Text("CANCEL", letterSpacing = 1.sp)
                }
            },
        )
    }

    // Import safety gate - countdown + RESET ALL DATA when modes are active
    if (showImportSafetyGate) {
        LaunchedEffect(importCountdown) {
            if (importCountdown > 0) {
                kotlinx.coroutines.delay(1000)
                importCountdown--
            }
        }

        AlertDialog(
            onDismissRequest = {
                showImportSafetyGate = false
                importConfirmText = ""
                importCountdown = 60
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Warning, null, tint = GuardianTheme.Error, modifier = Modifier.size(24.dp))
                    Text("MODES ARE ACTIVE", fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = GuardianTheme.Error)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.ErrorDark
                    ) {
                        Text(
                            "Importing a config while modes are active could be used to bypass blocking. Verify your intent.",
                            fontSize = 11.sp,
                            color = Color(0xFFFF8888),
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    if (importCountdown > 0) {
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
                                    importCountdown.toString(),
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.Black,
                                    color = GuardianTheme.Error
                                )
                                Text("Waiting...", fontSize = 11.sp, color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
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
                            value = importConfirmText,
                            onValueChange = { importConfirmText = it },
                            placeholder = { Text("Type here...", fontSize = 12.sp, letterSpacing = 1.sp) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = GuardianTheme.BackgroundSurface,
                                unfocusedContainerColor = GuardianTheme.BackgroundSurface,
                                focusedIndicatorColor = if (importConfirmText == "RESET ALL DATA") GuardianTheme.Error else Color.White,
                                unfocusedIndicatorColor = GuardianTheme.BorderSubtle,
                                cursorColor = GuardianTheme.InputCursor,
                                focusedTextColor = GuardianTheme.InputText,
                                unfocusedTextColor = GuardianTheme.InputText
                            ),
                            shape = RoundedCornerShape(0.dp),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (importConfirmText.isNotEmpty() && importConfirmText != "RESET ALL DATA") {
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
                    onClick = {
                        showImportSafetyGate = false
                        importConfirmText = ""
                        importCountdown = 60
                        importLauncher.launch(arrayOf("application/json", "application/x-yaml", "*/*"))
                    },
                    enabled = importCountdown == 0 && importConfirmText == "RESET ALL DATA",
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = GuardianTheme.Error,
                        disabledContentColor = Color(0xFF404040)
                    )
                ) {
                    Text("CONTINUE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportSafetyGate = false
                        importConfirmText = ""
                        importCountdown = 60
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF808080))
                ) {
                    Text("CANCEL", letterSpacing = 1.sp)
                }
            },
        )
    }

    // Import confirmation sub-dialog
    if (showImportConfirm && pendingImportData != null) {
        val data = pendingImportData!!
        AlertDialog(
            onDismissRequest = { showImportConfirm = false; pendingImportData = null },
            containerColor = GuardianTheme.ButtonSecondary,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.border(
                width = GuardianTheme.DialogBorderWidth,
                color = GuardianTheme.DialogBorderWarning,
                shape = RoundedCornerShape(0.dp)
            ),
            title = {
                Text("IMPORT CONFIG", fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = GuardianTheme.TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${data.modes.size} modes", fontSize = 11.sp, color = GuardianTheme.TextPrimary, letterSpacing = 0.5.sp)
                            Text("${data.schedules.size} schedules", fontSize = 11.sp, color = GuardianTheme.TextPrimary, letterSpacing = 0.5.sp)
                            Text("${data.nfcTags.size} NFC tags", fontSize = 11.sp, color = GuardianTheme.TextPrimary, letterSpacing = 0.5.sp)
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.WarningBackground
                    ) {
                        Text(
                            "REPLACE will overwrite all current config. MERGE will add non-duplicate items.",
                            fontSize = 10.sp,
                            color = GuardianTheme.Warning,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            viewModel.importConfig(data, mergeMode = true)
                            importMessage = "Imported (merged) successfully"
                            showImportConfirm = false
                            pendingImportData = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = GuardianTheme.TextPrimary)
                    ) {
                        Text("MERGE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    TextButton(
                        onClick = {
                            viewModel.importConfig(data, mergeMode = false)
                            importMessage = "Imported (replaced) successfully"
                            showImportConfirm = false
                            pendingImportData = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = GuardianTheme.Error)
                    ) {
                        Text("REPLACE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showImportConfirm = false; pendingImportData = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF808080))
                ) {
                    Text("CANCEL", letterSpacing = 1.sp)
                }
            },
        )
    }
}

@Composable
private fun PermissionRow(
    name: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = GuardianTheme.BackgroundSurface,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (granted) GuardianTheme.Success else Color(0xFF8B0000),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                name,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = GuardianTheme.TextPrimary,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (granted) "GRANTED" else "TAP TO GRANT",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (granted) GuardianTheme.TextSecondary else Color(0xFF8B0000),
                letterSpacing = 0.5.sp
            )
        }
    }
}