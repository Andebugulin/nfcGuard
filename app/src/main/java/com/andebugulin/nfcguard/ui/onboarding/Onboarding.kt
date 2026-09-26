package com.andebugulin.nfcguard.ui.onboarding

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andebugulin.nfcguard.ui.GuardianTheme
import com.andebugulin.nfcguard.ui.components.ButtonKind
import com.andebugulin.nfcguard.ui.components.GuardianButton
import com.andebugulin.nfcguard.ui.components.GuardianType
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager

const val PREFS_NAME = "guardian_prefs"
const val KEY_SEEN_ONBOARDING = "has_seen_onboarding"
const val KEY_SETUP_COMPLETE = "initial_permissions_granted"

/**
 * True while first-run setup still has something left to do.
 *
 * Two flags, because they can come apart: an install that finished the tour on
 * an older build but never got through permissions should land straight on the
 * permissions page rather than replaying the tour.
 */
fun needsOnboarding(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return !prefs.getBoolean(KEY_SEEN_ONBOARDING, false) ||
        !prefs.getBoolean(KEY_SETUP_COMPLETE, false)
}

private fun markComplete(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_SEEN_ONBOARDING, true)
        .putBoolean(KEY_SETUP_COMPLETE, true)
        .apply()
}

/**
 * A page of the tour: a title, a caption of a few words, and something moving.
 *
 * There is deliberately no `description` field. The old carousel had one and it
 * grew to a six-bullet list nobody read; the preview is the explanation now.
 */
private val HEADER_HEIGHT = 84.dp

private data class Page(
    val title: String,
    val caption: String,
    val preview: @Composable (Modifier) -> Unit
)

/**
 * First-run setup.
 *
 * Replaces a five-page prose carousel *and* a seven-dialog permission chain —
 * twelve screens of text before the user reached Home. What survives is a short
 * tour where each page animates the real component it is describing, a safety
 * page that lets the user configure the recovery challenge before they ever
 * need it, and one honest permissions list.
 *
 * [startAtPermissions] skips the tour for an install that has already seen it.
 */
@Composable
fun OnboardingFlow(
    onComplete: () -> Unit,
    startAtPermissions: Boolean = false,
    challengeSeconds: Int = 90
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val pages = remember(challengeSeconds) {
        listOf(
            Page("NFCGUARD", "APPS YOU CAN'T OPEN") { PurposePreview(it) },
            Page("MODES", "PICK WHAT'S BLOCKED") { ModesPreview(it) },
            Page("SCHEDULES", "TURN ON BY THEMSELVES") { SchedulesPreview(it) },
            Page("NFC TAGS", "TAP TO UNLOCK") { NfcPreview(it) },
            Page("LOST YOUR TAG?", "YOU CAN STILL GET BACK IN") {
                SafetyPreview(modifier = it, seconds = challengeSeconds)
            },
            Page("PERMISSIONS", "LET IT WORK") { PermissionsPage(it) }
        )
    }

    val lastIndex = pages.lastIndex
    // The pages look swipeable, so they are. The buttons stay: swiping is the
    // shortcut, not the only way through.
    val pager = rememberPagerState(
        initialPage = if (startAtPermissions) lastIndex else 0,
        pageCount = { pages.size }
    )
    val page = pager.currentPage
    val scope = rememberCoroutineScope()
    fun goTo(target: Int) = scope.launch { pager.animateScrollToPage(target) }

    Box(
        Modifier
            .fillMaxSize()
            .background(GuardianTheme.BackgroundPrimary)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
                // Each page owns its own scrolling where it needs it; the pager
                // must not also try to scroll vertically.
                userScrollEnabled = true
            ) { index ->
                val shown = pages[index]
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Fixed height: the heading used to shift between pages
                    // because the content below it differed in size.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(HEADER_HEIGHT),
                        verticalArrangement = Arrangement.Bottom,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            shown.title,
                            style = GuardianType.ScreenTitle,
                            color = GuardianTheme.TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            shown.caption,
                            style = GuardianType.Label,
                            color = GuardianTheme.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    shown.preview(Modifier.fillMaxWidth())
                }
            }

            PageDots(count = pages.size, current = page)

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (page > 0 && !startAtPermissions) {
                    TextButton(onClick = { goTo(page - 1) }) {
                        Text("BACK", style = GuardianType.Label, color = GuardianTheme.TextSecondary)
                    }
                } else {
                    Spacer(Modifier.width(80.dp))
                }

                GuardianButton(
                    label = if (page < lastIndex) "NEXT" else "GET STARTED",
                    kind = ButtonKind.Primary,
                    onClick = {
                        if (page < lastIndex) {
                            goTo(page + 1)
                        } else {
                            markComplete(context)
                            onComplete()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            val active = index == current
            // The dots used to be static Boxes; widening the active one reads
            // as progress rather than as a row of identical circles.
            val width by animateDpAsState(
                if (active) 20.dp else 8.dp,
                animationSpec = tween(220),
                label = "dot"
            )
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .height(8.dp)
                    .width(width)
                    .then(
                        if (active) {
                            Modifier.background(GuardianTheme.TextPrimary, CircleShape)
                        } else {
                            Modifier.border(1.dp, GuardianTheme.BorderSubtle, CircleShape)
                        }
                    )
            )
        }
    }
}
