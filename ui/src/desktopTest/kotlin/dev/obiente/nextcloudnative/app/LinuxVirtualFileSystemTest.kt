package dev.obiente.nextcloudnative.app

import jnr.ffi.Runtime
import jnr.ffi.Pointer
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import ru.serce.jnrfuse.ErrorCodes
import ru.serce.jnrfuse.struct.FileStat
import ru.serce.jnrfuse.struct.FuseFileInfo

class LinuxVirtualFileSystemTest {
    @Test
    fun `own fuse connection is resolved from its exact escaped mount point`() {
        val mountInfo = """
            624 73 0:94 / /mnt/Other rw - fuse.rclone remote: rw
            712 73 0:115 / /mnt/Nextcloud\040Native rw - fuse nextcloud-native rw
            713 73 0:116 / /mnt/Nextcloud\040Native rw - fuse.jnrfuse another-app rw
        """.trimIndent()

        assertEquals(
            115,
            linuxFuseConnectionIdForMount(Path.of("/mnt/Nextcloud Native"), mountInfo),
        )
        assertNull(linuxFuseConnectionIdForMount(Path.of("/mnt/Missing"), mountInfo))
    }

    @Test
    fun largeDirectoryMetadataUsesAnAdaptiveFreshnessWindow() {
        assertEquals(5_000L, linuxVirtualMetadataFreshnessMillis(128, 5_000L))
        assertEquals(30_000L, linuxVirtualMetadataFreshnessMillis(2_000, 5_000L))
        assertEquals(300_000L, linuxVirtualMetadataFreshnessMillis(9_520, 5_000L))
        assertEquals(900_000L, linuxVirtualMetadataFreshnessMillis(20_000, 5_000L))
        assertEquals(Long.MAX_VALUE, linuxVirtualMetadataFreshnessMillis(20_000, Long.MAX_VALUE))
    }

    @Test
    fun virtualInodesAreStableAndPathSpecific() {
        val first = stableLinuxVirtualInode("Photos/Camera/frame-0001.raf")

        assertTrue(first > 1L)
        assertEquals(first, stableLinuxVirtualInode("Photos/Camera/frame-0001.raf"))
        assertFalse(first == stableLinuxVirtualInode("Photos/Camera/frame-0002.raf"))
    }

    @Before
    fun requireLinux() {
        assumeTrue(
            "The jnr-fuse adapter requires a Linux libfuse runtime.",
            System.getProperty("os.name").startsWith("Linux", ignoreCase = true),
        )
    }

    @Test
    fun `open read seek and release use one generation pinned handle`() {
        val bytes = "nextcloud virtual file".encodeToByteArray()
        var opened = 0
        var closed = 0
        val backend = fixtureBackend(bytes) { opened += 1; { closed += 1 } }
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        val runtime = Runtime.getSystemRuntime()
        val fileInfo = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val output = runtime.memoryManager.allocateDirect(8)

        assertEquals(0, fileSystem.open("/Photos/example.raf", fileInfo))
        assertEquals(1, opened)
        assertEquals(7, fileSystem.read("/Photos/example.raf", output, 7L, 10L, fileInfo))
        val read = ByteArray(7).also { destination -> output.get(0L, destination, 0, destination.size) }
        assertContentEquals(bytes.copyOfRange(10, 17), read)
        assertEquals(0, fileSystem.release("/Photos/example.raf", fileInfo))
        assertEquals(1, closed)
    }

    @Test
    fun `failed detach closes open handles and retains the backend for retry`() {
        var handleClosed = 0
        var backendClosed = 0
        val fixture = fixtureBackend("content".encodeToByteArray()) { { handleClosed += 1 } }
        val backend = object : LinuxVirtualFileBackend by fixture {
            override fun close() {
                backendClosed += 1
            }
        }
        val fileSystem = LinuxNextcloudVirtualFileSystem(
            backend = backend,
            unmountOperation = { error("Simulated detach failure") },
        )
        val fileInfo = FuseFileInfo.of(Runtime.getSystemRuntime().memoryManager.allocateDirect(256))
        assertEquals(0, fileSystem.open("/Photos/example.raf", fileInfo))

        assertFails { fileSystem.unmount() }

        assertEquals(1, handleClosed)
        assertEquals(0, backendClosed)
        val output = Runtime.getSystemRuntime().memoryManager.allocateDirect(1)
        assertEquals(-ErrorCodes.EBADF(), fileSystem.read("/Photos/example.raf", output, 1L, 0L, fileInfo))
    }

    @Test
    fun `metadata is visible without hydrating file content`() {
        var opened = 0
        val backend = fixtureBackend("content".encodeToByteArray()) { opened += 1; {} }
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        val stat = FileStat(Runtime.getSystemRuntime())

        assertEquals(0, fileSystem.getattr("/Photos/example.raf", stat))
        assertEquals(7L, stat.st_size.longValue())
        assertTrue(FileStat.S_ISREG(stat.st_mode.intValue()))
        assertEquals(0, opened)
        assertEquals(-ErrorCodes.EINVAL(), fileSystem.getattr("/Photos/../secret", stat))
    }

    @Test
    fun `metadata ownership is fixed to the process that creates the mount`() {
        val fileSystem = LinuxNextcloudVirtualFileSystem(
            backend = fixtureBackend("content".encodeToByteArray()) { {} },
            mountOwnerUid = 2_001L,
            mountOwnerGid = 2_002L,
        )
        val stat = FileStat(Runtime.getSystemRuntime())

        assertEquals(0, fileSystem.getattr("/Photos/example.raf", stat))

        assertEquals(2_001L, stat.st_uid.longValue())
        assertEquals(2_002L, stat.st_gid.longValue())
    }

    @Test
    fun `created files stage random writes and become remote on flush`() {
        val backend = MutableFixtureBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        val runtime = Runtime.getSystemRuntime()
        val fileInfo = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val input = runtime.memoryManager.allocateDirect(8)
        input.put(0L, "RAF-data".encodeToByteArray(), 0, 8)

        assertEquals(0, fileSystem.create("/Photos/new.raf", 0L, fileInfo))
        assertEquals(8, fileSystem.write("/Photos/new.raf", input, 8L, 0L, fileInfo))
        assertEquals(0, fileSystem.getattr("/Photos/new.raf", FileStat(runtime)))
        assertEquals(null, backend.resolve("Photos/new.raf"))

        assertEquals(0, fileSystem.fsync("/Photos/new.raf", 0, fileInfo))
        assertContentEquals("RAF-data".encodeToByteArray(), backend.fileBytes("Photos/new.raf"))
        assertEquals(0, fileSystem.release("/Photos/new.raf", fileInfo))
    }

    @Test
    fun `pending created file can be reopened with an independent read handle`() {
        val backend = MutableFixtureBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        val runtime = Runtime.getSystemRuntime()
        val creator = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val reader = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val input = runtime.memoryManager.allocateDirect(5)
        input.put(0L, "draft".encodeToByteArray(), 0, 5)

        assertEquals(0, fileSystem.create("/Photos/draft.txt", 0L, creator))
        assertEquals(5, fileSystem.write("/Photos/draft.txt", input, 5L, 0L, creator))
        assertEquals(0, fileSystem.access("/Photos/draft.txt", 0))
        assertEquals(0, fileSystem.open("/Photos/draft.txt", reader))
        val output = runtime.memoryManager.allocateDirect(5)
        assertEquals(5, fileSystem.read("/Photos/draft.txt", output, 5L, 0L, reader))
        assertContentEquals(
            "draft".encodeToByteArray(),
            ByteArray(5).also { bytes -> output.get(0L, bytes, 0, bytes.size) },
        )

        assertEquals(0, fileSystem.release("/Photos/draft.txt", creator))
        assertEquals(null, backend.resolve("Photos/draft.txt"))
        assertEquals(0, fileSystem.release("/Photos/draft.txt", reader))
        assertContentEquals("draft".encodeToByteArray(), backend.fileBytes("Photos/draft.txt"))
    }

    @Test
    fun `directory and namespace mutations validate parents`() {
        val backend = MutableFixtureBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)

        assertEquals(-ErrorCodes.ENOENT(), fileSystem.mkdir("/Missing/Child", 0L))
        assertEquals(0, fileSystem.mkdir("/Photos/Trips", 0L))
        assertEquals(0, fileSystem.rename("/Photos/Trips", "/Archive"))
        assertTrue(backend.resolve("Archive")?.directory == true)
        assertEquals(0, fileSystem.rmdir("/Archive"))
        assertEquals(null, backend.resolve("Archive"))
    }

    @Test
    fun `rename atomically replaces an existing file for editor save workflows`() {
        val backend = MutableFixtureBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        backend.addFile("Photos/.notes.txt.tmp", "new".encodeToByteArray())
        backend.addFile("Photos/notes.txt", "old".encodeToByteArray())

        assertEquals(0, fileSystem.rename("/Photos/.notes.txt.tmp", "/Photos/notes.txt"))
        assertEquals(null, backend.resolve("Photos/.notes.txt.tmp"))
        assertContentEquals("new".encodeToByteArray(), backend.fileBytes("Photos/notes.txt"))
    }

    @Test
    fun `open read handle follows a remote rename until release`() {
        val backend = MutableFixtureBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        backend.addFile("Photos/open.txt", "read after rename".encodeToByteArray())
        val runtime = Runtime.getSystemRuntime()
        val fileInfo = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))

        assertEquals(0, fileSystem.open("/Photos/open.txt", fileInfo))
        assertEquals(0, fileSystem.rename("/Photos/open.txt", "/Photos/renamed.txt"))
        val output = runtime.memoryManager.allocateDirect(17)
        assertEquals(17, fileSystem.read("/Photos/renamed.txt", output, 17L, 0L, fileInfo))
        assertContentEquals(
            "read after rename".encodeToByteArray(),
            ByteArray(17).also { bytes -> output.get(0L, bytes, 0, bytes.size) },
        )
        assertEquals(0, fileSystem.release("/Photos/renamed.txt", fileInfo))
    }

    @Test
    fun `rename replacement waits for an open destination generation`() {
        val backend = MutableFixtureBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        backend.addFile("Photos/replacement.txt", "new".encodeToByteArray())
        backend.addFile("Photos/open.txt", "old generation".encodeToByteArray())
        val fileInfo = FuseFileInfo.of(Runtime.getSystemRuntime().memoryManager.allocateDirect(256))

        assertEquals(0, fileSystem.open("/Photos/open.txt", fileInfo))
        assertEquals(
            -ErrorCodes.EBUSY(),
            fileSystem.rename("/Photos/replacement.txt", "/Photos/open.txt"),
        )
        assertContentEquals("old generation".encodeToByteArray(), backend.fileBytes("Photos/open.txt"))
        assertEquals(0, fileSystem.release("/Photos/open.txt", fileInfo))
        assertEquals(0, fileSystem.rename("/Photos/replacement.txt", "/Photos/open.txt"))
        assertContentEquals("new".encodeToByteArray(), backend.fileBytes("Photos/open.txt"))
    }

    @Test
    fun `unlink waits for an open read handle instead of acknowledging a volatile delete`() {
        val backend = MutableFixtureBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        backend.addFile("Photos/open.txt", "read after unlink".encodeToByteArray())
        val runtime = Runtime.getSystemRuntime()
        val fileInfo = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))

        assertEquals(0, fileSystem.open("/Photos/open.txt", fileInfo))
        assertEquals(-ErrorCodes.EBUSY(), fileSystem.unlink("/Photos/open.txt"))
        assertEquals(0, fileSystem.getattr("/Photos/open.txt", FileStat(runtime)))
        assertTrue(backend.resolve("Photos/open.txt") != null)
        val output = runtime.memoryManager.allocateDirect(17)
        assertEquals(17, fileSystem.read("/Photos/open.txt", output, 17L, 0L, fileInfo))
        assertContentEquals(
            "read after unlink".encodeToByteArray(),
            ByteArray(17).also { bytes -> output.get(0L, bytes, 0, bytes.size) },
        )

        assertEquals(0, fileSystem.release("/Photos/open.txt", fileInfo))
        assertEquals(0, fileSystem.unlink("/Photos/open.txt"))
        assertEquals(null, backend.resolve("Photos/open.txt"))
    }

    @Test
    fun `unlink rejects a file with an open writeback handle`() {
        val backend = MutableFixtureBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        backend.addFile("Photos/open.txt", "pending edit".encodeToByteArray())
        val fileInfo = FuseFileInfo.of(Runtime.getSystemRuntime().memoryManager.allocateDirect(256)).apply {
            flags.set(1L)
        }

        assertEquals(0, fileSystem.open("/Photos/open.txt", fileInfo))
        assertEquals(-ErrorCodes.EBUSY(), fileSystem.unlink("/Photos/open.txt"))
        assertTrue(backend.resolve("Photos/open.txt") != null)
        assertEquals(0, fileSystem.release("/Photos/open.txt", fileInfo))
        assertEquals(0, fileSystem.unlink("/Photos/open.txt"))
        assertEquals(null, backend.resolve("Photos/open.txt"))
    }

    @Test
    fun `parent rename remains available after an open child is safely removed`() {
        val backend = MutableFixtureBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        backend.createDirectory("Photos/Working")
        backend.addFile("Photos/Working/open.txt", "pending delete".encodeToByteArray())
        val fileInfo = FuseFileInfo.of(Runtime.getSystemRuntime().memoryManager.allocateDirect(256))

        assertEquals(0, fileSystem.open("/Photos/Working/open.txt", fileInfo))
        assertEquals(-ErrorCodes.EBUSY(), fileSystem.unlink("/Photos/Working/open.txt"))
        assertEquals(0, fileSystem.release("/Photos/Working/open.txt", fileInfo))
        assertEquals(0, fileSystem.unlink("/Photos/Working/open.txt"))
        assertEquals(null, backend.resolve("Photos/Working/open.txt"))
        assertEquals(0, fileSystem.rename("/Photos/Working", "/Photos/Renamed"))
    }

    @Test
    fun `rename refuses a directory containing an open write handle`() {
        val backend = MutableFixtureBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        backend.createDirectory("Photos/Working")
        backend.addFile("Photos/Working/notes.txt", "draft".encodeToByteArray())
        val fileInfo = FuseFileInfo.of(Runtime.getSystemRuntime().memoryManager.allocateDirect(256)).apply {
            flags.set(1L)
        }

        assertEquals(0, fileSystem.open("/Photos/Working/notes.txt", fileInfo))
        assertEquals(-ErrorCodes.EBUSY(), fileSystem.rename("/Photos/Working", "/Photos/Renamed"))
        assertEquals(0, fileSystem.release("/Photos/Working/notes.txt", fileInfo))
        assertEquals(0, fileSystem.rename("/Photos/Working", "/Photos/Renamed"))
    }

    @Test
    fun `rmdir refuses a non empty remote directory`() {
        val backend = MutableFixtureBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        backend.createDirectory("Photos/Trips")
        backend.addFile("Photos/Trips/photo.raf", "raw".encodeToByteArray())

        assertEquals(-ErrorCodes.ENOTEMPTY(), fileSystem.rmdir("/Photos/Trips"))
        assertTrue(backend.resolve("Photos/Trips/photo.raf") != null)
    }

    @Test
    fun `destructive directory operations ignore a stale cached empty listing`() {
        val fixture = MutableFixtureBackend().apply {
            createDirectory("Photos/Trips")
            addFile("Photos/Trips/new.raf", byteArrayOf(1))
            createDirectory("Photos/Replacement")
            addFile("Photos/Replacement/new.raf", byteArrayOf(2))
            createDirectory("Photos/Source")
        }
        val backend = object : LinuxVirtualFileBackend by fixture {
            override fun list(path: String): List<LinuxVirtualFileNode> =
                if (path.trim('/') in setOf("Photos/Trips", "Photos/Replacement")) emptyList()
                else fixture.list(path)

            override fun isDirectoryEmpty(node: LinuxVirtualFileNode): Boolean =
                fixture.list(node.path).isEmpty()
        }
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)

        assertEquals(-ErrorCodes.ENOTEMPTY(), fileSystem.rmdir("/Photos/Trips"))
        assertEquals(
            -ErrorCodes.ENOTEMPTY(),
            fileSystem.rename("/Photos/Source", "/Photos/Replacement"),
        )
        assertNotNull(fixture.resolve("Photos/Trips/new.raf"))
        assertNotNull(fixture.resolve("Photos/Replacement/new.raf"))
    }

    @Test
    fun `readdir resumes from the supplied continuation offset`() {
        val backend = MutableFixtureBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        backend.addFile("Photos/a.raf", byteArrayOf(1))
        backend.addFile("Photos/b.raf", byteArrayOf(2))
        val runtime = Runtime.getSystemRuntime()
        val buffer = runtime.memoryManager.allocateDirect(8)
        val fileInfo = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val firstPage = mutableListOf<Pair<String, Long>>()
        val firstFiller = ru.serce.jnrfuse.FuseFillDir { _: Pointer, name: ByteBuffer, _, nextOffset: Long ->
            if (firstPage.size == 2) return@FuseFillDir 1
            firstPage += name.fuseName() to nextOffset
            0
        }

        assertEquals(0, fileSystem.readdir("/Photos", buffer, firstFiller, 0L, fileInfo))
        val secondPage = mutableListOf<String>()
        val secondFiller = ru.serce.jnrfuse.FuseFillDir { _: Pointer, name: ByteBuffer, _, _ ->
            secondPage += name.fuseName()
            0
        }
        assertEquals(
            0,
            fileSystem.readdir("/Photos", buffer, secondFiller, firstPage.last().second, fileInfo),
        )
        assertEquals(listOf(".", ".."), firstPage.map { it.first })
        assertEquals(listOf("a.raf", "b.raf"), secondPage)
    }

    @Test
    fun `large readdir keeps one stable snapshot across all continuation buffers`() {
        val backend = MutableFixtureBackend()
        repeat(12_000) { index ->
            backend.addFile("Photos/item-${index.toString().padStart(5, '0')}.dat", byteArrayOf(1))
        }
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        val runtime = Runtime.getSystemRuntime()
        val buffer = runtime.memoryManager.allocateDirect(8)
        val fileInfo = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val names = mutableListOf<String>()
        var offset = 0L

        while (names.size < 12_002) {
            val page = mutableListOf<Pair<String, Long>>()
            val filler = ru.serce.jnrfuse.FuseFillDir { _: Pointer, name: ByteBuffer, stat, nextOffset: Long ->
                if (page.size == 257) return@FuseFillDir 1
                page += name.fuseName() to nextOffset
                if (page.last().first !in setOf(".", "..")) assertNotNull(stat)
                0
            }
            assertEquals(0, fileSystem.readdir("/Photos", buffer, filler, offset, fileInfo))
            assertTrue(page.isNotEmpty())
            names += page.map { it.first }
            offset = page.last().second
        }

        assertEquals(12_002, names.distinct().size)
        assertEquals(1, backend.listCallCount("Photos"))
        assertEquals(0, fileSystem.releasedir("/Photos", fileInfo))
    }

    @Test
    fun `cached parent snapshot prevents one remote listing per child getattr`() {
        val delegate = MutableFixtureBackend()
        repeat(10_000) { index ->
            delegate.addFile("Photos/item-${index.toString().padStart(5, '0')}.dat", byteArrayOf(1))
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = MemoryLinuxVirtualMetadataStore(),
            freshForMillis = Long.MAX_VALUE,
        )
        val fileSystem = LinuxNextcloudVirtualFileSystem(cached)
        val runtime = Runtime.getSystemRuntime()
        val buffer = runtime.memoryManager.allocateDirect(8)
        val fileInfo = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val names = mutableListOf<String>()
        val filler = ru.serce.jnrfuse.FuseFillDir { _: Pointer, name: ByteBuffer, _, _ ->
            name.fuseName().takeUnless { it == "." || it == ".." }?.let(names::add)
            0
        }

        assertEquals(0, fileSystem.opendir("/Photos", fileInfo))
        assertEquals(0, fileSystem.readdir("/Photos", buffer, filler, 0L, fileInfo))
        assertEquals(10_000, names.size)
        names.forEach { name ->
            assertEquals(0, fileSystem.getattr("/Photos/$name", FileStat(runtime)))
        }

        assertEquals(1, delegate.listCallCount(""))
        assertEquals(1, delegate.listCallCount("Photos"))
        assertEquals(0, fileSystem.releasedir("/Photos", fileInfo))
        cached.close()
    }

    @Test
    fun `open directory handles share one bounded entry budget`() {
        val backend = MutableFixtureBackend().apply {
            addFile("Photos/a.dat", byteArrayOf(1))
            addFile("Photos/b.dat", byteArrayOf(2))
        }
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend, maximumOpenDirectoryEntries = 4)
        val runtime = Runtime.getSystemRuntime()
        val first = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val second = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))

        assertEquals(0, fileSystem.opendir("/Photos", first))
        assertEquals(-ErrorCodes.ENOMEM(), fileSystem.opendir("/Photos", second))
        assertEquals(0, fileSystem.releasedir("/Photos", first))
        assertEquals(0, fileSystem.opendir("/Photos", second))
        assertEquals(0, fileSystem.releasedir("/Photos", second))
    }

    @Test
    fun `slow directory materialization does not block an unrelated open`() {
        val fixture = MutableFixtureBackend().apply { createDirectory("Slow") }
        val backend = BlockingListBackend(fixture, "Slow")
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        val runtime = Runtime.getSystemRuntime()
        val slow = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val fast = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val workers = Executors.newFixedThreadPool(2)
        try {
            val slowOpen = workers.submit<Int> { fileSystem.opendir("/Slow", slow) }
            assertTrue(backend.started.await(2L, TimeUnit.SECONDS))

            assertEquals(0, workers.submit<Int> { fileSystem.opendir("/Photos", fast) }.get(1L, TimeUnit.SECONDS))
            assertEquals(0, fileSystem.releasedir("/Photos", fast))
            backend.release.countDown()
            assertEquals(0, slowOpen.get(2L, TimeUnit.SECONDS))
            assertEquals(0, fileSystem.releasedir("/Slow", slow))
        } finally {
            backend.release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `directory materialization follows a concurrent rename before registration`() {
        val fixture = MutableFixtureBackend()
        val backend = BlockingListBackend(fixture, "Photos")
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend, maximumOpenDirectoryEntries = 2)
        val runtime = Runtime.getSystemRuntime()
        val opened = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val reopened = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val worker = Executors.newSingleThreadExecutor()
        try {
            val opening = worker.submit<Int> { fileSystem.opendir("/Photos", opened) }
            assertTrue(backend.started.await(2L, TimeUnit.SECONDS))
            assertEquals(0, fileSystem.rename("/Photos", "/Albums"))
            backend.release.countDown()

            assertEquals(0, opening.get(2L, TimeUnit.SECONDS))
            assertEquals(0, fileSystem.releasedir("/Albums", opened))
            assertEquals(0, fileSystem.opendir("/Albums", reopened))
            assertEquals(0, fileSystem.releasedir("/Albums", reopened))
        } finally {
            backend.release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `open read handle is readdressed before rename cache maintenance`() {
        val fixture = MutableFixtureBackend().apply {
            addFile("Photos/open.txt", "read during rename".encodeToByteArray())
        }
        val maintenanceStarted = CountDownLatch(1)
        val releaseMaintenance = CountDownLatch(1)
        val backend = object : LinuxVirtualFileBackend by fixture {
            override fun move(
                node: LinuxVirtualFileNode,
                destinationPath: String,
                afterRemoteCommit: () -> Unit,
            ) {
                fixture.move(node, destinationPath)
                afterRemoteCommit()
                maintenanceStarted.countDown()
                check(releaseMaintenance.await(2L, TimeUnit.SECONDS))
            }
        }
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        val runtime = Runtime.getSystemRuntime()
        val fileInfo = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val output = runtime.memoryManager.allocateDirect(4)
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertEquals(0, fileSystem.open("/Photos/open.txt", fileInfo))
            val rename = worker.submit<Int> { fileSystem.rename("/Photos/open.txt", "/Photos/renamed.txt") }
            assertTrue(maintenanceStarted.await(2L, TimeUnit.SECONDS))

            assertEquals(4, fileSystem.read("/Photos/renamed.txt", output, 4L, 0L, fileInfo))
            assertContentEquals(
                "read".encodeToByteArray(),
                ByteArray(4).also { bytes -> output.get(0L, bytes, 0, bytes.size) },
            )
            releaseMaintenance.countDown()
            assertEquals(0, rename.get(2L, TimeUnit.SECONDS))
            assertEquals(0, fileSystem.release("/Photos/renamed.txt", fileInfo))
        } finally {
            releaseMaintenance.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `listing persistence keeps causal order from before the network request`() {
        val root = Files.createTempDirectory("linux-virtual-causal-listing-")
        val preferences = Preferences.userRoot().node(
            "dev/obiente/nextcloudnative/tests/linux-virtual-causal-listing/${UUID.randomUUID()}",
        )
        val listingStarted = CountDownLatch(1)
        val releaseListing = CountDownLatch(1)
        val persistenceAttempted = CountDownLatch(1)
        try {
            val cache = DesktopFileReadCache(root.toFile(), preferences = preferences)
            val accountId = "0".repeat(64)
            val fixture = MutableFixtureBackend().apply { addFile("Photos/old.dat", byteArrayOf(1)) }
            val delegate = object : LinuxVirtualFileBackend by fixture {
                override fun list(path: String): List<LinuxVirtualFileNode> {
                    val snapshot = fixture.list(path)
                    listingStarted.countDown()
                    check(releaseListing.await(2L, TimeUnit.SECONDS))
                    return snapshot
                }
            }
            val persisted = DesktopLinuxVirtualMetadataStore(cache, accountId)
            val store = object : LinuxVirtualMetadataStore by persisted {
                override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot): Boolean {
                    val stored = persisted.store(path, snapshot)
                    persistenceAttempted.countDown()
                    return stored
                }
            }
            val clock = AtomicLong(100L)
            val cached = CachingLinuxVirtualFileBackend(delegate, store, nowEpochMillis = clock::get)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val request = worker.submit<List<LinuxVirtualFileNode>> { cached.list("Photos") }
                assertTrue(listingStarted.await(2L, TimeUnit.SECONDS))
                cache.storeListing(
                    accountId,
                    "Photos",
                    listOf(cachedNextcloudFile("Photos/current.dat", "current-etag")),
                    nowEpochMillis = 200L,
                )
                clock.set(300L)
                releaseListing.countDown()
                assertEquals(listOf("old.dat"), request.get(2L, TimeUnit.SECONDS).map(LinuxVirtualFileNode::name))
                assertTrue(persistenceAttempted.await(2L, TimeUnit.SECONDS))
                assertEquals("Photos/current.dat", cache.cachedListing(accountId, "Photos")?.single()?.path)
            } finally {
                releaseListing.countDown()
                worker.shutdownNow()
                cached.close()
            }
        } finally {
            runCatching { preferences.removeNode() }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a skipped persisted refresh keeps its invalidation tombstone`() {
        val delegate = MutableFixtureBackend()
        val stale = LinuxVirtualDirectorySnapshot(
            nodes = listOf(LinuxVirtualFileNode("Photos", "Photos", true, 0L, "stale-photos")),
            fetchedAtEpochMillis = 100L,
        )
        val persistenceAttempted = CountDownLatch(1)
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? = stale.takeIf { path.isEmpty() }

            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot): Boolean {
                if (path.isEmpty()) persistenceAttempted.countDown()
                return false
            }

            override fun invalidate(path: String): Unit = error("Simulated persisted invalidation failure")
            override fun retainedPaths(): Set<String> = setOf("")
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = store,
            nowEpochMillis = { 100L },
            freshForMillis = Long.MAX_VALUE,
            maximumRetainedDirectories = 1,
        )
        try {
            assertEquals(listOf("Photos"), cached.list("").map(LinuxVirtualFileNode::name))
            cached.createDirectory("Albums")
            assertEquals(setOf("Photos", "Albums"), cached.list("").mapTo(mutableSetOf(), LinuxVirtualFileNode::name))
            assertTrue(persistenceAttempted.await(2L, TimeUnit.SECONDS))
            assertEquals(0, cached.revalidatedPersistedListingCount())
            assertEquals(1, cached.failedPersistedInvalidationCount())

            cached.list("Photos")
            assertEquals(setOf("Photos", "Albums"), cached.list("").mapTo(mutableSetOf(), LinuxVirtualFileNode::name))
            assertEquals(2, delegate.listCallCount(""))
        } finally {
            cached.close()
        }
    }

    @Test
    fun `directory snapshot indexes large child sets by canonical path`() {
        val nodes = List(50_000) { index ->
            val name = "item-${index.toString().padStart(5, '0')}.dat"
            LinuxVirtualFileNode("Photos/$name", name, false, 1L, "etag-$index")
        }
        val snapshot = LinuxVirtualDirectorySnapshot(nodes, fetchedAtEpochMillis = 1L)

        assertEquals(nodes.size, snapshot.nodesByPath.size)
        assertEquals(nodes.first(), snapshot.nodesByPath[nodes.first().path])
        assertEquals(nodes.last(), snapshot.nodesByPath[nodes.last().path])
    }

    @Test
    fun `stale persisted listing is returned while one background refresh replaces it`() {
        val delegate = MutableFixtureBackend().apply {
            addFile("Photos/new-a.dat", byteArrayOf(1))
            addFile("Photos/new-b.dat", byteArrayOf(2))
        }
        val store = MemoryLinuxVirtualMetadataStore().apply {
            seed(
                "Photos",
                LinuxVirtualDirectorySnapshot(
                    listOf(LinuxVirtualFileNode("Photos/cached.dat", "cached.dat", false, 1L, "cached-etag")),
                    fetchedAtEpochMillis = 1L,
                ),
            )
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = store,
            nowEpochMillis = { 10_000L },
            freshForMillis = 1_000L,
        )

        assertEquals(listOf("cached.dat"), cached.list("Photos").map(LinuxVirtualFileNode::name))
        assertTrue(
            waitUntil {
                cached.list("Photos").map(LinuxVirtualFileNode::name) == listOf("new-a.dat", "new-b.dat")
            },
        )
        assertEquals(listOf("new-a.dat", "new-b.dat"), cached.list("Photos").map(LinuxVirtualFileNode::name))
        assertEquals(1, delegate.listCallCount("Photos"))
        cached.close()
    }

    @Test
    fun `slow directory refresh is fresh from response completion`() {
        val fixture = MutableFixtureBackend().apply {
            addFile("Photos/new.dat", byteArrayOf(1))
        }
        val clock = AtomicLong(0L)
        val delegate = object : LinuxVirtualFileBackend by fixture {
            override fun list(path: String): List<LinuxVirtualFileNode> = fixture.list(path).also {
                clock.set(10_000L)
            }
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = MemoryLinuxVirtualMetadataStore(),
            nowEpochMillis = clock::get,
            freshForMillis = 5_000L,
        )
        try {
            assertEquals(listOf("new.dat"), cached.list("Photos").map(LinuxVirtualFileNode::name))
            repeat(100) { assertNotNull(cached.resolve("Photos/new.dat")) }
            TimeUnit.MILLISECONDS.sleep(100L)
            assertEquals(1, fixture.listCallCount("Photos"))
        } finally {
            cached.close()
        }
    }

    @Test
    fun `mutation invalidation discards an older in flight directory refresh`() {
        val delegate = MutableFixtureBackend().apply {
            addFile("Photos/existing.dat", byteArrayOf(1))
        }
        val blocking = BlockingListBackend(delegate, "Photos")
        val store = MemoryLinuxVirtualMetadataStore().apply {
            seed(
                "Photos",
                LinuxVirtualDirectorySnapshot(
                    listOf(LinuxVirtualFileNode("Photos/cached.dat", "cached.dat", false, 1L, "cached-etag")),
                    fetchedAtEpochMillis = 1L,
                ),
            )
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = blocking,
            store = store,
            nowEpochMillis = { 10_000L },
            freshForMillis = 1_000L,
        )

        assertEquals(listOf("cached.dat"), cached.list("Photos").map(LinuxVirtualFileNode::name))
        assertTrue(blocking.started.await(2L, TimeUnit.SECONDS))
        cached.createDirectory("Photos/New")
        blocking.release.countDown()

        assertEquals(
            setOf("existing.dat", "New"),
            cached.list("Photos").mapTo(mutableSetOf(), LinuxVirtualFileNode::name),
        )
        assertTrue(
            waitUntil {
                store.snapshot("Photos")?.nodes?.mapTo(mutableSetOf(), LinuxVirtualFileNode::name) ==
                    setOf("existing.dat", "New")
            },
        )
        assertEquals(2, delegate.listCallCount("Photos"))
        cached.close()
    }

    @Test
    fun `external mutation invalidation discards an older in flight directory refresh`() {
        val delegate = MutableFixtureBackend().apply {
            addFile("Photos/existing.dat", byteArrayOf(1))
        }
        val blocking = BlockingListBackend(delegate, "Photos")
        val store = MemoryLinuxVirtualMetadataStore().apply {
            seed(
                "Photos",
                LinuxVirtualDirectorySnapshot(
                    listOf(LinuxVirtualFileNode("Photos/cached.dat", "cached.dat", false, 1L, "cached-etag")),
                    fetchedAtEpochMillis = 1L,
                ),
            )
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = blocking,
            store = store,
            nowEpochMillis = { 10_000L },
            freshForMillis = 1_000L,
        )

        assertEquals(listOf("cached.dat"), cached.list("Photos").map(LinuxVirtualFileNode::name))
        assertTrue(blocking.started.await(2L, TimeUnit.SECONDS))
        delegate.createDirectory("Photos/New")
        cached.invalidateAfterExternalMutation("Photos/New")
        blocking.release.countDown()

        assertEquals(
            setOf("existing.dat", "New"),
            cached.list("Photos").mapTo(mutableSetOf(), LinuxVirtualFileNode::name),
        )
        assertTrue(
            waitUntil {
                store.snapshot("Photos")?.nodes?.mapTo(mutableSetOf(), LinuxVirtualFileNode::name) ==
                    setOf("existing.dat", "New")
            },
        )
        assertEquals(2, delegate.listCallCount("Photos"))
        cached.close()
    }

    @Test
    fun `external recovery invalidation keeps a failed persisted tombstone`() {
        val delegate = MutableFixtureBackend()
        val stale = LinuxVirtualDirectorySnapshot(
            nodes = listOf(LinuxVirtualFileNode("Photos", "Photos", true, 0L, "stale-photos")),
            fetchedAtEpochMillis = 100L,
        )
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? = stale.takeIf { path.isEmpty() }
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot): Boolean = false
            override fun invalidate(path: String): Unit = error("Simulated persisted invalidation failure")
            override fun retainedPaths(): Set<String> = setOf("")
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = store,
            nowEpochMillis = { 100L },
            freshForMillis = Long.MAX_VALUE,
            maximumRetainedDirectories = 1,
        )
        try {
            assertEquals(listOf("Photos"), cached.list("").map(LinuxVirtualFileNode::name))
            delegate.createDirectory("Albums")
            cached.invalidateAfterExternalMutation("Albums")

            assertEquals(
                setOf("Photos", "Albums"),
                cached.list("").mapTo(mutableSetOf(), LinuxVirtualFileNode::name),
            )
            assertEquals(1, cached.failedPersistedInvalidationCount())
            cached.list("Albums")
            assertEquals(
                setOf("Photos", "Albums"),
                cached.list("").mapTo(mutableSetOf(), LinuxVirtualFileNode::name),
            )
            assertEquals(2, delegate.listCallCount(""))
        } finally {
            cached.close()
        }
    }

    @Test
    fun `failed persisted invalidation survives a backend remount`() {
        val delegate = MutableFixtureBackend()
        val stale = LinuxVirtualDirectorySnapshot(
            nodes = listOf(LinuxVirtualFileNode("Photos", "Photos", true, 0L, "stale-photos")),
            fetchedAtEpochMillis = 100L,
        )
        var failedInvalidations = emptySet<String>()
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? = stale.takeIf { path.isEmpty() }
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot): Boolean = false
            override fun invalidate(path: String): Unit = error("Simulated persisted invalidation failure")
            override fun retainedPaths(): Set<String> = setOf("")
            override fun failedInvalidations(): Set<String> = failedInvalidations
            override fun replaceFailedInvalidations(paths: Set<String>) {
                failedInvalidations = paths.toSet()
            }
        }
        val first = CachingLinuxVirtualFileBackend(delegate, store, freshForMillis = Long.MAX_VALUE)
        assertEquals(listOf("Photos"), first.list("").map(LinuxVirtualFileNode::name))
        delegate.createDirectory("Albums")
        first.invalidateAfterExternalMutation("Albums")
        assertEquals(setOf("Albums"), failedInvalidations)
        first.close()

        val remounted = CachingLinuxVirtualFileBackend(delegate, store, freshForMillis = Long.MAX_VALUE)
        try {
            assertEquals(
                setOf("Photos", "Albums"),
                remounted.list("").mapTo(mutableSetOf(), LinuxVirtualFileNode::name),
            )
            assertEquals(1, delegate.listCallCount(""))
        } finally {
            remounted.close()
        }
    }

    @Test
    fun `failed quarantine persistence removes stale cache before mutation completion`() {
        val delegate = MutableFixtureBackend()
        val stale = LinuxVirtualDirectorySnapshot(
            nodes = listOf(LinuxVirtualFileNode("Photos", "Photos", true, 0L, "stale-photos")),
            fetchedAtEpochMillis = Long.MAX_VALUE,
        )
        val persistedInvalidated = AtomicBoolean(false)
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? = stale.takeIf {
                path.isEmpty() && !persistedInvalidated.get()
            }
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot) = true
            override fun invalidate(path: String) {
                persistedInvalidated.set(true)
            }
            override fun replaceFailedInvalidations(paths: Set<String>): Unit =
                error("Simulated unavailable preferences store")
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = store,
            freshForMillis = Long.MAX_VALUE,
            afterPersistedInvalidationMarked = { assertTrue(persistedInvalidated.get()) },
        )
        try {
            assertEquals(listOf("Photos"), cached.list("").map(LinuxVirtualFileNode::name))
            cached.createDirectory("Albums")

            assertTrue(persistedInvalidated.get())
            assertEquals(
                setOf("Photos", "Albums"),
                cached.list("").mapTo(mutableSetOf(), LinuxVirtualFileNode::name),
            )
        } finally {
            cached.close()
        }
    }

    @Test
    fun `failed stale refresh backs off during cached directory enumeration`() {
        val attempts = AtomicInteger(0)
        val delegate = object : LinuxVirtualFileBackend by MutableFixtureBackend() {
            override fun list(path: String): List<LinuxVirtualFileNode> {
                attempts.incrementAndGet()
                error("Simulated offline listing failure")
            }
        }
        val store = MemoryLinuxVirtualMetadataStore().apply {
            seed(
                "Photos",
                LinuxVirtualDirectorySnapshot(
                    listOf(LinuxVirtualFileNode("Photos/cached.dat", "cached.dat", false, 1L, "cached-etag")),
                    fetchedAtEpochMillis = 1L,
                ),
            )
        }
        val clock = AtomicLong(10_000L)
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = store,
            nowEpochMillis = clock::get,
            freshForMillis = 1_000L,
            refreshRetryBaseMillis = 1_000L,
            refreshRetryMaxMillis = 8_000L,
        )

        assertEquals(listOf("cached.dat"), cached.list("Photos").map(LinuxVirtualFileNode::name))
        assertTrue(waitUntil { attempts.get() == 1 && cached.hasRecordedRefreshFailure("Photos") })
        repeat(10_000) {
            assertNotNull(cached.resolve("Photos/cached.dat"))
        }
        assertEquals(1, attempts.get())

        clock.addAndGet(1_000L)
        assertEquals(listOf("cached.dat"), cached.list("Photos").map(LinuxVirtualFileNode::name))
        assertTrue(waitUntil { attempts.get() == 2 })
        repeat(1_000) { cached.list("Photos") }
        assertEquals(2, attempts.get())

        clock.set(5_000L)
        assertEquals(listOf("cached.dat"), cached.list("Photos").map(LinuxVirtualFileNode::name))
        assertTrue(waitUntil { attempts.get() == 3 })
        cached.close()
    }

    @Test
    fun `refresh failure backoff records stay within the directory budget`() {
        val delegate = object : LinuxVirtualFileBackend by MutableFixtureBackend() {
            override fun list(path: String): List<LinuxVirtualFileNode> =
                error("Simulated offline listing failure for $path")
        }
        val staleNode = { path: String ->
            LinuxVirtualDirectorySnapshot(
                listOf(LinuxVirtualFileNode("$path/cached.dat", "cached.dat", false, 1L, "cached-etag")),
                fetchedAtEpochMillis = 1L,
            )
        }
        val store = MemoryLinuxVirtualMetadataStore().apply {
            seed("First", staleNode("First"))
            seed("Second", staleNode("Second"))
            seed("Third", staleNode("Third"))
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = store,
            nowEpochMillis = { 10_000L },
            freshForMillis = 1_000L,
            maximumRetainedDirectories = 2,
        )

        cached.list("First")
        assertTrue(waitUntil { cached.hasRecordedRefreshFailure("First") })
        cached.list("Second")
        assertTrue(waitUntil { cached.hasRecordedRefreshFailure("Second") })
        cached.list("Third")
        assertTrue(waitUntil { cached.hasRecordedRefreshFailure("Third") })

        assertFalse(cached.hasRecordedRefreshFailure("First"))
        assertTrue(cached.hasRecordedRefreshFailure("Second"))
        assertTrue(cached.hasRecordedRefreshFailure("Third"))
        cached.close()
    }

    @Test
    fun `persisting a large snapshot does not block cached metadata reads`() {
        val delegate = MutableFixtureBackend().apply { addFile("Photos/cached.dat", byteArrayOf(1)) }
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? = null
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot): Boolean {
                persistenceStarted.countDown()
                check(releasePersistence.await(2L, TimeUnit.SECONDS))
                return true
            }
            override fun invalidate(path: String) = Unit
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = store,
            freshForMillis = Long.MAX_VALUE,
        )
        val reader = Executors.newSingleThreadExecutor()
        try {
            assertEquals(listOf("cached.dat"), cached.list("Photos").map(LinuxVirtualFileNode::name))
            assertTrue(persistenceStarted.await(2L, TimeUnit.SECONDS))
            assertEquals(
                listOf("cached.dat"),
                reader.submit<List<String>> {
                    cached.list("Photos").map(LinuxVirtualFileNode::name)
                }.get(1L, TimeUnit.SECONDS),
            )
        } finally {
            releasePersistence.countDown()
            reader.shutdownNow()
            cached.close()
        }
    }

    @Test
    fun `closing waits for an in-flight metadata publication`() {
        val delegate = MutableFixtureBackend().apply { addFile("Photos/cached.dat", byteArrayOf(1)) }
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? = null
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot): Boolean {
                persistenceStarted.countDown()
                check(releasePersistence.await(2L, TimeUnit.SECONDS))
                return true
            }
            override fun invalidate(path: String) = Unit
        }
        val cached = CachingLinuxVirtualFileBackend(delegate, store, freshForMillis = Long.MAX_VALUE)
        val closer = Executors.newSingleThreadExecutor()
        try {
            cached.list("Photos")
            assertTrue(persistenceStarted.await(2L, TimeUnit.SECONDS))
            closer.execute {
                cached.close()
                closeFinished.countDown()
            }
            assertFalse(closeFinished.await(100L, TimeUnit.MILLISECONDS))
            releasePersistence.countDown()
            assertTrue(closeFinished.await(2L, TimeUnit.SECONDS))
        } finally {
            releasePersistence.countDown()
            closer.shutdownNow()
            cached.close()
        }
    }

    @Test
    fun `persisted load racing mutation cannot reinstall stale metadata`() {
        val delegate = MutableFixtureBackend()
        val stale = LinuxVirtualDirectorySnapshot(
            listOf(LinuxVirtualFileNode("Old", "Old", true, 0L, "old-etag")),
            fetchedAtEpochMillis = Long.MAX_VALUE,
        )
        val loadStarted = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val invalidationMarked = CountDownLatch(1)
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? {
                loadStarted.countDown()
                check(releaseLoad.await(2L, TimeUnit.SECONDS))
                return stale
            }
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot) = true
            override fun invalidate(path: String) = Unit
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = store,
            freshForMillis = Long.MAX_VALUE,
            afterPersistedInvalidationMarked = invalidationMarked::countDown,
        )
        val workers = Executors.newFixedThreadPool(2)
        try {
            val listing = workers.submit<List<String>> { cached.list("").map(LinuxVirtualFileNode::name) }
            assertTrue(loadStarted.await(2L, TimeUnit.SECONDS))
            val mutation = workers.submit { cached.createDirectory("Albums") }
            assertTrue(invalidationMarked.await(2L, TimeUnit.SECONDS))
            releaseLoad.countDown()

            assertEquals(setOf("Photos", "Albums"), listing.get(2L, TimeUnit.SECONDS).toSet())
            mutation.get(2L, TimeUnit.SECONDS)
        } finally {
            releaseLoad.countDown()
            workers.shutdownNow()
            cached.close()
        }
    }

    @Test
    fun `mutation does not discard an unrelated directory refresh`() {
        val fixture = MutableFixtureBackend().apply { addFile("Archive/kept.dat", byteArrayOf(1)) }
        val listingStarted = CountDownLatch(1)
        val releaseListing = CountDownLatch(1)
        val archiveCalls = AtomicInteger(0)
        val delegate = object : LinuxVirtualFileBackend by fixture {
            override fun list(path: String): List<LinuxVirtualFileNode> {
                if (path == "Archive") {
                    archiveCalls.incrementAndGet()
                    listingStarted.countDown()
                    check(releaseListing.await(2L, TimeUnit.SECONDS))
                }
                return fixture.list(path)
            }
        }
        val cached = CachingLinuxVirtualFileBackend(delegate, MemoryLinuxVirtualMetadataStore())
        val workers = Executors.newFixedThreadPool(2)
        try {
            val archive = workers.submit<List<String>> {
                cached.list("Archive").map(LinuxVirtualFileNode::name)
            }
            assertTrue(listingStarted.await(2L, TimeUnit.SECONDS))
            cached.createDirectory("Photos/New")
            releaseListing.countDown()

            assertEquals(listOf("kept.dat"), archive.get(2L, TimeUnit.SECONDS))
            assertEquals(1, archiveCalls.get())
        } finally {
            releaseListing.countDown()
            workers.shutdownNow()
            cached.close()
        }
    }

    @Test
    fun `metadata snapshots evict least recently used directories within the global budget`() {
        val delegate = MutableFixtureBackend().apply {
            addFile("Photos/one.dat", byteArrayOf(1))
            createDirectory("Albums")
            addFile("Albums/two.dat", byteArrayOf(2))
        }
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? = null
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot) = true
            override fun invalidate(path: String) = Unit
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = store,
            freshForMillis = Long.MAX_VALUE,
            maximumRetainedMetadataEntries = 1,
        )

        assertEquals(listOf("one.dat"), cached.list("Photos").map(LinuxVirtualFileNode::name))
        assertEquals(listOf("two.dat"), cached.list("Albums").map(LinuxVirtualFileNode::name))
        assertEquals(listOf("one.dat"), cached.list("Photos").map(LinuxVirtualFileNode::name))
        assertEquals(2, delegate.listCallCount("Photos"))
        cached.close()
    }

    @Test
    fun `completed mutation ignores disposable persisted invalidation failure`() {
        val delegate = MutableFixtureBackend()
        val stale = LinuxVirtualDirectorySnapshot(
            nodes = listOf(LinuxVirtualFileNode("Photos", "Photos", true, 0L, "stale-photos")),
            fetchedAtEpochMillis = Long.MAX_VALUE,
        )
        val loads = AtomicInteger(0)
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? =
                stale.takeIf { path.isEmpty() }.also { loads.incrementAndGet() }

            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot) = true
            override fun invalidate(path: String): Unit = error("Simulated cache publication failure")
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = store,
            freshForMillis = Long.MAX_VALUE,
        )

        assertEquals(listOf("Photos"), cached.list("").map(LinuxVirtualFileNode::name))
        cached.createDirectory("Albums")
        assertEquals(setOf("Photos", "Albums"), cached.list("").mapTo(hashSetOf(), LinuxVirtualFileNode::name))
        assertEquals(1, loads.get())
        cached.close()
    }

    @Test
    fun `write invalidates metadata only once after a committed change`() {
        val delegate = MutableFixtureBackend().apply { addFile("Photos/draft.txt", "old".encodeToByteArray()) }
        val invalidations = AtomicInteger(0)
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? = null
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot) = true
            override fun invalidate(path: String) {
                invalidations.incrementAndGet()
            }
        }
        val cached = CachingLinuxVirtualFileBackend(delegate, store)
        val existing = requireNotNull(delegate.resolve("Photos/draft.txt"))
        val handle = cached.openWrite(existing.path, existing, truncate = false)

        handle.write(0L, "new".encodeToByteArray())
        handle.flush()
        handle.flush()
        handle.close()

        assertEquals(1, invalidations.get())
        cached.close()
    }

    @Test
    fun `same size truncate still invalidates the committed remote revision`() {
        val delegate = MutableFixtureBackend().apply { addFile("Photos/draft.txt", "old".encodeToByteArray()) }
        val invalidations = AtomicInteger(0)
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? = null
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot) = true
            override fun invalidate(path: String) {
                invalidations.incrementAndGet()
            }
        }
        val cached = CachingLinuxVirtualFileBackend(delegate, store)
        val existing = requireNotNull(delegate.resolve("Photos/draft.txt"))
        val handle = cached.openWrite(existing.path, existing, truncate = false)

        handle.truncate(existing.size)
        handle.flush()
        handle.close()

        assertEquals(1, invalidations.get())
        cached.close()
    }

    @Test
    fun `queued persistence snapshots remain within the metadata entry budget`() {
        val delegate = MutableFixtureBackend().apply {
            addFile("Photos/one.dat", byteArrayOf(1))
            addFile("Archive/two.dat", byteArrayOf(2))
        }
        val executorStarted = CountDownLatch(1)
        val releaseExecutor = CountDownLatch(1)
        val executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
        ).apply {
            execute {
                executorStarted.countDown()
                releaseExecutor.await()
            }
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = MemoryLinuxVirtualMetadataStore(),
            freshForMillis = Long.MAX_VALUE,
            maximumRetainedMetadataEntries = 1,
            refreshExecutor = executor,
        )
        try {
            assertTrue(executorStarted.await(2L, TimeUnit.SECONDS))
            assertEquals(listOf("one.dat"), cached.list("Photos").map(LinuxVirtualFileNode::name))
            assertEquals(listOf("two.dat"), cached.list("Archive").map(LinuxVirtualFileNode::name))
            assertEquals(1, executor.queue.size)
        } finally {
            releaseExecutor.countDown()
            cached.close()
        }
    }

    @Test
    fun `authoritative persistence clears a covered failed invalidation`() {
        val fixture = MutableFixtureBackend()
        val failRootListing = AtomicBoolean(false)
        val delegate = object : LinuxVirtualFileBackend by fixture {
            override fun list(path: String): List<LinuxVirtualFileNode> {
                if (path.isEmpty() && failRootListing.get()) error("Simulated offline root listing")
                return fixture.list(path)
            }
        }
        val persisted = MemoryLinuxVirtualMetadataStore()
        val store = object : LinuxVirtualMetadataStore by persisted {
            override fun invalidate(path: String): Unit = error("Simulated cache invalidation failure")
        }
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = store,
            freshForMillis = Long.MAX_VALUE,
            maximumRetainedDirectories = 1,
        )

        assertEquals(listOf("Photos"), cached.list("").map(LinuxVirtualFileNode::name))
        assertTrue(waitUntil { persisted.snapshot("")?.nodes?.singleOrNull()?.name == "Photos" })
        cached.createDirectory("Albums")
        assertEquals(1, cached.failedPersistedInvalidationCount())
        assertEquals(setOf("Photos", "Albums"), cached.list("").mapTo(hashSetOf(), LinuxVirtualFileNode::name))
        assertTrue(waitUntil { persisted.snapshot("")?.nodes?.any { it.name == "Albums" } == true })
        assertTrue(waitUntil { cached.failedPersistedInvalidationCount() == 0 })
        assertEquals(0, cached.revalidatedPersistedListingCount())
        cached.list("Photos")
        assertTrue(waitUntil { persisted.snapshot("Photos") != null })
        assertEquals(0, cached.revalidatedPersistedListingCount())
        failRootListing.set(true)

        assertEquals(setOf("Photos", "Albums"), cached.list("").mapTo(hashSetOf(), LinuxVirtualFileNode::name))
        cached.close()
    }

    @Test
    fun `virtual metadata persistence never degrades the authoritative Files listing`() {
        val root = Files.createTempDirectory("linux-virtual-metadata-")
        val preferences = Preferences.userRoot().node(
            "dev/obiente/nextcloudnative/tests/linux-virtual-metadata/${UUID.randomUUID()}",
        )
        try {
            val cache = DesktopFileReadCache(root.toFile(), preferences = preferences)
            val accountId = "0".repeat(64)
            val previous = NextcloudFile(
                path = "Photos/example.raf",
                name = "example.raf",
                isDirectory = false,
                mimeType = "image/x-raw",
                size = 5L,
                lastModified = "2026-08-02T12:00:00Z",
                fileId = 42L,
                hasPreview = true,
                etag = "revision-1",
                permissions = "RGDNVW",
                checksums = listOf("SHA256:abc"),
            )
            cache.storeListing(accountId, "Photos", listOf(previous), nowEpochMillis = 1L)
            val store = DesktopLinuxVirtualMetadataStore(cache, accountId)

            store.store(
                "Photos",
                LinuxVirtualDirectorySnapshot(
                    listOf(LinuxVirtualFileNode(previous.path, previous.name, false, 5L, "revision-1")),
                    fetchedAtEpochMillis = 2L,
                ),
            )
            val unchanged = cache.cachedListing(accountId, "Photos").orEmpty().single()
            assertEquals(previous, unchanged)
            assertEquals("revision-1", store.load("Photos")?.nodes?.single()?.remoteRevision)

            store.store(
                "Photos",
                LinuxVirtualDirectorySnapshot(
                    listOf(LinuxVirtualFileNode(previous.path, previous.name, false, 8L, "revision-2")),
                    fetchedAtEpochMillis = 3L,
                ),
            )
            val replaced = cache.cachedListing(accountId, "Photos").orEmpty().single()
            assertEquals(previous, replaced)
            val virtualReplacement = requireNotNull(store.load("Photos")).nodes.single()
            assertEquals("revision-2", virtualReplacement.remoteRevision)
            assertEquals(8L, virtualReplacement.size)

            val authoritative = previous.copy(name = "authoritative.raf", etag = "revision-3")
            cache.storeListing(accountId, "Photos", listOf(authoritative), nowEpochMillis = 10L)
            store.store(
                "Photos",
                LinuxVirtualDirectorySnapshot(
                    listOf(LinuxVirtualFileNode(previous.path, previous.name, false, 9L, "revision-older")),
                    fetchedAtEpochMillis = 4L,
                ),
            )
            assertEquals(authoritative, cache.cachedListing(accountId, "Photos").orEmpty().single())
            assertEquals("revision-older", store.load("Photos")?.nodes?.single()?.remoteRevision)
        } finally {
            runCatching { preferences.removeNode() }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `concurrent blocking metadata misses stay within a fixed network bound`() {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val delegate = object : LinuxVirtualFileBackend by MutableFixtureBackend() {
            override fun list(path: String): List<LinuxVirtualFileNode> {
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { previous -> maxOf(previous, current) }
                started.countDown()
                try {
                    check(release.await(2L, TimeUnit.SECONDS))
                    return emptyList()
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val cached = CachingLinuxVirtualFileBackend(delegate, MemoryLinuxVirtualMetadataStore())
        val workers = Executors.newFixedThreadPool(8)
        try {
            val reads = List(8) { index -> workers.submit<List<LinuxVirtualFileNode>> { cached.list("Folder-$index") } }
            assertTrue(started.await(2L, TimeUnit.SECONDS))
            assertEquals(2, maximumActive.get())
            release.countDown()
            reads.forEach { read -> assertTrue(read.get(2L, TimeUnit.SECONDS).isEmpty()) }
        } finally {
            release.countDown()
            workers.shutdownNow()
            cached.close()
        }
    }

    @Test
    fun `successful namespace mutation invalidates the affected parent listing`() {
        val delegate = MutableFixtureBackend()
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = MemoryLinuxVirtualMetadataStore(),
            freshForMillis = Long.MAX_VALUE,
        )

        assertEquals(listOf("Photos"), cached.list("").map(LinuxVirtualFileNode::name))
        cached.createDirectory("Albums")
        assertEquals(listOf("Photos", "Albums"), cached.list("").map(LinuxVirtualFileNode::name))
        assertEquals(2, delegate.listCallCount(""))
        cached.close()
    }

    @Test
    fun `partial retained navigation merges with a complete fallback listing`() {
        val directory = Files.createTempDirectory("retained-navigation-merge-").toFile()
        try {
            val accountId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            val cache = DesktopVirtualRangeCache(directory) {
                VirtualFileCachePolicy(
                    automaticCleanup = false,
                    maximumCacheBytes = null,
                    minimumFreeSpaceBytes = 0L,
                    unusedFileAgeMillis = null,
                )
            }
            cache.setFolderRetention(accountId, "Photos/2026", VirtualFolderRetention.KeepOnDevice)
            cache.publishRetainedListings(
                accountId,
                "Photos/2026",
                mapOf(
                    "" to LinuxVirtualDirectorySnapshot(
                        listOf(LinuxVirtualFileNode("Photos", "Photos", true, 0L, "photos-new")),
                        20L,
                        complete = false,
                    ),
                    "Photos" to LinuxVirtualDirectorySnapshot(
                        listOf(LinuxVirtualFileNode("Photos/2026", "2026", true, 0L, "2026-new")),
                        20L,
                        complete = false,
                    ),
                    "Photos/2026" to LinuxVirtualDirectorySnapshot(emptyList(), 20L),
                ),
            )
            val fallback = MemoryLinuxVirtualMetadataStore().apply {
                seed(
                    "",
                    LinuxVirtualDirectorySnapshot(
                        listOf(
                            LinuxVirtualFileNode("Photos", "Photos", true, 0L, "photos-old"),
                            LinuxVirtualFileNode("Documents", "Documents", true, 0L, "documents"),
                        ),
                        10L,
                    ),
                )
            }
            val store = RetainedLinuxVirtualMetadataStore(cache, accountId, fallback)

            val restored = assertNotNull(store.load(""))

            assertTrue(restored.complete)
            assertEquals(setOf("Photos", "Documents"), restored.nodes.mapTo(hashSetOf(), LinuxVirtualFileNode::name))
            assertEquals("photos-new", restored.nodesByPath.getValue("Photos").remoteRevision)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `partial retained navigation without a fallback is immediately stale`() {
        val directory = Files.createTempDirectory("retained-navigation-stale-").toFile()
        try {
            val accountId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            val cache = DesktopVirtualRangeCache(directory) {
                VirtualFileCachePolicy(automaticCleanup = false, minimumFreeSpaceBytes = 0L)
            }
            cache.setFolderRetention(accountId, "Photos/Retained", VirtualFolderRetention.KeepOnDevice)
            val retainedNodes = List(600) { index ->
                LinuxVirtualFileNode(
                    path = "Photos/item-$index",
                    name = "item-$index",
                    directory = true,
                    size = 0L,
                    remoteRevision = "etag-$index",
                )
            }
            cache.publishRetainedListings(
                accountId,
                "Photos/Retained",
                mapOf(
                    "Photos" to LinuxVirtualDirectorySnapshot(
                        nodes = retainedNodes,
                        fetchedAtEpochMillis = 20L,
                        freshAtEpochMillis = 10_000L,
                        complete = false,
                    ),
                    "Photos/Retained" to LinuxVirtualDirectorySnapshot(
                        nodes = emptyList(),
                        fetchedAtEpochMillis = 20L,
                    ),
                ),
            )

            val retainedStore = RetainedLinuxVirtualMetadataStore(
                cache,
                accountId,
                MemoryLinuxVirtualMetadataStore(),
            )
            val restored = assertNotNull(retainedStore.load("Photos"))

            assertFalse(restored.complete)
            assertEquals(600, restored.nodes.size)
            assertEquals(0L, restored.fetchedAtEpochMillis)
            assertEquals(0L, restored.freshAtEpochMillis)

            val delegate = MutableFixtureBackend()
            val cached = CachingLinuxVirtualFileBackend(
                delegate = delegate,
                store = retainedStore,
                nowEpochMillis = { 10_000L },
            )
            try {
                assertEquals(600, cached.list("Photos").size)
                assertTrue(waitUntil { delegate.listCallCount("Photos") == 1 })
            } finally {
                cached.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `newer fallback keeps missing retained navigation reachable without replacing live nodes`() {
        val directory = Files.createTempDirectory("retained-navigation-recovery-").toFile()
        try {
            val accountId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            val cache = DesktopVirtualRangeCache(directory) {
                VirtualFileCachePolicy(automaticCleanup = false, minimumFreeSpaceBytes = 0L)
            }
            cache.setFolderRetention(accountId, "Photos/Missing", VirtualFolderRetention.KeepOnDevice)
            cache.publishRetainedListings(
                accountId,
                "Photos/Missing",
                mapOf(
                    "Photos" to LinuxVirtualDirectorySnapshot(
                        listOf(
                            LinuxVirtualFileNode("Photos/Live", "Live", true, 0L, "live-old"),
                            LinuxVirtualFileNode("Photos/Missing", "Missing", true, 0L, "missing"),
                        ),
                        20L,
                        complete = false,
                    ),
                    "Photos/Missing" to LinuxVirtualDirectorySnapshot(emptyList(), 20L),
                ),
            )
            val fallback = MemoryLinuxVirtualMetadataStore().apply {
                seed(
                    "Photos",
                    LinuxVirtualDirectorySnapshot(
                        listOf(LinuxVirtualFileNode("Photos/Live", "Live", true, 0L, "live-new")),
                        30L,
                    ),
                )
            }

            val restored = assertNotNull(RetainedLinuxVirtualMetadataStore(cache, accountId, fallback).load("Photos"))

            assertEquals(setOf("Live", "Missing"), restored.nodes.mapTo(hashSetOf(), LinuxVirtualFileNode::name))
            assertEquals("live-new", restored.nodesByPath.getValue("Photos/Live").remoteRevision)
            assertEquals("missing", restored.nodesByPath.getValue("Photos/Missing").remoteRevision)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `retained metadata wrapper forwards invalidation quarantine state`() {
        val directory = Files.createTempDirectory("retained-invalidation-forwarding-").toFile()
        try {
            val accountId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            var failed = emptySet<String>()
            val fallback = object : LinuxVirtualMetadataStore by MemoryLinuxVirtualMetadataStore() {
                override fun failedInvalidations(): Set<String> = failed
                override fun replaceFailedInvalidations(paths: Set<String>) {
                    failed = paths.toSet()
                }
            }
            val cache = DesktopVirtualRangeCache(directory) {
                VirtualFileCachePolicy(automaticCleanup = false, minimumFreeSpaceBytes = 0L)
            }
            val retained = RetainedLinuxVirtualMetadataStore(cache, accountId, fallback)

            retained.replaceFailedInvalidations(setOf("Photos"))

            assertEquals(setOf("Photos"), retained.failedInvalidations())
            assertEquals(setOf("Photos"), failed)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `fallback metadata publication continues after a cache failure`() {
        val attempted = mutableListOf<String>()
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? = null
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot): Boolean {
                attempted += path
                if (path == "Photos") error("Simulated optional metadata cache failure")
                return true
            }
            override fun invalidate(path: String) = Unit
        }
        val complete = LinuxVirtualDirectorySnapshot(emptyList(), fetchedAtEpochMillis = 1L, complete = true)
        val partial = LinuxVirtualDirectorySnapshot(emptyList(), fetchedAtEpochMillis = 1L, complete = false)

        publishDesktopLinuxFallbackMetadataBestEffort(
            store,
            linkedMapOf("Photos" to complete, "Albums" to complete, "Partial" to partial),
        )

        assertEquals(listOf("Photos", "Albums"), attempted)
    }

    @Test
    fun `complete retained listing does not revive stale fallback children`() {
        val directory = Files.createTempDirectory("retained-complete-precedence-").toFile()
        try {
            val accountId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            val cache = DesktopVirtualRangeCache(directory) {
                VirtualFileCachePolicy(
                    automaticCleanup = false,
                    maximumCacheBytes = null,
                    minimumFreeSpaceBytes = 0L,
                    unusedFileAgeMillis = null,
                )
            }
            cache.setFolderRetention(accountId, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.publishRetainedListings(
                accountId,
                "Photos",
                mapOf(
                    "Photos" to LinuxVirtualDirectorySnapshot(
                        listOf(LinuxVirtualFileNode("Photos/live.raf", "live.raf", false, 4L, "live")),
                        20L,
                    ),
                ),
            )
            val fallback = MemoryLinuxVirtualMetadataStore().apply {
                seed(
                    "Photos",
                    LinuxVirtualDirectorySnapshot(
                        listOf(
                            LinuxVirtualFileNode("Photos/live.raf", "live.raf", false, 4L, "old"),
                            LinuxVirtualFileNode("Photos/deleted.raf", "deleted.raf", false, 4L, "deleted"),
                        ),
                        10L,
                    ),
                )
            }

            val restored = assertNotNull(RetainedLinuxVirtualMetadataStore(cache, accountId, fallback).load("Photos"))

            assertEquals(listOf("live.raf"), restored.nodes.map(LinuxVirtualFileNode::name))
            assertEquals("live", restored.nodes.single().remoteRevision)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `persisted remote listing reports only changed child paths once`() {
        val directory = Files.createTempDirectory("retained-listing-change-").toFile()
        try {
            val accountId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            val cache = DesktopVirtualRangeCache(directory) {
                VirtualFileCachePolicy(automaticCleanup = false, minimumFreeSpaceBytes = 0L)
            }
            val fallback = MemoryLinuxVirtualMetadataStore().apply {
                seed(
                    "",
                    LinuxVirtualDirectorySnapshot(
                        listOf(
                            LinuxVirtualFileNode("Photos", "Photos", true, 0L, "photos"),
                            LinuxVirtualFileNode("Documents", "Documents", true, 0L, "documents-old"),
                        ),
                        10L,
                    ),
                )
            }
            val changes = mutableListOf<Set<String>>()
            val store = RetainedLinuxVirtualMetadataStore(cache, accountId, fallback, changes::add)
            val refreshed = LinuxVirtualDirectorySnapshot(
                listOf(
                    LinuxVirtualFileNode("Photos", "Photos", true, 0L, "photos"),
                    LinuxVirtualFileNode("Documents", "Documents", true, 0L, "documents-new"),
                ),
                20L,
            )

            assertTrue(store.store("", refreshed))
            assertEquals(listOf(setOf("Documents")), changes)
            assertTrue(store.store("", refreshed.copy(fetchedAtEpochMillis = 30L)))
            assertEquals(1, changes.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failed namespace mutation invalidation requests retained folder recovery`() {
        val failure = IllegalStateException("permission rejected")
        val delegate = object : LinuxVirtualFileBackend by MutableFixtureBackend() {
            override fun delete(node: LinuxVirtualFileNode) {
                throw failure
            }
        }
        val invalidatedMutations = mutableListOf<String>()
        val cached = CachingLinuxVirtualFileBackend(
            delegate = delegate,
            store = MemoryLinuxVirtualMetadataStore(),
            afterMutationInvalidated = invalidatedMutations::add,
        )
        val node = LinuxVirtualFileNode("Photos/example.raf", "example.raf", false, 4L, "e1")

        assertEquals(failure, assertFailsWith<IllegalStateException> { cached.delete(node) })
        assertEquals(listOf("Photos/example.raf"), invalidatedMutations)
        cached.close()
    }

    @Test
    fun `renaming an open directory readdresses its retained handle`() {
        val backend = MutableFixtureBackend()
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        val runtime = Runtime.getSystemRuntime()
        val buffer = runtime.memoryManager.allocateDirect(8)
        val fileInfo = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val filler = ru.serce.jnrfuse.FuseFillDir { _, _, _, _ -> 0 }

        assertEquals(0, fileSystem.opendir("/Photos", fileInfo))
        assertEquals(0, fileSystem.rename("/Photos", "/Albums"))
        assertEquals(0, fileSystem.readdir("/Albums", buffer, filler, 0L, fileInfo))
        assertEquals(0, fileSystem.releasedir("/Albums", fileInfo))
    }

    @Test
    fun `directory release and rename cannot leak a readdressed handle`() {
        val fixture = MutableFixtureBackend()
        val moveCommitted = CountDownLatch(1)
        val backend = object : LinuxVirtualFileBackend by fixture {
            override fun move(
                node: LinuxVirtualFileNode,
                destinationPath: String,
                afterRemoteCommit: () -> Unit,
            ) {
                fixture.move(node, destinationPath)
                moveCommitted.countDown()
                afterRemoteCommit()
            }
        }
        val releaseLoaded = CountDownLatch(1)
        val allowRemoval = CountDownLatch(1)
        val fileSystem = LinuxNextcloudVirtualFileSystem(
            backend = backend,
            maximumOpenDirectoryEntries = 2,
            beforeDirectoryHandleRemoval = {
                releaseLoaded.countDown()
                check(allowRemoval.await(2L, TimeUnit.SECONDS))
            },
        )
        val runtime = Runtime.getSystemRuntime()
        val opened = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val reopened = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val workers = Executors.newFixedThreadPool(2)
        try {
            assertEquals(0, fileSystem.opendir("/Photos", opened))
            val release = workers.submit<Int> { fileSystem.releasedir("/Photos", opened) }
            assertTrue(releaseLoaded.await(2L, TimeUnit.SECONDS))
            val rename = workers.submit<Int> { fileSystem.rename("/Photos", "/Albums") }
            assertTrue(moveCommitted.await(2L, TimeUnit.SECONDS))
            assertFalse(rename.isDone)

            allowRemoval.countDown()
            assertEquals(0, release.get(2L, TimeUnit.SECONDS))
            assertEquals(0, rename.get(2L, TimeUnit.SECONDS))
            assertEquals(0, fileSystem.opendir("/Albums", reopened))
            assertEquals(0, fileSystem.releasedir("/Albums", reopened))
        } finally {
            allowRemoval.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `release reports a close time writeback failure`() {
        val backend = MutableFixtureBackend(failClose = true)
        val fileSystem = LinuxNextcloudVirtualFileSystem(backend)
        val runtime = Runtime.getSystemRuntime()
        val fileInfo = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))

        assertEquals(0, fileSystem.create("/Photos/failed.raf", 0L, fileInfo))
        assertEquals(-ErrorCodes.EIO(), fileSystem.release("/Photos/failed.raf", fileInfo))
    }

    private fun fixtureBackend(
        bytes: ByteArray,
        onOpen: () -> () -> Unit,
    ): LinuxVirtualFileBackend {
        val root = LinuxVirtualFileNode("", "Nextcloud", true, 0L, "root")
        val photos = LinuxVirtualFileNode("Photos", "Photos", true, 0L, "folder-etag")
        val file = LinuxVirtualFileNode(
            "Photos/example.raf",
            "example.raf",
            false,
            bytes.size.toLong(),
            "file-etag",
        )
        return object : LinuxVirtualFileBackend {
            override fun resolve(path: String): LinuxVirtualFileNode? = when (path.trim('/')) {
                "" -> root
                "Photos" -> photos
                "Photos/example.raf" -> file
                else -> null
            }

            override fun list(path: String): List<LinuxVirtualFileNode> = when (path.trim('/')) {
                "" -> listOf(photos)
                "Photos" -> listOf(file)
                else -> emptyList()
            }

            override fun open(node: LinuxVirtualFileNode): LinuxVirtualFileReadHandle {
                val close = onOpen()
                return object : LinuxVirtualFileReadHandle {
                    override val size: Long = bytes.size.toLong()

                    override fun read(offset: Long, length: Int): ByteArray =
                        bytes.copyOfRange(offset.toInt(), offset.toInt() + length)

                    override fun close() = close()
                }
            }

            override fun openWrite(
                path: String,
                existing: LinuxVirtualFileNode?,
                truncate: Boolean,
            ): LinuxVirtualFileWriteHandle = error("Write access is not used by this read fixture.")

            override fun createDirectory(path: String) = error("Not used by this read fixture.")
            override fun delete(node: LinuxVirtualFileNode) = error("Not used by this read fixture.")
            override fun move(node: LinuxVirtualFileNode, destinationPath: String) =
                error("Not used by this read fixture.")

            override fun moveReplacing(
                node: LinuxVirtualFileNode,
                destination: LinuxVirtualFileNode,
                destinationPath: String,
            ) = error("Not used by this read fixture.")
        }
    }

    private class MutableFixtureBackend(
        private val failClose: Boolean = false,
    ) : LinuxVirtualFileBackend {
        private val nodes = linkedMapOf(
            "" to LinuxVirtualFileNode("", "Nextcloud", true, 0L, "root"),
            "Photos" to LinuxVirtualFileNode("Photos", "Photos", true, 0L, "photos-etag"),
        )
        private val contents = linkedMapOf<String, ByteArray>()
        private val listCalls = mutableMapOf<String, Int>()

        override fun resolve(path: String): LinuxVirtualFileNode? = nodes[path.trim('/')]

        override fun list(path: String): List<LinuxVirtualFileNode> {
            val parent = path.trim('/')
            listCalls[parent] = listCalls.getOrDefault(parent, 0) + 1
            return nodes.values.filter { node ->
                node.path.isNotEmpty() && node.path.substringBeforeLast('/', "") == parent
            }
        }

        override fun open(node: LinuxVirtualFileNode): LinuxVirtualFileReadHandle =
            object : LinuxVirtualFileReadHandle {
                private var currentPath = node.path
                override val size: Long = node.size

                override fun read(offset: Long, length: Int): ByteArray =
                    requireNotNull(contents[currentPath]).copyOfRange(offset.toInt(), offset.toInt() + length)

                override fun readdress(path: String) {
                    currentPath = path
                }

                override fun close() = Unit
            }

        override fun openWrite(
            path: String,
            existing: LinuxVirtualFileNode?,
            truncate: Boolean,
        ): LinuxVirtualFileWriteHandle {
            var stagedBytes = if (truncate) byteArrayOf() else contents[path]?.copyOf() ?: byteArrayOf()
            return object : LinuxVirtualFileWriteHandle {
                override val size: Long get() = stagedBytes.size.toLong()

                override fun read(offset: Long, length: Int): ByteArray =
                    stagedBytes.copyOfRange(offset.toInt(), offset.toInt() + length)

                override fun write(offset: Long, bytes: ByteArray): Int {
                    val required = offset.toInt() + bytes.size
                    if (required > stagedBytes.size) stagedBytes = stagedBytes.copyOf(required)
                    bytes.copyInto(stagedBytes, offset.toInt())
                    return bytes.size
                }

                override fun truncate(size: Long) {
                    stagedBytes = stagedBytes.copyOf(size.toInt())
                }

                override fun flush() {
                    contents[path] = stagedBytes.copyOf()
                    nodes[path] = LinuxVirtualFileNode(
                        path,
                        path.substringAfterLast('/'),
                        false,
                        stagedBytes.size.toLong(),
                        "etag-${stagedBytes.size}",
                    )
                }

                override fun close() {
                    if (failClose) error("Simulated close-time writeback failure")
                    flush()
                }
            }
        }

        override fun createDirectory(path: String) {
            nodes[path] = LinuxVirtualFileNode(path, path.substringAfterLast('/'), true, 0L, "dir-etag")
        }

        override fun delete(node: LinuxVirtualFileNode) {
            nodes.remove(node.path)
            contents.remove(node.path)
        }

        override fun move(node: LinuxVirtualFileNode, destinationPath: String) {
            nodes.remove(node.path)
            val content = contents.remove(node.path)
            nodes[destinationPath] = node.copy(
                path = destinationPath,
                name = destinationPath.substringAfterLast('/'),
            )
            if (content != null) contents[destinationPath] = content
        }

        override fun moveReplacing(
            node: LinuxVirtualFileNode,
            destination: LinuxVirtualFileNode,
            destinationPath: String,
        ) {
            require(nodes[destinationPath]?.remoteRevision == destination.remoteRevision)
            nodes.remove(destinationPath)
            contents.remove(destinationPath)
            move(node, destinationPath)
        }

        fun fileBytes(path: String): ByteArray = requireNotNull(contents[path])

        fun listCallCount(path: String): Int = listCalls.getOrDefault(path.trim('/'), 0)

        fun addFile(path: String, bytes: ByteArray) {
            contents[path] = bytes.copyOf()
            nodes[path] = LinuxVirtualFileNode(
                path = path,
                name = path.substringAfterLast('/'),
                directory = false,
                size = bytes.size.toLong(),
                remoteRevision = "etag-${bytes.size}",
            )
        }
    }

    private fun cachedNextcloudFile(path: String, etag: String) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = "application/octet-stream",
        size = 1L,
        lastModified = null,
        fileId = null,
        hasPreview = false,
        etag = etag,
    )

    private class MemoryLinuxVirtualMetadataStore : LinuxVirtualMetadataStore {
        private val snapshots = mutableMapOf<String, LinuxVirtualDirectorySnapshot>()

        @Synchronized
        override fun load(path: String): LinuxVirtualDirectorySnapshot? = snapshots[path]

        @Synchronized
        override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot): Boolean {
            snapshots[path] = snapshot
            return true
        }

        @Synchronized
        override fun invalidate(path: String) {
            val normalized = path.trim('/')
            val parent = normalized.substringBeforeLast('/', "")
            snapshots.keys.removeIf { cachedPath ->
                normalized.isEmpty() ||
                    cachedPath == normalized ||
                    cachedPath.startsWith("$normalized/") ||
                    cachedPath == parent
            }
        }

        @Synchronized
        override fun retainedPaths(): Set<String> = snapshots.keys.toSet()

        @Synchronized
        fun seed(path: String, snapshot: LinuxVirtualDirectorySnapshot) {
            snapshots[path] = snapshot
        }

        @Synchronized
        fun snapshot(path: String): LinuxVirtualDirectorySnapshot? = snapshots[path]
    }

    private class BlockingListBackend(
        private val delegate: LinuxVirtualFileBackend,
        private val blockedPath: String,
    ) : LinuxVirtualFileBackend by delegate {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val blockNext = AtomicBoolean(true)

        override fun list(path: String): List<LinuxVirtualFileNode> {
            val snapshot = delegate.list(path)
            if (path.trim('/') == blockedPath && blockNext.compareAndSet(true, false)) {
                started.countDown()
                check(release.await(2L, TimeUnit.SECONDS)) { "Timed out waiting to release the directory listing." }
            }
            return snapshot
        }
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        repeat(200) {
            if (condition()) return true
            TimeUnit.MILLISECONDS.sleep(10L)
        }
        return condition()
    }
}

private fun ByteBuffer.fuseName(): String {
    val copy = duplicate()
    val bytes = ByteArray(copy.remaining())
    copy.get(bytes)
    return bytes.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
}
