package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

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
 * Settings of a notes app, as its own contract describes them. A changed setting reloads the
 * workspace because a storage-location setting can change which notes the app lists.
 */
@Composable
internal fun NotesSettingsSheet(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    settings: SemanticSettings,
    filesAccess: NotesFilesAccess?,
    onSettingChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val controller = remember(services, session, settings) {
        SemanticSettingsController(
            execute = { request -> services.executeNextcloudApi(session, request) },
            settings = settings,
            onChanged = { onSettingChanged() },
        )
    }
    val folderPicker = remember(services, session, filesAccess?.userId) {
        filesAccess?.let { access ->
            RemoteFolderPickerOperations(
                identity = "${session.accountId.storageKey}|${access.userId}|notes-settings",
                listCached = { path -> services.listFilesCachedWithSource(session, access.userId, path) },
                listNetwork = { path -> services.listFilesWithSource(session, access.userId, path) },
                createDirectoryIfAbsent = { path -> services.createDirectoryIfAbsent(session, access.userId, path) },
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
