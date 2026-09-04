package dev.obiente.nextcloudnative

import java.io.FileInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSafDownloadOwnershipIndexTest {
    @Test
    fun `pending ownership is isolated from unrelated SAF trees`() {
        val base = Files.createTempDirectory("saf-download-tree-index-").toFile()
        try {
            val first = androidSafDownloadOwnershipStoreForTree(base, "content://provider/tree/first")
            val second = androidSafDownloadOwnershipStoreForTree(base, "content://provider/tree/second")
            first.forDirectory("content://provider/tree/first/document/parent")
                .add(AndroidSafOwnedDownloadTransaction("Report.txt", FIRST_TOKEN))

            assertTrue(first.hasPendingTransactions())
            assertFalse(second.hasPendingTransactions())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `unrelated ownership does not enable a selective tree recovery prepass`() {
        val base = Files.createTempDirectory("saf-download-unrelated-index-").toFile()
        try {
            androidSafDownloadOwnershipStoreForTree(base, "content://provider/tree/first")
                .forDirectory("content://provider/tree/first/document/parent")
                .add(AndroidSafOwnedDownloadTransaction("Report.txt", FIRST_TOKEN))
            val unrelated = androidSafDownloadOwnershipStoreForTree(
                base,
                "content://provider/tree/large-selective-root",
            ).indexed()
            var traversed = false

            indexAndroidSafRecoveryLocationsIfNeeded(unrelated) { traversed = true }

            assertFalse(traversed)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `legacy unscoped ownership remains fail closed for scoped trees`() {
        val base = Files.createTempDirectory("saf-download-legacy-index-").toFile()
        try {
            val parent = "content://provider/tree/legacy/document/parent"
            val transaction = AndroidSafOwnedDownloadTransaction("Report.txt", FIRST_TOKEN)
            AndroidSafDownloadOwnershipStore(base).forDirectory(parent).add(transaction)

            val scoped = androidSafDownloadOwnershipStoreForTree(
                base,
                "content://provider/tree/current",
            )

            assertTrue(scoped.hasPendingTransactions())
            assertEquals(listOf(transaction), scoped.indexed().forDirectory(parent).transactions())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `tree-wide recovery indexing is skipped without pending ownership`() {
        val root = Files.createTempDirectory("saf-download-empty-index-").toFile()
        try {
            val index = AndroidSafDownloadOwnershipStore(root).indexed()
            var indexed = false

            indexAndroidSafRecoveryLocationsIfNeeded(index) { indexed = true }

            assertFalse(indexed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `pending ownership enables tree-wide recovery indexing`() {
        val root = Files.createTempDirectory("saf-download-pending-index-").toFile()
        try {
            AndroidSafDownloadOwnershipStore(root)
                .forDirectory("content://provider/tree/root/document/original")
                .add(AndroidSafOwnedDownloadTransaction("Report.txt", FIRST_TOKEN))
            val index = AndroidSafDownloadOwnershipStore(root).indexed()
            var indexed = false

            indexAndroidSafRecoveryLocationsIfNeeded(index) { indexed = true }

            assertTrue(indexed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `one ownership listing serves a large directory scan`() {
        val root = Files.createTempDirectory("saf-download-ownership-index-").toFile()
        try {
            val relevantScope = "content://provider/tree/root/document/relevant"
            val transaction = AndroidSafOwnedDownloadTransaction("Report.txt", FIRST_TOKEN)
            AndroidSafDownloadOwnershipStore(root).forDirectory(relevantScope).add(transaction)
            var listingCount = 0
            var readCount = 0
            val store = AndroidSafDownloadOwnershipStore(
                directory = root,
                listFiles = {
                    listingCount += 1
                    root.listFiles()
                },
                openInput = { file ->
                    readCount += 1
                    FileInputStream(file)
                },
            )

            val index = store.indexed()
            repeat(100) {
                assertEquals(listOf(transaction), index.forDirectory(relevantScope).transactions())
            }
            repeat(20_000) { directory ->
                assertEquals(
                    emptyList(),
                    index.forDirectory("content://provider/tree/root/document/$directory").transactions(),
                )
            }

            assertEquals(1, listingCount)
            assertEquals(1, readCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `indexed malformed row blocks only its encoded recovery scope`() {
        val root = Files.createTempDirectory("saf-download-ownership-index-malformed-").toFile()
        try {
            val firstScope = "content://provider/tree/root/document/one"
            val secondScope = "content://provider/tree/root/document/two"
            val first = AndroidSafOwnedDownloadTransaction("Archive", FIRST_TOKEN)
            val second = AndroidSafOwnedDownloadTransaction("Photos", SECOND_TOKEN)
            val store = AndroidSafDownloadOwnershipStore(root)
            store.forDirectory(firstScope).add(first)
            store.forDirectory(secondScope).add(second)
            root.listFiles().orEmpty().single { file -> "-${first.token}.row" in file.name }
                .writeBytes(byteArrayOf(0x01, 0x02))

            val index = store.indexed()
            assertEquals(listOf(second), index.forDirectory(secondScope).transactions())
            assertFailsWith<Exception> { index.forDirectory(firstScope).transactions() }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `indexed ownership mutations stay durable without another directory listing`() {
        val root = Files.createTempDirectory("saf-download-ownership-index-mutation-").toFile()
        try {
            var listingCount = 0
            val store = AndroidSafDownloadOwnershipStore(
                directory = root,
                listFiles = {
                    listingCount += 1
                    root.listFiles()
                },
            )
            val index = store.indexed()
            val ownership = index.forDirectory("content://provider/tree/root/document/one")
            val relocated = index.forDirectory("content://provider/tree/root/document/two")
            val initial = AndroidSafOwnedDownloadTransaction("Report.txt", FIRST_TOKEN)
            val attempted = initial.copy(publicationAttempted = true)

            ownership.add(initial)
            assertEquals(listOf(initial), relocated.transactions(setOf(initial.backupName)))
            relocated.replace(attempted)
            assertEquals(listOf(attempted), relocated.transactions(setOf(attempted.backupName)))
            relocated.remove(attempted)

            assertEquals(emptyList(), root.listFiles().orEmpty().toList())
            assertEquals(1, listingCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `tree-wide observations defer recovery to the parent containing the token`() {
        val root = Files.createTempDirectory("saf-download-ownership-relocated-").toFile()
        try {
            val originalScope = "content://provider/tree/root/document/one"
            val relocatedScope = "content://provider/tree/root/document/two"
            val transaction = AndroidSafOwnedDownloadTransaction("Report.txt", FIRST_TOKEN)
            AndroidSafDownloadOwnershipStore(root).forDirectory(originalScope).add(transaction)
            val index = AndroidSafDownloadOwnershipStore(root).indexed()
            val relocatedName = "provider-stage-${transaction.token}"

            index.observeRecoveryNames(originalScope, emptySet())
            index.observeRecoveryNames(relocatedScope, setOf(relocatedName))

            assertEquals(emptyList(), index.forDirectory(originalScope).transactions())
            assertEquals(
                listOf(transaction),
                index.forDirectory(relocatedScope).transactions(setOf(relocatedName)),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val FIRST_TOKEN = "01234567-89ab-cdef-0123-456789abcdef"
        const val SECOND_TOKEN = "fedcba98-7654-3210-fedc-ba9876543210"
    }
}
