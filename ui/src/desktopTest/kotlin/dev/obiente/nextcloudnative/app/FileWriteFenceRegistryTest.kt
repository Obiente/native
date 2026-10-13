package dev.obiente.nextcloudnative.app

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/** One process-level registry shares a Files delete's blocks with every surface of that account. */
class FileWriteFenceRegistryTest {
    private val alice = NextcloudSession("https://fence-registry.invalid", "alice", "synthetic")
    private val bob = NextcloudSession("https://fence-registry.invalid", "bob", "synthetic")
    private val folder = file("Projects/Old", etag = "f1", directory = true)

    @Test
    fun `a Files delete blocks the sync-center picker and the share upload of that account only`(): Unit = runBlocking {
        val registry = FileWriteFenceRegistry()
        val release = CompletableDeferred<Unit>()
        val deletes = coordinator(registry, alice)
        val dialog = FileDeleteDialogState(deletes).apply { open(folder) }
        val job = deletes.start(assertNotNull(dialog.begin()), { release.await(); NextcloudFileMutationResult(null, null) }) {
            error("not read")
        }
        val calls = mutableListOf<String>()

        // The sync center builds its picker from the same registry, outside the Files composition.
        val syncPicker = remoteFolderPickerOperations(services(calls), alice, "alice", registry.account(alice.accountId))
        assertIs<FileWriteBlockedException>(runCatching { syncPicker.createDirectoryIfAbsent("Projects/Old/Synced") }.exceptionOrNull())
        assertEquals(RemoteFolderSelectionAccess.Denied(FileWriteBlock.DeleteRunning.message), syncPicker.selectionAccess("Projects/Old"))
        assertEquals(RemoteFolderSelectionAccess.Allowed, syncPicker.selectionAccess("Projects"), "A child delete does not block its folder.")

        // The share upload resolves the fence for the account it uploads into.
        val sharePicker = incomingShareFolderPickerOperations(
            remoteFolderPickerOperations(services(calls), alice, "alice", registry.account(alice.accountId)),
            selectionAccess = { error("Permissions are not inspected for a blocked destination.") },
            requireCreateDirectories = {},
        )
        assertEquals(RemoteFolderSelectionAccess.Denied(FileWriteBlock.DeleteRunning.message), sharePicker.selectionAccess("Projects/Old/Inbox"))
        assertEquals(FileWriteBlock.DeleteRunning, incomingShareUploadBlock(registry.account(alice.accountId), "Projects/Old"))
        assertNull(incomingShareUploadBlock(registry.account(alice.accountId), "Projects"))

        // Another account of the same process is unaffected.
        val bobPicker = remoteFolderPickerOperations(services(calls), bob, "bob", registry.account(bob.accountId))
        bobPicker.createDirectoryIfAbsent("Projects/Old/Synced")
        assertNull(incomingShareUploadBlock(registry.account(bob.accountId), "Projects/Old"))
        assertEquals(listOf("createDirectoryIfAbsent:Projects/Old/Synced"), calls, "Only the other account wrote.")

        release.complete(Unit)
        job.join()
        assertNull(incomingShareUploadBlock(registry.account(alice.accountId), "Projects/Old"), "A verified delete lifts it.")
        deletes.close()
    }

    @Test
    fun `sign-out or removal drops that account's blocks and keeps other accounts'`(): Unit = runBlocking {
        val registry = FileWriteFenceRegistry()
        refreshRequired(registry, alice)
        refreshRequired(registry, bob)

        registry.removeAccount(alice.accountId.storageKey)

        assertNull(registry.account(alice.accountId).writeBlock("Projects/Old/new.txt"), "A new incarnation starts unblocked.")
        assertNotNull(registry.account(bob.accountId).writeBlock("Projects/Old/new.txt"))
    }

    @Test
    fun `a refresh block lifts only through a listing that started after the outcome`(): Unit = runBlocking {
        val registry = FileWriteFenceRegistry()
        val fence = registry.account(alice.accountId)
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val staleRead = async {
            fence.readFolder("Projects") {
                readStarted.complete(Unit)
                releaseRead.await()
                NextcloudFileListing(emptyList(), NextcloudFileListingSource.Network)
            }
        }
        readStarted.await()
        refreshRequired(registry, alice)
        releaseRead.complete(Unit)
        staleRead.await()
        val expected = FileWriteBlock.RefreshRequired("Projects/Old")

        assertEquals(expected, fence.writeBlock("Projects/Old/new.txt"), "A listing that started earlier does not lift it.")
        val picker = remoteFolderPickerOperations(services(mutableListOf()), alice, "alice", fence)
        assertEquals(RemoteFolderSelectionAccess.Denied(expected.message), picker.selectionAccess("Projects/Old"))

        fence.readFolder("Projects") { NextcloudFileListing(emptyList(), NextcloudFileListingSource.Cache) }
        assertEquals(expected, fence.writeBlock("Projects/Old/new.txt"), "A cached listing is not a server answer.")
        fence.readFolder("Projects") { NextcloudFileListing(emptyList(), NextcloudFileListingSource.Network) }
        assertNull(fence.writeBlock("Projects/Old/new.txt"))
        assertEquals(RemoteFolderSelectionAccess.Allowed, picker.selectionAccess("Projects/Old"))
    }

    @Test
    fun `the registry hands out one fence per account`() {
        val registry = FileWriteFenceRegistry()

        assertTrue(registry.account(alice.accountId) === registry.account(alice.accountId))
        assertTrue(registry.account(alice.accountId) !== registry.account(bob.accountId))
    }

    /** Leaves a refresh-required block on Projects/Old for [session]'s account. */
    private suspend fun CoroutineScope.refreshRequired(registry: FileWriteFenceRegistry, session: NextcloudSession) {
        val deletes = coordinator(registry, session)
        val dialog = FileDeleteDialogState(deletes).apply { open(folder) }
        deletes.start(assertNotNull(dialog.begin()), { throw fileOperationException(412) }) { error("not read") }.join()
        deletes.close()
    }

    private fun CoroutineScope.coordinator(registry: FileWriteFenceRegistry, session: NextcloudSession) =
        FileDeleteCoordinator(registry.account(session.accountId), CoroutineScope(coroutineContext.minusKey(Job)))

    private fun file(path: String, etag: String, directory: Boolean) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = directory,
        mimeType = null,
        size = null,
        lastModified = null,
        fileId = 9L,
        hasPreview = false,
        etag = etag,
    )

    private fun services(calls: MutableList<String>): NextcloudPlatformServices =
        Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "SyntheticRegistryServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                "createDirectoryIfAbsent" -> {
                    calls += "createDirectoryIfAbsent:${args!![2]}"
                    true
                }
                else -> error("Unexpected synthetic operation: ${method.name}")
            }
        } as NextcloudPlatformServices
}
