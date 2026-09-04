package dev.obiente.nextcloudnative

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AndroidSafDownloadStageIdentityTest {
    @Test
    fun `restart authenticates and retires a stage created before identity persistence`() {
        val directory = FakeSafDirectory().apply { addFile("Report.txt", byteArrayOf(10, 11)) }

        assertFailsWith<IOException> {
            publisher(directory).publish(
                finalName = "Report.txt",
                currentDocument = directory.documentNamed("Report.txt"),
                createStage = { name ->
                    directory.createFile(name)
                    throw IOException("process stopped after provider creation")
                },
                prepareStage = {},
            )
        }

        val pending = directory.ownership.transactions().single()
        assertEquals(null, pending.stageDocumentIdentity)
        assertEquals(setOf("Report.txt", pending.stageName), directory.names().toSet())
        directory.failNextStageDeletion = true

        publisher(directory).reconcile()

        val authenticated = directory.ownership.transactions().single()
        assertEquals(directory.documentNamed(pending.stageName).toString(), authenticated.stageDocumentIdentity)
        assertEquals(setOf("Report.txt", pending.stageName), directory.names().toSet())

        publisher(directory).reconcile()

        assertEquals(listOf("Report.txt"), directory.names())
        assertContentEquals(byteArrayOf(10, 11), directory.entryNamed("Report.txt").bytes)
        assertEquals(emptyList(), directory.ownership.transactions())
        assertEquals(2, directory.deleteCalls)
    }

    @Test
    fun `preexisting exact stage name prevents ownership and provider mutation`() {
        val transaction = AndroidSafOwnedDownloadTransaction("Report.txt", TOKEN)
        val directory = FakeSafDirectory().apply {
            addFile("Report.txt", byteArrayOf(12, 13))
            addFile(transaction.stageName, byteArrayOf(14, 15))
        }
        var createCalled = false

        assertFailsWith<IllegalArgumentException> {
            publisher(directory).publish(
                finalName = "Report.txt",
                currentDocument = directory.documentNamed("Report.txt"),
                createStage = {
                    createCalled = true
                    directory.createFile(it)
                },
                prepareStage = {},
            )
        }

        assertFalse(createCalled)
        assertEquals(emptyList(), directory.ownership.transactions())
        assertContentEquals(byteArrayOf(14, 15), directory.entryNamed(transaction.stageName).bytes)
        assertEquals(0, directory.deleteCalls)
    }

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
