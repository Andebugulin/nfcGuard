package com.andebugulin.nfcguard.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.andebugulin.nfcguard.ui.GuardianTheme

/**
 * Back arrow + screen title, with optional trailing [actions].
 *
 * This exact Row was copy-pasted into all five sub-screens. There is no
 * `TopAppBar` anywhere in the app and this is not one — Material's app bar
 * brings its own insets, elevation and colour behaviour that the pure-black
 * design would only have to undo.
 *
 * **The rendered title text is screen identity.** The instrumented robots in
 * `androidTest` assert which screen they are on by looking for the literal
 * "MODES" / "SCHEDULES" / "ABOUT", so [title] must keep reaching the tree
 * unchanged.
 *
 * [titleFontSize] defaults to the 24sp four of the five screens use. The mode
 * editor passes a smaller size because its title is a user-chosen mode name,
 * which can be long; the title is capped at two lines either way, so on a
 * narrow screen a long name ends in an ellipsis instead of pushing the screen
 * down one word per line. The About screen passes an empty [contentPadding]
 * because it sits inside a `LazyColumn` that already pads its content.
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleFontSize: TextUnit = GuardianType.ScreenTitle.fontSize,
    fillTitleWidth: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = GuardianTheme.IconPrimary)
        }
        FitText(
            title,
            style = GuardianType.ScreenTitle.copy(fontSize = titleFontSize),
            color = GuardianTheme.TextPrimary,
            maxLines = 2,
            modifier = if (fillTitleWidth) Modifier.weight(1f) else Modifier
        )
        actions()
    }
}
