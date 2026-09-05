package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected

@Composable
internal fun NativeFileWorkspaceList(
    files: List<NextcloudFile>,
    compact: Boolean,
    offlineAvailability: Map<String, FileOfflineAvailability>,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    selectedFile: NextcloudFile?,
    onSelectedFileChanged: (NextcloudFile?) -> Unit,
    onOpenPath: (String) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    onAction: (NextcloudFile, FileMenuAction) -> Unit,
    desktop: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = fileTableColumns(maxWidth.value / LocalDensity.current.fontScale, desktop && !compact)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
        ) {
            if (desktop && !compact) {
                item(key = "header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        Text("Name", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        if (columns.modified) Text("Modified", modifier = Modifier.width(112.dp), style = MaterialTheme.typography.labelMedium)
                        if (columns.size) Text("Size", modifier = Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(48.dp))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            items(files, key = NextcloudFile::path) { file ->
                var menuExpanded by remember(file.path) { mutableStateOf(false) }
                val selected = selectedFile?.path == file.path
                val availability = offlineAvailability[file.path] ?: FileOfflineAvailability.OnlineOnly
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    shape = RoundedCornerShape(NextcloudRadii.Small),
                    modifier = Modifier.fillMaxWidth().semantics { if (desktop) this.selected = selected }.combinedClickable(
                        onClickLabel = if (desktop) "Select ${file.name}" else primaryFileActionLabel(file),
                        onLongClickLabel = "Show actions for ${file.name}",
                        onClick = {
                            if (desktop) onSelectedFileChanged(file)
                            else if (file.isDirectory) onOpenPath(file.path) else onOpenFile(file)
                        },
                        onDoubleClick = if (desktop) {
                            { if (file.isDirectory) onOpenPath(file.path) else onOpenFile(file) }
                        } else null,
                        onLongClick = { menuExpanded = true },
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(
                            horizontal = NextcloudSpacing.Medium,
                            vertical = if (compact) 2.dp else 5.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = RoundedCornerShape(9.dp)) {
                            Icon(
                                if (file.isDirectory) NextcloudIcons.Folder else workspaceFileIcon(file),
                                contentDescription = null,
                                tint = NextcloudTheme.colors.appIcon,
                                modifier = Modifier.padding(if (compact) 7.dp else 9.dp).size(if (compact) 18.dp else 22.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    file.name,
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (file.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                if (file.favorite) {
                                    Icon(
                                        NextcloudIcons.Favorite,
                                        contentDescription = "Favorite",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 6.dp).size(15.dp),
                                    )
                                }
                            }
                            if (!compact) {
                                Text(
                                    availability.readableStatus()
                                        ?: file.readableFileType(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (columns.modified) {
                            Text(
                                file.lastModified.readableFileDate(),
                                modifier = Modifier.width(112.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        if (columns.size) {
                            Text(
                                if (file.isDirectory) "-" else formatWorkspaceBytes(file.size),
                                modifier = Modifier.width(72.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(NextcloudIcons.More, contentDescription = "Actions for ${file.name}")
                            }
                            FileActionMenu(
                                file = file,
                                offlineAvailability = availability,
                                offlineStorageSupported = offlineStorageSupported,
                                fileSharing = fileSharing,
                                externalHandoffCapability = externalHandoffCapability,
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                onAction = { onAction(file, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NativeFileWorkspaceGrid(
    files: List<NextcloudFile>,
    offlineAvailability: Map<String, FileOfflineAvailability>,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    selectedFile: NextcloudFile?,
    onSelectedFileChanged: (NextcloudFile?) -> Unit,
    onOpenPath: (String) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    onAction: (NextcloudFile, FileMenuAction) -> Unit,
    desktop: Boolean,
) {
    LazyVerticalGrid(
        modifier = Modifier.fillMaxSize(),
        columns = GridCells.Adaptive(if (desktop) 160.dp else 132.dp),
        contentPadding = PaddingValues(NextcloudSpacing.Large),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        gridItems(files, key = NextcloudFile::path) { file ->
            NativeFileWorkspaceTile(
                file = file,
                selected = selectedFile?.path == file.path,
                availability = offlineAvailability[file.path] ?: FileOfflineAvailability.OnlineOnly,
                offlineStorageSupported = offlineStorageSupported,
                fileSharing = fileSharing,
                externalHandoffCapability = externalHandoffCapability,
                services = services,
                session = session,
                userId = userId,
                onClick = {
                    if (desktop) onSelectedFileChanged(file)
                    else if (file.isDirectory) onOpenPath(file.path) else onOpenFile(file)
                },
                onDoubleClick = if (desktop) {
                    { if (file.isDirectory) onOpenPath(file.path) else onOpenFile(file) }
                } else null,
                onAction = { onAction(file, it) },
            )
        }
    }
}

@Composable
private fun NativeFileWorkspaceTile(
    file: NextcloudFile,
    selected: Boolean,
    availability: FileOfflineAvailability,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)?,
    onAction: (FileMenuAction) -> Unit,
) {
    var menuExpanded by remember(file.path) { mutableStateOf(false) }
    var preview by remember(file.fileId, file.etag, file.hasPreview) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(session, userId, file.fileId, file.etag, file.hasPreview) {
        file.fileId ?: return@LaunchedEffect
        if (file.isDirectory || !file.isPhotoMedia()) return@LaunchedEffect
        preview = services.loadMediaThumbnailDecoded(
            session = session,
            userId = userId,
            file = file,
            width = 420,
            height = 300,
        ) { payload ->
            decodePlatformImage(payload.bytes, payload.kind.orientationPolicy())
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().semantics { if (onDoubleClick != null) this.selected = selected }.combinedClickable(
            onClickLabel = if (onDoubleClick != null) "Select ${file.name}" else primaryFileActionLabel(file),
            onLongClickLabel = "Show actions for ${file.name}",
            onClick = onClick,
            onDoubleClick = onDoubleClick,
            onLongClick = { menuExpanded = true },
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else NextcloudTheme.colors.appTile,
        ),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1.35f).background(NextcloudTheme.colors.appIconContainer),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let {
                Image(it, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: Icon(
                if (file.isDirectory) NextcloudIcons.Folder else workspaceFileIcon(file),
                contentDescription = null,
                tint = NextcloudTheme.colors.appIcon,
                modifier = Modifier.size(38.dp),
            )
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(NextcloudSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (file.favorite) {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shape = CircleShape) {
                        Icon(
                            NextcloudIcons.Favorite,
                            contentDescription = "Favorite",
                            tint = NextcloudTheme.colors.appIcon,
                            modifier = Modifier.padding(8.dp).size(17.dp),
                        )
                    }
                }
                Box {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shape = CircleShape) {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                            Icon(NextcloudIcons.More, contentDescription = "Actions for ${file.name}")
                        }
                    }
                    FileActionMenu(
                        file = file,
                        offlineAvailability = availability,
                        offlineStorageSupported = offlineStorageSupported,
                        fileSharing = fileSharing,
                        externalHandoffCapability = externalHandoffCapability,
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onAction = onAction,
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(NextcloudSpacing.Medium)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            Text(
                availability.readableStatus() ?: if (file.isDirectory) "Folder" else formatWorkspaceBytes(file.size),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
