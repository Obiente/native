package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Account facts the notes workspace needs for Files-backed actions such as folders and sharing. */
internal data class NotesFilesAccess(
    val userId: String,
    val sharing: NextcloudFileSharingCapabilities,
) {
    init {
        require(userId.isNotBlank()) { "The Files user is unavailable." }
    }
}

/**
 * A notes app's contract-described settings with the transport their writes must use: the
 * workspace's contract-gated execute, so a settings write waits for the same verified contract
 * as every note write.
 */
internal class NotesSettingsAccess(
    val settings: SemanticSettings,
    val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
)

/**
 * Settings of a notes app, as its own contract describes them. A changed setting reloads the
 * workspace because a storage-location setting can change which notes the app lists.
 */
@Composable
internal fun NotesSettingsSheet(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    access: NotesSettingsAccess,
    filesAccess: NotesFilesAccess?,
    onSettingChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentOnSettingChanged by rememberUpdatedState(onSettingChanged)
    val controller = remember(access) {
        SemanticSettingsController(
            execute = access.execute,
            settings = access.settings,
            onChanged = { currentOnSettingChanged() },
        )
    }
    val folderPicker = remember(services, session, filesAccess?.userId) {
        filesAccess?.let { files ->
            RemoteFolderPickerOperations(
                identity = "${session.accountId.storageKey}|${files.userId}|notes-settings",
                listCached = { path -> services.listFilesCachedWithSource(session, files.userId, path) },
                listNetwork = { path -> services.listFilesWithSource(session, files.userId, path) },
                createDirectoryIfAbsent = { path -> services.createDirectoryIfAbsent(session, files.userId, path) },
            )
        }
    }
    SemanticSettingsDialog(
        title = "Notes settings",
        controller = controller,
        onDismiss = onDismiss,
        folderPicker = folderPicker,
    )
}
