package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun MarketingPhotoTimelineFailureScenario(
    scenario: MarketingCaptureScenario,
) {
    require(
        scenario == MarketingCaptureScenario.PhotoTimelineRevalidationErrorMobile ||
            scenario == MarketingCaptureScenario.PhotoTimelineReturnToNewestErrorMobile,
    ) {
        "${scenario.id} is not a photo timeline failure capture."
    }
    val entries = marketingPhotoTimelineEntries
    val dateIndex = buildPhotoTimelineDateIndex(entries)
    val gridState = rememberLazyGridState()
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Photos & Memories",
            subtitle = "Photo timeline",
            onBack = {},
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = NextcloudSpacing.XLarge,
                vertical = NextcloudSpacing.Small,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            item {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text("Timeline") },
                    leadingIcon = {
                        Icon(
                            NextcloudIcons.Photo,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
            item {
                FilterChip(
                    selected = false,
                    onClick = {},
                    label = { Text("Albums & tags") },
                )
            }
            item {
                FilterChip(
                    selected = false,
                    onClick = {},
                    label = { Text("People") },
                )
            }
        }
        PhotoTimelineFailureNotice(
            message = when (scenario) {
                MarketingCaptureScenario.PhotoTimelineRevalidationErrorMobile ->
                    "Could not check for newer photos. Your saved timeline is still available."
                MarketingCaptureScenario.PhotoTimelineReturnToNewestErrorMobile ->
                    "Could not return to the newest photos. This older window is still available."
            },
            onRetry = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = NextcloudSpacing.Medium,
                    vertical = NextcloudSpacing.Small,
                ),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 4.dp,
                    top = 4.dp,
                    end = 72.dp,
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
                        val colorIndex = ((entry.file.fileId ?: 0L) % marketingTimelineColors.size)
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
                activeSectionIndex = activePhotoTimelineSectionIndex(
                    dateIndex,
                    gridState.firstVisibleItemIndex,
                ),
                onJumpToGridItem = {},
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = NextcloudSpacing.Small),
            )
            if (scenario == MarketingCaptureScenario.PhotoTimelineReturnToNewestErrorMobile) {
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            top = NextcloudSpacing.Small,
                            end = NextcloudSpacing.Small,
                        ),
                ) {
                    Text("Back to newest")
                }
            }
        }
    }
}

private val marketingPhotoTimelineEntries: List<PhotoTimelineEntry> = listOf(
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

private val marketingTimelineColors = listOf(
    Color(0xFF3D5A80),
    Color(0xFF7A5195),
    Color(0xFF2A9D8F),
    Color(0xFFB56576),
    Color(0xFF577590),
)
