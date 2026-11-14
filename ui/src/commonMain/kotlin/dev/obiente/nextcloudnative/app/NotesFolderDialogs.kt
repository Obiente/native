package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RenameNoteFolderDialog(
    folder: NativeNoteFolder,
    noteStore: NotesRepository,
    session: NextcloudSession,
    onDismiss: () -> Unit,
    onReconciled: (List<NextcloudNote>, AccountPrivateMemoryProducer?) -> Unit,
    onRenamed: (String, AccountPrivateMemoryProducer?) -> Unit,
    onSubmittingChanged: (Boolean) -> Unit,
) {
    var name by remember(folder.path) { mutableStateOf(folder.name) }
    var submitting by remember(folder.path) { mutableStateOf(false) }
    var error by remember(folder.path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(folder.path) {
        onDispose { onSubmittingChanged(false) }
    }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Rename folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    enabled = !submitting,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && name != folder.name && !submitting,
                onClick = {
                    val destination = runCatching { noteFolderRenameTarget(folder.path, name) }
                        .onFailure { error = it.message }
                        .getOrNull() ?: return@Button
                    val cacheProducer = sharedNextcloudNotesCache.producer(session)
                    submitting = true
                    onSubmittingChanged(true)
                    scope.launch {
                        try {
                            noteStore.renameNoteCategory(folder.path, destination)
                            onRenamed(destination, cacheProducer)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            (failure as? PartialNoteFolderMutationException)
                                ?.refreshedSummaries
                                ?.let { refreshed -> onReconciled(refreshed, cacheProducer) }
                            error = failure.message ?: "Could not rename the folder."
                        } finally {
                            submitting = false
                            onSubmittingChanged(false)
                        }
                    }
                },
            ) { Text(if (submitting) "Renaming..." else "Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}

@Composable
internal fun DeleteNoteFolderDialog(
    folder: NativeNoteFolder,
    noteStore: NotesRepository,
    session: NextcloudSession,
    onDismiss: () -> Unit,
    onReconciled: (List<NextcloudNote>, AccountPrivateMemoryProducer?) -> Unit,
    onDeleted: (AccountPrivateMemoryProducer?) -> Unit,
    onSubmittingChanged: (Boolean) -> Unit,
) {
    var submitting by remember(folder.path) { mutableStateOf(false) }
    var error by remember(folder.path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(folder.path) {
        onDispose { onSubmittingChanged(false) }
    }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text("Delete ${folder.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Text(
                    "This deletes the folder and all ${folder.descendantNoteCount} notes inside it. " +
                        "This may not be reversible.",
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !submitting,
                onClick = {
                    val cacheProducer = sharedNextcloudNotesCache.producer(session)
                    submitting = true
                    onSubmittingChanged(true)
                    scope.launch {
                        try {
                            noteStore.deleteNoteCategory(folder.path)
                            onDeleted(cacheProducer)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            (failure as? PartialNoteFolderMutationException)
                                ?.refreshedSummaries
                                ?.let { refreshed -> onReconciled(refreshed, cacheProducer) }
                            error = failure.message ?: "Could not delete the folder."
                        } finally {
                            submitting = false
                            onSubmittingChanged(false)
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(if (submitting) "Deleting..." else "Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") } },
    )
}
