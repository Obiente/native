package dev.obiente.nextcloudnative

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidSafDownloadStageIdentityTest {
    @Test
    fun `replacement stage identity mismatch preserves and reveals the unknown occupant`() {
        val directory = FakeSafDirectory()
        val publisher = publisher(directory)
        assertFailsWith<CancellationException> {
            publisher.publish(
                finalName = "Archive",
                currentDocument = null,
                createStage = directory::createFile,
                prepareStage = { stage ->
                    directory.writeFile(stage) { output -> output.write(byteArrayOf(14, 15)) }
                    throw CancellationException("process stopped before publication")
                },
            )
        }
        val transaction = directory.ownership.transactions().single()
        assertEquals(
            directory.documentNamed(transaction.stageName).toString(),
            transaction.stageDocumentIdentity,
        )
        directory.replaceDocumentIdentity(transaction.stageName)

        publisher.reconcile()

        assertEquals(0, directory.deleteCalls)
        assertContentEquals(byteArrayOf(14, 15), directory.entryNamed(transaction.stageName).bytes)
        assertEquals(
            listOf(transaction.stageName),
            publisher.visibleDocuments().map { it.displayName },
        )
        assertEquals(listOf(transaction), directory.ownership.transactions())
        assertFailsWith<IllegalArgumentException> { publisher.reconcileForSync() }
    }

    @Test
    fun `restart retires an authenticated stage that never reached publication`() {
        val initial = AndroidSafOwnedDownloadTransaction(
            finalName = "Report.txt",
            token = TOKEN,
            publicationAttempted = true,
        )
        val directory = FakeSafDirectory()
        directory.addOwnedStage(initial, byteArrayOf(16, 17))

        publisher(directory).reconcile()

        assertEquals(emptyList(), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    private fun publisher(directory: FakeSafDirectory) =
        AndroidSafDownloadPublisher(directory, directory.ownership, { TOKEN }, directory::contentIdentity)

    private companion object {
        const val TOKEN = "01234567-89ab-cdef-0123-456789abcdef"
    }
}
