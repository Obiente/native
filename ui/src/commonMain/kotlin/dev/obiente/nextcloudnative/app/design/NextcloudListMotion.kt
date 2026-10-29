package dev.obiente.nextcloudnative.app.design

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * Item motion for lazy lists keyed by stable domain ids: new items fade in, items that move
 * glide to their new place, and, when [fadeOut] is set, removed items fade away.
 *
 * Use `fadeOut = false` where the whole content can be replaced at once, such as opening
 * another folder or page, so outgoing rows never overlap incoming ones, and where a leaving
 * item must stop reporting geometry at once, such as drag-and-drop targets. Set
 * `placement = false` while geometry drives an interaction, such as an active drag. Reduced
 * motion disables all of it.
 */
@Composable
fun LazyItemScope.nextcloudItemMotion(fadeOut: Boolean = true, placement: Boolean = true): Modifier {
    if (LocalNextcloudReducedMotion.current) return Modifier
    return Modifier.animateItem(
        fadeInSpec = itemFadeIn(),
        placementSpec = if (placement) itemPlacement() else null,
        fadeOutSpec = if (fadeOut) itemFadeOut() else null,
    )
}

/** Grid counterpart of [nextcloudItemMotion]. */
@Composable
fun LazyGridItemScope.nextcloudItemMotion(fadeOut: Boolean = true, placement: Boolean = true): Modifier {
    if (LocalNextcloudReducedMotion.current) return Modifier
    return Modifier.animateItem(
        fadeInSpec = itemFadeIn(),
        placementSpec = if (placement) itemPlacement() else null,
        fadeOutSpec = if (fadeOut) itemFadeOut() else null,
    )
}

private fun itemFadeIn(): FiniteAnimationSpec<Float> =
    tween(durationMillis = NextcloudMotion.MediumMillis, easing = NextcloudMotion.ContentEasing)

private fun itemFadeOut(): FiniteAnimationSpec<Float> = tween(durationMillis = NextcloudMotion.ShortMillis)

/** Critically damped, so reordered rows settle without bouncing past their slot. */
private fun itemPlacement(): FiniteAnimationSpec<IntOffset> =
    spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )
