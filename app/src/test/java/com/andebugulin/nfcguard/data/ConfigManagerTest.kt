package com.andebugulin.nfcguard.data

import com.andebugulin.nfcguard.AppState
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.schedule
import com.andebugulin.nfcguard.testing.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Export/import must round-trip the three config lists and must never carry
 * runtime state (active modes, timers) across a device.
 *
 * The JSON path keeps a manual fallback for older config versions; the YAML
 * path is hand-rolled, so its parser needs the most exercise.
 */
@RunWith(RobolectricTestRunner::class)
class ConfigManagerTest {

    private fun fullState() = AppState(
        modes = listOf(
            mode(id = "m1", name = "Focus", apps = listOf("com.a", "com.b"), tagIds = listOf("t1")),
            mode(id = "m2", name = "Allowlist", apps = listOf("com.c"),
                 blockMode = BlockMode.ALLOW_SELECTED, limits = mapOf("t1" to 30L, "ANY" to null))
        ),
        schedules = listOf(schedule(id = "s1", name = "Work", modeIds = listOf("m1"))),
        nfcTags = listOf(tag(id = "t1", name = "Desk", modeIds = listOf("m1"))),
        // Runtime state — must NOT be exported.
        activeModes = setOf("m1"),
        activeSchedules = setOf("s1"),
        timedModeDeactivations = mapOf("m1" to 999L)
    )

    // ─── JSON ─────────────────────────────────────────────────────────────

    @Test fun `json round-trips modes, schedules and tags`() {
        val out = ConfigManager.importFromJson(ConfigManager.exportToJson(fullState()))

        assertEquals(listOf("m1", "m2"), out.modes.map { it.id })
        assertEquals(listOf("s1"), out.schedules.map { it.id })
        assertEquals(listOf("t1"), out.nfcTags.map { it.id })
    }

    @Test fun `json preserves block mode, tag links and per-tag limits`() {
        val out = ConfigManager.importFromJson(ConfigManager.exportToJson(fullState()))

        val allow = out.modes.single { it.id == "m2" }
        assertEquals(BlockMode.ALLOW_SELECTED, allow.blockMode)
        assertEquals(30L, allow.tagUnlockLimits["t1"])
        assertTrue(allow.tagUnlockLimits.containsKey("ANY"))

        assertEquals(listOf("t1"), out.modes.single { it.id == "m1" }.nfcTagIds)
    }

    @Test fun `json export never contains runtime state`() {
        val raw = ConfigManager.exportToJson(fullState())
        assertFalse("activeModes leaked into the export", raw.contains("activeModes"))
        assertFalse("timers leaked into the export", raw.contains("timedModeDeactivations"))
        assertFalse(raw.contains("activeSchedules"))
    }

    @Test fun `json import tolerates a config missing optional sections`() {
        val out = ConfigManager.importFromJson("""{"version":1,"modes":[],"schedules":[],"nfcTags":[]}""")
        assertEquals(0, out.modes.size)
    }

    @Test fun `json import tolerates unknown keys from a newer schema`() {
        val out = ConfigManager.importFromJson(
            """{"version":99,"modes":[],"schedules":[],"nfcTags":[],"futureField":true}"""
        )
        assertEquals(0, out.modes.size)
    }

    // ─── YAML ─────────────────────────────────────────────────────────────

    @Test fun `yaml round-trips modes, schedules and tags`() {
        val out = ConfigManager.importFromYaml(ConfigManager.exportToYaml(fullState()))

        assertEquals(listOf("m1", "m2"), out.modes.map { it.id })
        assertEquals(listOf("s1"), out.schedules.map { it.id })
        assertEquals(listOf("t1"), out.nfcTags.map { it.id })
    }

    @Test fun `yaml preserves block mode and app lists`() {
        val out = ConfigManager.importFromYaml(ConfigManager.exportToYaml(fullState()))

        assertEquals(BlockMode.ALLOW_SELECTED, out.modes.single { it.id == "m2" }.blockMode)
        assertEquals(listOf("com.a", "com.b"), out.modes.single { it.id == "m1" }.blockedApps)
    }

    @Test fun `yaml preserves schedule day and time slots`() {
        val out = ConfigManager.importFromYaml(ConfigManager.exportToYaml(fullState()))

        val slot = out.schedules.single().timeSlot.dayTimes.single()
        assertEquals(1, slot.day)
        assertEquals(9, slot.startHour)
        assertEquals(17, slot.endHour)
    }

    @Test fun `yaml export never contains runtime state`() {
        val raw = ConfigManager.exportToYaml(fullState())
        assertFalse(raw.contains("activeModes"))
        assertFalse(raw.contains("timedModeDeactivations"))
    }

    @Test fun `yaml survives names containing quotes and colons`() {
        val tricky = AppState(
            modes = listOf(mode(id = "m1", name = """Work: "deep" mode""", apps = listOf("com.a"))),
            schedules = emptyList(),
            nfcTags = emptyList()
        )
        val out = ConfigManager.importFromYaml(ConfigManager.exportToYaml(tricky))
        assertEquals("""Work: "deep" mode""", out.modes.single().name)
    }

    @Test fun `an empty config round-trips through both formats`() {
        val empty = AppState()
        assertEquals(0, ConfigManager.importFromJson(ConfigManager.exportToJson(empty)).modes.size)
        assertEquals(0, ConfigManager.importFromYaml(ConfigManager.exportToYaml(empty)).modes.size)
    }
}
