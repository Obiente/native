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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    capabilities: NextcloudFileSharingCapabilities,
    onChanged: (NextcloudFileShare) -> Unit,
    onRevoked: (NextcloudFileShare) -> Unit,
) {
    var editing by remember(share.id) { mutableStateOf(false) }
    var confirmRevoke by remember(share.id) { mutableStateOf(false) }
    var draft by remember(share.id) { mutableStateOf(existingFileShareEditDraft(share)) }
    val target = share.target()
    val passwordPolicy = target?.let(capabilities::passwordPolicy) ?: FileShareFeaturePolicy(false)
    val expirationPolicy = target?.let(capabilities::expirationPolicy) ?: FileShareFeaturePolicy(false)
    var running by remember(share.id) { mutableStateOf(false) }
    var error by remember(share.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val safeUrl = safeFileShareUrl(session, share)
    val label = share.displayName ?: share.shareWith ?: shareTypeLabel(share.shareType)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        ExistingFileShareSummary(
            share = share,
            running = running,
            canCopy = safeUrl != null,
            showManagementActions = !editing,
            onCopy = {
                error = if (safeUrl != null && services.copyTextToClipboard("Nextcloud share link", safeUrl)) {
                    null
                } else {
                    "Could not copy the link."
                }
            },
            onPermissions = {
                draft = existingFileShareEditDraft(share)
                error = null
                editing = true
            },
            onRevoke = {
                error = null
                confirmRevoke = true
            },
        )
        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                FilterChip(selected = true, enabled = false, onClick = {}, label = { Text("Can view") })
                FilterChip(
                    selected = draft.allowEditing,
                    enabled = !running,
                    onClick = { draft = draft.copy(allowEditing = !draft.allowEditing) },
                    label = { Text("Can edit") },
                )
                FilterChip(
                    selected = draft.allowResharing,
                    enabled = !running,
                    onClick = { draft = draft.copy(allowResharing = !draft.allowResharing) },
                    label = { Text("Can reshare") },
                )
            }
            if (passwordPolicy.supported || share.passwordProtected) {
                OutlinedTextField(
                    value = draft.newPassword,
                    enabled = !running && !draft.removePassword,
                    onValueChange = {
                        draft = draft.copy(newPassword = it, removePassword = false)
                    },
                    label = { Text("New password") },
                    supportingText = {
                        Text(
                            if (share.passwordProtected) {
                                "Leave blank to keep the current password."
                            } else {
                                "Leave blank to keep this share without a password."
                            },
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (share.passwordProtected && !passwordPolicy.enforced) {
                    FilterChip(
                        selected = draft.removePassword,
                        enabled = !running,
                        onClick = {
                            val removing = !draft.removePassword
                            draft = draft.copy(
                                removePassword = removing,
                                newPassword = if (removing) "" else draft.newPassword,
                            )
                        },
                        label = { Text("Remove password") },
                    )
                }
            }
            if (expirationPolicy.supported || share.expiration != null) {
                OutlinedTextField(
                    value = draft.expirationDate,
                    enabled = !running,
                    onValueChange = { draft = draft.copy(expirationDate = it) },
                    label = { Text("Expiration date") },
                    placeholder = { Text("YYYY-MM-DD") },
                    supportingText = {
                        Text(
                            if (expirationPolicy.enforced) {
                                "This server requires an expiration date."
                            } else {
                                "Clear the field to remove the expiration date."
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = draft.note,
                enabled = !running,
                onValueChange = { draft = draft.copy(note = it) },
                label = { Text("Note") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                TextButton(
                    enabled = !running,
                    onClick = {
                        draft = existingFileShareEditDraft(share)
                        error = null
                        editing = false
                    },
                ) { Text("Cancel") }
                Button(
                    enabled = !running,
                    onClick = {
                        val request = runCatching {
                            planExistingFileShareUpdate(
                                share = share,
                                draft = draft,
                                sourceIsDirectory = sourceIsDirectory,
                                target = target,
                                expirationPolicy = expirationPolicy,
                            )
                        }.getOrElse { failure ->
                            error = failure.message ?: "The share settings are invalid."
                            return@Button
                        }
                        if (request == null) {
                            draft = existingFileShareEditDraft(share)
                            error = null
                            editing = false
                            return@Button
                        }
                        running = true
                        error = null
                        scope.launch {
                            try {
                                val changed = services.updateFileShare(
                                    session,
                                    request,
                                )
                                draft = existingFileShareEditDraft(changed)
                                editing = false
                                onChanged(changed)
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

@Composable
internal fun ExistingFileShareSummary(
    share: NextcloudFileShare,
    running: Boolean,
    canCopy: Boolean,
    showManagementActions: Boolean,
    onCopy: () -> Unit,
    onPermissions: () -> Unit,
    onRevoke: () -> Unit,
) {
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
                    "${shareTypeLabel(share.shareType)} - ${fileSharePermissionsLabel(share.permissions)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canCopy) {
                TextButton(
                    enabled = !running,
                    onClick = onCopy,
                ) { Text("Copy") }
            }
        }
        if (share.passwordProtected || share.expiration != null) {
            Text(
                buildList {
                    if (share.passwordProtected) add("Password protected")
                    share.expiration?.let { add("Expires $it") }
                }.joinToString(" - "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        share.note?.let { note ->
            Text(
                note,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showManagementActions) {
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                OutlinedButton(
                    enabled = !running,
                    onClick = onPermissions,
                ) { Text("Permissions") }
                TextButton(
                    enabled = !running,
                    onClick = onRevoke,
                ) {
                    Text("Revoke", color = MaterialTheme.colorScheme.error)
                }
            }
        }
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
