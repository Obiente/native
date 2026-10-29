package dev.obiente.nextcloudnative.app.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Whether a status explains a problem, which tints its icon with the error container. */
enum class NextcloudStatusTone { Neutral, Error }

/**
 * Centered explanation for an empty, failed, or otherwise content-less workspace: an icon tile,
 * a title, optional guidance, and an optional action. It eases in when it first appears, so
 * switching from content or loading never pops.
 */
@Composable
fun NextcloudStatusState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    tone: NextcloudStatusTone = NextcloudStatusTone.Neutral,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    NextcloudStateLayout(modifier) {
        NextcloudStatusIcon(icon, tone)
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        body?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        actions?.invoke(this)
    }
}

/** Shared centered column with the entrance used by every status state. */
@Composable
fun NextcloudStateLayout(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().padding(NextcloudSpacing.XLarge), contentAlignment = Alignment.Center) {
        NextcloudAppear(Modifier.widthIn(max = 480.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                content = content,
            )
        }
    }
}

@Composable
fun NextcloudStatusIcon(icon: ImageVector, tone: NextcloudStatusTone = NextcloudStatusTone.Neutral) {
    val error = tone == NextcloudStatusTone.Error
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else NextcloudTheme.colors.appIconContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (error) MaterialTheme.colorScheme.onErrorContainer else NextcloudTheme.colors.appIcon,
            modifier = Modifier.padding(NextcloudSpacing.Large).size(32.dp),
        )
    }
}

/** Placeholder shapes that resemble the content being loaded. */
enum class NextcloudSkeletonLayout { List, Grid }

/**
 * Loading state. With a [skeleton] layout it shows placeholder rows or tiles shaped like the
 * content that is coming; otherwise a centered progress indicator. Either way it waits briefly
 * before fading in, so a fast or cached load never flashes a spinner. [message] stays available
 * to screen readers and is shown as a caption.
 */
@Composable
fun NextcloudLoadingState(
    message: String,
    modifier: Modifier = Modifier,
    skeleton: NextcloudSkeletonLayout? = null,
) {
    if (skeleton == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            NextcloudAppear(delayMillis = LoadingRevealDelayMillis) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                ) {
                    CircularProgressIndicator()
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
        return
    }
    NextcloudAppear(modifier.fillMaxSize(), delayMillis = LoadingRevealDelayMillis) {
        Column(
            Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Text(message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NextcloudSkeleton(skeleton, label = message)
        }
    }
}

/**
 * Placeholder blocks with a slow, quiet pulse; reduced motion keeps them still. Screen readers
 * hear one busy indicator named by [label] instead of the individual shapes.
 */
@Composable
fun NextcloudSkeleton(layout: NextcloudSkeletonLayout, label: String, modifier: Modifier = Modifier) {
    val reducedMotion = LocalNextcloudReducedMotion.current
    val pulse = if (reducedMotion) {
        SkeletonRestAlpha
    } else {
        val transition = rememberInfiniteTransition(label = "skeleton pulse")
        val animated by transition.animateFloat(
            initialValue = SkeletonDimAlpha,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(SkeletonPulseMillis), RepeatMode.Reverse),
            label = "skeleton alpha",
        )
        animated
    }
    val block = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Column(
        modifier.fillMaxWidth().alpha(pulse).clearAndSetSemantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        when (layout) {
            NextcloudSkeletonLayout.List -> repeat(SkeletonRowCount) { row ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Box(Modifier.size(40.dp).background(block, CircleShape))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        SkeletonBar(block, fraction = if (row % 2 == 0) 0.62f else 0.48f, height = 14.dp)
                        SkeletonBar(block, fraction = if (row % 2 == 0) 0.38f else 0.52f, height = 10.dp)
                    }
                }
            }
            NextcloudSkeletonLayout.Grid -> repeat(SkeletonGridRows) {
                Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    repeat(SkeletonGridColumns) {
                        Box(Modifier.weight(1f).aspectRatio(1.35f).background(block, RoundedCornerShape(NextcloudRadii.Card)))
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(color: androidx.compose.ui.graphics.Color, fraction: Float, height: Dp) {
    Box(Modifier.fillMaxWidth(fraction).height(height).background(color, RoundedCornerShape(height / 2)))
}

/**
 * Thin progress bar for refreshing content that is already on screen. It eases open and closed
 * instead of popping, so the content below moves smoothly.
 */
@Composable
fun NextcloudRefreshBar(refreshing: Boolean, modifier: Modifier = Modifier) {
    val reducedMotion = LocalNextcloudReducedMotion.current
    AnimatedVisibility(
        visible = refreshing,
        modifier = modifier,
        enter = if (reducedMotion) EnterTransition.None else fadeIn(NextcloudMotion.content(false)) +
            expandVertically(NextcloudMotion.content(false)),
        exit = if (reducedMotion) androidx.compose.animation.ExitTransition.None else
            fadeOut(NextcloudMotion.content(false)) + shrinkVertically(NextcloudMotion.content(false)),
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Eases [content] in the first time it is composed: a short fade and a small rise, optionally
 * after [delayMillis]. Reduced motion shows it at once. The content is composed immediately, so
 * its semantics are available even while it is still transparent.
 */
@Composable
fun NextcloudAppear(
    modifier: Modifier = Modifier,
    delayMillis: Int = 0,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalNextcloudReducedMotion.current
    val visible = remember { MutableTransitionState(reducedMotion).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible,
        modifier = modifier,
        enter = if (reducedMotion) EnterTransition.None else
            fadeIn(tween(NextcloudMotion.MediumMillis, delayMillis, NextcloudMotion.ContentEasing)) +
                slideInVertically(tween(NextcloudMotion.MediumMillis, delayMillis, NextcloudMotion.ContentEasing)) { it / 24 },
    ) { content() }
}

private const val LoadingRevealDelayMillis = 160
private const val SkeletonPulseMillis = 900
private const val SkeletonDimAlpha = 0.45f
private const val SkeletonRestAlpha = 0.7f
private const val SkeletonRowCount = 6
private const val SkeletonGridRows = 3
private const val SkeletonGridColumns = 3
