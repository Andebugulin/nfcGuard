package com.andebugulin.nfcguard.ui.onboarding

import com.andebugulin.nfcguard.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import com.andebugulin.nfcguard.ui.components.FitText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
            Page(context.getString(R.string.home_nfcguard), context.getString(R.string.onb_apps_you_cant_open)) { PurposePreview(it) },
            Page(context.getString(R.string.home_modes), context.getString(R.string.onb_pick_whats_blocked)) { ModesPreview(it) },
            Page(context.getString(R.string.home_schedules), context.getString(R.string.onb_turn_on_by_themselves)) { SchedulesPreview(it) },
            Page(context.getString(R.string.home_nfc_tags), context.getString(R.string.onb_tap_to_unlock)) { NfcPreview(it) },
            Page(context.getString(R.string.onb_lost_your_tag), context.getString(R.string.onb_you_can_still_get_back)) {
                SafetyPreview(modifier = it, seconds = challengeSeconds)
            },
            Page(context.getString(R.string.home_permissions), context.getString(R.string.onb_let_it_work)) { PermissionsPage(it) }
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
    // Only on a first-run tour, until the first NEXT or a swipe; an install
    // resuming at permissions has seen the tour already.
    var showHint by rememberSaveable { mutableStateOf(!startAtPermissions) }
    LaunchedEffect(page) { if (page != 0) showHint = false }
    // Where NEXT is, so the hint can leave it lit.
    var nextBounds by remember { mutableStateOf<Rect?>(null) }
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
                // Each page scrolls on its own and is centred when it fits. On
                // a short or narrow phone the content used to run into the
                // dots and buttons below instead.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Minimum height: the heading used to shift between
                        // pages because the content below it differed in size.
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = HEADER_HEIGHT),
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            FitText(
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
                        Text(stringResource(R.string.onb_back), style = GuardianType.Label, color = GuardianTheme.TextSecondary)
                    }
                } else {
                    Spacer(Modifier.width(80.dp))
                }

                GuardianButton(
                    label = if (page < lastIndex) stringResource(R.string.onb_next) else stringResource(R.string.onb_get_started),
                    kind = ButtonKind.Primary,
                    modifier = Modifier.onGloballyPositioned { nextBounds = it.boundsInRoot() },
                    onClick = {
                        if (showHint) {
                            // The first press answers the hint; the user then
                            // reads the first page instead of skipping past it.
                            showHint = false
                        } else if (page < lastIndex) {
                            goTo(page + 1)
                        } else {
                            markComplete(context)
                            onComplete()
                        }
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = showHint && nextBounds != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            nextBounds?.let { TourHint(next = it) }
        }
    }
}

/**
 * A light dim over the first page, with NEXT left lit inside a ring and one
 * line saying what is about to happen.
 *
 * People landed on a page of three words and did not realise it was the first
 * of several, or that NEXT was how to move on. This says so without asking
 * anything of them: it takes no input of its own. The first NEXT dismisses it
 * and leaves them on the first page, which the dim was hiding; a swipe goes
 * straight through to the tour and dismisses it too.
 *
 * The ring is a rounded outline hugging the button rather than a circle: a
 * circle around a wide button has to be so large it ran off small screens.
 * Nothing in it moves; a small arrow leads from the text to the ring.
 * The text sits in the empty band between the page and the dots, not on top
 * of the page's own lines.
 *
 * [next] is in root coordinates.
 */
@Composable
private fun TourHint(next: Rect) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var text by remember { mutableStateOf<Rect?>(null) }
    val ink = GuardianTheme.TextPrimary

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() },
        // Two thirds of the way down: below the page, above the dots.
        contentAlignment = BiasAlignment(0f, 0.42f)
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                // Offscreen, so the cleared outline punches through the dim.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            val button = next.translate(-origin)
            val hole = button.inflate(8.dp.toPx())
            val ring = button.inflate(10.dp.toPx())
            val line = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round)
            drawRect(Color.Black.copy(alpha = 0.72f))
            drawRoundRect(
                Color.Transparent,
                topLeft = hole.topLeft,
                size = hole.size,
                cornerRadius = CornerRadius(hole.height / 2),
                blendMode = BlendMode.Clear
            )
            drawRoundRect(
                ink,
                topLeft = ring.topLeft,
                size = ring.size,
                cornerRadius = CornerRadius(ring.height / 2),
                style = Stroke(2.dp.toPx())
            )
            text?.let { drawArrow(from = it, to = ring, color = ink.copy(alpha = 0.8f), stroke = line) }
        }
        Text(
            stringResource(R.string.onb_tour_intro, stringResource(R.string.onb_next)),
            style = GuardianType.Body.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
            color = GuardianTheme.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .onGloballyPositioned { text = it.boundsInParent() }
        )
    }
}

/**
 * A thin curved arrow from just under the [from] text down onto the top of the
 * [to] ring: out of the text vertically, then bending to arrive from above.
 */
private fun DrawScope.drawArrow(from: Rect, to: Rect, color: Color, stroke: Stroke) {
    val start = Offset(from.center.x, from.bottom + 12.dp.toPx())
    val end = Offset(to.center.x, to.top - 8.dp.toPx())
    if (end.y - start.y < 24.dp.toPx()) return
    // Bend at the end's x on the start's level, so the arrow lands pointing down.
    val bend = Offset(end.x, start.y)
    drawPath(
        Path().apply {
            moveTo(start.x, start.y)
            quadraticTo(bend.x, bend.y, end.x, end.y)
        },
        color,
        style = stroke
    )
    val head = 7.dp.toPx()
    drawLine(color, end, Offset(end.x - head * 0.7f, end.y - head), stroke.width, StrokeCap.Round)
    drawLine(color, end, Offset(end.x + head * 0.7f, end.y - head), stroke.width, StrokeCap.Round)
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
