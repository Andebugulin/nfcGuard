package com.andebugulin.nfcguard.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.andebugulin.nfcguard.R

/**
 * A remaining time as "2H 5M" or "5M", in the user's language. One formatter
 * for every screen, the widget and the notification, so the abbreviations
 * stay consistent across translations.
 */
fun formatDuration(context: Context, minutes: Long): String {
    val m = minutes.coerceAtLeast(0)
    return if (m >= 60) context.getString(R.string.common_duration_hm, m / 60, m % 60)
    else context.getString(R.string.common_duration_m, m)
}

@Composable
fun formatDuration(minutes: Long): String = formatDuration(LocalContext.current, minutes)
