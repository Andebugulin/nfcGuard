package com.andebugulin.nfcguard.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * Renders [content] at a fraction of its natural size, laying out as if it
 * really were that small.
 *
 * This exists so onboarding can show the app's *actual* components rather than
 * look-alikes. A plain `Modifier.scale` would shrink the pixels but keep the
 * original footprint, leaving a hole around each card; here the child is
 * measured against widened constraints and the parent reports the scaled size,
 * so several real cards stack correctly in the space one would normally take.
 *
 * Purely visual: the shrunken copy is not meant to be interacted with.
 */
@Composable
fun ScaledDown(
    scale: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(
        content = { Column { content() } },
        modifier = modifier
    ) { measurables, constraints ->
        // Measure the child in the larger space it thinks it has, so its own
        // text sizes and paddings come out exactly as they do in the app.
        val inner = Constraints(
            minWidth = 0,
            maxWidth = if (constraints.maxWidth == Constraints.Infinity) {
                Constraints.Infinity
            } else {
                (constraints.maxWidth / scale).roundToInt()
            },
            minHeight = 0,
            maxHeight = Constraints.Infinity
        )
        val placeable = measurables.first().measure(inner)
        val width = (placeable.width * scale).roundToInt().coerceAtMost(constraints.maxWidth)
        val height = (placeable.height * scale).roundToInt()

        layout(width, height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}
