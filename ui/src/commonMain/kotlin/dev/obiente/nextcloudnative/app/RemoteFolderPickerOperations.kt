package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

class RemoteFolderPickerOperations(
    val identity: String,
    val listCached: suspend (path: String) -> NextcloudFileListing?,
    val listNetwork: suspend (path: String) -> NextcloudFileListing,
    val createDirectoryIfAbsent: suspend (path: String) -> Unit,
    val selectionAccess: suspend (path: String) -> RemoteFolderSelectionAccess = {
        RemoteFolderSelectionAccess.Allowed
    },
) {
    init {
        require(identity.isNotBlank() && identity.none(Char::isISOControl))
    }
}

sealed interface RemoteFolderSelectionAccess {
    data object Allowed : RemoteFolderSelectionAccess
    data object DirectoryCreationOnly : RemoteFolderSelectionAccess
    data class Denied(val message: String) : RemoteFolderSelectionAccess {
        init {
            require(message.isNotBlank() && message.none(Char::isISOControl))
        }
    }
}

internal suspend fun RemoteFolderPickerOperations.confirmSelectionAccess(
    path: String,
    source: NextcloudFileListingSource,
): RemoteFolderSelectionAccess {
    if (source != NextcloudFileListingSource.Network) {
        return RemoteFolderSelectionAccess.Denied("Connect to Nextcloud to confirm this destination.")
    }
    return runCatching { selectionAccess(path) }
        .rethrowRemoteFolderCancellation()
        .getOrElse { failure ->
            RemoteFolderSelectionAccess.Denied(
                failure.message ?: "Could not verify permission to upload here.",
            )
        }
}

internal suspend fun RemoteFolderPickerOperations.createAndConfirmDestination(
    destination: MissingRemoteFolderDestination,
) {
    destination.pathsToCreate.forEach { path -> createDirectoryIfAbsent(path) }
    when (val access = selectionAccess(destination.intendedPath)) {
        RemoteFolderSelectionAccess.Allowed -> Unit
        RemoteFolderSelectionAccess.DirectoryCreationOnly ->
            error("The new Nextcloud folder cannot receive files for this account.")
        is RemoteFolderSelectionAccess.Denied -> error(access.message)
    }
}

fun remoteFolderPickerOperations(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
): RemoteFolderPickerOperations {
    require(userId.isNotBlank())
    return RemoteFolderPickerOperations(
        identity = "${session.accountId.storageKey}|$userId",
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
