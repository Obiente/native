package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

enum class NativeMediaCollectionSection {
    Albums,
    Tags,
}

data class NativeMediaCollectionBrowserState(
    val section: NativeMediaCollectionSection = NativeMediaCollectionSection.Albums,
    val query: String = "",
    val showHiddenAlbums: Boolean = false,
)

fun visibleNativeMediaCollections(
    catalog: NativeMediaCollectionCatalog,
    state: NativeMediaCollectionBrowserState,
): List<NativeMediaCollection> {
    val source = when (state.section) {
        NativeMediaCollectionSection.Albums -> catalog.albums
        NativeMediaCollectionSection.Tags -> catalog.tags
    }
    val needle = state.query.trim()
    return source.filter { collection ->
        (state.showHiddenAlbums || !collection.isHidden) &&
            (needle.isEmpty() || collection.name.contains(needle, ignoreCase = true) ||
                collection.ownerDisplayName?.contains(needle, ignoreCase = true) == true ||
                collection.location?.contains(needle, ignoreCase = true) == true)
    }
}

/**
 * Reusable collection picker. It owns no navigation or network state and can therefore be placed
 * under Photos, Memories, global search, or a future desktop split view.
 */
@Composable
fun NativeMediaCollectionBrowser(
    catalog: NativeMediaCollectionCatalog,
    state: NativeMediaCollectionBrowserState,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onStateChange: (NativeMediaCollectionBrowserState) -> Unit,
    onCreateAlbum: () -> Unit,
    onOpenCollection: (NativeMediaCollection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = remember(catalog, state) { visibleNativeMediaCollections(catalog, state) }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = NextcloudSpacing.Large,
                vertical = NextcloudSpacing.Small,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            FilterChip(
                selected = state.section == NativeMediaCollectionSection.Albums,
                onClick = { onStateChange(state.copy(section = NativeMediaCollectionSection.Albums)) },
                label = { Text("Albums (${catalog.albums.count { state.showHiddenAlbums || !it.isHidden }})") },
                leadingIcon = { Icon(NextcloudIcons.Photo, contentDescription = null, Modifier.size(18.dp)) },
            )
            FilterChip(
                selected = state.section == NativeMediaCollectionSection.Tags,
                onClick = { onStateChange(state.copy(section = NativeMediaCollectionSection.Tags)) },
                label = { Text("Tags (${catalog.tags.size})") },
                leadingIcon = { Icon(NextcloudIcons.Tag, contentDescription = null, Modifier.size(18.dp)) },
            )
        }
        if (state.section == NativeMediaCollectionSection.Albums) {
            FilledTonalButton(
                onClick = onCreateAlbum,
                modifier = Modifier.padding(
                    start = NextcloudSpacing.Large,
                    end = NextcloudSpacing.Large,
                    bottom = NextcloudSpacing.Small,
                ),
            ) {
                Icon(NextcloudIcons.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(NextcloudSpacing.Small))
                Text("New album")
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = { onStateChange(state.copy(query = it)) },
            modifier = Modifier.fillMaxWidth().padding(
                start = NextcloudSpacing.Large,
                end = NextcloudSpacing.Large,
                bottom = NextcloudSpacing.Small,
            ),
            singleLine = true,
            leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
            label = {
                Text(
                    when (state.section) {
                        NativeMediaCollectionSection.Albums -> "Search albums"
                        NativeMediaCollectionSection.Tags -> "Search tags"
                    },
                )
            },
            shape = RoundedCornerShape(NextcloudRadii.Card),
        )
        if (state.section == NativeMediaCollectionSection.Albums && catalog.albums.any { it.isHidden }) {
            FilterChip(
                selected = state.showHiddenAlbums,
                onClick = { onStateChange(state.copy(showHiddenAlbums = !state.showHiddenAlbums)) },
                label = { Text("Show hidden albums") },
                modifier = Modifier.padding(
                    start = NextcloudSpacing.Large,
                    end = NextcloudSpacing.Large,
                    bottom = NextcloudSpacing.Small,
                ),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(144.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = NextcloudSpacing.Large,
                top = NextcloudSpacing.Small,
                end = NextcloudSpacing.Large,
                bottom = NextcloudSpacing.XXLarge,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            catalog.warnings.forEachIndexed { index, warning ->
                item(key = "collection-warning-$index", span = { GridItemSpan(maxLineSpan) }) {
                    CollectionWarning(warning)
                }
            }
            if (visible.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (state.query.isBlank()) "No collections are available." else "No collections match your search.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.XXLarge),
                    )
                }
            }
            items(visible, key = NativeMediaCollection::key) { collection ->
                NativeMediaCollectionTile(
                    collection = collection,
                    services = services,
                    session = session,
                    onClick = { onOpenCollection(collection) },
                )
            }
        }
    }
}

@Composable
fun NativeMediaCollectionTile(
    collection: NativeMediaCollection,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        enabled = collection.canBrowse,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NextcloudRadii.Card),
        colors = CardDefaults.cardColors(
            containerColor = NextcloudTheme.colors.appTile,
            disabledContainerColor = NextcloudTheme.colors.appTile,
        ),
    ) {
        NativeMediaCollectionCover(
            collection = collection,
            services = services,
            session = session,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                collection.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                collection.collectionSupportingText(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun NativeMediaCollectionCover(
    collection: NativeMediaCollection,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    modifier: Modifier = Modifier,
) {
    val coverFile = remember(collection.key, collection.cover) { collection.asNextcloudCoverFileOrNull() }
    var image by remember(collection.key, collection.cover) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(session, coverFile?.fileId, coverFile?.etag) {
        image = coverFile?.let { file ->
            runCatching {
                decodePlatformImage(services.loadPreviewCached(session, file, width = 480, height = 480))
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier.background(NextcloudTheme.colors.appIconContainer),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = "Cover for ${collection.name}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } ?: Icon(
            imageVector = if (collection.type == NativeMediaCollectionType.SystemTag) {
                NextcloudIcons.Tag
            } else {
                NextcloudIcons.Photo
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        if (!collection.canBrowse) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = RoundedCornerShape(NextcloudRadii.Pill),
                modifier = Modifier.align(Alignment.BottomStart).padding(NextcloudSpacing.Small),
            ) {
                Text(
                    "Not indexed",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Shared grid for an opened album or tag. Paging remains controlled by the host screen. */
@Composable
fun NativeMediaCollectionContent(
    collection: NativeMediaCollection,
    items: List<NativeMediaItem>,
    resolvedFiles: Map<Long, NextcloudFile> = emptyMap(),
    backupStatuses: Map<String, MediaBackupStatus> = emptyMap(),
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    loadingMore: Boolean,
    canLoadMore: Boolean,
    loadMoreError: String?,
    onOpenMedia: (NextcloudFile, List<NextcloudFile>) -> Unit,
    onLongPressMedia: ((NativeMediaItem) -> Unit)? = null,
    onLoadMore: () -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val files = remember(collection.key, items, resolvedFiles) {
        items.map { media -> resolvedFiles[media.fileId] ?: media.toNextcloudFile(collection.key) }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(NextcloudSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(items, key = NativeMediaItem::fileId) { media ->
            val file = remember(collection.key, media, resolvedFiles) {
                resolvedFiles[media.fileId] ?: media.toNextcloudFile(collection.key)
            }
            NativeMediaItemTile(
                media = media,
                file = file,
                backupStatus = backupStatuses[file.path.trim('/')],
                services = services,
                session = session,
                onClick = { onOpenMedia(file, files) },
                onLongClick = onLongPressMedia?.let { action -> { action(media) } },
            )
        }
        loadMoreItem(loadingMore, canLoadMore, loadMoreError, onLoadMore)
    }
}

@Composable
private fun NativeMediaItemTile(
    media: NativeMediaItem,
    file: NextcloudFile,
    backupStatus: MediaBackupStatus?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    var image by remember(file.fileId, file.etag) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(session, file.fileId, file.etag) {
        image = runCatching {
            decodePlatformImage(services.loadPreviewCached(session, file, width = 384, height = 384))
        }.getOrNull()
    }
    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            .background(NextcloudTheme.colors.appIconContainer)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = "Open ${media.name}",
                onLongClickLabel = onLongClick?.let { "Show actions for ${media.name}" },
            ),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = media.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } ?: Icon(
            imageVector = if (media.isVideo) NextcloudIcons.Video else NextcloudIcons.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(30.dp),
        )
        if (media.isFavorite) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
            ) {
                Icon(
                    NextcloudIcons.Favorite,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(4.dp).size(16.dp),
                )
            }
        }
        onLongClick?.let { showActions ->
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopStart).padding(5.dp),
            ) {
                IconButton(
                    onClick = showActions,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        NextcloudIcons.More,
                        contentDescription = "Actions for ${media.name}",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (media.rawStackFileIds.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                shape = RoundedCornerShape(NextcloudRadii.Pill),
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
            ) {
                Text(
                    "+${media.rawStackFileIds.size} RAW",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        backupStatus?.let { status ->
            MediaBackupStatusIndicator(
                status = status,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp),
            )
        }
    }
}

internal fun LazyGridScope.loadMoreItem(
    loadingMore: Boolean,
    canLoadMore: Boolean,
    error: String?,
    onLoadMore: () -> Unit,
) {
    if (!loadingMore && !canLoadMore && error == null) return
    item(span = { GridItemSpan(maxLineSpan) }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            when {
                loadingMore -> CircularProgressIndicator(modifier = Modifier.size(28.dp))
                error != null -> {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onLoadMore) { Text("Retry") }
                }
                canLoadMore -> Button(onClick = onLoadMore) { Text("Load more") }
            }
        }
    }
}

@Composable
private fun CollectionWarning(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(NextcloudRadii.Card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(NextcloudSpacing.Medium),
        )
    }
}

private fun NativeMediaCollection.collectionSupportingText(): String {
    val count = itemCount?.let { "$it ${if (it == 1) "photo" else "photos"}" } ?: "Photo collection"
    return when {
        type == NativeMediaCollectionType.SystemTag -> count
        isShared && ownerDisplayName != null -> "$count · Shared by $ownerDisplayName"
        isShared -> "$count · Shared"
        location != null -> "$count · $location"
        else -> count
    }
}
