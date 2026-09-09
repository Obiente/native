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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.CancellationException
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

internal fun remoteFolderRowKey(path: String): String {
    val canonical = requireNotNull(canonicalRemoteFolderPath(path)) {
        "The remote folder path is invalid."
    }
    return "remote-folder:${canonical.length}:$canonical"
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

internal data class MissingRemoteFolderDestination(
    val intendedPath: String,
    val accessibleParentPath: String,
    val pathsToCreate: List<String>,
)

internal fun missingRemoteFolderDestination(
    intendedPath: String,
    accessibleParentPath: String,
): MissingRemoteFolderDestination? {
    val intended = canonicalRemoteFolderPath(intendedPath) ?: return null
    val parent = canonicalRemoteFolderPath(accessibleParentPath) ?: return null
    if (intended.isEmpty() || intended == parent) return null
    if (parent.isNotEmpty() && !intended.startsWith("$parent/")) return null

    val parentSegmentCount = parent.split('/').count(String::isNotEmpty)
    val intendedSegments = intended.split('/')
    val paths = intendedSegments.indices
        .drop(parentSegmentCount)
        .map { index -> intendedSegments.take(index + 1).joinToString("/") }
    if (paths.isEmpty() || paths.any { canonicalRemoteFolderPath(it) != it }) return null
    return MissingRemoteFolderDestination(
        intendedPath = intended,
        accessibleParentPath = parent,
        pathsToCreate = paths,
    )
}

internal fun missingRemoteFolderParentAfter(failure: Throwable, path: String): String? {
    if (failure !is NextcloudFileListingHttpException || failure.status != 404) return null
    return remoteFolderParentPath(path)
}

internal fun <T> Result<T>.rethrowRemoteFolderCancellation(): Result<T> {
    val failure = exceptionOrNull()
    if (failure is CancellationException) throw failure
    return this
}

internal fun remoteFolderSelectionStatus(
    loading: Boolean,
    currentPath: String,
    canConfirm: Boolean,
    listingSource: NextcloudFileListingSource?,
    manualPathVisible: Boolean,
    manualPathDraft: String,
    missingDestinationPath: String? = null,
): String = when {
    loading -> "Loading this Nextcloud folder before it can be selected."
    manualPathVisible && normalizeRemoteFolderInput(manualPathDraft) != currentPath ->
        "Open and verify the advanced path before selecting it."
    missingDestinationPath != null ->
        "/$missingDestinationPath will be created when you confirm."
    canConfirm && currentPath.isEmpty() -> "The Files root is ready to select."
    canConfirm -> "/$currentPath is ready to select."
    listingSource == NextcloudFileListingSource.Cache ->
        "This cached destination must be confirmed online before selection."
    else -> "Open an accessible folder before confirming."
}

internal fun canCreateMissingRemoteFolderDestination(
    missingDestination: MissingRemoteFolderDestination?,
    networkConfirmedPath: String?,
    currentPath: String,
    manualPathVisible: Boolean,
    manualPathDraft: String,
    busy: Boolean,
): Boolean = !busy &&
    missingDestination?.accessibleParentPath == networkConfirmedPath &&
    (
        !manualPathVisible ||
            normalizeRemoteFolderInput(manualPathDraft) == currentPath
        )

@Composable
fun RemoteFolderPickerDialog(
    operations: RemoteFolderPickerOperations,
    initialPath: String,
    selectionError: String? = null,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val safeInitialPath = remember(initialPath) { normalizeRemoteFolderInput(initialPath).orEmpty() }
    var currentPath by rememberSaveable(
        operations.identity,
        safeInitialPath,
    ) {
        mutableStateOf(safeInitialPath)
    }
    var files by remember(operations.identity) { mutableStateOf<List<NextcloudFile>?>(null) }
    var listingSource by remember(operations.identity) {
        mutableStateOf<NextcloudFileListingSource?>(null)
    }
    var networkConfirmedPath by remember(operations.identity) { mutableStateOf<String?>(null) }
    var networkConfirmedDirectoryCreationPath by remember(operations.identity) { mutableStateOf<String?>(null) }
    var displayedPath by remember(operations.identity) { mutableStateOf<String?>(null) }
    var loading by remember(operations.identity) { mutableStateOf(true) }
    var refreshing by remember(operations.identity) { mutableStateOf(false) }
    var error by remember(operations.identity) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(operations.identity) { mutableStateOf("") }
    var loadAttempt by rememberSaveable(operations.identity) {
        mutableStateOf(0)
    }
    var createVisible by rememberSaveable(operations.identity) {
        mutableStateOf(false)
    }
    var createName by rememberSaveable(operations.identity) {
        mutableStateOf("")
    }
    var createError by remember(operations.identity) { mutableStateOf<String?>(null) }
    var createRunning by remember(operations.identity) { mutableStateOf(false) }
    var manualVisible by rememberSaveable(operations.identity) {
        mutableStateOf(false)
    }
    var manualPath by rememberSaveable(operations.identity) {
        mutableStateOf(safeInitialPath)
    }
    var manualError by remember(operations.identity) { mutableStateOf<String?>(null) }
    var recoveryTarget by rememberSaveable(
        operations.identity,
        safeInitialPath,
    ) {
        mutableStateOf(safeInitialPath.takeIf(String::isNotEmpty))
    }
    var missingDestination by remember(operations.identity, safeInitialPath) {
        mutableStateOf<MissingRemoteFolderDestination?>(null)
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(operations, currentPath, loadAttempt) {
        networkConfirmedPath = null
        networkConfirmedDirectoryCreationPath = null
        val retainingCurrentPath = files != null && displayedPath == currentPath
        if (!retainingCurrentPath) {
            files = null
            listingSource = null
            query = ""
        }
        loading = !retainingCurrentPath
        refreshing = retainingCurrentPath
        error = null
        missingDestination = null
        val cached = runCatching {
            operations.listCached(currentPath)
        }.rethrowRemoteFolderCancellation().getOrNull()
        if (cached != null) {
            files = cached.files
            listingSource = cached.source
            displayedPath = currentPath
            loading = false
            refreshing = true
        }
        runCatching { operations.listNetwork(currentPath) }
            .rethrowRemoteFolderCancellation()
            .onSuccess { listing ->
                val selectionAccess = operations.confirmSelectionAccess(currentPath, listing.source)
                files = listing.files
                listingSource = listing.source
                displayedPath = currentPath
                networkConfirmedPath = currentPath.takeIf {
                    listing.source == NextcloudFileListingSource.Network &&
                        selectionAccess == RemoteFolderSelectionAccess.Allowed
                }
                networkConfirmedDirectoryCreationPath = currentPath.takeIf {
                    listing.source == NextcloudFileListingSource.Network &&
                        selectionAccess !is RemoteFolderSelectionAccess.Denied
                }
                loading = false
                refreshing = false
                error = (selectionAccess as? RemoteFolderSelectionAccess.Denied)?.message
                if (selectionAccess !is RemoteFolderSelectionAccess.Denied) {
                    missingDestination = recoveryTarget?.let { intended ->
                        missingRemoteFolderDestination(intended, currentPath)
                    }
                }
            }
            .onFailure { failure ->
                loading = false
                refreshing = false
                val recoveryParent = recoveryTarget?.let { _ ->
                    missingRemoteFolderParentAfter(failure, currentPath)
                }
                if (recoveryParent != null) {
                    currentPath = recoveryParent
                    manualPath = recoveryParent
                    return@onFailure
                }
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
                                    recoveryTarget = null
                                    missingDestination = null
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
                selectionError?.let { message ->
                    item(key = "selection-error") {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
                                    "Showing cached folders while refreshing...",
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
                            items(directories, key = { directory -> remoteFolderRowKey(directory.path) }) { directory ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !createRunning) {
                                            recoveryTarget = null
                                            missingDestination = null
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
                missingDestination?.let { destination ->
                    item(key = "missing-destination") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(NextcloudRadii.Small),
                        ) {
                            Column(
                                modifier = Modifier.padding(NextcloudSpacing.Large),
                                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            ) {
                                Text(
                                    "/${destination.intendedPath}",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    "This folder will be created when you confirm it here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                if (destination.pathsToCreate.size > 1) {
                                    Text(
                                        "${destination.pathsToCreate.size} missing folders will be created safely.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                                createError?.let { message ->
                                    Text(
                                        message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
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
                            enabled = !createRunning && networkConfirmedDirectoryCreationPath == currentPath,
                            onClick = {
                                recoveryTarget = null
                                missingDestination = null
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
                                            operations.createDirectoryIfAbsent(target)
                                        }.rethrowRemoteFolderCancellation().onSuccess {
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
                                        recoveryTarget = null
                                        missingDestination = null
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
                        remoteFolderSelectionStatus(
                            loading = loading,
                            currentPath = currentPath,
                            canConfirm = canConfirm,
                            listingSource = listingSource,
                            manualPathVisible = manualVisible,
                            manualPathDraft = manualPath,
                            missingDestinationPath = missingDestination?.intendedPath,
                        ),
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
                enabled = canConfirm || canCreateMissingRemoteFolderDestination(
                    missingDestination = missingDestination,
                    networkConfirmedPath = networkConfirmedDirectoryCreationPath,
                    currentPath = currentPath,
                    manualPathVisible = manualVisible,
                    manualPathDraft = manualPath,
                    busy = createRunning,
                ),
                onClick = {
                    val destination = missingDestination
                    if (destination == null) {
                        onSelected(currentPath)
                        return@Button
                    }
                    createRunning = true
                    createError = null
                    scope.launch {
                        try {
                            runCatching {
                                operations.createAndConfirmDestination(destination)
                            }.rethrowRemoteFolderCancellation().onSuccess {
                                recoveryTarget = null
                                missingDestination = null
                                onSelected(destination.intendedPath)
                            }.onFailure { failure ->
                                createError = failure.message ?: "Could not create this destination."
                            }
                        } finally {
                            createRunning = false
                        }
                    }
                },
            ) {
                Text(
                    when {
                        missingDestination != null -> "Create and use this folder"
                        currentPath.isEmpty() -> "Use Files root"
                        else -> "Use this folder"
                    },
                )
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
internal fun PickerMessage(
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
