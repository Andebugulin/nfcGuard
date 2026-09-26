package com.andebugulin.nfcguard.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.andebugulin.nfcguard.ui.GuardianTheme

/**
 * A pick-one card: white when selected, near-black when not.
 *
 * Six of these were written by hand across the mode list and mode editor, and
 * every one paired its label with a subtitle explaining the option — which is
 * most of why those dialogs read as walls of small grey text. Here the label
 * stands alone and [detail] hides behind an (i), so the common case is two
 * words and the explanation is still one tap away.
 *
 * [content] renders inside the card only while it is selected, for options that
 * need to collect something further (a duration, a count).
 */
@Composable
fun SelectableOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    var detailExpanded by remember { mutableStateOf(false) }

    // The selected card flips to the app's light surface, so everything drawn
    // on it has to flip with it.
    val foreground = if (selected) Color.Black else Color.White
    val muted =
        if (selected) GuardianTheme.OnLightSurfaceSecondaryText else GuardianTheme.TextTertiary

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = if (selected) Color.White else GuardianTheme.SurfaceDim,
        enabled = enabled,
        onClick = onSelect
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = GuardianType.Title,
                    fontSize = GuardianType.Body.fontSize,
                    color = if (enabled) foreground else muted,
                    modifier = Modifier.weight(1f)
                )
                if (detail != null) {
                    InfoDot(
                        expanded = detailExpanded,
                        onToggle = { detailExpanded = !detailExpanded },
                        tint = muted,
                        activeTint = foreground
                    )
                }
            }
            if (detail != null) {
                AnimatedVisibility(
                    visible = detailExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Text(
                        detail,
                        style = GuardianType.EmptyHint,
                        color = muted,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            if (selected) content()
        }
    }
}
