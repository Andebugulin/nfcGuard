package com.andebugulin.nfcguard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-app logger that captures key events to a file for bug reporting.
 * Works alongside android.util.Log (logcat) — this captures events
 * that users can share without needing ADB.
 *
 * Usage:  AppLogger.log("SERVICE", "Overlay shown for com.example.app")
 */
object AppLogger {

    private const val MAX_ENTRIES = 1000
    private const val LOG_FILE = "guardian_debug.log"
    private const val GITHUB_ISSUES_URL = "https://github.com/Andebugulin/nfcGuard/issues/new"
    // GitHub URL body limit — keep well under browser URL length limits
    private const val GITHUB_BODY_LIMIT = 6000

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val entries = ConcurrentLinkedDeque<String>()

    @Volatile
    private var initialized = false
    private var logFile: File? = null

    /** Call once from Application.onCreate or MainActivity.onCreate */
    fun init(context: Context) {
        if (initialized) return
        logFile = File(context.filesDir, LOG_FILE)
        try {
            if (logFile!!.exists()) {
                logFile!!.readLines().takeLast(MAX_ENTRIES).forEach { entries.addLast(it) }
            }
        } catch (_: Exception) {}
        initialized = true
        log("SYSTEM", "Logger initialized | App launched")
    }

    /** Log an event. Category should be short: SERVICE, SCHEDULE, NFC, MODE, ALARM, UI, SYSTEM */
    fun log(category: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "$timestamp [$category] $message"

        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.pollFirst()
        }

        android.util.Log.d("AppLogger", entry)
        persistAsync()
    }

    private fun persistAsync() {
        try {
            val file = logFile ?: return
            val snapshot = entries.toList()
            Thread {
                try {
                    file.writeText(snapshot.joinToString("\n"))
                } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {}
    }

    /** Get all log entries as a single string */
    fun getLogText(): String = entries.joinToString("\n")

    /** Get entry count */
    fun getEntryCount(): Int = entries.size

    /** Clear all logs */
    fun clear() {
        entries.clear()
        try { logFile?.writeText("") } catch (_: Exception) {}
        log("SYSTEM", "Logs cleared by user")
    }

    /** Build device + permissions + state summary (short, for GitHub issue body) */
    private fun buildSummary(context: Context): String {
        val sb = StringBuilder()

        sb.appendLine("**Device:** ${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        try {
            val overlayOk = Settings.canDrawOverlays(context)
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val usageOk = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            ) == android.app.AppOpsManager.MODE_ALLOWED
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

            sb.appendLine("**Permissions:** Overlay=$overlayOk | UsageAccess=$usageOk | ServiceRunning=${BlockerService.isRunning()}")
        } catch (e: Exception) {
            sb.appendLine("**Permissions:** Error reading (${e.message})")
        }

        try {
            val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            val stateJson = prefs.getString("app_state", null)
            if (stateJson != null) {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val state = json.decodeFromString<AppState>(stateJson)
                sb.appendLine("**State:** ${state.modes.size} modes (${state.activeModes.size} active) | ${state.schedules.size} schedules (${state.activeSchedules.size} active) | ${state.nfcTags.size} tags")
            }
        } catch (_: Exception) {}

        return sb.toString()
    }

    /** Build full diagnostic report for file export */
    fun buildFullReport(context: Context): String {
        val sb = StringBuilder()

        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  GUARDIAN BUG REPORT")
        sb.appendLine("  Generated: ${dateFormat.format(Date())}")
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine()

        sb.appendLine("── DEVICE ──────────────────────────────")
        sb.appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Build: ${Build.DISPLAY}")
        sb.appendLine()

        sb.appendLine("── PERMISSIONS ─────────────────────────")
        try {
            sb.appendLine("Overlay: ${Settings.canDrawOverlays(context)}")
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            sb.appendLine("Usage Access: ${mode == android.app.AppOpsManager.MODE_ALLOWED}")
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            sb.appendLine("Battery Optimized: ${!pm.isIgnoringBatteryOptimizations(context.packageName)}")
        } catch (e: Exception) {
            sb.appendLine("Error: ${e.message}")
        }
        sb.appendLine("Service Running: ${BlockerService.isRunning()}")
        sb.appendLine()

        sb.appendLine("── APP STATE ───────────────────────────")
        try {
            val prefs = context.getSharedPreferences("guardian_prefs", Context.MODE_PRIVATE)
            val stateJson = prefs.getString("app_state", null)
            if (stateJson != null) {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val state = json.decodeFromString<AppState>(stateJson)
                sb.appendLine("Modes: ${state.modes.size}")
                state.modes.forEach { m ->
                    val active = if (state.activeModes.contains(m.id)) " [ACTIVE]" else ""
                    sb.appendLine("  - ${m.name} (${m.blockMode}, ${m.blockedApps.size} apps, nfc=${m.nfcTagId?.take(8) ?: "none"})$active")
                }
                sb.appendLine("Schedules: ${state.schedules.size}")
                state.schedules.forEach { s ->
                    val active = when {
                        state.activeSchedules.contains(s.id) -> " [ACTIVE]"
                        state.deactivatedSchedules.contains(s.id) -> " [DEACTIVATED]"
                        else -> ""
                    }
                    sb.appendLine("  - ${s.name} (${s.linkedModeIds.size} modes, endTime=${s.hasEndTime})$active")
                    s.timeSlot.dayTimes.forEach { dt ->
                        val dayName = when(dt.day) { 1->"Mon"; 2->"Tue"; 3->"Wed"; 4->"Thu"; 5->"Fri"; 6->"Sat"; 7->"Sun"; else->"?" }
                        sb.appendLine("    $dayName ${String.format("%02d:%02d", dt.startHour, dt.startMinute)} - ${String.format("%02d:%02d", dt.endHour, dt.endMinute)}")
                    }
                }
                sb.appendLine("NFC Tags: ${state.nfcTags.size}")
                state.nfcTags.forEach { t ->
                    sb.appendLine("  - ${t.name} (id=${t.id.take(12)}...)")
                }
                sb.appendLine("Active Modes: ${state.activeModes}")
                sb.appendLine("Active Schedules: ${state.activeSchedules}")
                sb.appendLine("Deactivated Schedules: ${state.deactivatedSchedules}")
            } else {
                sb.appendLine("No app state found")
            }
        } catch (e: Exception) {
            sb.appendLine("Error: ${e.message}")
        }
        sb.appendLine()

        sb.appendLine("── EVENT LOG (${entries.size} entries) ────────────")
        sb.appendLine(getLogText())
        sb.appendLine()
        sb.appendLine("═══════════════════════════════════════")
        sb.appendLine("  END OF REPORT")
        sb.appendLine("═══════════════════════════════════════")

        return sb.toString()
    }

    /**
     * Open GitHub new issue page with pre-filled body.
     * Uses <details> markdown for collapsible logs.
     * If logs are too long for URL, truncates and asks user to attach log file.
     */
    fun openGitHubIssue(context: Context) {
        try {
            val summary = buildSummary(context)
            val logText = getLogText()
            val logsTruncated = logText.length > GITHUB_BODY_LIMIT

            val body = buildString {
                appendLine("## Bug Description")
                appendLine("<!-- Write a short description of what went wrong -->")
                appendLine()
                appendLine()
                appendLine("## Steps to Reproduce")
                appendLine("1. ")
                appendLine("2. ")
                appendLine("3. ")
                appendLine()
                appendLine("## Expected Behavior")
                appendLine("<!-- What should have happened? -->")
                appendLine()
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("## Diagnostic Info (auto-filled)")
                appendLine(summary)
                appendLine()
                appendLine("<details>")
                appendLine("<summary>\uD83D\uDCCB Event Log (click to expand)</summary>")
                appendLine()
                appendLine("```")
                if (logsTruncated) {
                    appendLine(logText.takeLast(GITHUB_BODY_LIMIT))
                    appendLine()
                    appendLine("... (truncated \u2014 full log has ${entries.size} entries)")
                    appendLine("Please attach the full log file using the SAVE LOG FILE button in the app.")
                } else {
                    appendLine(logText)
                }
                appendLine("```")
                appendLine()
                appendLine("</details>")

                if (logsTruncated) {
                    appendLine()
                    appendLine("> \u26A0\uFE0F **Logs were truncated.** Use the \"SAVE LOG FILE\" button in Guardian's About screen and attach the file to this issue.")
                }
            }

            val encodedBody = URLEncoder.encode(body, "UTF-8")
            val encodedTitle = URLEncoder.encode("[Bug] ", "UTF-8")
            val url = "$GITHUB_ISSUES_URL?title=$encodedTitle&body=$encodedBody"

            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback: just open issues page
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_ISSUES_URL)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    /** Save full report to a file and open share sheet (for attaching to GitHub issues) */
    fun saveAndShareLogFile(context: Context) {
        try {
            val report = buildFullReport(context)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val reportFile = File(context.cacheDir, "guardian_report_$timestamp.txt")
            reportFile.writeText(report)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                reportFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Guardian Bug Report - $timestamp")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(Intent.createChooser(shareIntent, "Save/Share Log File").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {}
    }
}