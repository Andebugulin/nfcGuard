package com.andebugulin.nfcguard.service

/**
 * Which system screens let the user get rid of nfcGuard, and so must be
 * closed while a mode is active.
 *
 * Decided from the window's package and class name alone — both arrive on
 * every `TYPE_WINDOW_STATE_CHANGED` event, so this needs no window-content
 * access and keeps the accessibility service's "reads nothing on screen"
 * promise.
 *
 *  - **Uninstall confirmation** (`UninstallerActivity` of the package
 *    installer, AOSP and Google builds). Reached from the launcher's long
 *    press, Settings › Apps, or Play. The event does not say which app is
 *    being removed, so every uninstall is refused during a blocking session.
 *  - **Device-admin deactivation** (`DeviceAdminAdd` in Settings). With
 *    [com.andebugulin.nfcguard.receiver.GuardianDeviceAdminReceiver] active,
 *    this is the only route to making nfcGuard uninstallable again.
 */
object UninstallGuard {

    private val INSTALLER_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    private val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        // Samsung and a few OEMs keep the device-admin screens elsewhere.
        "com.samsung.android.settings"
    )

    fun isProtectedScreen(packageName: String, className: String?): Boolean {
        val cls = className ?: return false
        if (packageName in INSTALLER_PACKAGES) {
            return cls.endsWith("UninstallerActivity") ||
                cls.endsWith("UninstallUninstalling") ||
                // Android 15's rewritten installer ("v2" UI).
                cls.endsWith("UninstallLaunch")
        }
        if (packageName in SETTINGS_PACKAGES) {
            return cls.endsWith("DeviceAdminAdd")
        }
        return false
    }

    /**
     * True when [packageName]/[className] must be dismissed: blocking is on and
     * the window is one of the screens above.
     */
    fun shouldBlock(packageName: String, className: String?, blockingActive: Boolean): Boolean =
        blockingActive && isProtectedScreen(packageName, className)
}
