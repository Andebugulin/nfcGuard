package com.andebugulin.nfcguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for the schedule alarm transforms. Locks in
 * current behavior of activate/deactivate/timed-mode-deactivate before
 * the Phase 2 state-machine extraction.
 */
class ScheduleTransitionsTest {

    // ─── Builders ───────────────────────────────────────────────────────────

    private fun mode(
        id: String,
        blockMode: BlockMode = BlockMode.BLOCK_SELECTED
    ) = Mode(id = id, name = id, blockedApps = emptyList(), blockMode = blockMode)

    private fun dailySchedule(id: String, linkedModeIds: List<String>): Schedule {
        val dayTimes = (1..7).map { DayTime(it, 9, 0, 17, 0) }
        return Schedule(
            id = id, name = id,
            timeSlot = TimeSlot(dayTimes),
            linkedModeIds = linkedModeIds,
            hasEndTime = true
        )
    }

    // ─── applyScheduleActivation ───────────────────────────────────────────

    @Test
    fun applyScheduleActivation_scheduleNotFound_returnsSentinel() {
        val state = AppState()
        val result = ScheduleTransitions.applyScheduleActivation(state, "ghost")
        assertTrue(result is ScheduleTransitions.ScheduleActivationResult.ScheduleNotFound)
        assertSame(state, result.newState) // state untouched
    }

    @Test
    fun applyScheduleActivation_simple_activatesLinkedModes() {
        val m1 = mode("m1")
        val m2 = mode("m2")
        val s = dailySchedule("s1", listOf("m1", "m2"))
        val state = AppState(modes = listOf(m1, m2), schedules = listOf(s))

        val result = ScheduleTransitions.applyScheduleActivation(state, "s1")

        assertTrue(result is ScheduleTransitions.ScheduleActivationResult.Applied)
        val applied = result as ScheduleTransitions.ScheduleActivationResult.Applied
        assertEquals(setOf("m1", "m2"), applied.activatedModeIds)
        assertTrue(applied.conflictSkippedModeIds.isEmpty())
        assertEquals(setOf("m1", "m2"), applied.newState.activeModes)
        assertEquals(setOf("s1"), applied.newState.activeSchedules)
        assertFalse(applied.newState.deactivatedSchedules.contains("s1"))
    }

    @Test
    fun applyScheduleActivation_clearsPreviousDeactivatedMark() {
        val m1 = mode("m1")
        val s = dailySchedule("s1", listOf("m1"))
        val state = AppState(
            modes = listOf(m1),
            schedules = listOf(s),
            deactivatedSchedules = setOf("s1") // user dismissed previously
        )

        val result = ScheduleTransitions.applyScheduleActivation(state, "s1")

        assertTrue(result is ScheduleTransitions.ScheduleActivationResult.Applied)
        assertFalse(result.newState.deactivatedSchedules.contains("s1"))
        assertTrue(result.newState.activeSchedules.contains("s1"))
    }

    @Test
    fun applyScheduleActivation_skipsConflictingBlockMode() {
        // BLOCK mode already active. Schedule activates an ALLOW mode → conflict.
        val active = mode("m1", blockMode = BlockMode.BLOCK_SELECTED)
        val allow = mode("m2", blockMode = BlockMode.ALLOW_SELECTED)
        val s = dailySchedule("s1", listOf("m2"))
        val state = AppState(
            modes = listOf(active, allow),
            schedules = listOf(s),
            activeModes = setOf("m1")
        )

        val result = ScheduleTransitions.applyScheduleActivation(state, "s1")
        val applied = result as ScheduleTransitions.ScheduleActivationResult.Applied
        assertTrue(applied.activatedModeIds.isEmpty())
        assertEquals(setOf("m2"), applied.conflictSkippedModeIds)
        // m2 NOT added to active
        assertEquals(setOf("m1"), applied.newState.activeModes)
        // Schedule itself IS still marked active (preserves current behavior)
        assertTrue(applied.newState.activeSchedules.contains("s1"))
    }

    @Test
    fun applyScheduleActivation_missingModeId_silentlySkipped() {
        val m1 = mode("m1")
        val s = dailySchedule("s1", listOf("m1", "ghost-mode"))
        val state = AppState(modes = listOf(m1), schedules = listOf(s))

        val result = ScheduleTransitions.applyScheduleActivation(state, "s1")
        val applied = result as ScheduleTransitions.ScheduleActivationResult.Applied
        assertEquals(setOf("m1"), applied.activatedModeIds)
        assertTrue(applied.conflictSkippedModeIds.isEmpty()) // missing != conflict
    }

    @Test
    fun applyScheduleActivation_clearsPendingReactivationForActivatedModes() {
        // Mode m1 had a pending reactivation from a previous NFC unlock.
        // Schedule activates m1 → reactivation entry should be cleared.
        val m1 = mode("m1")
        val s = dailySchedule("s1", listOf("m1"))
        val state = AppState(
            modes = listOf(m1),
            schedules = listOf(s),
            timedModeReactivations = mapOf("m1" to 5_000L),
            pausedModeRemainingMs = mapOf("m1" to 600_000L)
        )

        val result = ScheduleTransitions.applyScheduleActivation(state, "s1")
        val applied = result as ScheduleTransitions.ScheduleActivationResult.Applied
        assertFalse(applied.newState.timedModeReactivations.containsKey("m1"))
        assertFalse(applied.newState.pausedModeRemainingMs.containsKey("m1"))
    }

    // ─── applyScheduleDeactivation ─────────────────────────────────────────

    @Test
    fun applyScheduleDeactivation_scheduleNotFound_returnsSentinel() {
        val state = AppState()
        val result = ScheduleTransitions.applyScheduleDeactivation(state, "ghost")
        assertTrue(result is ScheduleTransitions.ScheduleDeactivationResult.ScheduleNotFound)
    }

    @Test
    fun applyScheduleDeactivation_simple_removesLinkedModesAndSchedule() {
        val m1 = mode("m1")
        val s = dailySchedule("s1", listOf("m1"))
        val state = AppState(
            modes = listOf(m1),
            schedules = listOf(s),
            activeModes = setOf("m1"),
            activeSchedules = setOf("s1")
        )

        val result = ScheduleTransitions.applyScheduleDeactivation(state, "s1")
        val applied = result as ScheduleTransitions.ScheduleDeactivationResult.Applied
        assertEquals(setOf("m1"), applied.deactivatedModeIds)
        assertTrue(applied.keptDueToUserTimerModeIds.isEmpty())
        assertTrue(applied.newState.activeModes.isEmpty())
        assertFalse(applied.newState.activeSchedules.contains("s1"))
        assertFalse(applied.newState.deactivatedSchedules.contains("s1"))
    }

    @Test
    fun applyScheduleDeactivation_userTimerTakesPriority() {
        // m1 has a user-set deactivation timer. Schedule end should NOT kill m1.
        val m1 = mode("m1")
        val m2 = mode("m2")
        val s = dailySchedule("s1", listOf("m1", "m2"))
        val state = AppState(
            modes = listOf(m1, m2),
            schedules = listOf(s),
            activeModes = setOf("m1", "m2"),
            activeSchedules = setOf("s1"),
            timedModeDeactivations = mapOf("m1" to 9_999_999L)
        )

        val result = ScheduleTransitions.applyScheduleDeactivation(state, "s1")
        val applied = result as ScheduleTransitions.ScheduleDeactivationResult.Applied

        assertEquals(setOf("m2"), applied.deactivatedModeIds)
        assertEquals(setOf("m1"), applied.keptDueToUserTimerModeIds)
        assertTrue(applied.newState.activeModes.contains("m1")) // kept alive
        assertFalse(applied.newState.activeModes.contains("m2")) // deactivated
        assertFalse(applied.newState.activeSchedules.contains("s1"))
    }

    @Test
    fun applyScheduleDeactivation_clearsDeactivatedFlag() {
        // Edge: schedule is in deactivatedSchedules. End-time fires anyway and
        // the schedule cycle ends cleanly — both flags cleared.
        val m1 = mode("m1")
        val s = dailySchedule("s1", listOf("m1"))
        val state = AppState(
            modes = listOf(m1),
            schedules = listOf(s),
            deactivatedSchedules = setOf("s1")
        )

        val result = ScheduleTransitions.applyScheduleDeactivation(state, "s1")
        val applied = result as ScheduleTransitions.ScheduleDeactivationResult.Applied
        assertFalse(applied.newState.deactivatedSchedules.contains("s1"))
        assertFalse(applied.newState.activeSchedules.contains("s1"))
    }

    // ─── applyTimedModeDeactivation ────────────────────────────────────────

    @Test
    fun applyTimedModeDeactivation_alreadyInactive_returnsSentinel() {
        val m = mode("m1")
        val state = AppState(modes = listOf(m)) // m1 not in activeModes

        val result = ScheduleTransitions.applyTimedModeDeactivation(state, "m1")
        assertTrue(result is ScheduleTransitions.TimedDeactivationResult.AlreadyInactive)
        assertSame(state, result.newState)
    }

    @Test
    fun applyTimedModeDeactivation_clearsModeAndUserTimer() {
        val m = mode("m1")
        val state = AppState(
            modes = listOf(m),
            activeModes = setOf("m1"),
            manuallyActivatedModes = setOf("m1"),
            timedModeDeactivations = mapOf("m1" to 1_000_000L)
        )

        val result = ScheduleTransitions.applyTimedModeDeactivation(state, "m1")
        val applied = result as ScheduleTransitions.TimedDeactivationResult.Applied
        assertFalse(applied.newState.activeModes.contains("m1"))
        assertFalse(applied.newState.manuallyActivatedModes.contains("m1"))
        assertFalse(applied.newState.timedModeDeactivations.containsKey("m1"))
    }

    @Test
    fun applyTimedModeDeactivation_cascadesScheduleWhenAllLinkedModesInactive() {
        // Schedule s1 links only m1. m1 expires → s1 should cascade off.
        val m = mode("m1")
        val s = dailySchedule("s1", listOf("m1"))
        val state = AppState(
            modes = listOf(m),
            schedules = listOf(s),
            activeModes = setOf("m1"),
            activeSchedules = setOf("s1"),
            timedModeDeactivations = mapOf("m1" to 1_000_000L)
        )

        val result = ScheduleTransitions.applyTimedModeDeactivation(state, "m1")
        val applied = result as ScheduleTransitions.TimedDeactivationResult.Applied
        assertEquals(setOf("s1"), applied.deactivatedScheduleIds)
        assertFalse(applied.newState.activeSchedules.contains("s1"))
        assertTrue(applied.newState.deactivatedSchedules.contains("s1"))
    }

    @Test
    fun applyTimedModeDeactivation_doesNotCascadeWhenOtherLinkedModeActive() {
        // Schedule s1 links m1 + m2; only m1 expires. s1 stays active.
        val m1 = mode("m1")
        val m2 = mode("m2")
        val s = dailySchedule("s1", listOf("m1", "m2"))
        val state = AppState(
            modes = listOf(m1, m2),
            schedules = listOf(s),
            activeModes = setOf("m1", "m2"),
            activeSchedules = setOf("s1"),
            timedModeDeactivations = mapOf("m1" to 1_000_000L)
        )

        val result = ScheduleTransitions.applyTimedModeDeactivation(state, "m1")
        val applied = result as ScheduleTransitions.TimedDeactivationResult.Applied
        assertTrue(applied.deactivatedScheduleIds.isEmpty())
        assertTrue(applied.newState.activeSchedules.contains("s1")) // still active
        assertTrue(applied.newState.activeModes.contains("m2"))
    }

    @Test
    fun applyTimedModeDeactivation_doesNotCascadeWhenScheduleNotActive() {
        // Schedule isn't in activeSchedules to begin with → can't cascade.
        val m = mode("m1")
        val s = dailySchedule("s1", listOf("m1"))
        val state = AppState(
            modes = listOf(m),
            schedules = listOf(s),
            activeModes = setOf("m1"),
            activeSchedules = emptySet(), // not active
            timedModeDeactivations = mapOf("m1" to 1_000_000L)
        )

        val result = ScheduleTransitions.applyTimedModeDeactivation(state, "m1")
        val applied = result as ScheduleTransitions.TimedDeactivationResult.Applied
        assertTrue(applied.deactivatedScheduleIds.isEmpty())
    }
}
