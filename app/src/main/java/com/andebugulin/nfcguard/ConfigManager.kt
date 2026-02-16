package com.andebugulin.nfcguard

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Handles export/import of Guardian configuration in JSON and YAML formats.
 *
 * Exports only user-configured data (modes, schedules, nfcTags).
 * Runtime state (activeModes, activeSchedules, deactivatedSchedules) is NOT exported.
 */
object ConfigManager {

    @Serializable
    data class ExportData(
        val version: Int = 1,
        val modes: List<Mode>,
        val schedules: List<Schedule>,
        val nfcTags: List<NfcTag>
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    // ======================== JSON ========================

    fun exportToJson(appState: AppState): String {
        val data = ExportData(
            modes = appState.modes,
            schedules = appState.schedules,
            nfcTags = appState.nfcTags
        )
        return json.encodeToString(data)
    }

    fun importFromJson(content: String): ExportData {
        return json.decodeFromString<ExportData>(content)
    }

    // ======================== YAML ========================

    fun exportToYaml(appState: AppState): String {
        val sb = StringBuilder()
        sb.appendLine("# Guardian Configuration Export")
        sb.appendLine()
        sb.appendLine("version: 1")
        sb.appendLine()

        // Modes
        sb.appendLine("modes:")
        if (appState.modes.isEmpty()) {
            sb.appendLine("  []")
        } else {
            for (mode in appState.modes) {
                sb.appendLine("  - id: \"${escapeYaml(mode.id)}\"")
                sb.appendLine("    name: \"${escapeYaml(mode.name)}\"")
                sb.appendLine("    blockMode: ${mode.blockMode.name}")
                if (mode.nfcTagId != null) {
                    sb.appendLine("    nfcTagId: \"${escapeYaml(mode.nfcTagId)}\"")
                } else {
                    sb.appendLine("    nfcTagId: null")
                }
                sb.appendLine("    blockedApps:")
                if (mode.blockedApps.isEmpty()) {
                    sb.appendLine("      []")
                } else {
                    for (app in mode.blockedApps) {
                        sb.appendLine("      - \"${escapeYaml(app)}\"")
                    }
                }
            }
        }
        sb.appendLine()

        // Schedules
        sb.appendLine("schedules:")
        if (appState.schedules.isEmpty()) {
            sb.appendLine("  []")
        } else {
            for (schedule in appState.schedules) {
                sb.appendLine("  - id: \"${escapeYaml(schedule.id)}\"")
                sb.appendLine("    name: \"${escapeYaml(schedule.name)}\"")
                sb.appendLine("    hasEndTime: ${schedule.hasEndTime}")
                sb.appendLine("    linkedModeIds:")
                if (schedule.linkedModeIds.isEmpty()) {
                    sb.appendLine("      []")
                } else {
                    for (modeId in schedule.linkedModeIds) {
                        sb.appendLine("      - \"${escapeYaml(modeId)}\"")
                    }
                }
                sb.appendLine("    timeSlot:")
                sb.appendLine("      dayTimes:")
                for (dt in schedule.timeSlot.dayTimes) {
                    sb.appendLine("        - day: ${dt.day}")
                    sb.appendLine("          startHour: ${dt.startHour}")
                    sb.appendLine("          startMinute: ${dt.startMinute}")
                    sb.appendLine("          endHour: ${dt.endHour}")
                    sb.appendLine("          endMinute: ${dt.endMinute}")
                }
            }
        }
        sb.appendLine()

        // NFC Tags
        sb.appendLine("nfcTags:")
        if (appState.nfcTags.isEmpty()) {
            sb.appendLine("  []")
        } else {
            for (tag in appState.nfcTags) {
                sb.appendLine("  - id: \"${escapeYaml(tag.id)}\"")
                sb.appendLine("    name: \"${escapeYaml(tag.name)}\"")
                sb.appendLine("    linkedModeIds:")
                if (tag.linkedModeIds.isEmpty()) {
                    sb.appendLine("      []")
                } else {
                    for (modeId in tag.linkedModeIds) {
                        sb.appendLine("      - \"${escapeYaml(modeId)}\"")
                    }
                }
            }
        }

        return sb.toString()
    }

    fun importFromYaml(content: String): ExportData {
        val lines = content.lines()
        var i = 0

        fun currentLine(): String? = if (i < lines.size) lines[i].trimEnd() else null
        fun advance() { i++ }
        fun indent(line: String): Int = line.length - line.trimStart().length

        fun parseQuotedString(value: String): String {
            val trimmed = value.trim()
            return if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else {
                trimmed
            }
        }

        fun parseStringList(baseIndent: Int): List<String> {
            val result = mutableListOf<String>()
            while (currentLine() != null) {
                val line = currentLine()!!
                if (line.isBlank()) { advance(); continue }
                val curIndent = indent(line)
                if (curIndent <= baseIndent) break
                val trimmed = line.trim()
                if (trimmed == "[]") { advance(); return emptyList() }
                if (trimmed.startsWith("- ")) {
                    result.add(parseQuotedString(trimmed.removePrefix("- ")))
                    advance()
                } else {
                    break
                }
            }
            return result
        }

        fun skipToSection(name: String) {
            while (currentLine() != null) {
                val line = currentLine()!!
                if (line.trim().startsWith("$name:")) { advance(); return }
                advance()
            }
        }

        // Skip header
        while (currentLine() != null && (currentLine()!!.trim().startsWith("#") || currentLine()!!.isBlank() || currentLine()!!.trim().startsWith("version:"))) {
            advance()
        }

        // Parse modes
        val modes = mutableListOf<Mode>()
        i = 0 // reset
        skipToSection("modes")
        if (currentLine()?.trim() == "[]") { advance() }
        else {
            while (currentLine() != null) {
                val line = currentLine()!!
                if (line.isBlank()) { advance(); continue }
                if (indent(line) < 2 && !line.trim().startsWith("-")) break
                val trimmed = line.trim()
                if (trimmed.startsWith("- id:")) {
                    var id = ""; var name = ""; var blockMode = BlockMode.BLOCK_SELECTED
                    var nfcTagId: String? = null; var blockedApps = listOf<String>()
                    id = parseQuotedString(trimmed.removePrefix("- id:"))
                    advance()
                    while (currentLine() != null) {
                        val ml = currentLine()!!
                        if (ml.isBlank()) { advance(); continue }
                        val mIndent = indent(ml)
                        if (mIndent < 4) break
                        val mt = ml.trim()
                        when {
                            mt.startsWith("name:") -> { name = parseQuotedString(mt.removePrefix("name:")); advance() }
                            mt.startsWith("blockMode:") -> {
                                val bm = mt.removePrefix("blockMode:").trim()
                                blockMode = if (bm == "ALLOW_SELECTED") BlockMode.ALLOW_SELECTED else BlockMode.BLOCK_SELECTED
                                advance()
                            }
                            mt.startsWith("nfcTagId:") -> {
                                val v = mt.removePrefix("nfcTagId:").trim()
                                nfcTagId = if (v == "null") null else parseQuotedString(v)
                                advance()
                            }
                            mt.startsWith("blockedApps:") -> {
                                advance()
                                blockedApps = parseStringList(6)
                            }
                            else -> advance()
                        }
                    }
                    modes.add(Mode(id, name, blockedApps, blockMode, nfcTagId))
                } else {
                    advance()
                }
            }
        }

        // Parse schedules
        val schedules = mutableListOf<Schedule>()
        i = 0 // reset
        skipToSection("schedules")
        if (currentLine()?.trim() == "[]") { advance() }
        else {
            while (currentLine() != null) {
                val line = currentLine()!!
                if (line.isBlank()) { advance(); continue }
                if (indent(line) < 2 && !line.trim().startsWith("-")) break
                val trimmed = line.trim()
                if (trimmed.startsWith("- id:")) {
                    var id = ""; var name = ""; var hasEndTime = false
                    var linkedModeIds = listOf<String>()
                    val dayTimes = mutableListOf<DayTime>()
                    id = parseQuotedString(trimmed.removePrefix("- id:"))
                    advance()
                    while (currentLine() != null) {
                        val sl = currentLine()!!
                        if (sl.isBlank()) { advance(); continue }
                        val sIndent = indent(sl)
                        if (sIndent < 4) break
                        val st = sl.trim()
                        when {
                            st.startsWith("name:") -> { name = parseQuotedString(st.removePrefix("name:")); advance() }
                            st.startsWith("hasEndTime:") -> {
                                hasEndTime = st.removePrefix("hasEndTime:").trim().toBoolean()
                                advance()
                            }
                            st.startsWith("linkedModeIds:") -> {
                                advance()
                                linkedModeIds = parseStringList(6)
                            }
                            st.startsWith("timeSlot:") -> {
                                advance()
                                // skip "dayTimes:" line
                                while (currentLine() != null && !currentLine()!!.trim().startsWith("dayTimes:") && indent(currentLine()!!) >= 6) advance()
                                if (currentLine()?.trim()?.startsWith("dayTimes:") == true) advance()
                                // parse day time entries
                                while (currentLine() != null) {
                                    val dtl = currentLine()!!
                                    if (dtl.isBlank()) { advance(); continue }
                                    if (indent(dtl) < 8) break
                                    val dtt = dtl.trim()
                                    if (dtt.startsWith("- day:")) {
                                        var day = 0; var sh = 0; var sm = 0; var eh = 0; var em = 0
                                        day = dtt.removePrefix("- day:").trim().toIntOrNull() ?: 0
                                        advance()
                                        while (currentLine() != null) {
                                            val pl = currentLine()!!
                                            if (pl.isBlank()) { advance(); continue }
                                            if (indent(pl) < 10) break
                                            val pt = pl.trim()
                                            when {
                                                pt.startsWith("startHour:") -> { sh = pt.removePrefix("startHour:").trim().toIntOrNull() ?: 0; advance() }
                                                pt.startsWith("startMinute:") -> { sm = pt.removePrefix("startMinute:").trim().toIntOrNull() ?: 0; advance() }
                                                pt.startsWith("endHour:") -> { eh = pt.removePrefix("endHour:").trim().toIntOrNull() ?: 0; advance() }
                                                pt.startsWith("endMinute:") -> { em = pt.removePrefix("endMinute:").trim().toIntOrNull() ?: 0; advance() }
                                                else -> advance()
                                            }
                                        }
                                        dayTimes.add(DayTime(day, sh, sm, eh, em))
                                    } else {
                                        advance()
                                    }
                                }
                            }
                            else -> advance()
                        }
                    }
                    schedules.add(Schedule(id, name, TimeSlot(dayTimes), linkedModeIds, hasEndTime))
                } else {
                    advance()
                }
            }
        }

        // Parse NFC tags
        val nfcTags = mutableListOf<NfcTag>()
        i = 0 // reset
        skipToSection("nfcTags")
        if (currentLine()?.trim() == "[]") { advance() }
        else {
            while (currentLine() != null) {
                val line = currentLine()!!
                if (line.isBlank()) { advance(); continue }
                if (indent(line) < 2 && !line.trim().startsWith("-")) break
                val trimmed = line.trim()
                if (trimmed.startsWith("- id:")) {
                    var id = ""; var name = ""; var linkedModeIds = listOf<String>()
                    id = parseQuotedString(trimmed.removePrefix("- id:"))
                    advance()
                    while (currentLine() != null) {
                        val tl = currentLine()!!
                        if (tl.isBlank()) { advance(); continue }
                        if (indent(tl) < 4) break
                        val tt = tl.trim()
                        when {
                            tt.startsWith("name:") -> { name = parseQuotedString(tt.removePrefix("name:")); advance() }
                            tt.startsWith("linkedModeIds:") -> {
                                advance()
                                linkedModeIds = parseStringList(6)
                            }
                            else -> advance()
                        }
                    }
                    nfcTags.add(NfcTag(id, name, linkedModeIds))
                } else {
                    advance()
                }
            }
        }

        return ExportData(modes = modes, schedules = schedules, nfcTags = nfcTags)
    }

    private fun escapeYaml(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}