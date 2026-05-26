package com.andebugulin.nfcguard.ui

import com.andebugulin.nfcguard.ui.modes.ModeEditorScreen

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
    val SurfaceDim = Color(0xFF1A1A1A)             // Near-black - unselected option backgrounds

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
    val ButtonDisabledContainer = Color(0xFF333333) // Dark gray - disabled button background
    val ButtonDisabledText = Color(0xFF666666)    // Mid gray - text on disabled button

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
    val ErrorText = Color(0xFFFF8888)             // Soft pink - error text on dark snackbars/dialogs
    val ErrorTextEmphasized = Color(0xFFFF6666)   // Brighter pink - emphasized error text
    val Warning = Color(0xFFFFDD88)               // Yellow - warnings
    val WarningBackground = Color(0xFF1A1A00)     // Dark yellow - warning backgrounds
    val WarningAccent = Color(0xFFFFAA00)         // Amber - alternate warning accent (dialog highlights)
    val WarningAccentDim = Color(0xFF555500)      // Dark olive - warning border on dark surface
    val WarningTextMuted = Color(0xFF999966)      // Muted olive - muted warning text
    val Success = Color(0xFF4CAF50)               // Green - success states
    val SuccessBackground = Color(0xFF001A00)     // Dark green - success backgrounds
    val HighlightAccent = Color(0xFFFF9800)       // Orange - in-card warning accent (default)
    val HighlightAccentEmphasized = Color(0xFFE65100) // Deeper orange - same accent when selected

    // ============ LIGHT-SURFACE PALETTE ============
    // Used when a toggle/option flips its surface to white for emphasis
    // (e.g. ModeEditorScreen's selected option cards). The base text and
    // background are plain Color.Black / Color.White (intentional binary
    // inverse of the dark theme); these are the supporting greys.
    val OnLightSurfaceSecondaryText = Color(0xFF555555) // Mid gray - secondary text on white
    val OnLightSurfaceBorder = Color(0xFFCCCCCC)        // Light gray - input border on white

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
