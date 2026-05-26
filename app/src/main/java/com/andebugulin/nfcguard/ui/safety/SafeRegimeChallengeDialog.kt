package com.andebugulin.nfcguard.ui.safety

import com.andebugulin.nfcguard.ui.GuardianTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Safe Regime Challenge Dialog.
 *
 * Requires the user to remain attentive for 1.5 minutes before allowing
 * a potentially bypass-enabling action. Every 15 seconds a button appears
 * that the user must press within 5 seconds, otherwise the challenge
 * fails and the action is cancelled.
 *
 * Design rationale: an impulsive user (e.g. during an addiction craving)
 * is unlikely to stay focused for 1.5 solid minutes, making it very hard
 * to bypass the blocker in a moment of weakness.
 */
@Composable
fun SafeRegimeChallengeDialog(
    actionDescription: String,
    onComplete: () -> Unit,
    onCancel: () -> Unit
) {
    val totalDurationSeconds = 90 // 1.5 minutes
    val waitPhaseSeconds = 15     // seconds of passive waiting per cycle
    val checkPhaseSeconds = 5     // seconds the user has to press

    var totalSecondsLeft by remember { mutableIntStateOf(totalDurationSeconds) }
    var cycleSecondsLeft by remember { mutableIntStateOf(waitPhaseSeconds) }
    var inCheckPhase by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var checksCompleted by remember { mutableIntStateOf(0) }

    val totalChecks = totalDurationSeconds / (waitPhaseSeconds + checkPhaseSeconds) // 20

    // Main timer tick — 1 second resolution
    LaunchedEffect(failed, completed) {
        if (failed || completed) return@LaunchedEffect
        while (totalSecondsLeft > 0) {
            delay(1000)
            totalSecondsLeft--
            cycleSecondsLeft--

            if (cycleSecondsLeft <= 0) {
                if (!inCheckPhase) {
                    // Wait phase ended → start attention check
                    inCheckPhase = true
                    cycleSecondsLeft = checkPhaseSeconds
                } else {
                    // Check phase expired without press → FAIL
                    failed = true
                    return@LaunchedEffect
                }
            }
        }
        // 5 minutes passed with all checks — success
        completed = true
        onComplete()
    }

    Dialog(
        onDismissRequest = { /* prevent accidental dismiss */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .border(
                    width = GuardianTheme.DialogBorderWidth,
                    color = if (failed) GuardianTheme.Error else if (inCheckPhase) GuardianTheme.WarningAccent else GuardianTheme.DialogBorderWarning,
                    shape = RoundedCornerShape(0.dp)
                ),
            shape = RoundedCornerShape(0.dp),
            color = GuardianTheme.ButtonSecondary
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = GuardianTheme.Warning,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        "SAFE REGIME",
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        fontSize = 18.sp,
                        color = GuardianTheme.TextPrimary
                    )
                }

                // Action description
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(0.dp),
                    color = GuardianTheme.WarningBackground
                ) {
                    Text(
                        actionDescription,
                        fontSize = 11.sp,
                        color = GuardianTheme.Warning,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(12.dp),
                        textAlign = TextAlign.Center
                    )
                }

                if (failed) {
                    // FAILED STATE
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.ErrorDark
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                tint = GuardianTheme.ErrorText,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                "CHALLENGE FAILED",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = GuardianTheme.ErrorText,
                                letterSpacing = 2.sp
                            )
                            Text(
                                "You didn't press in time. Action cancelled.",
                                fontSize = 11.sp,
                                color = GuardianTheme.ErrorText,
                                letterSpacing = 0.5.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Button(
                        onClick = onCancel,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GuardianTheme.BackgroundSurface,
                            contentColor = GuardianTheme.TextPrimary
                        ),
                        shape = RoundedCornerShape(0.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("CLOSE", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                } else {
                    // ACTIVE CHALLENGE STATE

                    // Big countdown
                    val minutes = totalSecondsLeft / 60
                    val seconds = totalSecondsLeft % 60
                    Text(
                        String.format("%d:%02d", minutes, seconds),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Black,
                        color = if (inCheckPhase) GuardianTheme.WarningAccent else GuardianTheme.TextPrimary
                    )

                    // Progress
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        color = GuardianTheme.BackgroundSurface
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = {
                                    1f - (totalSecondsLeft.toFloat() / totalDurationSeconds)
                                },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = Color.White,
                                trackColor = GuardianTheme.ButtonDisabledContainer
                            )
                            Text(
                                "CHECK $checksCompleted / $totalChecks",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = GuardianTheme.TextSecondary,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    // Check phase or wait phase
                    if (inCheckPhase) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(0.dp),
                            color = Color(0xFF332200)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "PRESS NOW — ${cycleSecondsLeft}s",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    color = GuardianTheme.WarningAccent,
                                    letterSpacing = 2.sp
                                )

                                Button(
                                    onClick = {
                                        if (inCheckPhase) {
                                            checksCompleted++
                                            inCheckPhase = false
                                            cycleSecondsLeft = waitPhaseSeconds
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GuardianTheme.WarningAccent,
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(0.dp),
                                    modifier = Modifier.fillMaxWidth().height(56.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.TouchApp, null, modifier = Modifier.size(24.dp))
                                        Text(
                                            "I'M HERE",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Black,
                                            letterSpacing = 2.sp
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(0.dp),
                            color = GuardianTheme.BackgroundSurface
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "WAITING...",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = GuardianTheme.TextSecondary,
                                    letterSpacing = 2.sp
                                )
                                Text(
                                    "Next check in ${cycleSecondsLeft}s",
                                    fontSize = 10.sp,
                                    color = GuardianTheme.TextTertiary,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }

                    // Cancel button
                    TextButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = GuardianTheme.TextSecondary
                        )
                    ) {
                        Text("GIVE UP", letterSpacing = 1.sp, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
