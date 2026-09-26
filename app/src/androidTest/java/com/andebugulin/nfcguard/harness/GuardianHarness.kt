package com.andebugulin.nfcguard.harness

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.andebugulin.nfcguard.AppState
import com.andebugulin.nfcguard.Mode
import com.andebugulin.nfcguard.NfcTag
import com.andebugulin.nfcguard.Schedule
import com.andebugulin.nfcguard.data.AppStateRepository
import com.andebugulin.nfcguard.nfc.MockNfcTag
import com.andebugulin.nfcguard.ui.MainActivity
import com.andebugulin.nfcguard.ui.modes.AppInfo
import com.andebugulin.nfcguard.ui.modes.loadInstalledApps
import kotlinx.coroutines.runBlocking

/**
 * Drives the real app on a real device: puts the device into a known state,
 * launches [MainActivity], and simulates NFC taps against it.
 *
 * Unlike the Robolectric screen suites — which compose one screen in isolation
 * with a ViewModel they own — this goes through the app's own entry point, so
 * it covers the wiring those suites cannot see: the Activity's NFC intent
 * plumbing, `MainNavigation`'s `LaunchedEffect` routing, and the onboarding
 * gates.
 *
 * ### Why state is seeded through the repository, not reflection
 *
 * Instrumentation shares a process with the app under test, so
 * [AppStateRepository] is the *same* singleton the running Activity observes.
 * Nulling it out (as the Robolectric fixtures do) would leave two repositories
 * writing one prefs file, and the Activity holding the dead one. Seeding
 * through [AppStateRepository.update] instead keeps a single owner and
 * exercises the real write path, including [com.andebugulin.nfcguard.sync.StateSyncer].
 *
 * ### Why modes are activated through the UI
 *
 * Seeding an *active* mode makes `StateSyncer` start `BlockerService` while the
 * app is in the background, and Android answers a late `startForeground` with
 * `ForegroundServiceDidNotStartInTimeException`, which takes the whole process
 * down — see TESTS.md. Activating from a tap on the resumed Activity is a
 * legal foreground-service start, and is what a user does anyway. Seed config
 * only; activate via [ModesRobot.activate].
 */
class GuardianHarness(val compose: ComposeTestRule) {

    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo get() = AppStateRepository.getInstance(context)
    private var scenario: ActivityScenario<MainActivity>? = null

    /**
     * Wipes runtime state and marks both onboarding flows complete, so a
     * launch lands directly on Home. Must run before [launch] — `MainNavigation`
     * reads these flags into `remember` during first composition.
     */
    fun resetToOnboardedHome() {
        runBlocking { repo.update { AppState() } }
        prefs().edit()
            .putBoolean("has_seen_onboarding", true)
            .putBoolean("initial_permissions_granted", true)
            // The app's own default. Written explicitly so a test that turns it
            // off cannot leak that into the next one — it lives outside
            // AppState by design, so resetting AppState does not clear it.
            .putBoolean("safe_regime_enabled", true)
            .commit()
    }

    /** Restores a genuinely first-run device, for onboarding coverage. */
    fun resetToFirstRun() {
        runBlocking { repo.update { AppState() } }
        prefs().edit().clear().commit()
    }

    fun seedConfig(
        modes: List<Mode> = emptyList(),
        schedules: List<Schedule> = emptyList(),
        tags: List<NfcTag> = emptyList()
    ) = runBlocking {
        repo.update { it.copy(modes = modes, schedules = schedules, nfcTags = tags) }
    }

    /**
     * Turns off the anti-bypass setting, the way the Settings toggle does.
     * Used to prove the emergency-reset gate does *not* consult it.
     */
    fun disableSafeRegime() {
        prefs().edit().putBoolean("safe_regime_enabled", false).commit()
    }

    val state: AppState get() = repo.current

    /**
     * An app the mode editor's picker will actually list on *this* device.
     *
     * Resolved through the app's own [loadInstalledApps] rather than a
     * hardcoded package, so the test does not assume Chrome is installed and
     * stays in step with the critical-app filtering the picker applies.
     */
    fun aBlockableApp(): AppInfo =
        requireNotNull(loadInstalledApps(context).minByOrNull { it.appName }) {
            "the picker would show no apps on this device, so no mode can be saved"
        }

    fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also {
            scenario = it
            compose.waitForIdle()
        }

    /**
     * Simulates a tap on the app that is already open, by handing
     * [MainActivity] the exact intent the NFC foreground dispatch delivers.
     *
     * The intent goes straight to `onNewIntent` rather than through
     * `startActivity`. That is not a shortcut for convenience: re-entering a
     * `singleTop` Activity through AMS churns the instance enough that
     * `ActivityScenario` stops seeing its lifecycle and its teardown then hangs
     * waiting for a DESTROYED it never observes. Routing to `onNewIntent`
     * keeps one stable instance, and skips nothing that belongs to the app —
     * `onNewIntent` is precisely the entry point the real dispatch calls, and
     * everything under test (`handleNfcIntent`, the hex encoding, the
     * wrong-tag guard, `MainNavigation`'s routing) runs from there.
     *
     * AMS delivery itself is covered by [tapNfcTagFromColdStart], which goes
     * through a real launch.
     */
    fun tapNfcTag(tagId: String) {
        val scenario = requireNotNull(scenario) { "launch() the app before tapping a tag" }
        val intent = MockNfcTag.discoveryIntent(context, MainActivity::class.java, tagId)
        scenario.onActivity { activity ->
            MainActivity::class.java
                .getDeclaredMethod("onNewIntent", Intent::class.java)
                .apply { isAccessible = true }
                .invoke(activity, intent)
        }
        compose.waitForIdle()
    }

    /**
     * Simulates a tap that wakes the app from cold — a real AMS launch with the
     * NFC intent, landing in `onCreate`'s `handleNfcIntent` rather than
     * `onNewIntent`.
     */
    fun tapNfcTagFromColdStart(tagId: String) {
        close()
        val intent = MockNfcTag.discoveryIntent(context, MainActivity::class.java, tagId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()
    }

    /**
     * Finishes the Activity from inside the app process and waits for it to go.
     *
     * Deliberately never calls `ActivityScenario.close()`. That drives the
     * lifecycle through `InstrumentationActivityInvoker`, whose helper Activity
     * is hosted in the *test* package's own process; once our Activity is in
     * front that process goes cached, and this device's lowmemorykiller reaps
     * it (`adj=900`, confirmed in logcat). `close()` then blocks for its full
     * 45-second timeout waiting for a DESTROYED transition it can no longer
     * cause — which turned every test in this suite into a ~46s run that
     * failed in teardown with a perfectly healthy body.
     *
     * `finish()` needs no helper process, and the resulting DESTROYED is
     * observed in-process through the lifecycle monitor.
     */
    fun close() {
        val open = scenario ?: return
        scenario = null
        runCatching { open.onActivity { it.finish() } }
        awaitNoLiveActivity()
    }

    private fun awaitNoLiveActivity(timeoutMs: Long = 5_000) {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val liveStages = Stage.values().filter { it != Stage.DESTROYED }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var live = true
            // getActivitiesInStage is only safe to read from the main thread.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                live = liveStages.any { monitor.getActivitiesInStage(it).isNotEmpty() }
            }
            if (!live) return
            Thread.sleep(25)
        }
    }

    private fun prefs() = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
}
