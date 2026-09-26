package com.andebugulin.nfcguard.ui

/**
 * UI copy for domain results that more than one screen has to report.
 *
 * `ActivationResult` lives in `:domain` and deliberately carries no strings —
 * it says what happened, not how to phrase it. Both the modes list and the
 * schedules list can hit the same conflict, and they had drifted into holding
 * their own copies of the sentence.
 */
const val BLOCK_MODE_CONFLICT_MESSAGE =
    "Can't mix BLOCK and ALLOW ONLY. Turn off the active mode first."
