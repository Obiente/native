package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.PhotoTimelineDateScrubber
import dev.obiente.nextcloudnative.app.PhotoTimelineEntry
import dev.obiente.nextcloudnative.app.PhotoTimelineScrubberTouchLaneWidth
import dev.obiente.nextcloudnative.app.buildPhotoTimelineDateIndex
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.parseDavMediaSearchTimestamp
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat

fun main() {
    val scene = ImageComposeScene(
        width = 1_080,
        height = 1_800,
        density = Density(2.625f),
        coroutineContext = Dispatchers.Unconfined,
    ) {
        NextcloudNativeTheme(darkTheme = true) {
            PhotoTimelineSyntheticPreview()
        }
    }
    try {
        val output = Path.of(
            System.getProperty("user.dir"),
            "ui/build/reports/photo-timeline-preview.png",
        )
        Files.createDirectories(output.parent)
        val encoded = requireNotNull(scene.render().encodeToData(EncodedImageFormat.PNG)) {
            "Compose could not encode the Photos timeline preview."
        }
        Files.write(output, encoded.bytes)
        println("Rendered isolated Photos timeline preview to $output")
    } finally {
        scene.close()
    }
}

@Composable
private fun PhotoTimelineSyntheticPreview() {
    val entries = syntheticPhotoTimelineEntries()
    val dateIndex = buildPhotoTimelineDateIndex(entries)
    val gridState = rememberLazyGridState()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(
                    start = NextcloudSpacing.Small,
                    top = NextcloudSpacing.Large,
                    end = PhotoTimelineScrubberTouchLaneWidth,
                    bottom = NextcloudSpacing.XLarge,
                ),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                dateIndex.sections.forEach { section ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            section.month.label,
                            modifier = Modifier.padding(
                                horizontal = NextcloudSpacing.Medium,
                                vertical = NextcloudSpacing.Large,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(
                        entries.subList(
                            section.firstItemIndex,
                            section.firstItemIndex + section.itemCount,
                        ),
                        key = PhotoTimelineEntry::identity,
                    ) { entry ->
                        val colorIndex = (entry.file.fileId ?: 0L).rem(PREVIEW_COLORS.size).toInt()
                        Surface(
                            modifier = Modifier.aspectRatio(1f),
                            color = PREVIEW_COLORS[colorIndex],
                        ) {
                            Box(contentAlignment = Alignment.BottomStart) {
                                Text(
                                    entry.file.name,
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
                onJumpToGridItem = { _ -> },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
            )
        }
    }
}

private fun syntheticPhotoTimelineEntries(): List<PhotoTimelineEntry> {
    val dates = listOf(
        "Mon, 27 Jul 2026 09:00:00 GMT",
        "Sun, 26 Jul 2026 17:00:00 GMT",
        "Thu, 18 Jun 2026 12:00:00 GMT",
        "Fri, 15 May 2026 08:00:00 GMT",
        "Sat, 14 Feb 2026 15:00:00 GMT",
        "Wed, 24 Dec 2025 20:00:00 GMT",
    )
    return dates.flatMapIndexed { monthIndex, date ->
        List(4) { itemIndex ->
            val id = monthIndex * 10L + itemIndex + 1L
            PhotoTimelineEntry(
                file = NextcloudFile(
                    path = "Photos/Synthetic/photo-$id.jpg",
                    name = "Photo $id",
                    isDirectory = false,
                    mimeType = "image/jpeg",
                    size = 1L,
                    lastModified = date,
                    fileId = id,
                    hasPreview = true,
                ),
                capturedAtEpochSeconds = requireNotNull(parseDavMediaSearchTimestamp(date)) - itemIndex,
            )
        }
    }
}

private val PREVIEW_COLORS = listOf(
    Color(0xFF3D5A80),
    Color(0xFF7A5195),
    Color(0xFF2A9D8F),
    Color(0xFFB56576),
    Color(0xFF577590),
)
