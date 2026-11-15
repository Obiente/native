package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.launch

/**
 * Multi-select for the visible Files items with bulk favorite, move, copy, and delete. Each run
 * reports per item what completed, what failed, and what is safe to try again.
 */
@Composable
internal fun FilesSelectionWorkspace(
    files: List<NextcloudFile>,
    currentPath: String,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    deletes: FileDeleteCoordinator,
    onExit: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val runner = remember(services, session, userId, deletes) {
        FileBulkOperationRunner(
            deletes = deletes,
            mutate = { mutation -> services.executeFileMutation(session, userId, mutation) },
            setFavorite = { file, favorite -> services.setFileFavorite(session, userId, file, favorite) },
            readFolder = { folder -> services.listFilesWithSource(session, userId, folder) },
        )
    }
    // Selection is screen-local presentation state keyed by stable paths; it is dropped with the folder.
    var selectedPaths by remember(currentPath) { mutableStateOf(emptySet<String>()) }
    var pickerFor by remember { mutableStateOf<FileBulkAction?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<FileBulkAction?>(null) }
    var report by remember { mutableStateOf<BulkOperationReport?>(null) }
    val visiblePaths = files.mapTo(HashSet(), NextcloudFile::path)
    val selected = files.filter { it.path in selectedPaths }

    fun run(action: FileBulkAction, targets: List<NextcloudFile>, destination: String? = null) {
        if (targets.isEmpty() || running != null) return
        running = action
        report = null
        scope.launch {
            try {
                report = when (action) {
                    FileBulkAction.AddFavorite -> runner.favorite(targets, true)
                    FileBulkAction.RemoveFavorite -> runner.favorite(targets, false)
                    FileBulkAction.Move -> runner.transfer(targets, requireNotNull(destination), copy = false)
                    FileBulkAction.Copy -> runner.transfer(targets, requireNotNull(destination), copy = true)
                    FileBulkAction.Delete -> runner.delete(targets)
                }
                val done = report?.completed?.mapTo(HashSet()) { it.itemKey }.orEmpty()
                selectedPaths = selectedPaths - done
                if (done.isNotEmpty()) onChanged()
            } finally {
                running = null
            }
        }
    }

    PlatformBackHandler(enabled = running == null) { onExit() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (selected.isEmpty()) "Select items" else "${itemCount(selected.size)} selected",
            subtitle = if (currentPath.isEmpty()) "All files" else currentPath,
            onBack = { if (running == null) onExit() },
        ) {
            TextButton(
                onClick = { selectedPaths = if (selected.size == files.size) emptySet() else visiblePaths },
                enabled = running == null && files.isNotEmpty(),
            ) { Text(if (selected.size == files.size && files.isNotEmpty()) "Select none" else "Select all") }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = NextcloudSpacing.Medium, vertical = NextcloudSpacing.Small),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val enabled = selected.isNotEmpty() && running == null
            val allFavorite = selected.isNotEmpty() && selected.all(NextcloudFile::favorite)
            OutlinedButton(
                onClick = { run(if (allFavorite) FileBulkAction.RemoveFavorite else FileBulkAction.AddFavorite, selected) },
                enabled = enabled,
            ) { Text(if (allFavorite) "Unfavorite" else "Favorite") }
            OutlinedButton(onClick = { pickerFor = FileBulkAction.Move }, enabled = enabled) { Text("Move") }
            OutlinedButton(onClick = { pickerFor = FileBulkAction.Copy }, enabled = enabled) { Text("Copy") }
            OutlinedButton(onClick = { confirmDelete = true }, enabled = enabled) {
                Text("Delete", color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (running != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        report?.let { current ->
            BulkOperationReportCard(
                report = current,
                onDismiss = { report = null },
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (files.isEmpty()) {
            EmptyMessage("There is nothing to select in this view.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(files, key = NextcloudFile::path) { file ->
                    val checked = file.path in selectedPaths
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .toggleable(value = checked, enabled = running == null, role = Role.Checkbox) {
                                selectedPaths = if (it) selectedPaths + file.path else selectedPaths - file.path
                            }
                            .padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Icon(
                            if (file.isDirectory) NextcloudIcons.Folder else workspaceFileIcon(file),
                            contentDescription = null,
                            tint = NextcloudTheme.colors.appIcon,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            deletes.writeBlock(file)?.let { block ->
                                Text(block.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                            }
                        }
                        if (file.favorite) Icon(NextcloudIcons.Favorite, contentDescription = "Favorite", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }

    pickerFor?.let { action ->
        RemoteFolderPickerDialog(
            services = services,
            session = session,
            userId = userId,
            writeFence = deletes,
            initialPath = currentPath,
            onDismiss = { pickerFor = null },
            onSelected = { destination ->
                pickerFor = null
                run(action, selected, destination)
            },
        )
    }
    if (confirmDelete) {
        val folders = selected.count(NextcloudFile::isDirectory)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${itemCount(selected.size)}?") },
            text = {
                Text(
                    buildString {
                        append("The selected items are removed from Nextcloud and moved to the trash bin when it is enabled.")
                        if (folders > 0) append(" $folders folder${if (folders == 1) "" else "s"} and everything inside are included.")
                        append(" Each delete stops if the item changed since this folder was loaded.")
                    },
                )
            },
            dismissButton = { OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        run(FileBulkAction.Delete, selected)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
        )
    }
}
