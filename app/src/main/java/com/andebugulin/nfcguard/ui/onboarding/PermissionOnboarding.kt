package com.andebugulin.nfcguard.ui.onboarding

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.andebugulin.nfcguard.service.ForegroundDetectorService
import com.andebugulin.nfcguard.ui.GuardianTheme
import kotlinx.coroutines.delay

/**
 * Compose-native onboarding state machine for first-run permission setup.
 *
 * Replaces ~310 lines of `AlertDialog.Builder` + `window.setBackgroundDrawable`
 * styling that used to live in `MainActivity`. State transitions are linear:
 *
 *   Welcome → Grant(req, queue) … → PauseAppReminder → AccessibilityRec → Done
 *
 * Skipping any step still advances the flow (and skipping Welcome marks
 * setup complete so we don't nag again on next launch).
 */

private const val PREFS = "guardian_prefs"
private const val KEY_INITIAL_PERMISSIONS_GRANTED = "initial_permissions_granted"
private const val KEY_ACCESSIBILITY_REC_SHOWN = "accessibility_recommendation_shown"

/**
 * Decide whether the onboarding flow should run. Mirrors the gate that
 * used to live in `MainActivity.checkAndRequestPermissions`.
 */
fun shouldShowOnboarding(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val hasSeenOnboarding = prefs.getBoolean("has_seen_onboarding", false)
    val hasCompletedInitialSetup = prefs.getBoolean(KEY_INITIAL_PERMISSIONS_GRANTED, false)
    return hasSeenOnboarding && !hasCompletedInitialSetup
}

private data class PermissionRequest(
    val title: String,
    val description: String,
    val intent: Intent
)

private sealed interface OnboardingStep {
    data object Welcome : OnboardingStep
    /** Android 13+ runtime notification prompt, shown with an explanation first. */
    data object NotificationPermission : OnboardingStep
    data class GrantPermission(
        val current: PermissionRequest,
        val queue: List<PermissionRequest>
    ) : OnboardingStep
    data object PauseAppReminder : OnboardingStep
    data class AccessibilityRec(
        val title: String,
        val message: String,
        val positiveLabel: String
    ) : OnboardingStep
}

@Composable
fun PermissionOnboarding(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf<OnboardingStep?>(OnboardingStep.Welcome) }

    // POST_NOTIFICATIONS is a runtime permission (not a Settings intent), so we
    // request it through an Activity-result launcher after explaining why.
    // Whatever the user chooses, we advance into the rest of the flow.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { step = afterNotification(context) }

    when (val s = step) {
        null -> {
            // Flow finished — signal the host to drop us from composition.
            onDone()
        }
        OnboardingStep.Welcome -> WelcomeDialog(
            onContinue = {
                step = nextAfterWelcome(context)
                if (step == null) {
                    markInitialPermissionsGranted(context)
                }
            },
            onSkip = {
                markInitialPermissionsGranted(context)
                step = null
            }
        )
        OnboardingStep.NotificationPermission -> NotificationPermissionDialog(
            onAllow = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onSkip = { step = afterNotification(context) }
        )
        is OnboardingStep.GrantPermission -> GrantPermissionDialog(
            request = s.current,
            onGrant = {
                runCatching { context.startActivity(s.current.intent) }
                    .onFailure {
                        // Battery optimization fallback (some devices don't honor
                        // the per-app intent and need the system-wide screen).
                        if (s.current.title == "Battery Optimization") {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                    }
                step = advancePermissionQueue(s.queue, context)
            },
            onSkip = {
                step = advancePermissionQueue(s.queue, context)
            }
        )
        OnboardingStep.PauseAppReminder -> PauseAppReminderDialog(
            onOpenSettings = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}"))
                    )
                }
                markInitialPermissionsGranted(context)
                step = nextAfterPauseReminder(context)
            },
            onOk = {
                markInitialPermissionsGranted(context)
                step = nextAfterPauseReminder(context)
            }
        )
        is OnboardingStep.AccessibilityRec -> AccessibilityRecDialog(
            title = s.title,
            message = s.message,
            positiveLabel = s.positiveLabel,
            onConfirm = {
                markAccessibilityRecShown(context)
                runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                step = null
            },
            onSkip = {
                markAccessibilityRecShown(context)
                step = null
            }
        )
    }
}

// ─── State transitions ─────────────────────────────────────────────────────

private fun nextAfterWelcome(context: Context): OnboardingStep? {
    if (needsNotificationPermission(context)) return OnboardingStep.NotificationPermission
    return afterNotification(context)
}

/** Continue past the (optional) notification step into the remaining permissions. */
private fun afterNotification(context: Context): OnboardingStep? {
    val needed = computeNeededPermissions(context)
    return when {
        needed.isNotEmpty() -> OnboardingStep.GrantPermission(needed.first(), needed.drop(1))
        else -> nextAfterPermissionsExhausted(context)
    }
}

private fun needsNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.POST_NOTIFICATIONS
    ) != PackageManager.PERMISSION_GRANTED
}

private fun advancePermissionQueue(queue: List<PermissionRequest>, context: Context): OnboardingStep? {
    return if (queue.isNotEmpty()) {
        OnboardingStep.GrantPermission(queue.first(), queue.drop(1))
    } else {
        nextAfterPermissionsExhausted(context)
    }
}

private fun nextAfterPermissionsExhausted(@Suppress("UNUSED_PARAMETER") context: Context): OnboardingStep =
    OnboardingStep.PauseAppReminder

private fun nextAfterPauseReminder(context: Context): OnboardingStep? {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_ACCESSIBILITY_REC_SHOWN, false)) return null
    if (ForegroundDetectorService.isEnabled(context)) return null

    val manufacturer = Build.MANUFACTURER
    val isRequired = manufacturer.equals("Google", ignoreCase = true) ||
        manufacturer.equals("Samsung", ignoreCase = true)
    val title = when {
        manufacturer.equals("Google", ignoreCase = true) -> "PIXEL DEVICE DETECTED"
        manufacturer.equals("Samsung", ignoreCase = true) -> "SAMSUNG DEVICE DETECTED"
        else -> "IMPROVE RELIABILITY"
    }
    val message = if (isRequired) {
        "Your device has a known issue where app detection can fail during certain transitions.\n\n" +
            "To ensure Guardian blocks apps reliably, please enable the Accessibility Service permission.\n\n" +
            "Guardian only reads which app is in the foreground — it does NOT read any screen content or personal data."
    } else {
        "Enabling the Accessibility Service makes app detection faster and more reliable.\n\n" +
            "This is optional but recommended for the best experience.\n\n" +
            "Guardian only reads which app is in the foreground — it does NOT read any screen content or personal data."
    }
    return OnboardingStep.AccessibilityRec(
        title = title,
        message = message,
        positiveLabel = if (isRequired) "OPEN SETTINGS" else "ENABLE"
    )
}

private fun markInitialPermissionsGranted(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_INITIAL_PERMISSIONS_GRANTED, true).apply()
}

private fun markAccessibilityRecShown(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_ACCESSIBILITY_REC_SHOWN, true).apply()
}

// ─── Permission detection ──────────────────────────────────────────────────

private fun computeNeededPermissions(context: Context): List<PermissionRequest> {
    val needed = mutableListOf<PermissionRequest>()

    // Usage Access — heuristic: queryUsageStats returns empty if permission missing
    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
        as android.app.usage.UsageStatsManager
    val granted = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }
    if (!granted) {
        needed += PermissionRequest(
            "Usage Access",
            "Lets Guardian see which app is currently open so it can block the right ones. It can't see anything inside your apps.",
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        )
    }

    // Display Over Apps
    if (!Settings.canDrawOverlays(context)) {
        needed += PermissionRequest(
            "Display Over Apps",
            "Lets Guardian show the block screen on top of an app you've chosen to block.",
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    // Battery Optimization
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
        needed += PermissionRequest(
            "Battery Optimization",
            "Stops Android from shutting Guardian down in the background, so blocking keeps working even after a while.",
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }

    return needed
}

// ─── Dialogs ───────────────────────────────────────────────────────────────

@Composable
private fun WelcomeDialog(
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    StyledDialog(
        title = "WELCOME TO GUARDIAN",
        message = "To protect your focus, Guardian needs a few permissions. " +
            "We'll go through them one at a time and explain each:\n\n" +
            "• Notifications (optional) — show which modes are active\n" +
            "• Usage access — see which app is open\n" +
            "• Display over apps — show the block screen\n" +
            "• Battery optimization — keep running reliably\n" +
            "• Pause app activity — must be turned off for Guardian\n" +
            "• Accessibility — more reliable, instant blocking\n\n" +
            "Let's set these up now.",
        confirmLabel = "CONTINUE",
        onConfirm = onContinue,
        dismissLabel = "SKIP",
        onDismiss = onSkip
    )
}

@Composable
private fun NotificationPermissionDialog(
    onAllow: () -> Unit,
    onSkip: () -> Unit
) {
    StyledDialog(
        title = "NOTIFICATIONS (OPTIONAL)",
        message = "Guardian can show a quiet notification with which modes are " +
            "active and when a temporary unlock ends.\n\n" +
            "This is optional — blocking works fine without it, and you can " +
            "change it anytime in system settings.",
        confirmLabel = "ALLOW",
        onConfirm = onAllow,
        dismissLabel = "SKIP",
        onDismiss = onSkip
    )
}

@Composable
private fun GrantPermissionDialog(
    request: PermissionRequest,
    onGrant: () -> Unit,
    onSkip: () -> Unit
) {
    // The system Settings activity opens on top of us when the user taps GRANT.
    // The original code used a 500ms postDelayed before showing the next dialog;
    // with Compose we just set state immediately and Compose handles the
    // re-render when the user returns. No artificial delay needed.
    StyledDialog(
        title = request.title.uppercase(),
        message = request.description,
        confirmLabel = "GRANT",
        onConfirm = onGrant,
        dismissLabel = "SKIP",
        onDismiss = onSkip
    )
}

@Composable
private fun PauseAppReminderDialog(
    onOpenSettings: () -> Unit,
    onOk: () -> Unit
) {
    StyledDialog(
        title = "IMPORTANT: DISABLE 'PAUSE APP IF UNUSED'",
        message = "To ensure Guardian works reliably:\n\n" +
            "1. Go to Settings → Apps → Guardian\n" +
            "2. Find 'Pause app activity if unused'\n" +
            "3. Turn it OFF\n\n" +
            "This prevents Android from pausing Guardian in the background.",
        confirmLabel = "OPEN APP SETTINGS",
        onConfirm = onOpenSettings,
        dismissLabel = "OK",
        onDismiss = onOk
    )
}

@Composable
private fun AccessibilityRecDialog(
    title: String,
    message: String,
    positiveLabel: String,
    onConfirm: () -> Unit,
    onSkip: () -> Unit
) {
    StyledDialog(
        title = title,
        message = message,
        confirmLabel = positiveLabel,
        onConfirm = onConfirm,
        dismissLabel = "SKIP",
        onDismiss = onSkip
    )
}

@Composable
private fun StyledDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* uncancellable — user must pick a button */ },
        title = {
            Text(
                title,
                color = GuardianTheme.TextPrimary,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        },
        text = {
            Text(message, color = GuardianTheme.TextPrimary)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = GuardianTheme.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        },
        dismissButton = dismissLabel?.let {
            {
                TextButton(onClick = onDismiss) {
                    Text(it, color = GuardianTheme.TextSecondary, letterSpacing = 1.sp)
                }
            }
        },
        containerColor = GuardianTheme.BackgroundPrimary,
        textContentColor = GuardianTheme.TextPrimary,
        titleContentColor = GuardianTheme.TextPrimary,
        shape = RoundedCornerShape(0.dp),
        tonalElevation = 0.dp,
        modifier = Modifier.border(GuardianTheme.DialogBorderWidth, GuardianTheme.DialogBorderWarning),
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    )
}
