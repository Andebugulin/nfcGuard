package com.andebugulin.nfcguard.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.components.GuardianType
import com.andebugulin.nfcguard.ui.safety.SafeRegimeChallengeBody
import kotlinx.coroutines.delay

/** Demo cadence. The real challenge waits 15s and gives 5s to answer. */
private const val DEMO_WAIT_SECONDS = 3
private const val DEMO_CHECK_SECONDS = 3

/**
 * The recovery challenge, tried out for real.
 *
 * Losing your tag with a mode active means sitting through a timed attention
 * challenge before blocking can be switched off. Meeting that for the first
 * time mid-crisis — a countdown you have never seen, at the worst possible
 * moment — was the worst part of the original flow.
 *
 * So this is not a picture of the challenge, it *is* the challenge:
 * [SafeRegimeChallengeBody], the same composable the real dialog renders, on a
 * faster clock. Ignore a prompt and it turns red and says CHALLENGE FAILED,
 * exactly as it would for real — which is the single most useful thing to
 * learn here, and the thing a static mock-up could never teach. It loops, so
 * the user can fail it on purpose and try again.
 *
 * Read-only: no duration stepper. Setting the length is a Settings job, and
 * asking the user to tune something they have not yet seen work was backwards.
 */
@Composable
fun SafetyPreview(
    seconds: Int,
    modifier: Modifier = Modifier
) {
    var totalLeft by remember { mutableIntStateOf(seconds) }
    var cycleLeft by remember { mutableIntStateOf(DEMO_WAIT_SECONDS) }
    var inCheckPhase by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var checksPassed by remember { mutableIntStateOf(0) }

    fun restart() {
        totalLeft = seconds
        cycleLeft = DEMO_WAIT_SECONDS
        inCheckPhase = false
        failed = false
        checksPassed = 0
    }

    // Mirrors the real dialog's tick, at one second per second. A missed check
    // fails the demo; the failure holds on screen, then the demo restarts.
    LaunchedEffect(failed, seconds) {
        if (failed) {
            delay(4000)
            restart()
            return@LaunchedEffect
        }
        while (totalLeft > 0) {
            delay(1000)
            totalLeft--
            cycleLeft--
            if (cycleLeft <= 0) {
                if (!inCheckPhase) {
                    inCheckPhase = true
                    cycleLeft = DEMO_CHECK_SECONDS
                } else {
                    failed = true
                    return@LaunchedEffect
                }
            }
        }
        restart()
    }

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SafeRegimeChallengeBody(
            actionDescription = "Turning blocking off without your tag. " +
                "Stay on this screen and tap whenever it asks.",
            totalSecondsLeft = totalLeft,
            totalDurationSeconds = seconds,
            cycleSecondsLeft = cycleLeft,
            inCheckPhase = inCheckPhase,
            failed = failed,
            checksPassed = checksPassed,
            onPress = {
                if (inCheckPhase) {
                    checksPassed++
                    inCheckPhase = false
                    cycleLeft = DEMO_WAIT_SECONDS
                }
            },
            onFailedAction = { restart() },
            onCancel = {},
            failedActionLabel = "TRY AGAIN",
            // Nothing to abandon here, so no bail-out button.
            cancelLabel = null,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            if (failed) {
                "That is what happens if you walk away. It restarts from the top."
            } else {
                "Try ignoring a prompt — see what happens."
            },
            style = GuardianType.EmptyHint,
            color = if (failed) GuardianTheme.ErrorText else GuardianTheme.TextTertiary,
            textAlign = TextAlign.Center
        )
    }
}
