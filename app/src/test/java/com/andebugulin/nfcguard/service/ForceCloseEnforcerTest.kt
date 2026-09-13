package com.andebugulin.nfcguard.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.BlockDecider
import com.andebugulin.nfcguard.testing.setDetectorState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/**
 * `ForceCloseEnforcer`'s cooldown is the whole point of the class, and it is
 * pure bookkeeping over `block`/`onAllowed` — so it is JVM-testable, which
 * TESTS.md assumed it was not ("needs a device test that can observe the
 * launcher coming forward"). Observing the launcher is not necessary: the HOME
 * *intent* is the observable, and with `ForegroundDetectorService.isRunning`
 * false the accessibility path is skipped, making the fallback deterministic.
 *
 * What stays device-only is whether accessibility's `goHome()` actually moves
 * the launcher on a given OEM build.
 */
@RunWith(RobolectricTestRunner::class)
class ForceCloseEnforcerTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var enforcer: ForceCloseEnforcer

    private val blocked = "com.example.social"
    private val launcher = "com.example.launcher"

    @Before fun setUp() {
        // No accessibility service in a JVM test, so forceClose takes the
        // documented Intent fallback rather than ForegroundDetectorService.
        setDetectorState("isRunning", false)
        enforcer = ForceCloseEnforcer(app)
        drainStartedActivities()
        ShadowToast.reset()
    }

    @After fun tearDown() {
        enforcer.onDestroy()
    }

    /** Each force-close sends one HOME intent; draining counts them. */
    private fun drainStartedActivities(): List<Intent> {
        val out = mutableListOf<Intent>()
        while (true) out += shadowOf(app).nextStartedActivity ?: break
        return out
    }

    private fun forceCloses(): List<Intent> {
        ShadowLooper.idleMainLooper() // the toast is posted to the main handler
        return drainStartedActivities()
    }

    private fun block(pkg: String) = runBlocking { enforcer.block(pkg) }
    private fun allow(pkg: String, isLauncher: Boolean = false) =
        runBlocking { enforcer.onAllowed(pkg, isLauncher) }

    @Test fun `blocking an app sends the user HOME`() {
        block(blocked)

        val intents = forceCloses()
        assertEquals(1, intents.size)
        assertEquals(Intent.ACTION_MAIN, intents[0].action)
        assertTrue(intents[0].categories.contains(Intent.CATEGORY_HOME))
        assertTrue(
            "HOME from a non-Activity context needs NEW_TASK",
            intents[0].flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
    }

    @Test fun `blocking tells the user how to unlock`() {
        block(blocked)
        ShadowLooper.idleMainLooper()

        assertEquals(
            "BLOCKED — go to nfcGuard & tap NFC tag to unlock",
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test fun `a repeat tick for the same app inside the cooldown is suppressed`() {
        block(blocked)
        assertEquals(1, forceCloses().size)

        // Accessibility events lag 3-4s, so the detector keeps reporting the
        // blocked app. Re-sending HOME each tick would spam the user.
        block(blocked)
        block(blocked)
        assertEquals("cooldown should swallow these", 0, forceCloses().size)
    }

    @Test fun `a different app is closed immediately, not held off by the cooldown`() {
        block(blocked)
        assertEquals(1, forceCloses().size)

        block("com.example.other")
        assertEquals(1, forceCloses().size)
    }

    @Test fun `landing in a real app clears the cooldown`() {
        block(blocked)
        forceCloses()

        allow("com.example.notes")

        block(blocked)
        assertEquals(
            "after the user reached a real app, a fresh block must act",
            1, forceCloses().size
        )
    }

    @Test fun `passing through the launcher does not clear the cooldown`() {
        block(blocked)
        forceCloses()

        // The launcher is the transient state during the HOME animation. Clearing
        // the cooldown here means the next stale accessibility event for the
        // blocked app ejects the user out of whatever they open next.
        allow(launcher, isLauncher = true)

        block(blocked)
        assertEquals(0, forceCloses().size)
    }

    @Test fun `passing through a critical system app does not clear the cooldown`() {
        block(blocked)
        forceCloses()

        val critical = BlockDecider.CRITICAL_SYSTEM_APPS.first()
        allow(critical)

        block(blocked)
        assertEquals(
            "a dialer or Settings detour is not the user settling into an app",
            0, forceCloses().size
        )
    }

    @Test fun `onAllowed before anything was blocked is a no-op`() {
        allow("com.example.notes")
        assertEquals(0, forceCloses().size)
    }
}
