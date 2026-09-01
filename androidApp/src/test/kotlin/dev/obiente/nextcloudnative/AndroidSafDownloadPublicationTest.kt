package dev.obiente.nextcloudnative

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidSafDownloadPublicationTest {
    @Test
    fun `cancellation before publication preserves the mismatched directory`() {
        val directory = FakeSafDirectory().apply { addDirectory("Archive") }
        val current = directory.documentNamed("Archive")
        val publisher = publisher(directory)

        assertFailsWith<CancellationException> {
            publisher.publish("Archive", current) { output ->
                output.write(byteArrayOf(1, 2))
                throw CancellationException("worker stopped")
            }
        }

        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(listOf("Archive"), directory.names())
    }

    @Test
    fun `write failure before publication preserves the mismatched directory`() {
        val directory = FakeSafDirectory().apply { addDirectory("Archive") }
        val current = directory.documentNamed("Archive")

        assertFailsWith<IOException> {
            publisher(directory).publish("Archive", current) {
                throw IOException("provider write failed")
            }
        }

        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(listOf("Archive"), directory.names())
    }

    @Test
    fun `rename ambiguity recognizes the published stage and retires its backup`() {
        val directory = FakeSafDirectory().apply { addDirectory("Archive") }
        val current = directory.documentNamed("Archive")
        directory.throwAfterRenameTo = "Archive"

        publisher(directory).publish("Archive", current) { output ->
            output.write(byteArrayOf(3, 4, 5))
        }

        val published = directory.entryNamed("Archive")
        assertEquals(FakeSafKind.File, published.kind)
        assertContentEquals(byteArrayOf(3, 4, 5), published.bytes)
        assertEquals(listOf("Archive"), directory.names())
    }

    @Test
    fun `failed backup deletion stays hidden and a later instance retries cleanup`() {
        val directory = FakeSafDirectory().apply { addDirectory("Archive") }
        val current = directory.documentNamed("Archive")
        directory.failNextBackupDeletion = true
        val first = publisher(directory)

        first.publish("Archive", current) { output -> output.write(byteArrayOf(8, 9)) }

        assertEquals(listOf("Archive"), first.visibleDocuments().map { it.displayName })
        assertTrue(directory.names().any { ".nextcloud-native-backup-" in it })
        assertEquals(1, directory.ownership.transactions().size)

        val restarted = publisher(directory)
        restarted.reconcile()

        assertEquals(listOf("Archive"), restarted.visibleDocuments().map { it.displayName })
        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `restart restores a protected directory and hides the abandoned stage`() {
        val transaction = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val directory = FakeSafDirectory().apply {
            ownership.add(transaction)
            addDirectory(transaction.backupName)
            addFile(transaction.stageName, byteArrayOf(10, 11))
        }
        val restarted = publisher(directory)

        assertEquals(emptyList(), restarted.visibleDocuments())
        restarted.reconcile()

        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(listOf("Archive"), restarted.visibleDocuments().map { it.displayName })
        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `reserved-looking user file is visible and never reconciled without durable ownership`() {
        val transaction = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
        val directory = FakeSafDirectory().apply {
            addFile(transaction.stageName, byteArrayOf(12, 13))
        }
        val restarted = publisher(directory)

        restarted.reconcile()

        assertEquals(listOf(transaction.stageName), directory.names())
        assertEquals(listOf(transaction.stageName), restarted.visibleDocuments().map { it.displayName })
    }

    @Test
    fun `cancelled stage whose deletion fails remains owned and restart retry removes it`() {
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            failNextStageDeletion = true
        }
        val current = directory.documentNamed("Archive")

        assertFailsWith<CancellationException> {
            publisher(directory).publish("Archive", current) {
                throw CancellationException("worker stopped")
            }
        }

        assertEquals(listOf("Archive"), publisher(directory).visibleDocuments().map { it.displayName })
        assertEquals(1, directory.ownership.transactions().size)

        publisher(directory).reconcile()

        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `ownership cleanup failure after publication does not turn success ambiguous`() {
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            ownership.failNextRemove = true
        }
        val current = directory.documentNamed("Archive")

        publisher(directory).publish("Archive", current) { output -> output.write(byteArrayOf(14, 15)) }

        assertContentEquals(byteArrayOf(14, 15), directory.entryNamed("Archive").bytes)
        assertEquals(1, directory.ownership.transactions().size)

        publisher(directory).reconcile()

        assertEquals(emptyList(), directory.ownership.transactions())
        assertEquals(listOf("Archive"), directory.names())
    }

    @Test
    fun `ownership add failure prevents creation or replacement`() {
        val directory = FakeSafDirectory().apply {
            addDirectory("Archive")
            ownership.failNextAdd = true
        }
        val current = directory.documentNamed("Archive")

        assertFailsWith<IOException> {
            publisher(directory).publish("Archive", current) { output -> output.write(1) }
        }

        assertEquals(FakeSafKind.Directory, directory.entryNamed("Archive").kind)
        assertEquals(listOf("Archive"), directory.names())
    }

    @Test
    fun `directory publication failure restores the replaced file`() {
        val directory = FakeSafDirectory().apply {
            addFile("Archive", byteArrayOf(16, 17))
            failBeforeRenameTo = "Archive"
        }
        val current = directory.documentNamed("Archive")

        assertFailsWith<IOException> {
            publisher(directory).publish(
                finalName = "Archive",
                currentDocument = current,
                createStage = directory::createDirectory,
                prepareStage = {},
            )
        }

        assertEquals(FakeSafKind.File, directory.entryNamed("Archive").kind)
        assertContentEquals(byteArrayOf(16, 17), directory.entryNamed("Archive").bytes)
        assertEquals(listOf("Archive"), directory.names())
        assertEquals(emptyList(), directory.ownership.transactions())
    }

    @Test
    fun `file ownership survives store recreation and stays scoped to its SAF directory`() {
        val root = Files.createTempDirectory("saf-download-ownership-").toFile()
        try {
            val transaction = AndroidSafOwnedDownloadTransaction("Archive", TOKEN)
            AndroidSafDownloadOwnershipStore(root).forDirectory("content://provider/tree/root/document/one")
                .add(transaction)

            val restarted = AndroidSafDownloadOwnershipStore(root)
            assertEquals(
                listOf(transaction),
                restarted.forDirectory("content://provider/tree/root/document/one").transactions(),
            )
            assertEquals(
                emptyList(),
                restarted.forDirectory("content://provider/tree/root/document/two").transactions(),
            )

            restarted.forDirectory("content://provider/tree/root/document/one").remove(transaction)
            assertEquals(
                emptyList(),
                AndroidSafDownloadOwnershipStore(root)
                    .forDirectory("content://provider/tree/root/document/one")
                    .transactions(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun publisher(directory: FakeSafDirectory) =
        AndroidSafDownloadPublisher(directory, directory.ownership) { TOKEN }

    private companion object {
        const val TOKEN = "01234567-89ab-cdef-0123-456789abcdef"
    }
}

private enum class FakeSafKind { File, Directory }

private data class FakeSafEntry(
    val document: Int,
    var displayName: String,
    val kind: FakeSafKind,
    var bytes: ByteArray = byteArrayOf(),
)

private class FakeSafDirectory : AndroidSafPublicationDirectory<Int> {
    private val entries = linkedMapOf<Int, FakeSafEntry>()
    private var nextDocument = 1

    var throwAfterRenameTo: String? = null
    var failBeforeRenameTo: String? = null
    var failNextBackupDeletion: Boolean = false
    var failNextStageDeletion: Boolean = false
    val ownership = FakeSafDownloadOwnership()

    fun addDirectory(displayName: String): Int = add(displayName, FakeSafKind.Directory)

    fun addFile(displayName: String, bytes: ByteArray = byteArrayOf()): Int =
        add(displayName, FakeSafKind.File, bytes)

    fun documentNamed(displayName: String): Int = entryNamed(displayName).document

    fun entryNamed(displayName: String): FakeSafEntry = entries.values.single { it.displayName == displayName }

    fun names(): List<String> = entries.values.map { it.displayName }.sorted()

    override fun documents(): List<AndroidSafPublicationDocument<Int>> = entries.values.map { entry ->
        AndroidSafPublicationDocument(entry.document, entry.displayName)
    }

    override fun createFile(displayName: String): Int = addFile(displayName)

    override fun createDirectory(displayName: String): Int = addDirectory(displayName)

    override fun writeFile(document: Int, write: (OutputStream) -> Unit) {
        val destination = ByteArrayOutputStream()
        write(destination)
        entries.getValue(document).bytes = destination.toByteArray()
    }

    override fun rename(document: Int, displayName: String): Int {
        require(entries.values.none { it.document != document && it.displayName == displayName })
        if (failBeforeRenameTo == displayName) {
            failBeforeRenameTo = null
            throw IOException("rename failed before publication")
        }
        entries.getValue(document).displayName = displayName
        if (throwAfterRenameTo == displayName) {
            throwAfterRenameTo = null
            throw IOException("rename result was lost")
        }
        return document
    }

    override fun delete(document: Int): Boolean {
        val entry = entries.getValue(document)
        if (failNextBackupDeletion && ".nextcloud-native-backup-" in entry.displayName) {
            failNextBackupDeletion = false
            return false
        }
        if (failNextStageDeletion && ".nextcloud-native-download-" in entry.displayName) {
            failNextStageDeletion = false
            return false
        }
        return entries.remove(document) != null
    }

    private fun add(
        displayName: String,
        kind: FakeSafKind,
        bytes: ByteArray = byteArrayOf(),
    ): Int {
        require(entries.values.none { it.displayName == displayName })
        val document = nextDocument++
        entries[document] = FakeSafEntry(document, displayName, kind, bytes)
        return document
    }
}

private class FakeSafDownloadOwnership : AndroidSafDownloadOwnership {
    private val records = linkedSetOf<AndroidSafOwnedDownloadTransaction>()
    var failNextAdd = false
    var failNextRemove = false

    override fun transactions(): List<AndroidSafOwnedDownloadTransaction> = records.toList()

    override fun add(transaction: AndroidSafOwnedDownloadTransaction) {
        if (failNextAdd) {
            failNextAdd = false
            throw IOException("ownership save failed")
        }
        check(records.add(transaction))
    }

    override fun remove(transaction: AndroidSafOwnedDownloadTransaction) {
        if (failNextRemove) {
            failNextRemove = false
            throw IOException("ownership cleanup failed")
        }
        check(records.remove(transaction))
    }
}
