package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.FileSyncPendingUploadCleanup
import dev.obiente.nextcloudnative.app.FileSyncPriorityRule
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidFileSyncStoreTest {
    @Test
    fun `state round trips atomically with display metadata`() {
        withTemporaryStore { store ->
            val pair = pair()
            val expected = AndroidFileSyncPersistedState(
                coordinator = FileSyncCoordinatorState(listOf(pair)),
                localDisplayNames = mapOf(pair.id to "Obsidian vault"),
            )
            store.save(expected)

            assertEquals(expected, store.load())
        }
    }

    @Test
    fun `truncated state is rejected instead of silently losing sync intent`() {
        val directory = Files.createTempDirectory("file-sync-store-").toFile()
        try {
            val stateFile = File(directory, "state.bin")
            val store = AndroidFileSyncStore(stateFile)
            val pair = pair()
            store.save(
                AndroidFileSyncPersistedState(
                    FileSyncCoordinatorState(listOf(pair)),
                    mapOf(pair.id to "Vault"),
                ),
            )
            stateFile.writeBytes(stateFile.readBytes().dropLast(3).toByteArray())

            assertFailsWith<IllegalStateException> { store.load() }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `readers see complete snapshots while sync state is being replaced`() {
        val directory = Files.createTempDirectory("file-sync-store-").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val stateFile = File(directory, "state.bin")
            val writer = AndroidFileSyncStore(stateFile)
            val reader = AndroidFileSyncStore(stateFile)
            val pair = pair()
            val first = AndroidFileSyncPersistedState(
                FileSyncCoordinatorState(listOf(pair)),
                mapOf(pair.id to "Folder A"),
            )
            val second = first.copy(localDisplayNames = mapOf(pair.id to "Folder B"))
            writer.save(first)
            val start = CountDownLatch(1)

            val writes = executor.submit {
                start.await()
                repeat(100) { index ->
                    writer.save(if (index % 2 == 0) second else first)
                }
            }
            val reads = executor.submit {
                start.await()
                repeat(500) {
                    val snapshot = reader.load()
                    assertEquals(pair, snapshot.coordinator.pairs.single())
                    assertTrue(snapshot.localDisplayNames[pair.id] in setOf("Folder A", "Folder B"))
                }
            }

            start.countDown()
            writes.get(20, TimeUnit.SECONDS)
            reads.get(20, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `abandoned upload ownership is stored outside the bounded snapshot`() {
        val directory = Files.createTempDirectory("file-sync-cleanup-rows-").toFile()
        try {
            val stateFile = File(directory, "state.bin")
            val store = AndroidFileSyncStore(stateFile)
            val cleanups = (0 until 512).map { index ->
                FileSyncPendingUploadCleanup(
                    uploadId = "00000000-0000-0000-0000-${index.toString(16).padStart(12, '0')}",
                    relativePath = "Archive/${index.toString().padStart(4, '0')}-${"x".repeat(3_000)}.bin",
                    assembledStageEtag = "stage-$index",
                )
            }
            val pair = pair().copy(pendingUploadCleanups = cleanups)
            val expected = AndroidFileSyncPersistedState(
                FileSyncCoordinatorState(listOf(pair)),
                mapOf(pair.id to "Vault"),
            )

            store.save(expected)

            assertTrue(stateFile.length() < 64L * 1024L)
            val cleanupDirectory = File(directory, "state.bin.upload-cleanups")
            assertTrue(cleanupDirectory.isDirectory)
            assertEquals(cleanups.size, cleanupDirectory.listFiles().orEmpty().count { it.extension == "row" })
            assertEquals(expected, store.load())

            val cleanedPair = pair.copy(pendingUploadCleanups = emptyList())
            val cleaned = expected.copy(coordinator = FileSyncCoordinatorState(listOf(cleanedPair)))
            store.save(cleaned)

            assertEquals(0, cleanupDirectory.listFiles().orEmpty().count { it.extension == "row" })
            assertEquals(cleaned, store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `new cleanup ownership is durable even when the coordinator snapshot is rejected`() {
        val directory = Files.createTempDirectory("file-sync-cleanup-first-").toFile()
        try {
            val cleanup = FileSyncPendingUploadCleanup(
                uploadId = "01234567-89ab-cdef-0123-456789abcdef",
                relativePath = "Archive/large.bin",
            )
            val pair = pair().copy(pendingUploadCleanups = listOf(cleanup))
            val stateFile = File(directory, "state.bin")
            val store = AndroidFileSyncStore(stateFile, maximumSnapshotBytes = 1)

            assertFailsWith<IllegalStateException> {
                store.save(AndroidFileSyncPersistedState(FileSyncCoordinatorState(listOf(pair))))
            }

            assertTrue(!stateFile.exists())
            assertEquals(
                listOf(cleanup),
                AndroidFileSyncUploadCleanupStore(File(directory, "state.bin.upload-cleanups"))
                    .read().getValue(pair.id),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun pair() = FileSyncPair(
        id = "pair-1",
        accountId = "account-1",
        localRootId = "content://documents/tree/primary%3ANotes",
        remoteRootPath = "Notes",
        configuration = FileSyncConfiguration(
            deviceLabel = "phone",
            selectedPaths = listOf("Camera"),
            ignoredPatterns = listOf("*.part"),
            priorityRules = listOf(
                FileSyncPriorityRule("**/*.raf"),
                FileSyncPriorityRule("**/*.jpg"),
            ),
        ),
    )

    private fun withTemporaryStore(block: (AndroidFileSyncStore) -> Unit) {
        val directory = Files.createTempDirectory("file-sync-store-").toFile()
        try {
            block(AndroidFileSyncStore(File(directory, "state.bin")))
        } finally {
            directory.deleteRecursively()
        }
    }
}
