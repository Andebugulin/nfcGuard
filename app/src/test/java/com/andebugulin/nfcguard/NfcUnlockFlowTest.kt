package com.andebugulin.nfcguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end NFC-unlock scenarios that exercise the full state-machine
 * sequence — `computePendingUnlock` → `applyUnlock` → (later)
 * `applyReactivation`. Unit tests in `NfcUnlockLogicTest` cover each
 * function in isolation; these tests prove the composition is correct.
 */
class NfcUnlockFlowTest {

    // ─── Builders ───────────────────────────────────────────────────────────

    private fun mode(
        id: String,
        blockMode: BlockMode = BlockMode.BLOCK_SELECTED,
        nfcTagIds: List<String> = emptyList(),
        tagUnlockLimits: Map<String, Long?> = emptyMap()
    ) = Mode(id = id, name = id, blockedApps = listOf("com.example"), blockMode = blockMode,
        nfcTagIds = nfcTagIds, tagUnlockLimits = tagUnlockLimits)

    private fun dailySchedule(
        id: String, linkedModeIds: List<String>,
        startHour: Int = 9, endHour: Int = 17
    ): Schedule = Schedule(
        id = id, name = id,
        timeSlot = TimeSlot((1..7).map { DayTime(it, startHour, 0, endHour, 0) }),
        linkedModeIds = linkedModeIds, hasEndTime = true
    )

    // ─── Round-trip scenarios ──────────────────────────────────────────────

    @Test
    fun nfc_unlock_with_running_user_timer_pauses_remaining_then_restores_on_reactivation() {
        // Setup: m1 active with a 10-minute user timer (deadline = now + 600k).
        val now1 = 1_000_000L
        val deadline = now1 + 600_000L
        val m1 = mode("m1", nfcTagIds = listOf("tag-1"))
        val initial = AppState(
            modes = listOf(m1),
            activeModes = setOf("m1"),
            manuallyActivatedModes = setOf("m1"),
            timedModeDeactivations = mapOf("m1" to deadline)
        )

        // Step 1: tag scanned → pending
        val pending = NfcUnlockLogic.computePendingUnlock(initial, "tag-1", 1, 600)
        assertNotNull(pending)

        // Step 2: user confirms 5-minute temporary unlock
        val reactivateAt = now1 + 300_000L
        val afterUnlock = NfcUnlockLogic.applyUnlock(
            initial, pending!!, selectedModeIds = null,
            reactivateAtMillis = reactivateAt, now = now1, currentDayOfWeek = 1, currentMinuteOfDay = 600
        ).newState

        // Remaining time of the original timer (600k) should be stashed.
        assertEquals(600_000L, afterUnlock.pausedModeRemainingMs["m1"])
        assertFalse(afterUnlock.activeModes.contains("m1"))
        assertEquals(reactivateAt, afterUnlock.timedModeReactivations["m1"])

        // Step 3: 5 minutes later, reactivation fires
        val now2 = reactivateAt
        val reactivated = NfcUnlockLogic.applyReactivation(afterUnlock, "m1", now2)
        assertTrue(reactivated is NfcUnlockLogic.ReactivationResult.Reactivated)
        val r = reactivated as NfcUnlockLogic.ReactivationResult.Reactivated

        // The restored deadline should be now2 + the 600k that was paused.
        assertEquals(now2 + 600_000L, r.restoredDeactivationAt)
        assertEquals(now2 + 600_000L, r.newState.timedModeDeactivations["m1"])
        assertFalse(r.newState.pausedModeRemainingMs.containsKey("m1"))
        assertTrue(r.newState.activeModes.contains("m1"))
        assertFalse(r.newState.timedModeReactivations.containsKey("m1"))
    }

    @Test
    fun nfc_unlock_permanent_during_active_schedule_deactivates_schedule() {
        // Setup: schedule S1 (09:00-17:00) drives m1. Both currently active.
        // "Now" is 10:00 — schedule has started.
        val now = 1_000_000L
        val m1 = mode("m1", nfcTagIds = listOf("tag-1"))
        val s1 = dailySchedule("s1", listOf("m1"))
        val initial = AppState(
            modes = listOf(m1), schedules = listOf(s1),
            activeModes = setOf("m1"), activeSchedules = setOf("s1")
        )

        val pending = NfcUnlockLogic.computePendingUnlock(initial, "tag-1", 1, 10 * 60)
        // Schedule is in the pending's deactivation set.
        assertEquals(setOf("s1"), pending!!.schedulesToDeactivate)

        // Permanent unlock (reactivateAtMillis = null)
        val after = NfcUnlockLogic.applyUnlock(
            initial, pending, selectedModeIds = null,
            reactivateAtMillis = null, now = now, currentDayOfWeek = 1, currentMinuteOfDay = 10 * 60
        ).newState

        assertFalse(after.activeModes.contains("m1"))
        assertFalse(after.activeSchedules.contains("s1"))
        assertTrue(after.deactivatedSchedules.contains("s1"))
        // Permanent unlock → no reactivation scheduled
        assertFalse(after.timedModeReactivations.containsKey("m1"))
        // Wasn't on a user timer → nothing paused
        assertFalse(after.pausedModeRemainingMs.containsKey("m1"))
    }

    @Test
    fun nfc_unlock_temporary_then_reactivation_restores_deactivated_schedule() {
        // Setup: schedule S1 drives m1. Both active. User does a 5-min unlock,
        // which deactivates S1. When the timer expires, m1 reactivates AND
        // S1 should be restored to active.
        val now1 = 1_000_000L
        val m1 = mode("m1", nfcTagIds = listOf("tag-1"))
        val s1 = dailySchedule("s1", listOf("m1"))
        val initial = AppState(
            modes = listOf(m1), schedules = listOf(s1),
            activeModes = setOf("m1"), activeSchedules = setOf("s1")
        )

        val pending = NfcUnlockLogic.computePendingUnlock(initial, "tag-1", 1, 10 * 60)!!
        val reactivateAt = now1 + 300_000L
        val afterUnlock = NfcUnlockLogic.applyUnlock(
            initial, pending, null, reactivateAt, now1, 1, 10 * 60
        ).newState

        // Schedule moved to deactivatedSchedules during the temporary unlock.
        assertTrue(afterUnlock.deactivatedSchedules.contains("s1"))
        assertFalse(afterUnlock.activeSchedules.contains("s1"))

        // Reactivation later
        val reactivated = NfcUnlockLogic.applyReactivation(afterUnlock, "m1", reactivateAt)
            as NfcUnlockLogic.ReactivationResult.Reactivated

        // Schedule restored.
        assertTrue(reactivated.newState.activeSchedules.contains("s1"))
        assertFalse(reactivated.newState.deactivatedSchedules.contains("s1"))
        assertTrue(reactivated.newState.activeModes.contains("m1"))
    }

    @Test
    fun nfc_unlock_then_conflicting_mode_activated_blocks_reactivation() {
        // Real-world scenario: user has BLOCK mode m1 active. Taps NFC to unlock
        // temporarily. While unlocked, they manually activate ALLOW mode m2.
        // When m1's reactivation timer fires, m1 must NOT reactivate (would
        // conflict with m2). The reactivation entry is cleared silently.
        val now1 = 1_000_000L
        val m1 = mode("m1", blockMode = BlockMode.BLOCK_SELECTED, nfcTagIds = listOf("tag-1"))
        val m2 = mode("m2", blockMode = BlockMode.ALLOW_SELECTED)
        val initial = AppState(modes = listOf(m1, m2), activeModes = setOf("m1"))

        // Unlock m1 for 5 minutes
        val pending = NfcUnlockLogic.computePendingUnlock(initial, "tag-1", 1, 600)!!
        val reactivateAt = now1 + 300_000L
        val afterUnlock = NfcUnlockLogic.applyUnlock(
            initial, pending, null, reactivateAt, now1, 1, 600
        ).newState
        assertFalse(afterUnlock.activeModes.contains("m1"))

        // User manually activates m2 (ALLOW). Use ModeActivationLogic to do
        // this exactly as the ViewModel would.
        val afterM2 = ModeActivationLogic.applyModeActivation(afterUnlock, "m2", null)
        assertTrue(afterM2 is ModeActivationLogic.ActivateModeResult.Activated)
        val withM2 = afterM2.newState
        assertTrue(withM2.activeModes.contains("m2"))
        assertTrue(withM2.timedModeReactivations.containsKey("m1")) // still pending

        // Timer fires for m1
        val reactivationOutcome = NfcUnlockLogic.applyReactivation(withM2, "m1", reactivateAt)

        // Must be Conflict — m2 is ALLOW, m1 is BLOCK
        assertTrue(reactivationOutcome is NfcUnlockLogic.ReactivationResult.Conflict)
        // And the reactivation entry is cleared (so we don't retry forever)
        assertFalse(reactivationOutcome.newState.timedModeReactivations.containsKey("m1"))
        // m1 not active
        assertFalse(reactivationOutcome.newState.activeModes.contains("m1"))
        // m2 still active
        assertTrue(reactivationOutcome.newState.activeModes.contains("m2"))
    }

    @Test
    fun nfc_unlock_partial_selection_leaves_other_modes_active() {
        // Two modes with the same tag. User unlocks only m1 from the dialog;
        // m2 stays active.
        val now = 1_000_000L
        val m1 = mode("m1", nfcTagIds = listOf("tag-1"))
        val m2 = mode("m2", nfcTagIds = listOf("tag-1"))
        val initial = AppState(modes = listOf(m1, m2), activeModes = setOf("m1", "m2"))

        val pending = NfcUnlockLogic.computePendingUnlock(initial, "tag-1", 1, 600)!!
        assertEquals(setOf("m1", "m2"), pending.modeIds)

        val unlockResult = NfcUnlockLogic.applyUnlock(
            initial, pending,
            selectedModeIds = setOf("m1"), // user unchecks m2
            reactivateAtMillis = null, now = now, currentDayOfWeek = 1, currentMinuteOfDay = 600
        )

        assertEquals(setOf("m1"), unlockResult.unlockedModeIds)
        assertEquals(setOf("m2"), unlockResult.newState.activeModes)
    }
}
