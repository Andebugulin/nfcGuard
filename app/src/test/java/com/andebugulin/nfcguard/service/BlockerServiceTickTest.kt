package com.andebugulin.nfcguard.service

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.app.KeyguardManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.BlockDecider
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.resetAppStateRepository
import com.andebugulin.nfcguard.testing.setDetectorState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * One tick of the monitoring loop: detect the foreground app, decide, enforce.
 *
 * The decision itself is `BlockDecider`'s (covered in `:domain`) and each
 * enforcer is covered on its own. What only lives here is the wiring between
 * them, and in particular the branch CLAUDE.md calls out:
 *
 *  - **Which enforcer runs is chosen per tick** from
 *    `ForegroundDetectorService.isRunning`. Overlay and accessibility race
 *    badly together — on Samsung the overlay's appearance kills the
 *    accessibility service — so exactly one must act.
 *  - **On the allow path, *both* enforcers' `onAllowed` fire.** That is
 *    deliberate defence in depth: the force-close cooldown reset and hiding an
 *    overlay left over from a previous fallback are independent concerns, and
 *    whichever enforcer is not in charge this tick may still have state to
 *    clean up.
 *
 * The tick is driven directly rather than through the 500ms loop, which would
 * make these tests timing-dependent for no gain.
 */
@RunWith(RobolectricTestRunner::class)
class BlockerServiceTickTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val usm: UsageStatsManager
        get() = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val blocked = "com.example.social"
    private val launcher = "com.example.launcher"

    @Before fun setUp() {
        resetAppStateRepository()
        grantOverlayPermission()
        setDetectorState("isRunning", false)
        setDetectorState("lastDetectedPackage", null)
        setDetectorState("lastDetectedTime", 0L)
        screen(interactive = true, locked = false)
    }

    @After fun tearDown() {
        setDetectorState("isRunning", false)
    }

    private fun screen(interactive: Boolean, locked: Boolean) {
        shadowOf(app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .setIsInteractive(interactive)
        shadowOf(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
            .setKeyguardLocked(locked)
    }

    /** Accessibility bound *and* reporting this app — strategy 1 of the detector. */
    private fun accessibilityReports(pkg: String) {
        setDetectorState("isRunning", true)
        setDetectorState("lastDetectedPackage", pkg)
        setDetectorState("lastDetectedTime", System.currentTimeMillis())
    }

    /** No accessibility; the detector falls through to usage events. */
    private fun usageEventsReport(pkg: String) {
        setDetectorState("isRunning", false)
        shadowOf(usm).addEvent(pkg, System.currentTimeMillis() - 1_000, UsageEvents.Event.ACTIVITY_RESUMED)
    }

    private fun service(
        blockedApps: Set<String> = setOf(blocked),
        blockMode: BlockMode = BlockMode.BLOCK_SELECTED,
        activeModeIds: Set<String> = setOf("m1")
    ): BlockerService {
        val intent = Intent(app, BlockerService::class.java).apply {
            putStringArrayListExtra("blocked_apps", ArrayList(blockedApps))
            putExtra("block_mode", blockMode.name)
            putStringArrayListExtra("active_mode_ids", ArrayList(activeModeIds))
            putStringArrayListExtra("manually_activated_mode_ids", ArrayList(activeModeIds))
            putExtra("timed_mode_deactivations", HashMap<String, Long>())
            putExtra("mode_names", HashMap(mapOf("m1" to "Deep Work")))
            putExtra("timed_mode_reactivations", HashMap<String, Long>())
        }
        val controller = Robolectric.buildService(BlockerService::class.java, intent)
            .create().startCommand(0, 0)
        drainStartedActivities()   // the loop may already have ticked
        return controller.get()
    }

    /**
     * Runs one tick.
     *
     * `checkCurrentApp` is a private suspend function; bridging its raw
     * continuation calls it without dragging in kotlin-reflect and without
     * waiting on the real 500ms loop.
     *
     * It runs on a worker with a deadline rather than inline, because one path
     * *can* deadlock: raising the overlay dispatches to `Dispatchers.Main`, and
     * a paused Robolectric looper can never drain that while the test thread is
     * blocked. A deadline turns that into a named failure in five seconds
     * instead of a suite that hangs until the build times out.
     */
    private fun tick(service: BlockerService) {
        val method = BlockerService::class.java
            .getDeclaredMethod("checkCurrentApp", Continuation::class.java)
            .apply { isAccessible = true }
        var failure: Throwable? = null
        val worker = Thread {
            try {
                runBlocking {
                    suspendCoroutineUninterceptedOrReturn<Any?> { cont -> method.invoke(service, cont) }
                    Unit
                }
            } catch (t: Throwable) {
                failure = t
            }
        }
        worker.start()
        worker.join(5_000)
        check(!worker.isAlive) {
            "the tick did not finish — a path dispatched to Dispatchers.Main, " +
                "which a paused Robolectric looper cannot drain"
        }
        failure?.let { throw it }
    }

    /** A force-close sends HOME; the overlay does not. That is the observable difference. */
    private fun drainStartedActivities(): List<Intent> {
        val out = mutableListOf<Intent>()
        while (true) out += shadowOf(app).nextStartedActivity ?: break
        return out
    }

    private fun sentHome() = drainStartedActivities().any {
        it.action == Intent.ACTION_MAIN && it.categories?.contains(Intent.CATEGORY_HOME) == true
    }

    private fun setOverlayShowing(service: BlockerService, value: Boolean) {
        val enforcer = BlockerService::class.java.getDeclaredField("overlayEnforcer")
            .apply { isAccessible = true }.get(service)
        OverlayEnforcer::class.java.getDeclaredField("isShowing")
            .apply { isAccessible = true }.setBoolean(enforcer, value)
    }

    private fun overlayShowing(service: BlockerService): Boolean {
        val enforcerField = BlockerService::class.java.getDeclaredField("overlayEnforcer")
            .apply { isAccessible = true }
        val enforcer = enforcerField.get(service)
        val showing = OverlayEnforcer::class.java.getDeclaredField("isShowing")
            .apply { isAccessible = true }
        return showing.getBoolean(enforcer)
    }

    // ---------------- which enforcer acts ----------------

    @Test fun `with accessibility bound a blocked app is force-closed`() {
        val service = service()
        accessibilityReports(blocked)

        tick(service)

        assertTrue("expected the force-close path to send HOME", sentHome())
    }

    /**
     * The overlay is marked already-showing first, so `showSafe` takes its
     * early return instead of dispatching an animation to `Dispatchers.Main`
     * (see [tick]). That is enough to pin the *selection* — no HOME means
     * force-close did not act — while the overlay actually appearing stays
     * covered on device by `OverlayEnforcerInstrumentedTest`.
     */
    @Test fun `without accessibility a blocked app gets the overlay instead`() {
        val service = service()
        setOverlayShowing(service, true)
        usageEventsReport(blocked)

        tick(service)

        assertFalse(
            "the overlay path must not send HOME — that is the force-close enforcer",
            sentHome()
        )
        assertTrue("the overlay should still be the one in charge", overlayShowing(service))
    }

    /**
     * Exactly one enforcer acts per tick. Running both is what kills the
     * accessibility service on Samsung.
     */
    @Test fun `the force-close path does not also raise the overlay`() {
        val service = service()
        accessibilityReports(blocked)

        tick(service)

        assertTrue(sentHome())
        assertFalse("only one enforcer may act on a tick", overlayShowing(service))
    }

    // ---------------- the allow path ----------------

    @Test fun `an app that is not blocked is left alone`() {
        val service = service()
        accessibilityReports("com.example.notes")

        tick(service)

        assertFalse(sentHome())
        assertFalse(overlayShowing(service))
    }

    @Test fun `the launcher is never blocked`() {
        val service = service(blockedApps = setOf(blocked, launcher))
        accessibilityReports(launcher)

        tick(service)

        // isSystemLauncher resolves the real HOME package, which Robolectric
        // does not set to our fake — so assert the decision directly instead.
        assertEquals(
            BlockDecider.Decision.ALLOW,
            BlockDecider.decide(
                currentApp = launcher,
                isLauncher = true,
                activeModeIds = setOf("m1"),
                blockedApps = setOf(blocked, launcher),
                blockMode = BlockMode.BLOCK_SELECTED
            )
        )
    }

    @Test fun `a critical system app is never blocked even when blocklisted`() {
        val critical = BlockDecider.CRITICAL_SYSTEM_APPS.first()
        val service = service(blockedApps = setOf(critical))
        accessibilityReports(critical)

        tick(service)

        assertFalse("blocking Settings would trap the user", sentHome())
        assertFalse(overlayShowing(service))
    }

    /**
     * Both enforcers' `onAllowed` fire on the allow path. Proving it for
     * force-close: its 3-second cooldown only resets when the user reaches a
     * real app, so a second block right after one is suppressed — unless the
     * allow tick ran and cleared it.
     */
    @Test fun `reaching a real app clears the force-close cooldown`() {
        val service = service()
        accessibilityReports(blocked)
        tick(service)
        assertTrue("first block should act", sentHome())

        accessibilityReports("com.example.notes")
        tick(service)                                   // allow tick clears the cooldown

        accessibilityReports(blocked)
        tick(service)

        assertTrue("the cooldown should have been reset by the allow tick", sentHome())
    }

    @Test fun `a repeated block without an allow in between is held off by the cooldown`() {
        val service = service()
        accessibilityReports(blocked)
        tick(service)
        assertTrue(sentHome())

        tick(service)

        assertFalse("the cooldown should suppress the repeat", sentHome())
    }

    // ---------------- gates before any of that ----------------

    @Test fun `nothing is enforced while the screen is off`() {
        val service = service()
        accessibilityReports(blocked)
        screen(interactive = false, locked = false)

        tick(service)

        assertFalse(sentHome())
        assertFalse(overlayShowing(service))
    }

    @Test fun `nothing is enforced behind the keyguard`() {
        val service = service()
        accessibilityReports(blocked)
        screen(interactive = true, locked = true)

        tick(service)

        assertFalse(sentHome())
    }

    /**
     * With nothing active and nothing blocked there is no decision to make, and
     * the tick returns before detection — which is what stops a stale blocklist
     * being evaluated during a mode transition.
     */
    @Test fun `with no active modes and an empty blocklist it enforces nothing`() {
        val service = service(blockedApps = emptySet(), activeModeIds = emptySet())
        accessibilityReports(blocked)

        tick(service)

        assertFalse(sentHome())
        assertFalse(overlayShowing(service))
    }

    @Test fun `an allowlist mode blocks anything outside the list`() {
        val service = service(
            blockedApps = setOf("com.example.notes"),
            blockMode = BlockMode.ALLOW_SELECTED
        )
        accessibilityReports(blocked)

        tick(service)

        assertTrue("under ALLOW_SELECTED an unlisted app is blocked", sentHome())
    }

    @Test fun `an allowlist mode leaves a listed app alone`() {
        val service = service(
            blockedApps = setOf("com.example.notes"),
            blockMode = BlockMode.ALLOW_SELECTED
        )
        accessibilityReports("com.example.notes")

        tick(service)

        assertFalse(sentHome())
    }
}
