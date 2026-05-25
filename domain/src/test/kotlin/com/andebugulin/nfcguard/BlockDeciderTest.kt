package com.andebugulin.nfcguard

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockDeciderTest {

    // ─── Precedence: critical system apps always allowed ────────────────────

    @Test
    fun criticalSystemApp_alwaysAllowed_evenInBlocklist() {
        // Even if a critical app is in the blocklist, it must be allowed.
        // (This is defense-in-depth; the editor also prevents the user from
        // picking such an app in the first place.)
        val decision = BlockDecider.decide(
            currentApp = "com.android.settings",
            isLauncher = false,
            activeModeIds = setOf("m1"),
            blockedApps = setOf("com.android.settings"),
            blockMode = BlockMode.BLOCK_SELECTED
        )
        assertEquals(BlockDecider.Decision.ALLOW, decision)
    }

    @Test
    fun ourOwnPackage_inCriticalList_isAllowed() {
        val decision = BlockDecider.decide(
            currentApp = "com.andebugulin.nfcguard",
            isLauncher = false,
            activeModeIds = setOf("m1"),
            blockedApps = setOf("com.andebugulin.nfcguard"),
            blockMode = BlockMode.BLOCK_SELECTED
        )
        assertEquals(BlockDecider.Decision.ALLOW, decision)
    }

    // ─── Launcher always allowed ───────────────────────────────────────────

    @Test
    fun launcher_alwaysAllowed() {
        val decision = BlockDecider.decide(
            currentApp = "com.example.launcher",
            isLauncher = true,
            activeModeIds = setOf("m1"),
            blockedApps = setOf("com.example.launcher"),
            blockMode = BlockMode.BLOCK_SELECTED
        )
        assertEquals(BlockDecider.Decision.ALLOW, decision)
    }

    // ─── No active modes ─────────────────────────────────────────────────────

    @Test
    fun noActiveModes_alwaysAllowed_evenWithBlocklist() {
        val decision = BlockDecider.decide(
            currentApp = "com.instagram.android",
            isLauncher = false,
            activeModeIds = emptySet(),
            blockedApps = setOf("com.instagram.android"),
            blockMode = BlockMode.BLOCK_SELECTED
        )
        assertEquals(BlockDecider.Decision.ALLOW, decision)
    }

    // ─── BLOCK_SELECTED ─────────────────────────────────────────────────────

    @Test
    fun blockSelected_appInList_blocks() {
        val decision = BlockDecider.decide(
            currentApp = "com.instagram.android",
            isLauncher = false,
            activeModeIds = setOf("m1"),
            blockedApps = setOf("com.instagram.android", "com.twitter.android"),
            blockMode = BlockMode.BLOCK_SELECTED
        )
        assertEquals(BlockDecider.Decision.BLOCK, decision)
    }

    @Test
    fun blockSelected_appNotInList_allows() {
        val decision = BlockDecider.decide(
            currentApp = "com.spotify.music",
            isLauncher = false,
            activeModeIds = setOf("m1"),
            blockedApps = setOf("com.instagram.android"),
            blockMode = BlockMode.BLOCK_SELECTED
        )
        assertEquals(BlockDecider.Decision.ALLOW, decision)
    }

    @Test
    fun blockSelected_emptyList_allowsEverything() {
        // BLOCK_SELECTED with an empty blocklist blocks nothing — useful when
        // the active modes only exist for schedule monitoring.
        val decision = BlockDecider.decide(
            currentApp = "com.example.app",
            isLauncher = false,
            activeModeIds = setOf("m1"),
            blockedApps = emptySet(),
            blockMode = BlockMode.BLOCK_SELECTED
        )
        assertEquals(BlockDecider.Decision.ALLOW, decision)
    }

    // ─── ALLOW_SELECTED ─────────────────────────────────────────────────────

    @Test
    fun allowSelected_appInAllowlist_allows() {
        val decision = BlockDecider.decide(
            currentApp = "com.spotify.music",
            isLauncher = false,
            activeModeIds = setOf("m1"),
            blockedApps = setOf("com.spotify.music"), // allowlist (poorly-named field)
            blockMode = BlockMode.ALLOW_SELECTED
        )
        assertEquals(BlockDecider.Decision.ALLOW, decision)
    }

    @Test
    fun allowSelected_appNotInAllowlist_blocks() {
        val decision = BlockDecider.decide(
            currentApp = "com.instagram.android",
            isLauncher = false,
            activeModeIds = setOf("m1"),
            blockedApps = setOf("com.spotify.music"),
            blockMode = BlockMode.ALLOW_SELECTED
        )
        assertEquals(BlockDecider.Decision.BLOCK, decision)
    }

    @Test
    fun allowSelected_emptyAllowlist_blocksEverything() {
        // The headline ALLOW_SELECTED use case: empty allowlist = block all
        // (non-critical, non-launcher) apps. Maximum focus mode.
        val decision = BlockDecider.decide(
            currentApp = "com.example.app",
            isLauncher = false,
            activeModeIds = setOf("m1"),
            blockedApps = emptySet(),
            blockMode = BlockMode.ALLOW_SELECTED
        )
        assertEquals(BlockDecider.Decision.BLOCK, decision)
    }

    // ─── Critical-list sanity ───────────────────────────────────────────────

    @Test
    fun criticalSystemApps_includesOurOwnPackage() {
        // Required so checkCurrentApp doesn't loop-block Guardian itself.
        assertEquals(true, "com.andebugulin.nfcguard" in BlockDecider.CRITICAL_SYSTEM_APPS)
    }

    @Test
    fun criticalSystemApps_includesDialerAndSettings() {
        // Sanity: the most important "user must be able to escape" apps.
        assertEquals(true, "com.android.settings" in BlockDecider.CRITICAL_SYSTEM_APPS)
        assertEquals(true, "com.android.dialer" in BlockDecider.CRITICAL_SYSTEM_APPS)
        assertEquals(true, "com.google.android.dialer" in BlockDecider.CRITICAL_SYSTEM_APPS)
        assertEquals(true, "com.android.emergency" in BlockDecider.CRITICAL_SYSTEM_APPS)
    }
}
