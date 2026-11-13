package com.andebugulin.nfcguard.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallGuardTest {

    private val installer = "com.google.android.packageinstaller"
    private val uninstaller = "com.android.packageinstaller.UninstallerActivity"
    private val settings = "com.android.settings"
    private val adminAdd = "com.android.settings.applications.specialaccess.deviceadmin.DeviceAdminAdd"

    @Test fun `uninstall dialog is blocked while a mode is active`() {
        assertTrue(UninstallGuard.shouldBlock(installer, uninstaller, blockingActive = true))
        assertTrue(UninstallGuard.shouldBlock("com.android.packageinstaller", uninstaller, true))
    }

    @Test fun `admin deactivation screen is blocked while a mode is active`() {
        assertTrue(UninstallGuard.shouldBlock(settings, adminAdd, blockingActive = true))
    }

    @Test fun `nothing is blocked without an active mode`() {
        assertFalse(UninstallGuard.shouldBlock(installer, uninstaller, blockingActive = false))
        assertFalse(UninstallGuard.shouldBlock(settings, adminAdd, blockingActive = false))
    }

    @Test fun `ordinary settings and installs are left alone`() {
        assertFalse(UninstallGuard.shouldBlock(settings, "com.android.settings.SubSettings", true))
        assertFalse(UninstallGuard.shouldBlock(installer, "com.android.packageinstaller.InstallStart", true))
        assertFalse(UninstallGuard.shouldBlock(installer, null, true))
        assertFalse(UninstallGuard.shouldBlock("com.example.fake", uninstaller, true))
    }
}
