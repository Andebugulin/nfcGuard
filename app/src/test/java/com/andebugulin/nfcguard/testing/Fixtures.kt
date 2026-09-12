package com.andebugulin.nfcguard.testing

import com.andebugulin.nfcguard.AppState
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.DayTime
import com.andebugulin.nfcguard.Mode
import com.andebugulin.nfcguard.NfcTag
import com.andebugulin.nfcguard.Schedule
import com.andebugulin.nfcguard.TimeSlot
import com.andebugulin.nfcguard.data.AppStateRepository

import android.content.Context
import org.robolectric.shadows.ShadowSettings

/** Builders and harness helpers shared across the `:app` Robolectric suite. */

fun mode(
    id: String = "m1",
    name: String = "Focus",
    apps: List<String> = listOf("com.example.social"),
    blockMode: BlockMode = BlockMode.BLOCK_SELECTED,
    tagIds: List<String> = emptyList(),
    limits: Map<String, Long?> = emptyMap()
) = Mode(
    id = id,
    name = name,
    blockedApps = apps,
    blockMode = blockMode,
    nfcTagIds = tagIds,
    tagUnlockLimits = limits
)

fun schedule(
    id: String = "s1",
    name: String = "Work",
    modeIds: List<String> = listOf("m1"),
    day: Int = 1,
    startHour: Int = 9,
    endHour: Int = 17,
    hasEndTime: Boolean = true
) = Schedule(
    id = id,
    name = name,
    timeSlot = TimeSlot(listOf(DayTime(day, startHour, 0, endHour, 0))),
    linkedModeIds = modeIds,
    hasEndTime = hasEndTime
)

fun tag(id: String = "t1", name: String = "Desk tag", modeIds: List<String> = emptyList()) =
    NfcTag(id = id, name = name, linkedModeIds = modeIds)

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

/** `BlockerService.start` bails out early without this. */
fun grantOverlayPermission() = ShadowSettings.setCanDrawOverlays(true)

fun seedPersistedState(context: Context, rawJson: String) {
    context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        .edit().putString("app_state", rawJson).commit()
}

fun persistedStateJson(context: Context): String? =
    context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
        .getString("app_state", null)

fun emptyState() = AppState()

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
