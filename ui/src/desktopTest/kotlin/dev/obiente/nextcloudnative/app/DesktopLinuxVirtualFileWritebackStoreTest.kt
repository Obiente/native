package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class DesktopLinuxVirtualFileWritebackStoreTest {
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

    private class FakeWritebackRemote(
        var content: ByteArray,
        var etag: String,
    ) : LinuxVirtualWritebackRemote {
        val expectedRevisions = mutableListOf<String?>()
        var failWrites = false

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
            return entry(relativePath)
        }

        private fun entry(path: String) = RemoteSyncEntry(path, SyncEntryKind.File, etag, content.size.toLong())
    }
}
