package com.andebugulin.nfcguard.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
        compose.onNodeWithText("NOT LINKED TO ANY MODES").assertIsDisplayed()
    }

    @Test fun `leaves registration mode off until asked`() {
        show()
        assertEquals(false, registrationMode.value)
    }
}
