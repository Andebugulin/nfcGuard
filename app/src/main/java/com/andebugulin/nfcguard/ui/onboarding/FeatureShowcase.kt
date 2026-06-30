package com.andebugulin.nfcguard.ui.onboarding

import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.Screen

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * One-time, minimalist "what is this?" popup shown the first time a brand-new
 * user opens Modes / Schedules / NFC Tags. Existing users (who already have
 * items configured, or have seen the popup) never see it — gated by
 * [isShowcaseSeen] plus an emptiness check at the call site.
 */

private const val PREFS = "guardian_prefs"

private fun showcaseKeyFor(screen: Screen): String? = when (screen) {
    Screen.MODES -> "showcase_modes"
    Screen.SCHEDULES -> "showcase_schedules"
    Screen.NFC_TAGS -> "showcase_nfc"
    else -> null
}

fun isShowcaseSeen(context: Context, screen: Screen): Boolean {
    val key = showcaseKeyFor(screen) ?: return true
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)
}

fun markShowcaseSeen(context: Context, screen: Screen) {
    val key = showcaseKeyFor(screen) ?: return
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(key, true).apply()
}

private data class ShowcaseContent(
    val title: String,
    val icon: ImageVector,
    val body: String
)

@Composable
fun FeatureShowcaseDialog(screen: Screen, onContinue: () -> Unit) {
    val content = when (screen) {
        Screen.MODES -> ShowcaseContent(
            "MODES",
            Icons.Default.Block,
            "A mode is a set of apps to block. Create one, choose BLOCK (block the apps you pick) " +
                "or ALLOW ONLY (block everything except the apps you pick), then activate it.\n\n" +
                "You can turn a mode on manually, on a schedule, or lock it behind an NFC tag."
        )
        Screen.SCHEDULES -> ShowcaseContent(
            "SCHEDULES",
            Icons.Default.Schedule,
            "Schedules turn your modes on and off automatically. Pick the days and times, link one " +
                "or more modes, and Guardian handles the rest — for example, work hours on weekdays " +
                "or a sleep schedule overnight."
        )
        Screen.NFC_TAGS -> ShowcaseContent(
            "NFC TAGS",
            Icons.Default.Nfc,
            "Register an NFC tag as a physical key, then link it to a mode. You'll need to tap that " +
                "tag to unlock the mode — real friction between you and a blocked app. Keep the tag " +
                "somewhere inconvenient for the most effect."
        )
        else -> return
    }

    // Subtle scale + fade entrance — clean, not flashy.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = tween(220),
        label = "showcaseScale"
    )
    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220),
        label = "showcaseAlpha"
    )

    Dialog(onDismissRequest = onContinue) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .alpha(fade)
                .border(
                    width = GuardianTheme.DialogBorderWidth,
                    color = GuardianTheme.DialogBorderInfo,
                    shape = RoundedCornerShape(0.dp)
                ),
            shape = RoundedCornerShape(0.dp),
            color = GuardianTheme.ButtonSecondary
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    content.icon,
                    contentDescription = null,
                    tint = GuardianTheme.IconPrimary,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    content.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = GuardianTheme.TextPrimary
                )
                Text(
                    content.body,
                    fontSize = 13.sp,
                    color = GuardianTheme.TextSecondary,
                    letterSpacing = 0.3.sp,
                    lineHeight = 19.sp
                )

                if (screen == Screen.NFC_TAGS) {
                    NfcShowcaseTips()
                }

                Button(
                    onClick = onContinue,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GuardianTheme.ButtonPrimary,
                        contentColor = GuardianTheme.ButtonPrimaryText
                    ),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("GOT IT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
private fun NfcShowcaseTips() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = GuardianTheme.BackgroundSurface
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TipRow(
                Icons.Default.Delete,
                "Lost a tag? Use Emergency Reset (the trash icon on the Home screen) to switch modes off and remove lost tags — no tag needed."
            )
            TipRow(
                Icons.Default.Shield,
                "Turn on Anti-Bypass Protection in Settings for an extra safety check before risky actions."
            )
            TipRow(
                Icons.Default.BugReport,
                "Found a bug? Report it from the Info (i) screen."
            )
        }
    }
}

@Composable
private fun TipRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = GuardianTheme.IconSecondary,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontSize = 10.sp,
            color = GuardianTheme.TextTertiary,
            letterSpacing = 0.3.sp,
            lineHeight = 14.sp
        )
    }
}
