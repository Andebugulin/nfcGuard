package com.andebugulin.nfcguard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.runBlocking

class GuardianWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            renderWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return

        when (intent.action) {
            ACTION_PREV_MODE -> cycleMode(context, id, -1)
            ACTION_NEXT_MODE -> cycleMode(context, id, 1)
            ACTION_CYCLE_DURATION -> cycleDuration(context, id)
            ACTION_ACTIVATE -> activateSelectedMode(context, id)
            ACTION_OPEN_APP -> {
                context.startActivity(Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                })
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (id in appWidgetIds) {
            editor.remove("mode_index_$id")
            editor.remove("duration_$id")
        }
        editor.apply()
    }

    // ======================== Render ========================

    private fun renderWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.guardian_widget)
        val appState = loadAppState(context)
        val wPrefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)

        // Settings button always opens the app
        views.setOnClickPendingIntent(R.id.btn_settings, pending(context, widgetId, ACTION_OPEN_APP, 4))

        val modes = appState.modes
        if (modes.isEmpty()) {
            views.setTextViewText(R.id.tv_mode_name, "NO MODES")
            views.setViewVisibility(R.id.btn_prev_mode, View.INVISIBLE)
            views.setViewVisibility(R.id.btn_next_mode, View.INVISIBLE)
            views.setTextViewText(R.id.tv_status, "CREATE A MODE IN THE APP")
            views.setInt(R.id.tv_status, "setTextColor", COLOR_SUBTLE)
            views.setTextViewText(R.id.btn_action, "OPEN APP")
            views.setOnClickPendingIntent(R.id.btn_action, pending(context, widgetId, ACTION_OPEN_APP, 4))
            manager.updateAppWidget(widgetId, views)
            return
        }

        val index = wPrefs.getInt("mode_index_$widgetId", 0).coerceIn(0, modes.lastIndex)
        val mode = modes[index]
        val isThisModeActive = appState.activeModes.contains(mode.id)
        val anyActive = appState.activeModes.isNotEmpty()

        views.setTextViewText(R.id.tv_mode_name, mode.name.uppercase())

        // Mode cycling arrows — always wire them, toggle visibility per state
        views.setOnClickPendingIntent(R.id.btn_prev_mode, pending(context, widgetId, ACTION_PREV_MODE, 0))
        views.setOnClickPendingIntent(R.id.btn_next_mode, pending(context, widgetId, ACTION_NEXT_MODE, 1))

        when {
            isThisModeActive -> renderActiveState(views, context, widgetId, appState, mode)
            anyActive -> renderOtherActiveState(views, context, widgetId, wPrefs, appState, mode)
            else -> renderInactiveState(views, context, widgetId, wPrefs)
        }

        manager.updateAppWidget(widgetId, views)
    }

    /** Selected mode is currently active */
    private fun renderActiveState(
        views: RemoteViews, context: Context, widgetId: Int,
        appState: AppState, mode: Mode
    ) {
        views.setViewVisibility(R.id.btn_prev_mode, View.VISIBLE)
        views.setViewVisibility(R.id.btn_next_mode, View.VISIBLE)

        val isTimed = appState.timedModeDeactivations.containsKey(mode.id)
        val statusText = if (isTimed) {
            val endTime = appState.timedModeDeactivations[mode.id] ?: 0
            "● ACTIVE · UNTIL ${formatTime(endTime)}"
        } else {
            "● ACTIVE · NFC TO UNLOCK"
        }
        views.setTextViewText(R.id.tv_status, statusText)
        views.setInt(R.id.tv_status, "setTextColor", COLOR_WHITE)

        views.setTextViewText(R.id.btn_action, "OPEN APP")
        views.setInt(R.id.btn_action, "setBackgroundColor", COLOR_WHITE)
        views.setInt(R.id.btn_action, "setTextColor", COLOR_BLACK)
        views.setOnClickPendingIntent(R.id.btn_action, pending(context, widgetId, ACTION_OPEN_APP, 4))
    }

    /** A different mode is active — check if this one can stack */
    private fun renderOtherActiveState(
        views: RemoteViews, context: Context, widgetId: Int,
        wPrefs: android.content.SharedPreferences, appState: AppState, mode: Mode
    ) {
        views.setViewVisibility(R.id.btn_prev_mode, View.VISIBLE)
        views.setViewVisibility(R.id.btn_next_mode, View.VISIBLE)

        val activeBlockModes = appState.modes
            .filter { appState.activeModes.contains(it.id) }
            .map { it.blockMode }.toSet()
        val wouldConflict = activeBlockModes.isNotEmpty() && !activeBlockModes.contains(mode.blockMode)

        if (wouldConflict) {
            views.setTextViewText(R.id.tv_status, "CONFLICTS WITH ACTIVE MODE")
            views.setInt(R.id.tv_status, "setTextColor", COLOR_WARNING)
            views.setTextViewText(R.id.btn_action, "OPEN APP")
            views.setInt(R.id.btn_action, "setBackgroundColor", COLOR_SURFACE)
            views.setInt(R.id.btn_action, "setTextColor", COLOR_WHITE)
            views.setOnClickPendingIntent(R.id.btn_action, pending(context, widgetId, ACTION_OPEN_APP, 4))
        } else {
            // Same block type — can stack
            showDurationAndActivate(views, context, widgetId, wPrefs)
        }
    }

    /** No modes active — full activate UI */
    private fun renderInactiveState(
        views: RemoteViews, context: Context, widgetId: Int,
        wPrefs: android.content.SharedPreferences
    ) {
        views.setViewVisibility(R.id.btn_prev_mode, View.VISIBLE)
        views.setViewVisibility(R.id.btn_next_mode, View.VISIBLE)
        showDurationAndActivate(views, context, widgetId, wPrefs)
    }

    /** Shared UI: duration selector + ACTIVATE button */
    private fun showDurationAndActivate(
        views: RemoteViews, context: Context, widgetId: Int,
        wPrefs: android.content.SharedPreferences
    ) {
        val durationIndex = wPrefs.getInt("duration_$widgetId", 0).coerceIn(0, DURATIONS.lastIndex)
        views.setTextViewText(R.id.tv_status, DURATION_LABELS[durationIndex])
        views.setInt(R.id.tv_status, "setTextColor", COLOR_SUBTLE)
        views.setOnClickPendingIntent(R.id.tv_status, pending(context, widgetId, ACTION_CYCLE_DURATION, 2))

        views.setTextViewText(R.id.btn_action, "ACTIVATE")
        views.setInt(R.id.btn_action, "setBackgroundColor", COLOR_WHITE)
        views.setInt(R.id.btn_action, "setTextColor", COLOR_BLACK)
        views.setOnClickPendingIntent(R.id.btn_action, pending(context, widgetId, ACTION_ACTIVATE, 3))
    }

    // ======================== Actions ========================

    private fun cycleMode(context: Context, widgetId: Int, direction: Int) {
        val modes = loadAppState(context).modes
        if (modes.isEmpty()) return
        val wPrefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
        val current = wPrefs.getInt("mode_index_$widgetId", 0)
        val next = (current + direction).mod(modes.size)
        wPrefs.edit().putInt("mode_index_$widgetId", next).apply()
        refreshOne(context, widgetId)
    }

    private fun cycleDuration(context: Context, widgetId: Int) {
        val wPrefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
        val current = wPrefs.getInt("duration_$widgetId", 0)
        wPrefs.edit().putInt("duration_$widgetId", (current + 1) % DURATIONS.size).apply()
        refreshOne(context, widgetId)
    }

    private fun activateSelectedMode(context: Context, widgetId: Int) {
        val repo = AppStateRepository.getInstance(context)
        val wPrefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
        val appState = repo.current
        if (appState.modes.isEmpty()) return

        val index = wPrefs.getInt("mode_index_$widgetId", 0).coerceIn(0, appState.modes.lastIndex)
        val mode = appState.modes[index]

        // BLOCK/ALLOW conflict check
        val activeModes = appState.modes.filter { appState.activeModes.contains(it.id) }
        if (activeModes.isNotEmpty() && activeModes.any { it.blockMode != mode.blockMode }) {
            AppLogger.log("WIDGET", "Conflict: cannot activate '${mode.name}'")
            return
        }

        val durationIndex = wPrefs.getInt("duration_$widgetId", 0).coerceIn(0, DURATIONS.lastIndex)
        val durationMinutes = DURATIONS[durationIndex]
        val timedUntil = if (durationMinutes > 0) System.currentTimeMillis() + durationMinutes * 60_000L else null

        AppLogger.log("WIDGET", "Activating '${mode.name}' (timed=${timedUntil != null})")
        runBlocking {
            repo.update { state ->
                val newTimedDeactivations = if (timedUntil != null) {
                    state.timedModeDeactivations + (mode.id to timedUntil)
                } else {
                    state.timedModeDeactivations
                }
                state.copy(
                    activeModes = state.activeModes + mode.id,
                    manuallyActivatedModes = state.manuallyActivatedModes + mode.id,
                    timedModeDeactivations = newTimedDeactivations,
                    timedModeReactivations = state.timedModeReactivations - mode.id
                )
            }
        }
        // All side effects (service, schedule alarms, per-mode timer alarm,
        // widget refresh) dispatched by AppStateRepository via StateSyncer.
    }

    // ======================== Helpers ========================

    private fun loadAppState(context: Context): AppState =
        AppStateRepository.getInstance(context).current

    private fun pending(context: Context, widgetId: Int, action: String, slot: Int): PendingIntent {
        val intent = Intent(context, GuardianWidget::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        return PendingIntent.getBroadcast(
            context, widgetId * 10 + slot, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun refreshOne(context: Context, widgetId: Int) {
        renderWidget(context, AppWidgetManager.getInstance(context), widgetId)
    }

    // ======================== Companion ========================

    companion object {
        private const val WIDGET_PREFS = "guardian_widget_prefs"

        private const val ACTION_PREV_MODE = "com.andebugulin.nfcguard.WIDGET_PREV"
        private const val ACTION_NEXT_MODE = "com.andebugulin.nfcguard.WIDGET_NEXT"
        private const val ACTION_CYCLE_DURATION = "com.andebugulin.nfcguard.WIDGET_DURATION"
        private const val ACTION_ACTIVATE = "com.andebugulin.nfcguard.WIDGET_ACTIVATE"
        private const val ACTION_OPEN_APP = "com.andebugulin.nfcguard.WIDGET_OPEN_APP"

        // Durations in minutes; 0 = unlimited (until NFC / schedule)
        private val DURATIONS = listOf(0L, 15L, 30L, 60L, 120L)
        private val DURATION_LABELS = listOf(
            "UNTIL NFC / SCHEDULE",
            "FOR 15 MINUTES",
            "FOR 30 MINUTES",
            "FOR 1 HOUR",
            "FOR 2 HOURS"
        )

        private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
        private const val COLOR_BLACK = 0xFF000000.toInt()
        private const val COLOR_SUBTLE = 0xFF888888.toInt()
        private const val COLOR_WARNING = 0xFFFF9800.toInt()
        private const val COLOR_SURFACE = 0xFF1A1A1A.toInt()

        /** Call this from anywhere state changes to refresh all widget instances */
        fun notifyAllWidgets(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, GuardianWidget::class.java))
                if (ids.isNotEmpty()) {
                    val intent = Intent(context, GuardianWidget::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                    context.sendBroadcast(intent)
                }
            } catch (_: Exception) { /* widget not placed, ignore */ }
        }

        /** Format epoch millis as "HH:MM" in device local time */
        private fun formatTime(epochMillis: Long): String {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
            return String.format("%02d:%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
        }
    }
}