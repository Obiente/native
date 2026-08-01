package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopLinuxVirtualFileWritebackStoreTest {
    @Test
    fun `writeback capacity preserves the configured free space reserve`() {
        assertTrue(linuxWritebackFitsCapacity(remoteBytes = 40L, availableBytes = 140L, reserveBytes = 100L))
        assertFalse(linuxWritebackFitsCapacity(remoteBytes = 41L, availableBytes = 140L, reserveBytes = 100L))
        assertFalse(linuxWritebackFitsCapacity(remoteBytes = Long.MAX_VALUE, availableBytes = Long.MAX_VALUE, reserveBytes = 1L))
    }

    @Test
    fun `existing file edits are staged and committed with the scanned revision`() {
        val directory = Files.createTempDirectory("linux-writeback-").toFile()
        try {
            val remote = FakeWritebackRemote("before".encodeToByteArray(), "etag-1")
            val store = DesktopLinuxVirtualFileWritebackStore(directory)
            val node = LinuxVirtualFileNode("Notes/today.txt", "today.txt", false, 6L, "etag-1")
            val handle = store.open("Notes/today.txt", node, truncate = false, tree = remote) {}

            handle.write(0L, "after!".encodeToByteArray())
            handle.flush()
            handle.close()

            assertContentEquals("after!".encodeToByteArray(), remote.content)
            assertEquals(listOf<String?>("etag-1"), remote.expectedRevisions)
            assertEquals(emptyList<DesktopLinuxPendingWriteback>(), store.pendingWritebacks())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failed conflict-safe upload remains recoverable after close`() {
        val directory = Files.createTempDirectory("linux-writeback-").toFile()
        try {
            val remote = FakeWritebackRemote("before".encodeToByteArray(), "etag-1").apply {
                failWrites = true
            }
            val store = DesktopLinuxVirtualFileWritebackStore(directory)
            val node = LinuxVirtualFileNode("Notes/today.txt", "today.txt", false, 6L, "etag-1")
            val handle = store.open("Notes/today.txt", node, truncate = false, tree = remote) {}
            handle.write(0L, "local!".encodeToByteArray())

            assertFails { handle.flush() }
            assertFails { handle.close() }
            assertEquals(
                listOf(
                    DesktopLinuxPendingWriteback(
                        "Notes/today.txt",
                        "etag-1",
                        6L,
                        store.pendingWritebacks().single().stagedAtEpochMillis,
                        dirty = true,
                    ),
                ),
                store.pendingWritebacks(),
            )
            remote.failWrites = false
            assertEquals(
                DesktopLinuxWritebackRecoveryResult(recoveredCount = 1, retainedCount = 0),
                store.recoverPending(remote) {},
            )
            assertContentEquals("local!".encodeToByteArray(), remote.content)
            assertEquals(emptyList(), store.pendingWritebacks())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `recovery accepts exact remote bytes after a lost write response`() {
        val directory = Files.createTempDirectory("linux-writeback-lost-response-").toFile()
        try {
            val remote = FakeWritebackRemote("before".encodeToByteArray(), "etag-1").apply {
                failAfterWrite = true
            }
            val store = DesktopLinuxVirtualFileWritebackStore(directory)
            val node = LinuxVirtualFileNode("Notes/today.txt", "today.txt", false, 6L, "etag-1")
            val handle = store.open("Notes/today.txt", node, truncate = false, tree = remote) {}
            handle.write(0L, "saved!".encodeToByteArray())

            assertFails { handle.close() }
            assertEquals(1, store.pendingWritebacks().size)
            remote.failAfterWrite = false

            assertEquals(
                DesktopLinuxWritebackRecoveryResult(recoveredCount = 1, retainedCount = 0),
                store.recoverPending(remote) {},
            )
            assertContentEquals("saved!".encodeToByteArray(), remote.content)
            assertEquals(emptyList(), store.pendingWritebacks())
        } finally {
            directory.deleteRecursively()
        }
    }

    private class FakeWritebackRemote(
        var content: ByteArray,
        var etag: String,
    ) : LinuxVirtualWritebackRemote {
        val expectedRevisions = mutableListOf<String?>()
        var failWrites = false
        var failAfterWrite = false

        override fun resolveFile(relativePath: String): RemoteSyncEntry? = entry(relativePath)

        override fun stageDownload(
            relativePath: String,
            expectedRemoteEtag: String,
            destination: File,
            maximumBytes: Long,
        ): RemoteSyncEntry {
            require(expectedRemoteEtag == etag)
            destination.writeBytes(content)
            return entry(relativePath)
        }

        override fun writeFile(
            relativePath: String,
            source: File,
            expectedRemoteEtag: String?,
        ): RemoteSyncEntry {
            expectedRevisions += expectedRemoteEtag
            if (failWrites) error("Simulated ETag conflict")
            require(expectedRemoteEtag == etag)
            content = source.readBytes()
            etag = "etag-${expectedRevisions.size + 1}"
            if (failAfterWrite) error("Simulated lost write response")
            return entry(relativePath)
        }

        private fun entry(path: String) = RemoteSyncEntry(path, SyncEntryKind.File, etag, content.size.toLong())
    }
}
