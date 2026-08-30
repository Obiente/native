package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfficeWorkspaceDiscoveryTest {
    @Test
    fun stalledEditorDiscoveryDoesNotDelayConfirmedFolderOrRestoredPreview() = runBlocking {
        val releaseEditors = CompletableDeferred<Unit>()
        val workspace = OfficeWorkspace(OfficeWorkspaceOperations(
            cachedFiles = { listing("Manual.pdf", NextcloudFileListingSource.Cache) },
            files = { listing("Manual.pdf", NextcloudFileListingSource.Network) },
            capabilities = { releaseEditors.await(); capabilities("suite") },
        ))
        val load = async(start = CoroutineStart.UNDISPATCHED) { workspace.load("") }
        try {
            val state = workspace.state.value
            assertFalse(load.isCompleted)
            assertTrue(state.discoveringEditors)
            assertFalse(state.loading)
            assertTrue(state.listingNetworkConfirmed)
            assertEquals("Manual.pdf", OfficeWorkspaceLocation("", 1).resolveSelection(state)?.path)
            releaseEditors.complete(Unit)
            load.await()
            assertFalse(workspace.state.value.discoveringEditors)
            assertEquals(setOf("suite"), workspace.state.value.capabilities.editors.keys)
            assertTrue(workspace.state.value.listingNetworkConfirmed)
        } finally { load.cancelAndJoin() }
    }

    @Test
    fun failedFolderCanBeRetriedWhileEditorDiscoveryIsPending() = runBlocking {
        val releaseEditors = CompletableDeferred<Unit>()
        val workspace = OfficeWorkspace(OfficeWorkspaceOperations(
            cachedFiles = { listing("Manual.pdf", NextcloudFileListingSource.Cache) },
            files = { error("Folder unavailable") },
            capabilities = { releaseEditors.await(); error("Editor unavailable") },
        ))
        val load = async(start = CoroutineStart.UNDISPATCHED) { workspace.load("") }
        try {
            val failedFolder = workspace.state.value
            assertFalse(failedFolder.loading)
            assertFalse(failedFolder.listingNetworkConfirmed)
            assertTrue(failedFolder.error.orEmpty().contains("refresh this folder"))
            releaseEditors.complete(Unit)
            load.await()
            assertEquals(failedFolder.error, workspace.state.value.error)
        } finally { load.cancelAndJoin() }
    }

    @Test
    fun lateEditorSuccessOrFailureCannotReplaceANewerFoldersState() = runBlocking {
        listOf(false, true).forEach { oldFails ->
            val releaseOld = CompletableDeferred<Unit>()
            var calls = 0
            val workspace = OfficeWorkspace(OfficeWorkspaceOperations(
                cachedFiles = { null },
                files = { path -> listing("$path/Manual.pdf", NextcloudFileListingSource.Network) },
                capabilities = {
                    if (++calls == 1) {
                        releaseOld.await()
                        if (oldFails) error("Old editor request failed")
                        capabilities("old")
                    } else capabilities("new")
                },
            ))
            val old = async(start = CoroutineStart.UNDISPATCHED) { workspace.load("old") }
            try {
                workspace.load("new")
                val fresh = workspace.state.value
                releaseOld.complete(Unit)
                old.await()
                assertEquals(fresh, workspace.state.value)
                assertEquals("new", fresh.path)
                assertEquals(setOf("new"), fresh.capabilities.editors.keys)
            } finally { old.cancelAndJoin() }
        }
    }

    @Test
    fun cancellationDuringDiscoveryKeepsThePublishedFolderWithoutAnOfflineError() = runBlocking {
        var discoveryCancelled = false
        val releaseEditors = CompletableDeferred<Unit>()
        val workspace = OfficeWorkspace(OfficeWorkspaceOperations(
            cachedFiles = { null },
            files = { listing("Manual.pdf", NextcloudFileListingSource.Network) },
            capabilities = {
                try { releaseEditors.await(); capabilities("suite") } finally { discoveryCancelled = true }
            },
        ))
        val load = async(start = CoroutineStart.UNDISPATCHED) { workspace.load("") }
        load.cancelAndJoin()
        assertTrue(discoveryCancelled)
        assertTrue(workspace.state.value.listingNetworkConfirmed)
        assertEquals(null, workspace.state.value.error)
    }

    private fun listing(path: String, source: NextcloudFileListingSource) = NextcloudFileListing(listOf(
        NextcloudFile(path, path.substringAfterLast('/'), false, "application/pdf", 1, null,
            fileId = 1, hasPreview = false, etag = "v1"),
    ), source)

    private fun capabilities(id: String) = NextcloudDocumentEditingCapabilities(
        editors = mapOf(id to NextcloudDocumentEditorCapability(id, "Suite", setOf("application/pdf"), emptySet(), true)),
        creators = emptyMap(), supportsFileId = true,
    )
}
