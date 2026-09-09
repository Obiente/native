package dev.obiente.nextcloudnative.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun PhotoFolderBrowser(
    inventory: PhotoFolderPagedInventory,
    initialResult: Result<PhotoFolderBrowseResult>? = null,
    selectedFolderPath: String,
    query: String,
    scope: PhotoFolderBrowseScope,
    viewMode: PhotoFolderViewMode,
    backupStatuses: Map<String, MediaBackupStatus>,
    gridState: LazyGridState,
    listState: LazyListState,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String? = null,
    onSelectedFolderPathChanged: (String) -> Unit,
    onQueryChanged: (String) -> Unit,
    onScopeChanged: (PhotoFolderBrowseScope) -> Unit,
    onViewModeChanged: (PhotoFolderViewMode) -> Unit,
    onOpenMedia: (NextcloudFile, MediaStackViewerSequence) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(selectedFolderPath, query, scope, viewMode) {
        PhotoFolderBrowseState(
            selectedFolderPath = selectedFolderPath,
            query = query,
            scope = scope,
            preference = PhotoFolderBrowsePreference(viewMode),
        )
    }
    val rawFileIds = remember(inventory.rawStackFileIdsByRecordPath) {
        inventory.rawStackFileIdsByRecordPath.values
            .asSequence()
            .flatten()
            .distinct()
            .sorted()
            .toList()
    }
    var resolvedRawFilesById by remember(session, userId) {
        mutableStateOf<Map<Long, NextcloudFile>>(emptyMap())
    }
    LaunchedEffect(session, services, userId, rawFileIds) {
        val safeUserId = userId
        if (safeUserId == null || rawFileIds.isEmpty()) {
            resolvedRawFilesById = emptyMap()
            return@LaunchedEffect
        }
        resolvedRawFilesById = try {
            services.resolveFilesById(session, safeUserId, rawFileIds)
                .filter { (fileId, file) ->
                    fileId in rawFileIds && file.fileId == fileId && file.isRawPhoto()
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
    }
    val rawStackFilesByRecordPath = remember(
        inventory.rawStackFileIdsByRecordPath,
        resolvedRawFilesById,
    ) {
        inventory.rawStackFileIdsByRecordPath.mapValues { (_, fileIds) ->
            fileIds.mapNotNull(resolvedRawFilesById::get)
        }
    }
    val resultState by produceState<Result<PhotoFolderBrowseResult>?>(
        initialValue = initialResult,
        inventory,
        state,
        initialResult,
        rawStackFilesByRecordPath,
    ) {
        if (initialResult != null && inventory.rawStackFileIdsByRecordPath.isEmpty()) {
            value = initialResult
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            if (state.query.isNotEmpty()) {
                delay(PHOTO_FOLDER_SEARCH_DEBOUNCE_MILLIS)
            }
            runCatching {
                buildPhotoFolderBrowseResult(
                    inventory = inventory,
                    state = state,
                    rawStackFilesByRecordPath = rawStackFilesByRecordPath,
                )
            }
        }
    }
    val result = resultState?.getOrNull()
    val viewerSequence = remember(result) {
        mediaStackViewerSequence(result?.media.orEmpty())
    }

    Column(modifier = modifier) {
        if (resultState == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    CircularProgressIndicator()
                    Text("Indexing photo folders...")
                }
            }
            return@Column
        }
        if (result == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Could not prepare this photo folder.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return@Column
        }
        PhotoFolderToolbar(
            state = state,
            result = result,
            onSelectedFolderPathChanged = onSelectedFolderPathChanged,
            onQueryChanged = onQueryChanged,
            onScopeChanged = onScopeChanged,
            onViewModeChanged = onViewModeChanged,
        )
        if (result.folders.isEmpty() && result.media.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) {
                        "This folder has no media in the selected scope."
                    } else {
                        "No folder matches your search."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (viewMode == PhotoFolderViewMode.Grid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(128.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NextcloudSpacing.Medium),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                items(result.folders, key = { "folder:${it.path}" }) { folder ->
                    PhotoFolderGridItem(
                        folder = folder,
                        services = services,
                        session = session,
                        onClick = { onSelectedFolderPathChanged(folder.path) },
                    )
                }
                items(result.media, key = { "media:${it.id}" }) { stack ->
                    MediaTile(
                        services = services,
                        session = session,
                        userId = userId,
                        file = stack.cover,
                        badge = stack.badge,
                        backupStatus = backupStatuses[stack.cover.path.trim('/')],
                        onClick = { onOpenMedia(stack.cover, viewerSequence) },
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = NextcloudSpacing.Medium,
                    vertical = NextcloudSpacing.Small,
                ),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
            ) {
                listItems(result.folders, key = { "folder:${it.path}" }) { folder ->
                    PhotoFolderListItem(
                        folder = folder,
                        onClick = { onSelectedFolderPathChanged(folder.path) },
                    )
                }
                listItems(result.media, key = { "media:${it.id}" }) { stack ->
                    PhotoFolderMediaListItem(
                        stack = stack,
                        backupStatus = backupStatuses[stack.cover.path.trim('/')],
                        services = services,
                        session = session,
                        userId = userId,
                        onClick = { onOpenMedia(stack.cover, viewerSequence) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoFolderToolbar(
    state: PhotoFolderBrowseState,
    result: PhotoFolderBrowseResult,
    onSelectedFolderPathChanged: (String) -> Unit,
    onQueryChanged: (String) -> Unit,
    onScopeChanged: (PhotoFolderBrowseScope) -> Unit,
    onViewModeChanged: (PhotoFolderViewMode) -> Unit,
) {
    var scopeMenuExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = NextcloudSpacing.Large,
            vertical = NextcloudSpacing.Small,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            if (state.selectedFolderPath.isNotEmpty()) {
                IconButton(
                    onClick = {
                        onSelectedFolderPathChanged(
                            state.selectedFolderPath.substringBeforeLast(
                                '/',
                                missingDelimiterValue = "",
                            ),
                        )
                    },
                ) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "Open parent folder")
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.selectedFolderPath.ifEmpty { "All photo folders" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    photoFolderMediaCountLabel(
                        count = result.recursiveMediaCount,
                        scope = state.scope,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SingleChoiceSegmentedButtonRow {
                PhotoFolderViewMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.preference.viewMode == mode,
                        onClick = { onViewModeChanged(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = PhotoFolderViewMode.entries.size,
                        ),
                        label = {
                            Icon(
                                imageVector = when (mode) {
                                    PhotoFolderViewMode.Grid -> NextcloudIcons.Apps
                                    PhotoFolderViewMode.List -> NextcloudIcons.ListView
                                },
                                contentDescription = photoFolderViewModeLabel(mode),
                            )
                        },
                    )
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val search: @Composable (Modifier) -> Unit = { modifier ->
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { onQueryChanged(sanitizePhotoFolderQuery(it)) },
                    modifier = modifier,
                    label = { Text("Find folders") },
                    leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                    singleLine = true,
                )
            }
            val scopeControl: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier) {
                    OutlinedButton(
                        onClick = { scopeMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(photoFolderScopeLabel(state.scope))
                    }
                    DropdownMenu(
                        expanded = scopeMenuExpanded,
                        onDismissRequest = { scopeMenuExpanded = false },
                    ) {
                        PhotoFolderBrowseScope.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(photoFolderScopeLabel(option)) },
                                onClick = {
                                    scopeMenuExpanded = false
                                    onScopeChanged(option)
                                },
                            )
                        }
                    }
                }
            }
            if (maxWidth < 520.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    search(Modifier.fillMaxWidth())
                    scopeControl(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    search(Modifier.weight(1f))
                    scopeControl(Modifier.width(220.dp))
                }
            }
        }
    }
}

@Composable
private fun PhotoFolderGridItem(
    folder: PhotoFolderSummary,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        shape = RoundedCornerShape(NextcloudRadii.Card),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PhotoFolderPreviewMosaic(
                folder = folder,
                services = services,
                session = session,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Icon(
                    NextcloudIcons.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    folder.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    photoFolderSummary(folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                }
            }
        }
    }
}

@Composable
private fun PhotoFolderPreviewMosaic(
    folder: PhotoFolderSummary,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    modifier: Modifier = Modifier,
) {
    if (folder.previewFileIds.isEmpty()) {
        Box(
            modifier = modifier.background(NextcloudTheme.colors.appIconContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                NextcloudIcons.Folder,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        return
    }
    val previewFileIds = folder.previewFileIds.take(4)
    when (previewFileIds.size) {
        1 -> PhotoFolderPreviewCell(
            fileId = previewFileIds.single(),
            services = services,
            session = session,
            modifier = modifier,
        )
        2 -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            previewFileIds.forEach { fileId ->
                PhotoFolderPreviewCell(
                    fileId = fileId,
                    services = services,
                    session = session,
                    modifier = Modifier.fillMaxHeight().weight(1f),
                )
            }
        }
        3 -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PhotoFolderPreviewCell(
                fileId = previewFileIds.first(),
                services = services,
                session = session,
                modifier = Modifier.fillMaxHeight().weight(1f),
            )
            Column(
                modifier = Modifier.fillMaxHeight().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                previewFileIds.drop(1).forEach { fileId ->
                    PhotoFolderPreviewCell(
                        fileId = fileId,
                        services = services,
                        session = session,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
        else -> Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(2) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    repeat(2) { column ->
                        PhotoFolderPreviewCell(
                            fileId = previewFileIds[row * 2 + column],
                            services = services,
                            session = session,
                            modifier = Modifier.fillMaxSize().weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoFolderPreviewCell(
    fileId: Long,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(NextcloudTheme.colors.appIconContainer),
        contentAlignment = Alignment.Center,
    ) {
        PhotoFolderPreviewImage(
            fileId = fileId,
            services = services,
            session = session,
        )
    }
}

@Composable
private fun PhotoFolderPreviewImage(
    fileId: Long,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
) {
    val file = remember(fileId) {
        NextcloudFile(
            path = "memories/folder-preview/$fileId",
            name = "$fileId.jpg",
            isDirectory = false,
            mimeType = "image/jpeg",
            size = null,
            lastModified = null,
            fileId = fileId,
            hasPreview = true,
            etag = null,
            originalAccessAllowed = false,
            davPathAuthoritative = false,
            memoriesRenderAllowed = true,
        )
    }
    var image by remember(fileId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(session, fileId) {
        image = services.loadMediaThumbnailDecoded(
            session = session,
            file = file,
            width = 320,
            height = 320,
        ) { payload ->
            decodePlatformImage(payload.bytes, payload.kind.orientationPolicy())
        }
    }
    image?.let { bitmap ->
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun PhotoFolderListItem(
    folder: PhotoFolderSummary,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Medium),
    ) {
        Row(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Icon(
                NextcloudIcons.Folder,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    folder.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    photoFolderSummary(folder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(NextcloudIcons.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun PhotoFolderMediaListItem(
    stack: MediaStack,
    backupStatus: MediaBackupStatus?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    onClick: () -> Unit,
) {
    var image by remember(
        stack.cover.fileId,
        stack.cover.etag,
        stack.cover.hasPreview,
        stack.cover.memoriesRenderAllowed,
    ) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(
        session,
        userId,
        stack.cover.fileId,
        stack.cover.etag,
        stack.cover.hasPreview,
        stack.cover.memoriesRenderAllowed,
    ) {
        if (stack.cover.fileId == null) return@LaunchedEffect
        image = services.loadMediaThumbnailDecoded(
            session = session,
            file = stack.cover,
            userId = userId,
        ) { payload ->
            decodePlatformImage(payload.bytes, payload.kind.orientationPolicy())
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Medium),
    ) {
        Row(
            modifier = Modifier.padding(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                image?.let {
                    Image(
                        bitmap = it,
                        contentDescription = stack.cover.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } ?: Icon(
                    NextcloudIcons.Image,
                    contentDescription = stack.cover.name,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stack.cover.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stack.cover.path.substringBeforeLast('/', missingDelimiterValue = "Photos"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            stack.badge?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            backupStatus?.let { MediaBackupStatusIndicator(it) }
        }
    }
}

internal fun photoFolderScopeLabel(scope: PhotoFolderBrowseScope): String = when (scope) {
    PhotoFolderBrowseScope.FoldersOnly -> "Folders only"
    PhotoFolderBrowseScope.DirectMediaAndSubfolders -> "Photos and subfolders"
    PhotoFolderBrowseScope.DirectMediaOnly -> "Photos in folder"
    PhotoFolderBrowseScope.RecursiveMedia -> "All nested photos"
}

internal fun photoFolderViewModeLabel(viewMode: PhotoFolderViewMode): String = when (viewMode) {
    PhotoFolderViewMode.Grid -> "Grid view"
    PhotoFolderViewMode.List -> "List view"
}

internal fun photoFolderMediaCountLabel(
    count: Int,
    scope: PhotoFolderBrowseScope,
): String {
    require(count >= 0) { "The photo folder media count is invalid." }
    val item = if (count == 1) "media item" else "media items"
    val location = when (scope) {
        PhotoFolderBrowseScope.FoldersOnly -> "indexed below this folder"
        PhotoFolderBrowseScope.DirectMediaAndSubfolders,
        PhotoFolderBrowseScope.DirectMediaOnly,
        -> "in this folder"
        PhotoFolderBrowseScope.RecursiveMedia -> "in this folder tree"
    }
    return "$count $item $location"
}

private fun photoFolderSummary(folder: PhotoFolderSummary): String = buildList {
    if (!folder.countsAuthoritative) {
        add("Browse folder")
        return@buildList
    }
    if (folder.directChildFolderCount > 0) {
        add("${folder.directChildFolderCount} ${if (folder.directChildFolderCount == 1) "folder" else "folders"}")
    }
    add("${folder.recursiveMediaCount} ${if (folder.recursiveMediaCount == 1) "item" else "items"}")
}.joinToString(" - ")

private const val PHOTO_FOLDER_SEARCH_DEBOUNCE_MILLIS = 150L
