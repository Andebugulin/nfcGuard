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
 * All writers go through [update] / [updateWith]; readers observe
 * [state] (or read [current] for a snapshot).
 */
class AppStateRepository private constructor(private val appContext: Context) {

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
            StateSyncer.sync(appContext, next)
        }
        next
    }

    /**
     * Variant of [update] that also returns a caller-supplied value
     * alongside the new state — useful when the transform produces a
     * result object (e.g. a sealed `ScheduleActivationResult`) that
     * the caller needs for post-write logging or side effects.
     *
     * The transform returns `(newState, returnValue)`. Mutex semantics
     * and the no-op equality short-circuit are the same as [update].
     */
    suspend fun <R> updateWith(transform: (AppState) -> Pair<AppState, R>): R = writeMutex.withLock {
        val previous = _state.value
        val (next, returned) = transform(previous)
        if (next != previous) {
            _state.value = next
            prefs.edit().putString(APP_STATE_KEY, json.encodeToString(next)).apply()
            StateSyncer.sync(appContext, next)
        }
        returned
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
