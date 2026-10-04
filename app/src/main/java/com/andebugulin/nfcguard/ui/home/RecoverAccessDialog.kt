package com.andebugulin.nfcguard.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.andebugulin.nfcguard.R
import androidx.compose.ui.res.stringResource
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
@Composable
internal fun recoveryOutcome(activeModes: Int, lostTags: Int): String {
    val modes = if (activeModes == 0) stringResource(R.string.home_no_modes_are_active)
    else pluralStringResource(R.plurals.home_recovery_turns_off_modes, activeModes, activeModes)
    if (lostTags == 0) return modes
    return modes + " " + pluralStringResource(R.plurals.home_recovery_forgets_tags, lostTags, lostTags)
}

/** The confirm button says what it does, not "continue". */
@Composable
internal fun recoveryConfirmLabel(activeModes: Int, lostTags: Int): String = when {
    lostTags == 0 && activeModes > 0 -> stringResource(R.string.home_turn_off_modes)
    lostTags == 0 -> stringResource(R.string.home_done)
    activeModes > 0 -> pluralStringResource(R.plurals.home_recovery_confirm_modes_and_tags, lostTags, lostTags)
    else -> pluralStringResource(R.plurals.home_recovery_confirm_tags, lostTags, lostTags)
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
        title = stringResource(R.string.home_recover_access_2),
        message = recoveryOutcome(activeModeCount, selectedTags.size),
        detail = stringResource(R.string.home_your_modes_schedules_and_settings) + if (activeModeCount > 0) {
                " " + stringResource(R.string.home_because_this_switches_blocking_off)
            } else "",
        kind = DialogKind.Warning,
        confirmLabel = recoveryConfirmLabel(activeModeCount, selectedTags.size),
        onConfirm = onConfirm,
        confirmColor = GuardianTheme.ErrorTextEmphasized,
        confirmModifier = Modifier.testTag(TestTags.Emergency.TAG_SELECTION_CONFIRM),
        dismissLabel = stringResource(R.string.home_cancel),
        onDismiss = onDismiss,
        dismissModifier = Modifier.testTag(TestTags.Emergency.TAG_SELECTION_CANCEL)
    ) {
        if (nfcTags.isNotEmpty()) {
            Text(
                stringResource(R.string.home_lost_a_tag_tick_it),
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
