package com.andebugulin.nfcguard.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andebugulin.nfcguard.BlockMode
import com.andebugulin.nfcguard.R
import com.andebugulin.nfcguard.data.AppStateRepository
import com.andebugulin.nfcguard.testing.grantOverlayPermission
import com.andebugulin.nfcguard.testing.mode
import com.andebugulin.nfcguard.testing.resetAppStateRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The widget was the first item on TESTS.md's gap list. It is reachable on the
 * JVM after all: Robolectric's `ShadowAppWidgetManager` inflates the real
 * `RemoteViews` so the rendered text can be read back, and the button actions
 * are plain broadcasts, so `onReceive` can be called directly.
 *
 * The action strings are duplicated here deliberately. They are private to the
 * provider but they are also a *published contract* — they are baked into
 * `PendingIntent`s that live in the launcher's process and survive app
 * upgrades. Pinning the literals means renaming one breaks this test, which is
 * the point: already-placed widgets would stop working.
 */
@RunWith(RobolectricTestRunner::class)
class GuardianWidgetTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val manager get() = AppWidgetManager.getInstance(app)

    private val actionPrev = "com.andebugulin.nfcguard.WIDGET_PREV"
    private val actionNext = "com.andebugulin.nfcguard.WIDGET_NEXT"
    private val actionDuration = "com.andebugulin.nfcguard.WIDGET_DURATION"
    private val actionActivate = "com.andebugulin.nfcguard.WIDGET_ACTIVATE"
    private val actionOpenApp = "com.andebugulin.nfcguard.WIDGET_OPEN_APP"

    @Before fun setUp() {
        resetAppStateRepository()
        grantOverlayPermission()
        app.getSharedPreferences("guardian_widget_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun repo() = AppStateRepository.getInstance(app)

    private fun placeWidget(): Int =
        shadowOf(manager).createWidget(GuardianWidget::class.java, R.layout.guardian_widget)

    private fun textOf(widgetId: Int, viewId: Int): String =
        shadowOf(manager).getViewFor(widgetId).findViewById<TextView>(viewId).text.toString()

    private fun visibilityOf(widgetId: Int, viewId: Int): Int =
        shadowOf(manager).getViewFor(widgetId).findViewById<View>(viewId).visibility

    /** Button taps reach the provider as broadcasts, exactly as the launcher sends them. */
    private fun press(widgetId: Int, action: String) {
        GuardianWidget().onReceive(
            app,
            Intent(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        )
    }

    private fun seedModes(vararg names: String) = runBlocking {
        repo().update { state ->
            state.copy(modes = names.mapIndexed { i, n -> mode(id = "m$i", name = n) })
        }
    }

    // ---------------- rendering ----------------

    @Test fun `with no modes it invites the user into the app instead of offering activation`() {
        val id = placeWidget()

        assertEquals("NO MODES", textOf(id, R.id.tv_mode_name))
        assertEquals("CREATE A MODE IN THE APP", textOf(id, R.id.tv_status))
        assertEquals("OPEN APP", textOf(id, R.id.btn_action))
        assertEquals("nothing to cycle through", View.INVISIBLE, visibilityOf(id, R.id.btn_prev_mode))
        assertEquals("nothing to cycle through", View.INVISIBLE, visibilityOf(id, R.id.btn_next_mode))
    }

    @Test fun `with an inactive mode it offers the duration and ACTIVATE`() {
        seedModes("Deep Work")
        val id = placeWidget()

        assertEquals("DEEP WORK", textOf(id, R.id.tv_mode_name))
        assertEquals("UNTIL NFC / SCHEDULE", textOf(id, R.id.tv_status))
        assertEquals("ACTIVATE", textOf(id, R.id.btn_action))
        assertEquals(View.VISIBLE, visibilityOf(id, R.id.btn_prev_mode))
    }

    @Test fun `an active mode reports that NFC is what unlocks it`() {
        seedModes("Deep Work")
        runBlocking { repo().update { it.copy(activeModes = setOf("m0")) } }
        val id = placeWidget()

        assertEquals("● ACTIVE · NFC TO UNLOCK", textOf(id, R.id.tv_status))
        assertEquals("OPEN APP", textOf(id, R.id.btn_action))
    }

    @Test fun `an active timed mode reports when it ends`() {
        seedModes("Deep Work")
        runBlocking {
            repo().update {
                it.copy(
                    activeModes = setOf("m0"),
                    timedModeDeactivations = mapOf("m0" to System.currentTimeMillis() + 600_000)
                )
            }
        }
        val id = placeWidget()

        assertTrue(
            "expected an end time, got '${textOf(id, R.id.tv_status)}'",
            textOf(id, R.id.tv_status).startsWith("● ACTIVE · UNTIL ")
        )
    }

    @Test fun `a mode of the opposite polarity is reported as conflicting, not activatable`() {
        runBlocking {
            repo().update {
                it.copy(
                    modes = listOf(
                        mode(id = "m0", name = "Allow Only", blockMode = BlockMode.ALLOW_SELECTED),
                        mode(id = "m1", name = "Blocker", blockMode = BlockMode.BLOCK_SELECTED)
                    ),
                    activeModes = setOf("m1")
                )
            }
        }
        val id = placeWidget()

        assertEquals("CONFLICTS WITH ACTIVE MODE", textOf(id, R.id.tv_status))
        assertEquals("OPEN APP", textOf(id, R.id.btn_action))
    }

    @Test fun `a mode of the same polarity can still stack onto an active one`() {
        runBlocking {
            repo().update {
                it.copy(
                    modes = listOf(
                        mode(id = "m0", name = "Second", blockMode = BlockMode.BLOCK_SELECTED),
                        mode(id = "m1", name = "First", blockMode = BlockMode.BLOCK_SELECTED)
                    ),
                    activeModes = setOf("m1")
                )
            }
        }
        val id = placeWidget()

        assertEquals("ACTIVATE", textOf(id, R.id.btn_action))
    }

    // ---------------- buttons ----------------

    @Test fun `next and prev cycle the selected mode and wrap around`() {
        seedModes("One", "Two", "Three")
        val id = placeWidget()
        assertEquals("ONE", textOf(id, R.id.tv_mode_name))

        press(id, actionNext)
        assertEquals("TWO", textOf(id, R.id.tv_mode_name))

        press(id, actionPrev)
        assertEquals("ONE", textOf(id, R.id.tv_mode_name))

        // Backwards off the start wraps to the end rather than crashing on -1.
        press(id, actionPrev)
        assertEquals("THREE", textOf(id, R.id.tv_mode_name))

        press(id, actionNext)
        assertEquals("ONE", textOf(id, R.id.tv_mode_name))
    }

    @Test fun `the duration button cycles the whole list and wraps`() {
        seedModes("Deep Work")
        val id = placeWidget()
        val expected = listOf(
            "UNTIL NFC / SCHEDULE", "FOR 15 MINUTES", "FOR 30 MINUTES",
            "FOR 1 HOUR", "FOR 2 HOURS"
        )

        assertEquals(expected[0], textOf(id, R.id.tv_status))
        for (i in 1 until expected.size) {
            press(id, actionDuration)
            assertEquals(expected[i], textOf(id, R.id.tv_status))
        }
        press(id, actionDuration)
        assertEquals(expected[0], textOf(id, R.id.tv_status))
    }

    @Test fun `ACTIVATE activates the selected mode as a manual activation`() {
        seedModes("One", "Two")
        val id = placeWidget()
        press(id, actionNext)

        press(id, actionActivate)

        assertEquals(setOf("m1"), repo().current.activeModes)
        assertEquals(
            "a widget tap is a manual activation, like a tap in the app",
            setOf("m1"), repo().current.manuallyActivatedModes
        )
        assertTrue(repo().current.timedModeDeactivations.isEmpty())
    }

    @Test fun `ACTIVATE with a duration selected schedules a deactivation`() {
        seedModes("Deep Work")
        val id = placeWidget()
        press(id, actionDuration) // 15 minutes

        val before = System.currentTimeMillis()
        press(id, actionActivate)

        val deadline = repo().current.timedModeDeactivations["m0"]
        assertTrue("expected a timed deactivation, got $deadline", deadline != null)
        assertTrue(
            "15 minutes out, got ${deadline!! - before}ms",
            deadline - before in 14 * 60_000L..16 * 60_000L
        )
    }

    @Test fun `ACTIVATE refuses a mode that conflicts with what is already active`() {
        runBlocking {
            repo().update {
                it.copy(
                    modes = listOf(
                        mode(id = "m0", name = "Allow Only", blockMode = BlockMode.ALLOW_SELECTED),
                        mode(id = "m1", name = "Blocker", blockMode = BlockMode.BLOCK_SELECTED)
                    ),
                    activeModes = setOf("m1")
                )
            }
        }
        val id = placeWidget()

        press(id, actionActivate)

        assertEquals("the conflicting mode must not activate", setOf("m1"), repo().current.activeModes)
    }

    @Test fun `ACTIVATE clears any pending NFC reactivation for that mode`() {
        seedModes("Deep Work")
        runBlocking {
            repo().update {
                it.copy(timedModeReactivations = mapOf("m0" to System.currentTimeMillis() + 60_000))
            }
        }
        val id = placeWidget()

        press(id, actionActivate)

        assertFalse(
            "re-activating by hand supersedes the scheduled reactivation",
            repo().current.timedModeReactivations.containsKey("m0")
        )
    }

    @Test fun `the settings and OPEN APP buttons launch MainActivity`() {
        val id = placeWidget()

        press(id, actionOpenApp)

        val started = shadowOf(app).nextStartedActivity
        assertEquals(
            "com.andebugulin.nfcguard.ui.MainActivity",
            started?.component?.className
        )
    }

    @Test fun `an intent with no widget id is ignored rather than crashing`() {
        seedModes("Deep Work")
        GuardianWidget().onReceive(app, Intent(actionActivate))
        assertTrue(repo().current.activeModes.isEmpty())
    }

    @Test fun `deleting a widget forgets its selection so a new one starts fresh`() {
        seedModes("One", "Two")
        val id = placeWidget()
        press(id, actionNext)
        press(id, actionDuration)

        GuardianWidget().onDeleted(app, intArrayOf(id))

        val prefs = app.getSharedPreferences("guardian_widget_prefs", Context.MODE_PRIVATE)
        assertEquals(0, prefs.getInt("mode_index_$id", 0))
        assertEquals(0, prefs.getInt("duration_$id", 0))
    }

    @Test fun `a stale selection index beyond the mode list is clamped, not crashed on`() {
        seedModes("One", "Two", "Three")
        val id = placeWidget()
        press(id, actionNext)
        press(id, actionNext) // index 2

        // The user deletes modes in the app until only one is left, then the
        // launcher refreshes the widget — the saved index 2 no longer exists.
        runBlocking { repo().update { it.copy(modes = listOf(mode(id = "m0", name = "One"))) } }
        GuardianWidget().onUpdate(app, manager, intArrayOf(id))

        assertEquals("ONE", textOf(id, R.id.tv_mode_name))
    }
}
