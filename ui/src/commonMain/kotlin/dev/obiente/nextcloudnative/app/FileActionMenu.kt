package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.obiente.nextcloudnative.app.design.NextcloudIcons

@Composable
internal fun FileActionMenu(
    file: NextcloudFile,
    offlineAvailability: FileOfflineAvailability,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (FileMenuAction) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        planFilesScreenActions(
            file = file,
            support = FileActionSupport(
                sharing = fileSharing.apiEnabled,
                externalSharing = ExternalFileHandoffAction.Share in
                    externalHandoffCapability?.supportedActions.orEmpty(),
                offlineStorage = offlineStorageSupported,
                platformViewer = ExternalFileHandoffAction.OpenWith in
                    externalHandoffCapability?.supportedActions.orEmpty(),
                maximumInMemoryExternalFileBytes = externalHandoffCapability?.maximumInMemoryFileBytes,
                seekableExternalFileStreaming =
                    externalHandoffCapability?.supportsSeekableRemoteStreaming == true,
                discoverDocumentEditing = true,
            ),
            offlineState = offlineAvailability.toFileActionOfflineState(),
        ).actions.forEach { action ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(action.label)
                        action.disabledReason?.let { reason ->
                            Text(
                                reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = fileActionIcon(action.action),
                        contentDescription = null,
                        tint = if (action.tone == FileActionTone.Destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                enabled = action.enabled,
                onClick = {
                    onDismiss()
                    onAction(action.action)
                },
            )
        }
    }
}

private fun fileActionIcon(action: FileMenuAction): ImageVector = when (action) {
    FileMenuAction.Open -> NextcloudIcons.FolderOpen
    FileMenuAction.Preview -> NextcloudIcons.Image
    FileMenuAction.OpenWith -> NextcloudIcons.File
    FileMenuAction.EditText, FileMenuAction.EditWith, FileMenuAction.Rename -> NextcloudIcons.Edit
    FileMenuAction.AddFavorite -> NextcloudIcons.FavoriteBorder
    FileMenuAction.RemoveFavorite -> NextcloudIcons.Favorite
    FileMenuAction.Details -> NextcloudIcons.Info
    FileMenuAction.VersionHistory -> NextcloudIcons.Refresh
    FileMenuAction.Download -> NextcloudIcons.Cloud
    FileMenuAction.Move -> NextcloudIcons.FolderOpen
    FileMenuAction.Copy -> NextcloudIcons.File
    FileMenuAction.Share -> NextcloudIcons.People
    FileMenuAction.SendCopy -> NextcloudIcons.Cloud
    FileMenuAction.MakeAvailableOffline, FileMenuAction.RemoveOffline -> NextcloudIcons.CheckCircle
    FileMenuAction.Delete -> NextcloudIcons.Error
}
