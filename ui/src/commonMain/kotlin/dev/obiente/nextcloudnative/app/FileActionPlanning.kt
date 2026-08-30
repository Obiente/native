package dev.obiente.nextcloudnative.app

enum class FileMenuAction {
    Open,
    Preview,
    OpenWith,
    EditText,
    EditWith,
    AddFavorite,
    RemoveFavorite,
    Details,
    VersionHistory,
    Download,
    Rename,
    Move,
    Copy,
    Share,
    SendCopy,
    MakeAvailableOffline,
    RemoveOffline,
    Delete,
}

enum class FileActionPlacement { Primary, Overflow }

enum class FileActionTone { Normal, Destructive }

enum class FileOfflineState { NotStored, Stored, Pending }

data class FileActionSupport(
    val webDavMutations: Boolean = true,
    val sharing: Boolean = false,
    val externalSharing: Boolean = false,
    val offlineStorage: Boolean = false,
    val platformViewer: Boolean = false,
    val platformEditor: Boolean = false,
    val maximumInMemoryExternalFileBytes: Long? = null,
    val seekableExternalFileStreaming: Boolean = false,
    val documentEditing: NextcloudDocumentEditingCapabilities? = null,
    val discoverDocumentEditing: Boolean = false,
)

data class PlannedFileAction(
    val action: FileMenuAction,
    val label: String,
    val placement: FileActionPlacement,
    val enabled: Boolean = true,
    val disabledReason: String? = null,
    val requiresConfirmation: Boolean = false,
    val tone: FileActionTone = FileActionTone.Normal,
)

data class FileLongPressActionPlan(val actions: List<PlannedFileAction>) {
    val primary: List<PlannedFileAction> get() = actions.filter { it.placement == FileActionPlacement.Primary }
    val overflow: List<PlannedFileAction> get() = actions.filter { it.placement == FileActionPlacement.Overflow }
}

fun planFileActions(
    file: NextcloudFile,
    support: FileActionSupport = FileActionSupport(),
    offlineState: FileOfflineState = FileOfflineState.NotStored,
): FileLongPressActionPlan {
    val descriptor = describeDocument(file)
    val previewable = !file.isDirectory && (
        descriptor.method != DocumentPreviewMethod.Unsupported ||
            file.canOpenInMediaViewer() || support.platformViewer
        )
    val hasVersion = !file.etag.isNullOrBlank()
    val canStreamExternalFile = support.seekableExternalFileStreaming &&
        file.size?.let { it >= 0L } == true &&
        hasVersion
    val mutationReason = when {
        !support.webDavMutations -> "This server does not expose safe file changes."
        !hasVersion -> "Refresh the folder before changing this item."
        else -> null
    }
    val downloadReason = when {
        file.isDirectory -> "Folder downloads are not supported yet."
        else -> null
    }
    val inMemoryEditReason = when {
        file.size != null && file.size > MAX_IN_MEMORY_FILE_CONTENT_BYTES ->
            "Use another app for this file; it is too large for an in-memory editor."
        else -> null
    }
    val externalHandoffReason = when {
        file.isDirectory -> "Folders cannot be sent to another app as a single file."
        !file.originalAccessAllowed -> "This file allows preview only."
        !hasVersion -> "Refresh the folder before sending this file to another app."
        canStreamExternalFile -> null
        else -> downloadReason
    }
    val offlineReason = when {
        !file.isDirectory && !hasVersion -> "Refresh the folder before making this file available offline."
        else -> null
    }
    val textEditReason = when {
        !file.isEditableText() -> "This file type needs another editor."
        file.size != null && file.size > MAX_EDITABLE_TEXT_BYTES ->
            "Text editing is limited to ${MAX_EDITABLE_TEXT_BYTES / (1024 * 1024)} MiB."
        !hasVersion -> "Refresh the folder before editing this file."
        else -> null
    }
    val officeEditPlan = support.documentEditing?.let { planOfficeEditSession(file, it) }

    return FileLongPressActionPlan(buildList {
        if (file.isDirectory) {
            add(PlannedFileAction(FileMenuAction.Open, "Open", FileActionPlacement.Primary))
        } else if (previewable) {
            add(PlannedFileAction(FileMenuAction.Preview, "Preview", FileActionPlacement.Primary))
        }
        if (!file.isDirectory && file.isEditableText()) {
            add(action(FileMenuAction.EditText, "Edit text", FileActionPlacement.Primary, textEditReason))
        }
        if (!file.isDirectory && support.platformViewer) {
            add(action(FileMenuAction.OpenWith, "Open with...", FileActionPlacement.Overflow, externalHandoffReason))
        }
        if (!file.isDirectory && officeEditPlan != null) {
            val reason = (officeEditPlan as? OfficeEditSessionPlan.Blocked)?.reason?.userMessage()
            add(action(FileMenuAction.EditWith, "Edit in Office", FileActionPlacement.Overflow, reason))
        } else if (!file.isDirectory && descriptor.officeEditable && support.discoverDocumentEditing) {
            add(
                PlannedFileAction(
                    FileMenuAction.EditWith,
                    "Choose Office editor...",
                    FileActionPlacement.Overflow,
                ),
            )
        } else if (!file.isDirectory && support.platformEditor) {
            val reason = inMemoryEditReason ?: if (!hasVersion) "Refresh the folder before editing this file." else null
            add(action(FileMenuAction.EditWith, "Edit with...", FileActionPlacement.Overflow, reason))
        }
        add(
            action(
                if (file.favorite) FileMenuAction.RemoveFavorite else FileMenuAction.AddFavorite,
                if (file.favorite) "Remove from favorites" else "Add to favorites",
                FileActionPlacement.Overflow,
                mutationReason,
            ),
        )
        add(PlannedFileAction(FileMenuAction.Details, "Details", FileActionPlacement.Overflow))
        if (!file.isDirectory && file.fileId != null) {
            add(
                action(
                    FileMenuAction.VersionHistory,
                    "Version history",
                    FileActionPlacement.Overflow,
                    if (file.originalAccessAllowed) null else "Version history is restricted for this file.",
                ),
            )
        }
        if (!file.isDirectory) {
            add(action(FileMenuAction.Download, "Download", FileActionPlacement.Overflow, downloadReason))
        }
        add(action(FileMenuAction.Rename, "Rename", FileActionPlacement.Overflow, mutationReason))
        add(action(FileMenuAction.Move, "Move", FileActionPlacement.Overflow, mutationReason))
        add(action(FileMenuAction.Copy, "Copy", FileActionPlacement.Overflow, mutationReason))
        add(
            action(
                FileMenuAction.Share,
                "Share",
                FileActionPlacement.Overflow,
                when {
                    !support.sharing -> "File sharing is unavailable for this account."
                    file.permissions != null && 'R' !in file.permissions ->
                        "You do not have permission to share this item."
                    else -> null
                },
            ),
        )
        if (!file.isDirectory && support.externalSharing) {
            add(
                action(
                    FileMenuAction.SendCopy,
                    "Send a copy...",
                    FileActionPlacement.Overflow,
                    externalHandoffReason,
                ),
            )
        }
        val offlineAction = when (offlineState) {
            FileOfflineState.Stored -> FileMenuAction.RemoveOffline
            FileOfflineState.NotStored, FileOfflineState.Pending -> FileMenuAction.MakeAvailableOffline
        }
        val label = when {
            file.isDirectory && offlineAction == FileMenuAction.RemoveOffline -> "Remove offline folder"
            file.isDirectory -> "Make folder available offline"
            offlineAction == FileMenuAction.RemoveOffline -> "Remove offline copy"
            else -> "Make available offline"
        }
        val reason = when {
            !support.offlineStorage -> "Offline storage is not configured on this device."
            offlineState == FileOfflineState.Pending -> "The offline copy is already being updated."
            offlineReason != null -> offlineReason
            else -> null
        }
        add(action(offlineAction, label, FileActionPlacement.Overflow, reason))
        add(
            action(
                FileMenuAction.Delete,
                "Delete",
                FileActionPlacement.Overflow,
                mutationReason,
                requiresConfirmation = true,
                tone = FileActionTone.Destructive,
            ),
        )
    })
}

/** Actions currently backed by a complete Files-screen workflow, not just a transport primitive. */
fun planFilesScreenActions(
    file: NextcloudFile,
    support: FileActionSupport = FileActionSupport(),
    offlineState: FileOfflineState = FileOfflineState.NotStored,
): FileLongPressActionPlan {
    val plan = planFileActions(file, support, offlineState)
    return FileLongPressActionPlan(
        plan.actions.map { action ->
            if (!action.enabled || action.action in filesScreenImplementedActions) {
                action
            } else {
                action.copy(
                    enabled = false,
                    disabledReason = when (action.action) {
                        FileMenuAction.Download -> "Choosing a local save location is not available yet."
                        FileMenuAction.Move, FileMenuAction.Copy -> "The destination picker is not available yet."
                        else -> "This action is not available yet."
                    },
                )
            }
        },
    )
}

fun fileRenameValidationError(file: NextcloudFile, value: String): String? = when {
    value.isBlank() -> "Enter a file name."
    value != value.trim() -> "File names cannot begin or end with spaces."
    value == "." || value == ".." -> "Enter a valid file name."
    value.any { it == '/' || it == '\\' || it == '\u0000' } -> "File names cannot contain slashes."
    value == file.name -> "Enter a different file name."
    else -> null
}

fun fileTransferValidationError(
    file: NextcloudFile,
    destinationDirectory: String,
    destinationName: String,
): String? {
    val directory = destinationDirectory.trim()
    val name = destinationName.trim()
    val destination = if (directory.isEmpty()) name else "$directory/$name"
    return when {
        destinationDirectory != directory -> "Folder paths cannot begin or end with spaces."
        destinationName != name -> "File names cannot begin or end with spaces."
        directory.startsWith('/') || directory.endsWith('/') -> "Use a path relative to your Nextcloud root."
        directory.split('/').any { it == "." || it == ".." || it.isEmpty() } && directory.isNotEmpty() ->
            "The destination folder contains an invalid segment."
        directory.any { it == '\\' || it == '\u0000' } -> "The destination folder contains invalid characters."
        name.isEmpty() || name == "." || name == ".." -> "Enter a valid destination name."
        name.any { it == '/' || it == '\\' || it == '\u0000' } -> "Names cannot contain slashes."
        destination == file.path -> "Choose a different destination."
        file.isDirectory && destination.startsWith("${file.path}/") -> "A folder cannot be placed inside itself."
        else -> null
    }
}

sealed interface FilePreviewHandoff {
    val path: String
    val mimeType: String?

    data class NativeText(
        override val path: String,
        override val mimeType: String?,
        val maxBytes: Long,
        val etag: String?,
    ) : FilePreviewHandoff

    data class ServerRaster(
        override val path: String,
        override val mimeType: String?,
        val fileId: Long,
        val width: Int,
        val height: Int,
        val etag: String?,
    ) : FilePreviewHandoff

    data class PlatformViewer(
        override val path: String,
        override val mimeType: String?,
        val displayName: String,
        val maxBytes: Long,
        val etag: String?,
    ) : FilePreviewHandoff
}

sealed interface FileEditHandoff {
    val path: String
    val mimeType: String?
    val expectedEtag: String

    data class NativeText(
        override val path: String,
        override val mimeType: String?,
        override val expectedEtag: String,
        val maxBytes: Long,
    ) : FileEditHandoff

    data class PlatformEditor(
        override val path: String,
        override val mimeType: String?,
        override val expectedEtag: String,
        val displayName: String,
        val maxBytes: Long,
    ) : FileEditHandoff

    data class Office(
        override val path: String,
        override val mimeType: String?,
        override val expectedEtag: String,
        val request: NextcloudDocumentEditSessionRequest,
    ) : FileEditHandoff
}

data class FileDownloadHandoff(
    val path: String,
    val displayName: String,
    val mimeType: String?,
    val expectedEtag: String?,
    val maxBytes: Long,
)

data class FileContentHandoffPlan(
    val preview: FilePreviewHandoff?,
    val edit: FileEditHandoff?,
    val download: FileDownloadHandoff?,
)

fun planFileContentHandoffs(
    file: NextcloudFile,
    support: FileActionSupport = FileActionSupport(),
): FileContentHandoffPlan {
    if (file.isDirectory) return FileContentHandoffPlan(null, null, null)
    val withinInMemoryLimit = file.size == null || file.size <= MAX_IN_MEMORY_FILE_CONTENT_BYTES
    val descriptor = describeDocument(file)
    val preview = when {
        descriptor.method == DocumentPreviewMethod.NativeText &&
            (file.size == null || file.size <= DEFAULT_DOCUMENT_TEXT_PREVIEW_LIMIT_BYTES) ->
            FilePreviewHandoff.NativeText(
                file.path,
                descriptor.mimeType,
                DEFAULT_DOCUMENT_TEXT_PREVIEW_LIMIT_BYTES,
                file.etag,
            )
        file.hasPreview && file.fileId != null -> FilePreviewHandoff.ServerRaster(
            file.path,
            descriptor.mimeType,
            file.fileId,
            DEFAULT_DOCUMENT_PREVIEW_WIDTH,
            DEFAULT_DOCUMENT_PREVIEW_HEIGHT,
            file.etag,
        )
        support.platformViewer && withinInMemoryLimit -> FilePreviewHandoff.PlatformViewer(
            file.path,
            descriptor.mimeType,
            file.name,
            MAX_IN_MEMORY_FILE_CONTENT_BYTES,
            file.etag,
        )
        else -> null
    }
    val currentEtag = file.etag?.takeIf(String::isNotBlank)
    val officeEditRequest = support.documentEditing
        ?.let { planOfficeEditSession(file, it) }
        ?.let { it as? OfficeEditSessionPlan.Ready }
        ?.request
    val edit = when {
        currentEtag == null -> null
        file.isEditableText() && (file.size == null || file.size <= MAX_EDITABLE_TEXT_BYTES) ->
            FileEditHandoff.NativeText(file.path, descriptor.mimeType, currentEtag, MAX_EDITABLE_TEXT_BYTES)
        officeEditRequest != null -> FileEditHandoff.Office(
            path = file.path,
            mimeType = descriptor.mimeType,
            expectedEtag = currentEtag,
            request = officeEditRequest,
        )
        support.platformEditor && withinInMemoryLimit -> FileEditHandoff.PlatformEditor(
            file.path,
            descriptor.mimeType,
            currentEtag,
            file.name,
            MAX_IN_MEMORY_FILE_CONTENT_BYTES,
        )
        else -> null
    }
    val download = if (withinInMemoryLimit) {
        FileDownloadHandoff(file.path, file.name, descriptor.mimeType, currentEtag, MAX_IN_MEMORY_FILE_CONTENT_BYTES)
    } else {
        null
    }
    return FileContentHandoffPlan(preview, edit, download)
}

private fun action(
    action: FileMenuAction,
    label: String,
    placement: FileActionPlacement,
    disabledReason: String?,
    requiresConfirmation: Boolean = false,
    tone: FileActionTone = FileActionTone.Normal,
): PlannedFileAction = PlannedFileAction(
    action = action,
    label = label,
    placement = placement,
    enabled = disabledReason == null,
    disabledReason = disabledReason,
    requiresConfirmation = requiresConfirmation,
    tone = tone,
)

private val filesScreenImplementedActions = setOf(
    FileMenuAction.Open,
    FileMenuAction.Preview,
    FileMenuAction.OpenWith,
    FileMenuAction.EditText,
    FileMenuAction.EditWith,
    FileMenuAction.AddFavorite,
    FileMenuAction.RemoveFavorite,
    FileMenuAction.Details,
    FileMenuAction.VersionHistory,
    FileMenuAction.Rename,
    FileMenuAction.Move,
    FileMenuAction.Copy,
    FileMenuAction.Share,
    FileMenuAction.SendCopy,
    FileMenuAction.MakeAvailableOffline,
    FileMenuAction.RemoveOffline,
    FileMenuAction.Delete,
)

fun FileOfflineAvailability.toFileActionOfflineState(): FileOfflineState = when (this) {
    FileOfflineAvailability.Available -> FileOfflineState.Stored
    FileOfflineAvailability.Queued,
    FileOfflineAvailability.Downloading,
    FileOfflineAvailability.Removing,
    FileOfflineAvailability.WaitingForNetwork,
    -> FileOfflineState.Pending
    FileOfflineAvailability.OnlineOnly,
    FileOfflineAvailability.Failed,
    FileOfflineAvailability.NeedsAttention,
    -> FileOfflineState.NotStored
}

fun FileOfflineAvailability.readableStatus(): String? = when (this) {
    FileOfflineAvailability.OnlineOnly -> null
    FileOfflineAvailability.Queued -> "Queued for offline use"
    FileOfflineAvailability.Downloading -> "Downloading for offline use"
    FileOfflineAvailability.Available -> "Available offline"
    FileOfflineAvailability.Removing -> "Removing offline copy"
    FileOfflineAvailability.WaitingForNetwork -> "Waiting for network"
    FileOfflineAvailability.Failed -> "Offline download failed"
    FileOfflineAvailability.NeedsAttention -> "Offline copy needs attention"
}
