package com.andebugulin.nfcguard.ui.home

import com.andebugulin.nfcguard.ui.formatDuration
import androidx.compose.ui.res.pluralStringResource
import com.andebugulin.nfcguard.R
import androidx.compose.ui.res.stringResource
import com.andebugulin.nfcguard.ScheduleClock
import com.andebugulin.nfcguard.BlockDecider
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.Schedule
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.GuardianViewModel
import com.andebugulin.nfcguard.ui.safety.SafeRegimeChallengeDialog
import com.andebugulin.nfcguard.ui.Screen
import com.andebugulin.nfcguard.ui.TestTags

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.interaction.MutableInteractionSource

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.andebugulin.nfcguard.data.Permissions
import androidx.compose.material.icons.filled.LockOpen

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
    var selectedTagsToDelete by remember { mutableStateOf(setOf<String>()) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showBlockedApps by remember { mutableStateOf(false) }
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
        Permissions.allEssentialGranted(context)
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
                    stringResource(R.string.home_nfcguard),
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

                    val activeCount = appState.activeModes.size
                    val activeText = pluralStringResource(R.plurals.home_modes_active, activeCount, activeCount)
                    // The breakdown only adds information when both kinds are on.
                    val sourceText = if (manualCount > 0 && scheduleCount > 0) {
                        stringResource(
                            R.string.home_modes_active_breakdown,
                            activeText,
                            pluralStringResource(R.plurals.home_manual_count, manualCount, manualCount),
                            pluralStringResource(R.plurals.home_scheduled_count, scheduleCount, scheduleCount)
                        )
                    } else activeText

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
                                    stringResource(R.string.home_remaining, formatDuration(remaining)),
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
                                stringResource(R.string.home_tap_nfc_to_unlock),
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
                // Was a trash icon labelled "Emergency Reset" — which reads as
                // "delete everything". This flow turns blocking off when you
                // have lost your tag; nothing of yours is destroyed unless you
                // tick a tag to forget.
                Icon(
                    imageVector = Icons.Default.LockOpen,
                    contentDescription = stringResource(R.string.home_recover_access),
                    tint = GuardianTheme.IconPrimary,
                    modifier = Modifier
                        .testTag(TestTags.Home.EMERGENCY_RESET)
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
                    contentDescription = stringResource(R.string.home_settings_permissions),
                    tint = if (permissionsGranted) GuardianTheme.IconPrimary else GuardianTheme.Error,
                    modifier = Modifier
                        .testTag(TestTags.Home.SETTINGS)
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
                    contentDescription = stringResource(R.string.home_info),
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
            title = stringResource(R.string.home_modes),
            subtitle = pluralStringResource(R.plurals.home_modes_created, appState.modes.size, appState.modes.size),
            icon = Icons.Default.Block,
            testTag = TestTags.Home.NAV_MODES,
            onClick = { onNavigate(Screen.MODES) }
        )

        NavigationCard(
            title = stringResource(R.string.home_schedules),
            subtitle = pluralStringResource(R.plurals.home_schedules_configured, appState.schedules.size, appState.schedules.size),
            icon = Icons.Default.Schedule,
            testTag = TestTags.Home.NAV_SCHEDULES,
            onClick = { onNavigate(Screen.SCHEDULES) }
        )

        NavigationCard(
            title = stringResource(R.string.home_nfc_tags),
            subtitle = pluralStringResource(R.plurals.home_tags_registered, appState.nfcTags.size, appState.nfcTags.size),
            icon = Icons.Default.Nfc,
            testTag = TestTags.Home.NAV_NFC_TAGS,
            onClick = { onNavigate(Screen.NFC_TAGS) }
        )

        Spacer(Modifier.weight(1f))

        // Active Modes Summary
        if (appState.activeModes.isNotEmpty()) {
            val rule = BlockDecider.ruleFor(appState)
            Surface(
                onClick = { showBlockedApps = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(0.dp),
                color = GuardianTheme.TextPrimary
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.home_active_now),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = GuardianTheme.BackgroundSurface,
                            letterSpacing = 1.sp
                        )
                        val count = rule.apps.size
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (rule.blockMode == BlockMode.ALLOW_SELECTED) {
                                    pluralStringResource(R.plurals.home_apps_allowed, count, count)
                                } else {
                                    pluralStringResource(R.plurals.home_apps_blocked, count, count)
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.OnLightSurfaceSecondaryText,
                                letterSpacing = 1.sp
                            )
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = stringResource(R.string.home_show_apps),
                                tint = GuardianTheme.OnLightSurfaceSecondaryText,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
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
                                        val endStr = ScheduleClock.format(endCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + endCal.get(java.util.Calendar.MINUTE))
                                        val prefix = if (isManual) stringResource(R.string.home_manual) else stringResource(R.string.home_active)
                                        stringResource(R.string.home_timed_label, prefix, formatDuration(remaining), endStr)
                                    }
                                    isManual -> stringResource(R.string.home_manual_nfc_to_unlock)
                                    else -> {
                                        val until = ScheduleClock.modeHeldUntil(
                                            appState, mode.id, ScheduleClock.momentOf(now)
                                        )
                                        if (until != null) stringResource(R.string.home_by_schedule_until, ScheduleClock.format(until))
                                        else stringResource(R.string.home_by_schedule)
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
                        stringResource(R.string.home_temporarily_unlocked),
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
                            val remainText = formatDuration(remaining)
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
                                        stringResource(R.string.home_reenables_in, remainText),
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
                                        stringResource(R.string.home_re_enable),
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

    if (showBlockedApps && appState.activeModes.isNotEmpty()) {
        BlockedAppsDialog(
            rule = BlockDecider.ruleFor(appState),
            onDismiss = { showBlockedApps = false }
        )
    }

    // Recovery. One screen states the outcome and takes the (optional) tag
    // selection; the challenge comes after, so the user knows what they are
    // waiting for instead of meeting a countdown they have never seen.
    if (showEmergencyDialog) {
        RecoverAccessDialog(
            activeModeCount = appState.activeModes.size,
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
                showEmergencyDialog = false
                selectedTagsToDelete = emptySet()
            },
            onConfirm = {
                showEmergencyDialog = false
                if (appState.activeModes.isNotEmpty()) {
                    showEmergencyChallenge = true
                } else {
                    // Nothing is being bypassed, so there is nothing to gate.
                    applyRecovery(viewModel, appState.activeModes, selectedTagsToDelete)
                    selectedTagsToDelete = emptySet()
                }
            }
        )
    }

    if (showEmergencyChallenge) {
        SafeRegimeChallengeDialog(
            actionDescription = recoveryOutcome(
                appState.activeModes.size,
                selectedTagsToDelete.size
            ),
            totalDurationSeconds = challengeDuration,
            onComplete = {
                showEmergencyChallenge = false
                applyRecovery(viewModel, appState.activeModes, selectedTagsToDelete)
                selectedTagsToDelete = emptySet()
            },
            onCancel = {
                showEmergencyChallenge = false
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
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(testTag),
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
