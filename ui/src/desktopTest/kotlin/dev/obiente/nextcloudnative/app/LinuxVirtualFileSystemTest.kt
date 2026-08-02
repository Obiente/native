package dev.obiente.nextcloudnative.app

import jnr.ffi.Runtime
import jnr.ffi.Pointer
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.serce.jnrfuse.ErrorCodes
import ru.serce.jnrfuse.struct.FileStat
import ru.serce.jnrfuse.struct.FuseFileInfo

class LinuxVirtualFileSystemTest {
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
        assertTrue(waitUntil { attempts.get() == 1 })
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
    fun `persisting a large snapshot does not block cached metadata reads`() {
        val delegate = MutableFixtureBackend().apply { addFile("Photos/cached.dat", byteArrayOf(1)) }
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? = null
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot) {
                persistenceStarted.countDown()
                check(releasePersistence.await(2L, TimeUnit.SECONDS))
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
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot) = Unit
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
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot) = Unit
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
        val store = object : LinuxVirtualMetadataStore {
            override fun load(path: String): LinuxVirtualDirectorySnapshot? = null
            override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot) = Unit
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
        cached.close()
    }

    @Test
    fun `persisted metadata is reused only for the same remote revision`() {
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
            assertEquals("image/x-raw", unchanged.mimeType)
            assertEquals("RGDNVW", unchanged.permissions)
            assertEquals(listOf("SHA256:abc"), unchanged.checksums)

            store.store(
                "Photos",
                LinuxVirtualDirectorySnapshot(
                    listOf(LinuxVirtualFileNode(previous.path, previous.name, false, 8L, "revision-2")),
                    fetchedAtEpochMillis = 3L,
                ),
            )
            val replaced = cache.cachedListing(accountId, "Photos").orEmpty().single()
            assertEquals("revision-2", replaced.etag)
            assertEquals(8L, replaced.size)
            assertNull(replaced.mimeType)
            assertNull(replaced.lastModified)
            assertNull(replaced.fileId)
            assertTrue(!replaced.hasPreview)
            assertNull(replaced.permissions)
            assertTrue(replaced.checksums.isEmpty())

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
        } finally {
            runCatching { preferences.removeNode() }
            root.toFile().deleteRecursively()
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

    private class MemoryLinuxVirtualMetadataStore : LinuxVirtualMetadataStore {
        private val snapshots = mutableMapOf<String, LinuxVirtualDirectorySnapshot>()

        override fun load(path: String): LinuxVirtualDirectorySnapshot? = snapshots[path]

        override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot) {
            snapshots[path] = snapshot
        }

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

        fun seed(path: String, snapshot: LinuxVirtualDirectorySnapshot) {
            snapshots[path] = snapshot
        }

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
