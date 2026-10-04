package com.andebugulin.nfcguard.ui.home

import androidx.compose.ui.res.pluralStringResource
import com.andebugulin.nfcguard.R
import androidx.compose.ui.res.stringResource
import com.andebugulin.nfcguard.AppState
import com.andebugulin.nfcguard.data.ConfigManager
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.onboarding.PermissionsPage
import com.andebugulin.nfcguard.ui.GuardianViewModel
import com.andebugulin.nfcguard.ui.safety.SafeRegimeChallengeDialog
import com.andebugulin.nfcguard.ui.TestTags

import androidx.compose.foundation.border
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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.andebugulin.nfcguard.data.Permissions
import com.andebugulin.nfcguard.ui.components.InfoDisclosure
import com.andebugulin.nfcguard.ui.components.GuardianType

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

    // Re-read on resume and on a slow poll: the permissions list below does
    // its own probing, but the blocking-method readout still has to notice the
    // accessibility switch being flipped while this dialog is open.
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
                importMessage = context.getString(R.string.home_exported_json_successfully)
            } catch (e: Exception) {
                importMessage = context.getString(R.string.settings_export_failed, e.message)
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
                importMessage = context.getString(R.string.home_exported_yaml_successfully)
            } catch (e: Exception) {
                importMessage = context.getString(R.string.settings_export_failed, e.message)
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
                importMessage = context.getString(R.string.settings_import_failed, e.message)
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
                    stringResource(R.string.home_settings),
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
                    stringResource(R.string.home_permissions),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 2.sp
                )


                // One list, one owner. This used to be a second implementation
                // of the onboarding permissions page — its own permission
                // checks, its own row widget, its own 2-second poll — and the
                // two had already drifted: Settings never mentioned autostart
                // and called `canDrawOverlays` directly instead of going
                // through `Permissions`.
                PermissionsPage(scrollable = false)

                Spacer(Modifier.height(8.dp))

                // ===== SAFE REGIME SECTION =====
                Text(
                    stringResource(R.string.home_safe_regime),
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
                        // The list of protected actions used to be a second card
                        // below this row; it is the same information, one tap away.
                        InfoDisclosure(
                            detail = stringResource(R.string.home_protected_linking_a_schedule_to),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(R.string.home_anti_bypass_protection),
                                style = GuardianType.Label,
                                color = GuardianTheme.TextPrimary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (safeRegimeEnabled) stringResource(R.string.home_risky_actions_need_a_timed)
                                else stringResource(R.string.home_off_nothing_is_protected),
                                style = GuardianType.Meta,
                                fontWeight = FontWeight.Normal,
                                color = GuardianTheme.TextTertiary
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            modifier = Modifier.testTag(TestTags.Settings.SAFE_REGIME_TOGGLE),
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

                // Challenge duration — configurable, but never below the floor
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.Settings.CHALLENGE_DURATION_ROW),
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
                                stringResource(R.string.home_challenge_duration),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.home_minimum_1_00),
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
                        Text(stringResource(R.string.home_test_challenge), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }

                if (showSafeRegimeChallenge) {
                    SafeRegimeChallengeDialog(
                        actionDescription = stringResource(R.string.home_you_are_trying_to_disable),
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
                    stringResource(R.string.home_blocking_method),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 2.sp
                )

                val accessibilityOn = remember(permRefreshKey) {
                    Permissions.hasAccessibility(context)
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
                                if (accessibilityOn) stringResource(R.string.home_force_close_mode) else stringResource(R.string.home_overlay_mode),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (accessibilityOn) stringResource(R.string.home_blocked_apps_are_force_closed)
                                else stringResource(R.string.home_blocked_apps_show_a_full),
                                fontSize = 9.sp,
                                color = GuardianTheme.TextTertiary,
                                letterSpacing = 0.3.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.home_auto),
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
                            if (accessibilityOn) stringResource(R.string.home_accessibility_on_force_close_avoids)
                            else stringResource(R.string.home_accessibility_off_overlay_mode_active),
                            fontSize = 9.sp,
                            color = GuardianTheme.WarningTextMuted,
                            letterSpacing = 0.3.sp
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ===== DATA SECTION =====
                Text(
                    stringResource(R.string.home_data),
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
                    modifier = Modifier.fillMaxWidth().height(40.dp).testTag(TestTags.Settings.EXPORT)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.home_export_config), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                    modifier = Modifier.fillMaxWidth().height(40.dp).testTag(TestTags.Settings.IMPORT)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.home_import_config), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                            stringResource(
                            R.string.settings_config_summary,
                            pluralStringResource(R.plurals.settings_mode_count, appState.modes.size, appState.modes.size),
                            pluralStringResource(R.plurals.settings_schedule_count, appState.schedules.size, appState.schedules.size),
                            pluralStringResource(R.plurals.settings_tag_count, appState.nfcTags.size, appState.nfcTags.size)
                        ),
                            fontSize = 10.sp,
                            color = GuardianTheme.TextSecondary,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Status message
                importMessage?.let { msg ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.Settings.STATUS_MESSAGE),
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
                modifier = Modifier.testTag(TestTags.Settings.DONE),
                colors = ButtonDefaults.textButtonColors(contentColor = GuardianTheme.TextPrimary)
            ) {
                Text(stringResource(R.string.home_done), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
                Text(stringResource(R.string.home_export_format), fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = GuardianTheme.TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.Settings.EXPORT_JSON),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface,
                        onClick = {
                            showExportFormatChooser = false
                            exportJsonLauncher.launch("guardian_config.json")
                        }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.home_json),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                stringResource(R.string.home_works_with_most_tools),
                                fontSize = 10.sp,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 0.3.sp
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.Settings.EXPORT_YAML),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface,
                        onClick = {
                            showExportFormatChooser = false
                            exportYamlLauncher.launch("guardian_config.yaml")
                        }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.home_yaml),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                stringResource(R.string.home_easy_to_edit_by_hand),
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
                    Text(stringResource(R.string.home_cancel), letterSpacing = 1.sp)
                }
            },
        )
    }

    // Import safety gate - SafeRegime challenge when modes are active
    if (showImportChallenge) {
        SafeRegimeChallengeDialog(
            actionDescription = stringResource(R.string.home_importing_a_config_while_modes),
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
            actionDescription = stringResource(R.string.home_this_is_a_practice_run),
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
                Text(stringResource(R.string.home_import_config), fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = GuardianTheme.TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(pluralStringResource(R.plurals.settings_mode_count, data.modes.size, data.modes.size), fontSize = 11.sp, color = GuardianTheme.TextPrimary, letterSpacing = 0.5.sp)
                            Text(pluralStringResource(R.plurals.settings_schedule_count, data.schedules.size, data.schedules.size), fontSize = 11.sp, color = GuardianTheme.TextPrimary, letterSpacing = 0.5.sp)
                            Text(pluralStringResource(R.plurals.settings_nfc_tag_count, data.nfcTags.size, data.nfcTags.size), fontSize = 11.sp, color = GuardianTheme.TextPrimary, letterSpacing = 0.5.sp)
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.WarningBackground
                    ) {
                        Text(
                            stringResource(R.string.home_replace_will_overwrite_all_current),
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
                            importMessage = context.getString(R.string.home_imported_merged_successfully)
                            showImportConfirm = false
                            pendingImportData = null
                        },
                        modifier = Modifier.testTag(TestTags.Settings.IMPORT_MERGE),
                        colors = ButtonDefaults.textButtonColors(contentColor = GuardianTheme.TextPrimary)
                    ) {
                        Text(stringResource(R.string.home_merge), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    TextButton(
                        onClick = {
                            viewModel.importConfig(data, mergeMode = false)
                            importMessage = context.getString(R.string.home_imported_replaced_successfully)
                            showImportConfirm = false
                            pendingImportData = null
                        },
                        modifier = Modifier.testTag(TestTags.Settings.IMPORT_REPLACE),
                        colors = ButtonDefaults.textButtonColors(contentColor = GuardianTheme.Error)
                    ) {
                        Text(stringResource(R.string.home_replace), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showImportConfirm = false; pendingImportData = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = GuardianTheme.TextSecondary)
                ) {
                    Text(stringResource(R.string.home_cancel), letterSpacing = 1.sp)
                }
            },
        )
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
    val belowMin = total < GuardianViewModel.CHALLENGE_MIN_SECONDS

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
                stringResource(R.string.home_challenge_duration),
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = GuardianTheme.TextPrimary,
                fontSize = 14.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.home_how_long_you_must_stay),
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
                        label = { Text(stringResource(R.string.home_min), fontSize = 9.sp, letterSpacing = 1.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag(TestTags.Settings.DURATION_MINUTES),
                        colors = fieldColors,
                        shape = RoundedCornerShape(0.dp)
                    )
                    Text(":", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GuardianTheme.TextPrimary)
                    OutlinedTextField(
                        value = secs,
                        onValueChange = { secs = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text(stringResource(R.string.home_sec), fontSize = 9.sp, letterSpacing = 1.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag(TestTags.Settings.DURATION_SECONDS),
                        colors = fieldColors,
                        shape = RoundedCornerShape(0.dp)
                    )
                }
                if (belowMin) {
                    Text(
                        stringResource(R.string.home_minimum_is_1_00),
                        fontSize = 10.sp,
                        color = GuardianTheme.Error,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(total) },
                enabled = !belowMin,
                modifier = Modifier.testTag(TestTags.Settings.DURATION_APPLY)
            ) {
                Text(stringResource(R.string.home_apply), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = GuardianTheme.TextSecondary)
            ) {
                Text(stringResource(R.string.home_cancel), letterSpacing = 1.sp)
            }
        }
    )
}
