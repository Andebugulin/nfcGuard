package com.andebugulin.nfcguard.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.andebugulin.nfcguard.BlockDecider
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.data.InstalledApps
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.components.GuardianDialog
import com.andebugulin.nfcguard.ui.components.GuardianType
import com.andebugulin.nfcguard.ui.modes.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ListedApp(val name: String, val icon: ImageBitmap?)

/**
 * The apps the active modes block right now, or under an allow mode the
 * only ones left usable. Opened by tapping the ACTIVE NOW panel on Home.
 */
@Composable
fun BlockedAppsDialog(rule: BlockDecider.Rule, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Names and icons come from PackageManager, which is too slow for the
    // main thread once a mode lists dozens of apps.
    val apps by produceState<List<ListedApp>?>(null, rule) {
        value = withContext(Dispatchers.IO) {
            rule.apps.map { pkg ->
                ListedApp(
                    name = InstalledApps.label(context, pkg),
                    icon = InstalledApps.icon(context, pkg)?.toBitmap(64, 64)?.asImageBitmap()
                )
            }.sortedBy { it.name.lowercase() }
        }
    }

    val allow = rule.blockMode == BlockMode.ALLOW_SELECTED
    GuardianDialog(
        title = if (allow) "ALLOWED APPS" else "BLOCKED APPS",
        message = when {
            allow && rule.apps.isEmpty() -> "Every app is blocked."
            allow -> "Everything else is blocked."
            rule.apps.isEmpty() -> "No apps are blocked."
            else -> null
        },
        confirmLabel = "CLOSE",
        onConfirm = onDismiss
    ) {
        val list = apps
        if (rule.apps.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (list == null) {
                    Text("Loading...", style = GuardianType.Meta, color = GuardianTheme.TextTertiary)
                } else {
                    list.forEach { app -> AppRow(app) }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: ListedApp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            app.icon?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(28.dp)) }
        }
        Spacer(Modifier.size(12.dp))
        Text(
            app.name,
            style = GuardianType.Body,
            color = GuardianTheme.TextPrimary,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}
