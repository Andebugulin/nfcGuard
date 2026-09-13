package com.andebugulin.nfcguard.harness

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso

/**
 * Text-driven page objects over the real app's screens.
 *
 * The app sets no `testTag`s, so — like the Robolectric screen suites — these
 * key off visible text. Three app-wide conventions they encode once, so the
 * tests themselves stay readable:
 *
 *  - **Names render `.uppercase()`.** Callers pass "Deep Work"; robots uppercase.
 *  - **Lists are `LazyColumn`s**, so off-screen rows are never composed.
 *    [scrollTo] brings one into composition before asserting or clicking.
 *  - **A dialog's button label often also exists on the screen behind it**
 *    (the mode card's ACTIVATE under the activate dialog's ACTIVATE). Anything
 *    aimed at a dialog goes through [tapInDialog], which scopes the match with
 *    `isDialog()` instead of gambling on node order.
 */
abstract class Robot(protected val compose: ComposeTestRule) {

    /**
     * Waits for [text] to exist in the tree.
     *
     * `waitForIdle` is not enough on its own: screens that load from disk or
     * PackageManager do so on a background dispatcher Compose knows nothing
     * about (the mode editor's app picker is the case that matters), so the
     * tree is briefly settled *and* empty.
     */
    protected fun waitFor(text: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun tap(text: String) {
        scrollTo(text)
        click(hasText(text))
    }

    protected fun tapInDialog(text: String) {
        click(hasAnyAncestor(isDialog()) and hasText(text))
    }

    /**
     * Clicks the node that actually carries the click action.
     *
     * A label usually matches *two* nodes: the clickable container (whose merged
     * semantics include the label) and the inner `Text`. Taking `onFirst()`
     * blindly can land on the `Text`, and the tap is then silently swallowed —
     * the unlock dialog's mode rows behaved exactly that way, reporting success
     * while nothing toggled. So prefer a node with a click action, and fall
     * back to plain text only for labels that are genuinely not interactive.
     */
    private fun click(matcher: SemanticsMatcher) {
        val clickable = compose.onAllNodes(matcher and hasClickAction())
        if (clickable.fetchSemanticsNodes().isNotEmpty()) {
            clickable.onFirst().performClick()
        } else {
            compose.onAllNodes(matcher).onFirst().performClick()
        }
        compose.waitForIdle()
    }

    protected fun typeInDialog(value: String) {
        compose.onNode(hasAnyAncestor(isDialog()) and hasSetTextAction()).performTextInput(value)
        compose.waitForIdle()
    }

    /**
     * No-op when the screen does not scroll, or the node is already composed.
     *
     * Prefers a scrollable *inside* an open dialog: a tall dialog (the schedule
     * editor) sits over a scrollable screen, and scrolling the screen behind it
     * would never bring the dialog's own content into view.
     */
    protected fun scrollTo(text: String) {
        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
        runCatching {
            val inDialog = compose.onAllNodes(hasScrollAction() and hasAnyAncestor(isDialog()))
            val scroller =
                if (inDialog.fetchSemanticsNodes().isNotEmpty()) inDialog.onFirst()
                else compose.onNode(hasScrollAction())
            scroller.performScrollToNode(hasText(text))
            compose.waitForIdle()
        }
    }

    fun assertVisible(text: String) = apply {
        scrollTo(text)
        compose.onAllNodesWithText(text).onFirst().assertIsDisplayed()
    }

    /**
     * Asserts the node is in the tree without requiring it on screen. The
     * unlock dialog with several modes listed overflows the viewport, so an
     * option can be present-and-clipped — and presence is what the behaviour
     * under test is about. Pairs with [assertAbsent], which is also
     * existence-based, so the two are symmetric.
     */
    fun assertPresent(text: String) = apply {
        compose.onAllNodesWithText(text).onFirst().assertExists()
    }

    fun assertAbsent(text: String) = apply {
        compose.onAllNodesWithText(text).assertCountEquals(0)
    }

    protected fun assertDialogButtonDisabled(text: String) {
        compose.onAllNodes(hasAnyAncestor(isDialog()) and hasText(text)).onFirst().assertIsNotEnabled()
    }

    protected fun assertDialogButtonEnabled(text: String) {
        compose.onAllNodes(hasAnyAncestor(isDialog()) and hasText(text)).onFirst().assertIsEnabled()
    }

    protected fun clearDialogText() {
        compose.onNode(hasAnyAncestor(isDialog()) and hasSetTextAction()).performTextClearance()
        compose.waitForIdle()
    }

    /** Dialogs with more than one field (the unlock dialog's HOURS/MINUTES). */
    protected fun setDurationField(index: Int, value: String) {
        val field = compose.onAllNodes(hasAnyAncestor(isDialog()) and hasSetTextAction())[index]
        field.performTextClearance()
        field.performTextInput(value)
        compose.waitForIdle()
    }

    /** `MainNavigation`'s BackHandler returns to Home from any sub-screen. */
    fun back() = apply {
        Espresso.pressBack()
        compose.waitForIdle()
    }
}

class HomeRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertOnHome() = apply { assertVisible("GUARDIAN") }

    /** The trash icon in the header; the only node carrying this description. */
    fun openEmergencyReset() = EmergencyResetRobot(compose).also {
        compose.onNodeWithContentDescription("Emergency Reset").performClick()
        compose.waitForIdle()
    }
    fun openModes() = ModesRobot(compose).also { tap("MODES") }
    fun openSchedules() = SchedulesRobot(compose).also { tap("SCHEDULES") }
    fun openNfcTags() = NfcTagsRobot(compose).also { tap("NFC TAGS") }
}

class ModesRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertOnModes() = apply { assertVisible("MODES") }
    fun assertModeListed(name: String) = apply { assertVisible(name.uppercase()) }

    /**
     * The add-mode button has two forms: "CREATE MODE" in the empty state,
     * "+ NEW MODE" at the foot of the list once modes exist.
     */
    fun openAddDialog() = apply {
        if (compose.onAllNodesWithText("CREATE MODE").fetchSemanticsNodes().isNotEmpty()) {
            tap("CREATE MODE")
        } else {
            tap("+ NEW MODE")
        }
    }

    fun typeModeName(name: String) = apply { typeInDialog(name) }
    fun assertCannotCreate() = apply { assertDialogButtonDisabled("CREATE") }
    fun assertCanCreate() = apply { assertDialogButtonEnabled("CREATE") }
    fun assertDuplicateNameRejected() = apply {
        assertVisible("A mode with this name already exists")
        assertCannotCreate()
    }
    fun confirmCreate() = apply { tapInDialog("CREATE") }

    fun openEditor(name: String) = ModeEditorRobot(compose).also {
        scrollTo(name.uppercase()); tap("EDIT")
    }

    fun deleteMode() = apply { tap("DELETE"); tapInDialog("DELETE") }

    /**
     * Naming a mode does not persist it: the name dialog hands off to
     * [ModeEditorScreen], and only its SAVE writes through the ViewModel.
     * SAVE also stays disabled until at least one app is picked, so the caller
     * must supply an app label the picker will list — see
     * [GuardianHarness.aBlockableApp].
     */
    fun createMode(name: String, withApp: String): ModesRobot {
        tap("CREATE MODE")
        typeInDialog(name)
        tapInDialog("CREATE")
        ModeEditorRobot(compose).pickApp(withApp).save()
        return this
    }

    /**
     * The card's ACTIVATE opens a duration dialog; the dialog's own ACTIVATE
     * commits. The default option is "until NFC tag", which is the state the
     * unlock flow needs.
     */
    fun activate(name: String) = apply {
        scrollTo(name.uppercase())
        tap("ACTIVATE")
        tapInDialog("ACTIVATE")
    }

    fun assertActive() = apply { assertVisible("ACTIVE") }
}

/**
 * The five-page intro carousel shown on a genuinely first-run device. Its last
 * page hands off to [com.andebugulin.nfcguard.ui.onboarding.PermissionOnboarding]
 * — the handoff that crashed in issue #12.
 */
class OnboardingRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertOnFirstPage() = apply { assertVisible("DIGITAL WELLBEING") }
    fun next() = apply { tap("NEXT") }
    fun getStarted() = apply { tap("GET STARTED") }
}

/**
 * The app-picking editor a mode is created or edited through. It is a full
 * screen composed *over* ModesScreen rather than a dialog, so its controls are
 * matched unscoped.
 */
class ModeEditorRobot(compose: ComposeTestRule) : Robot(compose) {

    /**
     * Narrows the list with the search field first — the picker lists every
     * launchable app on the device, and the target may be far below the fold.
     *
     * The list is loaded off the main thread, so wait for it before typing:
     * filtering an empty list matches nothing.
     */
    fun pickApp(label: String) = apply {
        waitFor("SEARCH APPS...")
        compose.onNode(hasSetTextAction()).performTextInput(label)
        compose.waitForIdle()
        waitFor(label.uppercase())
        tap(label.uppercase())
    }

    fun save() = apply { tap("SAVE") }

    fun assertOnEditor() = apply { assertVisible("NFC TAG LOCK") }

    /** Tag rows sit below the app picker, so they need scrolling into view. */
    fun assertTagLimit(tagName: String, shown: String) = apply {
        scrollTo(tagName.uppercase())
        assertPresent(shown)
    }

    fun toggleTag(tagName: String) = apply { tap(tagName.uppercase()) }

    /**
     * Opens the limit dialog for one tag's row.
     *
     * Every row shows "PERMANENT" until a limit is set, so the label alone is
     * ambiguous. The limit button and the name+checkbox block are siblings
     * inside the row, so "the clickable whose sibling carries this name"
     * identifies it without depending on row order.
     */
    fun openLimitFor(tagName: String) = apply {
        scrollTo(tagName.uppercase())
        compose.onAllNodes(hasClickAction() and hasAnySibling(hasText(tagName.uppercase())))
            .onFirst().performClick()
        compose.waitForIdle()
    }

    fun choosePermanent() = apply { tapInDialog("PERMANENT UNLOCK") }
    fun chooseLimited() = apply { tapInDialog("MAX DURATION LIMIT") }
    fun setLimitHours(value: String) = apply { setDurationField(0, value) }
    fun setLimitMinutes(value: String) = apply { setDurationField(1, value) }
    fun assertLimitFields(hours: String, minutes: String) = apply {
        assertPresent(hours); assertPresent(minutes)
    }
    fun applyLimit() = apply { tapInDialog("APPLY") }
    fun cancelLimit() = apply { tapInDialog("CANCEL") }

    /** SAVE refuses silently when every selected tag is capped; it warns first. */
    fun assertNoPermanentUnlockWarning() = apply { assertVisible("NO PERMANENT UNLOCK") }
    fun saveAnyway() = apply { tapInDialog("SAVE ANYWAY") }
    fun dismissWarning() = apply { tapInDialog("CANCEL") }
}

/**
 * The lost-tag escape hatch: warning → (attention challenge, only when modes are
 * active) → tag selection → deactivate every mode and delete the chosen tags.
 *
 * This is the one flow in the app that can legitimately switch blocking off, so
 * the branch deciding whether the challenge is required is the highest-value
 * assertion here.
 */
class EmergencyResetRobot(compose: ComposeTestRule) : Robot(compose) {

    fun assertWarningShown() = apply { assertVisible("LOST NFC TAG?") }
    fun continueFromWarning() = apply { tapInDialog("CONTINUE") }
    fun cancelWarning() = apply { tapInDialog("CANCEL") }

    fun assertChallengeRequired() = apply { assertVisible("SAFE REGIME") }
    fun assertChallengeSkipped() = apply { assertAbsent("SAFE REGIME") }
    fun giveUpChallenge() = apply { tap("GIVE UP") }

    fun assertTagSelectionShown() = apply { assertVisible("SELECT LOST TAGS") }
    fun assertTagSelectionNotShown() = apply { assertAbsent("SELECT LOST TAGS") }
    fun selectLostTag(name: String) = apply { tap(name.uppercase()) }
    fun confirmReset() = apply { tapInDialog("CONFIRM") }
    fun cancelTagSelection() = apply { tapInDialog("CANCEL") }

    /**
     * Sits out the real attention challenge, pressing "I'M HERE" each time the
     * check phase opens (every 15s, for 5s) until tag selection appears.
     *
     * Slow on purpose — the gate is 90 seconds by design and its floor is
     * raise-only, so it genuinely cannot be shortened from a test.
     *
     * **Never wait here with `Thread.sleep`.** A Compose test rule drives the
     * clock the dialog's `delay(1000)` ticks on, and that clock only advances
     * while the test framework is pumping. Sleeping on the test thread freezes
     * the countdown mid-dialog — on screen it sits at 1:30 forever, which looks
     * exactly like an app-side race and is not one. `waitUntil` keeps the
     * framework pumping, so the dialog ticks in real time as a user sees it.
     */
    fun passChallenge(timeoutMs: Long = 150_000) = apply {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (exists("SELECT LOST TAGS")) return@apply
            check(!exists("CHALLENGE FAILED")) {
                "a check window was missed, so the challenge failed instead of completing"
            }
            runCatching {
                compose.waitUntil(2_000) {
                    exists("I'M HERE") || exists("SELECT LOST TAGS") || exists("CHALLENGE FAILED")
                }
            }
            if (exists("I'M HERE")) {
                compose.onAllNodesWithText("I'M HERE").onFirst().performClick()
                compose.waitForIdle()
            }
        }
        error("the challenge did not complete within ${timeoutMs}ms")
    }

    private fun exists(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}

class SchedulesRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertOnSchedules() = apply { assertVisible("SCHEDULES") }
    fun assertScheduleListed(name: String) = apply { assertVisible(name.uppercase()) }

    /** "CREATE SCHEDULE" in the empty state, "+ NEW SCHEDULE" once one exists. */
    fun openEditor() = apply {
        if (compose.onAllNodesWithText("CREATE SCHEDULE").fetchSemanticsNodes().isNotEmpty()) {
            tap("CREATE SCHEDULE")
        } else {
            tap("+ NEW SCHEDULE")
        }
        assertVisible("NEW SCHEDULE")
    }

    fun openEditorFor(name: String) = apply {
        scrollTo(name.uppercase())
        tap("EDIT")
        assertVisible("EDIT SCHEDULE")
    }

    fun typeName(name: String) = apply { typeInDialog(name) }

    /**
     * Replaces an existing name. `performTextInput` inserts at the cursor,
     * which sits at offset 0 in a prefilled field, so typing alone would
     * prepend rather than append.
     */
    fun renameTo(name: String) = apply {
        clearDialogText()
        typeInDialog(name)
    }

    fun toggleDay(day: String) = apply { tap(day.uppercase()) }
    fun toggleMode(name: String) = apply { tap(name.uppercase()) }

    fun assertCannotSubmit() = apply { assertDialogButtonDisabled("CREATE") }
    fun assertCanSubmit() = apply { assertDialogButtonEnabled("CREATE") }
    fun assertDuplicateNameRejected() = apply {
        assertVisible("A schedule with this name already exists")
        assertCannotSubmit()
    }

    fun create() = apply { tapInDialog("CREATE") }
    fun saveEdit() = apply { tapInDialog("SAVE") }
    fun cancelEditor() = apply { tapInDialog("CANCEL") }

    fun deleteSchedule(name: String) = apply {
        scrollTo(name.uppercase())
        tap("DELETE")
    }
    fun assertDeleteConfirmShown() = apply { assertVisible("DELETE SCHEDULE?") }
    fun confirmDelete() = apply { tapInDialog("DELETE") }
    fun cancelDelete() = apply { tapInDialog("CANCEL") }

    fun assertChallengeRequired() = apply { assertVisible("SAFE REGIME") }
    fun assertChallengeSkipped() = apply { assertAbsent("SAFE REGIME") }
    fun giveUpChallenge() = apply { tap("GIVE UP") }
}

class NfcTagsRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertOnNfcTags() = apply { assertVisible("NFC TAGS") }

    /**
     * Opening the register dialog is what arms `nfcRegistrationMode`.
     *
     * Like the add-mode button, this one has two forms: "REGISTER TAG" in the
     * empty state and "+ REGISTER TAG" at the foot of the list once tags exist.
     * Exact text matching keeps them distinct, so try the empty-state one first.
     */
    fun beginRegistration() = apply {
        if (compose.onAllNodesWithText("REGISTER TAG").fetchSemanticsNodes().isNotEmpty()) {
            tap("REGISTER TAG")
        } else {
            tap("+ REGISTER TAG")
        }
        assertVisible("TAP NFC TAG")
    }

    fun assertTagDetected() = apply { assertVisible("TAG DETECTED") }
    fun assertAlreadyRegistered() = apply { assertVisible("TAG ALREADY REGISTERED") }
    fun assertCannotRegister() = apply { assertDialogButtonDisabled("REGISTER") }
    fun assertDuplicateNameRejected() = apply {
        assertVisible("A tag with this name already exists")
        assertCannotRegister()
    }
    fun cancelDialog() = apply { tapInDialog("CANCEL") }

    fun renameTag(to: String) = apply {
        tap("RENAME")
        clearDialogText()
        typeInDialog(to)
        tapInDialog("SAVE")
    }

    /** The dialog holds one text field: the tag's name. REGISTER stays disabled until it is set. */
    fun nameTag(name: String) = apply { typeInDialog(name) }
    fun confirmRegistration() = apply { tapInDialog("REGISTER") }
    fun assertTagListed(name: String) = apply { assertVisible(name.uppercase()) }
}

class UnlockDialogRobot(compose: ComposeTestRule) : Robot(compose) {
    fun assertShown() = apply { assertVisible("HOW LONG SHOULD IT STAY UNLOCKED?") }
    fun assertPermanentOffered() = apply { assertPresent("PERMANENTLY") }
    fun assertPermanentNotOffered() = apply { assertAbsent("PERMANENTLY") }
    fun assertTimedOnly() = apply { assertVisible("TEMPORARY BREAK"); assertPermanentNotOffered() }
    fun deselectMode(name: String) = apply { tap(name.uppercase()) }
    fun setHours(value: String) = apply { setDurationField(0, value) }
    fun assertNotShown() = apply { assertAbsent("HOW LONG SHOULD IT STAY UNLOCKED?") }
    fun confirmUnlock() = apply { tapInDialog("UNLOCK") }
    fun cancel() = apply { tapInDialog("CANCEL") }
}
