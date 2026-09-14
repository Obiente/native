package dev.obiente.nextcloudnative

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidSafRecoveryGenerationCompletionTest {
    @Test
    fun exactRecordedBackupIsVerifiedBeforeItsRestorationRename() {
        val directory = FakeSafDirectory()
        val initial = AndroidSafOwnedDownloadTransaction("original", "01234567-89ab-cdef-0123-456789abcdef")
        val backup = directory.addFile(initial.backupName, byteArrayOf(1, 2))
        val transaction = initial.copy(backupProtected = true, backupDocumentIdentity = backup.toString(),
            backupContentIdentity = directory.contentIdentity(backup))
        directory.ownership.add(transaction)
        val generations = AndroidProviderRecoveryGenerations()
        val guarded = object : AndroidSafPublicationDirectory<Int> by directory {
            override fun rename(document: Int, displayName: String): Int {
                assertEquals("generation-a", generations.mutationEtag(document.toString(), false))
                return directory.rename(document, displayName)
            }
        }
        AndroidSafDownloadPublisher(guarded, directory.ownership, { initial.token }) { document ->
            generations.run(document.toString(), AndroidDocumentsProviderRecoveryOperation.OpenRead) {
                generations.recordReadGeneration(document.toString(), "generation-a")
                directory.contentIdentity(document)
            }
        }.reconcileForSync()
        assertEquals(listOf("original"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun directoryStagePreservationRetiresRecoveryAfterSuccessfulOrAmbiguousMove() {
        for (ambiguous in listOf(false, true)) {
            val directory = FakeSafDirectory()
            val initial = AndroidSafOwnedDownloadTransaction("original", "01234567-89ab-cdef-0123-456789abcdef")
            val stage = directory.addDirectory(initial.stageName)
            directory.ownership.add(initial.copy(stageDocumentIdentity = stage.toString(), stageContentIdentity = directory.contentIdentity(stage)))
            val preservedName = "Recovered folder - original - unique"
            val guarded = object : AndroidSafPublicationDirectory<Int> by directory {
                override fun delete(document: Int): Boolean {
                    directory.rename(document, preservedName)
                    directory.replaceDocumentIdentity(preservedName)
                    if (ambiguous) throw IOException("move response lost")
                    return true
                }
            }
            AndroidSafDownloadPublisher(guarded, directory.ownership, { initial.token }, directory::contentIdentity).reconcileForSync()
            assertEquals(listOf(preservedName), directory.names())
            assertTrue(directory.ownership.transactions().isEmpty())
            assertEquals(0, directory.deleteCalls)
        }
    }
}
