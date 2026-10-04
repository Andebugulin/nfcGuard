package com.andebugulin.nfcguard.ui

import com.andebugulin.nfcguard.R
import androidx.annotation.StringRes

/**
 * UI copy for domain results that more than one screen has to report.
 *
 * `ActivationResult` lives in `:domain` and deliberately carries no strings —
 * it says what happened, not how to phrase it. Both the modes list and the
 * schedules list can hit the same conflict, and they had drifted into holding
 * their own copies of the sentence.
 */
@StringRes
val BLOCK_MODE_CONFLICT_MESSAGE = R.string.common_cant_mix_block_and_allow
