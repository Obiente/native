package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ExistingFileShareManager(
    share: NextcloudFileShare,
    sourceIsDirectory: Boolean,
    session: NextcloudSession,
    services: NextcloudPlatformServices,
    onChanged: (NextcloudFileShare) -> Unit,
    onRevoked: (NextcloudFileShare) -> Unit,
) {
    var editing by remember(share.id) { mutableStateOf(false) }
    var confirmRevoke by remember(share.id) { mutableStateOf(false) }
    var allowEditing by remember(share.id, share.permissions) {
        mutableStateOf(fileSharePermissionsFromMask(share.permissions).update)
    }
    var allowResharing by remember(share.id, share.permissions) {
        mutableStateOf(fileSharePermissionsFromMask(share.permissions).reshare)
    }
    var running by remember(share.id) { mutableStateOf(false) }
    var error by remember(share.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val safeUrl = safeFileShareUrl(session, share)
    val label = share.displayName ?: share.shareWith ?: shareTypeLabel(share.shareType)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${shareTypeLabel(share.shareType)} · ${fileSharePermissionsLabel(share.permissions)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (safeUrl != null) {
                TextButton(
                    enabled = !running,
                    onClick = {
                        error = if (services.copyTextToClipboard("Nextcloud share link", safeUrl)) {
                            null
                        } else {
                            "Could not copy the link."
                        }
                    },
                ) { Text("Copy") }
            }
        }
        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                FilterChip(selected = true, enabled = false, onClick = {}, label = { Text("Can view") })
                FilterChip(
                    selected = allowEditing,
                    enabled = !running,
                    onClick = { allowEditing = !allowEditing },
                    label = { Text("Can edit") },
                )
                FilterChip(
                    selected = allowResharing,
                    enabled = !running,
                    onClick = { allowResharing = !allowResharing },
                    label = { Text("Can reshare") },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                TextButton(enabled = !running, onClick = { editing = false }) { Text("Cancel") }
                Button(
                    enabled = !running,
                    onClick = {
                        running = true
                        error = null
                        val permissions = FileSharePermissions(
                            read = true,
                            update = allowEditing,
                            create = allowEditing && sourceIsDirectory,
                            delete = allowEditing && sourceIsDirectory,
                            reshare = allowResharing,
                        )
                        scope.launch {
                            try {
                                services.updateFileSharePermissions(session, share.id, permissions)
                                editing = false
                                onChanged(share.copy(permissions = permissions.mask))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                error = failure.message ?: "Could not update share permissions."
                            }
                            running = false
                        }
                    },
                ) {
                    if (running) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(NextcloudSpacing.Small))
                    }
                    Text("Save")
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                OutlinedButton(
                    enabled = !running,
                    onClick = {
                        error = null
                        editing = true
                    },
                ) { Text("Permissions") }
                TextButton(
                    enabled = !running,
                    onClick = {
                        error = null
                        confirmRevoke = true
                    },
                ) {
                    Text("Revoke", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { if (!running) confirmRevoke = false },
            title = { Text("Revoke access?") },
            text = { Text("$label will no longer have access to this item.") },
            dismissButton = {
                TextButton(enabled = !running, onClick = { confirmRevoke = false }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = !running,
                    onClick = {
                        running = true
                        error = null
                        scope.launch {
                            try {
                                services.revokeFileShare(session, share.id)
                                confirmRevoke = false
                                onRevoked(share)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Throwable) {
                                error = failure.message ?: "Could not revoke this share."
                                confirmRevoke = false
                            }
                            running = false
                        }
                    },
                ) {
                    if (running) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(NextcloudSpacing.Small))
                    }
                    Text("Revoke")
                }
            },
        )
    }
}

private fun shareTypeLabel(type: Int?): String = when (type) {
    FileShareTarget.User.wireValue -> FileShareTarget.User.presentation().label
    FileShareTarget.Group.wireValue -> FileShareTarget.Group.presentation().label
    FileShareTarget.PublicLink.wireValue -> FileShareTarget.PublicLink.presentation().label
    FileShareTarget.Email.wireValue -> FileShareTarget.Email.presentation().label
    FileShareTarget.Remote.wireValue -> FileShareTarget.Remote.presentation().label
    7 -> "Circle"
    10 -> "Talk conversation"
    else -> "Shared access"
}
