package com.andebugulin.nfcguard.sync

import com.andebugulin.nfcguard.AppState
import com.andebugulin.nfcguard.data.AppLogger
import com.andebugulin.nfcguard.receiver.ScheduleAlarmReceiver
import com.andebugulin.nfcguard.Schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Date

/**
 * Wraps AlarmManager for the two per-mode timed alarms.
 *
 * Schedule/cancel are idempotent — `FLAG_UPDATE_CURRENT` overwrites any
 * existing alarm with the same request code, and cancelling a missing
 * alarm is a no-op (we use `FLAG_NO_CREATE` to look up by request code
 * without registering a new PendingIntent).
 *
 * Callers don't invoke these directly; [StateSyncer] diffs old vs new
 * `AppState` and dispatches schedule/cancel as the maps change.
 */
internal object TimedAlarms {

    fun scheduleDeactivation(context: Context, modeId: String, deactivateAtMillis: Long) {
        schedule(context, ACTION_DEACTIVATE, modeId, deactivateAtMillis, "deactivation")
    }

    fun cancelDeactivation(context: Context, modeId: String) {
        cancel(context, ACTION_DEACTIVATE, modeId, "deactivation")
    }

    fun scheduleReactivation(context: Context, modeId: String, reactivateAtMillis: Long) {
        schedule(context, ACTION_REACTIVATE, modeId, reactivateAtMillis, "reactivation")
    }

    fun cancelReactivation(context: Context, modeId: String) {
        cancel(context, ACTION_REACTIVATE, modeId, "reactivation")
    }

    private fun schedule(
        context: Context,
        action: String,
        modeId: String,
        triggerAtMillis: Long,
        label: String
    ) {
        try {
            val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                this.action = action
                putExtra("mode_id", modeId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCodeFor(action, modeId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            AppLogger.log("TIMER", "Scheduled $label for mode $modeId at ${Date(triggerAtMillis)}")
        } catch (e: Exception) {
            AppLogger.log("TIMER", "Error scheduling $label for mode $modeId: ${e.message}")
        }
    }

    private fun cancel(context: Context, action: String, modeId: String, label: String) {
        try {
            val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                this.action = action
                putExtra("mode_id", modeId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCodeFor(action, modeId), intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(it)
            }
        } catch (e: Exception) {
            AppLogger.log("TIMER", "Error cancelling $label for mode $modeId: ${e.message}")
        }
    }

    /**
     * Mirrors the original convention so existing scheduled alarms keep
     * matching after this refactor: `"timed_<modeId>".hashCode()` for
     * deactivations, `"reactivate_<modeId>".hashCode()` for reactivations.
     */
    private fun requestCodeFor(action: String, modeId: String): Int = when (action) {
        ACTION_DEACTIVATE -> "timed_$modeId".hashCode()
        ACTION_REACTIVATE -> "reactivate_$modeId".hashCode()
        else -> error("Unknown action $action")
    }

    private const val ACTION_DEACTIVATE = "com.andebugulin.nfcguard.TIMED_DEACTIVATE_MODE"
    private const val ACTION_REACTIVATE = "com.andebugulin.nfcguard.TIMED_REACTIVATE_MODE"
}
