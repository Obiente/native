package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import kotlinx.coroutines.launch

/** Account facts the Notes workspace needs for Files-backed actions such as folders and sharing. */
internal data class NotesFilesAccess(
    val userId: String,
    val sharing: NextcloudFileSharingCapabilities,
) {
    init {
        require(userId.isNotBlank()) { "The Files user is unavailable." }
    }
}

@Composable
internal fun NotesSettingsDialog(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    filesAccess: NotesFilesAccess?,
    onNotesFolderChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val controller = remember(services, session) {
        NotesSettingsController(
            execute = { request -> services.executeNextcloudApi(session, request) },
            onNotesFolderChanged = onNotesFolderChanged,
        )
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.load() }
    val state = controller.state
    var folderDraft by remember(state.settings?.notesPath) { mutableStateOf(state.settings?.notesPath.orEmpty()) }
    var suffixDraft by remember(state.settings?.fileSuffix) { mutableStateOf(state.settings?.fileSuffix.orEmpty()) }
    var choosingFolder by remember { mutableStateOf(false) }
    val change = state.settings?.let { stored ->
        NotesSettingsChange(
            notesPath = folderDraft.trim().trim('/').takeIf { it.isNotEmpty() && it != stored.notesPath.trim('/') },
            fileSuffix = suffixDraft.trim().lowercase().takeIf { it.isNotEmpty() && it != stored.fileSuffix },
        ).takeIf { it.notesPath != null || it.fileSuffix != null }
    }
    val validationError = change?.let { pending ->
        val capabilities = state.capabilities ?: return@let null
        try {
            updateNotesSettingsRequest(capabilities, pending)
            null
        } catch (invalid: IllegalArgumentException) {
            invalid.message
        }
    }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("Notes settings") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                state.errorMessage?.let { NotesSettingsMessage(it, error = true) }
                state.notice?.let { NotesSettingsMessage(it, error = false) }
                when {
                    state.loading -> CircularProgressIndicator()
                    state.notesUnavailable -> Text("The Notes app is not available for this account.")
                    state.capabilities?.supportsSettings == false -> Text(
                        "The installed Notes app does not let other apps change its settings. Notes API 1.2 or newer is needed.",
                    )
                    state.settings == null -> OutlinedButton(onClick = { scope.launch { controller.load() } }) {
                        Text("Load settings again")
                    }
                    else -> {
                        if (state.outcomeUnknown) {
                            OutlinedButton(onClick = { scope.launch { controller.load() } }, enabled = !state.busy) {
                                Text("Reload settings")
                            }
                        }
                        NotesSettingsHeading("Notes folder")
                        Text(
                            "Notes shows the notes stored in this folder of your Files. " +
                                "Changing it does not move existing notes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (filesAccess != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("/${folderDraft.trim('/')}", modifier = Modifier.weight(1f))
                                TextButton(onClick = { choosingFolder = true }, enabled = state.canSave) {
                                    Text("Choose folder")
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = folderDraft,
                                onValueChange = { folderDraft = it },
                                label = { Text("Folder path in Files") },
                                singleLine = true,
                                enabled = state.canSave,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        NotesSettingsHeading("File type for new notes")
                        NotesSuffixOptions(
                            selected = suffixDraft,
                            customAllowed = state.capabilities?.supportsCustomSuffix == true,
                            enabled = state.canSave,
                            onSelected = { suffixDraft = it },
                        )
                        validationError?.let { NotesSettingsMessage(it, error = true) }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Close") }
        },
        confirmButton = {
            if (state.settings != null) {
                Button(
                    onClick = { change?.let { pending -> scope.launch { controller.save(pending) } } },
                    enabled = state.canSave && change != null && validationError == null,
                ) {
                    Text(if (state.busy) "Saving..." else "Save")
                }
            }
        },
    )
    if (choosingFolder && filesAccess != null) {
        val operations = remember(services, session, filesAccess.userId) {
            RemoteFolderPickerOperations(
                identity = "${session.accountId.storageKey}|${filesAccess.userId}|notes-settings",
                listCached = { path -> services.listFilesCachedWithSource(session, filesAccess.userId, path) },
                listNetwork = { path -> services.listFilesWithSource(session, filesAccess.userId, path) },
                createDirectoryIfAbsent = { path -> services.createDirectoryIfAbsent(session, filesAccess.userId, path) },
            )
        }
        RemoteFolderPickerDialog(
            operations = operations,
            initialPath = folderDraft.trim('/'),
            onDismiss = { choosingFolder = false },
            onSelected = { path ->
                if (path.isNotBlank()) folderDraft = path
                choosingFolder = false
            },
        )
    }
}

@Composable
private fun NotesSuffixOptions(
    selected: String,
    customAllowed: Boolean,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    val custom = selected !in NOTES_STANDARD_SUFFIXES
    Column(Modifier.selectableGroup()) {
        NOTES_STANDARD_SUFFIXES.forEach { suffix ->
            NotesSuffixRow(
                label = if (suffix == ".md") "Markdown (.md)" else "Plain text (.txt)",
                selected = selected == suffix,
                enabled = enabled,
                onSelect = { onSelected(suffix) },
            )
        }
        if (customAllowed) {
            NotesSuffixRow(
                label = "Custom suffix",
                selected = custom,
                enabled = enabled,
                onSelect = { if (!custom) onSelected(".") },
            )
            if (custom) {
                OutlinedTextField(
                    value = selected,
                    onValueChange = onSelected,
                    label = { Text("Suffix, such as .markdown") },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun NotesSuffixRow(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label)
    }
}

@Composable
private fun NotesSettingsHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun NotesSettingsMessage(message: String, error: Boolean) {
    Text(
        message,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall,
    )
}
