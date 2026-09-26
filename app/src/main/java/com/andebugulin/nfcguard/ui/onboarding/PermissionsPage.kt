package com.andebugulin.nfcguard.ui.onboarding

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
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
import com.andebugulin.nfcguard.ui.components.GuardianButton
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
 */
@Composable
fun PermissionsPage(modifier: Modifier = Modifier) {
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

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { probe++ }

    var batteryAttempted by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PermissionRow(
            icon = Icons.Default.Visibility,
            name = "USAGE ACCESS",
            why = "See which app is open.",
            detail = "nfcGuard needs to know which app is in the foreground to " +
                "block the right one. It cannot see anything inside your apps.",
            granted = usage,
            onGrant = { context.launch(Permissions.usageAccessIntent()) }
        )
        PermissionRow(
            icon = Icons.Default.Fullscreen,
            name = "DISPLAY OVER APPS",
            why = "Show the block screen.",
            detail = "Draws the blocker on top of an app you chose to block.",
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
            name = "BATTERY",
            why = "Keep running in the background.",
            detail = "Stops Android shutting nfcGuard down, so blocking keeps " +
                "working hours later.\n\nStill says GRANT after you allowed it? " +
                "Some phones (Xiaomi, Samsung) have a second power-saving " +
                "setting of their own that Android does not report. Tap again " +
                "for the full battery list, set nfcGuard to Unrestricted, then " +
                "use CHECK AGAIN below.",
            granted = battery,
            grantLabel = if (batteryAttempted) "SETTINGS" else "GRANT",
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
            name = "ACCESSIBILITY",
            why = if (accessibilityRequired) {
                "Required on this device."
            } else {
                "Faster, more reliable blocking."
            },
            detail = if (accessibilityRequired) {
                "Your device has a detection bug that makes blocking unreliable " +
                    "without this. nfcGuard only reads which app is in front — " +
                    "never screen content."
            } else {
                "Lets nfcGuard close a blocked app instantly instead of covering " +
                    "it. nfcGuard only reads which app is in front — never screen " +
                    "content."
            },
            granted = accessibility,
            onGrant = { context.launch(Permissions.accessibilityIntent()) }
        )

        SectionDivider("OPTIONAL")

        PermissionRow(
            icon = Icons.Default.Notifications,
            name = "NOTIFICATIONS",
            why = "Show which modes are on.",
            detail = "Blocking works fine without this.",
            granted = notifications,
            onGrant = {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        )
        PermissionRow(
            icon = Icons.Default.OpenInNew,
            name = "PAUSE IF UNUSED",
            why = "Turn it off in app settings.",
            detail = "Android hibernates apps it thinks are idle. nfcGuard cannot " +
                "read or change this setting itself, so the button opens the page " +
                "where you can.",
            // Not a permission — nothing to query, so it never reports granted.
            granted = false,
            grantLabel = "OPEN",
            onGrant = { context.launch(Permissions.appDetailsIntent(context)) }
        )

        // Returning from Settings re-probes automatically, but some OEMs apply
        // the change a beat after handing focus back. This is the manual retry
        // so a stale row is never the end of the road.
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            GuardianButton(
                label = "CHECK AGAIN",
                kind = ButtonKind.Secondary,
                onClick = { probe++ },
                modifier = Modifier.height(36.dp)
            )
        }
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
    grantLabel: String = "GRANT"
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
                            contentDescription = "Granted",
                            tint = GuardianTheme.Success,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.size(5.dp))
                        Text("GRANTED", style = GuardianType.Meta, color = GuardianTheme.Success)
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
