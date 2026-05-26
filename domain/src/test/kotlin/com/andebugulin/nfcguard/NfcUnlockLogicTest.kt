package com.andebugulin.nfcguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Characterization tests for the NFC unlock state machine.
 * These lock in current behavior before further refactoring.
 */
class NfcUnlockLogicTest {

    // ─── Builders ───────────────────────────────────────────────────────────

    private fun mode(
        id: String,
        blockMode: BlockMode = BlockMode.BLOCK_SELECTED,
        blockedApps: List<String> = listOf("com.example.app"),
        nfcTagIds: List<String> = emptyList(),
        tagUnlockLimits: Map<String, Long?> = emptyMap()
    ) = Mode(
        id = id,
        name = id,
        blockedApps = blockedApps,
        blockMode = blockMode,
        nfcTagIds = nfcTagIds,
        tagUnlockLimits = tagUnlockLimits
    )

    private fun dailySchedule(
        id: String,
        linkedModeIds: List<String>,
        startHour: Int = 9,
        endHour: Int = 17
    ): Schedule {
        val dayTimes = (1..7).map { day ->
            DayTime(day = day, startHour = startHour, startMinute = 0, endHour = endHour, endMinute = 0)
        }
        return Schedule(
            id = id,
            name = id,
            timeSlot = TimeSlot(dayTimes),
            linkedModeIds = linkedModeIds,
            hasEndTime = true
        )
    }

    // ─── computePendingUnlock ───────────────────────────────────────────────

    @Test
    fun computePendingUnlock_noActiveModes_returnsNull() {
        val state = AppState(modes = listOf(mode("m1", nfcTagIds = listOf("tag-1"))))
        val pending = NfcUnlockLogic.computePendingUnlock(state, "tag-1", 1, 600)
        assertNull(pending)
    }

    @Test
    fun computePendingUnlock_specificTagMatch_returnsPending() {
        val m = mode("m1", nfcTagIds = listOf("tag-1"))
        val state = AppState(modes = listOf(m), activeModes = setOf("m1"))

        val pending = NfcUnlockLogic.computePendingUnlock(state, "tag-1", 1, 600)

        assertNotNull(pending)
        assertEquals(setOf("m1"), pending!!.modeIds)
        assertEquals("tag-1", pending.tagId)
        assertNull(pending.maxLimitMinutes) // no limit configured = permanent
    }

    @Test
    fun computePendingUnlock_wrongTag_returnsNull() {
        val m = mode("m1", nfcTagIds = listOf("tag-1"))
        val state = AppState(modes = listOf(m), activeModes = setOf("m1"))

        val pending = NfcUnlockLogic.computePendingUnlock(state, "tag-WRONG", 1, 600)
        assertNull(pending)
    }

    @Test
    fun computePendingUnlock_anyWildcard_anyTagUnlocks() {
        val m = mode("m1", nfcTagIds = listOf("ANY"))
        val state = AppState(modes = listOf(m), activeModes = setOf("m1"))

        val pending = NfcUnlockLogic.computePendingUnlock(state, "any-random-tag", 1, 600)
        assertNotNull(pending)
        assertEquals(setOf("m1"), pending!!.modeIds)
    }

    @Test
    fun computePendingUnlock_noTagsLinked_anyTagUnlocksWithoutLimit() {
        val m = mode("m1") // empty nfcTagIds
        val state = AppState(modes = listOf(m), activeModes = setOf("m1"))

        val pending = NfcUnlockLogic.computePendingUnlock(state, "whatever", 1, 600)
        assertNotNull(pending)
        assertEquals(setOf("m1"), pending!!.modeIds)
        assertEquals(mapOf<String, Long?>("m1" to null), pending.modeLimits)
        assertNull(pending.maxLimitMinutes)
    }

    @Test
    fun computePendingUnlock_perTagLimit_picked() {
        val m = mode(
            "m1",
            nfcTagIds = listOf("tag-1"),
            tagUnlockLimits = mapOf("tag-1" to 5L)
        )
        val state = AppState(modes = listOf(m), activeModes = setOf("m1"))

        val pending = NfcUnlockLogic.computePendingUnlock(state, "tag-1", 1, 600)

        assertNotNull(pending)
        assertEquals(5L, pending!!.maxLimitMinutes)
        assertEquals(5L, pending.modeLimits["m1"])
    }

    @Test
    fun computePendingUnlock_anyWildcardLimit_appliesToUnknownTag() {
        val m = mode(
            "m1",
            nfcTagIds = listOf("ANY"),
            tagUnlockLimits = mapOf("ANY" to 10L)
        )
        val state = AppState(modes = listOf(m), activeModes = setOf("m1"))

        val pending = NfcUnlockLogic.computePendingUnlock(state, "novel-tag", 1, 600)
        assertEquals(10L, pending!!.maxLimitMinutes)
    }

    @Test
    fun computePendingUnlock_aggregateLimitIsMinAcrossModes() {
        val m1 = mode("m1", nfcTagIds = listOf("tag-1"), tagUnlockLimits = mapOf("tag-1" to 30L))
        val m2 = mode("m2", nfcTagIds = listOf("tag-1"), tagUnlockLimits = mapOf("tag-1" to 5L))
        val m3 = mode("m3", nfcTagIds = listOf("tag-1"), tagUnlockLimits = mapOf("tag-1" to 60L))
        val state = AppState(
            modes = listOf(m1, m2, m3),
            activeModes = setOf("m1", "m2", "m3")
        )

        val pending = NfcUnlockLogic.computePendingUnlock(state, "tag-1", 1, 600)

        assertEquals(setOf("m1", "m2", "m3"), pending!!.modeIds)
        assertEquals(5L, pending.maxLimitMinutes)
    }

    @Test
    fun computePendingUnlock_permanentLimitMixedWithCapped_capWins() {
        // m1 = permanent (null), m2 = 5min cap. Aggregate should be 5 (capped).
        val m1 = mode("m1", nfcTagIds = listOf("tag-1"), tagUnlockLimits = mapOf("tag-1" to null))
        val m2 = mode("m2", nfcTagIds = listOf("tag-1"), tagUnlockLimits = mapOf("tag-1" to 5L))
        val state = AppState(modes = listOf(m1, m2), activeModes = setOf("m1", "m2"))

        val pending = NfcUnlockLogic.computePendingUnlock(state, "tag-1", 1, 600)
        assertEquals(5L, pending!!.maxLimitMinutes)
    }

    @Test
    fun computePendingUnlock_marksActiveSchedulesStartedToday() {
        val m = mode("m1", nfcTagIds = listOf("tag-1"))
        // Schedule starts at 09:00. Current time is 10:00 → schedule has started.
        val sched = dailySchedule("s1", listOf("m1"), startHour = 9)
        val state = AppState(
            modes = listOf(m),
            schedules = listOf(sched),
            activeModes = setOf("m1"),
            activeSchedules = setOf("s1")
        )

        val pending = NfcUnlockLogic.computePendingUnlock(state, "tag-1", 1, 10 * 60)
        assertEquals(setOf("s1"), pending!!.schedulesToDeactivate)
    }

    @Test
    fun computePendingUnlock_doesNotMarkScheduleBeforeStart() {
        val m = mode("m1", nfcTagIds = listOf("tag-1"))
        val sched = dailySchedule("s1", listOf("m1"), startHour = 14)
        val state = AppState(
            modes = listOf(m),
            schedules = listOf(sched),
            activeModes = setOf("m1"),
            activeSchedules = setOf("s1")
        )
        // 10:00 < 14:00 start
        val pending = NfcUnlockLogic.computePendingUnlock(state, "tag-1", 1, 10 * 60)
        assertTrue(pending!!.schedulesToDeactivate.isEmpty())
    }

    @Test
    fun computePendingUnlock_inactiveSchedule_notMarked() {
        val m = mode("m1", nfcTagIds = listOf("tag-1"))
        val sched = dailySchedule("s1", listOf("m1"), startHour = 9)
        val state = AppState(
            modes = listOf(m),
            schedules = listOf(sched),
            activeModes = setOf("m1"),
            activeSchedules = emptySet() // schedule not currently active
        )

        val pending = NfcUnlockLogic.computePendingUnlock(state, "tag-1", 1, 10 * 60)
        assertTrue(pending!!.schedulesToDeactivate.isEmpty())
    }

    // ─── applyUnlock ────────────────────────────────────────────────────────

    @Test
    fun applyUnlock_permanentUnlock_removesActiveMode() {
        val m = mode("m1", nfcTagIds = listOf("tag-1"))
        val state = AppState(modes = listOf(m), activeModes = setOf("m1"), manuallyActivatedModes = setOf("m1"))
        val pending = PendingUnlock(modeIds = setOf("m1"), schedulesToDeactivate = emptySet(), tagId = "tag-1")

        val result = NfcUnlockLogic.applyUnlock(state, pending, null, null, now = 1_000_000L, 1, 600)

        assertEquals(setOf("m1"), result.unlockedModeIds)
        assertTrue(result.newState.activeModes.isEmpty())
        assertTrue(result.newState.manuallyActivatedModes.isEmpty())
        assertTrue(result.newState.timedModeReactivations.isEmpty()) // permanent = no reactivation
    }

    @Test
    fun applyUnlock_temporaryUnlock_addsReactivationEntry() {
        val m = mode("m1", nfcTagIds = listOf("tag-1"))
        val state = AppState(modes = listOf(m), activeModes = setOf("m1"))
        val pending = PendingUnlock(modeIds = setOf("m1"), schedulesToDeactivate = emptySet(), tagId = "tag-1")
        val reactivateAt = 2_000_000L

        val result = NfcUnlockLogic.applyUnlock(state, pending, null, reactivateAt, now = 1_000_000L, 1, 600)

        assertEquals(mapOf("m1" to reactivateAt), result.newState.timedModeReactivations)
    }

    @Test
    fun applyUnlock_pausesRunningTimedDeactivation() {
        // Mode m1 has a timed deactivation in 10 minutes (600_000 ms).
        // User unlocks at now=1_000_000. Remaining = 600_000 should be saved.
        val m = mode("m1", nfcTagIds = listOf("tag-1"))
        val deadline = 1_600_000L
        val state = AppState(
            modes = listOf(m),
            activeModes = setOf("m1"),
            timedModeDeactivations = mapOf("m1" to deadline)
        )
        val pending = PendingUnlock(modeIds = setOf("m1"), schedulesToDeactivate = emptySet(), tagId = "tag-1")

        val result = NfcUnlockLogic.applyUnlock(state, pending, null, reactivateAtMillis = 1_500_000L, now = 1_000_000L, 1, 600)

        assertEquals(600_000L, result.newState.pausedModeRemainingMs["m1"])
        assertFalse(result.newState.timedModeDeactivations.containsKey("m1")) // active deactivation cleared
    }

    @Test
    fun applyUnlock_pastDeadline_doesNotSaveZeroRemaining() {
        // Deadline already passed — remaining = 0, should NOT be in pausedRemaining
        val m = mode("m1", nfcTagIds = listOf("tag-1"))
        val state = AppState(
            modes = listOf(m),
            activeModes = setOf("m1"),
            timedModeDeactivations = mapOf("m1" to 500_000L) // before now
        )
        val pending = PendingUnlock(modeIds = setOf("m1"), schedulesToDeactivate = emptySet(), tagId = "tag-1")

        val result = NfcUnlockLogic.applyUnlock(state, pending, null, null, now = 1_000_000L, 1, 600)
        assertFalse(result.newState.pausedModeRemainingMs.containsKey("m1"))
    }

    @Test
    fun applyUnlock_partialSelection_onlyUnlocksSelected() {
        val m1 = mode("m1", nfcTagIds = listOf("tag-1"))
        val m2 = mode("m2", nfcTagIds = listOf("tag-1"))
        val state = AppState(modes = listOf(m1, m2), activeModes = setOf("m1", "m2"))
        val pending = PendingUnlock(modeIds = setOf("m1", "m2"), schedulesToDeactivate = emptySet(), tagId = "tag-1")

        val result = NfcUnlockLogic.applyUnlock(
            state, pending,
            selectedModeIds = setOf("m1"),
            reactivateAtMillis = null,
            now = 1_000_000L, 1, 600
        )

        assertEquals(setOf("m1"), result.unlockedModeIds)
        assertEquals(setOf("m2"), result.newState.activeModes) // m2 still active
    }

    @Test
    fun applyUnlock_scheduleStaysActiveIfOtherLinkedModeStillActive() {
        // Schedule s1 links m1 AND m2. User unlocks only m1. Schedule must stay
        // active because m2 is still running.
        val m1 = mode("m1", nfcTagIds = listOf("tag-1"))
        val m2 = mode("m2", nfcTagIds = listOf("tag-2"))
        val sched = dailySchedule("s1", listOf("m1", "m2"), startHour = 9)
        val state = AppState(
            modes = listOf(m1, m2),
            schedules = listOf(sched),
            activeModes = setOf("m1", "m2"),
            activeSchedules = setOf("s1")
        )
        val pending = PendingUnlock(modeIds = setOf("m1"), schedulesToDeactivate = setOf("s1"), tagId = "tag-1")

        val result = NfcUnlockLogic.applyUnlock(state, pending, null, null, now = 1_000_000L, 1, 10 * 60)

        assertEquals(setOf("s1"), result.newState.activeSchedules) // schedule preserved
        assertFalse(result.newState.deactivatedSchedules.contains("s1"))
    }

    @Test
    fun applyUnlock_scheduleDeactivatedWhenAllLinkedModesUnlocked() {
        val m1 = mode("m1", nfcTagIds = listOf("tag-1"))
        val m2 = mode("m2", nfcTagIds = listOf("tag-1"))
        val sched = dailySchedule("s1", listOf("m1", "m2"), startHour = 9)
        val state = AppState(
            modes = listOf(m1, m2),
            schedules = listOf(sched),
            activeModes = setOf("m1", "m2"),
            activeSchedules = setOf("s1")
        )
        val pending = PendingUnlock(modeIds = setOf("m1", "m2"), schedulesToDeactivate = setOf("s1"), tagId = "tag-1")

        val result = NfcUnlockLogic.applyUnlock(state, pending, null, null, now = 1_000_000L, 1, 10 * 60)

        assertFalse(result.newState.activeSchedules.contains("s1"))
        assertTrue(result.newState.deactivatedSchedules.contains("s1"))
    }

    // ─── applyReactivation ──────────────────────────────────────────────────

    @Test
    fun applyReactivation_modeNotFound_returnsModeNotFound_clearsTimer() {
        val state = AppState(timedModeReactivations = mapOf("ghost" to 1_000L))
        val result = NfcUnlockLogic.applyReactivation(state, "ghost", now = 2_000L)
        assertTrue(result is NfcUnlockLogic.ReactivationResult.ModeNotFound)
        assertFalse(result.newState.timedModeReactivations.containsKey("ghost"))
    }

    @Test
    fun applyReactivation_alreadyActive_clearsTimerOnly() {
        val m = mode("m1")
        val state = AppState(
            modes = listOf(m),
            activeModes = setOf("m1"),
            timedModeReactivations = mapOf("m1" to 1_000L)
        )
        val result = NfcUnlockLogic.applyReactivation(state, "m1", now = 2_000L)
        assertTrue(result is NfcUnlockLogic.ReactivationResult.AlreadyActive)
        assertFalse(result.newState.timedModeReactivations.containsKey("m1"))
        assertEquals(setOf("m1"), result.newState.activeModes) // still active
    }

    @Test
    fun applyReactivation_blockAllowConflict_clearsTimerOnly() {
        val block = mode("m1", blockMode = BlockMode.BLOCK_SELECTED)
        val allow = mode("m2", blockMode = BlockMode.ALLOW_SELECTED)
        // m2 (ALLOW) is currently active. Reactivating m1 (BLOCK) would conflict.
        val state = AppState(
            modes = listOf(block, allow),
            activeModes = setOf("m2"),
            timedModeReactivations = mapOf("m1" to 1_000L)
        )

        val result = NfcUnlockLogic.applyReactivation(state, "m1", now = 2_000L)

        assertTrue(result is NfcUnlockLogic.ReactivationResult.Conflict)
        assertFalse(result.newState.timedModeReactivations.containsKey("m1"))
        assertFalse(result.newState.activeModes.contains("m1")) // NOT reactivated
    }

    @Test
    fun applyReactivation_restoresPausedDeactivationTimer() {
        // m1 had 10 minutes left when paused. now=2_000_000, remaining=600_000
        // → restored deadline = 2_600_000
        val m = mode("m1")
        val state = AppState(
            modes = listOf(m),
            activeModes = emptySet(),
            timedModeReactivations = mapOf("m1" to 1_500_000L),
            pausedModeRemainingMs = mapOf("m1" to 600_000L)
        )

        val result = NfcUnlockLogic.applyReactivation(state, "m1", now = 2_000_000L)

        assertTrue(result is NfcUnlockLogic.ReactivationResult.Reactivated)
        val reactivated = result as NfcUnlockLogic.ReactivationResult.Reactivated
        assertEquals(2_600_000L, reactivated.restoredDeactivationAt)
        assertEquals(2_600_000L, reactivated.newState.timedModeDeactivations["m1"])
        assertFalse(reactivated.newState.pausedModeRemainingMs.containsKey("m1"))
        assertTrue(reactivated.newState.activeModes.contains("m1"))
    }

    @Test
    fun applyReactivation_noPausedTimer_noDeactivationScheduled() {
        val m = mode("m1")
        val state = AppState(
            modes = listOf(m),
            activeModes = emptySet(),
            timedModeReactivations = mapOf("m1" to 1_500_000L)
        )

        val result = NfcUnlockLogic.applyReactivation(state, "m1", now = 2_000_000L)

        assertTrue(result is NfcUnlockLogic.ReactivationResult.Reactivated)
        val reactivated = result as NfcUnlockLogic.ReactivationResult.Reactivated
        assertNull(reactivated.restoredDeactivationAt)
        assertFalse(reactivated.newState.timedModeDeactivations.containsKey("m1"))
        assertTrue(reactivated.newState.activeModes.contains("m1"))
    }

    @Test
    fun applyReactivation_restoresDeactivatedSchedulesLinkedToMode() {
        val m = mode("m1")
        val sched = dailySchedule("s1", listOf("m1"), startHour = 9)
        val state = AppState(
            modes = listOf(m),
            schedules = listOf(sched),
            deactivatedSchedules = setOf("s1"),
            timedModeReactivations = mapOf("m1" to 1_500_000L)
        )

        val result = NfcUnlockLogic.applyReactivation(state, "m1", now = 2_000_000L)

        assertTrue(result is NfcUnlockLogic.ReactivationResult.Reactivated)
        assertTrue(result.newState.activeSchedules.contains("s1"))
        assertFalse(result.newState.deactivatedSchedules.contains("s1"))
    }
}
