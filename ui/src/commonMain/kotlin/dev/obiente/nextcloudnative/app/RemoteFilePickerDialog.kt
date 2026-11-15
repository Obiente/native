package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

/** Folder-first, name-sorted children of [parentPath] from a listing that may include the folder itself. */
internal fun remoteFilePickerEntries(files: List<NextcloudFile>, parentPath: String): List<NextcloudFile> {
    val parent = canonicalRemoteFolderPath(parentPath) ?: return emptyList()
    return files.asSequence()
        .filter { file -> canonicalRemoteFolderPath(file.path) == file.path && remoteFolderParentPath(file.path) == parent }
        .distinctBy(NextcloudFile::path)
        .sortedWith(compareBy<NextcloudFile> { !it.isDirectory }.thenBy { it.name.lowercase() })
        .toList()
}

/**
 * Chooses files from the account's Nextcloud Files, for example to attach them to a message.
 *
 * Only the server listing is used; nothing is downloaded to the device. Selections across folders
 * are kept until the dialog closes, and the result contains files only, never folders.
 */
@Composable
internal fun RemoteFilePickerDialog(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    title: String,
    confirmLabel: String,
    maximumSelection: Int,
    onDismiss: () -> Unit,
    onSelected: (List<NextcloudFile>) -> Unit,
) {
    var path by rememberSaveable(session.accountId) { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<NextcloudFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<Map<String, NextcloudFile>>(emptyMap()) }
    LaunchedEffect(services, session, userId, path, attempt) {
        entries = null
        error = null
        runCatchingPreservingCancellation { services.listFilesWithSource(session, userId, path) }
            .onSuccess { listing -> entries = remoteFilePickerEntries(listing.files, path) }
            .onFailure { error = "This folder could not be opened. Check your connection and try again." }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    remoteFolderParentPath(path)?.let { parent ->
                        TextButton(onClick = { path = parent }) { Text("Up") }
                    }
                    Text(
                        if (path.isEmpty()) "Files" else "Files / ${path.replace("/", " / ")}",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val current = entries
                when {
                    error != null -> Column {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { attempt += 1 }) { Text("Try again") }
                    }
                    current == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    current.isEmpty() -> Text("This folder is empty.")
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(current, key = NextcloudFile::path) { file ->
                            RemoteFilePickerRow(
                                file = file,
                                checked = file.path in selected,
                                canSelectMore = selected.size < maximumSelection,
                                onOpenFolder = { path = file.path },
                                onToggle = { checked ->
                                    selected = if (checked) selected + (file.path to file) else selected - file.path
                                },
                            )
                        }
                    }
                }
                if (selected.isNotEmpty()) {
                    Text("${selected.size} selected", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            Button(enabled = selected.isNotEmpty(), onClick = { onSelected(selected.values.toList()) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RemoteFilePickerRow(
    file: NextcloudFile,
    checked: Boolean,
    canSelectMore: Boolean,
    onOpenFolder: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (file.isDirectory) {
                    Modifier.clickable(role = Role.Button, onClickLabel = "Open folder", onClick = onOpenFolder)
                } else {
                    Modifier.clickable(role = Role.Checkbox, enabled = checked || canSelectMore) { onToggle(!checked) }
                },
            )
            .semantics { if (!file.isDirectory) stateDescription = if (checked) "Selected" else "Not selected" }
            .padding(vertical = NextcloudSpacing.XSmall),
    ) {
        if (file.isDirectory) {
            Icon(NextcloudIcons.Folder, contentDescription = null, modifier = Modifier.padding(NextcloudSpacing.Medium).size(20.dp))
        } else {
            Checkbox(checked = checked, onCheckedChange = onToggle, enabled = checked || canSelectMore)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!file.isDirectory) {
                file.size?.let { size ->
                    Text(formatByteSize(size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
