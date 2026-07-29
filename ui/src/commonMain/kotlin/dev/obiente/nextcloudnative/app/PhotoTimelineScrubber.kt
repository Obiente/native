package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal val PhotoTimelineScrubberTouchLaneWidth = 48.dp

private val PhotoTimelineScrubberThumbHeight = 32.dp
private val PhotoTimelineScrubberThumbWidth = 8.dp
private val PhotoTimelineScrubberActiveThumbWidth = 12.dp
private val PhotoTimelineScrubberTrackWidth = 3.dp
private val PhotoTimelineScrubberLabelGap = 8.dp
private val PhotoTimelineScrubberEstimatedLabelHeight = 36.dp

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

internal fun photoTimelineScrubberDisplaySectionIndex(
    activeSectionIndex: Int,
    interactionSectionIndex: Int?,
    sectionCount: Int,
): Int? {
    if (sectionCount <= 0) return null
    return (interactionSectionIndex ?: activeSectionIndex).coerceIn(0, sectionCount - 1)
}

internal fun distinctPhotoTimelineScrubberJumpTarget(
    currentSectionIndex: Int?,
    requestedSectionIndex: Int,
    sectionCount: Int,
): Int? {
    if (sectionCount <= 0) return null
    val target = requestedSectionIndex.coerceIn(0, sectionCount - 1)
    return target.takeUnless { it == currentSectionIndex }
}

internal fun photoTimelineGridItemCount(
    index: PhotoTimelineDateIndex,
): Int = index.totalItemCount + index.sections.size

internal fun photoTimelineSectionIndexForGridItem(
    index: PhotoTimelineDateIndex,
    gridItemIndex: Int,
): Int? {
    if (index.sections.isEmpty()) return null
    val boundedGridItem = gridItemIndex.coerceIn(0, photoTimelineGridItemCount(index) - 1)
    var sectionIndex = 0
    index.sections.forEachIndexed { candidateIndex, section ->
        if (photoTimelineGridIndex(section, candidateIndex) <= boundedGridItem) {
            sectionIndex = candidateIndex
        }
    }
    return sectionIndex
}

internal fun photoTimelineGridItemForRailPosition(
    positionY: Float,
    railHeight: Float,
    thumbHeight: Float,
    index: PhotoTimelineDateIndex,
): Int? {
    val gridItemCount = photoTimelineGridItemCount(index)
    if (
        gridItemCount <= 0 ||
        !positionY.isFinite() ||
        !railHeight.isFinite() ||
        !thumbHeight.isFinite() ||
        railHeight <= 0f ||
        thumbHeight < 0f ||
        thumbHeight >= railHeight
    ) {
        return null
    }
    if (gridItemCount == 1) return 0
    val thumbTravel = railHeight - thumbHeight
    val fraction = ((positionY - thumbHeight / 2f) / thumbTravel).coerceIn(0f, 1f)
    return (fraction * (gridItemCount - 1)).roundToInt()
}

internal fun photoTimelineRailFraction(
    positionY: Float,
    railHeight: Float,
    thumbHeight: Float,
): Float? {
    if (
        !positionY.isFinite() ||
        !railHeight.isFinite() ||
        !thumbHeight.isFinite() ||
        railHeight <= 0f ||
        thumbHeight < 0f ||
        thumbHeight >= railHeight
    ) {
        return null
    }
    val thumbTravel = railHeight - thumbHeight
    return ((positionY - thumbHeight / 2f) / thumbTravel).coerceIn(0f, 1f)
}

internal fun lightlySnappedPhotoTimelineGridItem(
    index: PhotoTimelineDateIndex,
    gridItemIndex: Int,
    maximumDistance: Int = 1,
): Int? {
    val gridItemCount = photoTimelineGridItemCount(index)
    if (gridItemCount <= 0 || maximumDistance < 0) return null
    val target = gridItemIndex.coerceIn(0, gridItemCount - 1)
    val nearestHeader = index.sections.indices
        .map { sectionIndex ->
            photoTimelineGridIndex(index.sections[sectionIndex], sectionIndex)
        }
        .minByOrNull { header -> abs(header - target) }
    return nearestHeader?.takeIf { header -> abs(header - target) <= maximumDistance } ?: target
}

internal fun lightlySnappedMemoriesTimelineDayId(
    geometry: MemoriesTimelinePlaceholderGeometry,
    fraction: Float,
    maximumAdvertisedItemDistance: Long = 1L,
): Long? {
    if (
        !fraction.isFinite() ||
        maximumAdvertisedItemDistance < 0L ||
        geometry.totalAdvertisedItemCount <= 0L
    ) {
        return null
    }
    val clamped = fraction.coerceIn(0f, 1f)
    val targetOffset = if (clamped == 1f) {
        geometry.totalAdvertisedItemCount - 1L
    } else {
        (clamped.toDouble() * geometry.totalAdvertisedItemCount.toDouble())
            .toLong()
            .coerceIn(0L, geometry.totalAdvertisedItemCount - 1L)
    }
    val nearestMonth = geometry.months.minByOrNull { month ->
        kotlin.math.abs(month.firstAdvertisedItemOffset - targetOffset)
    }
    val snappedMonth = nearestMonth?.takeIf { month ->
        kotlin.math.abs(month.firstAdvertisedItemOffset - targetOffset) <=
            maximumAdvertisedItemDistance
    }
    return snappedMonth
        ?.let { month -> geometry.days.getOrNull(month.firstDayIndex)?.dayId }
        ?: geometry.dayAtFraction(clamped)?.dayId
}

internal fun memoriesTimelineDayIdForScrubberRelease(
    geometry: MemoriesTimelinePlaceholderGeometry,
    interactionFraction: Float?,
    interactionDayId: Long?,
): Long? = interactionFraction
    ?.let { fraction ->
        lightlySnappedMemoriesTimelineDayId(
            geometry = geometry,
            fraction = fraction,
        )
    }
    ?: interactionDayId

@Composable
internal fun PhotoTimelineDateScrubber(
    dateIndex: PhotoTimelineDateIndex,
    activeGridItemIndex: Int,
    onJumpToGridItem: suspend (Int) -> Unit,
    fullGeometry: MemoriesTimelinePlaceholderGeometry? = null,
    onJumpToAdvertisedDay: (suspend (Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val completeGeometry = fullGeometry?.takeIf {
        onJumpToAdvertisedDay != null && it.months.isNotEmpty()
    }
    if (dateIndex.sections.isEmpty() && completeGeometry == null) return
    val gridItemCount = photoTimelineGridItemCount(dateIndex)
    val boundedActiveGridItemIndex = if (gridItemCount > 0) {
        activeGridItemIndex.coerceIn(0, gridItemCount - 1)
    } else {
        0
    }
    var interactionGridItemIndex by remember(dateIndex.sections) {
        mutableStateOf<Int?>(null)
    }
    var interactionFraction by remember(completeGeometry) {
        mutableStateOf<Float?>(null)
    }
    var interactionDayId by remember(completeGeometry) {
        mutableStateOf<Long?>(null)
    }
    var pointerInteracting by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val displayedGridItemIndex = interactionGridItemIndex ?: boundedActiveGridItemIndex
    val retainedSectionIndex = photoTimelineSectionIndexForGridItem(
        dateIndex,
        displayedGridItemIndex,
    ) ?: 0
    val retainedSection = dateIndex.sections.getOrNull(retainedSectionIndex)
    val activeCompleteFraction = retainedSection
        ?.month
        ?.let { month -> completeGeometry?.fractionFor(month) }
        ?: 0f
    val displayedFraction = interactionFraction ?: if (completeGeometry != null) {
        activeCompleteFraction
    } else if (gridItemCount <= 1) {
        0f
    } else {
        displayedGridItemIndex.toFloat() / (gridItemCount - 1)
    }
    val displayedSectionIndex = completeGeometry
        ?.monthIndexAtFraction(displayedFraction)
        ?: retainedSectionIndex
    val displayedMonth = completeGeometry
        ?.months
        ?.getOrNull(displayedSectionIndex)
        ?.month
        ?: retainedSection?.month
        ?: return
    val sectionCount = completeGeometry?.months?.size ?: dateIndex.sections.size
    val scope = rememberCoroutineScope()
    val currentOnJumpToGridItem by rememberUpdatedState(onJumpToGridItem)
    val currentOnJumpToAdvertisedDay by rememberUpdatedState(onJumpToAdvertisedDay)
    var jumpJob by remember(dateIndex.sections, completeGeometry) { mutableStateOf<Job?>(null) }
    val density = LocalDensity.current
    val scrubberThumbPixels = with(density) {
        PhotoTimelineScrubberThumbHeight.toPx().roundToInt()
    }
    val scrubberLabelOffsetPixels = with(density) {
        (PhotoTimelineScrubberThumbWidth + PhotoTimelineScrubberLabelGap)
            .toPx()
            .roundToInt()
    }
    val scrubberEstimatedLabelHeightPixels = with(density) {
        PhotoTimelineScrubberEstimatedLabelHeight.toPx().roundToInt()
    }

    DisposableEffect(dateIndex.sections, completeGeometry) {
        onDispose {
            jumpJob?.cancel()
        }
    }

    fun jumpToSection(targetIndex: Int) {
        jumpJob?.cancel()
        if (completeGeometry != null) {
            val dayId = completeGeometry.months
                .getOrNull(targetIndex)
                ?.let { month -> completeGeometry.days.getOrNull(month.firstDayIndex)?.dayId }
                ?: return
            jumpJob = scope.launch {
                currentOnJumpToAdvertisedDay?.invoke(dayId)
            }
        } else {
            val target = dateIndex.sections[targetIndex]
            jumpJob = scope.launch {
                currentOnJumpToGridItem(photoTimelineGridIndex(target, targetIndex))
            }
        }
    }

    fun jumpFromRailPointer(position: Offset, railHeight: Int) {
        if (completeGeometry != null) {
            val fraction = photoTimelineRailFraction(
                positionY = position.y,
                railHeight = railHeight.toFloat(),
                thumbHeight = scrubberThumbPixels.toFloat(),
            ) ?: return
            val targetDayId = completeGeometry.dayAtFraction(fraction)?.dayId ?: return
            interactionFraction = fraction
            interactionDayId = targetDayId
            return
        }
        photoTimelineGridItemForRailPosition(
            positionY = position.y,
            railHeight = railHeight.toFloat(),
            thumbHeight = scrubberThumbPixels.toFloat(),
            index = dateIndex,
        )?.let { targetGridItemIndex ->
            val currentTarget = interactionGridItemIndex ?: boundedActiveGridItemIndex
            interactionGridItemIndex = targetGridItemIndex
            if (targetGridItemIndex != currentTarget) {
                jumpJob?.cancel()
                jumpJob = scope.launch {
                    currentOnJumpToGridItem(targetGridItemIndex)
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .width(PhotoTimelineScrubberTouchLaneWidth)
            .fillMaxHeight()
            .semantics {
                contentDescription = "Photo timeline date scrubber"
                stateDescription = displayedMonth.label
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = displayedSectionIndex.toFloat(),
                    range = 0f..(sectionCount - 1).coerceAtLeast(0).toFloat(),
                    steps = (sectionCount - 2).coerceAtLeast(0),
                )
                setProgress { requested ->
                    val target = requested
                        .roundToInt()
                        .coerceIn(0, (sectionCount - 1).coerceAtLeast(0))
                    distinctPhotoTimelineScrubberJumpTarget(
                        currentSectionIndex = displayedSectionIndex,
                        requestedSectionIndex = target,
                        sectionCount = sectionCount,
                    )?.let(::jumpToSection)
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
                        activeSectionIndex = displayedSectionIndex,
                        sectionCount = sectionCount,
                        step = step,
                    )
                    if (target != null && target != displayedSectionIndex) {
                        jumpToSection(target)
                    }
                    true
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .pointerInput(dateIndex.sections, completeGeometry, scrubberThumbPixels) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pointerInteracting = true
                    jumpFromRailPointer(down.position, size.height)
                    down.consume()
                    var gestureCompleted = false
                    try {
                        var pointerPressed = true
                        while (pointerPressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                pointerPressed = false
                            } else {
                                pointerPressed = change.pressed
                                if (pointerPressed) {
                                    jumpFromRailPointer(change.position, size.height)
                                    change.consume()
                                }
                            }
                        }
                        gestureCompleted = true
                    } finally {
                        if (completeGeometry != null && gestureCompleted) {
                            memoriesTimelineDayIdForScrubberRelease(
                                geometry = completeGeometry,
                                interactionFraction = interactionFraction,
                                interactionDayId = interactionDayId,
                            )
                                ?.let { targetDayId ->
                                    jumpJob?.cancel()
                                    jumpJob = scope.launch {
                                        currentOnJumpToAdvertisedDay?.invoke(targetDayId)
                                    }
                                }
                        } else {
                            interactionGridItemIndex
                                ?.let { target ->
                                    lightlySnappedPhotoTimelineGridItem(dateIndex, target)
                                }
                                ?.takeIf { target -> target != interactionGridItemIndex }
                                ?.let { target ->
                                    jumpJob?.cancel()
                                    jumpJob = scope.launch {
                                        currentOnJumpToGridItem(target)
                                    }
                                }
                        }
                        pointerInteracting = false
                        interactionGridItemIndex = null
                        interactionFraction = null
                        interactionDayId = null
                    }
                }
            }
            .focusable(),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            modifier = Modifier
                .width(PhotoTimelineScrubberTrackWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        val availablePixels = constraints.maxHeight - scrubberThumbPixels
        val thumbOffsetPixels =
            (availablePixels.coerceAtLeast(0) * displayedFraction).roundToInt()
        if (pointerInteracting || focused) {
            val labelOffsetPixels = (
                thumbOffsetPixels +
                    (scrubberThumbPixels - scrubberEstimatedLabelHeightPixels) / 2
                ).coerceIn(
                minimumValue = 0,
                maximumValue = (
                    constraints.maxHeight - scrubberEstimatedLabelHeightPixels
                    ).coerceAtLeast(0),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            x = -scrubberLabelOffsetPixels,
                            y = labelOffsetPixels,
                        )
                    }
                    .clearAndSetSemantics {},
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
                shape = RoundedCornerShape(NextcloudRadii.Medium),
            ) {
                Text(
                    text = displayedMonth.label,
                    modifier = Modifier.padding(
                        horizontal = NextcloudSpacing.Medium,
                        vertical = NextcloudSpacing.Small,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(x = 0, y = thumbOffsetPixels) }
                .width(
                    if (pointerInteracting || focused) {
                        PhotoTimelineScrubberActiveThumbWidth
                    } else {
                        PhotoTimelineScrubberThumbWidth
                    },
                )
                .height(PhotoTimelineScrubberThumbHeight)
                .clip(RoundedCornerShape(NextcloudRadii.Pill))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
