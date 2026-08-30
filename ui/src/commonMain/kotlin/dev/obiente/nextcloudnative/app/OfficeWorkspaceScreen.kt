package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing

@Composable
internal fun OfficeWorkspaceScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val operations = remember(services, session, userId) { officeWorkspaceOperations(services, session, userId) }
    val accountScope = remember(session.serverUrl, session.loginName) { previewCacheDigest(session) }
    OfficeWorkspaceContent(operations, accountScope, userId.isNotBlank(), onExit, modifier) { file, previewModifier ->
        NextcloudDocumentPreview(file = file, session = session, userId = userId, services = services, modifier = previewModifier)
    }
}

@Composable
internal fun OfficeWorkspaceContent(
    operations: OfficeWorkspaceOperations,
    accountScope: String,
    accountReady: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable (NextcloudFile, Modifier) -> Unit,
) {
    val locationSaver = remember(accountScope) { officeWorkspaceLocationStateSaver(accountScope) }
    var location by rememberSaveable(accountScope, saver = locationSaver) { mutableStateOf(OfficeWorkspaceLocation()) }
    val workspace = remember(operations, accountScope) { OfficeWorkspace(operations) }
    val state by workspace.state.collectAsState()
    var attempt by remember(workspace) { mutableIntStateOf(0) }
    var selected by remember(workspace) { mutableStateOf<NextcloudFile?>(null) }
    fun back() {
        if (selected != null || location.selectedFileId != null) {
            selected = null
            location = location.copy(selectedFileId = null)
            attempt += 1
        } else {
            val parent = remoteFolderParentPath(location.folderPath)
            if (parent == null) onExit() else location = OfficeWorkspaceLocation(parent)
        }
    }
    PlatformBackHandler(enabled = true, onBack = ::back)
    LaunchedEffect(workspace, accountReady, location.folderPath, attempt) {
        if (accountReady) workspace.load(location.folderPath)
    }
    Column(modifier = modifier.fillMaxSize()) {
        val file = selected ?: location.resolveSelection(state)
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = ::back) { Text("Back") }
            Text(
                file?.name ?: "Office",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider()
        when {
            !accountReady -> Text(
                "The account is still loading. Return to Apps and refresh before opening Office.",
                modifier = Modifier.padding(NextcloudSpacing.Large),
            )
            file != null -> preview(file, Modifier.weight(1f))
            else -> {
                if (location.selectedFileId != null && !state.loading && !state.discoveringEditors && state.path == location.folderPath) {
                    Text("The selected document could not be restored. Refresh or choose another document.",
                        modifier = Modifier.padding(NextcloudSpacing.Medium))
                }
                OfficeWorkspaceBrowser(
                    state = state.takeIf { it.path == location.folderPath } ?: OfficeWorkspaceState(path = location.folderPath),
                    onOpenFolder = { location = OfficeWorkspaceLocation(it) },
                    onOpenFile = { selected = it; location = location.copy(selectedFileId = it.fileId?.takeIf { id -> id >= 0 }) },
                    onRetry = { attempt += 1 },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun OfficeWorkspaceBrowser(
    state: OfficeWorkspaceState,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember(state.path) { mutableStateOf("") }
    val files = remember(state, query) { officeWorkspaceFiles(state, query) }
    Column(modifier = modifier.fillMaxSize().padding(NextcloudSpacing.Medium)) {
        Text("Choose a document", style = MaterialTheme.typography.titleMedium)
        Text(
            if (state.path.isEmpty()) "Files" else state.path,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search this folder") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.Small),
        )
        if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onRetry, enabled = !state.loading) { Text("Refresh") }
        LazyColumn(modifier = Modifier.weight(1f)) {
            if (files.isEmpty() && !state.loading) {
                item {
                    Text(if (state.discoveringEditors) "Checking the server's document editors..."
                        else "No Office documents in this folder. Open another folder or refresh.")
                }
            }
            items(files, key = NextcloudFile::path) { file ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = file.isDirectory || state.listingNetworkConfirmed) {
                            if (file.isDirectory) onOpenFolder(file.path) else onOpenFile(file)
                        }
                        .padding(vertical = NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (file.isDirectory) NextcloudIcons.FolderOpen else NextcloudIcons.File,
                        contentDescription = if (file.isDirectory) "Folder" else "Document",
                    )
                    Text(file.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
        }
    }
}
