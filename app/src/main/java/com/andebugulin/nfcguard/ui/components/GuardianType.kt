package com.andebugulin.nfcguard.ui.components

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's type scale, named.
 *
 * Every `fontSize` / `fontWeight` / `letterSpacing` in nfcGuard used to be an
 * inline literal at the call site — ~6 styles repeated across ~130 places. These
 * are those styles, extracted verbatim so adopting them is a no-op visually.
 *
 * Deliberately a flat object rather than a Material3 `Typography`, matching the
 * existing [com.andebugulin.nfcguard.ui.GuardianTheme] convention: the codebase
 * consumes design tokens directly, not through `MaterialTheme`. Wiring these into
 * `MaterialTheme.typography` would also silently resize every `Text` that relies
 * on the default `bodyLarge` — notably the mode-editor header.
 *
 * The all-caps + wide-tracking signature is the app's visual identity; keep it.
 */
object GuardianType {

    /** Screen headings — "MODES", "SCHEDULES", "ABOUT". */
    val ScreenTitle = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.sp
    )

    /** Card and dialog titles. */
    val Title = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )

    /** Running prose. The only style without wide tracking — prose needs to read. */
    val Body = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp
    )

    /** Button text and small all-caps labels. */
    val Label = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )

    /** Counts, timestamps, "3 CREATED" subtitles. */
    val Meta = TextStyle(
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )

    /** The "NO MODES" / "NO SCHEDULES" heading of an empty section. */
    val EmptyLabel = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp
    )

    /** The supporting line under an [EmptyLabel], and other quiet hints. */
    val EmptyHint = TextStyle(
        fontSize = 11.sp,
        letterSpacing = 0.5.sp
    )

    /** The safe-regime countdown, and the NFC unlock counter. */
    val Countdown = TextStyle(
        fontSize = 56.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 2.sp
    )
}
