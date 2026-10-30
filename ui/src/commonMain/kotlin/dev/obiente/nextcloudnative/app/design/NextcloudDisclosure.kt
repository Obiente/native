package dev.obiente.nextcloudnative.app.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Secondary content revealed on demand, such as details, insights, or older items. It unfolds
 * from the top and folds back instead of snapping, so the content below moves smoothly.
 * Reduced motion shows and hides it at once. The caller owns [expanded].
 */
@Composable
fun NextcloudDisclosureContent(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reducedMotion = LocalNextcloudReducedMotion.current
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = if (reducedMotion) EnterTransition.None else
            fadeIn(NextcloudMotion.content(false)) +
                expandVertically(NextcloudMotion.content(false), expandFrom = Alignment.Top),
        exit = if (reducedMotion) ExitTransition.None else
            fadeOut(NextcloudMotion.content(false, NextcloudMotion.ShortMillis)) +
                shrinkVertically(NextcloudMotion.content(false), shrinkTowards = Alignment.Top),
    ) { Column(content = content) }
}

/** Turns a downward chevron to point up while [expanded], following the disclosure. */
@Composable
fun Modifier.nextcloudDisclosureChevron(expanded: Boolean): Modifier {
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        NextcloudMotion.content(LocalNextcloudReducedMotion.current),
        label = "disclosure chevron",
    )
    return graphicsLayer { rotationZ = rotation }
}
