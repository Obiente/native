package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.launch

internal data class RemoteFolderBreadcrumb(
    val label: String,
    val path: String,
)

internal fun canonicalRemoteFolderPath(value: String): String? {
    if (value.isEmpty()) return ""
    if (value.length > MAX_REMOTE_FOLDER_PATH_LENGTH ||
        value.startsWith('/') ||
        value.endsWith('/') ||
        '\\' in value ||
        '\u0000' in value ||
        value.any(Char::isISOControl)
    ) {
        return null
    }
    val segments = value.split('/')
    if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
    return value
}

internal fun normalizeRemoteFolderInput(value: String): String? =
    canonicalRemoteFolderPath(value.trim('/'))

internal fun canConfirmRemoteFolderSelection(
    currentPath: String,
    networkConfirmedPath: String?,
    manualPathVisible: Boolean,
    manualPathDraft: String,
    busy: Boolean,
): Boolean {
    if (busy || networkConfirmedPath != currentPath) return false
    if (!manualPathVisible) return true
    return normalizeRemoteFolderInput(manualPathDraft) == currentPath
}

internal fun remoteFolderBreadcrumbs(path: String): List<RemoteFolderBreadcrumb> {
    val canonical = requireNotNull(canonicalRemoteFolderPath(path)) {
        "The remote folder path is invalid."
    }
    val breadcrumbs = mutableListOf(RemoteFolderBreadcrumb("Files", ""))
    var current = ""
    canonical.split('/').filter(String::isNotEmpty).forEach { segment ->
        current = if (current.isEmpty()) segment else "$current/$segment"
        breadcrumbs += RemoteFolderBreadcrumb(segment, current)
    }
    return breadcrumbs
}

internal fun remoteFolderParentPath(path: String): String? {
    val canonical = requireNotNull(canonicalRemoteFolderPath(path)) {
        "The remote folder path is invalid."
    }
    if (canonical.isEmpty()) return null
    return canonical.substringBeforeLast('/', missingDelimiterValue = "")
}

internal fun remoteFolderDirectories(
    files: List<NextcloudFile>,
    parentPath: String,
    query: String,
): List<NextcloudFile> {
    val canonicalParent = requireNotNull(canonicalRemoteFolderPath(parentPath)) {
        "The remote folder path is invalid."
    }
    val normalizedQuery = query.trim()
    return files.asSequence()
        .filter(NextcloudFile::isDirectory)
        .filter { file ->
            canonicalRemoteFolderPath(file.path) == file.path &&
                remoteFolderParentPath(file.path) == canonicalParent
        }
        .filter { file ->
            normalizedQuery.isEmpty() || file.name.contains(normalizedQuery, ignoreCase = true)
        }
        .distinctBy(NextcloudFile::path)
        .sortedBy { it.name.lowercase() }
        .toList()
}

internal fun newRemoteFolderPath(parentPath: String, name: String): String? {
    val parent = canonicalRemoteFolderPath(parentPath) ?: return null
    if (name.isBlank() || name != name.trim() ||
        name in setOf(".", "..") ||
        name.any { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }
    ) {
        return null
    }
    return canonicalRemoteFolderPath(if (parent.isEmpty()) name else "$parent/$name")
}

@Composable
internal fun RemoteFolderPickerDialog(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    initialPath: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val safeInitialPath = remember(initialPath) { normalizeRemoteFolderInput(initialPath).orEmpty() }
    var currentPath by remember(session, userId, safeInitialPath) { mutableStateOf(safeInitialPath) }
    var files by remember(session, userId) { mutableStateOf<List<NextcloudFile>?>(null) }
    var listingSource by remember(session, userId) {
        mutableStateOf<NextcloudFileListingSource?>(null)
    }
    var networkConfirmedPath by remember(session, userId) { mutableStateOf<String?>(null) }
    var loading by remember(session, userId) { mutableStateOf(true) }
    var refreshing by remember(session, userId) { mutableStateOf(false) }
    var error by remember(session, userId) { mutableStateOf<String?>(null) }
    var query by remember(session, userId) { mutableStateOf("") }
    var loadAttempt by remember(session, userId) { mutableStateOf(0) }
    var createVisible by remember(session, userId) { mutableStateOf(false) }
    var createName by remember(session, userId) { mutableStateOf("") }
    var createError by remember(session, userId) { mutableStateOf<String?>(null) }
    var createRunning by remember(session, userId) { mutableStateOf(false) }
    var manualVisible by remember(session, userId) { mutableStateOf(false) }
    var manualPath by remember(session, userId) { mutableStateOf(safeInitialPath) }
    var manualError by remember(session, userId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session, userId, currentPath, loadAttempt) {
        files = null
        listingSource = null
        networkConfirmedPath = null
        loading = true
        refreshing = false
        error = null
        query = ""
        val cached = runCatching {
            services.listFilesCachedWithSource(session, userId, currentPath)
        }.getOrNull()
        if (cached != null) {
            files = cached.files
            listingSource = cached.source
            loading = false
            refreshing = true
        }
        runCatching { services.listFilesWithSource(session, userId, currentPath) }
            .onSuccess { listing ->
                files = listing.files
                listingSource = listing.source
                networkConfirmedPath = currentPath.takeIf {
                    listing.source == NextcloudFileListingSource.Network
                }
                loading = false
                refreshing = false
                if (listing.source != NextcloudFileListingSource.Network) {
                    error = "Connect to Nextcloud to confirm this destination."
                }
            }
            .onFailure { failure ->
                loading = false
                refreshing = false
                error = if (files == null) {
                    failure.message ?: "Could not open this Nextcloud folder."
                } else {
                    "Could not confirm this cached folder with Nextcloud."
                }
            }
    }

    val directories = remember(files, currentPath, query) {
        remoteFolderDirectories(files.orEmpty(), currentPath, query)
    }
    val breadcrumbs = remember(currentPath) { remoteFolderBreadcrumbs(currentPath) }
    val canConfirm = canConfirmRemoteFolderSelection(
        currentPath = currentPath,
        networkConfirmedPath = networkConfirmedPath,
        manualPathVisible = manualVisible,
        manualPathDraft = manualPath,
        busy = createRunning,
    )

    AlertDialog(
        onDismissRequest = { if (!createRunning) onDismiss() },
        title = { Text("Choose Nextcloud folder") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 590.dp),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                item(key = "breadcrumbs") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        breadcrumbs.forEachIndexed { index, breadcrumb ->
                            if (index > 0) {
                                Text(
                                    " / ",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                enabled = !createRunning && breadcrumb.path != currentPath,
                                onClick = {
                                    currentPath = breadcrumb.path
                                    manualPath = breadcrumb.path
                                },
                            ) {
                                Text(
                                    breadcrumb.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                item(key = "current-path") {
                    Text(
                        if (currentPath.isEmpty()) "Files root" else "/$currentPath",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item(key = "search") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(MAX_REMOTE_FOLDER_SEARCH_LENGTH) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search this folder") },
                        leadingIcon = {
                            Icon(NextcloudIcons.Search, contentDescription = null)
                        },
                        singleLine = true,
                    )
                }
                when {
                    loading -> {
                        item(key = "loading") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    files == null -> {
                        item(key = "load-error") {
                            PickerMessage(
                                message = error ?: "Could not open this folder.",
                                parentPath = remoteFolderParentPath(currentPath),
                                onParent = { parent ->
                                    currentPath = parent
                                    manualPath = parent
                                },
                                onRetry = { loadAttempt += 1 },
                            )
                        }
                    }
                    else -> {
                        if (refreshing) {
                            item(key = "refreshing") {
                                Text(
                                    "Showing cached folders while refreshing…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        error?.let {
                            item(key = "listing-error") {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (directories.isEmpty()) {
                            item(key = "empty-folders") {
                                Text(
                                    if (query.isBlank()) {
                                        "No folders here. You can select this folder or create one."
                                    } else {
                                        "No folders match your search."
                                    },
                                    modifier = Modifier.padding(NextcloudSpacing.Large),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(directories, key = NextcloudFile::path) { directory ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !createRunning) {
                                            currentPath = directory.path
                                            manualPath = directory.path
                                        }
                                        .padding(vertical = NextcloudSpacing.Medium),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                                ) {
                                    Icon(
                                        NextcloudIcons.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        directory.name,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Icon(NextcloudIcons.ChevronRight, contentDescription = "Open folder")
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
                item(key = "folder-actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        OutlinedButton(
                            enabled = !createRunning && networkConfirmedPath == currentPath,
                            onClick = {
                                createVisible = !createVisible
                                manualVisible = false
                                createError = null
                                createName = ""
                            },
                        ) {
                            Icon(
                                NextcloudIcons.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(" New folder")
                        }
                        TextButton(
                            enabled = !createRunning,
                            onClick = {
                                manualVisible = !manualVisible
                                createVisible = false
                                manualPath = currentPath
                                manualError = null
                            },
                        ) {
                            Text("Advanced path")
                        }
                    }
                }
                if (createVisible) {
                    item(key = "create-folder") {
                        Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                            OutlinedTextField(
                                value = createName,
                                onValueChange = {
                                    createName = it.take(MAX_REMOTE_FOLDER_NAME_LENGTH)
                                    createError = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("New folder name") },
                                supportingText = createError?.let { message -> { Text(message) } },
                                isError = createError != null,
                                singleLine = true,
                            )
                            Button(
                                enabled = !createRunning && newRemoteFolderPath(currentPath, createName) != null,
                                onClick = {
                                    val target = newRemoteFolderPath(currentPath, createName)
                                    if (target == null) {
                                        createError = "Enter a valid folder name."
                                        return@Button
                                    }
                                    createRunning = true
                                    createError = null
                                    scope.launch {
                                        runCatching {
                                            services.createDirectoryIfAbsent(session, userId, target)
                                        }.onSuccess {
                                            createVisible = false
                                            createName = ""
                                            currentPath = target
                                            manualPath = target
                                        }.onFailure { failure ->
                                            createError = failure.message ?: "Could not create this folder."
                                        }
                                        createRunning = false
                                    }
                                },
                            ) {
                                if (createRunning) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Create and open")
                                }
                            }
                        }
                    }
                }
                if (manualVisible) {
                    item(key = "manual-path") {
                        Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                            OutlinedTextField(
                                value = manualPath,
                                onValueChange = {
                                    manualPath = it.take(MAX_REMOTE_FOLDER_PATH_LENGTH)
                                    manualError = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Advanced Nextcloud path") },
                                supportingText = {
                                    Text(
                                        manualError
                                            ?: "Open and verify this path before selecting the destination.",
                                    )
                                },
                                isError = manualError != null,
                                singleLine = true,
                            )
                            OutlinedButton(
                                enabled = !createRunning,
                                onClick = {
                                    val target = normalizeRemoteFolderInput(manualPath)
                                    if (target == null) {
                                        manualError = "Enter a valid relative Nextcloud path."
                                    } else {
                                        manualVisible = false
                                        currentPath = target
                                        manualPath = target
                                    }
                                },
                            ) {
                                Text("Open and verify")
                            }
                        }
                    }
                }
                item(key = "selection-status") {
                    Text(
                        when {
                            manualVisible && normalizeRemoteFolderInput(manualPath) != currentPath ->
                                "Open and verify the advanced path before selecting it."
                            canConfirm && currentPath.isEmpty() -> "The Files root is ready to select."
                            canConfirm -> "/$currentPath is ready to select."
                            listingSource == NextcloudFileListingSource.Cache ->
                                "This cached destination must be confirmed online before selection."
                            else -> "Open an accessible folder before confirming."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (canConfirm) FontWeight.Medium else FontWeight.Normal,
                        color = if (canConfirm) {
                            NextcloudTheme.colors.success
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canConfirm,
                onClick = { onSelected(currentPath) },
            ) {
                Text(if (currentPath.isEmpty()) "Use Files root" else "Use this folder")
            }
        },
        dismissButton = {
            TextButton(enabled = !createRunning, onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun PickerMessage(
    message: String,
    parentPath: String?,
    onParent: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(NextcloudRadii.Small),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                parentPath?.let { parent ->
                    TextButton(onClick = { onParent(parent) }) { Text("Go to parent") }
                }
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

private const val MAX_REMOTE_FOLDER_PATH_LENGTH = 8_192
private const val MAX_REMOTE_FOLDER_NAME_LENGTH = 255
private const val MAX_REMOTE_FOLDER_SEARCH_LENGTH = 256
