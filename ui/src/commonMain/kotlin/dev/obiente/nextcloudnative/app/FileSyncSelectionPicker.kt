package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

internal fun fileSyncSelectionRelativePath(remoteRootPath: String, absolutePath: String): String? {
    val root = canonicalRemoteFolderPath(remoteRootPath) ?: return null
    val absolute = canonicalRemoteFolderPath(absolutePath) ?: return null
    return when {
        root.isEmpty() && absolute.isNotEmpty() -> absolute
        root.isNotEmpty() && absolute.startsWith("$root/") -> absolute.removePrefix("$root/")
        else -> null
    }?.takeIf { relative -> runCatching { requireValidSyncPath(relative) }.isSuccess }
}

@Composable
internal fun RemoteFileSyncSelectionDialog(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    remoteRootPath: String,
    initialSelection: List<String>,
    onDismiss: () -> Unit,
    onSelected: (List<String>) -> Unit,
    embedded: Boolean = false,
) {
    val root = remember(remoteRootPath) { canonicalRemoteFolderPath(remoteRootPath).orEmpty() }
    var currentRelativePath by rememberSaveable(root) { mutableStateOf("") }
    var selectedPaths by rememberSaveable(root, initialSelection) {
        mutableStateOf(initialSelection.distinct().sorted())
    }
    var files by remember(session, userId, root) { mutableStateOf<List<NextcloudFile>?>(null) }
    var displayedAbsolutePath by remember(session, userId, root) { mutableStateOf<String?>(null) }
    var networkConfirmed by remember(session, userId, root) { mutableStateOf(false) }
    var refreshing by remember(session, userId, root) { mutableStateOf(false) }
    var loading by remember(session, userId, root) { mutableStateOf(true) }
    var error by remember(session, userId, root) { mutableStateOf<String?>(null) }
    var loadAttempt by rememberSaveable(session.serverUrl, session.loginName, userId, root) {
        mutableStateOf(0)
    }
    val absoluteCurrentPath = remember(root, currentRelativePath) {
        listOf(root, currentRelativePath).filter(String::isNotEmpty).joinToString("/")
    }

    LaunchedEffect(session, userId, absoluteCurrentPath, loadAttempt) {
        val retainingCurrentPath = files != null && displayedAbsolutePath == absoluteCurrentPath
        loading = !retainingCurrentPath
        refreshing = retainingCurrentPath
        if (!retainingCurrentPath) {
            files = null
            networkConfirmed = false
        }
        error = null
        runCatching { services.listFilesWithSource(session, userId, absoluteCurrentPath) }
            .rethrowRemoteFolderCancellation()
            .onSuccess { listing ->
                files = listing.files
                displayedAbsolutePath = absoluteCurrentPath
                networkConfirmed = listing.source == NextcloudFileListingSource.Network
                if (!networkConfirmed) error = "Connect to Nextcloud to verify selectable items."
            }
            .onFailure { failure ->
                error = failure.message ?: "Could not open this mapped Nextcloud folder."
            }
        loading = false
        refreshing = false
    }

    val visibleItems = remember(files, absoluteCurrentPath, root) {
        files.orEmpty().asSequence()
            .filter { file ->
                canonicalRemoteFolderPath(file.path) == file.path &&
                    remoteFolderParentPath(file.path) == absoluteCurrentPath
            }
            .mapNotNull { file ->
                fileSyncSelectionRelativePath(root, file.path)?.let { relative -> file to relative }
            }
            .distinctBy { (_, relative) -> relative }
            .sortedWith(compareBy<Pair<NextcloudFile, String>> { !it.first.isDirectory }.thenBy { it.first.name.lowercase() })
            .toList()
    }
    val breadcrumbs = remember(currentRelativePath) { remoteFolderBreadcrumbs(currentRelativePath) }

    fun toggle(relativePath: String) {
        if (fileSyncPathSelection(relativePath, selectedPaths) == FileSyncPathSelection.Inherited) return
        selectedPaths = if (relativePath in selectedPaths) {
            selectedPaths - relativePath
        } else if (selectedPaths.size < MAX_FILE_SYNC_SELECTION_PATHS) {
            (selectedPaths + relativePath).distinct().sorted()
        } else {
            selectedPaths
        }
    }

    val pickerText: @Composable () -> Unit = {
        LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 590.dp),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                item(key = "selection-summary") {
                    Text("Nextcloud: /$absoluteCurrentPath", style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (selectedPaths.isEmpty()) {
                            "Nothing selected yet. Leaving the selection empty syncs the whole mapped folder."
                        } else if (selectedPaths.size == 1) {
                            "1 item selected"
                        } else {
                            "${selectedPaths.size} items selected"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (selectedPaths.isNotEmpty()) {
                    item(key = "selected-items") {
                        Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                            selectedPaths.take(MAX_VISIBLE_SYNC_SELECTIONS).forEach { selected ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(NextcloudRadii.Small),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(
                                            start = NextcloudSpacing.Medium,
                                            end = NextcloudSpacing.XSmall,
                                        ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            NextcloudIcons.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            selected,
                                            modifier = Modifier.weight(1f).padding(horizontal = NextcloudSpacing.Small),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        TextButton(onClick = { toggle(selected) }) { Text("Remove") }
                                    }
                                }
                            }
                            if (selectedPaths.size > MAX_VISIBLE_SYNC_SELECTIONS) {
                                Text(
                                    "+${selectedPaths.size - MAX_VISIBLE_SYNC_SELECTIONS} more selected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item(key = "selection-breadcrumbs") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        breadcrumbs.forEachIndexed { index, breadcrumb ->
                            if (index > 0) Text(" / ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (breadcrumb.path == currentRelativePath) {
                                Text(
                                    if (index == 0) if (root.isEmpty()) "Files" else root.substringAfterLast('/') else breadcrumb.label,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                TextButton(onClick = { currentRelativePath = breadcrumb.path }) {
                                    Text(
                                        if (index == 0) "Mapped folder" else breadcrumb.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                if (currentRelativePath.isNotEmpty() && networkConfirmed) {
                    item(key = "select-current-folder:$currentRelativePath") {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { toggle(currentRelativePath) }
                                .padding(vertical = NextcloudSpacing.Small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FileSyncSelectionCheckbox(currentRelativePath, selectedPaths) { toggle(currentRelativePath) }
                            Text("Select this folder", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (refreshing) {
                    item(key = "selection-refreshing") {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                if (error != null && files != null) {
                    item(key = "selection-refresh-error") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            shape = RoundedCornerShape(NextcloudRadii.Small),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = NextcloudSpacing.Medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    requireNotNull(error),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { loadAttempt += 1 }) { Text("Retry") }
                            }
                        }
                    }
                }
                when {
                    loading -> item(key = "selection-loading") {
                        Row(Modifier.fillMaxWidth().padding(NextcloudSpacing.Large), Arrangement.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    files == null || !networkConfirmed -> item(key = "selection-error") {
                        PickerMessage(
                            message = error ?: "Could not verify this folder.",
                            parentPath = currentRelativePath.takeIf(String::isNotEmpty)?.substringBeforeLast('/', ""),
                            onParent = { currentRelativePath = it },
                            onRetry = { loadAttempt += 1 },
                        )
                    }
                    visibleItems.isEmpty() -> item(key = "selection-empty") {
                        Text(
                            "No folders or files are available here.",
                            modifier = Modifier.padding(NextcloudSpacing.Large),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> items(visibleItems, key = { (_, relative) -> "sync-selection:$relative" }) { (file, relative) ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    if (file.isDirectory) currentRelativePath = relative else toggle(relative)
                                }
                                .padding(vertical = NextcloudSpacing.Small),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            FileSyncSelectionCheckbox(relative, selectedPaths) { toggle(relative) }
                            Icon(
                                if (file.isDirectory) NextcloudIcons.Folder else NextcloudIcons.File,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(file.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (file.isDirectory) {
                                Icon(NextcloudIcons.ChevronRight, contentDescription = "Open folder")
                            }
                        }
                        HorizontalDivider()
                    }
                }
        }
    }
    val confirmButton: @Composable () -> Unit = {
        Button(enabled = !loading && error == null, onClick = { onSelected(selectedPaths) }) {
            Text("Use selection")
        }
    }
    val dismissButton: @Composable () -> Unit = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
    }
    val content: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                shape = RoundedCornerShape(NextcloudRadii.Large),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(16.dp).heightIn(max = maxHeight),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Choose what syncs", style = MaterialTheme.typography.titleLarge)
                    Box(Modifier.weight(1f, fill = false)) { pickerText() }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
    if (embedded) content()
    else Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        content()
    }
}

internal enum class FileSyncPathSelection { None, Partial, Explicit, Inherited }

internal fun fileSyncPathSelection(path: String, selectedPaths: List<String>): FileSyncPathSelection = when {
    path in selectedPaths -> FileSyncPathSelection.Explicit
    selectedPaths.any { path.startsWith("$it/") } -> FileSyncPathSelection.Inherited
    selectedPaths.any { it.startsWith("$path/") } -> FileSyncPathSelection.Partial
    else -> FileSyncPathSelection.None
}

@Composable
private fun FileSyncSelectionCheckbox(path: String, selectedPaths: List<String>, onToggle: () -> Unit) {
    val selection = fileSyncPathSelection(path, selectedPaths)
    TriStateCheckbox(
        state = when (selection) {
            FileSyncPathSelection.Explicit, FileSyncPathSelection.Inherited -> ToggleableState.On
            FileSyncPathSelection.Partial -> ToggleableState.Indeterminate
            FileSyncPathSelection.None -> ToggleableState.Off
        },
        onClick = onToggle,
        enabled = selection != FileSyncPathSelection.Inherited,
        modifier = Modifier.semantics {
            contentDescription = when (selection) {
                FileSyncPathSelection.Inherited -> "$path, included by a selected parent folder"
                FileSyncPathSelection.Partial -> "$path, some contents selected"
                else -> "Select $path"
            }
        },
    )
}

private const val MAX_VISIBLE_SYNC_SELECTIONS = 4
