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
internal fun RemoteFolderPickerDialog(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    initialPath: String,
    selectionError: String? = null,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val safeInitialPath = remember(initialPath) { normalizeRemoteFolderInput(initialPath).orEmpty() }
    var currentPath by rememberSaveable(
        session.serverUrl,
        session.loginName,
        userId,
        safeInitialPath,
    ) {
        mutableStateOf(safeInitialPath)
    }
    var files by remember(session, userId) { mutableStateOf<List<NextcloudFile>?>(null) }
    var listingSource by remember(session, userId) {
        mutableStateOf<NextcloudFileListingSource?>(null)
    }
    var networkConfirmedPath by remember(session, userId) { mutableStateOf<String?>(null) }
    var loading by remember(session, userId) { mutableStateOf(true) }
    var refreshing by remember(session, userId) { mutableStateOf(false) }
    var error by remember(session, userId) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(session.serverUrl, session.loginName, userId) { mutableStateOf("") }
    var loadAttempt by rememberSaveable(session.serverUrl, session.loginName, userId) {
        mutableStateOf(0)
    }
    var createVisible by rememberSaveable(session.serverUrl, session.loginName, userId) {
        mutableStateOf(false)
    }
    var createName by rememberSaveable(session.serverUrl, session.loginName, userId) {
        mutableStateOf("")
    }
    var createError by remember(session, userId) { mutableStateOf<String?>(null) }
    var createRunning by remember(session, userId) { mutableStateOf(false) }
    var manualVisible by rememberSaveable(session.serverUrl, session.loginName, userId) {
        mutableStateOf(false)
    }
    var manualPath by rememberSaveable(session.serverUrl, session.loginName, userId) {
        mutableStateOf(safeInitialPath)
    }
    var manualError by remember(session, userId) { mutableStateOf<String?>(null) }
    var recoveryTarget by rememberSaveable(
        session.serverUrl,
        session.loginName,
        userId,
        safeInitialPath,
    ) {
        mutableStateOf(safeInitialPath.takeIf(String::isNotEmpty))
    }
    var missingDestination by remember(session, userId, safeInitialPath) {
        mutableStateOf<MissingRemoteFolderDestination?>(null)
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session, userId, currentPath, loadAttempt) {
        files = null
        listingSource = null
        networkConfirmedPath = null
        loading = true
        refreshing = false
        error = null
        query = ""
        missingDestination = null
        val cached = runCatching {
            services.listFilesCachedWithSource(session, userId, currentPath)
        }.rethrowRemoteFolderCancellation().getOrNull()
        if (cached != null) {
            files = cached.files
            listingSource = cached.source
            loading = false
            refreshing = true
        }
        runCatching { services.listFilesWithSource(session, userId, currentPath) }
            .rethrowRemoteFolderCancellation()
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
                } else {
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
                            enabled = !createRunning && networkConfirmedPath == currentPath,
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
                                            services.createDirectoryIfAbsent(session, userId, target)
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
                    networkConfirmedPath = networkConfirmedPath,
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
                                destination.pathsToCreate.forEach { path ->
                                    services.createDirectoryIfAbsent(session, userId, path)
                                }
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
    var networkConfirmed by remember(session, userId, root) { mutableStateOf(false) }
    var loading by remember(session, userId, root) { mutableStateOf(true) }
    var error by remember(session, userId, root) { mutableStateOf<String?>(null) }
    var loadAttempt by rememberSaveable(session.serverUrl, session.loginName, userId, root) {
        mutableStateOf(0)
    }
    val absoluteCurrentPath = remember(root, currentRelativePath) {
        listOf(root, currentRelativePath).filter(String::isNotEmpty).joinToString("/")
    }

    LaunchedEffect(session, userId, absoluteCurrentPath, loadAttempt) {
        loading = true
        files = null
        networkConfirmed = false
        error = null
        runCatching { services.listFilesWithSource(session, userId, absoluteCurrentPath) }
            .rethrowRemoteFolderCancellation()
            .onSuccess { listing ->
                files = listing.files
                networkConfirmed = listing.source == NextcloudFileListingSource.Network
                if (!networkConfirmed) error = "Connect to Nextcloud to verify selectable items."
            }
            .onFailure { failure ->
                error = failure.message ?: "Could not open this mapped Nextcloud folder."
            }
        loading = false
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
                    Text(
                        if (selectedPaths.isEmpty()) {
                            "Nothing selected yet. Leaving the selection empty syncs the whole mapped folder."
                        } else if (selectedPaths.size == 1) {
                            "1 verified item selected"
                        } else {
                            "${selectedPaths.size} verified items selected"
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
                                    if (index == 0) "Mapped folder" else breadcrumb.label,
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
                            Checkbox(
                                checked = currentRelativePath in selectedPaths,
                                onCheckedChange = { toggle(currentRelativePath) },
                            )
                            Text("Select this folder", fontWeight = FontWeight.SemiBold)
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
                            Checkbox(
                                checked = relative in selectedPaths,
                                onCheckedChange = { toggle(relative) },
                            )
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
    if (embedded) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(NextcloudRadii.Large),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(NextcloudSpacing.XLarge),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Text("Choose what syncs", style = MaterialTheme.typography.headlineSmall)
                pickerText()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton()
                    confirmButton()
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Choose what syncs") },
            text = pickerText,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
        )
    }
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
private const val MAX_VISIBLE_SYNC_SELECTIONS = 4
