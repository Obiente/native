package dev.obiente.nextcloudnative.app.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A filter chip whose selection eases between states: the fill, label, icon, and outline change
 * color together, and the chip widens smoothly when a selected check mark appears. Reduced
 * motion switches at once. Parameters match Material's FilterChip so it is a drop-in.
 */
@Composable
fun NextcloudFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val reducedMotion = LocalNextcloudReducedMotion.current
    val scheme = MaterialTheme.colorScheme
    val spec = NextcloudMotion.color<Color>(reducedMotion)
    val container by animateColorAsState(
        if (selected) scheme.secondaryContainer else Color.Transparent, spec, label = "chip container",
    )
    val labelColor by animateColorAsState(
        if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant, spec, label = "chip label",
    )
    val iconColor by animateColorAsState(
        if (selected) scheme.onSecondaryContainer else scheme.primary, spec, label = "chip icon",
    )
    val outline by animateColorAsState(
        if (selected) Color.Transparent else scheme.outline, spec, label = "chip outline",
    )
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = if (reducedMotion) modifier else modifier.animateContentSize(NextcloudMotion.content(false)),
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        // Selected and unselected roles share one animated value, so the chip never snaps.
        colors = FilterChipDefaults.filterChipColors(
            containerColor = container,
            labelColor = labelColor,
            iconColor = iconColor,
            selectedContainerColor = container,
            selectedLabelColor = labelColor,
            selectedLeadingIconColor = iconColor,
            selectedTrailingIconColor = iconColor,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = outline,
            selectedBorderColor = outline,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        ),
    )
}

/**
 * An icon that changes with state, such as favorite, play and pause, or open and close. The new
 * icon pops in while the old one fades away, so the change reads as a response to the press.
 * Reduced motion swaps it at once. Parameters match Material's Icon.
 */
@Composable
fun NextcloudAnimatedIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val reducedMotion = LocalNextcloudReducedMotion.current
    AnimatedContent(
        targetState = imageVector,
        modifier = modifier,
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(NextcloudMotion.content(true)) togetherWith fadeOut(NextcloudMotion.content(true))
            } else {
                (fadeIn(NextcloudMotion.content(false, NextcloudMotion.ShortMillis)) +
                    scaleIn(NextcloudMotion.selection(false), initialScale = 0.6f)) togetherWith
                    (fadeOut(NextcloudMotion.content(false, NextcloudMotion.ShortMillis)) +
                        scaleOut(NextcloudMotion.content(false, NextcloudMotion.ShortMillis), targetScale = 0.6f))
            }
        },
        label = "animated icon",
    ) { icon ->
        // Only the arriving icon is announced; the leaving one is decoration while it fades.
        Icon(icon, contentDescription = contentDescription.takeIf { icon == imageVector }, tint = tint)
    }
}

/**
 * Fades a loaded image in instead of letting it pop over its placeholder. Images arrive after
 * their screen because they load asynchronously, so the fade reads as "loaded" wherever it is
 * used. Reduced motion shows the image at once.
 */
@Composable
fun Modifier.nextcloudFadeInOnLoad(): Modifier {
    val reducedMotion = LocalNextcloudReducedMotion.current
    val alpha = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(alpha) {
        if (!reducedMotion) alpha.animateTo(1f, NextcloudMotion.content(false, ImageRevealMillis))
    }
    return graphicsLayer { this.alpha = alpha.value }
}

private const val ImageRevealMillis = 220

/**
 * A selection or state color that fades to [target] instead of snapping, for rows, cards, and
 * segments whose background or text marks what is selected. Reduced motion switches at once.
 */
@Composable
fun nextcloudAnimatedColor(target: Color): Color {
    val color by animateColorAsState(target, NextcloudMotion.color(LocalNextcloudReducedMotion.current), label = "selection color")
    return color
}
