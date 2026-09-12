package com.andebugulin.nfcguard.data

import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.persistedStateJson
import com.andebugulin.nfcguard.testing.resetAppStateRepository
import com.andebugulin.nfcguard.testing.seedPersistedState

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the single owner of `guardian_prefs:app_state` — persistence,
 * the legacy `Mode.nfcTagId` migration, and the write semantics that the
 * rest of the app relies on (atomicity, no-op short-circuit, updateWith).
 */
@RunWith(RobolectricTestRunner::class)
class AppStateRepositoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        resetAppStateRepository()
        grantOverlayPermission()
    }

    @Test fun `starts empty when nothing is persisted`() {
        val repo = AppStateRepository.getInstance(context)
        assertEquals(0, repo.current.modes.size)
        assertNull(persistedStateJson(context))
    }

    @Test fun `update persists and is visible through current`() = runTest {
        val repo = AppStateRepository.getInstance(context)
        repo.update { it.copy(modes = listOf(mode(id = "m1", name = "Deep Work"))) }

        assertEquals("Deep Work", repo.current.modes.single().name)
        assertTrue(persistedStateJson(context)!!.contains("Deep Work"))
    }

    @Test fun `update survives a repository rebuild`() = runTest {
        AppStateRepository.getInstance(context).update { it.copy(modes = listOf(mode(id = "m9"))) }

        resetAppStateRepository()
        val reloaded = AppStateRepository.getInstance(context)

        assertEquals("m9", reloaded.current.modes.single().id)
    }

    @Test fun `writing an identical state is a silent no-op`() = runTest {
        val repo = AppStateRepository.getInstance(context)
        repo.update { it }   // transform returns the same value

        // Nothing was written, so the key is still absent.
        assertNull(persistedStateJson(context))
    }

    @Test fun `updateWith returns the caller value and still persists`() = runTest {
        val repo = AppStateRepository.getInstance(context)

        val returned: String = repo.updateWith { state ->
            state.copy(activeModes = setOf("m1")) to "activated"
        }

        assertEquals("activated", returned)
        assertEquals(setOf("m1"), repo.current.activeModes)
    }

    @Test fun `state flow reflects the latest write`() = runTest {
        val repo = AppStateRepository.getInstance(context)
        repo.update { it.copy(activeModes = setOf("a", "b")) }
        assertEquals(setOf("a", "b"), repo.state.value.activeModes)
    }

    @Test fun `corrupt persisted json falls back to empty state instead of crashing`() {
        seedPersistedState(context, "{ this is not json")
        val repo = AppStateRepository.getInstance(context)
        assertEquals(0, repo.current.modes.size)
    }

    // ─── Legacy nfcTagId migration ────────────────────────────────────────

    @Test fun `legacy single nfcTagId is migrated into nfcTagIds`() {
        seedPersistedState(context, """
            {"modes":[{"id":"m1","name":"Old","blockedApps":["com.x"],"nfcTagId":"tag-legacy"}]}
        """.trimIndent())

        val migrated = AppStateRepository.getInstance(context).current.modes.single()

        assertEquals(listOf("tag-legacy"), migrated.nfcTagIds)
        @Suppress("DEPRECATION")
        assertNull(migrated.nfcTagId)
    }

    @Test fun `migration is written back to prefs, not just held in memory`() {
        seedPersistedState(context, """
            {"modes":[{"id":"m1","name":"Old","blockedApps":[],"nfcTagId":"tag-legacy"}]}
        """.trimIndent())
        AppStateRepository.getInstance(context)   // triggers migration

        val raw = persistedStateJson(context)!!
        assertTrue("nfcTagIds should be persisted", raw.contains("tag-legacy"))

        // A fresh repo reading the migrated blob needs no second migration.
        resetAppStateRepository()
        val reloaded = AppStateRepository.getInstance(context).current.modes.single()
        assertEquals(listOf("tag-legacy"), reloaded.nfcTagIds)
    }

    @Test fun `modes already using nfcTagIds are left untouched`() {
        seedPersistedState(context, """
            {"modes":[{"id":"m1","name":"New","blockedApps":[],"nfcTagIds":["a","b"]}]}
        """.trimIndent())

        val m = AppStateRepository.getInstance(context).current.modes.single()
        assertEquals(listOf("a", "b"), m.nfcTagIds)
    }

    @Test fun `unknown json keys from a newer version are ignored`() {
        seedPersistedState(context, """
            {"modes":[],"somethingFromTheFuture":42}
        """.trimIndent())
        assertEquals(0, AppStateRepository.getInstance(context).current.modes.size)
    }
}
