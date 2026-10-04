package com.andebugulin.nfcguard.ui.onboarding

import com.andebugulin.nfcguard.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.andebugulin.nfcguard.data.Permissions
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.components.ButtonKind
import com.andebugulin.nfcguard.ui.components.DialogKind
import com.andebugulin.nfcguard.ui.components.GuardianButton
import com.andebugulin.nfcguard.ui.components.GuardianDialog
import com.andebugulin.nfcguard.ui.components.GuardianType
import com.andebugulin.nfcguard.ui.components.InfoDisclosure
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box

/**
 * Every permission on one page, in one list, reporting the truth.
 *
 * This replaces a chain of up to seven uncancellable dialogs. Two things were
 * wrong with that chain and both are fixed by the shape of this screen:
 *
 *  1. **It lied.** Tapping GRANT fired `startActivity` and advanced the queue in
 *     the same breath, so the flow moved on before the system screen had even
 *     appeared and never re-checked. It could mark setup complete with nothing
 *     granted. Here every row's state comes from [Permissions] and is re-read on
 *     `ON_RESUME`, so a row goes green when — and only when — the permission is
 *     genuinely held.
 *  2. **It led with the optional one.** Notifications were asked first and
 *     labelled "(OPTIONAL)", which reads as a contradiction. Required rows come
 *     first here; optional ones sit below a divider that says so.
 *
 * Nothing blocks: CONTINUE is always live, because a user who wants to look
 * around before granting anything should be allowed to.
 *
 * Settings shows this same composable rather than a second list of its own.
 * It used to keep a parallel copy — its own `canDrawOverlays` call, its own
 * `isIgnoringBatteryOptimizations` call, its own row widget — which is exactly
 * the drift [Permissions] exists to prevent. [scrollable] is the one thing the
 * two call sites genuinely disagree about: the onboarding page is the only
 * thing on screen and scrolls itself, while in the Settings dialog the
 * surrounding column already scrolls and a second scroller nested inside it
 * would be measured with infinite height.
 */
@Composable
fun PermissionsPage(modifier: Modifier = Modifier, scrollable: Boolean = true) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Bumping this re-runs every check below. Returning from a Settings screen
    // is the only moment any of them can change while this page is up.
    var probe by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) probe++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val usage = remember(probe) { Permissions.hasUsageStats(context) }
    val overlay = remember(probe) { Permissions.hasOverlay(context) }
    val battery = remember(probe) { Permissions.hasBatteryExemption(context) }
    val accessibility = remember(probe) { Permissions.hasAccessibility(context) }
    val notifications = remember(probe) { Permissions.hasNotifications(context) }
    val accessibilityRequired = remember { Permissions.accessibilityIsRequired() }
    // Null on stock Android, so the row below simply does not exist there.
    val autostart = remember { Permissions.autostartIntent(context) }
    // Null where the phone gives no way to read the switch (all but Xiaomi).
    val autostartGranted = remember(probe) { Permissions.hasAutostart(context) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { probe++ }

    var batteryAttempted by remember { mutableStateOf(false) }

    // Play's AccessibilityService policy requires a disclosure the user must
    // affirmatively accept or decline before the permission is requested —
    // tapping away must not count as consent. GRANT opens this dialog instead
    // of the system screen directly; only ALLOW launches it.
    var showAccessibilityConsent by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PermissionRow(
            icon = Icons.Default.Visibility,
            name = stringResource(R.string.onb_usage_access),
            why = stringResource(R.string.onb_see_which_app_is_open),
            detail = stringResource(R.string.onb_nfcguard_needs_to_know_which),
            granted = usage,
            onGrant = { context.launch(Permissions.usageAccessIntent()) }
        )
        PermissionRow(
            icon = Icons.Default.Fullscreen,
            name = stringResource(R.string.onb_display_over_apps),
            why = stringResource(R.string.onb_show_the_block_screen),
            detail = stringResource(R.string.onb_draws_the_blocker_on_top),
            granted = overlay,
            onGrant = { context.launch(Permissions.overlayIntent(context)) }
        )
        // Battery is the row that most often disagrees with the user. Several
        // OEMs (MIUI especially) keep their own power-saving switch alongside
        // Android's, and only Android's is visible to
        // `isIgnoringBatteryOptimizations` — so granting the OEM one leaves
        // this saying GRANT and looks broken. The action escalates instead of
        // repeating itself: the per-app dialog first, then the full list.
        PermissionRow(
            icon = Icons.Default.BatteryFull,
            name = stringResource(R.string.onb_battery),
            why = stringResource(R.string.onb_keep_running_in_the_background),
            detail = stringResource(R.string.onb_stops_android_shutting_nfcguard_down),
            granted = battery,
            grantLabel = if (batteryAttempted) stringResource(R.string.home_settings) else stringResource(R.string.onb_grant),
            onGrant = {
                val intent = if (batteryAttempted) {
                    Permissions.batteryFallbackIntent()
                } else {
                    Permissions.batteryIntent(context)
                }
                if (!context.launch(intent)) context.launch(Permissions.batteryFallbackIntent())
                batteryAttempted = true
            }
        )
        PermissionRow(
            icon = Icons.Default.Accessibility,
            name = stringResource(R.string.onb_accessibility),
            why = if (accessibilityRequired) {
                stringResource(R.string.onb_required_on_this_device)
            } else {
                stringResource(R.string.onb_faster_more_reliable_blocking)
            },
            detail = if (accessibilityRequired) {
                stringResource(R.string.onb_your_device_has_a_detection)
            } else {
                stringResource(R.string.onb_lets_nfcguard_close_a_blocked)
            },
            granted = accessibility,
            onGrant = { showAccessibilityConsent = true }
        )

        // Required where it exists: without it these phones kill nfcGuard
        // and drop its alarms, so schedules start late or not at all. Only
        // Xiaomi lets us read the switch; elsewhere the row stays at OPEN.
        if (autostart != null) {
            PermissionRow(
                icon = Icons.Default.RestartAlt,
                name = stringResource(R.string.onb_autostart),
                why = stringResource(R.string.onb_keep_schedules_on_time),
                detail = stringResource(R.string.onb_your_phone_keeps_a_list) +
                    if (autostartGranted == null) {
                        "\n\n" + stringResource(R.string.onb_your_phone_gives_no_way)
                    } else "",
                granted = autostartGranted == true,
                grantLabel = stringResource(R.string.onb_open),
                onGrant = { context.launch(autostart) }
            )
        }

        SectionDivider(stringResource(R.string.modes_optional))

        PermissionRow(
            icon = Icons.Default.Notifications,
            name = stringResource(R.string.onb_notifications),
            why = stringResource(R.string.onb_show_which_modes_are_on),
            detail = stringResource(R.string.onb_blocking_works_fine_without_this),
            granted = notifications,
            onGrant = {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        )

        // Not a permission Android will tell us about, so it can never show
        // GRANTED. A heading that says so is the honest alternative to a row
        // that looks permanently un-granted.
        SectionDivider(stringResource(R.string.onb_system_settings))

        PermissionRow(
            icon = Icons.Default.OpenInNew,
            name = stringResource(R.string.onb_pause_if_unused),
            why = stringResource(R.string.onb_turn_it_off_in_app),
            detail = stringResource(R.string.onb_android_hibernates_apps_it_thinks),
            // Not a permission — nothing to query, so it never reports granted.
            granted = false,
            grantLabel = stringResource(R.string.onb_open),
            onGrant = { context.launch(Permissions.appDetailsIntent(context)) }
        )
    }

    if (showAccessibilityConsent) {
        GuardianDialog(
            title = stringResource(R.string.onb_accessibility_access),
            message = stringResource(R.string.onb_nfcguard_uses_androids_accessibility_api),
            kind = DialogKind.Warning,
            confirmLabel = stringResource(R.string.onb_allow),
            onConfirm = {
                showAccessibilityConsent = false
                context.launch(Permissions.accessibilityIntent())
            },
            dismissLabel = stringResource(R.string.onb_dont_allow),
            // Tapping away or pressing back must decline, never grant —
            // onDismiss is explicit here so GuardianDialog's onDismissRequest
            // never falls back to onConfirm.
            onDismiss = { showAccessibilityConsent = false }
        )
    }
}

/** Starts [intent], reporting whether any activity was there to handle it. */
private fun Context.launch(intent: Intent): Boolean =
    runCatching { startActivity(intent) }.isSuccess

/** Every row's trailing status occupies exactly this box, granted or not. */
private val STATUS_WIDTH = 92.dp
private val STATUS_HEIGHT = 36.dp

@Composable
private fun PermissionRow(
    icon: ImageVector,
    name: String,
    why: String,
    detail: String,
    granted: Boolean,
    onGrant: () -> Unit,
    grantLabel: String = stringResource(R.string.onb_grant)
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = GuardianTheme.BackgroundSurface
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (granted) GuardianTheme.Success else GuardianTheme.IconSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.size(12.dp))

            InfoDisclosure(detail = detail, modifier = Modifier.weight(1f)) {
                Text(name, style = GuardianType.Label, color = GuardianTheme.TextPrimary)
                Text(why, style = GuardianType.Meta, color = GuardianTheme.TextTertiary)
            }

            Spacer(Modifier.size(8.dp))
            // Fixed box for the status, whichever state the row is in. The
            // GRANTED tick and the GRANT button are different heights, so
            // without this every row sat at a slightly different height and
            // the (i) icons stopped lining up down the column.
            Box(
                modifier = Modifier.size(width = STATUS_WIDTH, height = STATUS_HEIGHT),
                contentAlignment = Alignment.Center
            ) {
                if (granted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.onb_granted),
                            tint = GuardianTheme.Success,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.size(5.dp))
                        Text(stringResource(R.string.onb_granted_2), style = GuardianType.Meta, color = GuardianTheme.Success)
                    }
                } else {
                    GuardianButton(
                        label = grantLabel,
                        onClick = onGrant,
                        kind = ButtonKind.Secondary,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionDivider(label: String) {
    Text(
        label,
        style = GuardianType.Meta,
        color = GuardianTheme.TextDisabled,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}
