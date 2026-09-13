package com.andebugulin.nfcguard.testing

import com.andebugulin.nfcguard.data.AppStateRepository
import com.andebugulin.nfcguard.service.ForegroundDetectorService

import android.content.Context
import org.robolectric.shadows.ShadowSettings

/**
 * Robolectric-only harness helpers for the `:app` unit suite.
 *
 * The domain builders (`mode()`, `schedule()`, `tag()`, `emptyState()`) live in
 * `src/testShared/.../testing/Builders.kt` so the instrumented suite can share
 * them; everything here depends on Robolectric shadows and cannot.
 */

/**
 * The repository is a process-wide singleton. Robolectric hands each test a
 * fresh Application, so a stale INSTANCE would keep a dead Context and leak
 * state between tests. Clear it in @Before.
 */
fun resetAppStateRepository() {
    val field = AppStateRepository::class.java.getDeclaredField("INSTANCE")
    field.isAccessible = true
    field.set(null, null)
}

/**
 * `ForegroundDetectorService.isRunning` / `lastDetectedTime` are `private set`,
 * so tests that need to pose as "accessibility is (not) bound" write the
 * backing field directly. Which enforcer `BlockerService` picks, and which of
 * `ForegroundAppDetector`'s three strategies runs, both hang off these.
 */
fun setDetectorState(name: String, value: Any?) {
    val field = ForegroundDetectorService::class.java.getDeclaredField(name)
    field.isAccessible = true
    field.set(null, value)
}

/**
 * Pose as the accessibility service being enabled in system settings.
 *
 * Distinct from [setDetectorState] on purpose: `isRunning` says the service is
 * *bound right now* (which enforcer a tick picks), while the Settings.Secure
 * list says the user has *granted* it — which is what the settings sheet reads
 * to report the blocking method.
 */
fun enableAccessibilityService(context: Context) {
    android.provider.Settings.Secure.putString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        "${context.packageName}/${ForegroundDetectorService::class.java.canonicalName}"
    )
}

/** `BlockerService.start` bails out early without this. */
fun grantOverlayPermission() = ShadowSettings.setCanDrawOverlays(true)

fun seedPersistedState(context: Context, rawJson: String) {
    context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        .edit().putString("app_state", rawJson).commit()
}

fun persistedStateJson(context: Context): String? =
    context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        .getString("app_state", null)

/**
 * Cancel a ViewModel's scope. `GuardianViewModel.init` starts an endless 5s
 * polling loop; left running it keeps the test JVM from settling.
 */
fun cancelViewModel(vm: androidx.lifecycle.ViewModel) {
    runCatching {
        val clear = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("clear")
        clear.isAccessible = true
        clear.invoke(vm)
    }
}
