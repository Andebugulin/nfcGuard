package com.andebugulin.nfcguard

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Single owner of the persisted `AppState`.
 *
 * One process-wide instance backed by `guardian_prefs:app_state`.
 * All future writers should go through [update]; readers should observe
 * [state] (or read [current] for a snapshot).
 *
 * During the migration window (Phase 1.2 → 1.4) some callers still write
 * to SharedPreferences directly. The registered prefs-change listener
 * bridges those writes back into [state] so the repo's view never goes
 * stale, regardless of who wrote last.
 */
class AppStateRepository private constructor(appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val writeMutex = Mutex()

    private val _state = MutableStateFlow(readFromPrefs())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** Read-only snapshot of the current state. */
    val current: AppState get() = _state.value

    private val externalChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == APP_STATE_KEY) {
                val fresh = readFromPrefs()
                if (fresh != _state.value) _state.value = fresh
            }
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(externalChangeListener)
    }

    /**
     * Atomic read-modify-write: apply [transform] to the latest state and
     * persist the result. Returns the new state. Concurrent calls are
     * serialized through a mutex.
     *
     * If [transform] returns a value equal to the current state, nothing
     * is written (silent no-op).
     */
    suspend fun update(transform: (AppState) -> AppState): AppState = writeMutex.withLock {
        val previous = _state.value
        val next = transform(previous)
        if (next != previous) {
            _state.value = next
            prefs.edit().putString(APP_STATE_KEY, json.encodeToString(next)).apply()
        }
        next
    }

    private fun readFromPrefs(): AppState {
        val raw = prefs.getString(APP_STATE_KEY, null) ?: return AppState()
        return try {
            json.decodeFromString<AppState>(raw)
        } catch (e: Exception) {
            AppLogger.log("REPO", "Failed to decode app_state, falling back to empty: ${e.message}")
            AppState()
        }
    }

    companion object {
        private const val PREFS_NAME = "guardian_prefs"
        private const val APP_STATE_KEY = "app_state"

        @Volatile
        private var INSTANCE: AppStateRepository? = null

        fun getInstance(context: Context): AppStateRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppStateRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
