package com.andebugulin.nfcguard.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
 * The (i) affordance that makes text removal safe.
 *
 * nfcGuard's screens were unreadable because every explanation was inlined as a
 * paragraph nobody read. The fix is not to delete the explanations but to move
 * them one tap away: a surface shows an icon and a few words, and the detail
 * expands in place for the rare user who wants it.
 *
 * [InfoDot] is the bare icon (pair it with your own expansion state when the
 * detail belongs somewhere other than directly below). [InfoDisclosure] is the
 * common case: a row of content with the dot on the right and the detail
 * expanding underneath it.
 */
@Composable
fun InfoDot(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "More information",
    tint: Color = GuardianTheme.IconSecondary,
    activeTint: Color = GuardianTheme.IconPrimary
) {
    IconButton(onClick = onToggle, modifier = modifier.size(28.dp)) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = contentDescription,
            // Callers that draw on the light surface pass the inverted pair.
            tint = if (expanded) activeTint else tint,
            modifier = Modifier.size(15.dp)
        )
    }
}

/**
 * [content] with an [InfoDot] trailing it, and [detail] expanding below on tap.
 *
 * Collapsed, this costs one 15dp icon of screen space. That is the whole point.
 */
@Composable
fun InfoDisclosure(
    detail: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { content() }
            InfoDot(expanded = expanded, onToggle = { expanded = !expanded })
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Text(
                detail,
                style = GuardianType.Body,
                color = GuardianTheme.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, end = 28.dp)
            )
        }
    }
}
