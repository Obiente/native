package dev.obiente.nextcloudnative

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidSafRelocatedBackupRecoveryTest {
    @Test fun relocatedDeletionBackupRestoresOnlyItsAuthenticatedContent() {
        for ((changed, alternateName) in listOf(false to false, true to false, false to true, true to true)) {
            val root = Files.createTempDirectory("relocated-delete-backup-").toFile()
            try {
                val directory = FakeSafDirectory()
                val initial = AndroidSafOwnedDownloadTransaction("Archive", "01234567-89ab-cdef-0123-456789abcdef")
                val original = directory.addFile(initial.backupName, byteArrayOf(1, 2))
                val content = directory.contentIdentity(original)
                val backupName = if (alternateName) "provider-backup-${initial.token}" else initial.backupName
                if (changed) {
                    directory.delete(original)
                    directory.addFile(initial.backupName, byteArrayOf(8, 9))
                }
                if (alternateName) directory.rename(directory.documentNamed(initial.backupName), backupName)
                val transaction = initial.copy(
                    backupProtected = true, backupDocumentIdentity = "old-provider-identity",
                    backupContentIdentity = content,
                )
                val store = AndroidSafDownloadOwnershipStore(root)
                store.forDirectory("content://provider/document/original").add(transaction)
                val index = store.indexed()
                val relocated = "content://provider/document/moved"
                index.observeRecoveryNames(relocated, setOf(backupName))
                val publisher = AndroidSafDownloadPublisher(directory, index.forDirectory(relocated), { initial.token }, directory::contentIdentity)
                if (changed) {
                    assertFailsWith<IllegalArgumentException> { publisher.reconcileForSync() }
                    assertEquals(listOf(transaction), store.pendingTransactions())
                    assertContentEquals(byteArrayOf(8, 9), directory.entryNamed(backupName).bytes)
                } else {
                    publisher.reconcile()
                    assertEquals(emptyList(), store.pendingTransactions())
                    assertContentEquals(byteArrayOf(1, 2), directory.entryNamed("Archive").bytes)
                }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun movedStagesRequireContentProofAndUnknownRenamedCandidatesStayPending() {
        for ((changed, alternateName) in listOf(false to false, true to false, false to true, true to true)) {
            val directory = FakeSafDirectory()
            val initial = AndroidSafOwnedDownloadTransaction("Archive", "01234567-89ab-cdef-0123-456789abcdef")
            val original = directory.addFile(initial.stageName, byteArrayOf(1, 2))
            val content = directory.contentIdentity(original)
            directory.delete(original)
            val name = if (alternateName) "renamed-stage-${initial.token}" else initial.stageName
            val bytes = if (changed) byteArrayOf(8, 9) else byteArrayOf(1, 2)
            directory.addFile(name, bytes)
            val transaction = initial.copy(stageDocumentIdentity = original.toString(), stageContentIdentity = content)
            directory.ownership.add(transaction)
            val publisher = AndroidSafDownloadPublisher(directory, directory.ownership, { initial.token }, directory::contentIdentity)
            if (!changed && !alternateName) {
                publisher.reconcileForSync()
                assertEquals(emptyList(), directory.ownership.transactions())
                assertEquals(emptyList(), directory.names())
            } else {
                assertFailsWith<IllegalArgumentException> { publisher.reconcileForSync() }
                assertEquals(listOf(transaction), directory.ownership.transactions())
                assertContentEquals(bytes, directory.entryNamed(name).bytes)
            }
        }
    }
}
