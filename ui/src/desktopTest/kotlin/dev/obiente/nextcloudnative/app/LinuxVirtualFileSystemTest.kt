package dev.obiente.nextcloudnative.app

import jnr.ffi.Runtime
import jnr.ffi.Pointer
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.serce.jnrfuse.ErrorCodes
import ru.serce.jnrfuse.struct.FileStat
import ru.serce.jnrfuse.struct.FuseFileInfo

class LinuxVirtualFileSystemTest {
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

        override fun resolve(path: String): LinuxVirtualFileNode? = nodes[path.trim('/')]

        override fun list(path: String): List<LinuxVirtualFileNode> {
            val parent = path.trim('/')
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
}

private fun ByteBuffer.fuseName(): String {
    val copy = duplicate()
    val bytes = ByteArray(copy.remaining())
    copy.get(bytes)
    return bytes.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
}
