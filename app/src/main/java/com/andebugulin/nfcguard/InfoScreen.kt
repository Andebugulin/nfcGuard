package com.andebugulin.nfcguard

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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var githubStars by remember { mutableStateOf(0) }
    var showLogViewer by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var logCount by remember { mutableIntStateOf(AppLogger.getEntryCount()) }

    // Fetch GitHub stars
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL("https://api.github.com/repos/Andebugulin/nfcGuard")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "Guardian-App")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(jsonString)
                    val stars = json.getInt("stargazers_count")

                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        githubStars = stars
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("InfoScreen", "Failed to fetch stars", e)
            }
        }
    }

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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, null, tint = GuardianTheme.IconPrimary)
                        }
                        Text(
                            "ABOUT",
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            fontSize = 24.sp,
                            color = GuardianTheme.TextPrimary
                        )
                    }
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
                                "GUARDIAN",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                color = GuardianTheme.TextPrimary,
                                letterSpacing = 2.sp
                            )
                            Text(
                                "NFC-powered app blocker for digital wellbeing",
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
                                    "STAR ON GITHUB",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GuardianTheme.TextPrimary,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    "Support the project",
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
                                        if (githubStars > 0) githubStars.toString() else "0",
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
                                        "REPORT A PROBLEM",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GuardianTheme.TextPrimary,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        "Opens a GitHub issue with diagnostic info",
                                        fontSize = 10.sp,
                                        color = GuardianTheme.TextSecondary,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }

                            // Log count
                            Text(
                                "$logCount events logged",
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
                                    "REPORT ON GITHUB",
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
                                        "SAVE LOG FILE",
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
                                        "VIEW",
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
                                    logCount = 1  // "Logs cleared" entry
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Logs cleared")
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
                                    "CLEAR LOGS",
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
                                        "HOW TO REPORT:",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GuardianTheme.TextSecondary,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        "1. Reproduce the bug first\n" +
                                                "2. Tap REPORT ON GITHUB \u2014 a pre-filled issue opens\n" +
                                                "3. Describe what happened in the issue\n" +
                                                "4. If logs are long, use SAVE LOG FILE and attach it",
                                        fontSize = 10.sp,
                                        color = GuardianTheme.TextTertiary,
                                        letterSpacing = 0.3.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    InfoSection(
                        title = "WHAT IS GUARDIAN?",
                        content = "Guardian helps you maintain focus by blocking distracting apps. Use NFC tags as physical keys to unlock \u2014 making it harder to mindlessly open blocked apps."
                    )
                }

                item {
                    InfoSection(
                        title = "HOW TO USE",
                        items = listOf(
                            "1. CREATE MODES \u2014 Select apps to block or allow",
                            "2. LINK NFC TAGS (optional) \u2014 Register physical tags as unlock keys",
                            "3. SET SCHEDULES \u2014 Auto-activate modes at specific times",
                            "4. TAP TO UNLOCK \u2014 Use NFC tags to disable blocking"
                        )
                    )
                }

                item {
                    InfoSection(
                        title = "FEATURES",
                        items = listOf(
                            "\u2022 BLOCK MODE \u2014 Block selected apps",
                            "\u2022 ALLOW MODE \u2014 Block everything except selected apps",
                            "\u2022 NFC LOCKS \u2014 Require specific tags to unlock modes",
                            "\u2022 SCHEDULES \u2014 Auto-activate modes by day/time",
                            "\u2022 PERSISTENT \u2014 Survives reboots and app restarts"
                        )
                    )
                }

                item {
                    InfoSection(
                        title = "TIPS",
                        items = listOf(
                            "\u2022 Keep NFC tags in hard-to-reach places",
                            "\u2022 Use schedules for work/sleep hours",
                            "\u2022 Combine modes for maximum protection",
                            "\u2022 Check Settings to disable 'Pause app if unused'"
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
                                "OPEN SOURCE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                "Guardian is free and open source software. Contributions welcome!",
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
                    "EVENT LOG ($entryCount)",
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
                        "No events logged yet.\nUse the app normally and logs will be collected automatically.",
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
                Text("SAVE FILE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
            }
        }
    )
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