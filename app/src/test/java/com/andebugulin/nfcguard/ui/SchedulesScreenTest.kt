package com.andebugulin.nfcguard.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.data.ConfigManager
import com.andebugulin.nfcguard.testing.cancelViewModel
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.resetAppStateRepository
import com.andebugulin.nfcguard.testing.schedule
import com.andebugulin.nfcguard.ui.schedules.SchedulesScreen
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class SchedulesScreenTest {

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

    private fun show() = compose.setContent {
        MinimalistTheme { SchedulesScreen(viewModel = vm, onBack = {}) }
    }

    @Test fun `renders the schedules header`() {
        show()
        compose.onNodeWithText("SCHEDULES").assertIsDisplayed()
    }

    @Test fun `tells the user to create modes first when none exist`() {
        show()
        // A schedule is meaningless without a mode to link, so the screen
        // steers the user to modes rather than offering an unusable form.
        compose.onNodeWithText("CREATE MODES FIRST").assertIsDisplayed()
    }

    @Test fun `offers schedule creation once a mode exists`() {
        vm.importConfig(ConfigManager.ExportData(
            1, listOf(mode(id = "m1", name = "Focus")), emptyList(), emptyList()
        ))
        show()
        compose.onAllNodesWithText("CREATE SCHEDULE", substring = true).onFirst().assertIsDisplayed()
    }

    @Test fun `lists an existing schedule by name`() {
        vm.importConfig(ConfigManager.ExportData(
            1,
            listOf(mode(id = "m1", name = "Focus")),
            listOf(schedule(id = "s1", name = "Work Hours", modeIds = listOf("m1"))),
            emptyList()
        ))
        show()
        compose.onAllNodesWithText("WORK HOURS", substring = true).onFirst().assertIsDisplayed()
    }

    @Test fun `renders several schedules`() {
        vm.importConfig(ConfigManager.ExportData(
            1,
            listOf(mode(id = "m1", name = "Focus")),
            listOf(
                schedule(id = "s1", name = "Work Hours", modeIds = listOf("m1")),
                schedule(id = "s2", name = "Sleep Time", modeIds = listOf("m1"), day = 2)
            ),
            emptyList()
        ))
        show()
        compose.onAllNodesWithText("WORK HOURS", substring = true).onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("SLEEP TIME", substring = true).onFirst().assertIsDisplayed()
    }
}
