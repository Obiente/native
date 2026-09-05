package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

import androidx.compose.foundation.verticalScroll

@Composable
internal fun FilesInspector(
    file: NextcloudFile?,
    offlineAvailability: FileOfflineAvailability?,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    onClose: () -> Unit,
    onOpen: (NextcloudFile) -> Unit,
    onAction: (NextcloudFile, FileMenuAction) -> Unit,
) {
    Column(
        modifier = Modifier.width(304.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        if (file == null) {
            Text("Details", style = MaterialTheme.typography.titleMedium)
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = RoundedCornerShape(NextcloudRadii.Card)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.XLarge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    Icon(NextcloudIcons.Info, contentDescription = null, modifier = Modifier.size(30.dp))
                    Text("Select an item", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "See a preview, location, owner, offline state, sharing, and version actions here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }
        var menuExpanded by remember(file.path) { mutableStateOf(false) }
        var preview by remember(file.fileId, file.etag, file.hasPreview) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(session, userId, file.fileId, file.etag, file.hasPreview) {
            file.fileId ?: return@LaunchedEffect
            if (file.isDirectory || !file.isPhotoMedia()) return@LaunchedEffect
            preview = services.loadMediaThumbnailDecoded(
                session = session,
                userId = userId,
                file = file,
                width = 620,
                height = 420,
            ) { payload ->
                decodePlatformImage(payload.bytes, payload.kind.orientationPolicy())
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(NextcloudIcons.Close, "Close details") }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(NextcloudIcons.More, contentDescription = "Actions for ${file.name}")
                }
                FileActionMenu(
                    file = file,
                    offlineAvailability = offlineAvailability ?: FileOfflineAvailability.OnlineOnly,
                    offlineStorageSupported = offlineStorageSupported,
                    fileSharing = fileSharing,
                    externalHandoffCapability = externalHandoffCapability,
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    onAction = { onAction(file, it) },
                )
            }
        }
        Surface(
            color = NextcloudTheme.colors.appIconContainer,
            shape = RoundedCornerShape(NextcloudRadii.Card),
            modifier = (if (file.isPhotoMedia()) Modifier.fillMaxWidth().aspectRatio(1.55f) else Modifier.size(64.dp)).clip(RoundedCornerShape(NextcloudRadii.Card)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                preview?.let {
                    Image(it, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } ?: Icon(
                    if (file.isDirectory) NextcloudIcons.Folder else workspaceFileIcon(file),
                    contentDescription = null,
                    tint = NextcloudTheme.colors.appIcon,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (file.favorite) Icon(NextcloudIcons.Favorite, "Favorite", tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                file.readableFileType(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
            FilledTonalButton(onClick = { onOpen(file) }, modifier = Modifier.weight(1f)) {
                Text(if (file.isDirectory) "Open" else "Preview")
            }
            IconButton(onClick = {
                onAction(file, if (file.favorite) FileMenuAction.RemoveFavorite else FileMenuAction.AddFavorite)
            }) {
                Icon(
                    if (file.favorite) NextcloudIcons.Favorite else NextcloudIcons.FavoriteBorder,
                    contentDescription = if (file.favorite) "Remove favorite" else "Add favorite",
                )
            }
            if (fileSharing.apiEnabled) {
                IconButton(onClick = { onAction(file, FileMenuAction.Share) }) {
                    Icon(NextcloudIcons.People, contentDescription = "Share ${file.name}")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        InspectorProperty("Location", "/${file.path}")
        InspectorProperty("Modified", file.lastModified.readableFileDate())
        if (!file.isDirectory) InspectorProperty("Size", formatWorkspaceBytes(file.size))
        file.ownerDisplayName?.let { InspectorProperty("Owner", it) }
        offlineAvailability?.readableStatus()?.let { InspectorProperty("Offline", it) }
        if (file.unreadComments > 0) InspectorProperty("Comments", "${file.unreadComments} unread")
        if (!file.isDirectory && file.fileId != null) {
            TextButton(onClick = { onAction(file, FileMenuAction.VersionHistory) }) {
                Text("View version history")
            }
        }
    }
}

@Composable
private fun InspectorProperty(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(74.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}
