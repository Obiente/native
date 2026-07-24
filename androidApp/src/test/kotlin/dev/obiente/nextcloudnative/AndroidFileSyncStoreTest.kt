package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncPair
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
