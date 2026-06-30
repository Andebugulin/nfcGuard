package com.andebugulin.nfcguard.ui.home

import com.andebugulin.nfcguard.AppState
import com.andebugulin.nfcguard.data.ConfigManager
import com.andebugulin.nfcguard.NfcTag
import com.andebugulin.nfcguard.Schedule
import com.andebugulin.nfcguard.service.ForegroundDetectorService
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.GuardianViewModel
import com.andebugulin.nfcguard.ui.safety.SafeRegimeChallengeDialog
import com.andebugulin.nfcguard.ui.Screen
import com.andebugulin.nfcguard.ui.onboarding.FeatureShowcaseDialog
import com.andebugulin.nfcguard.ui.onboarding.isShowcaseSeen
import com.andebugulin.nfcguard.ui.onboarding.markShowcaseSeen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.interaction.MutableInteractionSource



import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
    val challengeDuration by viewModel.challengeDurationSeconds.collectAsState()
    val context = LocalContext.current

    var showEmergencyDialog by remember { mutableStateOf(false) }
    var showEmergencyChallenge by remember { mutableStateOf(false) }
    var showTagSelectionDialog by remember { mutableStateOf(false) }
    var selectedTagsToDelete by remember { mutableStateOf(setOf<String>()) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showcaseFor by remember { mutableStateOf<Screen?>(null) }

    // First-time entry to a section shows a one-off explainer, but only for a
    // genuinely new user: that section has nothing configured AND the popup
    // hasn't been seen. Otherwise navigate straight through.
    val openSection: (Screen) -> Unit = { screen ->
        val sectionEmpty = when (screen) {
            Screen.MODES -> appState.modes.isEmpty()
            Screen.SCHEDULES -> appState.schedules.isEmpty()
            Screen.NFC_TAGS -> appState.nfcTags.isEmpty()
            else -> false
        }
        if (sectionEmpty && !isShowcaseSeen(context, screen)) showcaseFor = screen
        else onNavigate(screen)
    }

    // Tick every 30s to keep timer countdowns fresh
    var timeTick by remember { mutableStateOf(0L) }
    LaunchedEffect(appState.timedModeDeactivations, appState.timedModeReactivations) {
        while (appState.timedModeDeactivations.isNotEmpty() || appState.timedModeReactivations.isNotEmpty()) {
            kotlinx.coroutines.delay(30_000)
            timeTick = System.currentTimeMillis()
        }
    }
    // Read timeTick to derive 'now' — forces recomposition every 30s for live countdowns
    val now = timeTick.let { System.currentTimeMillis() }

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
        val accessibilityRequired = Build.MANUFACTURER.equals("Google", ignoreCase = true) ||
                Build.MANUFACTURER.equals("Samsung", ignoreCase = true)
        val accessibilityOk = if (accessibilityRequired) {
            ForegroundDetectorService.isEnabled(context)
        } else true
        usageStatsOk && overlayOk && batteryOk && accessibilityOk
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GuardianTheme.BackgroundPrimary)
            .windowInsetsPadding(WindowInsets.systemBars)
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
                    val manualCount = appState.activeModes.count { appState.manuallyActivatedModes.contains(it) }
                    val scheduleCount = appState.activeModes.size - manualCount
                    val timedCount = appState.activeModes.count { appState.timedModeDeactivations.containsKey(it) }

                    val sourceText = buildString {
                        append("${appState.activeModes.size} MODE${if (appState.activeModes.size > 1) "S" else ""} ACTIVE")
                        val parts = mutableListOf<String>()
                        if (manualCount > 0) parts.add("$manualCount MANUAL")
                        if (scheduleCount > 0) parts.add("$scheduleCount SCHEDULED")
                        if (parts.size > 1) append(" (${parts.joinToString(" · ")})")
                    }

                    Text(
                        sourceText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = GuardianTheme.TextSecondary,
                        letterSpacing = 1.sp
                    )

                    if (timedCount > 0) {
                        val nextExpiry = appState.activeModes
                            .mapNotNull { appState.timedModeDeactivations[it] }
                            .minOrNull()
                        if (nextExpiry != null) {
                            val remaining = ((nextExpiry - now) / 60000).coerceAtLeast(0)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Timer,
                                    contentDescription = null,
                                    tint = GuardianTheme.IconSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "${remaining}M REMAINING",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GuardianTheme.TextSecondary,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }

                    val hasManual = manualCount > 0
                    if (hasManual) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp)
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
                    tint = if (permissionsGranted) GuardianTheme.IconPrimary else GuardianTheme.Error,
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
            onClick = { openSection(Screen.MODES) }
        )

        NavigationCard(
            title = "SCHEDULES",
            subtitle = "${appState.schedules.size} CONFIGURED",
            icon = Icons.Default.Schedule,
            onClick = { openSection(Screen.SCHEDULES) }
        )

        NavigationCard(
            title = "NFC TAGS",
            subtitle = "${appState.nfcTags.size} REGISTERED",
            icon = Icons.Default.Nfc,
            onClick = { openSection(Screen.NFC_TAGS) }
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
                        val isManual = appState.manuallyActivatedModes.contains(mode.id)
                        val isTimed = appState.timedModeDeactivations.containsKey(mode.id)

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
                            Column(Modifier.weight(1f)) {
                                Text(
                                    mode.name.uppercase(),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GuardianTheme.BackgroundSurface,
                                    letterSpacing = 1.sp
                                )
                                val sourceLabel = when {
                                    isTimed -> {
                                        val remaining = ((appState.timedModeDeactivations[mode.id] ?: 0) - now) / 60000
                                        val endCal = java.util.Calendar.getInstance().apply {
                                            timeInMillis = appState.timedModeDeactivations[mode.id] ?: 0
                                        }
                                        val endStr = String.format("%02d:%02d", endCal.get(java.util.Calendar.HOUR_OF_DAY), endCal.get(java.util.Calendar.MINUTE))
                                        val prefix = if (isManual) "MANUAL" else "ACTIVE"
                                        "$prefix · ${remaining.coerceAtLeast(0)}M LEFT · UNTIL $endStr"
                                    }
                                    isManual -> "MANUAL · NFC TO UNLOCK"
                                    else -> {
                                        val cal = java.util.Calendar.getInstance()
                                        val currentDay = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                                            java.util.Calendar.MONDAY -> 1; java.util.Calendar.TUESDAY -> 2
                                            java.util.Calendar.WEDNESDAY -> 3; java.util.Calendar.THURSDAY -> 4
                                            java.util.Calendar.FRIDAY -> 5; java.util.Calendar.SATURDAY -> 6
                                            java.util.Calendar.SUNDAY -> 7; else -> 1
                                        }
                                        val endTimeStr = appState.schedules.firstOrNull { schedule ->
                                            schedule.linkedModeIds.contains(mode.id) &&
                                                    schedule.hasEndTime
                                        }?.timeSlot?.getTimeForDay(currentDay)?.let { dt ->
                                            String.format(" · UNTIL %02d:%02d", dt.endHour, dt.endMinute)
                                        } ?: ""
                                        "BY SCHEDULE$endTimeStr"
                                    }
                                }
                                Text(
                                    sourceLabel,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = GuardianTheme.OnLightSurfaceSecondaryText,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Temporarily unlocked modes — yellow panel inside Column
        if (appState.timedModeReactivations.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(0.dp),
                color = GuardianTheme.Warning
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "TEMPORARILY UNLOCKED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    appState.timedModeReactivations.forEach { (modeId, reactivateAt) ->
                        val mode = appState.modes.find { it.id == modeId }
                        if (mode != null) {
                            val remaining = ((reactivateAt - now) / 60000).coerceAtLeast(0)
                            val remainH = remaining / 60
                            val remainM = remaining % 60
                            val remainText = if (remainH > 0) "${remainH}H ${remainM}M" else "${remainM}M"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.LockOpen,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        mode.name.uppercase(),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        "RE-ENABLES IN $remainText",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = GuardianTheme.WarningAccentDim,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    onClick = { viewModel.reactivateMode(modeId) },
                                    color = Color.Black,
                                    shape = RoundedCornerShape(0.dp)
                                ) {
                                    Text(
                                        "RE-ENABLE",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
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
                    // Modes active — require safe regime challenge
                    showEmergencyChallenge = true
                } else {
                    // No modes active --” skip safety gate, go straight to tag selection
                    showTagSelectionDialog = true
                }
            }
        )
    }

    if (showEmergencyChallenge) {
        SafeRegimeChallengeDialog(
            actionDescription = "Emergency reset will deactivate all modes and delete selected NFC tags. This could bypass the blocker.",
            totalDurationSeconds = challengeDuration,
            onComplete = {
                showEmergencyChallenge = false
                showTagSelectionDialog = true
            },
            onCancel = {
                showEmergencyChallenge = false
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

    showcaseFor?.let { screen ->
        FeatureShowcaseDialog(
            screen = screen,
            onContinue = {
                markShowcaseSeen(context, screen)
                showcaseFor = null
                onNavigate(screen)
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
                        "--- Deactivate all active modes",
                        fontSize = 13.sp,
                        color = GuardianTheme.OnLightSurfaceBorder,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        "--- Delete lost NFC tags",
                        fontSize = 13.sp,
                        color = GuardianTheme.OnLightSurfaceBorder,
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
                    contentColor = GuardianTheme.TextSecondary
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
                    color = GuardianTheme.OnLightSurfaceBorder,
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
                    contentColor = GuardianTheme.TextSecondary
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
    var showImportChallenge by remember { mutableStateOf(false) }
    val challengeDuration by viewModel.challengeDurationSeconds.collectAsState()
    var showTestChallenge by remember { mutableStateOf(false) }
    var showDurationPicker by remember { mutableStateOf(false) }

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
    val notificationGranted = remember(permRefreshKey) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
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
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow(
                        name = "NOTIFICATIONS (OPTIONAL)",
                        granted = notificationGranted,
                        onClick = {
                            try {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                )
                            } catch (_: Exception) {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                )
                            }
                        }
                    )
                }
// Accessibility Service — required on Google/Samsung, recommended elsewhere
                val accessibilityGranted = remember(permRefreshKey) {
                    ForegroundDetectorService.isEnabled(context)
                }
                val accessibilityIsRequired = Build.MANUFACTURER.equals("Google", ignoreCase = true) ||
                        Build.MANUFACTURER.equals("Samsung", ignoreCase = true)
                PermissionRow(
                    name = if (accessibilityIsRequired) "ACCESSIBILITY SERVICE"
                    else "ACCESSIBILITY (RECOMMENDED)",
                    granted = accessibilityGranted,
                    optional = !accessibilityIsRequired,
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            )
                        } catch (_: Exception) {}
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
                            "Recommended -” prevents Android from hibernating Guardian in background",
                            fontSize = 9.sp,
                            color = GuardianTheme.WarningTextMuted,
                            letterSpacing = 0.3.sp
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ===== SAFE REGIME SECTION =====
                Text(
                    "SAFE REGIME",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 2.sp
                )

                val safeRegimeEnabled by viewModel.safeRegimeEnabled.collectAsState()
                var showSafeRegimeChallenge by remember { mutableStateOf(false) }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = GuardianTheme.BackgroundSurface
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "ANTI-BYPASS PROTECTION",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (safeRegimeEnabled) "Actions that could bypass blocking require a 1.5-minute attention challenge"
                                else "Disabled — all actions are unrestricted",
                                fontSize = 9.sp,
                                color = GuardianTheme.TextTertiary,
                                letterSpacing = 0.3.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = safeRegimeEnabled,
                            onCheckedChange = { newValue ->
                                if (newValue) {
                                    // Turning ON is always allowed
                                    viewModel.setSafeRegimeEnabled(true)
                                } else {
                                    // Turning OFF: require challenge if modes are active
                                    if (appState.activeModes.isNotEmpty()) {
                                        showSafeRegimeChallenge = true
                                    } else {
                                        viewModel.setSafeRegimeEnabled(false)
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color.White,
                                uncheckedThumbColor = GuardianTheme.ButtonDisabledText,
                                uncheckedTrackColor = GuardianTheme.ButtonDisabledContainer
                            )
                        )
                    }
                }

                if (safeRegimeEnabled) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.WarningBackground
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Shield, null, tint = GuardianTheme.Warning, modifier = Modifier.size(14.dp))
                            Text(
                                "Protected: schedule linking to active modes, editing/deleting active schedules, disabling safe regime",
                                fontSize = 9.sp,
                                color = GuardianTheme.WarningTextMuted,
                                letterSpacing = 0.3.sp
                            )
                        }
                    }
                }

                // Challenge duration — configurable, but never below 1:30
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = GuardianTheme.BackgroundSurface,
                    onClick = { showDurationPicker = true }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "CHALLENGE DURATION",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "How long you must stay attentive (minimum 1:30)",
                                fontSize = 9.sp,
                                color = GuardianTheme.TextTertiary,
                                letterSpacing = 0.3.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${challengeDuration / 60}:${"%02d".format(challengeDuration % 60)}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = GuardianTheme.TextPrimary,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Test the challenge without needing an active mode
                Button(
                    onClick = { showTestChallenge = true },
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
                        Icon(Icons.Default.Shield, null, modifier = Modifier.size(16.dp))
                        Text("TEST CHALLENGE", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }

                if (showSafeRegimeChallenge) {
                    SafeRegimeChallengeDialog(
                        actionDescription = "You are trying to disable Safe Regime while modes are active. This could allow bypassing the blocker.",
                        totalDurationSeconds = challengeDuration,
                        onComplete = {
                            viewModel.setSafeRegimeEnabled(false)
                            showSafeRegimeChallenge = false
                        },
                        onCancel = {
                            showSafeRegimeChallenge = false
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ===== BLOCKING METHOD SECTION =====
                Text(
                    "BLOCKING METHOD",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 2.sp
                )

                val accessibilityOn = remember(permRefreshKey) {
                    ForegroundDetectorService.isEnabled(context)
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = GuardianTheme.BackgroundSurface
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (accessibilityOn) "FORCE-CLOSE MODE" else "OVERLAY MODE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (accessibilityOn) "Blocked apps are force-closed instantly"
                                else "Blocked apps show a full-screen overlay",
                                fontSize = 9.sp,
                                color = GuardianTheme.TextTertiary,
                                letterSpacing = 0.3.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "AUTO",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = GuardianTheme.TextSecondary,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = GuardianTheme.WarningBackground
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Info, null, tint = GuardianTheme.Warning, modifier = Modifier.size(14.dp))
                        Text(
                            if (accessibilityOn) "Accessibility ON — force-close avoids overlay conflicts on Samsung/Pixel"
                            else "Accessibility OFF — overlay mode active. Enable accessibility for force-close",
                            fontSize = 9.sp,
                            color = GuardianTheme.WarningTextMuted,
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
                            showImportChallenge = true
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
                            "${appState.modes.size} modes  -  ${appState.schedules.size} schedules  -  ${appState.nfcTags.size} tags",
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
                            color = if (msg.contains("success", ignoreCase = true)) GuardianTheme.Success else GuardianTheme.ErrorText,
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
                                "Standard data format -” compatible with most tools",
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
                                "Human-readable format -” easy to edit by hand",
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
                    colors = ButtonDefaults.textButtonColors(contentColor = GuardianTheme.TextSecondary)
                ) {
                    Text("CANCEL", letterSpacing = 1.sp)
                }
            },
        )
    }

    // Import safety gate - SafeRegime challenge when modes are active
    if (showImportChallenge) {
        SafeRegimeChallengeDialog(
            actionDescription = "Importing a config while modes are active could be used to bypass blocking. Verify your intent.",
            totalDurationSeconds = challengeDuration,
            onComplete = {
                showImportChallenge = false
                importLauncher.launch(arrayOf("application/json", "application/x-yaml", "*/*"))
            },
            onCancel = {
                showImportChallenge = false
            }
        )
    }

    // Let users feel the anti-bypass challenge without an active mode
    if (showTestChallenge) {
        SafeRegimeChallengeDialog(
            actionDescription = "This is a practice run of the anti-bypass challenge. It's how Guardian protects sensitive actions while modes are active.",
            totalDurationSeconds = challengeDuration,
            onComplete = { showTestChallenge = false },
            onCancel = { showTestChallenge = false }
        )
    }

    if (showDurationPicker) {
        ChallengeDurationDialog(
            currentSeconds = challengeDuration,
            onDismiss = { showDurationPicker = false },
            onConfirm = { seconds ->
                viewModel.setChallengeDurationSeconds(seconds)
                showDurationPicker = false
            }
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
                    colors = ButtonDefaults.textButtonColors(contentColor = GuardianTheme.TextSecondary)
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
    onClick: () -> Unit,
    optional: Boolean = false
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
                when {
                    granted -> Icons.Default.CheckCircle
                    optional -> Icons.Default.Info
                    else -> Icons.Default.Error
                },
                contentDescription = null,
                tint = when {
                    granted -> GuardianTheme.Success
                    optional -> GuardianTheme.TextSecondary
                    else -> GuardianTheme.Error
                },
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
                when {
                    granted -> "GRANTED"
                    optional -> "TAP TO ENABLE"
                    else -> "TAP TO GRANT"
                },
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    granted -> GuardianTheme.TextSecondary
                    optional -> GuardianTheme.TextSecondary
                    else -> GuardianTheme.Error
                },
                letterSpacing = 0.5.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChallengeDurationDialog(
    currentSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var mins by remember { mutableStateOf((currentSeconds / 60).toString()) }
    var secs by remember { mutableStateOf((currentSeconds % 60).toString()) }
    val total = (mins.toIntOrNull() ?: 0) * 60 + (secs.toIntOrNull() ?: 0)
    val belowMin = total < 90

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = GuardianTheme.BorderFocused,
        unfocusedBorderColor = GuardianTheme.BorderSubtle,
        focusedTextColor = GuardianTheme.InputText,
        unfocusedTextColor = GuardianTheme.InputText,
        focusedLabelColor = GuardianTheme.TextSecondary,
        unfocusedLabelColor = GuardianTheme.TextTertiary,
        cursorColor = GuardianTheme.InputCursor
    )

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
            Text(
                "CHALLENGE DURATION",
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = GuardianTheme.TextPrimary,
                fontSize = 14.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "How long you must stay attentive before a bypass-risky action goes through. The minimum is 1:30 — you can make it longer, never shorter.",
                    fontSize = 10.sp,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 0.3.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = mins,
                        onValueChange = { mins = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("MIN", fontSize = 9.sp, letterSpacing = 1.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = fieldColors,
                        shape = RoundedCornerShape(0.dp)
                    )
                    Text(":", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GuardianTheme.TextPrimary)
                    OutlinedTextField(
                        value = secs,
                        onValueChange = { secs = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("SEC", fontSize = 9.sp, letterSpacing = 1.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = fieldColors,
                        shape = RoundedCornerShape(0.dp)
                    )
                }
                if (belowMin) {
                    Text(
                        "Minimum is 1:30",
                        fontSize = 10.sp,
                        color = GuardianTheme.Error,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(total) }, enabled = !belowMin) {
                Text("APPLY", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = GuardianTheme.TextSecondary)
            ) {
                Text("CANCEL", letterSpacing = 1.sp)
            }
        }
    )
}
