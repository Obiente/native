package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AndroidSafDownloadContentRecoveryTest {
    @Test
    fun `changed protected backup becomes a visible recovered copy instead of being deleted`() {
        val concurrentEdit = byteArrayOf(8, 9)
        val directory = FakeSafDirectory().apply {
            addFile("Archive", byteArrayOf(1, 2))
            mutateNextBackupBeforeContentIdentity = concurrentEdit
        }

        publisher(directory).publish("Archive", directory.documentNamed("Archive")) { output ->
            output.write(byteArrayOf(3, 4))
        }

        val recoveredName = AndroidSafOwnedDownloadTransaction("Archive", TOKEN).changedBackupName
        assertEquals(setOf("Archive", recoveredName), directory.names().toSet())
        assertContentEquals(byteArrayOf(3, 4), directory.entryNamed("Archive").bytes)
        assertContentEquals(concurrentEdit, directory.entryNamed(recoveredName).bytes)
        assertEquals(emptyList(), directory.ownership.transactions())
        assertEquals(
            setOf("Archive", recoveredName),
            publisher(directory).visibleDocuments().map { it.displayName }.toSet(),
        )
    }

    @Test
    fun `restart preserves a changed completed backup as a visible recovered copy`() {
        val directory = FakeSafDirectory().apply {
            addFile("Archive", byteArrayOf(3, 4))
        }
        val initial = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val backup = directory.addFile(initial.backupName, byteArrayOf(8, 9))
        val transaction = initial.copy(
            publicationAttempted = true,
            publicationCompleted = true,
            backupProtected = true,
            backupDocumentIdentity = backup.toString(),
            backupContentIdentity = "File:1,2",
        )
        directory.ownership.add(transaction)

        publisher(directory).reconcile()

        assertEquals(setOf("Archive", transaction.changedBackupName), directory.names().toSet())
        assertContentEquals(byteArrayOf(8, 9), directory.entryNamed(transaction.changedBackupName).bytes)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `restart recognizes a published final by its persisted stage identity`() {
        val directory = FakeSafDirectory()
        val initial = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val backup = directory.addFile(initial.backupName, byteArrayOf(1, 2))
        val final = directory.addFile("Archive", byteArrayOf(3, 4))
        val transaction = initial.copy(
            publicationAttempted = true,
            backupProtected = true,
            backupDocumentIdentity = backup.toString(),
            backupContentIdentity = directory.contentIdentity(backup),
            stageDocumentIdentity = final.toString(),
        )
        directory.ownership.add(transaction)

        publisher(directory).reconcile()

        assertEquals(listOf("Archive"), directory.names())
        assertContentEquals(byteArrayOf(3, 4), directory.entryNamed("Archive").bytes)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `verified publication never restores its authenticated backup`() {
        val directory = FakeSafDirectory()
        val initial = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val backup = directory.addDirectory(initial.backupName)
        val transaction = initial.copy(
            publicationAttempted = true,
            publicationCompleted = true,
            backupContentIdentity = directory.contentIdentity(backup),
        )
        directory.ownership.add(transaction)

        publisher(directory).reconcile()

        assertEquals(emptyList(), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `legacy completed backup without content identity is preserved visibly`() {
        val directory = FakeSafDirectory().apply {
            addFile("Archive", byteArrayOf(3, 4))
        }
        val transaction = AndroidSafOwnedDownloadTransaction(
            finalName = "Archive",
            token = TOKEN,
            publicationAttempted = true,
            publicationCompleted = true,
        )
        directory.addFile(transaction.backupName, byteArrayOf(1, 2))
        directory.ownership.add(transaction)

        publisher(directory).reconcile()

        assertEquals(setOf("Archive", transaction.changedBackupName), directory.names().toSet())
        assertContentEquals(byteArrayOf(1, 2), directory.entryNamed(transaction.changedBackupName).bytes)
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    private fun publisher(directory: FakeSafDirectory) =
        AndroidSafDownloadPublisher(directory, directory.ownership, { TOKEN }, directory::contentIdentity)

    private companion object {
        const val TOKEN = "01234567-89ab-cdef-0123-456789abcdef"
    }
}
