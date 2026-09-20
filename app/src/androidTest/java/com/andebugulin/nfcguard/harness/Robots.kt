package com.andebugulin.nfcguard.harness

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.espresso.Espresso
import com.andebugulin.nfcguard.ui.TestTags
import com.andebugulin.nfcguard.ui.modes.AppInfo

/**
 * Page objects over the real app's screens.
 *
 * Interaction targets are selected by `testTag` (see [TestTags]); text is kept
 * only for assertions genuinely *about* wording — headings, explanatory copy,
 * validation messages. That split matters here because the UI renders names,
 * titles and status messages `.uppercase()`, gives several actions two labels
 * depending on state, and stacks dialogs whose buttons share labels with the
 * screen behind them.
 *
 * Two conventions the tag-based selectors make unnecessary, kept in the base
 * for the text assertions that remain:
 *
 *  - **Lists are `LazyColumn`s and sheets are `verticalScroll` columns**, so a
 *    node may exist without being on screen. [scrollTo] checks *displayed*,
 *    never mere presence — a presence check never scrolls a `verticalScroll`
 *    column, and a tap aimed below the fold then lands off-screen silently.
 *  - **Dialogs animate in**, so a button is composed a frame or two before it
 *    is visible. [assertVisible] waits for display.
 */
abstract class Robot(protected val compose: ComposeTestRule) {

    // ---- tag-based interaction (preferred) ----

    protected fun tapTag(tag: String) {
        scrollToTag(tag)
        compose.onAllNodes(hasTestTag(tag)).onFirst().performClick()
        compose.waitForIdle()
    }

    protected fun typeInTag(tag: String, value: String) {
        scrollToTag(tag)
        compose.onAllNodes(hasTestTag(tag)).onFirst().performTextInput(value)
        compose.waitForIdle()
    }

    protected fun replaceInTag(tag: String, value: String) {
        scrollToTag(tag)
        val field = compose.onAllNodes(hasTestTag(tag)).onFirst()
        field.performTextClearance()
        field.performTextInput(value)
        compose.waitForIdle()
    }

    protected fun assertTagEnabled(tag: String) {
        scrollToTag(tag)
        compose.onAllNodes(hasTestTag(tag)).onFirst().assertIsEnabled()
    }

    protected fun assertTagDisabled(tag: String) {
        scrollToTag(tag)
        compose.onAllNodes(hasTestTag(tag)).onFirst().assertIsNotEnabled()
    }

    protected fun tagExists(tag: String) =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    fun assertTagPresent(tag: String) = apply {
        compose.onAllNodes(hasTestTag(tag)).onFirst().assertExists()
    }

    fun assertTagAbsent(tag: String) = apply {
        compose.onAllNodes(hasTestTag(tag)).assertCountEquals(0)
    }

    private fun scrollToTag(tag: String) {
        if (runCatching {
                compose.onAllNodes(hasTestTag(tag)).onFirst().assertIsDisplayed()
            }.isSuccess
        ) return
        scrollWith(hasTestTag(tag))
    }

    // ---- text-based assertions (for wording, not identity) ----

    protected fun isDisplayed(text: String) = runCatching {
        compose.onAllNodesWithText(text).onFirst().assertIsDisplayed()
    }.isSuccess

    protected fun scrollTo(text: String) {
        if (isDisplayed(text)) return
        scrollWith(hasText(text))
    }

    private fun scrollWith(matcher: SemanticsMatcher) {
        runCatching {
            val inDialog = compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog()))
            val scroller =
                if (inDialog.fetchSemanticsNodes().isNotEmpty()) inDialog.onFirst()
                else compose.onNode(hasScrollAction())
            scroller.performScrollToNode(matcher)
            compose.waitForIdle()
        }
    }

    protected fun waitFor(text: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    fun assertVisible(text: String) = apply {
        scrollTo(text)
        runCatching {
            compose.waitUntil(5_000) { isDisplayed(text) }
        }
        compose.onAllNodesWithText(text).onFirst().assertIsDisplayed()
    }

    fun assertPresent(text: String) = apply {
        compose.onAllNodesWithText(text).onFirst().assertExists()
    }

    fun assertAbsent(text: String) = apply {
        compose.onAllNodesWithText(text).assertCountEquals(0)
    }

    protected fun tap(text: String) {
        scrollTo(text)
        val clickable = compose.onAllNodes(hasText(text) and hasClickAction())
        if (clickable.fetchSemanticsNodes().isNotEmpty()) clickable.onFirst().performClick()
        else compose.onAllNodesWithText(text).onFirst().performClick()
        compose.waitForIdle()
    }

    /** `MainNavigation`'s BackHandler returns to Home from any sub-screen. */
    fun back() = apply {
        Espresso.pressBack()
        compose.waitForIdle()
    }
}

class HomeRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertOnHome() = apply { assertVisible("NFCGUARD") }
    fun openModes() = ModesRobot(compose).also { tapTag(TestTags.Home.NAV_MODES) }
    fun openSchedules() = SchedulesRobot(compose).also { tapTag(TestTags.Home.NAV_SCHEDULES) }
    fun openNfcTags() = NfcTagsRobot(compose).also { tapTag(TestTags.Home.NAV_NFC_TAGS) }
    fun openEmergencyReset() = EmergencyResetRobot(compose).also { tapTag(TestTags.Home.EMERGENCY_RESET) }
    fun openSettings() = SettingsRobot(compose).also { tapTag(TestTags.Home.SETTINGS) }
}

class ModesRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertOnModes() = apply { assertVisible("MODES") }
    fun assertModeListed(name: String) = apply { assertVisible(name.uppercase()) }

    /** One handle covers both the empty-state and in-list add buttons. */
    fun openAddDialog() = apply { tapTag(TestTags.Modes.ADD) }

    fun typeModeName(name: String) = apply { typeInTag(TestTags.Modes.NAME_INPUT, name) }
    fun assertCannotCreate() = apply { assertTagDisabled(TestTags.Modes.NAME_CONFIRM) }
    fun assertCanCreate() = apply { assertTagEnabled(TestTags.Modes.NAME_CONFIRM) }
    fun confirmCreate() = apply { tapTag(TestTags.Modes.NAME_CONFIRM) }
    fun assertDuplicateNameRejected() = apply {
        assertVisible("A mode with this name already exists")
        assertCannotCreate()
    }

    fun openEditor(modeId: String) = ModeEditorRobot(compose).also {
        tapTag(TestTags.Modes.edit(modeId))
    }

    fun deleteMode(modeId: String) = apply {
        tapTag(TestTags.Modes.delete(modeId))
        tapTag(TestTags.Modes.DELETE_CONFIRM)
    }

    /**
     * Naming a mode does not persist it: the name dialog hands off to the
     * editor, and only its SAVE writes through the ViewModel. SAVE also stays
     * disabled until at least one app is picked.
     */
    fun createMode(name: String, withApp: AppInfo): ModesRobot {
        openAddDialog()
        typeModeName(name)
        confirmCreate()
        ModeEditorRobot(compose).pickApp(withApp).save()
        return this
    }

    /**
     * The card's ACTIVATE opens a duration dialog; the dialog's own ACTIVATE
     * commits. The default option is "until NFC tag".
     */
    fun activate(modeId: String) = apply {
        tapTag(TestTags.Modes.activate(modeId))
        tapTag(TestTags.Modes.ACTIVATE_CONFIRM)
    }

    fun assertActive() = apply { assertVisible("ACTIVE") }
}

/** The app-picking editor a mode is created or edited through. */
class ModeEditorRobot(compose: ComposeTestRule) : Robot(compose) {

    fun assertOnEditor() = apply { assertVisible("NFC TAG LOCK") }

    /**
     * Narrows the list, then picks the row by package.
     *
     * Takes the whole [AppInfo] because the two halves are not interchangeable:
     * the search field filters on the app's *label*, while the row's tag is its
     * *package*. Searching is not optional — the picker lists every launchable
     * app on the device, and in a `LazyColumn` a row far down the list does not
     * exist to be scrolled to until the list is short enough to compose it.
     */
    fun pickApp(app: AppInfo) = apply {
        typeInTag(TestTags.ModeEditor.SEARCH, app.appName)
        compose.waitUntil(10_000) { tagExists(TestTags.ModeEditor.appRow(app.packageName)) }
        tapTag(TestTags.ModeEditor.appRow(app.packageName))
    }

    fun save() = apply { tapTag(TestTags.ModeEditor.SAVE) }

    fun assertTagLimit(tagId: String, shown: String) = apply {
        assertTagPresent(TestTags.ModeEditor.tagLimit(tagId))
        assertPresent(shown)
    }

    fun toggleTag(tagId: String) = apply { tapTag(TestTags.ModeEditor.tagRow(tagId)) }
    fun openLimitFor(tagId: String) = apply { tapTag(TestTags.ModeEditor.tagLimit(tagId)) }

    fun choosePermanent() = apply { tapTag(TestTags.ModeEditor.LIMIT_PERMANENT) }
    fun chooseLimited() = apply { tapTag(TestTags.ModeEditor.LIMIT_TIMED) }
    fun setLimitHours(value: String) = apply { replaceInTag(TestTags.ModeEditor.LIMIT_HOURS, value) }
    fun setLimitMinutes(value: String) = apply { replaceInTag(TestTags.ModeEditor.LIMIT_MINUTES, value) }
    fun assertLimitFields(hours: String, minutes: String) = apply {
        assertPresent(hours); assertPresent(minutes)
    }
    fun applyLimit() = apply { tapTag(TestTags.ModeEditor.LIMIT_APPLY) }
    fun cancelLimit() = apply { tapTag(TestTags.ModeEditor.LIMIT_CANCEL) }

    /** SAVE warns first when every selected tag is capped. */
    fun assertNoPermanentUnlockWarning() = apply { assertVisible("NO PERMANENT UNLOCK") }
    fun saveAnyway() = apply { tapTag(TestTags.ModeEditor.NO_PERMANENT_SAVE_ANYWAY) }
    fun dismissWarning() = apply { tap("CANCEL") }
}

/**
 * The lost-tag escape hatch: warning → (attention challenge, only when modes
 * are active) → tag selection → deactivate every mode and delete the chosen
 * tags. The only flow in the app that can legitimately switch blocking off.
 */
class EmergencyResetRobot(compose: ComposeTestRule) : Robot(compose) {

    fun assertWarningShown() = apply { assertVisible("LOST NFC TAG?") }
    fun continueFromWarning() = apply { tapTag(TestTags.Emergency.WARNING_CONTINUE) }
    fun cancelWarning() = apply { tapTag(TestTags.Emergency.WARNING_CANCEL) }

    fun assertChallengeRequired() = apply { assertTagPresent(TestTags.Challenge.GIVE_UP) }
    fun assertChallengeSkipped() = apply { assertTagAbsent(TestTags.Challenge.GIVE_UP) }
    fun giveUpChallenge() = apply { tapTag(TestTags.Challenge.GIVE_UP) }

    fun assertTagSelectionShown() = apply { assertVisible("SELECT LOST TAGS") }
    fun assertTagSelectionNotShown() = apply { assertAbsent("SELECT LOST TAGS") }
    fun selectLostTag(tagId: String) = apply { tapTag(TestTags.Emergency.lostTag(tagId)) }
    fun confirmReset() = apply { tapTag(TestTags.Emergency.TAG_SELECTION_CONFIRM) }
    fun cancelTagSelection() = apply { tapTag(TestTags.Emergency.TAG_SELECTION_CANCEL) }
}

/**
 * The settings sheet. Only the challenge-duration dialog is driven here — it
 * holds the MIN/SEC fields, so it cannot run under Robolectric; the rest of the
 * sheet is covered far more cheaply by `SettingsDialogTest`.
 */
class SettingsRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertOnSettings() = apply { assertVisible("SETTINGS") }

    fun openChallengeDuration() = apply {
        tapTag(TestTags.Settings.CHALLENGE_DURATION_ROW)
        assertTagPresent(TestTags.Settings.DURATION_APPLY)
    }

    fun setMinutes(value: String) = apply { replaceInTag(TestTags.Settings.DURATION_MINUTES, value) }
    fun setSeconds(value: String) = apply { replaceInTag(TestTags.Settings.DURATION_SECONDS, value) }
    fun assertCannotApply() = apply { assertTagDisabled(TestTags.Settings.DURATION_APPLY) }
    fun assertCanApply() = apply { assertTagEnabled(TestTags.Settings.DURATION_APPLY) }
    fun assertBelowMinimumWarned() = apply { assertVisible("Minimum is 1:30") }
    fun applyDuration() = apply { tapTag(TestTags.Settings.DURATION_APPLY) }
    fun done() = apply { tapTag(TestTags.Settings.DONE) }
}

/** The five-page intro carousel shown on a genuinely first-run device. */
class OnboardingRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertOnFirstPage() = apply { assertVisible("DIGITAL WELLBEING") }
    fun next() = apply { tap("NEXT") }
    fun getStarted() = apply { tap("GET STARTED") }
}

class SchedulesRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertOnSchedules() = apply { assertVisible("SCHEDULES") }
    fun assertScheduleListed(name: String) = apply { assertVisible(name.uppercase()) }

    /** One handle covers both the empty-state and in-list create buttons. */
    fun openEditor() = apply {
        tapTag(TestTags.Schedules.ADD)
        assertVisible("NEW SCHEDULE")
    }

    fun openEditorFor(scheduleId: String) = apply {
        tapTag(TestTags.Schedules.edit(scheduleId))
        assertVisible("EDIT SCHEDULE")
    }

    fun typeName(name: String) = apply { typeInTag(TestTags.Schedules.EDITOR_NAME, name) }
    fun renameTo(name: String) = apply { replaceInTag(TestTags.Schedules.EDITOR_NAME, name) }
    fun toggleDay(day: Int) = apply { tapTag(TestTags.Schedules.day(day)) }
    fun toggleMode(modeId: String) = apply { tapTag(TestTags.Schedules.linkedMode(modeId)) }

    fun assertCannotSubmit() = apply { assertTagDisabled(TestTags.Schedules.EDITOR_CONFIRM) }
    fun assertCanSubmit() = apply { assertTagEnabled(TestTags.Schedules.EDITOR_CONFIRM) }
    fun assertDuplicateNameRejected() = apply {
        assertVisible("A schedule with this name already exists")
        assertCannotSubmit()
    }

    fun create() = apply { tapTag(TestTags.Schedules.EDITOR_CONFIRM) }
    fun saveEdit() = apply { tapTag(TestTags.Schedules.EDITOR_CONFIRM) }
    fun cancelEditor() = apply { tap("CANCEL") }

    fun deleteSchedule(scheduleId: String) = apply { tapTag(TestTags.Schedules.delete(scheduleId)) }
    fun assertDeleteConfirmShown() = apply { assertVisible("DELETE SCHEDULE?") }
    fun confirmDelete() = apply { tapTag(TestTags.Schedules.DELETE_CONFIRM) }
    fun cancelDelete() = apply { tap("CANCEL") }

    fun assertChallengeRequired() = apply { assertTagPresent(TestTags.Challenge.GIVE_UP) }
    fun assertChallengeSkipped() = apply { assertTagAbsent(TestTags.Challenge.GIVE_UP) }
    fun giveUpChallenge() = apply { tapTag(TestTags.Challenge.GIVE_UP) }

    // ---- per-day times ----

    fun enableCustomEndTimes() = apply { tapTag(TestTags.Schedules.EDITOR_CUSTOM_END_TIMES) }

    fun openStartTime(day: Int) = TimePickerRobot(compose).also {
        tapTag(TestTags.Schedules.startTime(day))
    }

    fun openEndTime(day: Int) = TimePickerRobot(compose).also {
        tapTag(TestTags.Schedules.endTime(day))
    }

    fun assertTimeShown(shown: String) = apply { assertVisible(shown) }
    fun assertEndTimeRejected() = apply { assertVisible("End time must be after start time") }
}

/**
 * The clock picker, reached from a day's start or end time.
 *
 * Its own maths is covered far more cheaply on the JVM by
 * `ModernTimePickerDialogTest`. What only a device run shows is that a time
 * chosen here survives into the saved schedule.
 *
 * SET and CANCEL are tagged because the schedule editor underneath has its own
 * CANCEL, and both sit inside a dialog — `isDialog()` cannot separate them, and
 * dismissing the wrong one closes the whole editor.
 */
class TimePickerRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertSelectingHour() = apply { assertVisible("SELECT HOUR") }
    fun assertSelectingMinute() = apply { assertVisible("SELECT MINUTE") }

    /** Taps a mark on the face; the numerals are not text-transformed. */
    fun pick(value: String) = apply { tap(value) }

    fun set() = apply { tapTag(TestTags.TimePicker.SET) }
    fun cancel() = apply { tapTag(TestTags.TimePicker.CANCEL) }
}

class NfcTagsRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertOnNfcTags() = apply { assertVisible("NFC TAGS") }

    /** One handle covers both the empty-state and in-list register buttons. */
    fun beginRegistration() = apply {
        tapTag(TestTags.NfcTags.REGISTER)
        assertVisible("TAP NFC TAG")
    }

    fun assertTagDetected() = apply { assertVisible("TAG DETECTED") }
    fun assertAlreadyRegistered() = apply { assertVisible("TAG ALREADY REGISTERED") }
    fun assertCannotRegister() = apply { assertTagDisabled(TestTags.NfcTags.REGISTER_CONFIRM) }
    fun assertDuplicateNameRejected() = apply {
        assertVisible("A tag with this name already exists")
        assertCannotRegister()
    }

    fun nameTag(name: String) = apply { typeInTag(TestTags.NfcTags.REGISTER_NAME_INPUT, name) }
    fun confirmRegistration() = apply { tapTag(TestTags.NfcTags.REGISTER_CONFIRM) }
    fun cancelDialog() = apply { tap("CANCEL") }
    fun assertTagListed(name: String) = apply { assertVisible(name.uppercase()) }

    fun renameTag(tagId: String, to: String) = apply {
        tapTag(TestTags.NfcTags.rename(tagId))
        replaceInTag(TestTags.NfcTags.RENAME_INPUT, to)
        tapTag(TestTags.NfcTags.RENAME_SAVE)
    }
}

class UnlockDialogRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertShown() = apply { assertVisible("HOW LONG SHOULD IT STAY UNLOCKED?") }
    fun assertNotShown() = apply { assertAbsent("HOW LONG SHOULD IT STAY UNLOCKED?") }
    fun assertPermanentOffered() = apply { assertTagPresent(TestTags.Unlock.PERMANENT_OPTION) }
    fun assertPermanentNotOffered() = apply { assertTagAbsent(TestTags.Unlock.PERMANENT_OPTION) }
    fun assertTimedOnly() = apply { assertVisible("TEMPORARY BREAK"); assertPermanentNotOffered() }
    fun deselectMode(modeId: String) = apply { tapTag(TestTags.Unlock.modeRow(modeId)) }
    fun setHours(value: String) = apply { replaceInTag(TestTags.Unlock.HOURS, value) }
    fun confirmUnlock() = apply { tapTag(TestTags.Unlock.CONFIRM) }
    fun cancel() = apply { tap("CANCEL") }
}
