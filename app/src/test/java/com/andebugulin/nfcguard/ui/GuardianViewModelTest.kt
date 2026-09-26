package com.andebugulin.nfcguard.ui

import com.andebugulin.nfcguard.ActivationResult
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.DayTime
import com.andebugulin.nfcguard.TimeSlot
import com.andebugulin.nfcguard.data.AppStateRepository
import com.andebugulin.nfcguard.data.ConfigManager
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.resetAppStateRepository
import com.andebugulin.nfcguard.testing.schedule
import com.andebugulin.nfcguard.testing.tag

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The ViewModel is a thin orchestrator over the repository, so these tests
 * target the parts that are not just delegation: the safe-regime settings that
 * deliberately live outside AppState, and the import paths with their
 * orphan-cleanup pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GuardianViewModelTest {

    /** A hung test should name itself rather than stall the whole task. */
    @get:Rule val timeout: Timeout = Timeout.seconds(15)

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: GuardianViewModel

    private fun prefs() = app.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        resetAppStateRepository()
        grantOverlayPermission()
        prefs().edit().clear().commit()
        vm = GuardianViewModel(app)
    }

    @After fun tearDown() {
        // init starts an endless 5s polling loop on viewModelScope; leaving it
        // alive stops the test JVM from settling.
        runCatching {
            val clear = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("clear")
            clear.isAccessible = true
            clear.invoke(vm)
        }
        Dispatchers.resetMain()
    }

    // ─── modes ────────────────────────────────────────────────────────────

    @Test fun `addMode persists a mode`() {
        vm.addMode("Focus", listOf("com.a"))

        val m = AppStateRepository.getInstance(app).current.modes.single()
        assertEquals("Focus", m.name)
        assertEquals(listOf("com.a"), m.blockedApps)
    }

    @Test fun `deleteMode removes it and clears it from active state`() {
        vm.addMode("Focus", listOf("com.a"))
        val id = AppStateRepository.getInstance(app).current.modes.single().id

        vm.activateMode(id)
        vm.deleteMode(id)

        val state = AppStateRepository.getInstance(app).current
        assertEquals(0, state.modes.size)
        assertFalse(state.activeModes.contains(id))
    }

    @Test fun `activating two conflicting block modes is rejected`() {
        vm.addMode("Blocklist", listOf("com.a"), BlockMode.BLOCK_SELECTED)
        vm.addMode("Allowlist", listOf("com.b"), BlockMode.ALLOW_SELECTED)
        val modes = AppStateRepository.getInstance(app).current.modes

        assertEquals(ActivationResult.SUCCESS, vm.activateMode(modes[0].id))
        assertEquals(ActivationResult.BLOCK_MODE_CONFLICT, vm.activateMode(modes[1].id))
    }

    @Test fun `activating an unknown mode reports MODE_NOT_FOUND`() {
        assertEquals(ActivationResult.MODE_NOT_FOUND, vm.activateMode("ghost"))
    }

    // ─── safe regime lives outside AppState ───────────────────────────────

    @Test fun `safe regime defaults to enabled`() {
        assertTrue(vm.safeRegimeEnabled.value)
    }

    @Test fun `safe regime is stored in prefs, not AppState`() {
        vm.setSafeRegimeEnabled(false)

        assertFalse(vm.safeRegimeEnabled.value)
        assertFalse(prefs().getBoolean("safe_regime_enabled", true))
    }

    @Test fun `challenge duration cannot be lowered below the floor`() {
        vm.setChallengeDurationSeconds(10)
        assertEquals(GuardianViewModel.CHALLENGE_MIN_SECONDS, vm.challengeDurationSeconds.value)
    }

    /**
     * The floor and the starting value are separate on purpose: a fresh
     * install begins at the longer default, and the floor is only how far it
     * may be wound down.
     */
    @Test fun `a fresh install starts above the floor`() {
        assertEquals(GuardianViewModel.CHALLENGE_DEFAULT_SECONDS, vm.challengeDurationSeconds.value)
        assertTrue(
            "the default must not sit on the floor",
            GuardianViewModel.CHALLENGE_DEFAULT_SECONDS > GuardianViewModel.CHALLENGE_MIN_SECONDS
        )
    }

    @Test fun `challenge duration can be raised`() {
        vm.setChallengeDurationSeconds(300)
        assertEquals(300, vm.challengeDurationSeconds.value)
    }

    @Test fun `an imported config cannot disable the safety challenge`() {
        vm.setSafeRegimeEnabled(true)

        vm.importConfig(ConfigManager.ExportData(1, emptyList(), emptyList(), emptyList()))

        assertTrue("import must never weaken the safety gate", vm.safeRegimeEnabled.value)
    }

    // ─── import ───────────────────────────────────────────────────────────

    @Test fun `replace import overwrites config and resets runtime state`() {
        vm.addMode("Old", listOf("com.old"))
        val oldId = AppStateRepository.getInstance(app).current.modes.single().id
        vm.activateMode(oldId)

        vm.importConfig(ConfigManager.ExportData(
            1, listOf(mode(id = "new", name = "New")), emptyList(), emptyList()
        ), mergeMode = false)

        val state = AppStateRepository.getInstance(app).current
        assertEquals(listOf("new"), state.modes.map { it.id })
        assertTrue("runtime state must reset on replace", state.activeModes.isEmpty())
    }

    @Test fun `merge import replaces matching ids and appends new ones`() {
        vm.importConfig(ConfigManager.ExportData(
            1, listOf(mode(id = "m1", name = "Original")), emptyList(), emptyList()
        ), mergeMode = false)

        vm.importConfig(ConfigManager.ExportData(
            1, listOf(mode(id = "m1", name = "Replaced"), mode(id = "m2", name = "Added")),
            emptyList(), emptyList()
        ), mergeMode = true)

        val modes = AppStateRepository.getInstance(app).current.modes
        assertEquals(2, modes.size)
        assertEquals("Replaced", modes.single { it.id == "m1" }.name)
        assertEquals("Added", modes.single { it.id == "m2" }.name)
    }

    @Test fun `import drops tag references that no longer exist`() {
        vm.importConfig(ConfigManager.ExportData(
            version = 1,
            modes = listOf(mode(id = "m1", tagIds = listOf("ghost-tag", "real-tag"))),
            schedules = emptyList(),
            nfcTags = listOf(tag(id = "real-tag"))
        ), mergeMode = false)

        val m = AppStateRepository.getInstance(app).current.modes.single()
        assertEquals("orphan tag reference must be cleaned up", listOf("real-tag"), m.nfcTagIds)
    }

    // ─── schedules and tags ───────────────────────────────────────────────

    @Test fun `addSchedule persists it`() {
        vm.addSchedule("Work", TimeSlot(listOf(DayTime(1, 9, 0, 17, 0))), listOf("m1"), true)

        assertEquals("Work", AppStateRepository.getInstance(app).current.schedules.single().name)
    }

    @Test fun `addNfcTag rejects a duplicate id`() {
        assertTrue(vm.addNfcTag("tag-1", "Desk"))
        assertFalse("same tag must not register twice", vm.addNfcTag("tag-1", "Desk again"))
    }

    @Test fun `deleteNfcTag removes it`() {
        vm.addNfcTag("tag-1", "Desk")
        vm.deleteNfcTag("tag-1")

        assertEquals(0, AppStateRepository.getInstance(app).current.nfcTags.size)
    }

    // ─── NFC unlock ───────────────────────────────────────────────────────

    @Test fun `scanning an unknown tag raises no pending unlock`() {
        vm.handleNfcTag("unknown-tag")
        assertNull(vm.pendingUnlock.value)
    }

    @Test fun `scanning a linked tag on an active mode raises a pending unlock`() {
        vm.importConfig(ConfigManager.ExportData(
            1, listOf(mode(id = "m1", tagIds = listOf("t1"))), emptyList(), listOf(tag(id = "t1"))
        ), mergeMode = false)
        vm.activateMode("m1")

        vm.handleNfcTag("t1")

        assertNotNull(vm.pendingUnlock.value)
        assertTrue(vm.pendingUnlock.value!!.modeIds.contains("m1"))
    }

    @Test fun `confirming an unlock deactivates the mode and clears the pending state`() {
        vm.importConfig(ConfigManager.ExportData(
            1, listOf(mode(id = "m1", tagIds = listOf("t1"))), emptyList(), listOf(tag(id = "t1"))
        ), mergeMode = false)
        vm.activateMode("m1")
        vm.handleNfcTag("t1")

        vm.confirmUnlock()

        assertNull(vm.pendingUnlock.value)
        assertFalse(AppStateRepository.getInstance(app).current.activeModes.contains("m1"))
    }
}
