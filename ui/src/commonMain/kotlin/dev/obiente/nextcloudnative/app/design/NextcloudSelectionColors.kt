package dev.obiente.nextcloudnative.app.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Navigation selection colors shared by the desktop sidebar, bottom bar, and rail. The selected
 * entry gets a soft tint of the accent with a faint outline, and its icon takes the accent, so
 * the selection reads clearly without a heavy filled block. Selected labels also use a heavier
 * weight at the call site, so selection never depends on color alone.
 */
@Immutable
data class NextcloudSelectionColors(
    val fill: Color,
    val outline: Color,
    val icon: Color,
    val selectedLabel: Color,
    val unselectedContent: Color,
)

@Composable
@ReadOnlyComposable
fun nextcloudSelectionColors(accent: Color = MaterialTheme.colorScheme.primary): NextcloudSelectionColors {
    val scheme = MaterialTheme.colorScheme
    return NextcloudSelectionColors(
        fill = accent.copy(alpha = SelectionFillAlpha),
        outline = accent.copy(alpha = SelectionOutlineAlpha),
        icon = accent,
        selectedLabel = scheme.onSurface,
        unselectedContent = scheme.onSurfaceVariant,
    )
}

internal const val SelectionFillAlpha = 0.13f
private const val SelectionOutlineAlpha = 0.2f
