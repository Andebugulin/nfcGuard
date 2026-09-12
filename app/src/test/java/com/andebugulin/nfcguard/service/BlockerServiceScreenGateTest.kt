package com.andebugulin.nfcguard.service

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Regression tests for issue #13 ("App Opens When Unlocking Phone").
 *
 * With the screen off, `ForegroundAppDetector` falls through to its
 * usage-stats fallback, which reports the last-used package — usually the
 * blocked one. Enforcing on that put the overlay over the lock screen
 * (FLAG_SHOW_WHEN_LOCKED) and held the display awake (FLAG_KEEP_SCREEN_ON).
 *
 * The gate: enforcement only runs while the user can actually reach an app.
 */
@RunWith(RobolectricTestRunner::class)
class BlockerServiceScreenGateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun screenUsable(interactive: Boolean, locked: Boolean): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(pm).setIsInteractive(interactive)
        shadowOf(km).setKeyguardLocked(locked)

        val service = Robolectric.buildService(BlockerService::class.java).create().get()
        val method = BlockerService::class.java.getDeclaredMethod("isScreenUsable")
        method.isAccessible = true
        return method.invoke(service) as Boolean
    }

    @Test fun `enforces while the screen is on and unlocked`() {
        assertTrue(screenUsable(interactive = true, locked = false))
    }

    @Test fun `does not enforce while the screen is off`() {
        assertFalse(screenUsable(interactive = false, locked = false))
    }

    @Test fun `does not enforce behind the keyguard`() {
        assertFalse(screenUsable(interactive = true, locked = true))
    }

    @Test fun `does not enforce while asleep and locked`() {
        assertFalse(screenUsable(interactive = false, locked = true))
    }
}
