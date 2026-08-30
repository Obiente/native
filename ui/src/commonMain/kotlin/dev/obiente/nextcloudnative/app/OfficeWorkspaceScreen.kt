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
    val workspace = remember(services, session, userId) {
        OfficeWorkspace(officeWorkspaceOperations(services, session, userId))
    }
    val state by workspace.state.collectAsState()
    var path by remember(workspace) { mutableStateOf("") }
    var attempt by remember(workspace) { mutableIntStateOf(0) }
    var selected by remember(workspace) { mutableStateOf<NextcloudFile?>(null) }
    fun back() {
        if (selected != null) {
            selected = null
            attempt += 1
        } else {
            val parent = remoteFolderParentPath(path)
            if (parent == null) onExit() else path = parent
        }
    }
    PlatformBackHandler(enabled = true, onBack = ::back)
    LaunchedEffect(workspace, path, attempt) {
        if (userId.isNotBlank()) workspace.load(path)
    }
    Column(modifier = modifier.fillMaxSize()) {
        val file = selected
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
            userId.isBlank() -> Text(
                "The account is still loading. Return to Apps and refresh before opening Office.",
                modifier = Modifier.padding(NextcloudSpacing.Large),
            )
            file != null -> NextcloudDocumentPreview(
                file = file,
                session = session,
                userId = userId,
                services = services,
                modifier = Modifier.weight(1f),
            )
            else -> OfficeWorkspaceBrowser(
                state = state,
                onOpenFolder = { path = it },
                onOpenFile = { selected = it },
                onRetry = { attempt += 1 },
                modifier = Modifier.weight(1f),
            )
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
                item { Text("No Office documents in this folder. Open another folder or refresh.") }
            }
            items(files, key = NextcloudFile::path) { file ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = file.isDirectory || state.networkConfirmed) {
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
