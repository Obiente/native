package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun MarketingPhotoTimelineFailureScenario(
    scenario: MarketingCaptureScenario,
) {
    require(
        scenario == MarketingCaptureScenario.PhotoTimelineRevalidationErrorMobile ||
            scenario == MarketingCaptureScenario.PhotoTimelineReturnToNewestErrorMobile ||
            scenario == MarketingCaptureScenario.PhotoTimelineRawRetryMobile,
    ) {
        "${scenario.id} is not a photo timeline failure capture."
    }
    val fixture = marketingPhotoTimelineCaptureFixture(scenario)
    val entries = fixture.entries
    val dateIndex = buildPhotoTimelineDateIndex(entries)
    val gridState = rememberLazyGridState()
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Photos & Memories",
            subtitle = "Photo timeline",
            onBack = {},
        )
        val navigationIntent = planPhotoNavigation(
            state = PhotoNavigationState(PhotoDestination.Timeline),
            capabilities = PhotoNavigationCapabilities(
                albumsAvailable = true,
                peopleAvailable = true,
                favoritesAvailable = false,
            ),
            widthClass = PhotoNavigationWidthClass.Compact,
        )
        PhotoAdaptiveNavigationLayout(
            intent = navigationIntent,
            onDestinationSelected = {},
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.fillMaxSize()) {
                PhotoTimelineFailureNotice(
                    message = when (scenario) {
                        MarketingCaptureScenario.PhotoTimelineRevalidationErrorMobile ->
                            "Could not check for newer photos. Your saved timeline is still available."
                        MarketingCaptureScenario.PhotoTimelineReturnToNewestErrorMobile ->
                            "Could not return to the newest photos. This older window is still available."
                        MarketingCaptureScenario.PhotoTimelineRawRetryMobile ->
                            "Some RAW photos could not be loaded. " +
                                "They will be retried without hiding other photos."
                    },
                    onRetry = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = NextcloudSpacing.Medium,
                            vertical = NextcloudSpacing.Small,
                        ),
                    actionLabel = if (
                        scenario == MarketingCaptureScenario.PhotoTimelineRawRetryMobile
                    ) {
                        "Retry RAW"
                    } else {
                        "Retry"
                    },
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(120.dp),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = 4.dp,
                            top = 4.dp,
                            end = PhotoTimelineScrubberTouchLaneWidth,
                            bottom = NextcloudSpacing.XLarge,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        dateIndex.sections.forEach { section ->
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = section.month.label,
                                    modifier = Modifier.padding(
                                        horizontal = NextcloudSpacing.Medium,
                                        vertical = NextcloudSpacing.Large,
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            items(
                                entries.subList(
                                    section.firstItemIndex,
                                    section.firstItemIndex + section.itemCount,
                                ),
                                key = PhotoTimelineEntry::identity,
                            ) { entry ->
                                val colorIndex =
                                    ((entry.file.fileId ?: 0L) % marketingTimelineColors.size)
                                        .toInt()
                                Surface(
                                    color = marketingTimelineColors[colorIndex],
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().size(120.dp),
                                        contentAlignment = Alignment.BottomStart,
                                    ) {
                                        Text(
                                            text = entry.file.name,
                                            modifier = Modifier.padding(NextcloudSpacing.Small),
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    PhotoTimelineDateScrubber(
                        dateIndex = dateIndex,
                        activeGridItemIndex = gridState.firstVisibleItemIndex,
                        onJumpToGridItem = {},
                        fullGeometry = fixture.fullGeometry,
                        onJumpToAdvertisedDay = {},
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(),
                    )
                    if (scenario == MarketingCaptureScenario.PhotoTimelineReturnToNewestErrorMobile) {
                        OutlinedButton(
                            onClick = {},
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(
                                    top = NextcloudSpacing.Small,
                                    end = (
                                        PhotoTimelineScrubberTouchLaneWidth +
                                            NextcloudSpacing.Small
                                        ),
                                ),
                        ) {
                            Text("Back to newest")
                        }
                    }
                }
            }
        }
    }
}

internal data class MarketingPhotoTimelineCaptureFixture(
    val entries: List<PhotoTimelineEntry>,
    val fullGeometry: MemoriesTimelinePlaceholderGeometry,
)

internal fun marketingPhotoTimelineCaptureFixture(
    scenario: MarketingCaptureScenario,
): MarketingPhotoTimelineCaptureFixture {
    require(
        scenario == MarketingCaptureScenario.PhotoTimelineRevalidationErrorMobile ||
            scenario == MarketingCaptureScenario.PhotoTimelineReturnToNewestErrorMobile ||
            scenario == MarketingCaptureScenario.PhotoTimelineRawRetryMobile,
    ) {
        "${scenario.id} is not a photo timeline failure capture."
    }
    val entries = when (scenario) {
        MarketingCaptureScenario.PhotoTimelineRevalidationErrorMobile ->
            marketingNewestPhotoTimelineEntries
        MarketingCaptureScenario.PhotoTimelineReturnToNewestErrorMobile ->
            marketingOlderPhotoTimelineEntries
        MarketingCaptureScenario.PhotoTimelineRawRetryMobile ->
            marketingNewestPhotoTimelineEntries
    }
    return MarketingPhotoTimelineCaptureFixture(
        entries = entries,
        fullGeometry = marketingPhotoTimelineFullGeometry,
    )
}

private val marketingNewestPhotoTimelineEntries: List<PhotoTimelineEntry> = listOf(
    marketingTimelineEntry(1L, "Mon, 27 Jul 2026 09:00:00 GMT"),
    marketingTimelineEntry(2L, "Sun, 26 Jul 2026 17:00:00 GMT"),
    marketingTimelineEntry(3L, "Sat, 18 Jul 2026 12:00:00 GMT"),
    marketingTimelineEntry(4L, "Thu, 25 Jun 2026 08:00:00 GMT"),
    marketingTimelineEntry(5L, "Fri, 12 Jun 2026 15:00:00 GMT"),
    marketingTimelineEntry(6L, "Tue, 02 Jun 2026 20:00:00 GMT"),
    marketingTimelineEntry(7L, "Sun, 24 May 2026 10:00:00 GMT"),
    marketingTimelineEntry(8L, "Mon, 11 May 2026 18:00:00 GMT"),
    marketingTimelineEntry(9L, "Sun, 03 May 2026 07:00:00 GMT"),
)

private val marketingOlderPhotoTimelineEntries: List<PhotoTimelineEntry> = listOf(
    marketingTimelineEntry(101L, "Tue, 17 Dec 2024 09:00:00 GMT"),
    marketingTimelineEntry(102L, "Tue, 17 Dec 2024 08:45:00 GMT"),
    marketingTimelineEntry(103L, "Mon, 16 Dec 2024 16:00:00 GMT"),
    marketingTimelineEntry(104L, "Thu, 28 Nov 2024 08:00:00 GMT"),
    marketingTimelineEntry(105L, "Wed, 20 Nov 2024 15:00:00 GMT"),
    marketingTimelineEntry(106L, "Sat, 09 Nov 2024 20:00:00 GMT"),
    marketingTimelineEntry(107L, "Thu, 31 Oct 2024 10:00:00 GMT"),
    marketingTimelineEntry(108L, "Fri, 18 Oct 2024 18:00:00 GMT"),
    marketingTimelineEntry(109L, "Thu, 03 Oct 2024 07:00:00 GMT"),
)

private val marketingPhotoTimelineFullGeometry: MemoriesTimelinePlaceholderGeometry =
    requireNotNull(
        buildMemoriesTimelinePlaceholderGeometry(
            MemoriesMainTimelineDayIndex(
                listOf(
                    marketingTimelineDay("Mon, 27 Jul 2026 09:00:00 GMT", 2),
                    marketingTimelineDay("Sun, 26 Jul 2026 17:00:00 GMT", 1),
                    marketingTimelineDay("Sat, 18 Jul 2026 12:00:00 GMT", 1),
                    marketingTimelineDay("Thu, 25 Jun 2026 08:00:00 GMT", 3),
                    marketingTimelineDay("Sun, 24 May 2026 10:00:00 GMT", 3),
                    marketingTimelineDay("Wed, 24 Dec 2025 20:00:00 GMT", 12),
                    marketingTimelineDay("Wed, 05 Nov 2025 18:00:00 GMT", 18),
                    marketingTimelineDay("Tue, 17 Dec 2024 09:00:00 GMT", 2),
                    marketingTimelineDay("Mon, 16 Dec 2024 16:00:00 GMT", 1),
                    marketingTimelineDay("Thu, 28 Nov 2024 08:00:00 GMT", 1),
                    marketingTimelineDay("Wed, 20 Nov 2024 15:00:00 GMT", 1),
                    marketingTimelineDay("Sat, 09 Nov 2024 20:00:00 GMT", 1),
                    marketingTimelineDay("Thu, 31 Oct 2024 10:00:00 GMT", 1),
                    marketingTimelineDay("Fri, 18 Oct 2024 18:00:00 GMT", 1),
                    marketingTimelineDay("Thu, 03 Oct 2024 07:00:00 GMT", 1),
                ),
            ),
        ),
    )

private fun marketingTimelineEntry(
    id: Long,
    lastModified: String,
): PhotoTimelineEntry = PhotoTimelineEntry(
    file = NextcloudFile(
        path = "Photos/Synthetic/photo-$id.jpg",
        name = "Photo $id",
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 1_000_000L + id,
        lastModified = lastModified,
        fileId = id,
        hasPreview = true,
    ),
    capturedAtEpochSeconds = requireNotNull(parseDavMediaSearchTimestamp(lastModified)),
)

private fun marketingTimelineDay(
    lastModified: String,
    itemCount: Int,
): NativeMediaDay = NativeMediaDay(
    id = requireNotNull(parseDavMediaSearchTimestamp(lastModified)) / 86_400L,
    itemCount = itemCount,
)

private val marketingTimelineColors = listOf(
    Color(0xFF3D5A80),
    Color(0xFF7A5195),
    Color(0xFF2A9D8F),
    Color(0xFFB56576),
    Color(0xFF577590),
)
