package com.andebugulin.nfcguard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Guardian App Theme - Centralized color definitions
 * Pure black & white minimalist design
 *
 * IMPORTANT: For pure black dialogs, always add:
 * tonalElevation = 0.dp
 * to AlertDialog to disable Material Design's gray tint overlay
 */
object GuardianTheme {

    // ============ BACKGROUNDS ============
    val BackgroundPrimary = Color(0xFF000000)      // Pure black - main background
    val BackgroundSurface = Color(0xFF000000)      // Pure black - cards, surfaces (was 0xFF0A0A0A)
    val BackgroundSurfaceVariant = Color(0xFF000000) // Pure black - alternative surface

    // ============ TEXT COLORS ============
    val TextPrimary = Color(0xFFFFFFFF)           // White - main text
    val TextSecondary = Color(0xFF808080)         // Gray - secondary text, subtle info
    val TextTertiary = Color(0xFF606060)          // Darker gray - very subtle text
    val TextDisabled = Color(0xFF404040)          // Very dark gray - disabled text

    // ============ INTERACTIVE ELEMENTS ============
    val ButtonPrimary = Color(0xFFFFFFFF)         // White - primary buttons
    val ButtonPrimaryText = Color(0xFF000000)     // Black - text on primary buttons
    val ButtonSecondary = Color(0xFF000000)       // Black - secondary buttons
    val ButtonSecondaryText = Color(0xFFFFFFFF)   // White - text on secondary buttons

    // ============ BORDERS & DIVIDERS ============
    val BorderFocused = Color(0xFFFFFFFF)         // White - focused input borders
    val BorderUnfocused = Color(0xFF808080)       // Gray - unfocused borders
    val BorderSubtle = Color(0xFF404040)          // Dark gray - very subtle borders
    val Divider = Color(0xFF808080)               // Gray - dividers

    // ============ STATUS COLORS ============
    val StatusActive = Color(0xFFFFFFFF)          // White - active state background
    val StatusActiveText = Color(0xFF000000)      // Black - text on active background
    val StatusActiveIndicator = Color(0xFF000000) // Black - dot indicator when active

    val StatusInactive = Color(0xFF000000)        // Black - inactive state background
    val StatusInactiveText = Color(0xFFFFFFFF)    // White - text on inactive background

    val StatusDeactivated = Color(0xFF000000)     // Black - deactivated state
    val StatusDeactivatedText = Color(0xFF808080) // Gray - deactivated text
    val StatusDeactivatedBorder = Color(0xFF404040) // Dark gray - deactivated border

    // ============ SEMANTIC COLORS ============
    val Error = Color(0xFF8B0000)                 // Red - errors, delete actions
    val ErrorDark = Color(0xFF8B0000)             // Dark red - error backgrounds
    val Warning = Color(0xFFFFDD88)               // Yellow - warnings
    val WarningBackground = Color(0xFF1A1A00)     // Dark yellow - warning backgrounds
    val Success = Color(0xFF4CAF50)               // Green - success states
    val SuccessBackground = Color(0xFF001A00)     // Dark green - success backgrounds

    // ============ DIALOG BORDERS (sexy & professional) ============
    val DialogBorderDelete = Color(0xFF340000)    // Dark red - delete/destructive dialogs
    val DialogBorderEdit = Color(0xFF2A1C00)      // Dark yellow/amber - edit/modify dialogs
    val DialogBorderWarning = Color(0xFF340000)   // Dark orange - warning/permission dialogs
    val DialogBorderInfo = Color(0xFF1F1F1F)      // White - info/create dialogs
    val DialogBorderWidth = 2.dp                  // Border thickness

    // ============ ICONS ============
    val IconPrimary = Color(0xFFFFFFFF)           // White - primary icons
    val IconSecondary = Color(0xFF808080)         // Gray - secondary icons
    val IconDisabled = Color(0xFF404040)          // Dark gray - disabled icons

    // ============ INPUT FIELDS ============
    val InputBackground = Color(0xFF000000)       // Black - input field backgrounds
    val InputText = Color(0xFFFFFFFF)             // White - input text
    val InputCursor = Color(0xFFFFFFFF)           // White - cursor
    val InputPlaceholder = Color(0xFF808080)      // Gray - placeholder text

    // ============ SPECIAL ELEMENTS ============
    val NfcIcon = Color(0xFFFFFFFF)               // White - NFC icons
    val NfcIconSubtle = Color(0xFF808080)         // Gray - subtle NFC icons
    val OverlayBackground = Color(0x88000000)     // Semi-transparent black - overlays

    // ============ BLOCKER SCREEN ============
    val BlockerBackground = Color(0xFF000000)     // Black - blocker screen background
    val BlockerText = Color(0xFFFFFFFF)           // White - blocker main text
    val BlockerSubtext = Color(0xFF808080)        // Gray - blocker secondary text
}