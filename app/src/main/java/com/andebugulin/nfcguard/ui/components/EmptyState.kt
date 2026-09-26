package com.andebugulin.nfcguard.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andebugulin.nfcguard.ui.GuardianTheme

/**
 * What an empty section says for itself.
 *
 * With the first-run explainer dialogs gone, this is the only thing standing
 * between a new user and an empty screen — so it carries the instruction, and
 * its action is the thing to do next.
 *
 * [action] is a slot rather than a label-and-callback pair because the three
 * callers genuinely differ: one disables its button and swaps its label when no
 * modes exist yet, one puts an NFC glyph inside it, and each needs its own
 * `testTag`. Flattening those into parameters would add flags that each serve a
 * single caller; the shared part is the frame, so only the frame is shared.
 */
@Composable
fun EmptyState(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    secondary: String? = null,
    action: @Composable ColumnScope.() -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = GuardianTheme.IconDisabled,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(
                label,
                style = GuardianType.EmptyLabel,
                color = GuardianTheme.TextDisabled,
                textAlign = TextAlign.Center
            )
            if (secondary != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    secondary,
                    style = GuardianType.EmptyHint,
                    color = GuardianTheme.TextDisabled,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(if (secondary != null) 24.dp else 16.dp))
            action()
        }
    }
}
