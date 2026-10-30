package dev.obiente.nextcloudnative.app.design

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Shared motion vocabulary. Selection moves with a short, slightly bouncy spring; content
 * arrives with a calm deceleration. Every spec has a reduced-motion form that settles at once.
 */
object NextcloudMotion {
    /** Hover, press, and small color changes. */
    const val ShortMillis = 150

    /** Selection label and icon color changes, timed to the moving highlight. */
    const val SelectionColorMillis = 240

    /** Pane width changes, such as the sidebar expanding or collapsing. */
    const val MediumMillis = 300

    /** Larger surfaces such as sheets and side panes. */
    const val LongMillis = 360

    /** Navigation chrome gliding to a new app accent, like the website's hue shift. */
    const val AccentMillis = 600

    /**
     * A root destination arriving. The fade completes in the first [EntranceFadeFraction] of
     * this time; the remaining rise settles calmly so frequent switching never feels sluggish.
     */
    const val EntranceMillis = 460
    const val EntranceFadeFraction = 0.55f

    /** Matches the website's `cubic-bezier(.2, .8, .2, 1)`: fast start, long calm finish. */
    val ContentEasing: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    /**
     * The website's selection curve, `cubic-bezier(.3, 1.35, .5, 1)`, expressed as a spring.
     * The damping ratio is below 1 so a moving highlight overshoots a little, and high enough
     * that it settles after one small bounce.
     */
    const val SelectionDampingRatio = 0.66f
    const val SelectionStiffness = 320f

    /** Vertical distance a root destination rises while it fades in. */
    val DestinationEntranceOffset = 14.dp

    /** Distance a deeper page or a returning parent travels in from the side. */
    val RouteEntranceOffset = 36.dp

    fun <T> selection(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = SelectionDampingRatio, stiffness = SelectionStiffness)

    fun <T> content(reduced: Boolean, durationMillis: Int = MediumMillis): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMillis = durationMillis, easing = ContentEasing)

    fun <T> color(reduced: Boolean): FiniteAnimationSpec<T> = content(reduced, SelectionColorMillis)

    fun <T> accent(reduced: Boolean): FiniteAnimationSpec<T> = content(reduced, AccentMillis)

    /** Linear time for the entrance; [nextcloudDestinationEntranceFrame] applies the curves. */
    fun <T> entrance(reduced: Boolean): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMillis = EntranceMillis, easing = LinearEasing)

    /** A trailing side pane, such as an inspector, widening into a row. */
    fun sidePaneEnter(reduced: Boolean): EnterTransition =
        if (reduced) EnterTransition.None else fadeIn(content(false)) + expandHorizontally(content(false))

    fun sidePaneExit(reduced: Boolean): ExitTransition =
        if (reduced) ExitTransition.None else fadeOut(content(false, ShortMillis)) + shrinkHorizontally(content(false))
}

/**
 * Whether the person asked the platform to remove nonessential motion. Platforms provide it at
 * the root; the default keeps full motion where no platform preference is known.
 */
val LocalNextcloudReducedMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * Android reports reduced motion as an animator duration scale of zero ("Remove animations").
 * Unreadable or invalid values keep full motion rather than guessing.
 */
fun nextcloudReducedMotionForAnimatorScale(scale: Float): Boolean = scale == 0f
