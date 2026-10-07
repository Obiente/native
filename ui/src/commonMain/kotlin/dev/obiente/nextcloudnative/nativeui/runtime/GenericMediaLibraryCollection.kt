package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

@Composable
internal fun GenericMediaLibraryCollection(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    audioCollectionContext: NativeAudioCollectionContext?,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    imageLoader: NativeImageLoader?,
    audioPlayer: NativeAudioRecordPlayer?,
    mediaArtworkResolver: NativeMediaArtworkResolver?,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    val mediaItems = remember(resource, records) {
        records.map { record -> record to nativeMediaPresentation(resource, record) }
    }
    val trackList = mediaItems.count { (_, item) -> item.kind == NativeMediaItemKind.Track } > mediaItems.size / 2
    if (trackList) {
        val listState = rememberLazyListState()
        NativeCollectionAutoPager(
            listState = listState,
            itemCount = mediaItems.size,
            onLoadMore = onLoadMore,
            loadingMore = loadingMore,
            loadMoreError = loadMoreError,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = NextcloudSpacing.Large,
                top = NextcloudSpacing.Small,
                end = NextcloudSpacing.Large,
                bottom = NextcloudSpacing.XXLarge,
            ),
        ) {
            if (audioCollectionContext != null) {
                item(key = "media-collection-context") {
                    NativeMediaCollectionHeader(
                        resource = resource,
                        records = records,
                        collectionContext = audioCollectionContext,
                        imageLoader = imageLoader,
                        audioPlayer = audioPlayer,
                        mediaArtworkResolver = mediaArtworkResolver,
                    )
                }
            }
            items(mediaItems, key = { (record, _) -> record.id }) { (record, presentation) ->
                val artwork = remember(resource, record, mediaArtworkResolver) {
                    mediaArtworkResolver?.resolve(resource, record)
                        ?: presentation.nativeFallbackArtworkReference(record.id)
                }
                val playable = remember(resource, record, audioCollectionContext) {
                    nativeAudioTrack(resource, record, audioCollectionContext) != null
                }
                val interaction = when {
                    playable && audioPlayer != null -> Modifier.clickable {
                        audioPlayer.play(resource, records, record, audioCollectionContext)
                    }
                    onSelectRecord != null -> Modifier.clickable { onSelectRecord(record) }
                    else -> Modifier
                }
                Row(
                    modifier = interaction.fillMaxWidth().heightIn(min = 76.dp).padding(vertical = NextcloudSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    if (presentation.trackNumber != null) {
                        Text(presentation.trackNumber, modifier = Modifier.width(30.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    } else {
                        NativeMediaArtworkThumbnail(reference = artwork, title = presentation.title,
                            imageLoader = imageLoader, modifier = Modifier.size(44.dp))
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            presentation.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        listOfNotNull(presentation.artist,
                            presentation.album?.takeUnless { audioCollectionContext?.kind == NativeAudioCollectionKind.Album },
                            presentation.detail)
                            .distinct().joinToString(" - ")
                            .takeIf(String::isNotBlank)?.let { subtitle ->
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                    }
                    if (presentation.favorite) {
                        Icon(
                            NextcloudIcons.Favorite,
                            contentDescription = "Favorite",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    presentation.duration?.let { duration ->
                        Text(
                            duration,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (playable && audioPlayer != null) {
                        Icon(
                            NextcloudIcons.Play,
                            contentDescription = "Play ${presentation.title}",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else if (onSelectRecord != null) {
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open ${presentation.title}",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            NativeCollectionPagingFooter(
                loadingMore = loadingMore,
                loadMoreError = loadMoreError,
                onRetry = onLoadMore,
            )
        }
    } else {
        val gridState = rememberLazyGridState()
        NativeCollectionGridAutoPager(
            gridState = gridState,
            onLoadMore = onLoadMore,
            loadingMore = loadingMore,
            loadMoreError = loadMoreError,
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(168.dp),
            state = gridState,
            contentPadding = PaddingValues(NextcloudSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            items(mediaItems, key = { (record, _) -> record.id }) { (record, presentation) ->
                val artwork = remember(resource, record, mediaArtworkResolver) {
                    mediaArtworkResolver?.resolve(resource, record)
                        ?: presentation.nativeFallbackArtworkReference(record.id)
                }
                val interaction = onSelectRecord?.let { callback -> Modifier.clickable { callback(record) } } ?: Modifier
                Card(
                    modifier = interaction.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(108.dp),
                            color = NextcloudTheme.colors.appIconContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            NativeMediaArtworkThumbnail(
                                reference = artwork,
                                title = presentation.title,
                                imageLoader = imageLoader,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text(
                            presentation.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        (presentation.artist ?: presentation.detail)?.let { subtitle ->
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (loadingMore || loadMoreError != null) {
                item(
                    key = "media-grid-paging-footer",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    NativeCollectionPagingStatus(
                        loadingMore = loadingMore,
                        loadMoreError = loadMoreError,
                        onRetry = onLoadMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeMediaCollectionHeader(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    collectionContext: NativeAudioCollectionContext,
    imageLoader: NativeImageLoader?,
    audioPlayer: NativeAudioRecordPlayer?,
    mediaArtworkResolver: NativeMediaArtworkResolver?,
) {
    val firstPlayableRecord = remember(resource, records, collectionContext) {
        nativeAudioCollectionFirstPlayableRecord(resource, records, collectionContext)
    }
    val artworkReference = remember(
        resource,
        firstPlayableRecord,
        collectionContext,
        mediaArtworkResolver,
    ) {
        nativeAudioCollectionArtworkReference(
            collectionContext = collectionContext,
            childResource = resource,
            firstPlayableRecord = firstPlayableRecord,
            resolver = mediaArtworkResolver,
        )
    }
    val playableCount = remember(resource, records, collectionContext) {
        records.count { record -> nativeAudioTrack(resource, record, collectionContext) != null }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = NextcloudSpacing.Large),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < 520.dp
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    if (compact) NextcloudSpacing.Medium else NextcloudSpacing.Large,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(
                    if (compact) NextcloudSpacing.Medium else NextcloudSpacing.Large,
                ),
            ) {
                NativeMediaArtworkThumbnail(
                    reference = artworkReference,
                    title = collectionContext.title,
                    imageLoader = imageLoader,
                    modifier = Modifier.size(if (compact) 80.dp else 112.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    Text(
                        when (collectionContext.kind) {
                            NativeAudioCollectionKind.Album -> "Album"
                            NativeAudioCollectionKind.Artist -> "Artist"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        collectionContext.title,
                        style = if (compact) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.headlineSmall
                        },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (playableCount) {
                            0 -> "No playable tracks"
                            1 -> "1 track"
                            else -> "$playableCount tracks"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (firstPlayableRecord != null && audioPlayer != null) {
                        Button(
                            onClick = {
                                audioPlayer.playCollectionIfPossible(
                                    resource,
                                    records,
                                    collectionContext,
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(
                                NextcloudIcons.Play,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text("Play all", modifier = Modifier.padding(start = NextcloudSpacing.Small))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeMediaArtworkThumbnail(
    reference: NativeMediaArtworkReference,
    title: String,
    imageLoader: NativeImageLoader?,
    modifier: Modifier,
) {
    var image by remember(reference.cacheKey, imageLoader) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(reference.cacheKey, imageLoader) {
        image = reference.relativePath?.let { path ->
            imageLoader?.let { loader -> runCatching { loader.load(path) }.getOrNull() }
        }
    }
    Surface(
        modifier = modifier,
        color = NextcloudTheme.colors.appIconContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        image?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = "Artwork for $title",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } ?: Box(contentAlignment = Alignment.Center) {
            if (reference.fallback == NativeMediaArtworkFallback.Artist) {
                Text(
                    title.trim().firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = NextcloudTheme.colors.appIcon,
                )
            } else {
                Icon(
                    NextcloudIcons.app("music"),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
                    tint = NextcloudTheme.colors.appIcon,
                )
            }
        }
    }
}
