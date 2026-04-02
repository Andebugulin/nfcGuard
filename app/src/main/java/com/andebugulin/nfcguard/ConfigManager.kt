package com.andebugulin.nfcguard

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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
        // First try direct deserialization (handles current format + defaults for missing fields)
        try {
            val data = json.decodeFromString<ExportData>(content)
            return migrateExportData(data)
        } catch (_: Exception) { /* Fall through to manual migration */ }

        // Manual migration for older configs that may have incompatible structure
        val root = Json.parseToJsonElement(content).jsonObject
        val modesArray = root["modes"]?.jsonArray ?: emptyJsonArray()
        val schedulesArray = root["schedules"]?.jsonArray ?: emptyJsonArray()
        val nfcTagsArray = root["nfcTags"]?.jsonArray ?: emptyJsonArray()

        val modes = modesArray.map { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: ""
            val name = obj["name"]?.jsonPrimitive?.content ?: ""
            val blockModeStr = obj["blockMode"]?.jsonPrimitive?.content ?: "BLOCK_SELECTED"
            val blockMode = if (blockModeStr == "ALLOW_SELECTED") BlockMode.ALLOW_SELECTED else BlockMode.BLOCK_SELECTED
            val blockedApps = obj["blockedApps"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

            // Handle both new nfcTagIds list and legacy nfcTagId single value
            val nfcTagIds = obj["nfcTagIds"]?.jsonArray?.map { it.jsonPrimitive.content }
                ?: run {
                    val legacy = obj["nfcTagId"]?.jsonPrimitive?.contentOrNull
                    if (legacy != null) listOf(legacy) else emptyList()
                }

            // tagUnlockLimits: missing = empty map (permanent by default)
            val tagUnlockLimits = obj["tagUnlockLimits"]?.jsonObject?.entries?.associate { (k, v) ->
                k to if (v.jsonPrimitive.content == "null") null else v.jsonPrimitive.longOrNull
            } ?: emptyMap()

            Mode(id, name, blockedApps, blockMode, nfcTagId = null, nfcTagIds = nfcTagIds, tagUnlockLimits = tagUnlockLimits)
        }

        val schedules = schedulesArray.map { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: ""
            val name = obj["name"]?.jsonPrimitive?.content ?: ""
            val hasEndTime = obj["hasEndTime"]?.jsonPrimitive?.booleanOrNull ?: false
            // Handle both linkedModeIds list and legacy modeId single value
            val linkedModeIds = obj["linkedModeIds"]?.jsonArray?.map { it.jsonPrimitive.content }
                ?: run {
                    val legacy = obj["modeId"]?.jsonPrimitive?.contentOrNull
                    if (legacy != null) listOf(legacy) else emptyList()
                }
            val dayTimes = obj["timeSlot"]?.jsonObject?.get("dayTimes")?.jsonArray?.map { dtEl ->
                val dt = dtEl.jsonObject
                DayTime(
                    day = dt["day"]?.jsonPrimitive?.intOrNull ?: 0,
                    startHour = dt["startHour"]?.jsonPrimitive?.intOrNull ?: 0,
                    startMinute = dt["startMinute"]?.jsonPrimitive?.intOrNull ?: 0,
                    endHour = dt["endHour"]?.jsonPrimitive?.intOrNull ?: 0,
                    endMinute = dt["endMinute"]?.jsonPrimitive?.intOrNull ?: 0
                )
            } ?: emptyList()
            Schedule(id, name, TimeSlot(dayTimes), linkedModeIds, hasEndTime)
        }

        val nfcTags = nfcTagsArray.map { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: ""
            val name = obj["name"]?.jsonPrimitive?.content ?: ""
            val linkedModeIds = obj["linkedModeIds"]?.jsonArray?.map { it.jsonPrimitive.content }
                ?: run {
                    val legacy = obj["linkedModeId"]?.jsonPrimitive?.contentOrNull
                    if (legacy != null) listOf(legacy) else emptyList()
                }
            NfcTag(id, name, linkedModeIds)
        }

        return ExportData(modes = modes, schedules = schedules, nfcTags = nfcTags)
    }

    /** Normalize imported data: migrate legacy fields, ensure consistent state */
    private fun migrateExportData(data: ExportData): ExportData {
        val migratedModes = data.modes.map { mode ->
            val effectiveIds = mode.effectiveNfcTagIds
            if (effectiveIds != mode.nfcTagIds || @Suppress("DEPRECATION") mode.nfcTagId != null) {
                mode.copy(nfcTagId = null, nfcTagIds = effectiveIds)
            } else mode
        }
        return data.copy(modes = migratedModes)
    }

    private fun emptyJsonArray() = kotlinx.serialization.json.JsonArray(emptyList())

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
                sb.appendLine("    nfcTagIds:")
                val effectiveIds = mode.effectiveNfcTagIds
                if (effectiveIds.isEmpty()) {
                    sb.appendLine("      []")
                } else {
                    for (tagId in effectiveIds) {
                        sb.appendLine("      - \"${escapeYaml(tagId)}\"")
                    }
                }
                sb.appendLine("    tagUnlockLimits:")
                if (mode.tagUnlockLimits.isEmpty()) {
                    sb.appendLine("      {}")
                } else {
                    for ((tid, limit) in mode.tagUnlockLimits) {
                        sb.appendLine("      \"${escapeYaml(tid)}\": ${limit ?: "null"}")
                    }
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
                if (curIndent < baseIndent) break
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
                    var nfcTagIds = listOf<String>(); var blockedApps = listOf<String>()
                    var tagUnlockLimits = mapOf<String, Long?>()
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
                            mt.startsWith("nfcTagIds:") -> {
                                advance()
                                nfcTagIds = parseStringList(6)
                            }
                            mt.startsWith("nfcTagId:") -> {
                                // Legacy single-tag format migration
                                val v = mt.removePrefix("nfcTagId:").trim()
                                if (v != "null") {
                                    nfcTagIds = listOf(parseQuotedString(v))
                                }
                                advance()
                            }
                            mt.startsWith("tagUnlockLimits:") -> {
                                advance()
                                val limits = mutableMapOf<String, Long?>()
                                if (currentLine()?.trim() == "{}") {
                                    advance()
                                } else {
                                    while (currentLine() != null) {
                                        val ll = currentLine()!!
                                        if (ll.isBlank()) { advance(); continue }
                                        if (indent(ll) < 6) break
                                        val lt = ll.trim()
                                        val colonIdx = lt.indexOf(":")
                                        if (colonIdx != -1) {
                                            val tid = parseQuotedString(lt.substring(0, colonIdx).trim())
                                            val valStr = lt.substring(colonIdx + 1).trim()
                                            val limit = if (valStr == "null") null else valStr.toLongOrNull()
                                            limits[tid] = limit
                                            advance()
                                        } else {
                                            break
                                        }
                                    }
                                }
                                tagUnlockLimits = limits
                            }
                            mt.startsWith("blockedApps:") -> {
                                advance()
                                blockedApps = parseStringList(6)
                            }
                            else -> advance()
                        }
                    }
                    modes.add(Mode(id, name, blockedApps, blockMode, nfcTagIds = nfcTagIds, tagUnlockLimits = tagUnlockLimits))
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
                            st.startsWith("modeId:") -> {
                                // Legacy single-mode format migration
                                val v = st.removePrefix("modeId:").trim()
                                if (v != "null") {
                                    linkedModeIds = listOf(parseQuotedString(v))
                                }
                                advance()
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
                            tt.startsWith("linkedModeId:") -> {
                                // Legacy single-mode format migration
                                val v = tt.removePrefix("linkedModeId:").trim()
                                if (v != "null") {
                                    linkedModeIds = listOf(parseQuotedString(v))
                                }
                                advance()
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