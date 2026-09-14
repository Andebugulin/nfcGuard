package com.andebugulin.nfcguard

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.andebugulin.nfcguard.harness.GuardianHarness
import com.andebugulin.nfcguard.harness.HomeRobot
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.schedule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Building and editing a schedule through `ScheduleEditorDialog`.
 *
 * `SchedulesScreen` was the largest screen with the least dialog coverage: the
 * Robolectric suite asserted the empty-state gate and that schedules list, but
 * nothing exercised the editor, so nothing checked that a schedule assembled in
 * the UI reaches `AppStateRepository` with the right days, times and links.
 *
 * On device because the editor holds a name field, and a Compose dialog with a
 * text field never reaches idle under Robolectric (see TESTS.md). The clock
 * picker it opens has no field and is covered far more cheaply on the JVM by
 * `ModernTimePickerDialogTest`.
 */
class ScheduleEditorEndToEndTest {

    /** `getDayName` maps 1..7 to MONDAY..SUNDAY. */
    private val MONDAY = 1
    private val TUESDAY = 2


    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var harness: GuardianHarness

    @Before fun setUp() {
        harness = GuardianHarness(compose)
        harness.resetToOnboardedHome()
    }

    @After fun tearDown() {
        harness.close()
        harness.resetToOnboardedHome()
    }

    private fun seedMode() =
        harness.seedConfig(modes = listOf(mode(id = "m1", name = "Deep Work")))

    private fun seedModeAndSchedule() = harness.seedConfig(
        modes = listOf(mode(id = "m1", name = "Deep Work")),
        schedules = listOf(schedule(id = "s1", name = "Work Hours", modeIds = listOf("m1")))
    )

    private fun schedules() = harness.state.schedules

    // ---------------- creating ----------------

    @Test fun aScheduleBuiltInTheEditorReachesTheRepository() {
        seedMode()
        harness.launch()

        HomeRobot(compose).openSchedules()
            .openEditor()
            .typeName("Work Hours")
            .toggleDay(MONDAY)
            .toggleMode("m1")
            .create()

        val saved = schedules().single()
        assertEquals("Work Hours", saved.name)
        assertEquals(listOf("m1"), saved.linkedModeIds)
        assertEquals("the selected day is the one stored", listOf(1), saved.timeSlot.days)
    }

    @Test fun aScheduleNeedsAName() {
        seedMode()
        harness.launch()

        HomeRobot(compose).openSchedules()
            .openEditor()
            .toggleDay(MONDAY)
            .toggleMode("m1")
            .assertCannotSubmit()
    }

    @Test fun aScheduleNeedsADay() {
        seedMode()
        harness.launch()

        HomeRobot(compose).openSchedules()
            .openEditor()
            .typeName("Work Hours")
            .toggleMode("m1")
            .assertCannotSubmit()
    }

    @Test fun aScheduleNeedsAtLeastOneLinkedMode() {
        seedMode()
        harness.launch()

        // A schedule linked to nothing would fire and do nothing at all.
        HomeRobot(compose).openSchedules()
            .openEditor()
            .typeName("Work Hours")
            .toggleDay(MONDAY)
            .assertCannotSubmit()
    }

    @Test fun allThreeTogetherAreEnoughToSubmit() {
        seedMode()
        harness.launch()

        HomeRobot(compose).openSchedules()
            .openEditor()
            .typeName("Work Hours")
            .toggleDay(MONDAY)
            .toggleMode("m1")
            .assertCanSubmit()
    }

    @Test fun aDuplicateScheduleNameIsRejected() {
        seedModeAndSchedule()
        harness.launch()

        HomeRobot(compose).openSchedules()
            .openEditor()
            .typeName("Work Hours")
            .assertDuplicateNameRejected()
    }

    @Test fun cancellingTheEditorCreatesNothing() {
        seedMode()
        harness.launch()

        HomeRobot(compose).openSchedules()
            .openEditor()
            .typeName("Work Hours")
            .toggleDay(MONDAY)
            .toggleMode("m1")
            .cancelEditor()

        assertTrue(schedules().isEmpty())
    }

    @Test fun severalDaysCanBeSelectedForOneSchedule() {
        seedMode()
        harness.launch()

        HomeRobot(compose).openSchedules()
            .openEditor()
            .typeName("Weekdays")
            .toggleDay(MONDAY)
            .toggleDay(TUESDAY)
            .toggleMode("m1")
            .create()

        assertEquals(listOf(1, 2), schedules().single().timeSlot.days.sorted())
    }

    @Test fun aDayCanBeDeselectedAgain() {
        seedMode()
        harness.launch()

        HomeRobot(compose).openSchedules()
            .openEditor()
            .typeName("Tuesdays")
            .toggleDay(MONDAY)
            .toggleDay(TUESDAY)
            .toggleDay(MONDAY)   // toggled back off
            .toggleMode("m1")
            .create()

        assertEquals(listOf(2), schedules().single().timeSlot.days)
    }

    // ---------------- deleting ----------------

    @Test fun deletingAScheduleAsksFirstAndThenRemovesIt() {
        seedModeAndSchedule()
        harness.launch()

        val screen = HomeRobot(compose).openSchedules()
        screen.deleteSchedule("s1").assertDeleteConfirmShown().confirmDelete()

        assertTrue("the schedule should be gone", schedules().isEmpty())
    }

    @Test fun cancellingADeleteKeepsTheSchedule() {
        seedModeAndSchedule()
        harness.launch()

        val screen = HomeRobot(compose).openSchedules()
        screen.deleteSchedule("s1").assertDeleteConfirmShown().cancelDelete()

        assertEquals(listOf("s1"), schedules().map { it.id })
    }

    // ---------------- the challenge gate ----------------

    /**
     * Editing a schedule while a mode is active can switch blocking off, so it
     * goes through the attention challenge.
     *
     * Note the contrast with the emergency reset, which is gated
     * *unconditionally*: this gate reads `safeRegimeEnabled`
     * (`if (safeRegimeEnabled && appState.activeModes.isNotEmpty())`). Both
     * behaviours are pinned so neither is "harmonised" into the other by
     * accident.
     */
    @Test fun editingWhileAModeIsActiveRequiresTheChallenge() {
        seedModeAndSchedule()
        harness.launch()
        HomeRobot(compose).openModes().activate("m1").back()

        HomeRobot(compose).openSchedules().openEditorFor("s1")
            .renameTo("Evening Hours")
            .saveEdit()
            .assertChallengeRequired()

        assertEquals(
            "nothing may be written before the challenge passes",
            "Work Hours", schedules().single().name
        )
    }

    @Test fun givingUpThatChallengeLeavesTheScheduleUnchanged() {
        seedModeAndSchedule()
        harness.launch()
        HomeRobot(compose).openModes().activate("m1").back()

        HomeRobot(compose).openSchedules().openEditorFor("s1")
            .renameTo("Evening Hours")
            .saveEdit()
            .assertChallengeRequired()
            .giveUpChallenge()

        assertEquals("Work Hours", schedules().single().name)
    }

    @Test fun withNoModeActiveTheEditSavesWithoutAChallenge() {
        seedModeAndSchedule()
        harness.launch()

        HomeRobot(compose).openSchedules().openEditorFor("s1")
            .renameTo("Evening Hours")
            .saveEdit()
            .assertChallengeSkipped()

        assertEquals("Evening Hours", schedules().single().name)
    }

    /** Unlike the emergency reset, this gate is opt-out via the safe-regime toggle. */
    @Test fun turningSafeRegimeOffRemovesTheChallengeFromScheduleEdits() {
        seedModeAndSchedule()
        harness.disableSafeRegime()
        harness.launch()
        HomeRobot(compose).openModes().activate("m1").back()

        HomeRobot(compose).openSchedules().openEditorFor("s1")
            .renameTo("Evening Hours")
            .saveEdit()
            .assertChallengeSkipped()

        assertEquals("Evening Hours", schedules().single().name)
    }

    // ---------------- per-day times ----------------
    //
    // The clock picker's own behaviour is covered on the JVM; what only a real
    // run can show is that a time chosen in it survives into the saved schedule.

    private fun newSchedule() = HomeRobot(compose).openSchedules()
        .openEditor()
        .typeName("Work Hours")
        .toggleDay(MONDAY)
        .toggleMode("m1")

    @Test fun aSelectedDayStartsAtNineByDefault() {
        seedMode()
        harness.launch()

        newSchedule().assertTimeShown("09:00").create()

        val day = schedules().single().timeSlot.dayTimes.single()
        assertEquals(9, day.startHour)
        assertEquals(0, day.startMinute)
    }

    @Test fun aStartTimeChosenOnTheClockIsSaved() {
        seedMode()
        harness.launch()

        val editor = newSchedule()
        editor.openStartTime(MONDAY).assertSelectingHour().pick("7").set()
        editor.assertTimeShown("07:00").create()

        assertEquals(7, schedules().single().timeSlot.dayTimes.single().startHour)
    }

    @Test fun cancellingTheClockLeavesTheTimeAlone() {
        seedMode()
        harness.launch()

        val editor = newSchedule()
        editor.openStartTime(MONDAY).pick("7").cancel()

        editor.assertTimeShown("09:00")
    }

    /** Without the toggle a schedule runs to the end of the day. */
    @Test fun aScheduleWithoutCustomEndTimesRunsToMidnight() {
        seedMode()
        harness.launch()

        newSchedule().create()

        val saved = schedules().single()
        assertFalse("hasEndTime should stay off", saved.hasEndTime)
        val day = saved.timeSlot.dayTimes.single()
        assertEquals(23, day.endHour)
        assertEquals(59, day.endMinute)
    }

    @Test fun customEndTimesRevealAnEndTimePerDay() {
        seedMode()
        harness.launch()

        newSchedule().enableCustomEndTimes().assertTimeShown("UNTIL")
    }

    @Test fun anEndTimeChosenOnTheClockIsSaved() {
        seedMode()
        harness.launch()

        val editor = newSchedule().enableCustomEndTimes()
        // The end time defaults to 23:59; move it to 17:00.
        editor.openEndTime(MONDAY).pick("17").set()
        editor.create()

        val saved = schedules().single()
        assertTrue("the toggle should be recorded", saved.hasEndTime)
        assertEquals(17, saved.timeSlot.dayTimes.single().endHour)
    }

    /** An end before the start would describe a window that never opens. */
    @Test fun anEndTimeBeforeTheStartIsRejected() {
        seedMode()
        harness.launch()

        val editor = newSchedule().enableCustomEndTimes()
        editor.openEndTime(MONDAY).pick("7").set()   // 07:00, before the 09:00 start

        editor.create()
        editor.assertEndTimeRejected()
        assertTrue("nothing may be saved while the window is impossible", schedules().isEmpty())
    }
}
