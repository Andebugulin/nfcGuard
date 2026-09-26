package com.andebugulin.nfcguard.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.andebugulin.nfcguard.ui.GuardianTheme

/**
 * What a dialog is *for*, which is also what colour its border is.
 *
 * The four `GuardianTheme.DialogBorder*` tokens already encoded this taxonomy —
 * every one of the app's dialogs picked one by hand, and the meaning lived only
 * in whichever colour the author happened to copy. Naming it makes the choice
 * explicit and keeps new dialogs consistent.
 */
enum class DialogKind {
    /** Creating, picking, explaining. The default. */
    Info,

    /** Changing something that already exists. */
    Edit,

    /** Proceeding has a consequence worth pausing over. */
    Warning,

    /** Destructive and hard to undo. */
    Delete
}

private val DialogKind.borderColor: Color
    get() = when (this) {
        DialogKind.Info -> GuardianTheme.DialogBorderInfo
        DialogKind.Edit -> GuardianTheme.DialogBorderEdit
        DialogKind.Warning -> GuardianTheme.DialogBorderWarning
        DialogKind.Delete -> GuardianTheme.DialogBorderDelete
    }

/**
 * The app's one dialog.
 *
 * Replaces 14 hand-styled `AlertDialog` call sites that each repeated the same
 * five arguments, generalising the private `StyledDialog` that
 * `PermissionOnboarding` had already converged on. Three details here are
 * load-bearing and were easy to forget per-site:
 *
 *  - `tonalElevation = 0.dp` — without it Material3 paints a grey tint over the
 *    container and the pure-black theme visibly breaks (see `GuardianTheme`).
 *  - `RoundedCornerShape(0.dp)` — nothing in this app is rounded.
 *  - the [kind] border, which is the only colour most dialogs carry.
 *
 * Copy discipline: [title] is 2-4 words, [message] is one line of at most ~12
 * words, and anything longer belongs in [detail], which hides behind an (i).
 * Pass [content] for interactive body content (pickers, fields, checkboxes).
 */
@Composable
fun GuardianDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    detail: String? = null,
    kind: DialogKind = DialogKind.Info,
    confirmEnabled: Boolean = true,
    confirmColor: Color = GuardianTheme.TextPrimary,
    confirmModifier: Modifier = Modifier,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    dismissModifier: Modifier = Modifier,
    dismissible: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    var detailExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        // An uncancellable dialog must still supply a no-op here; a dismissible
        // one falls back to its dismiss action, or its confirm if it has none.
        onDismissRequest = {
            if (dismissible) (onDismiss ?: onConfirm)()
        },
        title = {
            Text(title, style = GuardianType.Title, color = GuardianTheme.TextPrimary)
        },
        text = if (message == null && detail == null && content == null) null else {
            {
                Column(Modifier.fillMaxWidth()) {
                    if (message != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                message,
                                style = GuardianType.Body,
                                color = GuardianTheme.TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            if (detail != null) {
                                InfoDot(
                                    expanded = detailExpanded,
                                    onToggle = { detailExpanded = !detailExpanded }
                                )
                            }
                        }
                    }
                    if (detail != null) {
                        // With no message there is nothing to sit beside, so the
                        // dot goes on its own line rather than orphaning itself.
                        if (message == null) {
                            InfoDot(
                                expanded = detailExpanded,
                                onToggle = { detailExpanded = !detailExpanded }
                            )
                        }
                        AnimatedVisibility(
                            visible = detailExpanded,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Text(
                                detail,
                                style = GuardianType.Body,
                                color = GuardianTheme.TextSecondary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                    if (content != null) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = if (message != null || detail != null) 12.dp else 0.dp),
                            content = content
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled, modifier = confirmModifier) {
                Text(
                    confirmLabel,
                    style = GuardianType.Label,
                    color = if (confirmEnabled) confirmColor else GuardianTheme.ButtonDisabledText
                )
            }
        },
        dismissButton = dismissLabel?.let { label ->
            {
                TextButton(onClick = { (onDismiss ?: onConfirm)() }, modifier = dismissModifier) {
                    Text(label, style = GuardianType.Label, color = GuardianTheme.TextSecondary)
                }
            }
        },
        containerColor = GuardianTheme.BackgroundPrimary,
        textContentColor = GuardianTheme.TextPrimary,
        titleContentColor = GuardianTheme.TextPrimary,
        shape = RoundedCornerShape(0.dp),
        tonalElevation = 0.dp,
        modifier = modifier.border(GuardianTheme.DialogBorderWidth, kind.borderColor),
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible
        )
    )
}
