package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal data class WindowsCloudFileIdentity(
    val accountId: String,
    val path: String,
    val remoteRevision: String,
    val size: Long,
    val directory: Boolean,
) {
    init {
        require(accountId.isNotBlank() && accountId.length <= MAX_ACCOUNT_ID_LENGTH)
        if (path.isNotEmpty()) FileOfflineKey(accountId, path)
        require(remoteRevision.isNotBlank() && remoteRevision.length <= MAX_REVISION_LENGTH)
        require(size >= 0L)
        require(!directory || size == 0L)
    }

    private companion object {
        const val MAX_ACCOUNT_ID_LENGTH = 256
        const val MAX_REVISION_LENGTH = 1_024
    }
}

/** Versioned, checksummed and strictly bounded identity persisted in Windows placeholders. */
internal object WindowsCloudFileIdentityCodec {
    fun encode(identity: WindowsCloudFileIdentity): ByteArray {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeShort(VERSION)
                output.writeBoolean(identity.directory)
                output.writeLong(identity.size)
                output.writeBoundedUtf8(identity.accountId, MAX_ACCOUNT_BYTES)
                output.writeBoundedUtf8(identity.path, MAX_PATH_BYTES)
                output.writeBoundedUtf8(identity.remoteRevision, MAX_REVISION_BYTES)
            }
            bytes.toByteArray()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return payload + digest
    }

    fun decode(bytes: ByteArray): WindowsCloudFileIdentity {
        require(bytes.size in MIN_IDENTITY_BYTES..MAX_IDENTITY_BYTES) {
            "The Windows placeholder identity has an invalid size."
        }
        val payload = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
        val expectedDigest = bytes.copyOfRange(bytes.size - DIGEST_BYTES, bytes.size)
        require(MessageDigest.getInstance("SHA-256").digest(payload).contentEquals(expectedDigest)) {
            "The Windows placeholder identity checksum is invalid."
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "The Windows placeholder identity type is invalid." }
            require(input.readUnsignedShort() == VERSION) { "The Windows placeholder identity version is unsupported." }
            val directory = input.readBoolean()
            val size = input.readLong()
            val accountId = input.readBoundedUtf8(MAX_ACCOUNT_BYTES)
            val path = input.readBoundedUtf8(MAX_PATH_BYTES)
            val revision = input.readBoundedUtf8(MAX_REVISION_BYTES)
            require(input.available() == 0) { "The Windows placeholder identity has trailing data." }
            WindowsCloudFileIdentity(accountId, path, revision, size, directory)
        }
    }

    private fun DataOutputStream.writeBoundedUtf8(value: String, maximumBytes: Int) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= maximumBytes)
        writeShort(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedUtf8(maximumBytes: Int): String {
        val length = readUnsignedShort()
        require(length <= maximumBytes && length <= available()) {
            "The Windows placeholder identity field is invalid."
        }
        val bytes = ByteArray(length).also(::readFully)
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    private const val MAGIC = 0x4E434656 // NCFV
    private const val VERSION = 1
    private const val DIGEST_BYTES = 32
    private const val MAX_ACCOUNT_BYTES = 256
    private const val MAX_PATH_BYTES = 3_072
    private const val MAX_REVISION_BYTES = 1_024
    private const val MAX_IDENTITY_BYTES = 4_096
    private const val MIN_IDENTITY_BYTES = 4 + 2 + 1 + 8 + 2 + 2 + 2 + DIGEST_BYTES
}

internal data class WindowsCloudHydrationRange(val offset: Long, val length: Int) {
    init {
        require(offset >= 0L && offset % WINDOWS_CLOUD_ALIGNMENT == 0L)
        require(length > 0)
    }
}

/** Produces CfExecute-compatible chunks; only the final range may end unaligned at EOF. */
internal fun planWindowsCloudHydration(
    requiredOffset: Long,
    requiredLength: Long,
    fileSize: Long,
    maximumChunkBytes: Int = 4 * 1024 * 1024,
): List<WindowsCloudHydrationRange> {
    require(requiredOffset >= 0L && requiredLength > 0L && fileSize > 0L)
    require(
        maximumChunkBytes.toLong() >= WINDOWS_CLOUD_ALIGNMENT &&
            maximumChunkBytes.toLong() % WINDOWS_CLOUD_ALIGNMENT == 0L,
    )
    val requiredEnd = minOf(fileSize, Math.addExact(requiredOffset, requiredLength))
    require(requiredOffset < requiredEnd)
    val transferEnd = if (requiredEnd == fileSize) {
        fileSize
    } else {
        minOf(fileSize, Math.addExact(requiredEnd, WINDOWS_CLOUD_ALIGNMENT - 1L) / WINDOWS_CLOUD_ALIGNMENT * WINDOWS_CLOUD_ALIGNMENT)
    }
    var cursor = requiredOffset - requiredOffset % WINDOWS_CLOUD_ALIGNMENT
    return buildList {
        while (cursor < transferEnd) {
            val remaining = transferEnd - cursor
            val length = minOf(maximumChunkBytes.toLong(), remaining).toInt()
            add(WindowsCloudHydrationRange(cursor, length))
            cursor += length
        }
    }
}

internal data class WindowsCloudCallbackInfo(
    val connectionKey: Long,
    val transferKey: Long,
    val requestKey: Long,
    val normalizedPath: String,
    val fileIdentity: ByteArray?,
    val fileSize: Long,
    val priorityHint: Int,
)

internal interface WindowsCloudFilesCallbacks {
    fun fetchData(info: WindowsCloudCallbackInfo, requiredOffset: Long, requiredLength: Long)
    fun cancelFetchData(info: WindowsCloudCallbackInfo, offset: Long, length: Long)
    fun fetchPlaceholders(info: WindowsCloudCallbackInfo, pattern: String?)
    fun cancelFetchPlaceholders(info: WindowsCloudCallbackInfo)
    fun closed(info: WindowsCloudCallbackInfo, deleted: Boolean)
    fun deleteRequested(info: WindowsCloudCallbackInfo)
    fun renameRequested(info: WindowsCloudCallbackInfo, targetPath: String)
}

internal data class WindowsCloudPlaceholder(
    val name: String,
    val identity: ByteArray,
    val size: Long,
    val directory: Boolean,
    val lastModifiedEpochMillis: Long? = null,
) {
    init {
        require(name.isNotBlank() && name.none { it == '/' || it == '\\' || it == '\u0000' })
        require(identity.size <= 4_096)
        require(size >= 0L)
    }
}

internal enum class WindowsCloudPlaceholderState {
    Absent,
    InSync,
    Dirty,
}

internal interface WindowsCloudFilesApi : AutoCloseable {
    fun registerSyncRoot(root: Path, syncRootIdentity: ByteArray)
    fun connect(root: Path, callbacks: WindowsCloudFilesCallbacks): Long
    fun disconnect(connectionKey: Long)
    fun createPlaceholders(baseDirectory: Path, placeholders: List<WindowsCloudPlaceholder>)
    fun transferData(info: WindowsCloudCallbackInfo, offset: Long, bytes: ByteArray)
    fun failData(info: WindowsCloudCallbackInfo, offset: Long, length: Long, message: String)
    fun completePlaceholderFetch(info: WindowsCloudCallbackInfo, placeholders: List<WindowsCloudPlaceholder>)
    fun failPlaceholderFetch(info: WindowsCloudCallbackInfo)
    fun acknowledgeDelete(info: WindowsCloudCallbackInfo, accepted: Boolean)
    fun acknowledgeRename(info: WindowsCloudCallbackInfo, accepted: Boolean)
    fun placeholderState(path: Path): WindowsCloudPlaceholderState
    fun allocatedBytes(path: Path): Long
    fun lastAccessedAtEpochMillis(path: Path): Long
    fun isPinned(path: Path): Boolean
    fun placeholderIdentity(path: Path): ByteArray?
    fun updatePlaceholder(
        path: Path,
        placeholder: WindowsCloudPlaceholder,
        invalidateContent: Boolean = false,
        preserveSyncState: Boolean = false,
    )
    fun convertToPlaceholder(path: Path, placeholder: WindowsCloudPlaceholder)
    fun markInSync(path: Path)
    fun dehydrate(path: Path): Long
}

internal interface WindowsCloudFileReadHandle : AutoCloseable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

internal data class WindowsCloudFilesSummary(
    val cachedBytes: Long,
    val reclaimableBytes: Long,
    val pinnedBytes: Long,
    val hydratedFileCount: Int,
    val pinnedFileCount: Int,
    val availableFreeBytes: Long,
    val pendingWritebackCount: Int,
    val failedWritebackCount: Int,
)

internal interface WindowsCloudFilesBackend {
    val accountId: String
    fun resolve(path: String): WindowsCloudFileIdentity?
    fun list(path: String): List<WindowsCloudFileIdentity>
    fun open(identity: WindowsCloudFileIdentity): WindowsCloudFileReadHandle
    fun upload(path: String, localFile: File, expectedRemoteRevision: String?): WindowsCloudFileIdentity
    fun createDirectory(path: String): WindowsCloudFileIdentity
    fun delete(identity: WindowsCloudFileIdentity)
    fun move(identity: WindowsCloudFileIdentity, destinationPath: String): WindowsCloudFileIdentity
}

internal class DesktopNextcloudWindowsCloudFilesBackend(
    private val session: NextcloudSession,
    private val userId: String,
    private val services: NextcloudPlatformServices,
    private val tree: DesktopFileSyncRemoteTree = DesktopFileSyncRemoteTree(session, userId, ""),
) : WindowsCloudFilesBackend {
    override val accountId: String = desktopFileCacheAccountId(session)

    override fun resolve(path: String): WindowsCloudFileIdentity? =
        tree.resolve(path.windowsCloudPath())?.toWindowsIdentity()

    override fun list(path: String): List<WindowsCloudFileIdentity> =
        tree.list(path.windowsCloudPath()).map { it.toWindowsIdentity() }

    override fun open(identity: WindowsCloudFileIdentity): WindowsCloudFileReadHandle {
        require(identity.accountId == accountId && !identity.directory && identity.size > 0L)
        val source = services.openFileRangeSession(
            session = session,
            userId = userId,
            path = identity.path,
            size = identity.size,
            expectedEtag = identity.remoteRevision,
        )
        return object : WindowsCloudFileReadHandle {
            override val size: Long = source.size
            override fun read(offset: Long, length: Int): ByteArray =
                runBlocking(Dispatchers.IO) { source.read(offset, length) }
            override fun close() = source.close()
        }
    }

    override fun upload(
        path: String,
        localFile: File,
        expectedRemoteRevision: String?,
    ): WindowsCloudFileIdentity = tree.writeFile(path.windowsCloudPath(), localFile, expectedRemoteRevision)
        .let { uploaded ->
            WindowsCloudFileIdentity(accountId, uploaded.relativePath, uploaded.etag, uploaded.size ?: localFile.length(), false)
        }

    override fun createDirectory(path: String): WindowsCloudFileIdentity {
        val normalized = path.windowsCloudPath()
        tree.createDirectory(normalized, expectedRemoteEtag = null)
        return requireNotNull(resolve(normalized))
    }

    override fun delete(identity: WindowsCloudFileIdentity) {
        require(identity.accountId == accountId)
        tree.delete(identity.path, identity.remoteRevision)
    }

    override fun move(identity: WindowsCloudFileIdentity, destinationPath: String): WindowsCloudFileIdentity {
        require(identity.accountId == accountId)
        val destination = destinationPath.windowsCloudPath()
        tree.move(identity.path, destination, identity.remoteRevision)
        return requireNotNull(resolve(destination))
    }

    private fun DesktopRemoteSyncDocument.toWindowsIdentity(): WindowsCloudFileIdentity =
        WindowsCloudFileIdentity(
            accountId = accountId,
            path = entry.relativePath,
            remoteRevision = entry.etag,
            size = entry.size ?: 0L,
            directory = isDirectory,
        )
}

/**
 * Windows Cloud Files provider lifecycle and callback coordinator.
 *
 * Native callbacks are dispatched away from the Cloud Filter thread. Hydration is generation
 * pinned, random-seek capable, cancellable, and transferred in 4 KiB aligned chunks. Namespace
 * mutations are accepted only after the corresponding ETag-guarded WebDAV operation succeeds.
 */
internal class WindowsCloudFilesProvider(
    private val root: Path,
    private val backend: WindowsCloudFilesBackend,
    private val api: WindowsCloudFilesApi,
    private val executor: ExecutorService = Executors.newFixedThreadPool(4) { work ->
        Thread(work, "nextcloud-windows-cloud-files").apply { isDaemon = true }
    },
    private val writebackRetryDelayMillis: (attempt: Int) -> Long = ::windowsWritebackRetryDelayMillis,
) : AutoCloseable, WindowsCloudFilesCallbacks {
    private val connection = AtomicLongState()
    private val cancelledRequests = ConcurrentHashMap<Long, AtomicBoolean>()
    private val knownIdentities = ConcurrentHashMap<String, WindowsCloudFileIdentity>()
    private val pathOperations = ConcurrentHashMap.newKeySet<String>()
    private val queuedPathOperations = ConcurrentHashMap<String, () -> Unit>()
    private val pendingWritebacks = ConcurrentHashMap.newKeySet<String>()
    private val failedWritebacks = ConcurrentHashMap.newKeySet<String>()
    private val writebackAttempts = ConcurrentHashMap<String, Int>()
    private val localChangeScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { work ->
        Thread(work, "nextcloud-windows-local-changes").apply { isDaemon = true }
    }
    private val pendingLocalChanges = ConcurrentHashMap<Path, ScheduledFuture<*>>()
    @Volatile private var watchService: WatchService? = null
    @Volatile private var watcherThread: Thread? = null

    fun start() {
        check(connection.get() == 0L) { "The Windows Cloud Files provider is already connected." }
        Files.createDirectories(root)
        check(!Files.isSymbolicLink(root)) { "The Windows Cloud Files root cannot be a symlink." }
        val rootIdentity = WindowsCloudFileIdentity(backend.accountId, "", "root", 0L, true)
        api.registerSyncRoot(root, WindowsCloudFileIdentityCodec.encode(rootIdentity))
        populateDirectory("", root)
        connection.set(api.connect(root, this))
        startLocalWatcher()
        executor.execute(::recoverLocalChanges)
    }

    override fun fetchData(info: WindowsCloudCallbackInfo, requiredOffset: Long, requiredLength: Long) {
        val cancellation = AtomicBoolean(false)
        cancelledRequests[info.requestKey] = cancellation
        executor.execute {
            val identity = runCatching { requireIdentity(info, expectDirectory = false) }.getOrElse { failure ->
                api.failData(info, requiredOffset, requiredLength, failure.message ?: "Invalid placeholder identity")
                cancelledRequests.remove(info.requestKey)
                return@execute
            }
            try {
                backend.open(identity).use { source ->
                    check(source.size == identity.size) { "The remote file generation has a different size." }
                    planWindowsCloudHydration(requiredOffset, requiredLength, identity.size).forEach { range ->
                        if (cancellation.get()) return@execute
                        val bytes = source.read(range.offset, range.length)
                        check(bytes.size == range.length) { "The remote file returned an incomplete range." }
                        api.transferData(info, range.offset, bytes)
                    }
                }
            } catch (failure: Throwable) {
                if (!cancellation.get()) {
                    api.failData(info, requiredOffset, requiredLength, failure.message ?: "Hydration failed")
                }
            } finally {
                cancelledRequests.remove(info.requestKey)
            }
        }
    }

    override fun cancelFetchData(info: WindowsCloudCallbackInfo, offset: Long, length: Long) {
        cancelledRequests[info.requestKey]?.set(true)
    }

    override fun fetchPlaceholders(info: WindowsCloudCallbackInfo, pattern: String?) {
        val cancellation = AtomicBoolean(false)
        cancelledRequests[info.requestKey] = cancellation
        executor.execute {
            try {
                val directory = requireIdentity(info, expectDirectory = true)
                val identities = backend.list(directory.path)
                    .filter { !cancellation.get() }
                    .filter { identity -> pattern.isNullOrBlank() || windowsWildcardMatches(pattern, identity.path.substringAfterLast('/')) }
                identities.forEach { identity -> knownIdentities[identity.path] = identity }
                val placeholders = identities.map(::placeholder)
                if (!cancellation.get()) api.completePlaceholderFetch(info, placeholders)
            } catch (_: Throwable) {
                if (!cancellation.get()) api.failPlaceholderFetch(info)
            } finally {
                cancelledRequests.remove(info.requestKey)
            }
        }
    }

    override fun cancelFetchPlaceholders(info: WindowsCloudCallbackInfo) {
        cancelledRequests[info.requestKey]?.set(true)
    }

    override fun closed(info: WindowsCloudCallbackInfo, deleted: Boolean) {
        if (deleted || info.fileIdentity == null) return
        val identity = runCatching { requireIdentity(info, expectDirectory = null) }.getOrNull() ?: return
        if (identity.directory) return
        val localPath = root.resolve(identity.path.replace('/', File.separatorChar)).normalize()
        if (!localPath.startsWith(root) || !Files.exists(localPath)) return
        if (api.placeholderState(localPath) != WindowsCloudPlaceholderState.Dirty) return
        pendingWritebacks += identity.path
        submitPathOperation(identity.path) {
            val current = api.placeholderIdentity(localPath)
                ?.let { encoded -> runCatching { WindowsCloudFileIdentityCodec.decode(encoded) }.getOrNull() }
                ?.takeIf { it.accountId == backend.accountId && it.path == identity.path && !it.directory }
                ?: knownIdentities[identity.path]
                ?: identity
            val uploaded = backend.upload(identity.path, localPath.toFile(), current.remoteRevision)
            knownIdentities[uploaded.path] = uploaded
            api.updatePlaceholder(localPath, placeholder(uploaded))
            api.markInSync(localPath)
        }
    }

    override fun deleteRequested(info: WindowsCloudCallbackInfo) {
        executor.execute {
            val accepted = runCatching {
                val identity = requireIdentity(info, expectDirectory = null)
                backend.delete(identity)
                knownIdentities.remove(identity.path)
            }.isSuccess
            api.acknowledgeDelete(info, accepted)
        }
    }

    override fun renameRequested(info: WindowsCloudCallbackInfo, targetPath: String) {
        executor.execute {
            val accepted = runCatching {
                val identity = requireIdentity(info, expectDirectory = null)
                val destination = relativePath(targetPath)
                val moved = backend.move(identity, destination)
                knownIdentities.remove(identity.path)
                knownIdentities[moved.path] = moved
                val destinationPath = root.resolve(destination.replace('/', File.separatorChar))
                api.updatePlaceholder(destinationPath, placeholder(moved))
                if (identity.directory) rebindMovedDescendants(identity, moved, destinationPath)
            }.isSuccess
            api.acknowledgeRename(info, accepted)
        }
    }

    /** Handles local files or complete directory trees that do not have Cloud Files identities yet. */
    fun localEntryChanged(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(root.toAbsolutePath().normalize()) || normalized == root) return
        if (!Files.exists(normalized) || api.placeholderState(normalized) != WindowsCloudPlaceholderState.Absent) return
        val relative = root.toAbsolutePath().normalize().relativize(normalized)
            .joinToString("/") { it.toString() }.windowsCloudPath()
        submitPathOperation(relative) {
            if (Files.isDirectory(normalized)) uploadLocalTree(normalized) else uploadLocalEntry(normalized, relative)
        }
    }

    fun freeUpSpace(requestedBytes: Long): Long {
        require(requestedBytes >= 0L)
        var freed = 0L
        knownIdentities.values.asSequence()
            .filter { !it.directory }
            .filter { identity -> !api.isPinned(localPath(identity)) }
            .sortedBy { identity -> api.lastAccessedAtEpochMillis(localPath(identity)) }
            .forEach { identity ->
                if (requestedBytes > 0L && freed >= requestedBytes) return@forEach
                val path = localPath(identity)
                if (path.startsWith(root) && api.placeholderState(path) == WindowsCloudPlaceholderState.InSync) {
                    val allocated = api.allocatedBytes(path)
                    api.dehydrate(path)
                    freed += allocated
                }
            }
        return freed
    }

    fun enforcePolicy(policy: VirtualFileCachePolicy, nowEpochMillis: Long = System.currentTimeMillis()): Long {
        if (!policy.automaticCleanup) return 0L
        val entries = knownIdentities.values.mapNotNull { identity ->
            if (identity.directory) return@mapNotNull null
            val path = localPath(identity)
            val allocated = api.allocatedBytes(path)
            if (allocated <= 0L) return@mapNotNull null
            val accessed = api.lastAccessedAtEpochMillis(path).coerceAtLeast(0L)
            VirtualFileCacheEntry(
                key = FileOfflineKey(backend.accountId, identity.path),
                remoteRevision = identity.remoteRevision,
                localRevision = identity.remoteRevision,
                sizeBytes = allocated,
                cachedAtEpochMillis = accessed,
                lastAccessedAtEpochMillis = accessed,
                retention = if (api.isPinned(path)) VirtualFileRetention.Pinned else VirtualFileRetention.Automatic,
                dirty = api.placeholderState(path) == WindowsCloudPlaceholderState.Dirty,
            )
        }
        val plan = planVirtualFileEviction(
            entries = entries,
            policy = policy,
            availableFreeBytes = Files.getFileStore(root).usableSpace,
            nowEpochMillis = nowEpochMillis,
        )
        var freed = 0L
        plan.evictions.forEach { eviction ->
            val identity = knownIdentities[eviction.key.relativePath] ?: return@forEach
            val path = localPath(identity)
            if (
                !api.isPinned(path) &&
                api.placeholderState(path) == WindowsCloudPlaceholderState.InSync &&
                api.allocatedBytes(path) == eviction.sizeBytes
            ) {
                api.dehydrate(path)
                freed += eviction.sizeBytes
            }
        }
        return freed
    }

    fun summary(): WindowsCloudFilesSummary {
        var cached = 0L
        var reclaimable = 0L
        var pinned = 0L
        var hydratedCount = 0
        var pinnedCount = 0
        knownIdentities.values.forEach { identity ->
            if (identity.directory) return@forEach
            val path = localPath(identity)
            val allocated = api.allocatedBytes(path).coerceAtLeast(0L)
            if (allocated > 0L) hydratedCount += 1
            cached += allocated
            if (api.isPinned(path)) {
                pinned += allocated
                pinnedCount += 1
            } else if (api.placeholderState(path) == WindowsCloudPlaceholderState.InSync) {
                reclaimable += allocated
            }
        }
        return WindowsCloudFilesSummary(
            cachedBytes = cached,
            reclaimableBytes = reclaimable,
            pinnedBytes = pinned,
            hydratedFileCount = hydratedCount,
            pinnedFileCount = pinnedCount,
            availableFreeBytes = Files.getFileStore(root).usableSpace,
            pendingWritebackCount = pendingWritebacks.size,
            failedWritebackCount = failedWritebacks.size,
        )
    }

    override fun close() {
        val key = connection.getAndSet(0L)
        if (key != 0L) runCatching { api.disconnect(key) }
        cancelledRequests.values.forEach { it.set(true) }
        cancelledRequests.clear()
        runCatching { watchService?.close() }
        watcherThread?.interrupt()
        watcherThread = null
        watchService = null
        pendingLocalChanges.values.forEach { it.cancel(false) }
        pendingLocalChanges.clear()
        queuedPathOperations.clear()
        localChangeScheduler.shutdownNow()
        executor.shutdownNow()
        api.close()
    }

    private fun populateDirectory(relativePath: String, localDirectory: Path) {
        val identities = backend.list(relativePath)
        val missing = ArrayList<WindowsCloudPlaceholder>()
        identities.forEach { identity ->
            val localPath = localDirectory.resolve(identity.path.substringAfterLast('/'))
            when (api.placeholderState(localPath)) {
                WindowsCloudPlaceholderState.Absent -> {
                    if (!Files.exists(localPath)) {
                        missing += placeholder(identity)
                        knownIdentities[identity.path] = identity
                    }
                }
                WindowsCloudPlaceholderState.InSync -> {
                    val previous = api.placeholderIdentity(localPath)
                        ?.let { encoded -> runCatching { WindowsCloudFileIdentityCodec.decode(encoded) }.getOrNull() }
                    val changed = previous == null ||
                        previous.accountId != identity.accountId ||
                        previous.path != identity.path ||
                        previous.remoteRevision != identity.remoteRevision ||
                        previous.size != identity.size ||
                        previous.directory != identity.directory
                    api.updatePlaceholder(
                        localPath,
                        placeholder(identity),
                        invalidateContent = changed && !identity.directory,
                    )
                    knownIdentities[identity.path] = identity
                }
                WindowsCloudPlaceholderState.Dirty -> {
                    val previous = api.placeholderIdentity(localPath)
                        ?.let { encoded -> runCatching { WindowsCloudFileIdentityCodec.decode(encoded) }.getOrNull() }
                    if (previous != null && previous.accountId == backend.accountId && previous.path == identity.path) {
                        knownIdentities[identity.path] = previous
                    }
                }
            }
        }
        api.createPlaceholders(localDirectory, missing)
    }

    private fun requireIdentity(
        info: WindowsCloudCallbackInfo,
        expectDirectory: Boolean?,
    ): WindowsCloudFileIdentity {
        val bytes = requireNotNull(info.fileIdentity) { "The Cloud Files callback has no identity." }
        val identity = WindowsCloudFileIdentityCodec.decode(bytes)
        require(identity.accountId == backend.accountId) { "The Cloud Files callback belongs to another account." }
        if (expectDirectory != null) require(identity.directory == expectDirectory)
        require(identity.size == info.fileSize || identity.directory) { "The Cloud Files callback size is stale." }
        knownIdentities[identity.path] = identity
        return identity
    }

    private fun placeholder(identity: WindowsCloudFileIdentity): WindowsCloudPlaceholder = WindowsCloudPlaceholder(
        name = identity.path.substringAfterLast('/').ifBlank { "Nextcloud Native" },
        identity = WindowsCloudFileIdentityCodec.encode(identity),
        size = identity.size,
        directory = identity.directory,
    )

    private fun localPath(identity: WindowsCloudFileIdentity): Path =
        root.resolve(identity.path.replace('/', File.separatorChar)).normalize()

    private fun relativePath(absoluteTarget: String): String {
        val target = Path.of(absoluteTarget).toAbsolutePath().normalize()
        val absoluteRoot = root.toAbsolutePath().normalize()
        require(target.startsWith(absoluteRoot) && target != absoluteRoot)
        return absoluteRoot.relativize(target).joinToString("/") { it.toString() }.windowsCloudPath()
    }

    private fun submitPathOperation(path: String, block: () -> Unit) {
        failedWritebacks -= path
        writebackAttempts.remove(path)
        val shouldSchedule = synchronized(queuedPathOperations) {
            queuedPathOperations[path] = block
            pathOperations.add(path)
        }
        if (shouldSchedule) schedulePathOperationDrain(path)
    }

    private fun schedulePathOperationDrain(path: String, delayMillis: Long = 0L) {
        require(delayMillis >= 0L)
        val drain = {
            var failedOperation: (() -> Unit)? = null
            try {
                while (true) {
                    val next = synchronized(queuedPathOperations) {
                        queuedPathOperations.remove(path)
                    } ?: break
                    try {
                        next()
                        failedWritebacks -= path
                        writebackAttempts.remove(path)
                    } catch (_: Throwable) {
                        failedOperation = next
                        break
                    }
                }
            } finally {
                var retryDelay: Long? = null
                var rescheduleImmediately = false
                val shouldReschedule = synchronized(queuedPathOperations) {
                    pathOperations.remove(path)
                    if (failedOperation != null) {
                        val attempt = writebackAttempts.merge(path, 1, Int::plus) ?: 1
                        failedWritebacks += path
                        if (attempt < MAX_WINDOWS_WRITEBACK_ATTEMPTS) {
                            queuedPathOperations.putIfAbsent(path, requireNotNull(failedOperation))
                            retryDelay = writebackRetryDelayMillis(attempt).coerceAtLeast(0L)
                            pathOperations.add(path)
                        } else if (queuedPathOperations.containsKey(path)) {
                            writebackAttempts.remove(path)
                            failedWritebacks -= path
                            rescheduleImmediately = pathOperations.add(path)
                        }
                        false
                    } else if (queuedPathOperations.containsKey(path)) {
                        pathOperations.add(path)
                    } else {
                        pendingWritebacks -= path
                        failedWritebacks -= path
                        writebackAttempts.remove(path)
                        false
                    }
                }
                if (shouldReschedule) schedulePathOperationDrain(path)
                if (rescheduleImmediately) schedulePathOperationDrain(path)
                retryDelay?.let { delay -> schedulePathOperationDrain(path, delay) }
            }
        }
        if (delayMillis == 0L) {
            runCatching { executor.execute(drain) }
        } else {
            runCatching {
                localChangeScheduler.schedule(
                    { runCatching { executor.execute(drain) } },
                    delayMillis,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    private fun startLocalWatcher() {
        val watcher = root.fileSystem.newWatchService()
        Files.walk(root).use { paths ->
            paths.filter(Files::isDirectory).forEach { directory -> directory.registerForWindowsCloudChanges(watcher) }
        }
        watchService = watcher
        watcherThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val key = try {
                    watcher.take()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    return@Thread
                }
                val directory = key.watchable() as? Path
                if (directory != null) {
                    key.pollEvents().forEach { event ->
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) return@forEach
                        val child = directory.resolve(event.context() as Path).toAbsolutePath().normalize()
                        if (
                            event.kind() == StandardWatchEventKinds.ENTRY_CREATE &&
                            Files.isDirectory(child) &&
                            !Files.isSymbolicLink(child)
                        ) {
                            runCatching {
                                Files.walk(child).use { descendants ->
                                    descendants.filter { path -> Files.isDirectory(path) && !Files.isSymbolicLink(path) }
                                        .forEach { descendant -> descendant.registerForWindowsCloudChanges(watcher) }
                                }
                            }
                        }
                        if (event.kind() != StandardWatchEventKinds.ENTRY_DELETE) scheduleLocalChange(child)
                    }
                }
                if (!key.reset()) continue
            }
        }, "nextcloud-windows-cloud-files-watcher").apply {
            isDaemon = true
            start()
        }
    }

    private fun scheduleLocalChange(path: Path) {
        pendingLocalChanges.remove(path)?.cancel(false)
        pendingLocalChanges[path] = localChangeScheduler.schedule(
            {
                pendingLocalChanges.remove(path)
                runCatching { localEntryChanged(path) }
            },
            LOCAL_CHANGE_SETTLE_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun recoverLocalChanges() {
        recoverLocalPlaceholders()
        val pendingDirectories = ArrayDeque<String>()
        pendingDirectories += ""
        var discovered = 0
        while (pendingDirectories.isNotEmpty() && discovered < MAX_RECOVERY_IDENTITIES) {
            val directory = pendingDirectories.removeFirst()
            val children = runCatching { backend.list(directory) }.getOrElse { emptyList() }
            children.forEach { identity ->
                knownIdentities[identity.path] = identity
                discovered += 1
                if (identity.directory) pendingDirectories += identity.path
            }
        }
        runCatching {
            val unmanaged = Files.walk(root).use { paths ->
                paths.filter { path -> path != root && Files.exists(path) }
                    .filter { path -> api.placeholderState(path) == WindowsCloudPlaceholderState.Absent }
                    .sorted(compareBy<Path> { it.nameCount })
                    .toList()
            }
            unmanaged.forEach { path ->
                val relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                    .joinToString("/") { it.toString() }.windowsCloudPath()
                runCatching { uploadLocalEntry(path, relative) }
            }
        }
    }

    private fun recoverLocalPlaceholders() {
        runCatching {
            Files.walk(root).use { paths ->
                paths.filter { path -> path != root && !Files.isSymbolicLink(path) }.forEach { local ->
                    val state = api.placeholderState(local)
                    if (state == WindowsCloudPlaceholderState.Absent) return@forEach
                    val directory = Files.isDirectory(local, LinkOption.NOFOLLOW_LINKS)
                    if (!directory && !Files.isRegularFile(local, LinkOption.NOFOLLOW_LINKS)) return@forEach
                    val original = api.placeholderIdentity(local)
                        ?.let { encoded -> runCatching { WindowsCloudFileIdentityCodec.decode(encoded) }.getOrNull() }
                        ?.takeIf { identity ->
                            identity.accountId == backend.accountId &&
                                identity.directory == directory &&
                                localPath(identity).toAbsolutePath().normalize() == local.toAbsolutePath().normalize()
                        }
                        ?: return@forEach
                    knownIdentities[original.path] = original
                    if (state != WindowsCloudPlaceholderState.Dirty || original.directory) return@forEach
                    pendingWritebacks += original.path
                    submitPathOperation(original.path) {
                        val current = requireNotNull(api.placeholderIdentity(local)) {
                            "The dirty Windows placeholder has no recoverable identity."
                        }.let(WindowsCloudFileIdentityCodec::decode)
                        require(
                            current.accountId == backend.accountId &&
                                current.path == original.path &&
                                !current.directory,
                        ) { "The dirty Windows placeholder identity is not safe to recover." }
                        val uploaded = backend.upload(current.path, local.toFile(), current.remoteRevision)
                        knownIdentities[uploaded.path] = uploaded
                        api.updatePlaceholder(local, placeholder(uploaded))
                        api.markInSync(local)
                    }
                }
            }
        }
    }

    private fun rebindMovedDescendants(
        originalDirectory: WindowsCloudFileIdentity,
        movedDirectory: WindowsCloudFileIdentity,
        localDirectory: Path,
    ) {
        if (!Files.isDirectory(localDirectory) || Files.isSymbolicLink(localDirectory)) return
        Files.walk(localDirectory).use { paths ->
            paths.filter { path -> path != localDirectory && !Files.isSymbolicLink(path) }
                .forEach { descendant ->
                    val suffix = localDirectory.relativize(descendant).joinToString("/") { it.toString() }.windowsCloudPath()
                    val expectedOriginalPath = "${originalDirectory.path}/$suffix"
                    val previous = api.placeholderIdentity(descendant)
                        ?.let { encoded -> runCatching { WindowsCloudFileIdentityCodec.decode(encoded) }.getOrNull() }
                        ?.takeIf { identity ->
                            identity.accountId == backend.accountId && identity.path == expectedOriginalPath
                        }
                        ?: return@forEach
                    val rebound = previous.copy(path = "${movedDirectory.path}/$suffix")
                    knownIdentities.remove(previous.path)
                    knownIdentities[rebound.path] = rebound
                    api.updatePlaceholder(
                        descendant,
                        placeholder(rebound),
                        preserveSyncState = true,
                    )
                }
        }
    }

    private fun uploadLocalEntry(localPath: Path, relativePath: String) {
        require(localPath.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
        require(!Files.isSymbolicLink(localPath)) { "Windows Cloud Files does not import symbolic links." }
        require(Files.isDirectory(localPath) || Files.isRegularFile(localPath)) {
            "Windows Cloud Files only imports regular files and folders."
        }
        pendingWritebacks += relativePath
        var completed = false
        try {
            val uploaded = try {
                if (Files.isDirectory(localPath)) {
                    backend.createDirectory(relativePath)
                } else {
                    backend.upload(relativePath, localPath.toFile(), expectedRemoteRevision = null)
                }
            } catch (failure: Throwable) {
                val reconciled = runCatching { backend.resolve(relativePath) }.getOrNull()
                    ?.takeIf { identity -> localEntryMatches(identity, localPath, relativePath) }
                if (reconciled == null) throw failure
                reconciled
            }
            knownIdentities[relativePath] = uploaded
            api.convertToPlaceholder(localPath, placeholder(uploaded))
            api.markInSync(localPath)
            completed = true
        } finally {
            if (completed) pendingWritebacks -= relativePath
        }
    }

    private fun uploadLocalTree(localDirectory: Path) {
        val absoluteRoot = root.toAbsolutePath().normalize()
        val normalizedDirectory = localDirectory.toAbsolutePath().normalize()
        require(normalizedDirectory.startsWith(absoluteRoot) && normalizedDirectory != absoluteRoot)
        val entries = Files.walk(normalizedDirectory).use { paths ->
            paths.limit(MAX_RECOVERY_IDENTITIES.toLong() + 1L)
                .sorted(compareBy<Path> { it.nameCount })
                .toList()
        }
        require(entries.size <= MAX_RECOVERY_IDENTITIES) {
            "The new Windows folder contains too many entries to import safely."
        }
        entries.forEach { entry ->
            val normalized = entry.toAbsolutePath().normalize()
            require(normalized.startsWith(normalizedDirectory) && !Files.isSymbolicLink(normalized)) {
                "The new Windows folder contains an unsafe entry."
            }
            if (Files.exists(normalized) && api.placeholderState(normalized) == WindowsCloudPlaceholderState.Absent) {
                val relative = absoluteRoot.relativize(normalized)
                    .joinToString("/") { it.toString() }.windowsCloudPath()
                uploadLocalEntry(normalized, relative)
            }
        }
    }

    private fun localEntryMatches(
        identity: WindowsCloudFileIdentity,
        localPath: Path,
        relativePath: String,
    ): Boolean {
        if (identity.accountId != backend.accountId || identity.path != relativePath) return false
        val localDirectory = Files.isDirectory(localPath)
        if (identity.directory != localDirectory) return false
        if (localDirectory) return true
        if (!Files.isRegularFile(localPath) || identity.size != Files.size(localPath)) return false
        if (identity.size == 0L) return true
        return runCatching {
            backend.open(identity).use remoteUse@ { remote ->
                if (remote.size != identity.size) return@remoteUse false
                Files.newInputStream(localPath).buffered().use localUse@ { local ->
                    var offset = 0L
                    val localBuffer = ByteArray(RECONCILIATION_CHUNK_BYTES)
                    while (offset < identity.size) {
                        val length = minOf(localBuffer.size.toLong(), identity.size - offset).toInt()
                        var localCount = 0
                        while (localCount < length) {
                            val read = local.read(localBuffer, localCount, length - localCount)
                            if (read < 0) return@localUse false
                            localCount += read
                        }
                        val remoteBytes = remote.read(offset, length)
                        if (remoteBytes.size != length || !remoteBytes.contentEquals(localBuffer.copyOf(length))) {
                            return@localUse false
                        }
                        offset += length
                    }
                    local.read() == -1
                }
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val LOCAL_CHANGE_SETTLE_MILLIS = 750L
        const val MAX_RECOVERY_IDENTITIES = 20_000
        const val RECONCILIATION_CHUNK_BYTES = 1024 * 1024
    }
}

private class AtomicLongState {
    @Volatile private var value: Long = 0L
    @Synchronized fun get(): Long = value
    @Synchronized fun set(next: Long) { value = next }
    @Synchronized fun getAndSet(next: Long): Long = value.also { value = next }
}

private fun windowsWildcardMatches(pattern: String, name: String): Boolean {
    if (pattern == "*" || pattern == "*.*") return true
    var patternIndex = 0
    var nameIndex = 0
    var starIndex = -1
    var retryNameIndex = -1
    while (nameIndex < name.length) {
        if (patternIndex < pattern.length && (pattern[patternIndex] == '?' || pattern[patternIndex].equals(name[nameIndex], true))) {
            patternIndex += 1
            nameIndex += 1
        } else if (patternIndex < pattern.length && pattern[patternIndex] == '*') {
            starIndex = patternIndex++
            retryNameIndex = nameIndex
        } else if (starIndex >= 0) {
            patternIndex = starIndex + 1
            nameIndex = ++retryNameIndex
        } else {
            return false
        }
    }
    while (patternIndex < pattern.length && pattern[patternIndex] == '*') patternIndex += 1
    return patternIndex == pattern.length
}

private fun String.windowsCloudPath(): String {
    val normalized = trim('/', '\\').replace('\\', '/')
    if (normalized.isEmpty()) return ""
    require(normalized.split('/').none { it.isEmpty() || it == "." || it == ".." })
    require('\u0000' !in normalized)
    return normalized
}

private const val WINDOWS_CLOUD_ALIGNMENT = 4 * 1024L
private const val MAX_WINDOWS_WRITEBACK_ATTEMPTS = 5

private fun windowsWritebackRetryDelayMillis(attempt: Int): Long {
    require(attempt in 1 until MAX_WINDOWS_WRITEBACK_ATTEMPTS)
    return (250L shl (attempt - 1)).coerceAtMost(30_000L)
}

private fun Path.registerForWindowsCloudChanges(watcher: WatchService) {
    register(
        watcher,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY,
        StandardWatchEventKinds.ENTRY_DELETE,
    )
}
