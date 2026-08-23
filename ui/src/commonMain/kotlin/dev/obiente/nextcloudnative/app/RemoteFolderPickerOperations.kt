package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

class RemoteFolderPickerOperations(
    val identity: String,
    val listCached: suspend (path: String) -> NextcloudFileListing?,
    val listNetwork: suspend (path: String) -> NextcloudFileListing,
    val createDirectoryIfAbsent: suspend (path: String) -> Unit,
) {
    init {
        require(identity.isNotBlank() && identity.none(Char::isISOControl))
    }
}

fun remoteFolderPickerOperations(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
): RemoteFolderPickerOperations {
    require(userId.isNotBlank())
    return RemoteFolderPickerOperations(
        identity = "${session.serverUrl}|${session.loginName}|$userId",
        listCached = { path -> services.listFilesCachedWithSource(session, userId, path) },
        listNetwork = { path -> services.listFilesWithSource(session, userId, path) },
        createDirectoryIfAbsent = { path -> services.createDirectoryIfAbsent(session, userId, path) },
    )
}

@Composable
fun RemoteFolderPickerDialog(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    initialPath: String,
    selectionError: String? = null,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    val operations = remember(services, session, userId) {
        remoteFolderPickerOperations(services, session, userId)
    }
    RemoteFolderPickerDialog(
        operations = operations,
        initialPath = initialPath,
        selectionError = selectionError,
        onDismiss = onDismiss,
        onSelected = onSelected,
    )
}
