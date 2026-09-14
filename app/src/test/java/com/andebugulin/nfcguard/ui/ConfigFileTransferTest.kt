package com.andebugulin.nfcguard.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.data.ConfigManager
import com.andebugulin.nfcguard.testing.cancelViewModel
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.resetAppStateRepository
import com.andebugulin.nfcguard.testing.tag
import com.andebugulin.nfcguard.ui.home.SettingsDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Config export and import, including the file I/O — the path a user takes to
 * move their setup to a new phone, and the one place where a parsing mistake
 * silently replaces everything they built.
 *
 * `ConfigManagerTest` covers the serialisation itself. What was untested is the
 * layer above it in `SettingsDialog`: writing to the chosen document, reading
 * one back, and above all the **format heuristic** that decides whether a file
 * is YAML or JSON — misroute that and a valid file reports as corrupt.
 *
 * Reaching it needs no production change: `rememberLauncherForActivityResult`
 * resolves `LocalActivityResultRegistryOwner`, so the test supplies a registry
 * that records the launch and hands back a URI on demand, and Robolectric's
 * ContentResolver backs that URI with an in-memory stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1600dp")
class ConfigFileTransferTest {

    @get:Rule val compose = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var vm: GuardianViewModel

    /** Records what was launched and lets the test deliver the user's file choice. */
    private class FakeRegistry : ActivityResultRegistry() {
        var pendingRequestCode: Int? = null
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            pendingRequestCode = requestCode
        }

        fun deliver(uri: Uri?) {
            val code = requireNotNull(pendingRequestCode) { "nothing was launched" }
            pendingRequestCode = null
            @Suppress("UNCHECKED_CAST")
            dispatchResult(code, uri)
        }
    }

    private val registry = FakeRegistry()

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

    /**
     * The launcher callbacks write Compose state from outside the composition,
     * and with the clock paused those writes are not observed until the
     * snapshot is applied — the export bytes land, but nothing recomposes, so
     * neither the result message nor the import dialog ever appears.
     */
    private fun settle() {
        Snapshot.sendApplyNotifications()
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                }
            ) {
                MinimalistTheme {
                    val state by vm.appState.collectAsState()
                    SettingsDialog(viewModel = vm, appState = state, onDismiss = {})
                }
            }
        }
        settle()
    }

    private fun isDisplayed(text: String) = runCatching {
        compose.onAllNodesWithText(text).onFirst().assertIsDisplayed()
    }.isSuccess

    private fun scrollTo(text: String) {
        if (isDisplayed(text)) return
        runCatching {
            compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
            compose.mainClock.advanceTimeBy(1_000)
            settle()
        }
    }

    private fun tap(text: String) {
        scrollTo(text)
        compose.onAllNodesWithText(text).onFirst().performClick()
        settle()
    }

    private fun assertVisible(text: String) {
        scrollTo(text)
        compose.onAllNodesWithText(text).onFirst().assertIsDisplayed()
    }

    private fun assertVisibleContaining(text: String) {
        scrollTo(text)
        compose.onAllNodesWithText(text, substring = true).onFirst().assertIsDisplayed()
    }

    /** Surfaces the app's own failure message so a broken import names itself. */
    private fun reportedError(): String? =
        compose.onAllNodesWithText("IMPORT FAILED", substring = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)
            ?.joinToString()

    private fun seedConfig() = vm.importConfig(
        ConfigManager.ExportData(
            1,
            listOf(mode(id = "m1", name = "Deep Work")),
            emptyList(),
            listOf(tag(id = "t1", name = "Desk key"))
        )
    )

    // ---------------- export ----------------

    private fun exportTo(format: String): String {
        val uri = Uri.parse("content://test/config")
        val sink = ByteArrayOutputStream()
        shadowOf(app.contentResolver).registerOutputStream(uri, sink)

        tap("EXPORT CONFIG")
        tap(format)
        registry.deliver(uri)
        settle()

        return sink.toString()
    }

    @Test fun `exporting JSON writes the config to the chosen file`() {
        seedConfig()
        show()

        val written = exportTo("JSON")

        assertTrue("expected JSON, got: $written", written.trimStart().startsWith("{"))
        assertTrue("the mode should be in the file", written.contains("Deep Work"))
        assertTrue("the tag should be in the file", written.contains("Desk key"))
    }

    @Test fun `exporting YAML writes the config to the chosen file`() {
        seedConfig()
        show()

        val written = exportTo("YAML")

        assertTrue("expected YAML, got: $written", written.contains("modes:"))
        assertTrue(written.contains("Deep Work"))
    }

    @Test fun `a successful export is reported back`() {
        seedConfig()
        show()

        exportTo("JSON")

        assertVisible("EXPORTED JSON SUCCESSFULLY")   // status messages render .uppercase()
    }

    @Test fun `cancelling the file chooser exports nothing and says nothing`() {
        seedConfig()
        show()
        tap("EXPORT CONFIG")
        tap("JSON")

        registry.deliver(null)
        settle()

        compose.onAllNodesWithText("EXPORTED JSON SUCCESSFULLY").assertCountEquals(0)
    }

    // ---------------- import ----------------

    private fun importFrom(fileName: String, content: String) {
        val uri = Uri.parse("content://test/$fileName")
        shadowOf(app.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(content.toByteArray()))

        tap("IMPORT CONFIG")
        registry.deliver(uri)
        settle()
    }

    private fun assertImportSucceeded() {
        val error = reportedError()
        check(error == null) { "import failed instead of asking how to apply: $error" }
    }

    private fun exportedJson() = ConfigManager.exportToJson(
        com.andebugulin.nfcguard.AppState(
            modes = listOf(mode(id = "m9", name = "Imported")),
            nfcTags = listOf(tag(id = "t9", name = "Imported tag"))
        )
    )

    private fun exportedYaml() = ConfigManager.exportToYaml(
        com.andebugulin.nfcguard.AppState(
            modes = listOf(mode(id = "m9", name = "Imported")),
            nfcTags = listOf(tag(id = "t9", name = "Imported tag"))
        )
    )

    @Test fun `importing a JSON file asks how to apply it before touching anything`() {
        show()

        importFrom("config.json", exportedJson())

        assertVisible("MERGE")
        assertVisible("REPLACE")
        assertEquals("nothing may be applied before the user chooses", 0, vm.appState.value.modes.size)
    }

    /** The heuristic's first branch: trust the extension. */
    @Test fun `a yaml extension is parsed as YAML`() {
        show()

        importFrom("config.yaml", exportedYaml())

        assertImportSucceeded()
        assertVisible("MERGE")
    }

    /** Its fallback: sniff the content, for files saved without a useful name. */
    @Test fun `YAML content without a yaml extension is still parsed as YAML`() {
        show()

        importFrom("download", exportedYaml())

        assertImportSucceeded()
        assertVisible("MERGE")
    }

    @Test fun `MERGE adds the imported config to what is already there`() {
        seedConfig()
        show()
        importFrom("config.json", exportedJson())

        tap("MERGE")

        val names = vm.appState.value.modes.map { it.name }
        assertTrue("the existing mode should survive a merge", names.contains("Deep Work"))
        assertTrue(names.contains("Imported"))
    }

    @Test fun `REPLACE swaps the config wholesale`() {
        seedConfig()
        show()
        importFrom("config.json", exportedJson())

        tap("REPLACE")

        assertEquals(listOf("Imported"), vm.appState.value.modes.map { it.name })
    }

    @Test fun `a corrupt file is reported instead of wiping the config`() {
        seedConfig()
        show()

        importFrom("config.json", "this is not a config at all")

        assertVisibleContaining("IMPORT FAILED")
        assertEquals(
            "a bad file must never cost the user their setup",
            listOf("Deep Work"), vm.appState.value.modes.map { it.name }
        )
    }
}
