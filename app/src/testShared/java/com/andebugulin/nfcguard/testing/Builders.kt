package com.andebugulin.nfcguard.testing

import com.andebugulin.nfcguard.AppState
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.DayTime
import com.andebugulin.nfcguard.Mode
import com.andebugulin.nfcguard.NfcTag
import com.andebugulin.nfcguard.Schedule
import com.andebugulin.nfcguard.TimeSlot

/**
 * Domain builders shared by the Robolectric (`test`) and instrumented
 * (`androidTest`) suites — see the `testShared` source dir wired up in
 * `app/build.gradle.kts`.
 *
 * Only pure-domain construction belongs here. Anything needing Robolectric
 * shadows stays in `src/test/.../testing/Fixtures.kt`, which is not on the
 * instrumented classpath.
 */

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

fun tag(id: String = "t1", name: String = "Desk tag") = NfcTag(id = id, name = name)

fun emptyState() = AppState()
