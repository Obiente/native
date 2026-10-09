package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Local content verification isolates changed generations but never unsafe folder hierarchies. */
class DesktopFileSyncContentSliceSafetyTest {
    @Test
    fun `a git index rewritten after the scan leaves its candidate unverified`() = withScannedIndex { root, tree, slice, hash ->
        root.resolve(".git/refs/heads/main").writeText("DIRC rewritten") // Same size, new generation.

        assertNull(verifyDesktopFileSyncContentSlice(slice, tree, unusedRemote(), hash) { true })
    }

    @Test
    fun `a parent folder replaced after the scan still stops verification`() = withScannedIndex { root, tree, slice, hash ->
        val heads = root.resolve(".git/refs/heads")
        Files.move(heads, heads.resolveSibling("heads-original"))
        heads.createDirectories()
        Files.getFileAttributeView(heads, BasicFileAttributeView::class.java)
            .setTimes(null, null, FileTime.fromMillis(1_000_000_000_000L))
        heads.resolve("main").writeText("DIRC synthetic")

        val failure = assertFailsWith<IllegalArgumentException> {
            verifyDesktopFileSyncContentSlice(slice, tree, unusedRemote(), hash) { true }
        }

        assertFalse(failure is DesktopFileSyncLocalRevisionChangedException, failure.message)
    }

    private fun withScannedIndex(
        block: (Path, DesktopFileSyncLocalTree, FileSyncContentVerificationSlice, String) -> Unit,
    ) {
        val root = Files.createTempDirectory("desktop-sync-slice-safety-")
        try {
            root.resolve(".git/refs/heads").createDirectories()
            root.resolve(".git/refs/heads/main").writeText("DIRC synthetic")
            val tree = DesktopFileSyncLocalTree(root.toFile())
            val scanned = tree.scan().documents.single { it.entry.relativePath == ".git/refs/heads/main" }.entry
            val size = requireNotNull(scanned.size)
            val candidate = FileSyncContentVerificationCandidate(scanned.relativePath, scanned.revision, "etag", size)
            val slice = FileSyncContentVerificationSlice(
                candidate,
                offset = 0L,
                length = size.toInt(),
                aggregateHash = EMPTY_FILE_SYNC_IDENTITY_AGGREGATE,
            )
            block(root, tree, slice, requireNotNull(scanned.contentHash))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /** Both scenarios end before any server read; a request here would fail the test. */
    private fun unusedRemote() = DesktopFileSyncRemoteTree(
        NextcloudSession("https://cloud.example.test", "alice", "secret"),
        "alice",
        "Repo",
        okhttp3.OkHttpClient.Builder().addInterceptor { error("Unexpected server request") }.build(),
    )
}
