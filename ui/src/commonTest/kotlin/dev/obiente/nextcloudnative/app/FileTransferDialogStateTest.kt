package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileTransferDialogStateTest {
    private val file = NextcloudFile(
        path = "Documents/report.txt", name = "report.txt", isDirectory = false,
        mimeType = "text/plain", size = 10, lastModified = null, fileId = 1L, hasPreview = false, etag = "v1",
    )
    private val identity = FileTransferDialogIdentity("synthetic-account", file.path, file.etag, moving = false)
    private fun initial() = FileTransferDialogState(identity, "Documents", file.name)

    @Test
    fun `unchanged destination opens with neutral guidance and no enabled confirmation`() {
        val state = initial()
        val presentation = state.presentation(file)
        assertNull(presentation.error)
        assertEquals("Choose another folder or change the name.", presentation.guidance)
        assertFalse(presentation.canConfirm)
        assertNull(state.confirmedDestination(file))
    }

    @Test
    fun `cancelling folder selection preserves edited name and prior destination`() {
        val state = initial().copy(name = "report-copy.txt")
        val selecting = state.chooseFolder()
        assertTrue(selecting.choosingFolder)
        assertNull(selecting.confirmedDestination(file))
        assertEquals(state, selecting.cancelFolderSelection())
    }

    @Test
    fun `selected folder supplies an immutable validated destination`() {
        val selected = initial().chooseFolder().selectFolder(identity, "Archive")
        assertFalse(selected.choosingFolder)
        assertTrue(selected.presentation(file).canConfirm)
        val confirmation = selected.confirmedDestination(file)
        assertEquals(FileTransferDestination("Archive", "report.txt"), confirmation)
        val edited = selected.copy(name = "later-edit.txt")
        assertEquals("later-edit.txt", edited.name)
        assertEquals("report.txt", confirmation?.name)
    }

    @Test
    fun `invalid names remain visible and cannot reach confirmation`() {
        for (name in listOf("", ".", "..", "../report.txt", "sub/report.txt", " spaced.txt")) {
            val state = initial().copy(directory = "Archive", name = name)
            assertTrue(state.presentation(file).error != null)
            assertNull(state.confirmedDestination(file))
        }
        assertTrue(initial().copy(name = "report-copy.txt").presentation(file).canConfirm)
    }

    @Test
    fun `stale account target generation or dismissed picker cannot change selection`() {
        val state = initial().chooseFolder()
        for (stale in listOf(
            identity.copy(accountIdentity = "other-account"), identity.copy(sourcePath = "Other/report.txt"),
            identity.copy(sourceEtag = "older"), identity.copy(moving = true),
        )) assertSame(state, state.selectFolder(stale, "Archive"))
        val cancelled = state.cancelFolderSelection()
        assertSame(cancelled, cancelled.selectFolder(identity, "Archive"))
        assertNull(state.copy(directory = "Archive", choosingFolder = false).confirmedDestination(file.copy(etag = "v2")))
    }
}
