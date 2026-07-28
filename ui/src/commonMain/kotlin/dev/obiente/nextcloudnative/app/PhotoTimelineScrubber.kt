package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun photoTimelineGridIndex(
    section: PhotoTimelineMonthSection,
    sectionIndex: Int,
): Int = section.firstItemIndex + sectionIndex

internal fun activePhotoTimelineSectionIndex(
    index: PhotoTimelineDateIndex,
    firstVisibleGridItemIndex: Int,
): Int {
    if (index.sections.isEmpty()) return 0
    var activeIndex = 0
    index.sections.forEachIndexed { sectionIndex, section ->
        if (photoTimelineGridIndex(section, sectionIndex) <= firstVisibleGridItemIndex) {
            activeIndex = sectionIndex
        }
    }
    return activeIndex
}

internal fun photoTimelineSectionIndexForRailPosition(
    positionY: Float,
    railHeight: Float,
    thumbHeight: Float,
    sectionCount: Int,
): Int? {
    if (
        sectionCount <= 0 ||
        !positionY.isFinite() ||
        !railHeight.isFinite() ||
        !thumbHeight.isFinite() ||
        railHeight <= 0f ||
        thumbHeight < 0f ||
        thumbHeight >= railHeight
    ) {
        return null
    }
    if (sectionCount == 1) return 0
    val thumbTravel = railHeight - thumbHeight
    val fraction = ((positionY - thumbHeight / 2f) / thumbTravel).coerceIn(0f, 1f)
    return (fraction * (sectionCount - 1)).roundToInt()
}

internal fun photoTimelineSectionIndexAfterStep(
    activeSectionIndex: Int,
    sectionCount: Int,
    step: Int,
): Int? {
    if (sectionCount <= 0) return null
    val boundedActiveIndex = activeSectionIndex.coerceIn(0, sectionCount - 1)
    return when {
        step < 0 -> (boundedActiveIndex - 1).coerceAtLeast(0)
        step > 0 -> (boundedActiveIndex + 1).coerceAtMost(sectionCount - 1)
        else -> boundedActiveIndex
    }
}

@Composable
internal fun PhotoTimelineDateScrubber(
    dateIndex: PhotoTimelineDateIndex,
    activeSectionIndex: Int,
    onJumpToGridItem: suspend (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (dateIndex.sections.isEmpty()) return
    val boundedActiveIndex = activeSectionIndex.coerceIn(dateIndex.sections.indices)
    val activeSection = dateIndex.sections[boundedActiveIndex]
    val scope = rememberCoroutineScope()
    val scrubberThumbPixels = with(LocalDensity.current) {
        16.dp.toPx().roundToInt()
    }

    fun jumpToSection(targetIndex: Int) {
        val target = dateIndex.sections[targetIndex]
        scope.launch {
            onJumpToGridItem(photoTimelineGridIndex(target, targetIndex))
        }
    }

    fun jumpFromRailPointer(position: Offset, railHeight: Int) {
        photoTimelineSectionIndexForRailPosition(
            positionY = position.y,
            railHeight = railHeight.toFloat(),
            thumbHeight = scrubberThumbPixels.toFloat(),
            sectionCount = dateIndex.sections.size,
        )?.let(::jumpToSection)
    }

    Surface(
        modifier = modifier
            .width(64.dp)
            .heightIn(min = 184.dp, max = 360.dp)
            .semantics {
                stateDescription = activeSection.month.label
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = boundedActiveIndex.toFloat(),
                    range = 0f..dateIndex.sections.lastIndex.toFloat(),
                    steps = (dateIndex.sections.size - 2).coerceAtLeast(0),
                )
                setProgress { requested ->
                    val target = requested
                        .roundToInt()
                        .coerceIn(dateIndex.sections.indices)
                    jumpToSection(target)
                    true
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    val step = when (event.key) {
                        Key.DirectionUp,
                        Key.DirectionLeft,
                        -> -1
                        Key.DirectionDown,
                        Key.DirectionRight,
                        -> 1
                        else -> return@onPreviewKeyEvent false
                    }
                    val target = photoTimelineSectionIndexAfterStep(
                        activeSectionIndex = boundedActiveIndex,
                        sectionCount = dateIndex.sections.size,
                        step = step,
                    )
                    if (target != null && target != boundedActiveIndex) {
                        jumpToSection(target)
                    }
                    true
                }
            }
            .focusable(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(NextcloudRadii.Pill),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = NextcloudSpacing.Small,
                vertical = NextcloudSpacing.Medium,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = activeSection.month.label.substringBefore(' ').take(3),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = activeSection.month.year.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(NextcloudSpacing.Small))
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .pointerInput(dateIndex.sections) {
                        detectTapGestures { position ->
                            jumpFromRailPointer(position, size.height)
                        }
                    }
                    .pointerInput(dateIndex.sections) {
                        detectDragGestures(
                            onDragStart = { position ->
                                jumpFromRailPointer(position, size.height)
                            },
                        ) { change, _ ->
                            jumpFromRailPointer(change.position, size.height)
                            change.consume()
                        }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                val availablePixels = constraints.maxHeight - scrubberThumbPixels
                val fraction = if (dateIndex.sections.size == 1) {
                    0f
                } else {
                    boundedActiveIndex.toFloat() / dateIndex.sections.lastIndex
                }
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = 0,
                                y = (availablePixels.coerceAtLeast(0) * fraction).roundToInt(),
                            )
                        }
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}
