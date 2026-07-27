package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FileActionsTest {
    @Test
    fun foldersOnlyExposeRealReadOnlyActions() {
        assertEquals(
            listOf(FileAction.Open, FileAction.Details),
            availableFileActions(file(name = "Documents", isDirectory = true)),
        )
    }

    @Test
    fun previewableTextFilesExposePreviewDetailsAndEditor() {
        assertEquals(
            listOf(FileAction.Preview, FileAction.Details, FileAction.EditText),
            availableFileActions(
                file(
                    name = "notes.md",
                    mimeType = "text/markdown",
                    fileId = 42L,
                    hasPreview = true,
                ),
            ),
        )
    }

    @Test
    fun unknownBinaryFilesDoNotExposeFakeOpenOrEditActions() {
        val binary = file(name = "archive.bin", mimeType = "application/octet-stream")
        assertEquals(listOf(FileAction.Details), availableFileActions(binary))
        assertEquals("Show details for archive.bin", primaryFileActionLabel(binary))
    }

    @Test
    fun previewlessRawFilesExposeTheSameViewerActionAsActivation() {
        val raw = file(
            name = "capture.raf",
            mimeType = "application/octet-stream",
            fileId = 42L,
        )

        assertEquals(listOf(FileAction.Preview, FileAction.Details), availableFileActions(raw))
        assertEquals("Preview capture.raf", primaryFileActionLabel(raw))
    }

    private fun file(
        name: String,
        isDirectory: Boolean = false,
        mimeType: String? = null,
        fileId: Long? = null,
        hasPreview: Boolean = false,
    ) = NextcloudFile(
        path = name,
        name = name,
        isDirectory = isDirectory,
        mimeType = mimeType,
        size = null,
        lastModified = null,
        fileId = fileId,
        hasPreview = hasPreview,
    )
}
