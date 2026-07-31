package dev.obiente.nextcloudnative.app

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import jnr.ffi.Pointer
import jnr.ffi.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ru.serce.jnrfuse.ErrorCodes
import ru.serce.jnrfuse.FuseFillDir
import ru.serce.jnrfuse.FuseStubFS
import ru.serce.jnrfuse.struct.FileStat
import ru.serce.jnrfuse.struct.FuseFileInfo

internal data class LinuxVirtualFileNode(
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val remoteRevision: String,
)

internal interface LinuxVirtualFileReadHandle : AutoCloseable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

internal interface LinuxVirtualFileWriteHandle : AutoCloseable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
    fun write(offset: Long, bytes: ByteArray): Int
    fun truncate(size: Long)
    fun flush()
}

internal interface LinuxVirtualFileBackend {
    fun resolve(path: String): LinuxVirtualFileNode?
    fun list(path: String): List<LinuxVirtualFileNode>
    fun open(node: LinuxVirtualFileNode): LinuxVirtualFileReadHandle
    fun openWrite(path: String, existing: LinuxVirtualFileNode?, truncate: Boolean): LinuxVirtualFileWriteHandle
    fun createDirectory(path: String)
    fun delete(node: LinuxVirtualFileNode)
    fun move(node: LinuxVirtualFileNode, destinationPath: String)
}

/** Generation-pinned WebDAV backend shared by the Linux FUSE adapter and its unit tests. */
internal class DesktopNextcloudVirtualFileBackend(
    private val session: NextcloudSession,
    private val userId: String,
    private val services: NextcloudPlatformServices,
    private val rangeCache: DesktopVirtualRangeCache,
    private val writebacks: DesktopLinuxVirtualFileWritebackStore,
    private val tree: DesktopFileSyncRemoteTree = DesktopFileSyncRemoteTree(session, userId, ""),
) : LinuxVirtualFileBackend {
    private val accountId = desktopFileCacheAccountId(session)

    override fun resolve(path: String): LinuxVirtualFileNode? {
        val normalized = path.linuxVirtualPath()
        if (normalized.isEmpty()) return ROOT_NODE
        return tree.resolve(normalized)?.toLinuxNode()
    }

    override fun list(path: String): List<LinuxVirtualFileNode> =
        tree.list(path.linuxVirtualPath()).map { document -> document.toLinuxNode() }

    override fun open(node: LinuxVirtualFileNode): LinuxVirtualFileReadHandle {
        require(!node.directory)
        require(node.size > 0L)
        val source = services.openFileRangeSession(
            session = session,
            userId = userId,
            path = node.path,
            size = node.size,
            expectedEtag = node.remoteRevision,
        )
        rangeCache.acquire(accountId, node.path)
        return object : LinuxVirtualFileReadHandle {
            override val size: Long = source.size

            override fun read(offset: Long, length: Int): ByteArray {
                require(offset >= 0L && length > 0 && offset + length <= size)
                val firstBlock = offset / RANGE_BLOCK_BYTES
                val lastBlock = (offset + length - 1L) / RANGE_BLOCK_BYTES
                val destination = ByteArray(length)
                for (block in firstBlock..lastBlock) {
                    val blockOffset = block * RANGE_BLOCK_BYTES
                    val blockLength = minOf(RANGE_BLOCK_BYTES, size - blockOffset).toInt()
                    val bytes = rangeCache.readBlock(
                        accountId = accountId,
                        path = node.path,
                        remoteRevision = node.remoteRevision,
                        fileSize = size,
                        offset = blockOffset,
                        length = blockLength,
                    ) ?: runBlocking(Dispatchers.IO) { source.read(blockOffset, blockLength) }.also { fetched ->
                        rangeCache.storeBlock(
                            accountId = accountId,
                            path = node.path,
                            remoteRevision = node.remoteRevision,
                            fileSize = size,
                            offset = blockOffset,
                            bytes = fetched,
                        )
                    }
                    val copyStart = maxOf(offset, blockOffset)
                    val copyEnd = minOf(offset + length, blockOffset + blockLength)
                    bytes.copyInto(
                        destination = destination,
                        destinationOffset = (copyStart - offset).toInt(),
                        startIndex = (copyStart - blockOffset).toInt(),
                        endIndex = (copyEnd - blockOffset).toInt(),
                    )
                }
                return destination
            }

            override fun close() {
                source.close()
                rangeCache.release(accountId, node.path)
            }
        }
    }

    override fun openWrite(
        path: String,
        existing: LinuxVirtualFileNode?,
        truncate: Boolean,
    ): LinuxVirtualFileWriteHandle = writebacks.open(
        path = path.linuxVirtualPath(),
        existing = existing,
        truncate = truncate,
        tree = tree,
        onCommitted = { committedPath -> rangeCache.invalidate(accountId, committedPath) },
    )

    override fun createDirectory(path: String) {
        val normalized = path.linuxVirtualPath()
        tree.createDirectory(normalized, expectedRemoteEtag = null)
        rangeCache.invalidate(accountId, normalized)
    }

    override fun delete(node: LinuxVirtualFileNode) {
        tree.delete(node.path, node.remoteRevision)
        rangeCache.invalidate(accountId, node.path)
    }

    override fun move(node: LinuxVirtualFileNode, destinationPath: String) {
        val normalized = destinationPath.linuxVirtualPath()
        tree.move(node.path, normalized, node.remoteRevision)
        rangeCache.invalidate(accountId, node.path)
        rangeCache.invalidate(accountId, normalized)
    }

    private fun DesktopRemoteSyncDocument.toLinuxNode(): LinuxVirtualFileNode = LinuxVirtualFileNode(
        path = entry.relativePath,
        name = entry.relativePath.substringAfterLast('/'),
        directory = isDirectory,
        size = entry.size ?: 0L,
        remoteRevision = entry.etag,
    )

    private companion object {
        const val RANGE_BLOCK_BYTES = 1024L * 1024L
        val ROOT_NODE = LinuxVirtualFileNode("", "Nextcloud", true, 0L, "root")
    }
}

/**
 * Linux filesystem provider for remote Nextcloud metadata and seekable file content.
 *
 * Reads use persistent revision-pinned blocks. Writes are staged durably, uploaded with ETag
 * preconditions, and surfaced through flush so applications receive a real error when writeback
 * cannot be committed. Failed close-time writeback remains in recovery storage.
 */
internal class LinuxNextcloudVirtualFileSystem(
    private val backend: LinuxVirtualFileBackend,
) : FuseStubFS() {
    private val nextHandle = AtomicLong(1L)
    private val readHandles = ConcurrentHashMap<Long, LinuxVirtualFileReadHandle>()
    private val writeHandles = ConcurrentHashMap<Long, LinuxVirtualFileWriteHandle>()
    private val pendingCreatedFiles = ConcurrentHashMap<String, Long>()

    override fun getattr(path: String, stat: FileStat): Int = fuseResult {
        val normalized = path.linuxVirtualPath()
        val pending = pendingCreatedFiles[normalized]?.let(writeHandles::get)
        val node = backend.resolve(normalized)
            ?: pending?.let { LinuxVirtualFileNode(normalized, normalized.substringAfterLast('/'), false, it.size, "pending") }
            ?: return -ErrorCodes.ENOENT()
        stat.st_mode.set(
            if (node.directory) FileStat.S_IFDIR or DIRECTORY_PERMISSIONS
            else FileStat.S_IFREG or FILE_PERMISSIONS,
        )
        stat.st_nlink.set(if (node.directory) 2 else 1)
        stat.st_size.set(node.size)
        stat.st_uid.set(context.uid.get())
        stat.st_gid.set(context.gid.get())
        0
    }

    override fun readdir(
        path: String,
        buffer: Pointer,
        filler: FuseFillDir,
        offset: Long,
        fileInfo: FuseFileInfo,
    ): Int = fuseResult {
        val normalized = path.linuxVirtualPath()
        val directory = backend.resolve(normalized) ?: return -ErrorCodes.ENOENT()
        if (!directory.directory) return -ErrorCodes.ENOTDIR()
        filler.apply(buffer, ".", null, 0L)
        filler.apply(buffer, "..", null, 0L)
        val visibleNames = LinkedHashSet<String>()
        backend.list(normalized).forEach { node ->
            visibleNames += node.name
            filler.apply(buffer, node.name, null, 0L)
        }
        pendingCreatedFiles.keys
            .asSequence()
            .filter { pending -> pending.substringBeforeLast('/', "") == normalized }
            .map { pending -> pending.substringAfterLast('/') }
            .filter(visibleNames::add)
            .sorted()
            .forEach { pendingName -> filler.apply(buffer, pendingName, null, 0L) }
        0
    }

    override fun open(path: String, fileInfo: FuseFileInfo): Int = fuseResult {
        val normalized = path.linuxVirtualPath()
        val node = backend.resolve(normalized) ?: return -ErrorCodes.ENOENT()
        if (node.directory) return -ErrorCodes.EISDIR()
        val flags = fileInfo.flags.intValue()
        val writeAccess = flags and OPEN_ACCESS_MASK != OPEN_READ_ONLY
        if (writeAccess) {
            val id = nextHandle.getAndIncrement()
            writeHandles[id] = backend.openWrite(normalized, node, truncate = flags and OPEN_TRUNCATE != 0)
            fileInfo.fh.set(id)
            return 0
        }
        if (node.size == 0L) {
            fileInfo.fh.set(EMPTY_FILE_HANDLE)
            return 0
        }
        val id = nextHandle.getAndIncrement()
        readHandles[id] = backend.open(node)
        fileInfo.fh.set(id)
        0
    }

    override fun read(
        path: String,
        buffer: Pointer,
        requestedSize: Long,
        offset: Long,
        fileInfo: FuseFileInfo,
    ): Int = fuseResult {
        if (offset < 0L || requestedSize < 0L || requestedSize > Int.MAX_VALUE) return -ErrorCodes.EINVAL()
        val id = fileInfo.fh.get()
        if (id == EMPTY_FILE_HANDLE) return 0
        val handle = readHandles[id]
        val writeHandle = writeHandles[id]
        if (handle == null && writeHandle == null) return -ErrorCodes.EBADF()
        val handleSize = handle?.size ?: requireNotNull(writeHandle).size
        if (offset >= handleSize) return 0
        val length = minOf(requestedSize, handleSize - offset).toInt()
        val bytes = handle?.read(offset, length) ?: requireNotNull(writeHandle).read(offset, length)
        check(bytes.size == length) { "The Linux virtual file range was incomplete." }
        buffer.put(0L, bytes, 0, bytes.size)
        bytes.size
    }

    override fun release(path: String, fileInfo: FuseFileInfo): Int {
        val id = fileInfo.fh.get()
        if (id != EMPTY_FILE_HANDLE) {
            readHandles.remove(id)?.close()
            val write = writeHandles.remove(id)
            if (write != null) {
                runCatching(write::close)
                pendingCreatedFiles.entries.removeIf { it.value == id }
            }
        }
        return 0
    }

    override fun access(path: String, mask: Int): Int = fuseResult {
        if (backend.resolve(path.linuxVirtualPath()) == null) -ErrorCodes.ENOENT() else 0
    }

    override fun create(path: String, mode: Long, fi: FuseFileInfo?): Int = fuseResult {
        val fileInfo = fi ?: return -ErrorCodes.EINVAL()
        val normalized = path.linuxVirtualPath()
        val parent = backend.resolve(normalized.substringBeforeLast('/', ""))
            ?: return -ErrorCodes.ENOENT()
        if (!parent.directory) return -ErrorCodes.ENOTDIR()
        if (backend.resolve(normalized) != null || pendingCreatedFiles.containsKey(normalized)) {
            return -ErrorCodes.EEXIST()
        }
        val id = nextHandle.getAndIncrement()
        writeHandles[id] = backend.openWrite(normalized, existing = null, truncate = true)
        pendingCreatedFiles[normalized] = id
        fileInfo.fh.set(id)
        0
    }

    override fun mkdir(path: String, mode: Long): Int = fuseResult {
        val normalized = path.linuxVirtualPath()
        val parent = backend.resolve(normalized.substringBeforeLast('/', ""))
            ?: return -ErrorCodes.ENOENT()
        if (!parent.directory) return -ErrorCodes.ENOTDIR()
        if (backend.resolve(normalized) != null) return -ErrorCodes.EEXIST()
        backend.createDirectory(normalized)
        0
    }

    override fun unlink(path: String): Int = deletePath(path, expectDirectory = false)

    override fun rmdir(path: String): Int = deletePath(path, expectDirectory = true)

    override fun rename(oldPath: String, newPath: String): Int = fuseResult {
        val sourcePath = oldPath.linuxVirtualPath()
        if (pendingCreatedFiles.containsKey(sourcePath)) return -ErrorCodes.EBUSY()
        val source = backend.resolve(sourcePath) ?: return -ErrorCodes.ENOENT()
        val destination = newPath.linuxVirtualPath()
        val parent = backend.resolve(destination.substringBeforeLast('/', ""))
            ?: return -ErrorCodes.ENOENT()
        if (!parent.directory) return -ErrorCodes.ENOTDIR()
        if (backend.resolve(destination) != null || pendingCreatedFiles.containsKey(destination)) {
            return -ErrorCodes.EEXIST()
        }
        backend.move(source, destination)
        0
    }

    override fun truncate(path: String, size: Long): Int = fuseResult {
        val normalized = path.linuxVirtualPath()
        val existing = backend.resolve(normalized) ?: return -ErrorCodes.ENOENT()
        if (existing.directory) return -ErrorCodes.EISDIR()
        backend.openWrite(normalized, existing, truncate = false).use { handle ->
            handle.truncate(size)
            handle.flush()
        }
        0
    }

    override fun write(path: String, buf: Pointer, size: Long, offset: Long, fi: FuseFileInfo): Int = fuseResult {
        if (offset < 0L || size < 0L || size > Int.MAX_VALUE) return -ErrorCodes.EINVAL()
        val handle = writeHandles[fi.fh.get()] ?: return -ErrorCodes.EBADF()
        val bytes = ByteArray(size.toInt())
        buf.get(0L, bytes, 0, bytes.size)
        handle.write(offset, bytes)
    }

    override fun flush(path: String, fi: FuseFileInfo): Int = fuseResult {
        writeHandles[fi.fh.get()]?.flush()
        0
    }

    override fun fsync(path: String, isDataSync: Int, fi: FuseFileInfo): Int = flush(path, fi)

    fun mountAt(mountPoint: Path, blocking: Boolean = false, debug: Boolean = false) {
        require(Platform.getNativePlatform().os == Platform.OS.LINUX) {
            "The Linux virtual filesystem can only be mounted on Linux."
        }
        require(mountPoint.toFile().let { it.isDirectory && it.canWrite() }) {
            "The Linux virtual filesystem mount point must be a writable directory."
        }
        mount(mountPoint, blocking, debug, arrayOf("-o", "fsname=nextcloud-native", "-o", "default_permissions"))
    }

    fun unmount() {
        readHandles.values.forEach { runCatching(it::close) }
        writeHandles.values.forEach { runCatching(it::close) }
        readHandles.clear()
        writeHandles.clear()
        pendingCreatedFiles.clear()
        umount()
    }

    private fun deletePath(path: String, expectDirectory: Boolean): Int = fuseResult {
        val normalized = path.linuxVirtualPath()
        if (pendingCreatedFiles.containsKey(normalized)) return -ErrorCodes.EBUSY()
        val node = backend.resolve(normalized) ?: return -ErrorCodes.ENOENT()
        if (node.directory != expectDirectory) {
            return if (expectDirectory) -ErrorCodes.ENOTDIR() else -ErrorCodes.EISDIR()
        }
        backend.delete(node)
        0
    }

    private inline fun fuseResult(operation: () -> Int): Int = try {
        operation()
    } catch (_: IllegalArgumentException) {
        -ErrorCodes.EINVAL()
    } catch (_: Throwable) {
        -ErrorCodes.EIO()
    }

    private companion object {
        const val DIRECTORY_PERMISSIONS = 0b111101101 // 0755
        const val FILE_PERMISSIONS = 0b110100100 // 0644
        const val EMPTY_FILE_HANDLE = 0L
        const val OPEN_ACCESS_MASK = 0x3
        const val OPEN_READ_ONLY = 0x0
        const val OPEN_TRUNCATE = 0x200
    }
}

private fun String.linuxVirtualPath(): String {
    val normalized = trim('/')
    if (normalized.isEmpty()) return ""
    require(normalized.split('/').none { it.isEmpty() || it == "." || it == ".." })
    require('\u0000' !in normalized)
    return normalized
}
