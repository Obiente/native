package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme

@Immutable
internal data class FileShareDialogUiState(
    val file: NextcloudFile,
    val capabilities: NextcloudFileSharingCapabilities,
    val existingShares: List<NextcloudFileShare>?,
    val target: FileShareTarget,
    val recipient: String = "",
    val allowEditing: Boolean = false,
    val details: FileShareCreationDetails = FileShareCreationDetails(),
    val running: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    val supportedTargets: List<FileShareTarget>
        get() = FileShareTarget.entries.filter(capabilities::canOffer)

    val creationPlan: FileShareCreationPlan
        get() = planFileShareCreation(
            file = file,
            target = target,
            recipient = recipient.takeIf { target.requiresRecipient },
            permissions = (
                if (allowEditing) FileSharePermissionPreset.Edit else FileSharePermissionPreset.View
                ).toPermissions(file.isDirectory),
            capabilities = capabilities,
            details = details,
        )
}

/**
 * Shared production dialog for file-browser and media-viewer Nextcloud access management.
 *
 * The slots contain the stateful recipient lookup and existing-share mutations. Keeping the
 * surrounding dialog pure lets deterministic captures render the same production UI without
 * constructing a live platform service or advancing network timers.
 */
@Composable
internal fun FileShareDialog(
    state: FileShareDialogUiState,
    onDismiss: () -> Unit,
    onTargetChanged: (FileShareTarget) -> Unit,
    onAllowEditingChanged: (Boolean) -> Unit,
    onDetailsChanged: (FileShareCreationDetails) -> Unit,
    onCreate: (FileShareCreationPlan.Ready) -> Unit,
    recipientPicker: @Composable (FileShareTarget) -> Unit,
    existingShare: @Composable (NextcloudFileShare) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = { Text("Share ${state.file.name}") },
        text = {
            FileShareDialogContent(
                state = state,
                onTargetChanged = onTargetChanged,
                onAllowEditingChanged = onAllowEditingChanged,
                onDetailsChanged = onDetailsChanged,
                recipientPicker = recipientPicker,
                existingShare = existingShare,
            )
        },
        dismissButton = {
            FileShareDialogDismissAction(
                state = state,
                onDismiss = onDismiss,
            )
        },
        confirmButton = {
            FileShareDialogConfirmAction(
                state = state,
                onCreate = onCreate,
            )
        },
    )
}

@Composable
internal fun FileShareDialogContent(
    state: FileShareDialogUiState,
    onTargetChanged: (FileShareTarget) -> Unit,
    onAllowEditingChanged: (Boolean) -> Unit,
    onDetailsChanged: (FileShareCreationDetails) -> Unit,
    recipientPicker: @Composable (FileShareTarget) -> Unit,
    existingShare: @Composable (NextcloudFileShare) -> Unit,
    maximumHeight: Dp = 520.dp,
) {
    val creationPlan = state.creationPlan
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maximumHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Text(
            if (state.file.isDirectory) {
                "Manage access to this folder on your Nextcloud server."
            } else {
                "Manage access to this file on your Nextcloud server."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Existing access", style = MaterialTheme.typography.titleSmall)
        when (val shares = state.existingShares) {
            null -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else -> if (shares.isEmpty()) {
                Text(
                    "Not shared yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                shares.take(MAX_VISIBLE_EXISTING_FILE_SHARES).forEach { share ->
                    existingShare(share)
                }
                if (shares.size > MAX_VISIBLE_EXISTING_FILE_SHARES) {
                    Text(
                        "${shares.size - MAX_VISIBLE_EXISTING_FILE_SHARES} more shares are active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.supportedTargets.isNotEmpty()) {
            HorizontalDivider()
            Text("Create access", style = MaterialTheme.typography.titleSmall)
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                contentPadding = PaddingValues(end = NextcloudSpacing.Small),
            ) {
                items(state.supportedTargets, key = FileShareTarget::name) { target ->
                    FilterChip(
                        selected = state.target == target,
                        enabled = !state.running,
                        onClick = { onTargetChanged(target) },
                        label = {
                            Text(
                                target.presentation().label,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
            if (state.target.requiresRecipient) {
                recipientPicker(state.target)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                FilterChip(
                    selected = !state.allowEditing,
                    enabled = !state.running,
                    onClick = { onAllowEditingChanged(false) },
                    label = { Text("Can view") },
                )
                FilterChip(
                    selected = state.allowEditing,
                    enabled = !state.running,
                    onClick = { onAllowEditingChanged(true) },
                    label = { Text("Can edit") },
                )
            }
            FileShareCreationFields(
                target = state.target,
                capabilities = state.capabilities,
                details = state.details,
                enabled = !state.running,
                onDetailsChanged = onDetailsChanged,
            )
            (creationPlan as? FileShareCreationPlan.Blocked)?.let { blocked ->
                Text(
                    blocked.reason,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        state.notice?.let {
            Text(
                it,
                color = NextcloudTheme.colors.success,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun FileShareDialogDismissAction(
    state: FileShareDialogUiState,
    onDismiss: () -> Unit,
) {
    TextButton(
        enabled = !state.running,
        onClick = onDismiss,
    ) { Text("Close") }
}

@Composable
internal fun FileShareDialogConfirmAction(
    state: FileShareDialogUiState,
    onCreate: (FileShareCreationPlan.Ready) -> Unit,
) {
    val creationPlan = state.creationPlan
    Button(
        enabled = creationPlan is FileShareCreationPlan.Ready && !state.running,
        onClick = {
            val ready = creationPlan as? FileShareCreationPlan.Ready ?: return@Button
            onCreate(ready)
        },
    ) {
        if (state.running) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(NextcloudSpacing.Small))
        }
        Text(if (state.running) "Creating..." else "Create share")
    }
}

private const val MAX_VISIBLE_EXISTING_FILE_SHARES = 12
