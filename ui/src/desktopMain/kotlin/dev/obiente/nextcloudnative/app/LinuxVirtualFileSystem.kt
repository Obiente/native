package dev.obiente.nextcloudnative.app

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

internal fun DesktopRemoteSyncDocument.toLinuxVirtualFileNode(): LinuxVirtualFileNode = LinuxVirtualFileNode(
    path = entry.relativePath,
    name = entry.relativePath.substringAfterLast('/'),
    directory = isDirectory,
    size = entry.size ?: 0L,
    remoteRevision = entry.etag,
)

internal interface LinuxVirtualFileReadHandle : AutoCloseable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
    fun readdress(path: String) = Unit
}

internal interface LinuxVirtualFileWriteHandle : AutoCloseable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
    fun write(offset: Long, bytes: ByteArray): Int
    fun truncate(size: Long)
    fun flush()
}

internal interface LinuxVirtualFileBackend : AutoCloseable {
    fun resolve(path: String): LinuxVirtualFileNode?
    fun list(path: String): List<LinuxVirtualFileNode>
    fun open(node: LinuxVirtualFileNode): LinuxVirtualFileReadHandle
    fun openWrite(path: String, existing: LinuxVirtualFileNode?, truncate: Boolean): LinuxVirtualFileWriteHandle
    fun createDirectory(path: String)
    fun delete(node: LinuxVirtualFileNode)
    fun move(node: LinuxVirtualFileNode, destinationPath: String)
    fun moveReplacing(
        node: LinuxVirtualFileNode,
        destination: LinuxVirtualFileNode,
        destinationPath: String,
    )

    override fun close() = Unit
}

internal data class LinuxVirtualDirectorySnapshot(
    val nodes: List<LinuxVirtualFileNode>,
    val fetchedAtEpochMillis: Long,
    val generation: Long = 0L,
) {
    val nodesByPath: Map<String, LinuxVirtualFileNode> = nodes.associateBy(LinuxVirtualFileNode::path)

    init {
        require(nodesByPath.size == nodes.size) { "A Linux directory snapshot contains duplicate paths." }
    }
}

internal interface LinuxVirtualMetadataStore {
    fun load(path: String): LinuxVirtualDirectorySnapshot?
    fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot)
    fun invalidate(path: String)
}

internal class DesktopLinuxVirtualMetadataStore(
    private val cache: DesktopFileReadCache,
    private val accountId: String,
) : LinuxVirtualMetadataStore {
    override fun load(path: String): LinuxVirtualDirectorySnapshot? {
        val listing = cache.cachedListingSnapshot(accountId, path) ?: return null
        val nodes = listing.files.mapNotNull { file ->
            val revision = file.etag?.takeIf(String::isNotBlank) ?: return null
            LinuxVirtualFileNode(
                path = file.path,
                name = file.name,
                directory = file.isDirectory,
                size = file.size ?: 0L,
                remoteRevision = revision,
            )
        }
        return LinuxVirtualDirectorySnapshot(nodes, listing.fetchedAtEpochMillis)
    }

    override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot) {
        val previous = cache.cachedListing(accountId, path).orEmpty().associateBy(NextcloudFile::path)
        val files = snapshot.nodes.map { node ->
            previous[node.path]?.takeIf { file -> file.etag == node.remoteRevision }?.copy(
                name = node.name,
                isDirectory = node.directory,
                size = node.size.takeUnless { node.directory },
                etag = node.remoteRevision,
            ) ?: NextcloudFile(
                path = node.path,
                name = node.name,
                isDirectory = node.directory,
                mimeType = null,
                size = node.size.takeUnless { node.directory },
                lastModified = null,
                fileId = null,
                hasPreview = false,
                etag = node.remoteRevision,
            )
        }
        cache.storeListingUnlessNewer(accountId, path, files, snapshot.fetchedAtEpochMillis)
    }

    override fun invalidate(path: String) = cache.invalidate(accountId, path)
}

internal class RetainedLinuxVirtualMetadataStore(
    private val rangeCache: DesktopVirtualRangeCache,
    private val accountId: String,
    private val fallback: LinuxVirtualMetadataStore,
) : LinuxVirtualMetadataStore {
    override fun load(path: String): LinuxVirtualDirectorySnapshot? =
        rangeCache.loadRetainedListing(accountId, path) ?: fallback.load(path)

    override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot) = fallback.store(path, snapshot)

    override fun invalidate(path: String) {
        rangeCache.invalidateRetainedListings(accountId, path)
        fallback.invalidate(path)
    }
}

/**
 * Coalesces directory reads and serves a persisted snapshot before refreshing stale metadata.
 *
 * File managers typically follow one readdir with a getattr for every visible child. Resolving a
 * child from its cached parent snapshot prevents that access pattern from becoming one WebDAV
 * PROPFIND per entry. A stale snapshot remains useful while one daemon refresh updates it.
 */
internal class CachingLinuxVirtualFileBackend(
    private val delegate: LinuxVirtualFileBackend,
    private val store: LinuxVirtualMetadataStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val freshForMillis: Long = DEFAULT_FRESH_MILLIS,
    private val refreshRetryBaseMillis: Long = DEFAULT_REFRESH_RETRY_BASE_MILLIS,
    private val refreshRetryMaxMillis: Long = DEFAULT_REFRESH_RETRY_MAX_MILLIS,
    private val maximumRetainedMetadataEntries: Int = DEFAULT_MAX_RETAINED_METADATA_ENTRIES,
    private val maximumRetainedDirectories: Int = DEFAULT_MAX_RETAINED_DIRECTORIES,
    private val refreshExecutor: ExecutorService = defaultLinuxMetadataRefreshExecutor(),
    private val afterPersistedInvalidationMarked: () -> Unit = {},
) : LinuxVirtualFileBackend {
    private val snapshots = LinkedHashMap<String, LinuxVirtualDirectorySnapshot>(16, 0.75f, true)
    private val refreshes = ConcurrentHashMap<String, CompletableFuture<LinuxVirtualDirectorySnapshot?>>()
    private val refreshFailures = ConcurrentHashMap<String, LinuxVirtualRefreshFailure>()
    private val metadataLock = Any()
    private val persistedStoreLock = Any()
    private val pendingPersistedInvalidations = mutableMapOf<String, Int>()
    private val failedPersistedInvalidations = mutableSetOf<String>()
    private val revalidatedPersistedListings = linkedSetOf<String>()
    private val activeMetadataOperations = mutableSetOf<LinuxVirtualMetadataOperation>()
    private val pendingPersistenceLock = Any()
    private var pendingPersistenceEntries = 0L
    private var pendingPersistenceDirectories = 0
    private val nextGeneration = AtomicLong(1L)
    @Volatile
    private var closed = false

    init {
        require(freshForMillis >= 0L)
        require(refreshRetryBaseMillis > 0L)
        require(refreshRetryMaxMillis >= refreshRetryBaseMillis)
        require(maximumRetainedMetadataEntries > 0)
        require(maximumRetainedDirectories > 0)
    }

    override fun resolve(path: String): LinuxVirtualFileNode? {
        val normalized = path.linuxVirtualPath()
        if (normalized.isEmpty()) return ROOT_NODE
        val parent = normalized.substringBeforeLast('/', "")
        return snapshot(parent).nodesByPath[normalized]
    }

    override fun list(path: String): List<LinuxVirtualFileNode> = snapshot(path.linuxVirtualPath()).nodes

    internal fun hasRecordedRefreshFailure(path: String): Boolean =
        refreshFailures.containsKey(path.linuxVirtualPath())

    private fun snapshot(normalized: String): LinuxVirtualDirectorySnapshot {
        synchronized(metadataLock) { snapshots[normalized] }?.let { cached ->
            if (!cached.isFresh(nowEpochMillis(), freshForMillis)) refreshAsync(normalized)
            return cached
        }
        val operation = beginMetadataOperation(normalized)
        val persisted = try {
            synchronized(persistedStoreLock) {
                val invalidationPending = synchronized(metadataLock) {
                    pendingPersistedInvalidations.keys.any { mutation -> mutation.invalidatesListing(normalized) } ||
                        normalized !in revalidatedPersistedListings &&
                        failedPersistedInvalidations.any { mutation -> mutation.invalidatesListing(normalized) }
                }
                if (invalidationPending) null else store.load(normalized)
            }
        } catch (failure: Throwable) {
            endMetadataOperation(operation)
            throw failure
        }
        val cached = try {
            persisted?.let { restored ->
                synchronized(metadataLock) {
                    if (!operation.invalidated) {
                        snapshots[normalized] ?: restored.also { retainSnapshot(normalized, it) }
                    } else {
                        null
                    }
                }
            }
        } finally {
            endMetadataOperation(operation)
        }
        if (cached != null) {
            if (!cached.isFresh(nowEpochMillis(), freshForMillis)) refreshAsync(normalized)
            return cached
        }
        return refreshBlocking(normalized)
    }

    override fun open(node: LinuxVirtualFileNode): LinuxVirtualFileReadHandle = delegate.open(node)

    override fun openWrite(
        path: String,
        existing: LinuxVirtualFileNode?,
        truncate: Boolean,
    ): LinuxVirtualFileWriteHandle {
        val normalized = path.linuxVirtualPath()
        val handle = delegate.openWrite(normalized, existing, truncate)
        return object : LinuxVirtualFileWriteHandle {
            private var dirty = truncate

            override val size: Long get() = handle.size
            override fun read(offset: Long, length: Int): ByteArray = handle.read(offset, length)
            @Synchronized
            override fun write(offset: Long, bytes: ByteArray): Int = handle.write(offset, bytes).also { written ->
                if (written > 0) dirty = true
            }

            @Synchronized
            override fun truncate(size: Long) {
                handle.truncate(size)
                dirty = true
            }

            @Synchronized
            override fun flush() {
                handle.flush()
                if (dirty) {
                    invalidate(normalized)
                    dirty = false
                }
            }

            @Synchronized
            override fun close() {
                handle.close()
                if (dirty) {
                    invalidate(normalized)
                    dirty = false
                }
            }
        }
    }

    override fun createDirectory(path: String) {
        val normalized = path.linuxVirtualPath()
        delegate.createDirectory(normalized)
        invalidate(normalized)
    }

    override fun delete(node: LinuxVirtualFileNode) {
        delegate.delete(node)
        invalidate(node.path)
    }

    override fun move(node: LinuxVirtualFileNode, destinationPath: String) {
        val destination = destinationPath.linuxVirtualPath()
        delegate.move(node, destination)
        invalidate(node.path)
        invalidate(destination)
    }

    override fun moveReplacing(
        node: LinuxVirtualFileNode,
        destination: LinuxVirtualFileNode,
        destinationPath: String,
    ) {
        val normalized = destinationPath.linuxVirtualPath()
        delegate.moveReplacing(node, destination, normalized)
        invalidate(node.path)
        invalidate(normalized)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        refreshExecutor.shutdownNow()
        synchronized(metadataLock) {
            activeMetadataOperations.clear()
            pendingPersistedInvalidations.clear()
            failedPersistedInvalidations.clear()
            revalidatedPersistedListings.clear()
        }
        delegate.close()
    }

    private fun refreshBlocking(path: String): LinuxVirtualDirectorySnapshot {
        while (true) {
            val candidate = CompletableFuture<LinuxVirtualDirectorySnapshot?>()
            val existing = refreshes.putIfAbsent(path, candidate)
            val future = existing ?: candidate.also { refresh(path, it) }
            val refreshed = future.get() ?: continue
            val stillCurrent = synchronized(metadataLock) {
                snapshots[path]?.generation == refreshed.generation
            }
            if (stillCurrent) return refreshed
        }
    }

    private fun refreshAsync(path: String) {
        if (closed) return
        val now = nowEpochMillis().coerceAtLeast(0L)
        refreshFailures[path]?.let { failure ->
            if (now < failure.recordedAtEpochMillis) {
                refreshFailures.remove(path, failure)
            } else if (now < failure.retryAtEpochMillis) {
                return
            }
        }
        val candidate = CompletableFuture<LinuxVirtualDirectorySnapshot?>()
        if (refreshes.putIfAbsent(path, candidate) != null) return
        runCatching { refreshExecutor.execute { refresh(path, candidate) } }
            .onFailure { failure ->
                recordRefreshFailure(path)
                candidate.completeExceptionally(failure)
                refreshes.remove(path, candidate)
            }
    }

    private fun refresh(path: String, future: CompletableFuture<LinuxVirtualDirectorySnapshot?>) {
        val operation = beginMetadataOperation(path)
        var persistenceScheduled = false
        try {
            check(!closed) { "The Linux metadata cache is closed." }
            val snapshot = LinuxVirtualDirectorySnapshot(
                nodes = delegate.list(path),
                fetchedAtEpochMillis = nowEpochMillis().coerceAtLeast(0L),
                generation = nextGeneration.getAndIncrement(),
            )
            val published = synchronized(metadataLock) {
                if (!closed && !operation.invalidated) {
                    retainSnapshot(path, snapshot)
                    true
                } else {
                    false
                }
            }
            if (published) {
                refreshFailures.remove(path)
                future.complete(snapshot)
                persistenceScheduled = persistAsync(path, snapshot, operation)
            } else {
                future.complete(null)
            }
        } catch (failure: Throwable) {
            if (!closed) recordRefreshFailure(path)
            future.completeExceptionally(failure)
        } finally {
            if (!persistenceScheduled) endMetadataOperation(operation)
            refreshes.remove(path, future)
        }
    }

    private fun persistAsync(
        path: String,
        snapshot: LinuxVirtualDirectorySnapshot,
        operation: LinuxVirtualMetadataOperation,
    ): Boolean {
        val entryWeight = snapshot.nodes.size.coerceAtLeast(1).toLong()
        if (closed || !reservePendingPersistence(entryWeight)) return false
        val scheduled = runCatching {
            refreshExecutor.execute {
                try {
                    val current = synchronized(metadataLock) {
                        !closed &&
                            !operation.invalidated &&
                            snapshots[path]?.generation == snapshot.generation
                    }
                    if (current) synchronized(persistedStoreLock) {
                        val stillCurrent = synchronized(metadataLock) {
                            !closed &&
                                !operation.invalidated &&
                                snapshots[path]?.generation == snapshot.generation
                        }
                        if (stillCurrent && runCatching { store.store(path, snapshot) }.isSuccess) {
                            synchronized(metadataLock) {
                                if (
                                    !closed &&
                                    !operation.invalidated &&
                                    snapshots[path]?.generation == snapshot.generation
                                ) {
                                    rememberRevalidatedPersistedListing(path)
                                }
                            }
                        }
                    }
                } finally {
                    releasePendingPersistence(entryWeight)
                    endMetadataOperation(operation)
                }
            }
        }.isSuccess
        if (!scheduled) releasePendingPersistence(entryWeight)
        return scheduled
    }

    private fun reservePendingPersistence(entryWeight: Long): Boolean = synchronized(pendingPersistenceLock) {
        if (
            pendingPersistenceDirectories >= maximumRetainedDirectories ||
            entryWeight > maximumRetainedMetadataEntries.toLong() - pendingPersistenceEntries
        ) {
            return@synchronized false
        }
        pendingPersistenceDirectories += 1
        pendingPersistenceEntries += entryWeight
        true
    }

    private fun releasePendingPersistence(entryWeight: Long) = synchronized(pendingPersistenceLock) {
        pendingPersistenceDirectories = (pendingPersistenceDirectories - 1).coerceAtLeast(0)
        pendingPersistenceEntries = (pendingPersistenceEntries - entryWeight).coerceAtLeast(0L)
    }

    private fun invalidate(path: String) {
        val normalized = path.linuxVirtualPath()
        val parent = normalized.substringBeforeLast('/', "")
        synchronized(metadataLock) {
            val knownPaths = buildSet {
                add(normalized)
                add(parent)
                addAll(snapshots.keys)
                addAll(refreshes.keys)
                addAll(refreshFailures.keys)
            }
            knownPaths.filterTo(mutableSetOf()) { cachedPath ->
                normalized.invalidatesListing(cachedPath)
            }.forEach { cachedPath ->
                snapshots.remove(cachedPath)
                refreshFailures.remove(cachedPath)
            }
            activeMetadataOperations
                .filter { operation -> normalized.invalidatesListing(operation.path) }
                .forEach { operation -> operation.invalidated = true }
            revalidatedPersistedListings.removeIf { listing -> normalized.invalidatesListing(listing) }
            pendingPersistedInvalidations[normalized] =
                pendingPersistedInvalidations.getOrDefault(normalized, 0) + 1
        }
        afterPersistedInvalidationMarked()
        var persistedInvalidated = false
        try {
            synchronized(persistedStoreLock) {
                persistedInvalidated = runCatching { store.invalidate(normalized) }.isSuccess
            }
        } finally {
            synchronized(metadataLock) {
                if (persistedInvalidated) {
                    failedPersistedInvalidations.removeIf { failed ->
                        normalized.isEmpty() || failed == normalized || failed.startsWith("$normalized/")
                    }
                } else {
                    rememberFailedPersistedInvalidation(normalized)
                }
                val remaining = pendingPersistedInvalidations.getOrDefault(normalized, 1) - 1
                if (remaining <= 0) pendingPersistedInvalidations.remove(normalized)
                else pendingPersistedInvalidations[normalized] = remaining
            }
        }
    }

    private fun rememberFailedPersistedInvalidation(path: String) {
        check(Thread.holdsLock(metadataLock))
        if (failedPersistedInvalidations.any { failed ->
                failed.isEmpty() || failed == path || path.startsWith("$failed/")
            }
        ) {
            return
        }
        failedPersistedInvalidations.removeIf { failed -> path.isEmpty() || failed.startsWith("$path/") }
        failedPersistedInvalidations += path
        if (failedPersistedInvalidations.size > MAX_FAILED_PERSISTED_INVALIDATIONS) {
            failedPersistedInvalidations.clear()
            failedPersistedInvalidations += ""
        }
    }

    private fun rememberRevalidatedPersistedListing(path: String) {
        check(Thread.holdsLock(metadataLock))
        revalidatedPersistedListings.remove(path)
        revalidatedPersistedListings += path
        while (revalidatedPersistedListings.size > MAX_REVALIDATED_PERSISTED_LISTINGS) {
            revalidatedPersistedListings.remove(revalidatedPersistedListings.first())
        }
    }

    private fun retainSnapshot(path: String, snapshot: LinuxVirtualDirectorySnapshot) {
        check(Thread.holdsLock(metadataLock))
        snapshots[path] = snapshot
        var retainedEntries = snapshots.values.sumOf { retained -> retained.nodes.size }
        val iterator = snapshots.entries.iterator()
        while (
            (retainedEntries > maximumRetainedMetadataEntries || snapshots.size > maximumRetainedDirectories) &&
            snapshots.size > 1 &&
            iterator.hasNext()
        ) {
            val evicted = iterator.next()
            retainedEntries -= evicted.value.nodes.size
            iterator.remove()
        }
    }

    private fun beginMetadataOperation(path: String): LinuxVirtualMetadataOperation =
        synchronized(metadataLock) {
            LinuxVirtualMetadataOperation(path).also(activeMetadataOperations::add)
        }

    private fun endMetadataOperation(operation: LinuxVirtualMetadataOperation) {
        synchronized(metadataLock) { activeMetadataOperations.remove(operation) }
    }

    private fun recordRefreshFailure(path: String) {
        val now = nowEpochMillis().coerceAtLeast(0L)
        refreshFailures.compute(path) { _, previous ->
            val failures = (previous?.consecutiveFailures ?: 0).plus(1).coerceAtMost(MAX_BACKOFF_EXPONENT + 1)
            val exponent = (failures - 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
            val multiplier = 1L shl exponent
            val delay = if (refreshRetryBaseMillis > refreshRetryMaxMillis / multiplier) {
                refreshRetryMaxMillis
            } else {
                (refreshRetryBaseMillis * multiplier).coerceAtMost(refreshRetryMaxMillis)
            }
            LinuxVirtualRefreshFailure(
                consecutiveFailures = failures,
                recordedAtEpochMillis = now,
                retryAtEpochMillis = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay,
            )
        }
    }

    private fun LinuxVirtualDirectorySnapshot.isFresh(now: Long, duration: Long): Boolean {
        val age = now - fetchedAtEpochMillis
        return age >= 0L && age <= duration
    }

    private companion object {
        const val DEFAULT_FRESH_MILLIS = 5_000L
        const val DEFAULT_REFRESH_RETRY_BASE_MILLIS = 1_000L
        const val DEFAULT_REFRESH_RETRY_MAX_MILLIS = 60_000L
        const val DEFAULT_MAX_RETAINED_METADATA_ENTRIES = 100_000
        const val DEFAULT_MAX_RETAINED_DIRECTORIES = 512
        const val MAX_FAILED_PERSISTED_INVALIDATIONS = 1_024
        const val MAX_REVALIDATED_PERSISTED_LISTINGS = 1_024
        const val MAX_BACKOFF_EXPONENT = 30
        val ROOT_NODE = LinuxVirtualFileNode("", "Nextcloud", true, 0L, "root")
    }
}

private data class LinuxVirtualRefreshFailure(
    val consecutiveFailures: Int,
    val recordedAtEpochMillis: Long,
    val retryAtEpochMillis: Long,
)

private class LinuxVirtualMetadataOperation(val path: String) {
    var invalidated: Boolean = false
}

private fun String.invalidatesListing(listingPath: String): Boolean {
    return isEmpty() ||
        listingPath.isEmpty() ||
        listingPath == this ||
        listingPath.startsWith("$this/") ||
        startsWith("$listingPath/")
}

private fun defaultLinuxMetadataRefreshExecutor(): ExecutorService =
    Executors.newSingleThreadExecutor { task ->
        Thread(task, "nextcloud-linux-metadata-refresh").apply { isDaemon = true }
    }

/** Generation-pinned WebDAV backend shared by the Linux FUSE adapter and its unit tests. */
internal class DesktopNextcloudVirtualFileBackend(
    private val session: NextcloudSession,
    private val userId: String,
    private val services: NextcloudPlatformServices,
    private val rangeCache: DesktopVirtualRangeCache,
    private val writebacks: DesktopLinuxVirtualFileWritebackStore,
    private val tree: DesktopFileSyncRemoteTree = DesktopFileSyncRemoteTree(session, userId, ""),
    private val requireDurableCacheWrites: Boolean = false,
    private val afterCommitted: (String) -> Unit = {},
) : LinuxVirtualFileBackend {
    private val accountId = desktopFileCacheAccountId(session)

    override fun resolve(path: String): LinuxVirtualFileNode? {
        val normalized = path.linuxVirtualPath()
        if (normalized.isEmpty()) return ROOT_NODE
        return tree.resolve(normalized)?.toLinuxVirtualFileNode()
    }

    override fun list(path: String): List<LinuxVirtualFileNode> =
        tree.list(path.linuxVirtualPath()).map { document -> document.toLinuxVirtualFileNode() }

    override fun open(node: LinuxVirtualFileNode): LinuxVirtualFileReadHandle {
        require(!node.directory)
        require(node.size > 0L)
        return object : LinuxVirtualFileReadHandle {
            private var currentPath = node.path
            private val stagedRevision = if (requireDurableCacheWrites) {
                rangeCache.beginRevisionStaging(accountId, currentPath, node.remoteRevision, node.size)
            } else {
                null
            }
            private var source = try {
                openRangeSource(currentPath)
            } catch (failure: Throwable) {
                runCatching { stagedRevision?.close() }
                throw failure
            }
            private var closed = false

            init {
                try {
                    rangeCache.acquire(accountId, currentPath)
                } catch (failure: Throwable) {
                    runCatching(source::close)
                    runCatching { stagedRevision?.close() }
                    throw failure
                }
            }

            override val size: Long = node.size

            @Synchronized
            override fun read(offset: Long, length: Int): ByteArray {
                check(!closed)
                require(offset >= 0L && length > 0 && offset + length <= size)
                val firstBlock = offset / RANGE_BLOCK_BYTES
                val lastBlock = (offset + length - 1L) / RANGE_BLOCK_BYTES
                val destination = ByteArray(length)
                for (block in firstBlock..lastBlock) {
                    val blockOffset = block * RANGE_BLOCK_BYTES
                    val blockLength = minOf(RANGE_BLOCK_BYTES, size - blockOffset).toInt()
                    val bytes = runCatching {
                        rangeCache.readBlock(
                            accountId = accountId,
                            path = currentPath,
                            remoteRevision = node.remoteRevision,
                            fileSize = size,
                            offset = blockOffset,
                            length = blockLength,
                        )
                    }.getOrNull() ?: runBlocking(Dispatchers.IO) { source.read(blockOffset, blockLength) }.also { fetched ->
                        if (!requireDurableCacheWrites) runCatching {
                            rangeCache.storeBlock(
                                accountId = accountId,
                                path = currentPath,
                                remoteRevision = node.remoteRevision,
                                fileSize = size,
                                offset = blockOffset,
                                bytes = fetched,
                            )
                        }
                    }
                    stagedRevision?.store(blockOffset, bytes)
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

            @Synchronized
            override fun readdress(path: String) {
                check(!closed)
                check(stagedRevision == null) { "A retained-file download cannot change paths while it is running." }
                val normalized = path.linuxVirtualPath()
                if (normalized == currentPath) return
                val replacement = openRangeSource(normalized)
                rangeCache.acquire(accountId, normalized)
                val previousSource = source
                val previousPath = currentPath
                source = replacement
                currentPath = normalized
                runCatching(previousSource::close)
                rangeCache.release(accountId, previousPath)
            }

            @Synchronized
            override fun close() {
                if (closed) return
                closed = true
                var failure: Throwable? = null
                runCatching(source::close).onFailure { failure = it }
                val staging = stagedRevision
                if (failure == null && staging != null) {
                    runCatching {
                        check(staging.commitIfComplete()) {
                            "The retained file download did not persist a complete remote revision."
                        }
                    }.onFailure { failure = it }
                }
                runCatching { stagedRevision?.close() }
                    .onFailure { closeFailure -> if (failure == null) failure = closeFailure }
                rangeCache.release(accountId, currentPath)
                failure?.let { throw it }
            }

            private fun openRangeSource(path: String) = services.openFileRangeSession(
                session = session,
                userId = userId,
                path = path,
                size = node.size,
                expectedEtag = node.remoteRevision,
            )
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
        onCommitted = { committedPath ->
            rangeCache.invalidate(accountId, committedPath)
            runCatching { afterCommitted(committedPath) }
        },
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

    override fun moveReplacing(
        node: LinuxVirtualFileNode,
        destination: LinuxVirtualFileNode,
        destinationPath: String,
    ) {
        val normalized = destinationPath.linuxVirtualPath()
        tree.moveReplacing(node.path, normalized, node.remoteRevision, destination.remoteRevision)
        rangeCache.invalidate(accountId, node.path)
        rangeCache.invalidate(accountId, normalized)
    }

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
    private val readHandlePaths = ConcurrentHashMap<Long, String>()
    private val writeHandles = ConcurrentHashMap<Long, LinuxOpenWriteReference>()
    private val directoryHandles = ConcurrentHashMap<Long, LinuxOpenDirectorySnapshot>()
    private val pendingCreatedFiles = ConcurrentHashMap<String, LinuxSharedWriteHandle>()
    private val namespaceLock = Any()

    override fun getattr(path: String, stat: FileStat): Int = fuseResult {
        val normalized = path.linuxVirtualPath()
        val pending = pendingCreatedFiles[normalized]?.delegate
        val node = visibleNode(normalized)
            ?: pending?.let { LinuxVirtualFileNode(normalized, normalized.substringAfterLast('/'), false, it.size, "pending") }
            ?: return -ErrorCodes.ENOENT()
        fillStat(node, stat)
        0
    }

    override fun opendir(path: String, fileInfo: FuseFileInfo): Int = fuseResult {
        val snapshot = openDirectorySnapshot(path)
        val id = nextHandle.getAndIncrement()
        directoryHandles[id] = snapshot
        fileInfo.fh.set(id)
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
        val handleId = fileInfo.fh.get()
        val existingHandle = directoryHandles[handleId]?.takeIf { it.path == normalized }
        if (handleId != 0L && existingHandle == null) return -ErrorCodes.EBADF()
        val snapshot = existingHandle ?: openDirectorySnapshot(path).also { opened ->
            val id = nextHandle.getAndIncrement()
            directoryHandles[id] = opened
            fileInfo.fh.set(id)
        }
        val entries = snapshot.entries
        if (offset < 0L || offset > entries.size.toLong()) return -ErrorCodes.EINVAL()
        for (index in offset.toInt() until entries.size) {
            val entry = entries[index]
            val stat = entry.node?.let { node -> FileStat(jnr.ffi.Runtime.getSystemRuntime()).also { fillStat(node, it) } }
            if (filler.apply(buffer, entry.name, stat, index.toLong() + 1L) != 0) break
        }
        0
    }

    override fun releasedir(path: String, fileInfo: FuseFileInfo): Int = fuseResult {
        val id = fileInfo.fh.get()
        val handle = directoryHandles[id] ?: return -ErrorCodes.EBADF()
        if (handle.path != path.linuxVirtualPath()) return -ErrorCodes.EBADF()
        if (!directoryHandles.remove(id, handle)) return -ErrorCodes.EBADF()
        fileInfo.fh.set(0L)
        0
    }

    override fun open(path: String, fileInfo: FuseFileInfo): Int = fuseResult {
        val normalized = path.linuxVirtualPath()
        val flags = fileInfo.flags.intValue()
        val writeAccess = flags and OPEN_ACCESS_MASK != OPEN_READ_ONLY
        pendingCreatedFiles[normalized]?.let { pending ->
            if (writeAccess && flags and OPEN_TRUNCATE != 0) pending.delegate.truncate(0L)
            fileInfo.fh.set(registerWriteHandle(pending, writable = writeAccess))
            return 0
        }
        synchronized(namespaceLock) {
            val node = visibleNode(normalized) ?: return -ErrorCodes.ENOENT()
            if (node.directory) return -ErrorCodes.EISDIR()
            if (writeAccess) {
                val shared = LinuxSharedWriteHandle(
                    backend.openWrite(normalized, node, truncate = flags and OPEN_TRUNCATE != 0),
                    normalized,
                )
                fileInfo.fh.set(registerWriteHandle(shared, writable = true))
                return 0
            }
            if (node.size == 0L) {
                fileInfo.fh.set(EMPTY_FILE_HANDLE)
                return 0
            }
            val id = nextHandle.getAndIncrement()
            readHandles[id] = backend.open(node)
            readHandlePaths[id] = normalized
            fileInfo.fh.set(id)
        }
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
        val writeHandle = writeHandles[id]?.shared?.delegate
        if (handle == null && writeHandle == null) return -ErrorCodes.EBADF()
        val handleSize = handle?.size ?: requireNotNull(writeHandle).size
        if (offset >= handleSize) return 0
        val length = minOf(requestedSize, handleSize - offset).toInt()
        val bytes = handle?.read(offset, length) ?: requireNotNull(writeHandle).read(offset, length)
        check(bytes.size == length) { "The Linux virtual file range was incomplete." }
        buffer.put(0L, bytes, 0, bytes.size)
        bytes.size
    }

    override fun release(path: String, fileInfo: FuseFileInfo): Int = fuseResult {
        val id = fileInfo.fh.get()
        if (id != EMPTY_FILE_HANDLE) {
            synchronized(namespaceLock) {
                readHandlePaths.remove(id)
                readHandles.remove(id)?.close()
            }
            releaseWriteHandle(id)
        }
        0
    }

    override fun access(path: String, mask: Int): Int = fuseResult {
        val normalized = path.linuxVirtualPath()
        if (pendingCreatedFiles.containsKey(normalized) || visibleNode(normalized) != null) 0 else -ErrorCodes.ENOENT()
    }

    override fun create(path: String, mode: Long, fi: FuseFileInfo?): Int = fuseResult {
        val fileInfo = fi ?: return -ErrorCodes.EINVAL()
        val normalized = path.linuxVirtualPath()
        val parent = visibleNode(normalized.substringBeforeLast('/', ""))
            ?: return -ErrorCodes.ENOENT()
        if (!parent.directory) return -ErrorCodes.ENOTDIR()
        synchronized(pendingCreatedFiles) {
            if (visibleNode(normalized) != null || pendingCreatedFiles.containsKey(normalized)) {
                return -ErrorCodes.EEXIST()
            }
            val shared = LinuxSharedWriteHandle(
                backend.openWrite(normalized, existing = null, truncate = true),
                normalized,
            )
            pendingCreatedFiles[normalized] = shared
            fileInfo.fh.set(registerWriteHandle(shared, writable = true))
        }
        0
    }

    override fun mkdir(path: String, mode: Long): Int = fuseResult {
        val normalized = path.linuxVirtualPath()
        val parent = visibleNode(normalized.substringBeforeLast('/', ""))
            ?: return -ErrorCodes.ENOENT()
        if (!parent.directory) return -ErrorCodes.ENOTDIR()
        if (visibleNode(normalized) != null) return -ErrorCodes.EEXIST()
        backend.createDirectory(normalized)
        0
    }

    override fun unlink(path: String): Int = deletePath(path, expectDirectory = false)

    override fun rmdir(path: String): Int = deletePath(path, expectDirectory = true)

    override fun rename(oldPath: String, newPath: String): Int = fuseResult {
        synchronized(namespaceLock) {
            val sourcePath = oldPath.linuxVirtualPath()
            val destination = newPath.linuxVirtualPath()
            if (sourcePath == destination) return 0
            if (pendingCreatedFiles.containsKey(sourcePath)) return -ErrorCodes.EBUSY()
            if (hasOpenWriteHandleWithin(sourcePath) || hasOpenWriteHandleWithin(destination)) {
                return -ErrorCodes.EBUSY()
            }
            val source = visibleNode(sourcePath) ?: return -ErrorCodes.ENOENT()
            val parent = visibleNode(destination.substringBeforeLast('/', ""))
                ?: return -ErrorCodes.ENOENT()
            if (!parent.directory) return -ErrorCodes.ENOTDIR()
            if (pendingCreatedFiles.containsKey(destination)) return -ErrorCodes.EBUSY()
            val existingDestination = visibleNode(destination)
            if (existingDestination != null) {
                if (source.directory && !existingDestination.directory) return -ErrorCodes.ENOTDIR()
                if (!source.directory && existingDestination.directory) return -ErrorCodes.EISDIR()
                if (existingDestination.directory && backend.list(destination).isNotEmpty()) {
                    return -ErrorCodes.ENOTEMPTY()
                }
                if (hasOpenReadHandleWithin(destination)) return -ErrorCodes.EBUSY()
                backend.moveReplacing(source, existingDestination, destination)
            } else {
                backend.move(source, destination)
            }
            readdressReadHandles(sourcePath, destination)
            readdressDirectoryHandles(sourcePath, destination)
            0
        }
    }

    override fun truncate(path: String, size: Long): Int = fuseResult {
        val normalized = path.linuxVirtualPath()
        pendingCreatedFiles[normalized]?.let { pending ->
            pending.delegate.truncate(size)
            pending.delegate.flush()
            return 0
        }
        val existing = visibleNode(normalized) ?: return -ErrorCodes.ENOENT()
        if (existing.directory) return -ErrorCodes.EISDIR()
        backend.openWrite(normalized, existing, truncate = false).use { handle ->
            handle.truncate(size)
            handle.flush()
        }
        0
    }

    override fun write(path: String, buf: Pointer, size: Long, offset: Long, fi: FuseFileInfo): Int = fuseResult {
        if (offset < 0L || size < 0L || size > Int.MAX_VALUE) return -ErrorCodes.EINVAL()
        val reference = writeHandles[fi.fh.get()] ?: return -ErrorCodes.EBADF()
        if (!reference.writable) return -ErrorCodes.EBADF()
        val bytes = ByteArray(size.toInt())
        buf.get(0L, bytes, 0, bytes.size)
        reference.shared.delegate.write(offset, bytes)
    }

    override fun flush(path: String, fi: FuseFileInfo): Int = fuseResult {
        writeHandles[fi.fh.get()]?.shared?.delegate?.flush()
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
        mount(
            mountPoint,
            blocking,
            debug,
            arrayOf(
                "-o", "fsname=nextcloud-native",
                "-o", "default_permissions",
                "-o", "attr_timeout=5",
                "-o", "entry_timeout=5",
                "-o", "negative_timeout=1",
                "-o", "big_writes",
            ),
        )
    }

    fun unmount() {
        umount()
        readHandles.values.forEach { runCatching(it::close) }
        writeHandles.values.map(LinuxOpenWriteReference::shared).distinct().forEach { shared ->
            runCatching(shared.delegate::close)
        }
        readHandles.clear()
        readHandlePaths.clear()
        writeHandles.clear()
        directoryHandles.clear()
        pendingCreatedFiles.clear()
        backend.close()
    }

    private fun openDirectorySnapshot(path: String): LinuxOpenDirectorySnapshot {
        val normalized = path.linuxVirtualPath()
        val directory = visibleNode(normalized) ?: throw LinuxVirtualFileSystemException(ErrorCodes.ENOENT())
        if (!directory.directory) throw LinuxVirtualFileSystemException(ErrorCodes.ENOTDIR())
        val byName = linkedMapOf<String, LinuxVirtualFileNode>()
        backend.list(normalized).forEach { node -> byName[node.name] = node }
        pendingCreatedFiles.entries
            .asSequence()
            .filter { (pending, _) -> pending.substringBeforeLast('/', "") == normalized }
            .forEach { (pending, shared) ->
                val name = pending.substringAfterLast('/')
                byName.putIfAbsent(
                    name,
                    LinuxVirtualFileNode(pending, name, false, shared.delegate.size, "pending"),
                )
            }
        val entries = buildList {
            add(LinuxOpenDirectoryEntry(".", directory))
            add(LinuxOpenDirectoryEntry("..", null))
            byName.values.sortedBy(LinuxVirtualFileNode::name).forEach { node ->
                add(LinuxOpenDirectoryEntry(node.name, node))
            }
        }
        return LinuxOpenDirectorySnapshot(normalized, entries)
    }

    private fun fillStat(node: LinuxVirtualFileNode, stat: FileStat) {
        stat.st_mode.set(
            if (node.directory) FileStat.S_IFDIR or DIRECTORY_PERMISSIONS
            else FileStat.S_IFREG or FILE_PERMISSIONS,
        )
        stat.st_nlink.set(if (node.directory) 2 else 1)
        stat.st_size.set(node.size)
        stat.st_uid.set(context.uid.get())
        stat.st_gid.set(context.gid.get())
    }

    private fun registerWriteHandle(shared: LinuxSharedWriteHandle, writable: Boolean): Long {
        synchronized(shared) {
            check(!shared.closed)
            shared.referenceCount += 1
        }
        val id = nextHandle.getAndIncrement()
        writeHandles[id] = LinuxOpenWriteReference(shared, writable)
        return id
    }

    private fun releaseWriteHandle(id: Long) {
        val reference = writeHandles.remove(id) ?: return
        val shared = reference.shared
        val close = synchronized(shared) {
            check(shared.referenceCount > 0)
            shared.referenceCount -= 1
            if (shared.referenceCount == 0 && !shared.closed) {
                shared.closed = true
                true
            } else {
                false
            }
        }
        if (close) {
            try {
                shared.delegate.close()
            } finally {
                pendingCreatedFiles.entries.removeIf { it.value === shared }
            }
        }
    }

    private fun readdressReadHandles(sourcePath: String, destinationPath: String) {
        readHandlePaths.entries.toList().forEach { (id, openPath) ->
            val movedPath = when {
                openPath == sourcePath -> destinationPath
                openPath.startsWith("$sourcePath/") -> destinationPath + openPath.removePrefix(sourcePath)
                else -> return@forEach
            }
            readHandles[id]?.readdress(movedPath)
            readHandlePaths.replace(id, openPath, movedPath)
        }
    }

    private fun readdressDirectoryHandles(sourcePath: String, destinationPath: String) {
        directoryHandles.entries.toList().forEach { (id, snapshot) ->
            val movedPath = snapshot.path.readdressWithin(sourcePath, destinationPath) ?: return@forEach
            directoryHandles.replace(id, snapshot, snapshot.copy(path = movedPath))
        }
    }

    private fun String.readdressWithin(sourcePath: String, destinationPath: String): String? = when {
        this == sourcePath -> destinationPath
        startsWith("$sourcePath/") -> destinationPath + removePrefix(sourcePath)
        else -> null
    }

    private fun hasOpenWriteHandleWithin(path: String): Boolean =
        writeHandles.values.any { reference ->
            reference.shared.path == path || reference.shared.path.startsWith("$path/")
        }

    private fun hasOpenReadHandleWithin(path: String): Boolean =
        readHandlePaths.values.any { openPath ->
            openPath == path || openPath.startsWith("$path/")
        }

    private fun visibleNode(path: String): LinuxVirtualFileNode? = backend.resolve(path)

    private fun deletePath(path: String, expectDirectory: Boolean): Int = fuseResult {
        synchronized(namespaceLock) {
            val normalized = path.linuxVirtualPath()
            if (pendingCreatedFiles.containsKey(normalized)) return -ErrorCodes.EBUSY()
            val node = visibleNode(normalized) ?: return -ErrorCodes.ENOENT()
            if (node.directory != expectDirectory) {
                return if (expectDirectory) -ErrorCodes.ENOTDIR() else -ErrorCodes.EISDIR()
            }
            if (expectDirectory) {
                val hasRemoteChildren = backend.list(normalized).isNotEmpty()
                val hasPendingChildren = pendingCreatedFiles.keys.any { pending ->
                    pending.substringBeforeLast('/', "") == normalized
                }
                if (hasRemoteChildren || hasPendingChildren) return -ErrorCodes.ENOTEMPTY()
            }
            if (!expectDirectory &&
                (readHandlePaths.containsValue(normalized) || hasOpenWriteHandleWithin(normalized))
            ) {
                return -ErrorCodes.EBUSY()
            }
            backend.delete(node)
            0
        }
    }

    private inline fun fuseResult(operation: () -> Int): Int = try {
        operation()
    } catch (failure: LinuxVirtualFileSystemException) {
        -failure.errorCode
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

private data class LinuxOpenDirectorySnapshot(
    val path: String,
    val entries: List<LinuxOpenDirectoryEntry>,
)

private data class LinuxOpenDirectoryEntry(
    val name: String,
    val node: LinuxVirtualFileNode?,
)

private class LinuxVirtualFileSystemException(val errorCode: Int) : RuntimeException()

private class LinuxSharedWriteHandle(
    val delegate: LinuxVirtualFileWriteHandle,
    val path: String,
) {
    var referenceCount: Int = 0
    var closed: Boolean = false
}

private data class LinuxOpenWriteReference(
    val shared: LinuxSharedWriteHandle,
    val writable: Boolean,
)

private fun String.linuxVirtualPath(): String {
    val normalized = trim('/')
    if (normalized.isEmpty()) return ""
    require(normalized.split('/').none { it.isEmpty() || it == "." || it == ".." })
    require('\u0000' !in normalized)
    return normalized
}
