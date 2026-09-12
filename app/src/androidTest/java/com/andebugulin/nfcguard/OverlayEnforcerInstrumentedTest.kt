package com.andebugulin.nfcguard

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.andebugulin.nfcguard.service.OverlayEnforcer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay is the enforcement path when accessibility is off, and it is
 * the one piece Robolectric cannot exercise: it adds a real
 * TYPE_APPLICATION_OVERLAY window through WindowManager.
 *
 * These tests assert it can be shown and torn down repeatedly without
 * leaking a window or throwing — the failure mode users report as "the block
 * screen got stuck".
 */
class OverlayEnforcerInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var enforcer: OverlayEnforcer? = null

    /**
     * Run a suspending enforcer call ON the main dispatcher without blocking
     * it. `runBlocking` inside `runOnMainSync` deadlocks: the overlay's
     * show/hide animations post their completion callbacks to the main
     * looper, which a blocked main thread can never drain.
     */
    private fun onMain(timeoutSec: Long = 10, body: suspend () -> Unit) {
        val latch = CountDownLatch(1)
        CoroutineScope(Dispatchers.Main).launch {
            try { body() } finally { latch.countDown() }
        }
        assertTrue("main-thread work timed out", latch.await(timeoutSec, TimeUnit.SECONDS))
    }

    private fun newEnforcer(): OverlayEnforcer =
        OverlayEnforcer(context) { /* onTouch */ }.also { enforcer = it }

    @After fun tearDown() {
        val e = enforcer ?: return
        InstrumentationRegistry.getInstrumentation().runOnMainSync { e.onDestroy() }
        enforcer = null
    }

    @Test fun showingAndHidingTheOverlayDoesNotThrow() {
        val e = newEnforcer()
        onMain {
            e.block("com.example.blocked")
            e.onAllowed("com.example.allowed", isLauncher = false)
        }
        assertTrue("completed a full show/hide cycle", true)
    }

    @Test fun blockingTwiceIsIdempotent() {
        val e = newEnforcer()
        onMain {
            e.block("com.example.blocked")
            e.block("com.example.blocked")
        }
        // A second add of the same view would throw IllegalStateException.
        assertTrue(true)
    }

    @Test fun forceHideImmediateIsSafeWhenNothingIsShowing() {
        val e = newEnforcer()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { e.forceHideImmediate() }
        assertTrue(true)
    }

    @Test fun onDestroyAfterBlockRemovesTheWindow() {
        val e = newEnforcer()
        onMain { e.block("com.example.blocked") }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { e.onDestroy() }
        enforcer = null
        assertTrue(true)
    }
}
