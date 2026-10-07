package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

internal data class FileTransferDialogIdentity(
    val accountIdentity: String,
    val sourcePath: String,
    val sourceEtag: String?,
    val moving: Boolean,
)

internal data class FileTransferDestination(val directory: String, val name: String)

/** Ephemeral dialog choices; transport, preconditions, and mutation recovery stay with the caller. */
internal data class FileTransferDialogState(
    val identity: FileTransferDialogIdentity,
    val directory: String,
    val name: String,
    val choosingFolder: Boolean = false,
) {
    fun chooseFolder() = copy(choosingFolder = true)
    fun cancelFolderSelection() = copy(choosingFolder = false)
    fun selectFolder(origin: FileTransferDialogIdentity, path: String): FileTransferDialogState =
        if (origin != identity || !choosingFolder) this else copy(directory = path, choosingFolder = false)

    fun presentation(file: NextcloudFile): FileTransferDialogPresentation {
        if (file.path != identity.sourcePath || file.etag != identity.sourceEtag) {
            return FileTransferDialogPresentation(error = "Choose the item again.")
        }
        val validation = fileTransferValidationError(file, directory, name)
        val sameDestination = directory == file.path.substringBeforeLast('/', "") && name == file.name
        return FileTransferDialogPresentation(
            error = validation.takeUnless { sameDestination },
            guidance = if (sameDestination) "Choose another folder or change the name." else null,
            canConfirm = validation == null,
        )
    }

    fun confirmedDestination(file: NextcloudFile): FileTransferDestination? =
        if (!choosingFolder && presentation(file).canConfirm) FileTransferDestination(directory, name) else null
}

internal data class FileTransferDialogPresentation(
    val error: String? = null,
    val guidance: String? = null,
    val canConfirm: Boolean = false,
)

@Composable
internal fun FileTransferDialog(
    file: NextcloudFile,
    moving: Boolean,
    initialDirectory: String,
    operations: RemoteFolderPickerOperations,
    running: Boolean,
    error: String?,
    onEdited: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (FileTransferDestination) -> Unit,
) {
    val identity = FileTransferDialogIdentity(operations.identity, file.path, file.etag, moving)
    var state by remember(identity, initialDirectory) {
        mutableStateOf(FileTransferDialogState(identity, initialDirectory, file.name))
    }
    if (state.choosingFolder) {
        RemoteFolderPickerDialog(
            operations = operations,
            initialPath = state.directory,
            onDismiss = { state = state.cancelFolderSelection() },
            onSelected = { directory ->
                val selected = state.selectFolder(identity, directory)
                if (selected != state) {
                    state = selected
                    onEdited()
                }
            },
        )
        return
    }
    val verb = if (moving) "Move" else "Copy"
    val presentation = state.presentation(file)
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("$verb ${file.name}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Text("Destination folder", style = MaterialTheme.typography.labelLarge)
                Text(
                    state.directory.ifEmpty { "All files" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(enabled = !running, onClick = { state = state.chooseFolder() }) {
                    Text("Choose folder")
                }
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { state = state.copy(name = it); onEdited() },
                    label = { Text("Name at destination") },
                    singleLine = true,
                    enabled = !running,
                    isError = presentation.error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Existing items are never overwritten.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val displayedError = error ?: presentation.error
                if (displayedError != null) {
                    Text(displayedError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else presentation.guidance?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        dismissButton = { TextButton(enabled = !running, onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(
                enabled = !running && presentation.canConfirm,
                onClick = { state.confirmedDestination(file)?.let(onConfirm) },
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (running) { if (moving) "Moving..." else "Copying..." } else verb)
            }
        },
    )
}
