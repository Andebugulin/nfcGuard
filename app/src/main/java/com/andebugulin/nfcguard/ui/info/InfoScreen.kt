package com.andebugulin.nfcguard.ui.info

import androidx.compose.ui.res.pluralStringResource
import com.andebugulin.nfcguard.R
import androidx.compose.ui.res.stringResource
import com.andebugulin.nfcguard.data.AppLogger
import com.andebugulin.nfcguard.ui.GuardianTheme

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch
import com.andebugulin.nfcguard.ui.components.ScreenHeader
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.vector.ImageVector
import com.andebugulin.nfcguard.ui.components.InfoDisclosure
import com.andebugulin.nfcguard.ui.components.GuardianType


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showLogViewer by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var logCount by remember { mutableIntStateOf(AppLogger.getEntryCount()) }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = GuardianTheme.BackgroundSurface,
                    contentColor = GuardianTheme.TextPrimary,
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item {
                    ScreenHeader(
                        title = stringResource(R.string.info_about),
                        onBack = onBack,
                        fillTitleWidth = false,
                        contentPadding = PaddingValues(0.dp)
                    )
                }

                item {
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                stringResource(R.string.home_nfcguard),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 2.sp
                            )
                            Text(
                                stringResource(R.string.info_nfc_powered_app_blocker_for),
                                fontSize = 12.sp,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }

                item {
                    // GitHub + Stars section
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface,
                        modifier = Modifier.clickable {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/Andebugulin/nfcGuard")
                            )
                            context.startActivity(intent)
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(modifier = Modifier.size(32.dp)) {
                                GitHubOctocat(modifier = Modifier.size(32.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.info_star_on_github),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GuardianTheme.TextPrimary,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    stringResource(R.string.info_support_the_project),
                                    fontSize = 10.sp,
                                    color = GuardianTheme.TextSecondary,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = GuardianTheme.BackgroundPrimary
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        tint = GuardianTheme.IconPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        GITHUB_STARS_LABEL,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GuardianTheme.TextPrimary,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // ═══════════════════════════════════════
                // REPORT PROBLEM SECTION
                // ═══════════════════════════════════════
                item {
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Header
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.BugReport,
                                    contentDescription = null,
                                    tint = GuardianTheme.IconPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column {
                                    Text(
                                        stringResource(R.string.info_report_a_problem),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GuardianTheme.TextPrimary,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        stringResource(R.string.info_opens_a_github_issue_with),
                                        fontSize = 10.sp,
                                        color = GuardianTheme.TextSecondary,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }

                            // Log count
                            Text(
                                pluralStringResource(R.plurals.info_events_logged, logCount, logCount),
                                fontSize = 10.sp,
                                color = GuardianTheme.TextTertiary,
                                letterSpacing = 0.5.sp
                            )

                            // Primary action: Open GitHub Issue
                            Button(
                                onClick = { AppLogger.openGitHubIssue(context) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GuardianTheme.ButtonPrimary,
                                    contentColor = GuardianTheme.ButtonPrimaryText
                                ),
                                shape = RoundedCornerShape(0.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.BugReport,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.info_report_on_github),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }

                            // Secondary actions row
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Save log file
                                OutlinedButton(
                                    onClick = { AppLogger.saveAndShareLogFile(context) },
                                    shape = RoundedCornerShape(0.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = GuardianTheme.TextPrimary
                                    ),
                                    modifier = Modifier.weight(1f).height(40.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Save,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.info_save_log_file),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                }

                                // View logs
                                OutlinedButton(
                                    onClick = { showLogViewer = true },
                                    shape = RoundedCornerShape(0.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = GuardianTheme.TextPrimary
                                    ),
                                    modifier = Modifier.height(40.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.info_view),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }

                            // Clear logs
                            TextButton(
                                onClick = {
                                    AppLogger.clear()
                                    logCount = 1  // stringResource(R.string.info_logs_cleared) entry
                                    scope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.info_logs_cleared))
                                    }
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = GuardianTheme.TextTertiary
                                )
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.info_clear_logs),
                                    fontSize = 10.sp,
                                    letterSpacing = 1.sp
                                )
                            }

                            // Instructions
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(0.dp),
                                color = GuardianTheme.BackgroundPrimary
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.info_reproduce_the_bug_first_then),
                                        style = GuardianType.EmptyHint,
                                        color = GuardianTheme.TextTertiary
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    InfoSection(
                        title = stringResource(R.string.info_how_it_works),
                        items = listOf(
                            stringResource(R.string.info_t_1_modes_pick_apps_to),
                            stringResource(R.string.info_t_2_tags_register_an_nfc),
                            stringResource(R.string.info_t_3_schedules_turn_modes_on),
                            stringResource(R.string.info_t_4_tap_unlock_with_the)
                        )
                    )
                }

                item {
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                stringResource(R.string.info_how_blocking_works),
                                style = GuardianType.Label,
                                color = GuardianTheme.TextSecondary
                            )

                            BlockingMethodRow(
                                icon = Icons.Default.Shield,
                                name = stringResource(R.string.home_force_close_mode),
                                summary = stringResource(R.string.info_closes_the_app_instantly_needs),
                                detail = stringResource(R.string.info_opening_a_blocked_app_sends)
                            )

                            BlockingMethodRow(
                                icon = Icons.Default.Fullscreen,
                                name = stringResource(R.string.home_overlay_mode),
                                summary = stringResource(R.string.info_covers_the_screen_no_extra),
                                detail = stringResource(R.string.info_a_full_screen_black_panel)
                            )

                            Text(
                                stringResource(R.string.info_picked_automatically_settings_shows_which),
                                style = GuardianType.EmptyHint,
                                color = GuardianTheme.TextTertiary
                            )
                        }
                    }
                }

                item {
                    InfoSection(
                        title = stringResource(R.string.info_not_working),
                        items = listOf(
                            stringResource(R.string.info_u2022_enable_accessibility_for_the),
                            stringResource(R.string.info_u2022_turn_off_pause_app),
                            stringResource(R.string.info_u2022_xiaomi_samsung_enable_autostart)
                        )
                    )
                }

                item {
                    Surface(
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.info_open_source),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                stringResource(R.string.info_free_and_open_source_contributions),
                                fontSize = 12.sp,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // Log viewer dialog
    if (showLogViewer) {
        LogViewerDialog(
            onDismiss = { showLogViewer = false },
            onShareFile = { AppLogger.saveAndShareLogFile(context) }
        )
    }
}

@Composable
fun LogViewerDialog(
    onDismiss: () -> Unit,
    onShareFile: () -> Unit
) {
    val logText = remember { AppLogger.getLogText() }
    val entryCount = remember { AppLogger.getEntryCount() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GuardianTheme.BackgroundSurface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(0.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.BugReport,
                    contentDescription = null,
                    tint = GuardianTheme.IconPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    stringResource(R.string.info_event_log_count, entryCount),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    fontSize = 14.sp
                )
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                if (logText.isBlank()) {
                    Text(
                        stringResource(R.string.info_no_events_logged_yet_use),
                        fontSize = 11.sp,
                        color = GuardianTheme.TextSecondary,
                        letterSpacing = 0.5.sp
                    )
                } else {
                    Text(
                        logText,
                        fontSize = 9.sp,
                        color = GuardianTheme.TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onShareFile,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GuardianTheme.ButtonPrimary,
                    contentColor = GuardianTheme.ButtonPrimaryText
                ),
                shape = RoundedCornerShape(0.dp)
            ) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.info_save_file), fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_close), color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
            }
        }
    )
}

/**
 * One enforcement strategy: its name, a one-line summary, and the paragraph of
 * detail behind an (i). The two of these replace ~150 lines of stacked cards.
 */
@Composable
private fun BlockingMethodRow(
    icon: ImageVector,
    name: String,
    summary: String,
    detail: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = GuardianTheme.BackgroundPrimary
    ) {
        InfoDisclosure(detail = detail, modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = GuardianTheme.TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Text(name, style = GuardianType.Label, color = GuardianTheme.TextPrimary)
            }
            Spacer(Modifier.height(4.dp))
            Text(summary, style = GuardianType.EmptyHint, color = GuardianTheme.TextSecondary)
        }
    }
}

@Composable
fun InfoSection(
    title: String,
    content: String? = null,
    items: List<String>? = null
) {
    Surface(
        shape = RoundedCornerShape(0.dp),
        color = GuardianTheme.BackgroundSurface
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = GuardianTheme.TextSecondary,
                letterSpacing = 1.sp
            )

            content?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = GuardianTheme.TextPrimary,
                    letterSpacing = 0.5.sp,
                    lineHeight = 18.sp
                )
            }

            items?.forEach { item ->
                Text(
                    item,
                    fontSize = 12.sp,
                    color = GuardianTheme.TextPrimary,
                    letterSpacing = 0.5.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
fun GitHubOctocat(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val scale = size.minDimension / 640f

        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(316.8f * scale, 72f * scale)
            cubicTo(178.1f * scale, 72f * scale, 72f * scale, 177.3f * scale, 72f * scale, 316f * scale)
            cubicTo(72f * scale, 426.9f * scale, 141.8f * scale, 521.8f * scale, 241.5f * scale, 555.2f * scale)
            cubicTo(254.3f * scale, 557.5f * scale, 258.8f * scale, 549.6f * scale, 258.8f * scale, 543.1f * scale)
            cubicTo(258.8f * scale, 536.9f * scale, 258.5f * scale, 502.7f * scale, 258.5f * scale, 481.7f * scale)
            cubicTo(258.5f * scale, 481.7f * scale, 188.5f * scale, 496.7f * scale, 173.8f * scale, 451.9f * scale)
            cubicTo(173.8f * scale, 451.9f * scale, 162.4f * scale, 422.8f * scale, 146f * scale, 415.3f * scale)
            cubicTo(146f * scale, 415.3f * scale, 123.1f * scale, 399.6f * scale, 147.6f * scale, 399.9f * scale)
            cubicTo(147.6f * scale, 399.9f * scale, 172.5f * scale, 401.9f * scale, 186.2f * scale, 425.7f * scale)
            cubicTo(208.1f * scale, 464.3f * scale, 244.8f * scale, 453.2f * scale, 259.1f * scale, 446.6f * scale)
            cubicTo(261.4f * scale, 430.6f * scale, 267.9f * scale, 419.5f * scale, 275.1f * scale, 412.9f * scale)
            cubicTo(219.2f * scale, 406.7f * scale, 162.8f * scale, 398.6f * scale, 162.8f * scale, 302.4f * scale)
            cubicTo(162.8f * scale, 274.9f * scale, 170.4f * scale, 261.1f * scale, 186.4f * scale, 243.5f * scale)
            cubicTo(183.8f * scale, 237f * scale, 175.3f * scale, 210.2f * scale, 189f * scale, 175.6f * scale)
            cubicTo(209.9f * scale, 169.1f * scale, 258f * scale, 202.6f * scale, 258f * scale, 202.6f * scale)
            cubicTo(278f * scale, 197f * scale, 299.5f * scale, 194.1f * scale, 320.8f * scale, 194.1f * scale)
            cubicTo(342.1f * scale, 194.1f * scale, 363.6f * scale, 197f * scale, 383.6f * scale, 202.6f * scale)
            cubicTo(383.6f * scale, 202.6f * scale, 431.7f * scale, 169f * scale, 452.6f * scale, 175.6f * scale)
            cubicTo(466.3f * scale, 210.3f * scale, 457.8f * scale, 237f * scale, 455.2f * scale, 243.5f * scale)
            cubicTo(471.2f * scale, 261.2f * scale, 481f * scale, 275f * scale, 481f * scale, 302.4f * scale)
            cubicTo(481f * scale, 398.9f * scale, 422.1f * scale, 406.6f * scale, 366.2f * scale, 412.9f * scale)
            cubicTo(375.4f * scale, 420.8f * scale, 383.2f * scale, 435.8f * scale, 383.2f * scale, 459.3f * scale)
            cubicTo(383.2f * scale, 493f * scale, 382.9f * scale, 534.7f * scale, 382.9f * scale, 542.9f * scale)
            cubicTo(382.9f * scale, 549.4f * scale, 387.5f * scale, 557.3f * scale, 400.2f * scale, 555f * scale)
            cubicTo(500.2f * scale, 521.8f * scale, 568f * scale, 426.9f * scale, 568f * scale, 316f * scale)
            cubicTo(568f * scale, 177.3f * scale, 455.5f * scale, 72f * scale, 316.8f * scale, 72f * scale)
            close()
        }

        drawPath(
            path = path,
            color = GuardianTheme.IconPrimary
        )
    }
}

/**
 * Star count shown on the GitHub card, updated by hand now and then.
 * nfcGuard makes no network requests and holds no INTERNET permission, so
 * it does not fetch the live number.
 */
private const val GITHUB_STARS_LABEL = "20+"
