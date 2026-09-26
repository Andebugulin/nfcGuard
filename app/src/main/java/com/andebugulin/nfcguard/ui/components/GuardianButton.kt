package com.andebugulin.nfcguard.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.dp
import com.andebugulin.nfcguard.ui.GuardianTheme

/** Emphasis levels, mapped to the existing button tokens. */
enum class ButtonKind {
    /** White on black. The one action the screen wants you to take. */
    Primary,

    /** Outlined. Present, but not competing with [Primary]. */
    Secondary,

    /** Red. Deletes something. */
    Destructive
}

/**
 * The app's button.
 *
 * `Button(colors = …, shape = RoundedCornerShape(0.dp), modifier = .height(48.dp))`
 * with an all-caps `Text` inside appeared at essentially every call site; the
 * square corners and the disabled-state colours were the parts most often
 * dropped by accident.
 */
@Composable
fun GuardianButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(0.dp)

    if (kind == ButtonKind.Secondary) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(48.dp),
            shape = shape,
            border = BorderStroke(1.dp, SolidColor(GuardianTheme.BorderUnfocused)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = GuardianTheme.TextPrimary,
                disabledContentColor = GuardianTheme.ButtonDisabledText
            )
        ) {
            Text(label, style = GuardianType.Label)
        }
        return
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = when (kind) {
                ButtonKind.Destructive -> GuardianTheme.Error
                else -> GuardianTheme.ButtonPrimary
            },
            contentColor = when (kind) {
                ButtonKind.Destructive -> GuardianTheme.TextPrimary
                else -> GuardianTheme.ButtonPrimaryText
            },
            disabledContainerColor = GuardianTheme.ButtonDisabledContainer,
            disabledContentColor = GuardianTheme.ButtonDisabledText
        )
    ) {
        Text(label, style = GuardianType.Label)
    }
}
