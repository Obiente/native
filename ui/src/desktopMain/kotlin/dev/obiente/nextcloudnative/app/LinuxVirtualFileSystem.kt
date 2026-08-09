package dev.obiente.nextcloudnative.app

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
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
) {
    val inode: Long = stableLinuxVirtualInode(path)
}

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
    fun isDirectoryEmpty(node: LinuxVirtualFileNode): Boolean = list(node.path).isEmpty()
    fun open(node: LinuxVirtualFileNode): LinuxVirtualFileReadHandle
    fun openWrite(path: String, existing: LinuxVirtualFileNode?, truncate: Boolean): LinuxVirtualFileWriteHandle
    fun createDirectory(path: String)
    fun delete(node: LinuxVirtualFileNode)
    fun move(node: LinuxVirtualFileNode, destinationPath: String)
    fun move(node: LinuxVirtualFileNode, destinationPath: String, afterRemoteCommit: () -> Unit) {
        move(node, destinationPath)
        afterRemoteCommit()
    }
    fun moveReplacing(
        node: LinuxVirtualFileNode,
        destination: LinuxVirtualFileNode,
        destinationPath: String,
    )
    fun moveReplacing(
        node: LinuxVirtualFileNode,
        destination: LinuxVirtualFileNode,
        destinationPath: String,
        afterRemoteCommit: () -> Unit,
    ) {
        moveReplacing(node, destination, destinationPath)
        afterRemoteCommit()
    }

    override fun close() = Unit
}

internal data class LinuxVirtualDirectorySnapshot(
    val nodes: List<LinuxVirtualFileNode>,
    val fetchedAtEpochMillis: Long,
    val freshAtEpochMillis: Long = fetchedAtEpochMillis,
    val generation: Long = 0L,
    val complete: Boolean = true,
) {
    val nodesByPath: Map<String, LinuxVirtualFileNode> = nodes.associateBy(LinuxVirtualFileNode::path)

    init {
        require(nodesByPath.size == nodes.size) { "A Linux directory snapshot contains duplicate paths." }
    }
}

/**
 * Large WebDAV directories are expensive to enumerate and rarely benefit from five-second polling.
 * Known local and in-app mutations still invalidate their snapshots immediately.
 */
internal fun linuxVirtualMetadataFreshnessMillis(
    entryCount: Int,
    minimumFreshnessMillis: Long,
): Long {
    require(entryCount >= 0 && minimumFreshnessMillis >= 0L)
    val adaptiveFreshness = when {
        entryCount <= 512 -> 5_000L
        entryCount <= 4_096 -> 30_000L
        entryCount <= 16_384 -> 5 * 60_000L
        else -> 15 * 60_000L
    }
    return maxOf(minimumFreshnessMillis, adaptiveFreshness)
}

internal interface LinuxVirtualMetadataStore {
    fun load(path: String): LinuxVirtualDirectorySnapshot?
    fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot): Boolean
    fun invalidate(path: String)
    fun retainedPaths(): Set<String>? = null
    fun failedInvalidations(): Set<String> = emptySet()
    fun replaceFailedInvalidations(paths: Set<String>) = Unit
}

internal class DesktopLinuxVirtualMetadataStore(
    private val cache: DesktopFileReadCache,
    private val accountId: String,
) : LinuxVirtualMetadataStore {
    override fun load(path: String): LinuxVirtualDirectorySnapshot? {
        val listing = cache.cachedVirtualListingSnapshot(accountId, path) ?: return null
        return LinuxVirtualDirectorySnapshot(
            nodes = listing.nodes,
            fetchedAtEpochMillis = listing.fetchedAtEpochMillis,
            freshAtEpochMillis = listing.freshAtEpochMillis,
        )
    }

    override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot): Boolean =
        cache.storeVirtualListingUnlessNewer(
            accountId = accountId,
            path = path,
            nodes = snapshot.nodes,
            fetchedAtEpochMillis = snapshot.fetchedAtEpochMillis,
            freshAtEpochMillis = snapshot.freshAtEpochMillis,
        )

    override fun invalidate(path: String) = cache.invalidate(accountId, path)

    override fun retainedPaths(): Set<String> = cache.cachedVirtualListingPaths(accountId)

    override fun failedInvalidations(): Set<String> = cache.failedVirtualListingInvalidations(accountId)

    override fun replaceFailedInvalidations(paths: Set<String>) =
        cache.replaceFailedVirtualListingInvalidations(accountId, paths)
}

internal class RetainedLinuxVirtualMetadataStore(
    private val rangeCache: DesktopVirtualRangeCache,
    private val accountId: String,
    private val fallback: LinuxVirtualMetadataStore,
    private val afterRetainedListingChanged: (Set<String>) -> Unit = {},
) : LinuxVirtualMetadataStore {
    override fun load(path: String): LinuxVirtualDirectorySnapshot? {
        val retained = rangeCache.loadRetainedListing(accountId, path) ?: return fallback.load(path)
        if (retained.complete) return retained
        val completeFallback = fallback.load(path)?.takeIf(LinuxVirtualDirectorySnapshot::complete)
            ?: return retained.copy(fetchedAtEpochMillis = 0L)
        return mergeRetainedNavigationListing(completeFallback, retained)
    }

    override fun store(path: String, snapshot: LinuxVirtualDirectorySnapshot): Boolean {
        val previousNodes = runCatching { fallback.load(path)?.nodesByPath }.getOrNull().orEmpty()
        val persisted = fallback.store(path, snapshot)
        if (persisted && previousNodes != snapshot.nodesByPath) {
            val changedPaths = (previousNodes.keys + snapshot.nodesByPath.keys).filterTo(linkedSetOf()) { nodePath ->
                previousNodes[nodePath] != snapshot.nodesByPath[nodePath]
            }
            if (changedPaths.isNotEmpty()) runCatching { afterRetainedListingChanged(changedPaths) }
        }
        return persisted
    }

    override fun retainedPaths(): Set<String>? = fallback.retainedPaths()

    override fun failedInvalidations(): Set<String> = fallback.failedInvalidations()

    override fun replaceFailedInvalidations(paths: Set<String>) = fallback.replaceFailedInvalidations(paths)

    override fun invalidate(path: String) {
        rangeCache.invalidateRetainedListings(accountId, path)
        fallback.invalidate(path)
    }
}

internal fun mergeRetainedNavigationListing(
    complete: LinuxVirtualDirectorySnapshot,
    navigation: LinuxVirtualDirectorySnapshot,
): LinuxVirtualDirectorySnapshot {
    require(complete.complete && !navigation.complete)
    val nodes = LinkedHashMap(complete.nodesByPath)
    val navigationIsNewer = navigation.fetchedAtEpochMillis >= complete.fetchedAtEpochMillis
    navigation.nodes.forEach { node ->
        if (navigationIsNewer) nodes[node.path] = node else nodes.putIfAbsent(node.path, node)
    }
    return LinuxVirtualDirectorySnapshot(
        nodes = nodes.values.toList(),
        fetchedAtEpochMillis = maxOf(complete.fetchedAtEpochMillis, navigation.fetchedAtEpochMillis),
        freshAtEpochMillis = maxOf(complete.freshAtEpochMillis, navigation.freshAtEpochMillis),
        generation = maxOf(complete.generation, navigation.generation),
        complete = true,
    )
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
    private val afterMutationInvalidated: (String) -> Unit = {},
) : LinuxVirtualFileBackend {
    private val snapshots = LinkedHashMap<String, LinuxVirtualDirectorySnapshot>(16, 0.75f, true)
    private val fastSnapshots = ConcurrentHashMap<String, LinuxVirtualDirectorySnapshot>()
    private val fastNodes = ConcurrentHashMap<String, LinuxFastVirtualNode>()
    private val refreshes = ConcurrentHashMap<String, CompletableFuture<LinuxVirtualDirectorySnapshot?>>()
    private val blockingRefreshPermits = Semaphore(MAX_CONCURRENT_BLOCKING_REFRESHES, true)
    private val refreshFailures = LinkedHashMap<String, LinuxVirtualRefreshFailure>(16, 0.75f, true)
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
        failedPersistedInvalidations += store.failedInvalidations()
    }

    override fun resolve(path: String): LinuxVirtualFileNode? {
        val normalized = path.linuxVirtualPath()
        if (normalized.isEmpty()) return ROOT_NODE
        fastNodes[normalized]?.let { cached ->
            maybeRefresh(cached.parentPath, cached.snapshot)
            return cached.node
        }
        val parent = normalized.substringBeforeLast('/', "")
        val cached = fastSnapshots[parent] ?: snapshot(parent)
        maybeRefresh(parent, cached)
        return cached.nodesByPath[normalized]
    }

    override fun list(path: String): List<LinuxVirtualFileNode> = snapshot(path.linuxVirtualPath()).nodes

    override fun isDirectoryEmpty(node: LinuxVirtualFileNode): Boolean = delegate.isDirectoryEmpty(node)

    internal fun hasRecordedRefreshFailure(path: String): Boolean =
        synchronized(metadataLock) { refreshFailures.containsKey(path.linuxVirtualPath()) }

    internal fun revalidatedPersistedListingCount(): Int =
        synchronized(metadataLock) { revalidatedPersistedListings.size }

    internal fun failedPersistedInvalidationCount(): Int =
        synchronized(metadataLock) { failedPersistedInvalidations.size }

    /**
     * Invalidates metadata after a mutation committed outside this backend, such as an in-app
     * Files action or recovery of a durable writeback. This must use the same generation and
     * persisted-store guards as FUSE mutations so an older refresh cannot republish stale paths.
     */
    internal fun invalidateAfterExternalMutation(path: String) {
        invalidate(path)
    }

    private fun snapshot(normalized: String): LinuxVirtualDirectorySnapshot {
        synchronized(metadataLock) { snapshots[normalized] }?.let { cached ->
            maybeRefresh(normalized, cached)
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
            maybeRefresh(normalized, cached)
            return cached
        }
        return refreshBlocking(normalized)
    }

    private fun maybeRefresh(path: String, snapshot: LinuxVirtualDirectorySnapshot) {
        if (refreshes.containsKey(path)) return
        if (!snapshot.isFresh(nowEpochMillis(), snapshot.adaptiveFreshnessMillis())) {
            refreshAsync(path)
        }
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
                val shouldInvalidate = dirty
                try {
                    handle.flush()
                    dirty = false
                } finally {
                    if (shouldInvalidate) invalidateMutation(normalized)
                }
            }

            @Synchronized
            override fun close() {
                val shouldInvalidate = dirty
                try {
                    handle.close()
                    dirty = false
                } finally {
                    if (shouldInvalidate) invalidateMutation(normalized)
                }
            }
        }
    }

    override fun createDirectory(path: String) {
        val normalized = path.linuxVirtualPath()
        try {
            delegate.createDirectory(normalized)
        } finally {
            invalidateMutation(normalized)
        }
    }

    override fun delete(node: LinuxVirtualFileNode) {
        try {
            delegate.delete(node)
        } finally {
            invalidateMutation(node.path)
        }
    }

    override fun move(node: LinuxVirtualFileNode, destinationPath: String) {
        move(node, destinationPath) {}
    }

    override fun move(
        node: LinuxVirtualFileNode,
        destinationPath: String,
        afterRemoteCommit: () -> Unit,
    ) {
        val destination = destinationPath.linuxVirtualPath()
        try {
            delegate.move(node, destination, afterRemoteCommit)
        } finally {
            invalidateMutation(node.path)
            invalidateMutation(destination)
        }
    }

    override fun moveReplacing(
        node: LinuxVirtualFileNode,
        destination: LinuxVirtualFileNode,
        destinationPath: String,
    ) {
        moveReplacing(node, destination, destinationPath) {}
    }

    override fun moveReplacing(
        node: LinuxVirtualFileNode,
        destination: LinuxVirtualFileNode,
        destinationPath: String,
        afterRemoteCommit: () -> Unit,
    ) {
        val normalized = destinationPath.linuxVirtualPath()
        try {
            delegate.moveReplacing(node, destination, normalized, afterRemoteCommit)
        } finally {
            invalidateMutation(node.path)
            invalidateMutation(normalized)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        refreshExecutor.shutdown()
        // A persistence task that entered store.store() before close must finish before a new
        // backend can replace this one. Tasks that were only waiting for the store lock observe
        // `closed` in their second guarded check and skip publication.
        synchronized(persistedStoreLock) { }
        refreshExecutor.shutdownNow()
        synchronized(metadataLock) {
            snapshots.clear()
            fastSnapshots.clear()
            fastNodes.clear()
            activeMetadataOperations.clear()
            pendingPersistedInvalidations.clear()
            revalidatedPersistedListings.clear()
            refreshFailures.clear()
        }
        delegate.close()
    }

    private fun refreshBlocking(path: String): LinuxVirtualDirectorySnapshot {
        while (true) {
            val candidate = CompletableFuture<LinuxVirtualDirectorySnapshot?>()
            val existing = refreshes.putIfAbsent(path, candidate)
            val future = existing ?: candidate.also {
                var acquired = false
                try {
                    blockingRefreshPermits.acquire()
                    acquired = true
                    refresh(path, it)
                } catch (failure: Throwable) {
                    it.completeExceptionally(failure)
                    refreshes.remove(path, it)
                    throw failure
                } finally {
                    if (acquired) blockingRefreshPermits.release()
                }
            }
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
        synchronized(metadataLock) { refreshFailures[path] }?.let { failure ->
            if (now < failure.recordedAtEpochMillis) {
                synchronized(metadataLock) {
                    if (refreshFailures[path] == failure) refreshFailures.remove(path)
                }
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
            val requestStartedAtEpochMillis = nowEpochMillis().coerceAtLeast(0L)
            val nodes = delegate.list(path)
            val snapshot = LinuxVirtualDirectorySnapshot(
                nodes = nodes,
                fetchedAtEpochMillis = requestStartedAtEpochMillis,
                freshAtEpochMillis = nowEpochMillis().coerceAtLeast(requestStartedAtEpochMillis),
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
                synchronized(metadataLock) { refreshFailures.remove(path) }
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
                        val persisted = stillCurrent &&
                            runCatching { store.store(path, snapshot) }.getOrDefault(false)
                        if (persisted) {
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
        var quarantinePersisted = false
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
                snapshots.remove(cachedPath)?.let(::removeFastNodes)
                fastSnapshots.remove(cachedPath)
                refreshFailures.remove(cachedPath)
            }
            activeMetadataOperations
                .filter { operation -> normalized.invalidatesListing(operation.path) }
                .forEach { operation -> operation.invalidated = true }
            revalidatedPersistedListings.removeIf { listing -> normalized.invalidatesListing(listing) }
            pendingPersistedInvalidations[normalized] =
                pendingPersistedInvalidations.getOrDefault(normalized, 0) + 1
            // Persist the fail-closed quarantine before touching the main cache index. If the
            // process stops during invalidation, the next process must not restore stale data.
            rememberFailedPersistedInvalidation(normalized)
            quarantinePersisted = persistFailedInvalidationsBestEffort()
        }
        var persistedInvalidated = false
        if (!quarantinePersisted) {
            // Preferences can be unavailable even while the cache volume is writable. In that
            // case remove the affected index before exposing the mutation as complete so a
            // restart cannot resurrect the stale listing.
            synchronized(persistedStoreLock) {
                persistedInvalidated = runCatching { store.invalidate(normalized) }.isSuccess
            }
        }
        afterPersistedInvalidationMarked()
        try {
            if (!persistedInvalidated) {
                synchronized(persistedStoreLock) {
                    persistedInvalidated = runCatching { store.invalidate(normalized) }.isSuccess
                }
            }
        } finally {
            synchronized(metadataLock) {
                if (persistedInvalidated) {
                    failedPersistedInvalidations.removeIf { failed ->
                        normalized.isEmpty() || failed == normalized || failed.startsWith("$normalized/")
                    }
                    pruneUnpairedRevalidatedListings()
                }
                persistFailedInvalidationsBestEffort()
                val remaining = pendingPersistedInvalidations.getOrDefault(normalized, 1) - 1
                if (remaining <= 0) pendingPersistedInvalidations.remove(normalized)
                else pendingPersistedInvalidations[normalized] = remaining
            }
        }
    }

    private fun invalidateMutation(path: String) {
        invalidate(path)
        runCatching { afterMutationInvalidated(path.linuxVirtualPath()) }
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
        if (failedPersistedInvalidations.none { failed -> failed.invalidatesListing(path) }) {
            revalidatedPersistedListings.remove(path)
            return
        }
        revalidatedPersistedListings.remove(path)
        revalidatedPersistedListings += path
        reconcileRevalidatedListingsWithStore()
    }

    private fun pruneUnpairedRevalidatedListings() {
        check(Thread.holdsLock(metadataLock))
        revalidatedPersistedListings.removeIf { listing ->
            failedPersistedInvalidations.none { failed -> failed.invalidatesListing(listing) }
        }
    }

    private fun reconcileRevalidatedListingsWithStore() {
        check(Thread.holdsLock(metadataLock))
        val retainedPaths = store.retainedPaths() ?: return
        revalidatedPersistedListings.retainAll(retainedPaths)
        failedPersistedInvalidations.removeIf { failed ->
            retainedPaths.none { listing ->
                failed.invalidatesListing(listing) && listing !in revalidatedPersistedListings
            }
        }
        pruneUnpairedRevalidatedListings()
        persistFailedInvalidationsBestEffort()
    }

    private fun persistFailedInvalidationsBestEffort(): Boolean {
        check(Thread.holdsLock(metadataLock))
        return runCatching { store.replaceFailedInvalidations(failedPersistedInvalidations) }.isSuccess
    }

    private fun retainSnapshot(path: String, snapshot: LinuxVirtualDirectorySnapshot) {
        check(Thread.holdsLock(metadataLock))
        snapshots.put(path, snapshot)?.let { previous ->
            fastSnapshots.remove(path, previous)
            removeFastNodes(previous)
        }
        fastSnapshots[path] = snapshot
        snapshot.nodes.forEach { node ->
            fastNodes[node.path] = LinuxFastVirtualNode(node, path, snapshot)
        }
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
            fastSnapshots.remove(evicted.key, evicted.value)
            removeFastNodes(evicted.value)
        }
    }

    private fun removeFastNodes(snapshot: LinuxVirtualDirectorySnapshot) {
        snapshot.nodes.forEach { node ->
            fastNodes.computeIfPresent(node.path) { _, cached ->
                cached.takeUnless { it.snapshot === snapshot }
            }
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
        synchronized(metadataLock) {
            val previous = refreshFailures[path]
            val failures = (previous?.consecutiveFailures ?: 0).plus(1).coerceAtMost(MAX_BACKOFF_EXPONENT + 1)
            val exponent = (failures - 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
            val multiplier = 1L shl exponent
            val delay = if (refreshRetryBaseMillis > refreshRetryMaxMillis / multiplier) {
                refreshRetryMaxMillis
            } else {
                (refreshRetryBaseMillis * multiplier).coerceAtMost(refreshRetryMaxMillis)
            }
            refreshFailures[path] = LinuxVirtualRefreshFailure(
                consecutiveFailures = failures,
                recordedAtEpochMillis = now,
                retryAtEpochMillis = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay,
            )
            while (refreshFailures.size > maximumRetainedDirectories) {
                refreshFailures.remove(refreshFailures.keys.first())
            }
        }
    }

    private fun LinuxVirtualDirectorySnapshot.isFresh(now: Long, duration: Long): Boolean {
        val age = now - freshAtEpochMillis
        return age >= 0L && age <= duration
    }

    private fun LinuxVirtualDirectorySnapshot.adaptiveFreshnessMillis(): Long =
        if (freshForMillis == DEFAULT_FRESH_MILLIS) {
            linuxVirtualMetadataFreshnessMillis(nodes.size, freshForMillis)
        } else {
            freshForMillis
        }

    private companion object {
        const val DEFAULT_FRESH_MILLIS = 5_000L
        const val DEFAULT_REFRESH_RETRY_BASE_MILLIS = 1_000L
        const val DEFAULT_REFRESH_RETRY_MAX_MILLIS = 60_000L
        const val DEFAULT_MAX_RETAINED_METADATA_ENTRIES = 100_000
        const val DEFAULT_MAX_RETAINED_DIRECTORIES = 512
        const val MAX_FAILED_PERSISTED_INVALIDATIONS = 1_024
        const val MAX_CONCURRENT_BLOCKING_REFRESHES = 2
        const val MAX_BACKOFF_EXPONENT = 30
        val ROOT_NODE = LinuxVirtualFileNode("", "Nextcloud", true, 0L, "root")
    }
}

private data class LinuxVirtualRefreshFailure(
    val consecutiveFailures: Int,
    val recordedAtEpochMillis: Long,
    val retryAtEpochMillis: Long,
)

private data class LinuxFastVirtualNode(
    val node: LinuxVirtualFileNode,
    val parentPath: String,
    val snapshot: LinuxVirtualDirectorySnapshot,
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
    onMutationCommitted: (relativePath: String) -> Unit = {},
    onAmbiguousMutationResult: (relativePath: String) -> Unit = {},
    private val tree: DesktopFileSyncRemoteTree = DesktopFileSyncRemoteTree(
        session = session,
        userId = userId,
        remoteRootPath = "",
        onMutationCommitted = onMutationCommitted,
        onAmbiguousMutationResult = onAmbiguousMutationResult,
    ),
    private val requireDurableCacheWrites: Boolean = false,
    private val retentionSnapshot: VirtualFolderRetentionState? = null,
) : LinuxVirtualFileBackend {
    private val accountId = desktopFileCacheAccountId(session)

    override fun resolve(path: String): LinuxVirtualFileNode? {
        val normalized = path.linuxVirtualPath()
        if (normalized.isEmpty()) return ROOT_NODE
        return tree.resolve(normalized)?.toLinuxVirtualFileNode()
    }

    override fun list(path: String): List<LinuxVirtualFileNode> =
        tree.list(path.linuxVirtualPath()).map { document -> document.toLinuxVirtualFileNode() }

    override fun isDirectoryEmpty(node: LinuxVirtualFileNode): Boolean {
        require(node.directory)
        return tree.isDirectoryEmpty(node.path, node.remoteRevision)
    }

    override fun open(node: LinuxVirtualFileNode): LinuxVirtualFileReadHandle {
        require(!node.directory)
        require(node.size > 0L)
        return object : LinuxVirtualFileReadHandle {
            private var currentPath = node.path
            private val stagedRevision = if (requireDurableCacheWrites) {
                rangeCache.beginRevisionStaging(
                    accountId,
                    currentPath,
                    node.remoteRevision,
                    node.size,
                    retentionSnapshot,
                    preservePreviousRevisionUntilPublication = true,
                )
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
                    rangeCache.acquire(accountId, currentPath, node.remoteRevision, node.size)
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
                rangeCache.acquire(accountId, normalized, node.remoteRevision, node.size)
                val previousSource = source
                val previousPath = currentPath
                source = replacement
                currentPath = normalized
                runCatching(previousSource::close)
                rangeCache.release(accountId, previousPath, node.remoteRevision, node.size)
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
                rangeCache.release(accountId, currentPath, node.remoteRevision, node.size)
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
            runCatching { rangeCache.invalidate(accountId, committedPath) }
        },
    )

    override fun createDirectory(path: String) {
        val normalized = path.linuxVirtualPath()
        tree.createDirectory(normalized, expectedRemoteEtag = null)
        runCatching { rangeCache.invalidate(accountId, normalized) }
    }

    override fun delete(node: LinuxVirtualFileNode) {
        tree.delete(node.path, node.remoteRevision)
        runCatching { rangeCache.invalidate(accountId, node.path) }
    }

    override fun move(node: LinuxVirtualFileNode, destinationPath: String) {
        move(node, destinationPath) {}
    }

    override fun move(
        node: LinuxVirtualFileNode,
        destinationPath: String,
        afterRemoteCommit: () -> Unit,
    ) {
        val normalized = destinationPath.linuxVirtualPath()
        tree.move(node.path, normalized, node.remoteRevision)
        afterRemoteCommit()
        runCatching { rangeCache.invalidate(accountId, node.path) }
        runCatching { rangeCache.invalidate(accountId, normalized) }
    }

    override fun moveReplacing(
        node: LinuxVirtualFileNode,
        destination: LinuxVirtualFileNode,
        destinationPath: String,
    ) {
        moveReplacing(node, destination, destinationPath) {}
    }

    override fun moveReplacing(
        node: LinuxVirtualFileNode,
        destination: LinuxVirtualFileNode,
        destinationPath: String,
        afterRemoteCommit: () -> Unit,
    ) {
        val normalized = destinationPath.linuxVirtualPath()
        tree.moveReplacing(node.path, normalized, node.remoteRevision, destination.remoteRevision)
        afterRemoteCommit()
        runCatching { rangeCache.invalidate(accountId, node.path) }
        runCatching { rangeCache.invalidate(accountId, normalized) }
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
    private val maximumOpenDirectoryEntries: Int = DEFAULT_MAX_OPEN_DIRECTORY_ENTRIES,
    private val beforeDirectoryHandleRemoval: () -> Unit = {},
    private val unmountOperation: (LinuxNextcloudVirtualFileSystem) -> Unit = { fileSystem -> fileSystem.umount() },
) : FuseStubFS() {
    private val nextHandle = AtomicLong(1L)
    private val readHandles = ConcurrentHashMap<Long, LinuxVirtualFileReadHandle>()
    private val readHandlePaths = ConcurrentHashMap<Long, String>()
    private val writeHandles = ConcurrentHashMap<Long, LinuxOpenWriteReference>()
    private val directoryHandles = ConcurrentHashMap<Long, LinuxOpenDirectorySnapshot>()
    private val directoryHandleLock = Any()
    private val directorySnapshotCreationPermits = Semaphore(MAX_CONCURRENT_DIRECTORY_SNAPSHOT_CREATIONS, true)
    private val pendingDirectorySnapshots = mutableSetOf<LinuxPendingDirectorySnapshot>()
    private var openDirectoryEntries = 0L
    private val pendingCreatedFiles = ConcurrentHashMap<String, LinuxSharedWriteHandle>()
    private val namespaceLock = Any()
    @Volatile
    private var mountedUid: Long? = null
    @Volatile
    private var mountedGid: Long? = null

    init {
        require(maximumOpenDirectoryEntries > 0)
    }

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
        val id = openAndRegisterDirectorySnapshot(path)
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
        val snapshot = existingHandle ?: openAndRegisterDirectorySnapshot(path).let { id ->
            fileInfo.fh.set(id)
            checkNotNull(directoryHandles[id])
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
        val normalized = path.linuxVirtualPath()
        val removed = synchronized(directoryHandleLock) {
            val handle = directoryHandles[id] ?: return@synchronized null
            beforeDirectoryHandleRemoval()
            if (handle.path != normalized || !directoryHandles.remove(id, handle)) return@synchronized null
            openDirectoryEntries = (openDirectoryEntries - handle.entries.size).coerceAtLeast(0L)
            handle
        } ?: return -ErrorCodes.EBADF()
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
                if (existingDestination.directory && !backend.isDirectoryEmpty(existingDestination)) {
                    return -ErrorCodes.ENOTEMPTY()
                }
                if (hasOpenReadHandleWithin(destination)) return -ErrorCodes.EBUSY()
                backend.moveReplacing(source, existingDestination, destination) {
                    readdressOpenNamespace(sourcePath, destination)
                }
            } else {
                backend.move(source, destination) {
                    readdressOpenNamespace(sourcePath, destination)
                }
            }
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
                "-o", "use_ino",
                "-o", "big_writes",
            ),
        )
    }

    fun unmount() {
        var detached = false
        try {
            unmountOperation(this)
            detached = true
        } finally {
            readHandles.values.forEach { runCatching(it::close) }
            writeHandles.values.map(LinuxOpenWriteReference::shared).distinct().forEach { shared ->
                runCatching(shared.delegate::close)
            }
            readHandles.clear()
            readHandlePaths.clear()
            writeHandles.clear()
            synchronized(directoryHandleLock) {
                directoryHandles.clear()
                openDirectoryEntries = 0L
            }
            pendingCreatedFiles.clear()
            if (detached) backend.close()
        }
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

    private fun openAndRegisterDirectorySnapshot(path: String): Long {
        var acquired = false
        var pending: LinuxPendingDirectorySnapshot? = null
        try {
            directorySnapshotCreationPermits.acquire()
            acquired = true
            val registration = synchronized(namespaceLock) {
                LinuxPendingDirectorySnapshot(path.linuxVirtualPath()).also(pendingDirectorySnapshots::add)
            }
            pending = registration
            while (true) {
                val requestedPath = synchronized(namespaceLock) { registration.path }
                val snapshot = try {
                    openDirectorySnapshot(requestedPath)
                } catch (failure: Throwable) {
                    if (synchronized(namespaceLock) { registration.path != requestedPath }) continue
                    throw failure
                }
                val registered = synchronized(namespaceLock) {
                    if (registration.path == requestedPath) {
                        registerDirectorySnapshot(snapshot.copy(path = requestedPath))
                    } else {
                        null
                    }
                }
                if (registered != null) return registered
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LinuxVirtualFileSystemException(ErrorCodes.EINTR())
        } finally {
            pending?.let { snapshot -> synchronized(namespaceLock) { pendingDirectorySnapshots.remove(snapshot) } }
            if (acquired) directorySnapshotCreationPermits.release()
        }
    }

    private fun registerDirectorySnapshot(snapshot: LinuxOpenDirectorySnapshot): Long =
        synchronized(directoryHandleLock) {
            val entryCount = snapshot.entries.size.toLong()
            if (entryCount > maximumOpenDirectoryEntries.toLong() - openDirectoryEntries) {
                throw LinuxVirtualFileSystemException(ErrorCodes.ENOMEM())
            }
            val id = nextHandle.getAndIncrement()
            directoryHandles[id] = snapshot
            openDirectoryEntries += entryCount
            id
        }

    private fun fillStat(node: LinuxVirtualFileNode, stat: FileStat) {
        stat.st_ino.set(node.inode)
        stat.st_mode.set(
            if (node.directory) FileStat.S_IFDIR or DIRECTORY_PERMISSIONS
            else FileStat.S_IFREG or FILE_PERMISSIONS,
        )
        stat.st_nlink.set(if (node.directory) 2 else 1)
        stat.st_size.set(node.size)
        val uid = mountedUid ?: context.uid.get().also { mountedUid = it }
        val gid = mountedGid ?: context.gid.get().also { mountedGid = it }
        stat.st_uid.set(uid)
        stat.st_gid.set(gid)
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
        synchronized(directoryHandleLock) {
            directoryHandles.entries.toList().forEach { (id, snapshot) ->
                val movedPath = snapshot.path.readdressWithin(sourcePath, destinationPath) ?: return@forEach
                directoryHandles.replace(id, snapshot, snapshot.copy(path = movedPath))
            }
        }
    }

    private fun readdressOpenNamespace(sourcePath: String, destinationPath: String) {
        check(Thread.holdsLock(namespaceLock))
        readdressReadHandles(sourcePath, destinationPath)
        readdressDirectoryHandles(sourcePath, destinationPath)
        pendingDirectorySnapshots.forEach { pending ->
            pending.path.readdressWithin(sourcePath, destinationPath)?.let { movedPath ->
                pending.path = movedPath
            }
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
                val hasRemoteChildren = !backend.isDirectoryEmpty(node)
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
        const val DEFAULT_MAX_OPEN_DIRECTORY_ENTRIES = 100_000
        const val MAX_CONCURRENT_DIRECTORY_SNAPSHOT_CREATIONS = 4
    }
}

/** Stable across refreshes and app restarts so file managers can reconcile large directory models. */
internal fun stableLinuxVirtualInode(path: String): Long {
    var hash = -0x340d631b7bdddcdbL
    path.forEach { character ->
        hash = (hash xor character.code.toLong()) * 0x100000001b3L
    }
    return (hash and Long.MAX_VALUE).coerceAtLeast(2L)
}

private data class LinuxOpenDirectorySnapshot(
    val path: String,
    val entries: List<LinuxOpenDirectoryEntry>,
)

private class LinuxPendingDirectorySnapshot(var path: String)

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
    var start = 0
    while (start < length && this[start] == '/') start += 1
    var end = length
    while (end > start && this[end - 1] == '/') end -= 1
    if (start == end) return ""

    var segmentStart = start
    for (index in start..end) {
        val character = if (index < end) this[index] else '/'
        require(character != '\u0000')
        if (character != '/') continue
        require(index > segmentStart)
        val segmentLength = index - segmentStart
        require(
            segmentLength != 1 || this[segmentStart] != '.',
        )
        require(
            segmentLength != 2 || this[segmentStart] != '.' || this[segmentStart + 1] != '.',
        )
        segmentStart = index + 1
    }
    return if (start == 0 && end == length) this else substring(start, end)
}
