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
import androidx.compose.foundation.clickable


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var githubStars by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }

    // Fetch GitHub stars
    androidx.compose.runtime.LaunchedEffect(Unit) {
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

    Box(modifier = Modifier.fillMaxSize().background(GuardianTheme.BackgroundPrimary)) {
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
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Andebugulin/nfcGuard"))
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
                        // GitHub Icon
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

                        // Stars Counter
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

            item {
                InfoSection(
                    title = "WHAT IS GUARDIAN?",
                    content = "Guardian helps you maintain focus by blocking distracting apps. Use NFC tags as physical keys to unlock – making it harder to mindlessly open blocked apps."
                )
            }

            item {
                InfoSection(
                    title = "HOW TO USE",
                    items = listOf(
                        "1. CREATE MODES – Select apps to block or allow",
                        "2. LINK NFC TAGS (optional) – Register physical tags as unlock keys",
                        "3. SET SCHEDULES – Auto-activate modes at specific times",
                        "4. TAP TO UNLOCK – Use NFC tags to disable blocking"
                    )
                )
            }

            item {
                InfoSection(
                    title = "FEATURES",
                    items = listOf(
                        "• BLOCK MODE – Block selected apps",
                        "• ALLOW MODE – Block everything except selected apps",
                        "• NFC LOCKS – Require specific tags to unlock modes",
                        "• SCHEDULES – Auto-activate modes by day/time",
                        "• PERSISTENT – Survives reboots and app restarts"
                    )
                )
            }

            item {
                InfoSection(
                    title = "TIPS",
                    items = listOf(
                        "• Keep NFC tags in hard-to-reach places",
                        "• Use schedules for work/sleep hours",
                        "• Combine modes for maximum protection",
                        "• Check Settings to disable 'Pause app if unused'"
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

        // Font Awesome GitHub icon SVG path
        val path = androidx.compose.ui.graphics.Path().apply {
            // Main GitHub body shape
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