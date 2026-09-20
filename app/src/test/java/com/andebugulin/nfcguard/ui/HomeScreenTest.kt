package com.andebugulin.nfcguard.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.data.ConfigManager
import com.andebugulin.nfcguard.testing.cancelViewModel
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.resetAppStateRepository
import com.andebugulin.nfcguard.ui.home.HomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * HomeScreen runs two endless LaunchedEffect loops (a 2s permission refresh
 * and a 30s poll). Compose's default auto-advancing clock never reaches idle
 * with those running, so these tests drive the clock manually.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class HomeScreenTest {

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

    private fun show(onNavigate: (Screen) -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent { MinimalistTheme { HomeScreen(viewModel = vm, onNavigate = onNavigate) } }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test fun `renders the home screen`() {
        show()
        compose.onAllNodesWithText("nfcGuard", substring = true).onFirst().assertIsDisplayed()
    }

    @Test fun `renders with modes present`() {
        vm.importConfig(ConfigManager.ExportData(
            1, listOf(mode(id = "m1", name = "Focus")), emptyList(), emptyList()
        ))
        show()
        compose.onAllNodesWithText("nfcGuard", substring = true).onFirst().assertIsDisplayed()
    }

    @Test fun `renders with an active mode`() {
        vm.importConfig(ConfigManager.ExportData(
            1, listOf(mode(id = "m1", name = "Focus")), emptyList(), emptyList()
        ))
        vm.activateMode("m1")
        show()
        compose.onAllNodesWithText("ACTIVE", substring = true).onFirst().assertIsDisplayed()
    }
}
