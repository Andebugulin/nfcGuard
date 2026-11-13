package com.andebugulin.nfcguard.receiver

import com.andebugulin.nfcguard.R
import com.andebugulin.nfcguard.data.AppLogger
import com.andebugulin.nfcguard.data.AppStateRepository

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Uninstall protection.
 *
 * Android will not uninstall a package while it is an active device admin —
 * the launcher, Settings, Play and `adb uninstall` all refuse until the admin
 * is deactivated first. No policy is requested (see `res/xml/device_admin.xml`);
 * nfcGuard only needs to *be* an admin.
 *
 * Deactivation itself cannot be vetoed by a plain admin, so while a mode is
 * active `ForegroundDetectorService` sends the user away from the deactivate
 * screen ([com.andebugulin.nfcguard.service.UninstallGuard]). Outside of a
 * blocking session the admin can be turned off normally.
 */
class GuardianDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        AppLogger.init(context)
        AppLogger.log("ADMIN", "Uninstall protection enabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        val active = runCatching {
            AppStateRepository.getInstance(context).current.activeModes.isNotEmpty()
        }.getOrDefault(false)
        return context.getString(
            if (active) R.string.admin_disable_warning_active else R.string.admin_disable_warning
        )
    }

    override fun onDisabled(context: Context, intent: Intent) {
        AppLogger.init(context)
        AppLogger.log("ADMIN", "Uninstall protection disabled")
    }
}
