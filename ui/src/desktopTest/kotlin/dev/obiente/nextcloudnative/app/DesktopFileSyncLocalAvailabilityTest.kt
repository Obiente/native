package dev.obiente.nextcloudnative.app

import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Local scans keep folder sync running when individual Git working-tree items cannot be read. */
class DesktopFileSyncLocalAvailabilityTest {
    @Test
    fun `git metadata with dot folders hidden attributes and extensionless names is scanned`() = withTree { root ->
        writeGitTree(root)
        runCatching { Files.setAttribute(root.resolve(".git"), "dos:hidden", true) }

        val scan = DesktopFileSyncLocalTree(root.toFile()).scan()

        assertEquals(emptyList(), scan.unavailable)
        assertEquals(
            listOf(
                ".git", ".git/COMMIT_EDITMSG", ".git/HEAD", ".git/ORIG_HEAD", ".git/index", ".git/refs",
                ".git/refs/heads", ".git/refs/heads/main", ".gitignore", "README.md",
            ),
            scan.documents.map { it.entry.relativePath },
        )
    }

    @Test
    fun `a git lock file that disappears after listing is reported without stopping the scan`() = withTree { root ->
        writeGitTree(root)
        root.resolve(".git/index.lock").writeText("transient")
        val tree = DesktopFileSyncLocalTree(root.toFile()) { path, _ ->
            if (path.fileName.toString() == "index.lock") {
                Files.delete(path) // Git renamed the lock into place after the directory was listed.
                Files.newByteChannel(path).use { }
            }
            "a".repeat(64)
        }

        val scan = tree.scan()

        assertEquals(
            listOf(FileSyncUnavailableLocalItem(".git/index.lock", FileSyncLocalUnavailableReason.Vanished)),
            scan.unavailable,
        )
        assertTrue(scan.documents.any { it.entry.relativePath == ".git/COMMIT_EDITMSG" })
        assertTrue(scan.documents.none { it.entry.relativePath == ".git/index.lock" })
    }

    @Test
    fun `a git file held open by another process is reported unreadable`() = withTree { root ->
        writeGitTree(root)
        val tree = DesktopFileSyncLocalTree(root.toFile()) { path, _ ->
            if (path.fileName.toString() == "COMMIT_EDITMSG") {
                throw FileSystemException(path.toString(), null, "The file is being used by another process.")
            }
            "b".repeat(64)
        }

        val scan = tree.scan()

        assertEquals(
            listOf(FileSyncUnavailableLocalItem(".git/COMMIT_EDITMSG", FileSyncLocalUnavailableReason.Unreadable)),
            scan.unavailable,
        )
        assertTrue(scan.documents.any { it.entry.relativePath == ".git/HEAD" })
    }

    @Test
    fun `a mandatory byte range lock is reported unreadable on windows`() = withTree { root ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withTree
        writeGitTree(root)
        val index = root.resolve(".git/index").toFile()

        val scan = RandomAccessFile(index, "rw").use { handle ->
            handle.channel.lock().use { DesktopFileSyncLocalTree(root.toFile()).scan() }
        }

        assertEquals(
            listOf(FileSyncUnavailableLocalItem(".git/index", FileSyncLocalUnavailableReason.Unreadable)),
            scan.unavailable,
        )
    }

    @Test
    fun `special files are reported unless an ignore rule excludes them`() = withTree { root ->
        writeGitTree(root)
        val socketPath = root.resolve(".git/fsmonitor--daemon.ipc")
        val socket = runCatching {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socketPath))
        }.getOrNull() ?: return@withTree
        socket.use {
            if (Files.isRegularFile(socketPath)) return@withTree
            val ignoring = FileSyncConfiguration(deviceLabel = "Desktop", ignoredPatterns = listOf("*.ipc"))

            val reported = DesktopFileSyncLocalTree(root.toFile()).scan()
            val ignored = DesktopFileSyncLocalTree(root.toFile()).scan(includes = ignoring::includesSyncPath)

            assertEquals(
                listOf(
                    FileSyncUnavailableLocalItem(
                        ".git/fsmonitor--daemon.ipc",
                        FileSyncLocalUnavailableReason.Unsupported,
                    ),
                ),
                reported.unavailable,
            )
            assertEquals(emptyList(), ignored.unavailable)
        }
        Files.deleteIfExists(socketPath)
    }

    @Test
    fun `an unreadable git folder is reported once without listing its children`() = withTree { root ->
        writeGitTree(root)
        val objects = root.resolve(".git/objects").createDirectories()
        objects.resolve("pack").createDirectories()
        val restricted = runCatching {
            Files.setPosixFilePermissions(objects, PosixFilePermissions.fromString("---------"))
        }.isSuccess
        try {
            // Unsupported on Windows; a privileged user can still list the folder.
            if (!restricted || Files.isReadable(objects)) return@withTree

            val scan = DesktopFileSyncLocalTree(root.toFile()).scan()

            assertEquals(
                listOf(FileSyncUnavailableLocalItem(".git/objects", FileSyncLocalUnavailableReason.Unreadable)),
                scan.unavailable,
            )
            assertTrue(scan.documents.none { it.entry.relativePath.startsWith(".git/objects") })
        } finally {
            if (restricted) Files.setPosixFilePermissions(objects, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `an unreadable sync root still stops the scan`() = withTree { root ->
        val restricted = runCatching {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("---------"))
        }.isSuccess
        try {
            if (!restricted || Files.isReadable(root)) return@withTree

            assertFailsWith<Exception> { DesktopFileSyncLocalTree(root.toFile()).scan() }
        } finally {
            if (restricted) Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `every vanished file is reported individually within the scan entry limit`() = withTree { root ->
        root.resolve("one.txt").writeText("one")
        root.resolve("two.txt").writeText("two")
        val tree = DesktopFileSyncLocalTree(root.toFile(), maximumEntries = 2) { path, _ ->
            Files.delete(path)
            Files.newByteChannel(path).use { }
            "c".repeat(64)
        }

        val scan = tree.scan()

        assertEquals(emptyList(), scan.documents)
        assertEquals(listOf("one.txt", "two.txt"), scan.unavailable.map(FileSyncUnavailableLocalItem::relativePath))
    }

    @Test
    fun `a file rewritten while it is staged for upload is rejected for that item only`() = withTree { root ->
        writeGitTree(root)
        var changeToken = 0
        val tree = DesktopFileSyncLocalTree(root.toFile(), changeTokenProvider = { path ->
            if (path.fileName.toString() == "index") "rewrite-${changeToken++}" else "stable"
        })
        val staged = Files.createTempFile("desktop-sync-stage-", ".tmp").toFile()
        try {
            val failure = assertFailsWith<IllegalArgumentException> {
                tree.stageForUpload(".git/index", staged, maximumBytes = 1024L)
            }
            assertEquals("The local file changed while it was being prepared for upload.", failure.message)
            assertEquals(
                "fix: synthetic change\n".length.toLong(),
                tree.stageForUpload(".git/COMMIT_EDITMSG", staged, maximumBytes = 1024L).size,
            )
        } finally {
            staged.delete()
        }
    }

    private fun writeGitTree(root: Path) {
        root.resolve(".git/refs/heads").createDirectories()
        root.resolve(".git/HEAD").writeText("ref: refs/heads/main\n")
        root.resolve(".git/COMMIT_EDITMSG").writeText("fix: synthetic change\n")
        root.resolve(".git/ORIG_HEAD").writeText("0123456789abcdef0123456789abcdef01234567\n")
        root.resolve(".git/index").writeText("DIRC synthetic index")
        root.resolve(".git/refs/heads/main").writeText("0123456789abcdef0123456789abcdef01234567\n")
        root.resolve(".gitignore").writeText("build/\n")
        root.resolve("README.md").writeText("# Synthetic repository\n")
    }

    private fun withTree(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("desktop-sync-availability-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
