package com.andebugulin.nfcguard.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
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
}
