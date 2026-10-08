package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer

/** Synthetic Git working trees exercise the complete desktop scan, plan, upload, and verify path. */
class DesktopFileSyncGitRepositoryTest {
    @Test
    fun `a folder with git metadata uploads every regular item and stays stable`() = withRepository { fixture ->
        val result = fixture.run()

        assertIs<FileSyncCenterActionResult.Completed>(result, result.toString())
        assertTrue(fixture.dav.directories().containsAll(listOf("Repo/.git", "Repo/.git/refs/heads")))
        fixture.expectedFiles.forEach { relative ->
            assertContentEquals(
                fixture.local.resolve(relative).readBytes(),
                fixture.dav.files()["Repo/$relative"],
                relative,
            )
        }
        assertEquals(0, fixture.workItems().size)

        val second = fixture.run()

        assertIs<FileSyncCenterActionResult.Completed>(second)
        assertEquals("0 sync operations completed.", second.message)
    }

    @Test
    fun `an unsupported item inside git metadata is reported without stopping the folder`() =
        withRepository { fixture ->
            val socketPath = fixture.local.resolve(".git/fsmonitor--daemon.ipc").toPath()
            val socket = runCatching {
                ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socketPath))
            }.getOrNull() ?: return@withRepository // The platform cannot create a socket file.
            socket.use {
                if (Files.isRegularFile(socketPath)) return@withRepository

                val result = fixture.run()

                assertIs<FileSyncCenterActionResult.Completed>(result, result.toString())
                assertTrue(result.message.contains("1 local item was skipped"), result.message)
                val skipped = fixture.workItems().single()
                assertEquals(".git/fsmonitor--daemon.ipc", skipped.relativePath)
                assertEquals(FileSyncExecutionState.Skipped, skipped.state)
                assertTrue(
                    (skipped.operation as FileSyncOperation.Skipped).reason
                        .contains(".git/fsmonitor--daemon.ipc is not a regular file or folder"),
                )
                assertFalse("Repo/.git/fsmonitor--daemon.ipc" in fixture.dav.files())
                fixture.expectedFiles.forEach { relative -> assertTrue("Repo/$relative" in fixture.dav.files()) }
            }
            Files.deleteIfExists(socketPath)
        }

    @Test
    fun `a git file locked by another handle is skipped and synced once released`() = withRepository { fixture ->
        // Windows byte-range locks are mandatory; other platforms only offer advisory locks.
        if (!System.getProperty("os.name").startsWith("Windows")) return@withRepository
        val lockFile = fixture.local.resolve(".git/index.lock").apply { writeText("pending index") }

        val result = RandomAccessFile(lockFile, "rw").use { handle ->
            handle.channel.lock().use { fixture.run() }
        }

        assertIs<FileSyncCenterActionResult.Completed>(result, result.toString())
        val skipped = fixture.workItems().single()
        assertEquals(".git/index.lock", skipped.relativePath)
        assertTrue((skipped.operation as FileSyncOperation.Skipped).reason.contains("could not be read"))
        assertFalse("Repo/.git/index.lock" in fixture.dav.files())
        fixture.expectedFiles.forEach { relative -> assertTrue("Repo/$relative" in fixture.dav.files()) }

        val released = fixture.run()

        assertIs<FileSyncCenterActionResult.Completed>(released, released.toString())
        assertEquals(0, fixture.workItems().size)
        assertEquals("pending index", fixture.dav.files()["Repo/.git/index.lock"]?.decodeToString())
    }

    @Test
    fun `a git file rewritten during upload keeps the uploaded generation and syncs the rewrite next`() {
        var rewrite: (() -> Unit)? = null
        withRepository(onPut = { path -> if (path == "Repo/.git/COMMIT_EDITMSG") rewrite?.invoke() }) { fixture ->
            val message = fixture.local.resolve(".git/COMMIT_EDITMSG")
            rewrite = {
                rewrite = null
                message.writeText("fix: amended message\n")
            }

            val first = fixture.run()

            assertIs<FileSyncCenterActionResult.Completed>(first, first.toString())
            assertEquals("fix: synthetic change\n", fixture.dav.files()["Repo/.git/COMMIT_EDITMSG"]?.decodeToString())

            val second = fixture.run()

            assertIs<FileSyncCenterActionResult.Completed>(second, second.toString())
            assertEquals("1 sync operation completed.", second.message)
            assertEquals("fix: amended message\n", fixture.dav.files()["Repo/.git/COMMIT_EDITMSG"]?.decodeToString())
        }
    }

    @Test
    fun `an unreadable interrupted replacement backup never propagates a deletion`() = withRepository(
        configuration = FileSyncConfiguration(
            deviceLabel = "Workstation",
            deletionPolicy = FileSyncDeletionPolicy.Propagate,
        ),
    ) { fixture ->
        assertIs<FileSyncCenterActionResult.Completed>(fixture.run())
        // A crash during a folder replacement leaves the only local original in an owned backup.
        val refs = fixture.local.resolve(".git/refs").toPath()
        val backup = refs.resolveSibling(".refs.nextcloud-native-backup-4d6f8828-7d52-4f2d-945b-f46aa4c97b41")
        Files.move(refs, backup)
        val restore = denyDesktopTestDirectoryListing(backup) ?: return@withRepository
        try {
            val blocked = runCatching { fixture.run() }.exceptionOrNull()

            assertIs<IllegalStateException>(blocked, blocked.toString())
            assertFalse("DELETE" in fixture.dav.requestMethods())
            assertTrue("Repo/.git/refs/heads/main" in fixture.dav.files())
        } finally {
            restore()
        }

        val recovered = fixture.run()

        assertIs<FileSyncCenterActionResult.Completed>(recovered, recovered.toString())
        assertEquals("0 sync operations completed.", recovered.message)
        assertTrue(fixture.local.resolve(".git/refs/heads/main").isFile)
        assertFalse("DELETE" in fixture.dav.requestMethods())
    }

    private class Fixture(
        val dav: DesktopFileSyncFakeDav,
        val local: File,
        val expectedFiles: List<String>,
        private val store: DesktopFileSyncStore,
        private val engine: DesktopFileSyncEngine,
        private val session: NextcloudSession,
    ) {
        fun run(): FileSyncCenterActionResult = runBlocking { engine.runPair(session, "alice", PAIR_ID) }

        fun workItems(): List<FileSyncWorkItem> = store.loadPair(PAIR_ID).coordinator.pairs.single().workItems
    }

    private fun withRepository(
        onPut: (String) -> Unit = {},
        configuration: FileSyncConfiguration = FileSyncConfiguration(deviceLabel = "Workstation"),
        block: (Fixture) -> Unit,
    ) {
        val directory = Files.createTempDirectory("desktop-sync-git-").toFile()
        val dav = DesktopFileSyncFakeDav("alice", listOf("Repo"), onPut)
        try {
            MockWebServer().use { server ->
                server.dispatcher = dav
                server.start()
                val local = directory.resolve("Repo").apply { mkdirs() }
                val files = linkedMapOf(
                    "README.md" to "# Synthetic repository\n".encodeToByteArray(),
                    ".gitignore" to "build/\n".encodeToByteArray(),
                    ".git/HEAD" to "ref: refs/heads/main\n".encodeToByteArray(),
                    ".git/COMMIT_EDITMSG" to "fix: synthetic change\n".encodeToByteArray(),
                    ".git/config" to "[core]\n\trepositoryformatversion = 0\n".encodeToByteArray(),
                    ".git/refs/heads/main" to "0123456789abcdef0123456789abcdef01234567\n".encodeToByteArray(),
                    ".git/objects/ab/cdef0123" to ByteArray(512) { index -> (index * 31).toByte() },
                )
                files.forEach { (relative, bytes) ->
                    local.resolve(relative).apply { parentFile.mkdirs() }.writeBytes(bytes)
                }
                markHidden(local.resolve(".git").toPath())
                val session = NextcloudSession(server.url("/").toString(), "alice", "secret")
                val store = DesktopFileSyncStore(directory.resolve("state.db"), legacyStateFile = null)
                store.savePair(
                    DesktopFileSyncPersistedState(
                        coordinator = FileSyncCoordinatorState(
                            listOf(
                                FileSyncPair(
                                    id = PAIR_ID,
                                    accountId = desktopFileCacheAccountId(session),
                                    localRootId = "root",
                                    remoteRootPath = "Repo",
                                    configuration = configuration,
                                ),
                            ),
                        ),
                        roots = listOf(DesktopFileSyncRootRecord("root", local.absolutePath, "Repo")),
                    ),
                    PAIR_ID,
                )
                val engine = DesktopFileSyncEngine(store, directory.resolve("staging"))
                block(Fixture(dav, local, files.keys.toList(), store, engine, session))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Git for Windows marks `.git` hidden; the attribute must not change what syncs. */
    private fun markHidden(path: Path) {
        runCatching { Files.setAttribute(path, "dos:hidden", true) }
    }

    private companion object {
        const val PAIR_ID = "pair"
    }
}
