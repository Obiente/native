package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class OfficeWorkspaceTest {
    @Test
    fun officeEntriesUseNativeWorkspaceRegardlessOfDashboardHref() {
        listOf("office", "richdocuments", "onlyoffice", "collabora", "nextcloud_office")
            .forEach { assertTrue(isOfficeWorkspaceAppId(it)) }
        listOf("files", "notes", "pantry").forEach { assertFalse(isOfficeWorkspaceAppId(it)) }
    }

    @Test
    fun listsDirectoriesAndEveryAdvertisedTypeWithoutFlatteningFolders() {
        val state = OfficeWorkspaceState(
            files = listOf(
                file("document.docx"), file("manual.pdf", "application/pdf"),
                file("custom.design", "application/x-design"), file("ignore.bin", "application/octet-stream"),
                file("Folder", directory = true), file("Folder/nested.docx"),
                file("../escape.docx"), file("document.docx"),
            ),
            capabilities = capabilities(setOf("application/pdf", "application/x-design")),
        )
        assertEquals(
            listOf("Folder", "custom.design", "document.docx", "manual.pdf"),
            officeWorkspaceFiles(state, "").map(NextcloudFile::path),
        )
        assertEquals(listOf("manual.pdf"), officeWorkspaceFiles(state, "MANUAL").map(NextcloudFile::path))
    }

    @Test
    fun nativePreviewTypesRemainListedWithoutAnyAdvertisedEditor() {
        val state = OfficeWorkspaceState(files = listOf(
            file("Manual.pdf", "application/pdf"), file("Readme.md", "text/markdown"),
            file("Diagram.drawio", "application/x-drawio"), file("Notes.txt", "text/plain"),
            file("Other.bin", "application/octet-stream"),
        ))
        assertEquals(listOf("Diagram.drawio", "Manual.pdf", "Notes.txt", "Readme.md"),
            officeWorkspaceFiles(state, "").map(NextcloudFile::path))
    }

    @Test
    fun cachedAndFailedListingsNeverConfirmEditing() = runBlocking {
        val workspace = OfficeWorkspace(OfficeWorkspaceOperations(
            cachedFiles = { listing(NextcloudFileListingSource.Cache) },
            files = { error("offline") },
            capabilities = { capabilities(emptySet()) },
        ))
        workspace.load("")
        assertFalse(workspace.state.value.listingNetworkConfirmed)
        assertFalse(workspace.state.value.loading)
        assertEquals(1, workspace.state.value.files.size)
        assertTrue(workspace.state.value.error != null)
    }

    @Test
    fun retryRefreshesMetadataAndConfirmsNetworkListing() = runBlocking {
        var online = false
        val workspace = OfficeWorkspace(OfficeWorkspaceOperations(
            cachedFiles = { null },
            files = { if (online) listing(NextcloudFileListingSource.Network) else error("offline") },
            capabilities = { capabilities(setOf("application/pdf")) },
        ))
        workspace.load("")
        online = true
        workspace.load("")
        assertTrue(workspace.state.value.listingNetworkConfirmed)
        assertEquals(null, workspace.state.value.error)
    }

    @Test
    fun editorDiscoveryFailureDoesNotDisableNetworkDocumentPreviews() = runBlocking {
        val workspace = OfficeWorkspace(OfficeWorkspaceOperations(
            cachedFiles = { null },
            files = { listing(NextcloudFileListingSource.Network) },
            capabilities = { error("Direct Editing is unavailable") },
        ))
        workspace.load("")
        assertTrue(workspace.state.value.listingNetworkConfirmed)
        assertEquals(listOf("a.docx"), officeWorkspaceFiles(workspace.state.value, "").map(NextcloudFile::path))
        assertEquals(NextcloudDocumentEditingCapabilities.Unavailable, workspace.state.value.capabilities)
        assertTrue(workspace.state.value.error.orEmpty().contains("document editors"))
    }

    @Test
    fun unsupportedEditorsStillAllowNetworkPreviewsButCachedFallbackDoesNot() = runBlocking {
        var source = NextcloudFileListingSource.Network
        val workspace = OfficeWorkspace(OfficeWorkspaceOperations(
            cachedFiles = { null }, files = { listing(source) },
            capabilities = { NextcloudDocumentEditingCapabilities.Unavailable },
        ))
        workspace.load("")
        assertTrue(workspace.state.value.listingNetworkConfirmed)
        source = NextcloudFileListingSource.Cache
        workspace.load("")
        assertFalse(workspace.state.value.listingNetworkConfirmed)
    }

    @Test
    fun lateFolderResponseCannotReplaceNewerFolder() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val workspace = OfficeWorkspace(OfficeWorkspaceOperations(
            cachedFiles = { null },
            files = { path ->
                if (path == "old") release.await()
                NextcloudFileListing(listOf(file("$path/document.docx")), NextcloudFileListingSource.Network)
            },
            capabilities = { capabilities(emptySet()) },
        ))
        val old = async(start = CoroutineStart.UNDISPATCHED) { workspace.load("old") }
        workspace.load("new")
        release.complete(Unit)
        old.await()
        assertEquals("new", workspace.state.value.path)
        assertEquals("new/document.docx", workspace.state.value.files.single().path)
    }

    @Test
    fun cancellationIsNotAnOfflineFailure() = runBlocking {
        val workspace = OfficeWorkspace(OfficeWorkspaceOperations(
            cachedFiles = { throw CancellationException() },
            files = { error("must not run") },
            capabilities = { error("must not run") },
        ))
        assertFailsWith<CancellationException> { workspace.load("") }
        assertEquals(null, workspace.state.value.error)
    }

    private fun listing(source: NextcloudFileListingSource) = NextcloudFileListing(listOf(file("a.docx")), source)
    private fun file(
        path: String,
        mime: String = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        directory: Boolean = false,
    ) = NextcloudFile(
        path, path.substringAfterLast('/'), directory, mime, 1, null,
        fileId = 1, hasPreview = false, etag = "v1",
    )

    private fun capabilities(mimes: Set<String>) = NextcloudDocumentEditingCapabilities(
        editors = mapOf("suite" to NextcloudDocumentEditorCapability("suite", "Suite", mimes, emptySet(), true)),
        creators = emptyMap(), supportsFileId = true,
    )
}
