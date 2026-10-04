package com.andebugulin.nfcguard.ui.home

import com.andebugulin.nfcguard.NfcTag
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.GuardianViewModel
import com.andebugulin.nfcguard.ui.TestTags

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.andebugulin.nfcguard.ui.components.GuardianType
import androidx.compose.foundation.layout.heightIn
import com.andebugulin.nfcguard.ui.components.SelectableOption
import com.andebugulin.nfcguard.ui.components.DialogKind
import com.andebugulin.nfcguard.ui.components.GuardianDialog

/** Plain-language summary of exactly what recovery is about to do. */
internal fun recoveryOutcome(activeModes: Int, lostTags: Int): String {
    val modes = when (activeModes) {
        0 -> "No modes are active"
        1 -> "Turns off 1 active mode"
        else -> "Turns off $activeModes active modes"
    }
    val tags = when (lostTags) {
        0 -> ""
        1 -> ", forgets 1 tag"
        else -> ", forgets $lostTags tags"
    }
    return "$modes$tags."
}

/** The confirm button says what it does, not "continue". */
internal fun recoveryConfirmLabel(activeModes: Int, lostTags: Int): String {
    val modes = if (activeModes > 0) "TURN OFF MODES" else "DONE"
    if (lostTags == 0) return modes
    return "$modes & FORGET $lostTags TAG" + if (lostTags == 1) "" else "S"
}

internal fun applyRecovery(
    viewModel: GuardianViewModel,
    activeModes: Set<String>,
    lostTags: Set<String>
) {
    activeModes.forEach { viewModel.deactivateMode(it) }
    lostTags.forEach { viewModel.deleteNfcTag(it) }
}

/**
 * Lost-tag recovery, in one screen.
 *
 * This used to be three steps in the wrong order: a warning dialog whose button
 * said "CONTINUE", *then* an attention challenge the user had never heard
 * of, and only *then* the question of which tags were lost. People sat through
 * the wait without knowing what they were waiting for, and the screen never
 * said plainly that their modes were about to switch off.
 *
 * Now the outcome is stated first, the tag list is optional and right there,
 * the button names its own effect, and the challenge — which exists to stop
 * this being a one-tap bypass — runs last, against a decision already made.
 * Onboarding introduces that challenge, so it is no longer a surprise.
 */
@Composable
fun RecoverAccessDialog(
    activeModeCount: Int,
    nfcTags: List<NfcTag>,
    selectedTags: Set<String>,
    onTagToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    GuardianDialog(
        title = "RECOVER ACCESS",
        message = recoveryOutcome(activeModeCount, selectedTags.size),
        detail = "Your modes, schedules and settings are kept. Only the tags you " +
            "tick are forgotten." + if (activeModeCount > 0) {
                " Because this switches blocking off, it runs behind the " +
                    "attention challenge."
            } else "",
        kind = DialogKind.Warning,
        confirmLabel = recoveryConfirmLabel(activeModeCount, selectedTags.size),
        onConfirm = onConfirm,
        confirmColor = GuardianTheme.ErrorTextEmphasized,
        confirmModifier = Modifier.testTag(TestTags.Emergency.TAG_SELECTION_CONFIRM),
        dismissLabel = "CANCEL",
        onDismiss = onDismiss,
        dismissModifier = Modifier.testTag(TestTags.Emergency.TAG_SELECTION_CANCEL)
    ) {
        if (nfcTags.isNotEmpty()) {
            Text(
                "LOST A TAG? TICK IT TO FORGET IT",
                style = GuardianType.Meta,
                color = GuardianTheme.TextTertiary
            )
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                nfcTags.forEach { tag ->
                    SelectableOption(
                        label = tag.name.uppercase(),
                        selected = tag.id in selectedTags,
                        onSelect = { onTagToggle(tag.id) },
                        modifier = Modifier.testTag(TestTags.Emergency.lostTag(tag.id))
                    )
                }
            }
        }
    }
}
