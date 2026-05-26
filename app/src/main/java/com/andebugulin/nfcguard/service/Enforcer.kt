package com.andebugulin.nfcguard.service

/**
 * Strategy for stopping the user from using a blocked app.
 *
 * Two implementations: [OverlayEnforcer] (full-screen `BLOCKED` view via
 * SYSTEM_ALERT_WINDOW) and [ForceCloseEnforcer] (send HOME, kill bg
 * processes, toast). The service picks which to invoke per tick:
 * overlay when accessibility is off, force-close when on (because the
 * overlay races with accessibility's own window events on MIUI/Samsung).
 *
 * Both enforcers' [onAllowed] hooks run every allow tick — defense in
 * depth, since either may have leftover state from a prior tick
 * (overlay stuck from a fallback path; force-close cooldown timer).
 */
interface Enforcer {

    /** Take whatever action prevents [packageName] from being used. */
    suspend fun block(packageName: String)

    /**
     * The user is in [currentApp] which is allowed. Clean up state so
     * the next block tick starts fresh. [isLauncher] lets the
     * implementation distinguish a real foreground app from a transient
     * launcher state — [ForceCloseEnforcer] needs that distinction for
     * its cooldown-reset rule.
     */
    suspend fun onAllowed(currentApp: String, isLauncher: Boolean)

    /** Tear down anything held (views, listeners). Called from `onDestroy`. */
    fun onDestroy()
}
