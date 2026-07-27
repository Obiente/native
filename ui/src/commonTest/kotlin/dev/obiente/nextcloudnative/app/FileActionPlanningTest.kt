package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileActionPlanningTest {
    @Test
    fun folderPlanHasNavigationAndConflictSafeMutationsButNoDownload() {
        val plan = planFileActions(file(name = "Documents", isDirectory = true, etag = "folder-etag"))

        assertEquals(listOf(FileMenuAction.Open), plan.primary.map(PlannedFileAction::action))
        assertNull(plan.actions.find { it.action == FileMenuAction.Download })
        assertTrue(plan.action(FileMenuAction.Rename).enabled)
        assertFalse(plan.action(FileMenuAction.Share).enabled)
        assertFalse(plan.action(FileMenuAction.MakeAvailableOffline).enabled)
        assertTrue(plan.action(FileMenuAction.Delete).requiresConfirmation)
        assertEquals(FileActionTone.Destructive, plan.action(FileMenuAction.Delete).tone)

        val offlinePlan = planFileActions(
            file(name = "Documents", isDirectory = true, etag = null),
            FileActionSupport(offlineStorage = true),
        )
        assertTrue(offlinePlan.action(FileMenuAction.MakeAvailableOffline).enabled)
        assertEquals(
            "Make folder available offline",
            offlinePlan.action(FileMenuAction.MakeAvailableOffline).label,
        )
    }

    @Test
    fun editablePreviewableTextGetsNativePreviewEditAndDownload() {
        val file = file(
            name = "notes.md",
            path = "Documents/notes.md",
            mimeType = "text/markdown",
            size = 1024,
            etag = "v1",
        )

        val plan = planFileActions(file)
        val handoff = planFileContentHandoffs(file)

        assertEquals(listOf(FileMenuAction.Preview, FileMenuAction.EditText), plan.primary.map(PlannedFileAction::action))
        assertTrue(plan.action(FileMenuAction.Download).enabled)
        assertIs<FilePreviewHandoff.NativeText>(handoff.preview)
        assertIs<FileEditHandoff.NativeText>(handoff.edit)
        assertEquals("v1", handoff.download?.expectedEtag)
    }

    @Test
    fun missingEtagDisablesMutationsAndRemovesEditHandoff() {
        val file = file(name = "notes.txt", mimeType = "text/plain", size = 10, etag = null)
        val plan = planFileActions(
            file,
            FileActionSupport(
                platformViewer = true,
                externalSharing = true,
                maximumExternalFileBytes = MAX_EXTERNAL_FILE_HANDOFF_BYTES,
            ),
        )

        assertFalse(plan.action(FileMenuAction.EditText).enabled)
        assertFalse(plan.action(FileMenuAction.Move).enabled)
        assertFalse(plan.action(FileMenuAction.Delete).enabled)
        assertFalse(plan.action(FileMenuAction.OpenWith).enabled)
        assertFalse(plan.action(FileMenuAction.SendCopy).enabled)
        assertNull(planFileContentHandoffs(file).edit)
    }

    @Test
    fun oversizedFileCannotUseBoundedDownloadOrNativeEditing() {
        val file = file(
            name = "huge.txt",
            mimeType = "text/plain",
            size = DEFAULT_FILE_DOWNLOAD_LIMIT_BYTES + 1,
            etag = "v1",
        )
        val plan = planFileActions(file)
        val handoff = planFileContentHandoffs(file)

        assertFalse(plan.action(FileMenuAction.Download).enabled)
        assertFalse(plan.action(FileMenuAction.EditText).enabled)
        assertNull(handoff.download)
        assertNull(handoff.preview)
        assertNull(handoff.edit)
    }

    @Test
    fun shareOfflineAndPlatformHandoffsAreCapabilityGated() {
        val file = file(name = "design.bin", mimeType = "application/octet-stream", size = 2048, etag = "v3")
        val unsupported = planFileActions(file)
        assertFalse(unsupported.action(FileMenuAction.Share).enabled)
        assertFalse(unsupported.action(FileMenuAction.MakeAvailableOffline).enabled)
        assertNull(planFileContentHandoffs(file).preview)

        val support = FileActionSupport(sharing = true, offlineStorage = true, platformViewer = true, platformEditor = true)
        val supported = planFileActions(file, support)
        val handoff = planFileContentHandoffs(file, support)
        assertTrue(supported.action(FileMenuAction.Share).enabled)
        assertTrue(supported.action(FileMenuAction.MakeAvailableOffline).enabled)
        assertIs<FilePreviewHandoff.PlatformViewer>(handoff.preview)
        assertIs<FileEditHandoff.PlatformEditor>(handoff.edit)
    }

    @Test
    fun nativeSharingIsIndependentFromThePlatformFileHandoffSizeLimit() {
        val file = file(
            name = "large-video.mp4",
            mimeType = "video/mp4",
            size = MAX_EXTERNAL_FILE_HANDOFF_BYTES + 1,
            etag = "v4",
        )
        val support = FileActionSupport(
            sharing = true,
            externalSharing = true,
            platformViewer = true,
            maximumExternalFileBytes = MAX_EXTERNAL_FILE_HANDOFF_BYTES,
        )

        val plan = planFilesScreenActions(file, support)
        assertTrue(plan.action(FileMenuAction.Share).enabled)
        assertFalse(plan.action(FileMenuAction.SendCopy).enabled)
        assertFalse(plan.action(FileMenuAction.OpenWith).enabled)
        assertNull(plan.action(FileMenuAction.Share).disabledReason)
    }

    @Test
    fun nativeSharingSupportsFoldersAndHonorsExplicitDavSharePermission() {
        val shareableFolder = file(
            name = "Projects",
            isDirectory = true,
            permissions = "RDNVW",
        )
        val support = FileActionSupport(sharing = true)

        assertTrue(planFilesScreenActions(shareableFolder, support).action(FileMenuAction.Share).enabled)
        val denied = planFilesScreenActions(
            shareableFolder.copy(permissions = "DNVW"),
            support,
        ).action(FileMenuAction.Share)
        assertFalse(denied.enabled)
        assertTrue(denied.disabledReason.orEmpty().contains("permission"))
    }

    @Test
    fun rasterPreviewUsesFileIdAndOfflineStateChangesAction() {
        val file = file(
            name = "photo.jpg",
            mimeType = "image/jpeg",
            size = 4096,
            etag = "v4",
            fileId = 77,
            hasPreview = true,
        )
        val support = FileActionSupport(offlineStorage = true)
        val handoff = planFileContentHandoffs(file, support)

        assertEquals(77, assertIs<FilePreviewHandoff.ServerRaster>(handoff.preview).fileId)
        assertEquals(
            FileMenuAction.RemoveOffline,
            planFileActions(file, support, FileOfflineState.Stored).actions
                .first { it.action == FileMenuAction.RemoveOffline }.action,
        )
        assertTrue(planFilesScreenActions(file, support).action(FileMenuAction.VersionHistory).enabled)
    }

    @Test
    fun previewlessRawFileKeepsPreviewInTheLongPressPlan() {
        val raw = file(
            name = "capture.raf",
            mimeType = "application/octet-stream",
            fileId = 77,
            etag = "raw-v1",
        )

        assertEquals(
            FileMenuAction.Preview,
            planFileActions(raw).primary.first().action,
        )
    }

    @Test
    fun versionHistoryIsReachableOnlyForStableReadableFileRecords() {
        val readable = file(
            name = "draft.md",
            fileId = 42,
            etag = "v1",
            permissions = "RGDNVW",
        )
        val restricted = readable.copy(originalAccessAllowed = false)

        assertTrue(planFilesScreenActions(readable).action(FileMenuAction.VersionHistory).enabled)
        assertFalse(planFilesScreenActions(restricted).action(FileMenuAction.VersionHistory).enabled)
        assertNull(
            planFilesScreenActions(readable.copy(fileId = null)).actions
                .firstOrNull { it.action == FileMenuAction.VersionHistory },
        )
    }

    @Test
    fun officeHandoffRequiresAdvertisedEditorAndDavWritePermission() {
        val office = NextcloudDocumentEditingCapabilities(
            editors = mapOf(
                "richdocuments" to NextcloudDocumentEditorCapability(
                    id = "richdocuments",
                    displayName = "Nextcloud Office",
                    mimeTypes = setOf(DOCX_MIME),
                    optionalMimeTypes = emptySet(),
                    secure = true,
                ),
            ),
            creators = emptyMap(),
            supportsFileId = true,
        )
        val writable = file(
            name = "report.docx",
            path = "Documents/report.docx",
            mimeType = DOCX_MIME,
            etag = "v5",
            fileId = 91,
            hasPreview = true,
            permissions = "RGDNVW",
        )
        val readOnly = writable.copy(permissions = "RG")

        assertIs<FileEditHandoff.Office>(
            planFileContentHandoffs(writable, FileActionSupport(documentEditing = office)).edit,
        )
        assertNull(
            planFileContentHandoffs(readOnly, FileActionSupport(documentEditing = office)).edit,
        )
        assertTrue(
            planFileActions(writable, FileActionSupport(documentEditing = office))
                .action(FileMenuAction.EditWith).enabled,
        )
        assertFalse(
            planFileActions(readOnly, FileActionSupport(documentEditing = office))
                .action(FileMenuAction.EditWith).enabled,
        )
    }

    @Test
    fun filesScreenEnablesSafeTransferWorkflowsAndKeepsDownloadsVisible() {
        val plan = planFilesScreenActions(file(name = "report.pdf", mimeType = "application/pdf", etag = "v1"))

        assertFalse(plan.action(FileMenuAction.Download).enabled)
        assertTrue(plan.action(FileMenuAction.Download).disabledReason!!.contains("save location"))
        assertTrue(plan.action(FileMenuAction.Move).enabled)
        assertTrue(plan.action(FileMenuAction.Copy).enabled)
        assertTrue(plan.action(FileMenuAction.Rename).enabled)
        assertTrue(plan.action(FileMenuAction.Delete).enabled)
    }

    @Test
    fun filesScreenEnablesDurableOfflineActionsWhenThePlatformSupportsThem() {
        val file = file(name = "vault.md", mimeType = "text/markdown", etag = "v1")
        val support = FileActionSupport(offlineStorage = true)

        assertTrue(planFilesScreenActions(file, support).action(FileMenuAction.MakeAvailableOffline).enabled)
        assertTrue(
            planFilesScreenActions(file, support, FileOfflineState.Stored)
                .action(FileMenuAction.RemoveOffline).enabled,
        )
        assertFalse(
            planFilesScreenActions(file, support, FileOfflineState.Pending)
                .action(FileMenuAction.MakeAvailableOffline).enabled,
        )
    }

    @Test
    fun transferValidationRejectsSelfTargetsAndUnsafePaths() {
        val file = file(name = "report.pdf", path = "Documents/report.pdf", etag = "v1")
        val folder = file(name = "Archive", path = "Documents/Archive", isDirectory = true, etag = "v1")

        assertEquals(
            "Choose a different destination.",
            fileTransferValidationError(file, "Documents", "report.pdf"),
        )
        assertEquals(
            "A folder cannot be placed inside itself.",
            fileTransferValidationError(folder, "Documents/Archive/Inside", "Archive"),
        )
        assertEquals(
            "The destination folder contains an invalid segment.",
            fileTransferValidationError(file, "Documents/../Private", "report.pdf"),
        )
        assertNull(fileTransferValidationError(file, "Documents/Archive", "report-copy.pdf"))
    }

    @Test
    fun renameValidationRejectsUnchangedAndUnsafeNames() {
        val file = file(name = "notes.txt", etag = "v1")

        assertEquals("Enter a file name.", fileRenameValidationError(file, ""))
        assertEquals("Enter a different file name.", fileRenameValidationError(file, "notes.txt"))
        assertEquals("File names cannot contain slashes.", fileRenameValidationError(file, "folder/notes.txt"))
        assertNull(fileRenameValidationError(file, "ideas.txt"))
    }

    private fun FileLongPressActionPlan.action(action: FileMenuAction): PlannedFileAction =
        actions.first { it.action == action }

    private fun file(
        name: String,
        path: String = name,
        isDirectory: Boolean = false,
        mimeType: String? = null,
        size: Long? = null,
        etag: String? = null,
        fileId: Long? = null,
        hasPreview: Boolean = false,
        permissions: String? = null,
    ) = NextcloudFile(
        path = path,
        name = name,
        isDirectory = isDirectory,
        mimeType = mimeType,
        size = size,
        lastModified = null,
        fileId = fileId,
        hasPreview = hasPreview,
        etag = etag,
        permissions = permissions,
    )

    private companion object {
        const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
