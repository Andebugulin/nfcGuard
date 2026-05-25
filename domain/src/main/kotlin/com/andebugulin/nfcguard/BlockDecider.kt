package com.andebugulin.nfcguard

/**
 * Pure block/allow decision for a foreground app.
 *
 * Extracted from BlockerService so the policy is unit-testable without
 * an emulator. The platform-dependent input — "is this package the
 * current system launcher?" — is computed by the caller (PackageManager
 * is needed) and passed in as a boolean.
 *
 * Decision precedence (top wins):
 *   1. Critical system app (settings, dialer, IMEs, our own package, …) → ALLOW
 *   2. The system launcher → ALLOW
 *   3. No active modes → ALLOW (nothing to block)
 *   4. BLOCK_SELECTED: in blocklist → BLOCK, else ALLOW
 *      ALLOW_SELECTED: in allowlist → ALLOW, else BLOCK
 */
object BlockDecider {

    enum class Decision { BLOCK, ALLOW }

    /**
     * Apps that must never be blocked. The list is shared with the mode
     * editor UI so users can't accidentally pick a system package and
     * lock themselves out (e.g. blocking Settings would prevent them
     * from un-blocking Settings).
     */
    val CRITICAL_SYSTEM_APPS: Set<String> = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.providers.settings",
        "com.android.keychain",
        "android",
        "com.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.phone",
        "com.android.contacts",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.emergency",
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard",
        "com.andebugulin.nfcguard",
        // Lock screen / security apps
        "com.android.settings.lockscreen",
        "com.android.security",
        "com.miui.securitycenter",
        "com.samsung.android.lool",
        "com.coloros.lockscreen"
    )

    fun decide(
        currentApp: String,
        isLauncher: Boolean,
        activeModeIds: Set<String>,
        blockedApps: Set<String>,
        blockMode: BlockMode
    ): Decision {
        if (currentApp in CRITICAL_SYSTEM_APPS) return Decision.ALLOW
        if (isLauncher) return Decision.ALLOW
        if (activeModeIds.isEmpty()) return Decision.ALLOW
        return when (blockMode) {
            BlockMode.BLOCK_SELECTED -> if (currentApp in blockedApps) Decision.BLOCK else Decision.ALLOW
            BlockMode.ALLOW_SELECTED -> if (currentApp in blockedApps) Decision.ALLOW else Decision.BLOCK
        }
    }
}
