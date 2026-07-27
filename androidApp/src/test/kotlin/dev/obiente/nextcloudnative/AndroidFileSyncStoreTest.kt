package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncPair
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

    private fun pair() = FileSyncPair(
        id = "pair-1",
        accountId = "account-1",
        localRootId = "content://documents/tree/primary%3ANotes",
        remoteRootPath = "Notes",
        configuration = FileSyncConfiguration(deviceLabel = "phone"),
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
