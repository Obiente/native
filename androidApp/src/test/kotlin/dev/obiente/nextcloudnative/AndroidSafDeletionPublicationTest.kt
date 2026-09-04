package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncDecisionReason
import dev.obiente.nextcloudnative.app.FileSyncOperation
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidSafDeletionPublicationTest {
    @Test
    fun `local deletions retain strengthened replacement evidence`() {
        val protected = androidFileSyncProtectedReplacementPaths(
            operations = listOf(
                FileSyncOperation.NeedsDecision("removed", FileSyncDecisionReason.RemoteDeletion),
                FileSyncOperation.NeedsDecision("collision", FileSyncDecisionReason.FirstSyncCollision),
                FileSyncOperation.Download("download", expectedLocalRevision = "local-1"),
            ),
            localPaths = setOf("removed", "collision", "download"),
        )

        assertEquals(setOf("removed", "collision", "download"), protected)
    }

    @Test
    fun `local deletion preserves content edited after authentication`() {
        val original = byteArrayOf(1, 2)
        val concurrentEdit = byteArrayOf(8, 9)
        val directory = FakeSafDirectory().apply {
            addFile("Archive", original)
            mutateBackupBeforeContentIdentityCall = 3 to concurrentEdit
        }
        val current = directory.documentNamed("Archive")

        publisher(directory).delete(
            finalName = "Archive",
            currentDocument = current,
            backupContentIdentity = directory.contentIdentity(current),
        )

        val recoveredName = AndroidSafOwnedDownloadTransaction("Archive", TOKEN).changedBackupName
        assertEquals(listOf(recoveredName), directory.names())
        assertContentEquals(concurrentEdit, directory.entryNamed(recoveredName).bytes)
        assertEquals(0, directory.deleteCalls)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `local deletion removes only the authenticated protected generation`() {
        val directory = FakeSafDirectory().apply { addFile("Archive", byteArrayOf(1, 2)) }
        val current = directory.documentNamed("Archive")

        publisher(directory).delete(
            finalName = "Archive",
            currentDocument = current,
            backupContentIdentity = directory.contentIdentity(current),
        )

        assertEquals(emptyList(), directory.names())
        assertEquals(1, directory.deleteCalls)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `cancelled local deletion restores its protected generation after restart`() {
        val initial = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val directory = FakeSafDirectory().apply {
            addFile("Archive", byteArrayOf(1, 2))
            cancelAfterRenameTo = initial.backupName
        }
        val current = directory.documentNamed("Archive")

        assertFailsWith<CancellationException> {
            publisher(directory).delete(
                finalName = "Archive",
                currentDocument = current,
                backupContentIdentity = directory.contentIdentity(current),
            )
        }

        assertEquals(setOf(initial.backupName), directory.names().toSet())
        assertEquals(1, directory.ownership.transactions().size)

        publisher(directory).reconcile()

        assertEquals(listOf("Archive"), directory.names())
        assertContentEquals(byteArrayOf(1, 2), directory.entryNamed("Archive").bytes)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    private fun publisher(directory: FakeSafDirectory) =
        AndroidSafDownloadPublisher(directory, directory.ownership, { TOKEN }, directory::contentIdentity)

    private companion object {
        const val TOKEN = "01234567-89ab-cdef-0123-456789abcdef"
    }
}
