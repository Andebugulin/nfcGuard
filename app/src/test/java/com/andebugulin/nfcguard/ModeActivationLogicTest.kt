package com.andebugulin.nfcguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeActivationLogicTest {

    // ─── Builders ───────────────────────────────────────────────────────────

    private fun mode(
        id: String,
        blockMode: BlockMode = BlockMode.BLOCK_SELECTED
    ) = Mode(id = id, name = id, blockedApps = emptyList(), blockMode = blockMode)

    private fun dailySchedule(id: String, linkedModeIds: List<String>): Schedule {
        val dayTimes = (1..7).map { DayTime(it, 9, 0, 17, 0) }
        return Schedule(id = id, name = id, timeSlot = TimeSlot(dayTimes),
            linkedModeIds = linkedModeIds, hasEndTime = true)
    }

    // ─── applyModeActivation ────────────────────────────────────────────────

    @Test
    fun applyModeActivation_modeNotFound_returnsModeNotFoundUnchanged() {
        val state = AppState()
        val result = ModeActivationLogic.applyModeActivation(state, "ghost", null)
        assertTrue(result is ModeActivationLogic.ActivateModeResult.ModeNotFound)
        assertSame(state, result.newState)
    }

    @Test
    fun applyModeActivation_simple_addsModeAndManualFlag() {
        val m = mode("m1")
        val state = AppState(modes = listOf(m))

        val result = ModeActivationLogic.applyModeActivation(state, "m1", null)

        assertTrue(result is ModeActivationLogic.ActivateModeResult.Activated)
        assertEquals(setOf("m1"), result.newState.activeModes)
        assertEquals(setOf("m1"), result.newState.manuallyActivatedModes)
        assertFalse(result.newState.timedModeDeactivations.containsKey("m1"))
    }

    @Test
    fun applyModeActivation_withTimer_recordsDeactivationDeadline() {
        val m = mode("m1")
        val state = AppState(modes = listOf(m))
        val deadline = 5_000_000L

        val result = ModeActivationLogic.applyModeActivation(state, "m1", deadline)
        assertTrue(result is ModeActivationLogic.ActivateModeResult.Activated)
        assertEquals(deadline, result.newState.timedModeDeactivations["m1"])
    }

    @Test
    fun applyModeActivation_blockAllowConflict_returnsConflictUnchanged() {
        val active = mode("m1", BlockMode.BLOCK_SELECTED)
        val newer = mode("m2", BlockMode.ALLOW_SELECTED)
        val state = AppState(modes = listOf(active, newer), activeModes = setOf("m1"))

        val result = ModeActivationLogic.applyModeActivation(state, "m2", null)
        assertTrue(result is ModeActivationLogic.ActivateModeResult.Conflict)
        assertEquals("m2", (result as ModeActivationLogic.ActivateModeResult.Conflict).modeName)
        assertSame(state, result.newState) // state untouched
    }

    @Test
    fun applyModeActivation_samePolarity_noConflict() {
        // Two BLOCK_SELECTED modes coexist fine.
        val m1 = mode("m1", BlockMode.BLOCK_SELECTED)
        val m2 = mode("m2", BlockMode.BLOCK_SELECTED)
        val state = AppState(modes = listOf(m1, m2), activeModes = setOf("m1"))

        val result = ModeActivationLogic.applyModeActivation(state, "m2", null)
        assertTrue(result is ModeActivationLogic.ActivateModeResult.Activated)
        assertEquals(setOf("m1", "m2"), result.newState.activeModes)
    }

    @Test
    fun applyModeActivation_clearsReactivationAndPausedForThisMode() {
        // m1 had a pending reactivation and paused-remaining (from a prior NFC unlock).
        // Manually activating clears both — the manual activation wins.
        val m = mode("m1")
        val state = AppState(
            modes = listOf(m),
            timedModeReactivations = mapOf("m1" to 1_000L),
            pausedModeRemainingMs = mapOf("m1" to 600_000L)
        )

        val result = ModeActivationLogic.applyModeActivation(state, "m1", null)
        assertTrue(result is ModeActivationLogic.ActivateModeResult.Activated)
        assertFalse(result.newState.timedModeReactivations.containsKey("m1"))
        assertFalse(result.newState.pausedModeRemainingMs.containsKey("m1"))
    }

    @Test
    fun applyModeActivation_emptyActiveSet_alwaysSucceeds() {
        // Conflict check only runs when there are existing active modes.
        val m = mode("m1", BlockMode.ALLOW_SELECTED)
        val state = AppState(modes = listOf(m)) // nothing active

        val result = ModeActivationLogic.applyModeActivation(state, "m1", null)
        assertTrue(result is ModeActivationLogic.ActivateModeResult.Activated)
    }

    // ─── applyModeDeactivation ──────────────────────────────────────────────

    @Test
    fun applyModeDeactivation_clearsAllPerModeState() {
        val m = mode("m1")
        val state = AppState(
            modes = listOf(m),
            activeModes = setOf("m1"),
            manuallyActivatedModes = setOf("m1"),
            timedModeDeactivations = mapOf("m1" to 1_000_000L),
            timedModeReactivations = mapOf("m1" to 2_000_000L),
            pausedModeRemainingMs = mapOf("m1" to 600_000L)
        )

        val result = ModeActivationLogic.applyModeDeactivation(state, "m1")
        assertFalse(result.newState.activeModes.contains("m1"))
        assertFalse(result.newState.manuallyActivatedModes.contains("m1"))
        assertFalse(result.newState.timedModeDeactivations.containsKey("m1"))
        assertFalse(result.newState.timedModeReactivations.containsKey("m1"))
        assertFalse(result.newState.pausedModeRemainingMs.containsKey("m1"))
    }

    @Test
    fun applyModeDeactivation_cascadesScheduleWhenItWasLastActiveLinkedMode() {
        val m = mode("m1")
        val sched = dailySchedule("s1", listOf("m1"))
        val state = AppState(
            modes = listOf(m),
            schedules = listOf(sched),
            activeModes = setOf("m1"),
            activeSchedules = setOf("s1")
        )

        val result = ModeActivationLogic.applyModeDeactivation(state, "m1")
        assertEquals(setOf("s1"), result.deactivatedScheduleIds)
        assertFalse(result.newState.activeSchedules.contains("s1"))
        assertTrue(result.newState.deactivatedSchedules.contains("s1"))
    }

    @Test
    fun applyModeDeactivation_doesNotCascadeWhenSiblingLinkedModeStillActive() {
        val m1 = mode("m1")
        val m2 = mode("m2")
        val sched = dailySchedule("s1", listOf("m1", "m2"))
        val state = AppState(
            modes = listOf(m1, m2),
            schedules = listOf(sched),
            activeModes = setOf("m1", "m2"),
            activeSchedules = setOf("s1")
        )

        val result = ModeActivationLogic.applyModeDeactivation(state, "m1")
        assertTrue(result.deactivatedScheduleIds.isEmpty())
        assertTrue(result.newState.activeSchedules.contains("s1"))
        assertTrue(result.newState.activeModes.contains("m2"))
    }

    @Test
    fun applyModeDeactivation_doesNotCascadeInactiveSchedule() {
        // Schedule wasn't in activeSchedules → cascade rule never even considers it.
        val m = mode("m1")
        val sched = dailySchedule("s1", listOf("m1"))
        val state = AppState(
            modes = listOf(m),
            schedules = listOf(sched),
            activeModes = setOf("m1"),
            activeSchedules = emptySet() // schedule not active
        )

        val result = ModeActivationLogic.applyModeDeactivation(state, "m1")
        assertTrue(result.deactivatedScheduleIds.isEmpty())
        assertFalse(result.newState.deactivatedSchedules.contains("s1"))
    }

    // ─── applyManualScheduleActivation ──────────────────────────────────────

    @Test
    fun applyManualScheduleActivation_scheduleNotFound() {
        val state = AppState()
        val result = ModeActivationLogic.applyManualScheduleActivation(state, "ghost")
        assertTrue(result is ModeActivationLogic.ManualScheduleActivationResult.ScheduleNotFound)
        assertSame(state, result.newState)
    }

    @Test
    fun applyManualScheduleActivation_simple_activatesAllLinkedModes() {
        val m1 = mode("m1")
        val m2 = mode("m2")
        val s = dailySchedule("s1", listOf("m1", "m2"))
        val state = AppState(modes = listOf(m1, m2), schedules = listOf(s))

        val result = ModeActivationLogic.applyManualScheduleActivation(state, "s1")
        assertTrue(result is ModeActivationLogic.ManualScheduleActivationResult.Activated)
        assertEquals(setOf("m1", "m2"), result.newState.activeModes)
        assertEquals(setOf("s1"), result.newState.activeSchedules)
    }

    @Test
    fun applyManualScheduleActivation_anyConflict_rejectsAll() {
        // Strict semantics: m1 (BLOCK) is active. Schedule s1 links m2 (ALLOW)
        // and m3 (BLOCK). m2 conflicts → reject the whole schedule (including m3).
        // Contrast with ScheduleTransitions.applyScheduleActivation which would
        // skip m2 and activate m3.
        val activeBlock = mode("m1", BlockMode.BLOCK_SELECTED)
        val allow = mode("m2", BlockMode.ALLOW_SELECTED)
        val anotherBlock = mode("m3", BlockMode.BLOCK_SELECTED)
        val s = dailySchedule("s1", listOf("m2", "m3"))
        val state = AppState(
            modes = listOf(activeBlock, allow, anotherBlock),
            schedules = listOf(s),
            activeModes = setOf("m1")
        )

        val result = ModeActivationLogic.applyManualScheduleActivation(state, "s1")
        assertTrue(result is ModeActivationLogic.ManualScheduleActivationResult.Conflict)
        assertSame(state, result.newState)
    }

    @Test
    fun applyManualScheduleActivation_clearsDeactivatedFlagAndReactivations() {
        val m = mode("m1")
        val s = dailySchedule("s1", listOf("m1"))
        val state = AppState(
            modes = listOf(m),
            schedules = listOf(s),
            deactivatedSchedules = setOf("s1"),
            timedModeReactivations = mapOf("m1" to 1_000L),
            pausedModeRemainingMs = mapOf("m1" to 600_000L)
        )

        val result = ModeActivationLogic.applyManualScheduleActivation(state, "s1")
        assertTrue(result is ModeActivationLogic.ManualScheduleActivationResult.Activated)
        assertFalse(result.newState.deactivatedSchedules.contains("s1"))
        assertFalse(result.newState.timedModeReactivations.containsKey("m1"))
        assertFalse(result.newState.pausedModeRemainingMs.containsKey("m1"))
    }

    @Test
    fun applyManualScheduleActivation_emptyLinkedModesAndEmptyActive_succeedsAsNoop() {
        // Schedule with no linked modes, no active modes. Manual activation
        // should succeed: just mark the schedule active.
        val s = dailySchedule("s1", linkedModeIds = emptyList())
        val state = AppState(schedules = listOf(s))

        val result = ModeActivationLogic.applyManualScheduleActivation(state, "s1")
        assertTrue(result is ModeActivationLogic.ManualScheduleActivationResult.Activated)
        assertTrue(result.newState.activeModes.isEmpty())
        assertTrue(result.newState.activeSchedules.contains("s1"))
    }
}
