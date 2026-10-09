package com.andebugulin.nfcguard.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Never shrink below this fraction of the requested size; past it, wrapping is the lesser evil. */
private const val MIN_FIT_SCALE = 0.6f

/**
 * Text that never splits a word in half.
 *
 * The all-caps, wide-tracking labels are the app's look, and on a narrow phone
 * (or a large system font) a single word like ACCESSIBILITY can be wider than
 * the space it is given. Plain [Text] then breaks it mid-word, which reads as
 * broken. This measures the widest word and, only if it cannot fit, scales the
 * font and tracking down just enough. On any screen where the text already
 * fits it renders exactly like [Text].
 *
 * With [maxLines] = 1 the whole string is fitted instead, for button labels.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE
) {
    val merged = LocalTextStyle.current.merge(style)
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth
        val scale = remember(text, merged, maxWidth, maxLines) {
            if (maxWidth == Constraints.Infinity || text.isBlank()) return@remember 1f
            val pieces = if (maxLines == 1) listOf(text) else text.split(Regex("\\s+"))
            val widest = pieces.maxOf { piece ->
                measurer.measure(piece, merged, softWrap = false, maxLines = 1).size.width
            }
            if (widest <= maxWidth) 1f else (maxWidth.toFloat() / widest).coerceAtLeast(MIN_FIT_SCALE)
        }
        val fitted = if (scale < 1f) {
            merged.copy(fontSize = merged.fontSize * scale, letterSpacing = merged.letterSpacing * scale)
        } else {
            merged
        }
        Text(
            text,
            style = fitted,
            color = color,
            textAlign = textAlign,
            maxLines = maxLines,
            softWrap = maxLines != 1,
            // Only reached when even the smallest allowed size cannot fit.
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * [main] with [trailing] beside it, or below it when there is no room.
 *
 * Cards put their action (ACTIVATE, GRANT, an ACTIVE badge) to the right of the
 * text. On a narrow screen that squeezed the text into a sliver one word wide.
 * Here [trailing] keeps its natural width, and if that would leave [main] less
 * than [minMainWidth], it moves underneath instead, start-aligned and inset by
 * [stackedIndent] (so it can line up with text that follows a leading icon).
 */
@Composable
fun AdaptiveRow(
    modifier: Modifier = Modifier,
    minMainWidth: Dp = 150.dp,
    spacing: Dp = 12.dp,
    stackedIndent: Dp = 0.dp,
    main: @Composable () -> Unit,
    trailing: @Composable () -> Unit
) {
    Layout(
        contents = listOf(main, trailing),
        modifier = modifier
    ) { (mainMeasurables, trailingMeasurables), constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val trailingPlaceables = trailingMeasurables.map { it.measure(loose) }
        val trailingWidth = trailingPlaceables.maxOfOrNull { it.width } ?: 0
        val trailingHeight = trailingPlaceables.maxOfOrNull { it.height } ?: 0

        val sideBySide = trailingPlaceables.isEmpty() ||
            constraints.maxWidth == Constraints.Infinity ||
            constraints.maxWidth - trailingWidth - gap >= minMainWidth.roundToPx()

        val bounded = constraints.maxWidth != Constraints.Infinity
        val mainConstraints = when {
            !bounded -> loose
            sideBySide && trailingPlaceables.isNotEmpty() -> {
                val w = (constraints.maxWidth - trailingWidth - gap).coerceAtLeast(0)
                loose.copy(minWidth = w, maxWidth = w)
            }
            else -> loose.copy(minWidth = constraints.maxWidth)
        }
        val mainPlaceables = mainMeasurables.map { it.measure(mainConstraints) }
        val mainWidth = mainPlaceables.maxOfOrNull { it.width } ?: 0
        val mainHeight = mainPlaceables.maxOfOrNull { it.height } ?: 0

        val width = if (bounded) constraints.maxWidth else mainWidth + gap + trailingWidth

        if (sideBySide) {
            val height = maxOf(mainHeight, trailingHeight)
            layout(width, height) {
                mainPlaceables.forEach {
                    it.place(0, Alignment.CenterVertically.align(it.height, height))
                }
                trailingPlaceables.forEach {
                    it.place(width - it.width, Alignment.CenterVertically.align(it.height, height))
                }
            }
        } else {
            val height = mainHeight + gap + trailingHeight
            layout(width, height) {
                mainPlaceables.forEach { it.place(0, 0) }
                val indent = stackedIndent.roundToPx()
                trailingPlaceables.forEach { it.place(indent, mainHeight + gap) }
            }
        }
    }
}
