package com.andebugulin.nfcguard.data

import com.andebugulin.nfcguard.StatsLog
import com.andebugulin.nfcguard.StatsLogic

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Single owner of the on-device stats log (`guardian_stats:stats_log`).
 *
 * Deliberately separate from [AppStateRepository]: stats are written on
 * every blocked attempt and must not fire `StateSyncer` (service restart,
 * alarms, widgets). They never leave the device and are not part of config
 * export. Writers are the blocker service (its own thread) and the view
 * model (main thread), so writes are serialized with a plain lock.
 */
class StatsRepository private constructor(appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Any()

    private val _log = MutableStateFlow(read())
    val log: StateFlow<StatsLog> = _log.asStateFlow()

    fun recordBlockedAttempt(packageName: String) =
        write { StatsLogic.recordBlockedAttempt(it, packageName, LocalDate.now()) }

    fun recordNfcUnlock() = write { StatsLogic.recordNfcUnlock(it, LocalDate.now()) }

    fun recordRecovery() = write { StatsLogic.recordRecovery(it, LocalDate.now()) }

    private fun write(transform: (StatsLog) -> StatsLog) = synchronized(lock) {
        val previous = _log.value
        val next = transform(previous)
        if (next != previous) {
            _log.value = next
            prefs.edit().putString(LOG_KEY, json.encodeToString(next)).apply()
        }
    }

    private fun read(): StatsLog {
        val raw = prefs.getString(LOG_KEY, null) ?: return StatsLog()
        return try {
            json.decodeFromString<StatsLog>(raw)
        } catch (e: Exception) {
            AppLogger.log("STATS", "Failed to decode stats, starting fresh: ${e.message}")
            StatsLog()
        }
    }

    companion object {
        private const val PREFS_NAME = "guardian_stats"
        private const val LOG_KEY = "stats_log"

        @Volatile
        private var INSTANCE: StatsRepository? = null

        fun getInstance(context: Context): StatsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StatsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
