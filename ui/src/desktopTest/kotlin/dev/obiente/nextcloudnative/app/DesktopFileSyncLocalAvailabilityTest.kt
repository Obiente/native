package dev.obiente.nextcloudnative.app

import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private typealias SyncPathFilterForTest = (relativePath: String, kind: SyncEntryKind) -> Boolean

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
        val restore = denyDesktopTestDirectoryListing(objects) ?: return@withTree
        try {
            val scan = DesktopFileSyncLocalTree(root.toFile()).scan()

            assertEquals(
                listOf(FileSyncUnavailableLocalItem(".git/objects", FileSyncLocalUnavailableReason.Unreadable)),
                scan.unavailable,
            )
            assertTrue(scan.documents.none { it.entry.relativePath.startsWith(".git/objects") })
        } finally {
            restore()
        }
    }

    @Test
    fun `an unreadable sync root still stops the scan`() = withTree { root ->
        writeGitTree(root)
        val restore = denyDesktopTestDirectoryListing(root) ?: return@withTree
        try {
            assertFailsWith<Exception> { DesktopFileSyncLocalTree(root.toFile()).scan() }
        } finally {
            restore()
        }
    }

    @Test
    fun `an unreadable interrupted replacement backup stops the scan until it is recoverable`() = withTree { root ->
        val backup = root.resolve("Notes/.today.nextcloud-native-backup-4d6f8828-7d52-4f2d-945b-f46aa4c97b41")
        backup.createDirectories()
        backup.resolve("page.md").writeText("only original")
        val restore = denyDesktopTestDirectoryListing(backup) ?: return@withTree
        try {
            val failure = assertFailsWith<IllegalStateException> { DesktopFileSyncLocalTree(root.toFile()).scan() }
            assertTrue(failure.message.orEmpty().contains("interrupted-replacement item Notes/.today."))
            assertTrue(Files.isDirectory(backup))
        } finally {
            restore()
        }

        val recovered = DesktopFileSyncLocalTree(root.toFile()).scan()

        assertEquals(emptyList(), recovered.unavailable)
        assertTrue(recovered.documents.any { it.entry.relativePath == "Notes/today/page.md" })
        assertEquals("only original", root.resolve("Notes/today/page.md").toFile().readText())
    }

    @Test
    fun `a folder at the walk depth limit stops the scan unless it is ignored`() = withTree { root ->
        val levels = List(64) { "d" }
        val deepest = root.resolve(levels.joinToString("/")).createDirectories()
        deepest.resolve("x.txt").writeText("deep")
        root.resolve("keep.txt").writeText("keep")
        val selected = FileSyncConfiguration(
            deviceLabel = "Desktop",
            selectedPaths = listOf((levels + "x.txt").joinToString("/")),
        )
        val ignored = FileSyncConfiguration(deviceLabel = "Desktop", ignoredPatterns = listOf("d/d/d"))

        listOf<SyncPathFilterForTest>({ _, _ -> true }, selected::includesSyncPath).forEach { includes ->
            val failure = assertFailsWith<IllegalArgumentException> {
                DesktopFileSyncLocalTree(root.toFile()).scan(includes = includes)
            }
            assertTrue(failure.message.orEmpty().contains("nested more than 64 folders deep"))
        }
        val scan = DesktopFileSyncLocalTree(root.toFile()).scan(includes = ignored::includesSyncPath)
        assertEquals(listOf("d", "d/d", "keep.txt"), scan.documents.map { it.entry.relativePath })
        assertEquals(emptyList(), scan.unavailable)
    }

    @Test
    fun `a leaf that vanishes during ancestor validation is reported as unavailable`() = withTree { root ->
        writeGitTree(root)
        val lock = root.resolve(".git/index.lock").apply { writeText("transient") }
        val tree = DesktopFileSyncLocalTree(root.toFile(), changeTokenProvider = { path ->
            if (path == lock) Files.deleteIfExists(path) // Runs after listing, before leaf validation.
            null
        })

        val scan = tree.scan()

        assertEquals(
            listOf(FileSyncUnavailableLocalItem(".git/index.lock", FileSyncLocalUnavailableReason.Vanished)),
            scan.unavailable,
        )
        assertTrue(scan.documents.any { it.entry.relativePath == ".git/HEAD" })
    }

    @Test
    fun `a parent replaced during leaf validation still stops the scan`() = withTree { root ->
        writeGitTree(root)
        val heads = root.resolve(".git/refs/heads")
        val leaf = heads.resolve("main")
        val tree = DesktopFileSyncLocalTree(root.toFile(), changeTokenProvider = { path ->
            if (path == leaf && Files.exists(heads.resolveSibling("heads-original")).not()) {
                Files.move(heads, heads.resolveSibling("heads-original"))
                heads.createDirectories()
                Files.getFileAttributeView(heads, BasicFileAttributeView::class.java)
                    .setTimes(null, null, FileTime.fromMillis(1_000_000_000_000L))
                leaf.writeText("replacement")
            }
            null
        })

        val failure = assertFailsWith<IllegalArgumentException> { tree.scan() }

        assertTrue(failure.message.orEmpty().contains("replaced after it was scanned"))
    }

    @Test
    fun `a leaf replaced by a symbolic link during validation still stops the scan`() = withTree { root ->
        writeGitTree(root)
        val outside = Files.createTempDirectory("desktop-sync-outside-")
        try {
            val target = outside.resolve("secret.txt").apply { writeText("outside") }
            val leaf = root.resolve(".git/ORIG_HEAD")
            var linked = false
            val tree = DesktopFileSyncLocalTree(root.toFile(), changeTokenProvider = { path ->
                if (path == leaf && !linked) {
                    Files.delete(leaf)
                    linked = runCatching { Files.createSymbolicLink(leaf, target) }.isSuccess
                }
                null
            })

            val result = runCatching { tree.scan() }

            if (!linked) return@withTree // Symbolic links need extra privileges on this platform.
            val failure = assertIs<IllegalArgumentException>(result.exceptionOrNull())
            assertTrue(failure.message.orEmpty().contains(".git/ORIG_HEAD is a symbolic link"))
        } finally {
            outside.toFile().deleteRecursively()
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
