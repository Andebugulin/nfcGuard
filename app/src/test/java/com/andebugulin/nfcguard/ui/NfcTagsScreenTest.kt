package com.andebugulin.nfcguard.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.data.ConfigManager
import com.andebugulin.nfcguard.testing.cancelViewModel
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.resetAppStateRepository
import com.andebugulin.nfcguard.testing.tag
import com.andebugulin.nfcguard.ui.nfc.NfcTagsScreen
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
class NfcTagsScreenTest {

    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var vm: GuardianViewModel

    private val scanned = mutableStateOf<String?>(null)
    private val registrationMode = mutableStateOf(false)

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

    private fun show() = compose.setContent {
        MinimalistTheme {
            NfcTagsScreen(
                viewModel = vm,
                scannedNfcTagId = scanned,
                nfcRegistrationMode = registrationMode,
                onBack = {}
            )
        }
    }

    @Test fun `renders the tags header`() {
        show()
        compose.onNodeWithText("NFC TAGS").assertIsDisplayed()
    }

    @Test fun `shows an empty state when nothing is registered`() {
        show()
        compose.onNodeWithText("NO NFC TAGS").assertIsDisplayed()
    }

    @Test fun `offers tag registration`() {
        show()
        compose.onNodeWithText("REGISTER TAG").assertIsDisplayed()
    }

    @Test fun `lists a registered tag by name`() {
        vm.importConfig(ConfigManager.ExportData(
            1, emptyList(), emptyList(), listOf(tag(id = "t1", name = "Kitchen Tag"))
        ))
        show()
        compose.onNodeWithText("KITCHEN TAG").assertIsDisplayed()   // rendered .uppercase()
    }

    @Test fun `says when a tag is linked to no modes`() {
        vm.importConfig(ConfigManager.ExportData(
            1, emptyList(), emptyList(), listOf(tag(id = "t1", name = "Kitchen Tag"))
        ))
        show()
        compose.onNodeWithText("UNLOCKS NOTHING YET").assertIsDisplayed()
    }

    @Test fun `leaves registration mode off until asked`() {
        show()
        assertEquals(false, registrationMode.value)
    }

    // ---------------- registration + dialog branches ----------------
    //
    // `scannedNfcTagId` is the hoisted state MainActivity writes a tag id into,
    // so a tap can be simulated here on the JVM by writing it directly. That
    // covers the registration screen's half of the flow; the Activity's intent
    // plumbing that fills it is covered on device by `NfcTapEndToEndTest`.
    //
    // The register dialog holds a text field, whose cursor blink is an endless
    // animation — an auto-advancing clock never reaches idle and Espresso
    // throws AppNotIdleException. So the clock is driven by hand, as
    // HomeScreenTest does for its endless LaunchedEffect loops.

    private fun showManualClock() {
        compose.mainClock.autoAdvance = false
        show()
        settle()
    }

    private fun settle() = repeat(4) { compose.mainClock.advanceTimeByFrame() }

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

    private fun seedTags(vararg tags: com.andebugulin.nfcguard.NfcTag) =
        vm.importConfig(ConfigManager.ExportData(1, emptyList(), emptyList(), tags.toList()))

    @Test fun `deleting a tag removes it`() {
        seedTags(tag(id = "t1", name = "Desk key"))
        showManualClock()
        compose.onNodeWithText("DELETE").performClick()
        settle()

        inDialog("DELETE").performClick()
        settle()

        assertEquals(emptyList<String>(), vm.appState.value.nfcTags.map { it.id })
    }

    @Test fun `cancelling a delete keeps the tag`() {
        seedTags(tag(id = "t1", name = "Desk key"))
        showManualClock()
        compose.onNodeWithText("DELETE").performClick()
        settle()

        inDialog("CANCEL").performClick()
        settle()

        assertEquals(listOf("t1"), vm.appState.value.nfcTags.map { it.id })
    }
}
