package com.andebugulin.nfcguard.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.data.ConfigManager
import com.andebugulin.nfcguard.testing.cancelViewModel
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.resetAppStateRepository
import com.andebugulin.nfcguard.testing.schedule
import com.andebugulin.nfcguard.ui.modes.ModesScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ModesScreenTest {

    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var vm: GuardianViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetAppStateRepository()
        grantOverlayPermission()
        vm = GuardianViewModel(app)
    }

    @After fun tearDown() {
        cancelViewModel(vm)
        Dispatchers.resetMain()
    }

    private fun seed(vararg modes: com.andebugulin.nfcguard.Mode) =
        vm.importConfig(ConfigManager.ExportData(1, modes.toList(), emptyList(), emptyList()))

    private fun show(onBack: () -> Unit = {}) =
        compose.setContent { MinimalistTheme { ModesScreen(viewModel = vm, onBack = onBack) } }

    @Test fun `renders the modes header`() {
        show()
        compose.onNodeWithText("MODES").assertIsDisplayed()
    }

    @Test fun `offers mode creation when the list is empty`() {
        show()
        compose.onNodeWithText("CREATE MODE").assertIsDisplayed()
    }

    @Test fun `lists an existing mode by name`() {
        seed(mode(id = "m1", name = "Deep Work"))
        show()
        compose.onNodeWithText("DEEP WORK").assertIsDisplayed()   // rendered .uppercase()
    }

    @Test fun `lists several modes`() {
        seed(mode(id = "m1", name = "Deep Work"), mode(id = "m2", name = "Sleep"))
        show()
        compose.onNodeWithText("DEEP WORK").assertIsDisplayed()   // rendered .uppercase()
        compose.onNodeWithText("SLEEP").assertIsDisplayed()
    }

    @Test fun `shows the polarity of an allowlist mode`() {
        seed(mode(id = "m1", name = "Allowlist", blockMode = BlockMode.ALLOW_SELECTED))
        show()
        // Rendered as one string, e.g. "1 APPS · ALLOW ONLY".
        compose.onNodeWithText("ALLOW ONLY", substring = true).assertIsDisplayed()
    }

    @Test fun `shows the polarity of a blocklist mode`() {
        seed(mode(id = "m1", name = "Blocklist", blockMode = BlockMode.BLOCK_SELECTED))
        show()
        // "BLOCK" occurs in several nodes; the badge is enough.
        compose.onAllNodesWithText("BLOCK", substring = true).onFirst().assertIsDisplayed()
    }

    @Test fun `marks an active mode as active`() {
        seed(mode(id = "m1", name = "Deep Work"))
        vm.activateMode("m1")
        show()
        compose.onNodeWithText("ACTIVE").assertIsDisplayed()
    }

    @Test fun `back navigates away`() {
        var backs = 0
        seed(mode(id = "m1", name = "Deep Work"))
        show(onBack = { backs++ })
        compose.onNodeWithText("MODES").assertIsDisplayed()
        assertEquals(0, backs)
    }

    // ---------------- dialog branches ----------------
    //
    // The suites above assert each screen renders and its primary controls
    // work. The create/edit/delete dialogs and their validation were the
    // "largely unexercised" gap in TESTS.md.
    //
    // Two mechanics these need:
    //
    //  - **Dialogs holding a text field never reach idle.** The field's cursor
    //    blink is an endless animation, so the auto-advancing clock spins until
    //    Espresso throws AppNotIdleException. Same fix as HomeScreen's endless
    //    LaunchedEffect loops: drive the clock by hand.
    //  - **The add-mode button has two forms.** "CREATE MODE" in the empty
    //    state, "+ NEW MODE" at the foot of the list once modes exist — and the
    //    latter is below the fold in a LazyColumn.
    //  - Dialog controls are matched with `isDialog()` because their labels are
    //    reused by the screen behind them: the mode card's own DELETE sits
    //    under the delete dialog's DELETE.

    // A dialog containing an OutlinedTextField never reaches idle under
    // Robolectric — Compose spins recomposing until Espresso gives up with
    // AppNotIdleException, whatever the graphics mode, and `autoAdvance = false`
    // does not help because the stall is in composition, not the clock. (An
    // identical dialog *without* a text field is fine.) So every branch that
    // needs typing — the create/rename/register dialogs and the unlock dialog's
    // capped path — is covered on a real device in
    // `app/src/androidTest/.../DialogFlowsEndToEndTest.kt`, where it works.

    private fun inDialog(text: String) =
        compose.onAllNodes(hasAnyAncestor(isDialog()) and hasText(text)).onFirst()

    /** Hand-driven clock, for anything that opens a dialog with a text field. */
    private fun showManualClock() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MinimalistTheme { ModesScreen(viewModel = vm, onBack = {}) } }
        settle()
    }

    private fun settle() = repeat(4) { compose.mainClock.advanceTimeByFrame() }

    @Test fun `the delete dialog names the schedules that would be affected`() {
        vm.importConfig(ConfigManager.ExportData(
            1,
            listOf(mode(id = "m1", name = "Deep Work")),
            listOf(schedule(id = "s1", name = "Work Hours", modeIds = listOf("m1"))),
            emptyList()
        ))
        show()
        compose.onNodeWithText("DELETE").performClick()

        compose.onNodeWithText("LINKED SCHEDULES AFFECTED:").assertIsDisplayed()
        compose.onAllNodesWithText("WORK HOURS", substring = true).onFirst().assertIsDisplayed()
    }

    @Test fun `confirming the delete dialog removes the mode`() {
        seed(mode(id = "m1", name = "Deep Work"))
        show()
        compose.onNodeWithText("DELETE").performClick()

        inDialog("DELETE").performClick()

        assertEquals(emptyList<String>(), vm.appState.value.modes.map { it.id })
    }

    @Test fun `cancelling the delete dialog keeps the mode`() {
        seed(mode(id = "m1", name = "Deep Work"))
        show()
        compose.onNodeWithText("DELETE").performClick()

        inDialog("CANCEL").performClick()

        assertEquals(listOf("m1"), vm.appState.value.modes.map { it.id })
    }

    @Test fun `activating offers both an NFC-held and a timed option`() {
        seed(mode(id = "m1", name = "Deep Work"))
        showManualClock()
        compose.onNodeWithText("ACTIVATE").performClick()
        settle()

        compose.onNodeWithText("UNTIL NFC TAG").assertIsDisplayed()
        compose.onNodeWithText("FOR A SET DURATION").assertIsDisplayed()
    }

    @Test fun `the activation dialog does not activate anything until confirmed`() {
        seed(mode(id = "m1", name = "Deep Work"))
        showManualClock()
        compose.onNodeWithText("ACTIVATE").performClick()
        settle()

        assertEquals(emptySet<String>(), vm.appState.value.activeModes)

        inDialog("ACTIVATE").performClick()
        settle()

        assertEquals(setOf("m1"), vm.appState.value.activeModes)
    }
}
