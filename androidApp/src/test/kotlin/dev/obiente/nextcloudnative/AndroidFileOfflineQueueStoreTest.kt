package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileOfflineDescriptor
import dev.obiente.nextcloudnative.app.FileOfflineJobResult
import dev.obiente.nextcloudnative.app.FileOfflineKey
import dev.obiente.nextcloudnative.app.FileOfflineQueueState
import dev.obiente.nextcloudnative.app.FileOfflineRequest
import dev.obiente.nextcloudnative.app.markFileOfflineJobRunning
import dev.obiente.nextcloudnative.app.planFileOfflineRequest
import dev.obiente.nextcloudnative.app.recordFileOfflineJobResult
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidFileOfflineQueueStoreTest {
    @Test
    fun missingStoreLoadsEmptyAndRoundTripsTypedQueueState() = withStore { store, stateFile ->
        assertEquals(AndroidFileOfflinePersistedState(), store.load())
        val queued = planFileOfflineRequest(FileOfflineQueueState(), pin(), 10)
        val running = markFileOfflineJobRunning(queued, queued.jobs.single().id)
        val waiting = recordFileOfflineJobResult(
            running,
            running.jobs.single().id,
            FileOfflineJobResult.RetryableFailure("offline"),
            20,
        )

        val persisted = AndroidFileOfflinePersistedState(
            queue = waiting,
            folders = AndroidOfflineFolderState(
                directPins = setOf(waiting.records.single().descriptor.key),
                roots = listOf(
                    AndroidOfflineFolderRoot(
                        accountId = "account-a",
                        rootPath = "Notes",
                        rootDisplayName = "Notes",
                        directories = listOf(
                            AndroidOfflineDirectory(
                                path = "Notes",
                                displayName = "Notes",
                                remoteEtag = "\"folder-1\"",
                                lastModified = "today",
                                fileId = 7,
                            ),
                        ),
                        filePaths = listOf("Notes/vault.md"),
                    ),
                ),
            ),
        )
        store.save(persisted)

        assertEquals(persisted, store.load())
        assertFalse(stateFile.parentFile?.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun truncatedOrUnknownVersionIsRejectedInsteadOfSilentlyDiscarded() = withStore { store, stateFile ->
        stateFile.toPath().writeBytes(byteArrayOf(0x4e, 0x43, 0x4f))
        assertFailsWith<OfflineQueueStoreException> { store.load() }

        stateFile.toPath().writeBytes(
            ByteBuffer.allocate(16)
                .putInt(0x4e434f46)
                .putInt(99)
                .putLong(1L)
                .array(),
        )
        assertFailsWith<OfflineQueueStoreException> { store.load() }
    }

    @Test
    fun versionOneStateMigratesExistingPinsToExplicitDirectPins() = withStore { store, stateFile ->
        val bytes = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x4e434f46)
                output.writeInt(1)
                output.writeLong(2)
                output.writeInt(1)
                output.writeStoredTestString("account-a")
                output.writeStoredTestString("Notes/vault.md")
                output.writeStoredTestString("vault.md")
                output.writeStoredTestString("\"remote-1\"")
                output.writeBoolean(true)
                output.writeLong(42)
                output.writeBoolean(true)
                output.writeStoredTestString("text/markdown")
                output.writeStoredTestString("Pinned")
                output.writeBoolean(false)
                output.writeBoolean(false)
                output.writeBoolean(false)
                output.writeLong(10)
                output.writeInt(0)
            }
        }.toByteArray()
        stateFile.writeBytes(bytes)

        val restored = store.load()

        assertEquals(
            setOf(FileOfflineKey("account-a", "Notes/vault.md")),
            restored.folders.directPins,
        )
        assertTrue(restored.folders.roots.isEmpty())
    }

    @Test
    fun failedOversizeSaveKeepsPreviouslyPublishedGeneration() = withStore { store, _ ->
        val original = planFileOfflineRequest(FileOfflineQueueState(), pin(), 10)
        val persistedOriginal = AndroidFileOfflinePersistedState(queue = original)
        store.save(persistedOriginal)
        val hugeKey = FileOfflineKey("account-a", "a".repeat(17 * 1024))
        val oversized = planFileOfflineRequest(
            original,
            pin(hugeKey),
            20,
        )

        assertFailsWith<OfflineQueueStoreException> {
            store.save(AndroidFileOfflinePersistedState(queue = oversized))
        }
        assertEquals(persistedOriginal, store.load())
    }

    private fun pin(key: FileOfflineKey = FileOfflineKey("account-a", "Notes/vault.md")) =
        FileOfflineRequest.Pin(
            descriptor = FileOfflineDescriptor(
                key = key,
                displayName = "vault.md",
                remoteEtag = "\"remote-1\"",
                size = 42,
                mimeType = "text/markdown",
            ),
            observedLocalRevision = null,
        )

    private fun withStore(block: (AndroidFileOfflineQueueStore, File) -> Unit) {
        val directory = Files.createTempDirectory("ncn-offline-store-").toFile()
        try {
            val stateFile = File(directory, "queue.bin")
            block(AndroidFileOfflineQueueStore.forTesting(stateFile), stateFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun DataOutputStream.writeStoredTestString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
