package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val MAX_VIRTUAL_FOLDER_RETAINED_LISTINGS = 20_000

internal fun String.isValidDesktopVirtualCacheRootIdentity(): Boolean =
    length == 36 && runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

internal data class DesktopVirtualRangeCacheSummary(
    val cachedBytes: Long,
    val reclaimableBytes: Long,
    val pinnedBytes: Long,
    val fileCount: Int,
    val pinnedFileCount: Int,
    val availableFreeBytes: Long,
    val primaryCachedBytes: Long = cachedBytes,
    val primaryReclaimableBytes: Long = reclaimableBytes,
    val primaryPinnedBytes: Long = pinnedBytes,
    val overflowCachedBytes: Long = 0L,
    val overflowReclaimableBytes: Long = 0L,
    val overflowPinnedBytes: Long = 0L,
    val overflowAvailableFreeBytes: Long? = null,
    val overflowAvailable: Boolean = false,
    val tierAttention: String? = null,
)

internal data class VirtualRangeRevision(
    val relativePath: String,
    val remoteRevision: String,
    val fileSize: Long,
) {
    init {
        FileOfflineKey("account", relativePath)
        require(remoteRevision.isNotBlank() && remoteRevision.none(Char::isISOControl))
        require(fileSize > 0L)
    }
}

private data class ActiveVirtualRangeRevision(
    val file: FileOfflineKey,
    val remoteRevision: String,
    val fileSize: Long,
)

/** Persistent exact-revision block cache used by the Linux virtual filesystem. */
internal class DesktopVirtualRangeCache(
    private val root: File,
    private val overflowRoot: File? = null,
    private val maximumIndexBytes: Long = MAX_INDEX_BYTES,
    private val maximumBlocks: Int = MAX_BLOCKS,
    private val createParentDirectories: Boolean = true,
    private val initializePrimaryMarker: Boolean = false,
    private val initializeOverflowMarker: Boolean = false,
    expectedPrimaryIdentity: String? = null,
    private val requirePrimaryIdentity: Boolean = expectedPrimaryIdentity != null,
    expectedOverflowIdentity: String? = null,
    private val accessTimePersistenceIntervalMillis: Long = ACCESS_TIME_PERSISTENCE_INTERVAL_MILLIS,
    private val beforeBlockValidation: (File) -> Unit = {},
    private val beforeLegacyIndexPublication: () -> Unit = {},
    private val afterDirectoryMetadataSync: (File) -> Unit = {},
    private val afterDurableIndexPublication: () -> Unit = {},
    private val policy: () -> VirtualFileCachePolicy,
) {
    private val activePaths = mutableMapOf<FileOfflineKey, Int>()
    private val activeRevisions = mutableMapOf<ActiveVirtualRangeRevision, Int>()
    private val deferredInvalidationRevisions = mutableSetOf<ActiveVirtualRangeRevision>()
    private val recoveredAccounts = mutableSetOf<String>()
    private val recoveredOverflowAccounts = mutableSetOf<String>()
    private val loadedIndexes = mutableMapOf<String, RangeCacheIndex>()
    private val dirtyAccessTimeAccounts = mutableSetOf<String>()
    private val lastAccessTimePersistence = mutableMapOf<String, Long>()
    private var tierAttention: String? = null
    private var expectedPrimaryRootIdentity = expectedPrimaryIdentity
    private var expectedOverflowRootIdentity = expectedOverflowIdentity

    init {
        require(maximumIndexBytes in 1L..MAX_INDEX_BYTES)
        require(maximumBlocks in 1..MAX_BLOCKS)
        require(accessTimePersistenceIntervalMillis >= 0L)
        require(expectedPrimaryIdentity == null || expectedPrimaryIdentity.isValidDesktopVirtualCacheRootIdentity())
        require(expectedOverflowIdentity == null || expectedOverflowIdentity.isValidDesktopVirtualCacheRootIdentity())
        if (initializePrimaryMarker) {
            prepareCacheRoot(root, createParentDirectories, required = true)
            val initializedIdentity = initializePrimaryRootIdentity(root)
            require(expectedPrimaryRootIdentity == null || expectedPrimaryRootIdentity == initializedIdentity) {
                "The selected primary cache drive does not match its configured identity."
            }
            expectedPrimaryRootIdentity = initializedIdentity
        } else if (requirePrimaryIdentity) {
            require(
                expectedPrimaryRootIdentity != null && isPrimaryRootAvailable(root),
            ) { "Reconnect the selected primary cache drive before using it." }
        } else {
            prepareCacheRoot(root, createParentDirectories, required = true)
        }
        overflowRoot?.let { configuredOverflow ->
            require(configuredOverflow.toPath().toAbsolutePath().normalize() != root.toPath().toAbsolutePath().normalize()) {
                "The primary and overflow cache locations must be different."
            }
            prepareCacheRoot(configuredOverflow, createParentDirectories = false, required = false)
            if (initializeOverflowMarker && isCacheRootDirectory(configuredOverflow)) {
                val initializedIdentity = initializeOverflowRootIdentity(configuredOverflow)
                require(expectedOverflowRootIdentity == null || expectedOverflowRootIdentity == initializedIdentity) {
                    "The selected overflow cache drive does not match its configured identity."
                }
                expectedOverflowRootIdentity = initializedIdentity
            }
        }
    }

    fun primaryIdentity(): String? = expectedPrimaryRootIdentity

    fun overflowIdentity(): String? = expectedOverflowRootIdentity

    @Synchronized
    fun acquire(
        accountId: String,
        path: String,
        remoteRevision: String? = null,
        fileSize: Long? = null,
    ) {
        val key = FileOfflineKey(accountId, path)
        activePaths[key] = activePaths.getOrDefault(key, 0) + 1
        activeRevision(key, remoteRevision, fileSize)?.let { revision ->
            activeRevisions[revision] = activeRevisions.getOrDefault(revision, 0) + 1
        }
    }

    @Synchronized
    fun release(
        accountId: String,
        path: String,
        remoteRevision: String? = null,
        fileSize: Long? = null,
    ) {
        val key = FileOfflineKey(accountId, path)
        val remaining = activePaths.getOrDefault(key, 1) - 1
        if (remaining <= 0) activePaths.remove(key) else activePaths[key] = remaining
        val revision = activeRevision(key, remoteRevision, fileSize)
        if (revision != null) {
            val revisionRemaining = activeRevisions.getOrDefault(revision, 1) - 1
            if (revisionRemaining <= 0) {
                activeRevisions.remove(revision)
                if (deferredInvalidationRevisions.remove(revision)) removeExactRevision(revision)
            } else {
                activeRevisions[revision] = revisionRemaining
            }
        } else if (remaining <= 0) {
            deferredInvalidationRevisions.filter { deferred -> deferred.file == key }
                .toList()
                .forEach { deferred ->
                    deferredInvalidationRevisions.remove(deferred)
                    removeExactRevision(deferred)
                }
        }
    }

    @Synchronized
    fun readBlock(
        accountId: String,
        path: String,
        remoteRevision: String,
        fileSize: Long,
        offset: Long,
        length: Int,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): ByteArray? {
        val normalized = FileOfflineKey(accountId, path).relativePath
        val index = load(accountId)
        val record = index.blocks.firstOrNull { block ->
            block.path == normalized &&
                block.remoteRevision == remoteRevision &&
                block.fileSize == fileSize &&
                block.offset == offset &&
                block.length == length
        } ?: return null
        if (record.storageTier == CachedRangeStorageTier.Overflow && !isOverflowAvailable()) return null
        val blob = blobFile(accountId, record)
        beforeBlockValidation(blob)
        if (!blob.isFile || blob.length() != length.toLong()) {
            if (record.storageTier == CachedRangeStorageTier.Overflow && !isOverflowAvailable()) return null
            removeRecord(accountId, index, record, blob)
            return null
        }
        val bytes = blob.readBytes()
        if (sha256Hex(bytes) != record.sha256) {
            removeRecord(accountId, index, record, blob)
            return null
        }
        var updated = index.copy(
            blocks = index.blocks.map { current ->
                if (current == record) current.copy(lastAccessedAtEpochMillis = nowEpochMillis) else current
            },
        )
        if (record.storageTier == CachedRangeStorageTier.Overflow) {
            updated = promoteBlock(accountId, updated, record, blob)
        }
        loadedIndexes[accountId] = updated
        dirtyAccessTimeAccounts += accountId
        val lastPersistence = lastAccessTimePersistence[accountId]
        if (
            accessTimePersistenceIntervalMillis == 0L ||
            lastPersistence == null ||
            nowEpochMillis >= lastPersistence && nowEpochMillis - lastPersistence >= accessTimePersistenceIntervalMillis
        ) {
            runCatching { persistAccessTimes(accountId, updated) }.onSuccess {
                dirtyAccessTimeAccounts -= accountId
                lastAccessTimePersistence[accountId] = nowEpochMillis
            }
        }
        if (record.storageTier == CachedRangeStorageTier.Overflow) {
            runCatching { applyEviction(accountId, requestedBytes = 0L, nowEpochMillis = nowEpochMillis) }
        }
        return bytes
    }

    /** Best-effort persistence for LRU hints; cached bytes remain valid if this write fails. */
    @Synchronized
    fun flushAccessTimes() {
        dirtyAccessTimeAccounts.toList().forEach { accountId ->
            val index = loadedIndexes[accountId] ?: return@forEach
            runCatching { persistAccessTimes(accountId, index) }.onSuccess {
                dirtyAccessTimeAccounts -= accountId
                lastAccessTimePersistence[accountId] = System.currentTimeMillis()
            }
        }
    }

    @Synchronized
    fun storeBlock(
        accountId: String,
        path: String,
        remoteRevision: String,
        fileSize: Long,
        offset: Long,
        bytes: ByteArray,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        require(remoteRevision.isNotBlank())
        require(fileSize > 0L && offset >= 0L && bytes.isNotEmpty())
        require(offset + bytes.size <= fileSize)
        require(bytes.size <= MAX_BLOCK_BYTES)
        val normalized = FileOfflineKey(accountId, path).relativePath
        val directory = writableAccountDirectory(accountId)
        val identity = "$normalized\u0000$remoteRevision\u0000$fileSize\u0000$offset\u0000${bytes.size}"
        val blobName = "${sha256Hex(identity)}.block"
        val current = load(accountId)
        val obsolete = current.blocks.filter { block ->
            block.path == normalized &&
                (block.remoteRevision != remoteRevision || block.fileSize != fileSize || block.offset == offset)
        }
        check(
            loadFolderRetention(accountId).retentionFor(normalized) != VirtualFolderRetention.KeepOnDevice ||
                obsolete.none { block ->
                    block.remoteRevision != remoteRevision || block.fileSize != fileSize
                },
        ) { "A retained file revision must be replaced as one complete staged generation." }
        val newRecord = CachedRangeBlock(
            path = normalized,
            remoteRevision = remoteRevision,
            fileSize = fileSize,
            offset = offset,
            length = bytes.size,
            blobName = blobName,
            sha256 = sha256Hex(bytes),
            cachedAtEpochMillis = nowEpochMillis,
            lastAccessedAtEpochMillis = nowEpochMillis,
        )
        val next = current.copy(blocks = current.blocks.filterNot { it in obsolete } + newRecord)
        requireIndexFits(accountId, next, newRecord)
        val alreadyReferenced = current.blocks.any { block -> block.blobName == blobName }
        try {
            publishBytes(directory, blobName, bytes)
            save(accountId, next)
        } catch (failure: Throwable) {
            if (!alreadyReferenced) File(directory, blobName).delete()
            throw failure
        }
        applyEviction(accountId, 0L, nowEpochMillis)
    }

    @Synchronized
    fun invalidate(accountId: String, path: String) {
        val normalized = FileOfflineKey(accountId, path).relativePath
        runCatching { invalidateRetainedListings(accountId, normalized) }
        runCatching { invalidateRangeBlocks(accountId, normalized) }
    }

    @Synchronized
    fun invalidateDisposableRanges(accountId: String, path: String) {
        val normalized = FileOfflineKey(accountId, path).relativePath
        runCatching { invalidateRangeBlocks(accountId, normalized) }
    }

    private fun invalidateRangeBlocks(accountId: String, path: String) {
        val normalized = FileOfflineKey(accountId, path).relativePath
        val current = load(accountId)
        val candidates = current.blocks.filter { block ->
            block.path == normalized || block.path.startsWith("$normalized/")
        }
        val removed = candidates.filter { block ->
            val key = FileOfflineKey(accountId, block.path)
            val revision = block.activeRevision(accountId)
            val revisionIsActive = activeRevisions.getOrDefault(revision, 0) > 0
            val legacyPathLeaseIsActive = activePaths.getOrDefault(key, 0) > 0 &&
                activeRevisions.keys.none { active -> active.file == key }
            if (revisionIsActive || legacyPathLeaseIsActive) {
                deferredInvalidationRevisions += revision
                false
            } else {
                true
            }
        }
        removed.forEach { block -> blobFile(accountId, block).delete() }
        if (removed.isNotEmpty()) save(accountId, current.copy(blocks = current.blocks.filterNot { it in removed }))
    }

    @Synchronized
    fun loadFolderRetention(accountId: String): VirtualFolderRetentionState =
        loadRetention(accountId).rules.toDomain()

    @Synchronized
    fun loadFolderHydrationStatuses(accountId: String): List<VirtualFolderHydrationStatus> =
        loadRetention(accountId).hydration.map(CachedVirtualFolderHydration::toDomain)

    @Synchronized
    fun loadFolderHydrationStatus(accountId: String, path: String): VirtualFolderHydrationStatus? {
        val normalized = FileOfflineKey(accountId, path).relativePath
        return loadRetention(accountId).hydration
            .firstOrNull { status -> status.relativePath == normalized }
            ?.toDomain()
    }

    @Synchronized
    fun loadValidatedFolderHydrationStatus(accountId: String, path: String): VirtualFolderHydrationStatus? {
        val normalized = FileOfflineKey(accountId, path).relativePath
        val current = loadFolderHydrationStatuses(accountId)
            .firstOrNull { status -> status.relativePath == normalized }
            ?: return null
        if (
            current.phase != VirtualFolderHydrationPhase.AvailableOffline ||
            hasVerifiedRetainedFolderCoverage(accountId, normalized)
        ) return current
        return VirtualFolderHydrationStatus(normalized, VirtualFolderHydrationPhase.Queued).also { queued ->
            setFolderHydrationStatus(accountId, queued)
        }
    }

    @Synchronized
    fun hasCompleteRevision(accountId: String, path: String, remoteRevision: String, fileSize: Long): Boolean {
        val revision = VirtualRangeRevision(
            FileOfflineKey(accountId, path).relativePath,
            remoteRevision,
            fileSize,
        )
        return revision in completeRevisions(accountId, listOf(revision))
    }

    @Synchronized
    fun completeRevisions(
        accountId: String,
        revisions: Collection<VirtualRangeRevision>,
    ): Set<VirtualRangeRevision> {
        if (revisions.isEmpty()) return emptySet()
        val expected = revisions.toHashSet()
        val index = load(accountId)
        val recordsByRevision = index.blocks.asSequence()
            .mapNotNull { block ->
                val revision = VirtualRangeRevision(block.path, block.remoteRevision, block.fileSize)
                if (revision in expected) revision to block else null
            }
            .groupBy({ it.first }, { it.second })
        val complete = linkedSetOf<VirtualRangeRevision>()
        val invalidRecords = hashSetOf<CachedRangeBlock>()
        expected.forEach { revision ->
            val records = recordsByRevision[revision].orEmpty().sortedBy(CachedRangeBlock::offset)
            val hasOverflowRecords = records.any { it.storageTier == CachedRangeStorageTier.Overflow }
            if (hasOverflowRecords && !isOverflowAvailable()) {
                return@forEach
            }
            var expectedOffset = 0L
            var overflowUnavailableDuringValidation = false
            val valid = records.isNotEmpty() && records.all { block ->
                if (block.offset != expectedOffset) return@all false
                val blob = blobFile(accountId, block)
                beforeBlockValidation(blob)
                if (
                    !blob.isFile ||
                    blob.length() != block.length.toLong() ||
                    runCatching { sha256Hex(blob) }.getOrNull() != block.sha256
                ) {
                    if (block.storageTier == CachedRangeStorageTier.Overflow && !isOverflowAvailable()) {
                        overflowUnavailableDuringValidation = true
                    }
                    return@all false
                }
                expectedOffset += block.length
                true
            } && expectedOffset == revision.fileSize
            if (valid) {
                complete += revision
            } else if (!overflowUnavailableDuringValidation && (!hasOverflowRecords || isOverflowAvailable())) {
                invalidRecords += records
            }
        }
        if (invalidRecords.isNotEmpty()) {
            val removableRecords = if (isOverflowAvailable()) {
                invalidRecords
            } else {
                val overflowBackedRevisions = invalidRecords.asSequence()
                    .filter { it.storageTier == CachedRangeStorageTier.Overflow }
                    .mapTo(hashSetOf()) { block -> VirtualRangeRevision(block.path, block.remoteRevision, block.fileSize) }
                invalidRecords.filterNotTo(hashSetOf()) { block ->
                    VirtualRangeRevision(block.path, block.remoteRevision, block.fileSize) in overflowBackedRevisions
                }
            }
            if (removableRecords.isNotEmpty()) {
                removableRecords.forEach { block -> blobFile(accountId, block).delete() }
                save(accountId, index.copy(blocks = index.blocks.filterNot(removableRecords::contains)))
            }
        }
        return complete
    }

    fun requireAvailable() {
        val cacheRoot = root.toPath().toAbsolutePath().normalize()
        val parent = requireNotNull(cacheRoot.parent)
        require(
            Files.isDirectory(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(parent) &&
                Files.isDirectory(cacheRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(cacheRoot) &&
                (!requirePrimaryIdentity || isPrimaryRootAvailable(root)),
        ) { "Reconnect the selected virtual-file storage drive before changing its location." }
    }

    @Synchronized
    fun hasUnavailableRetainedOverflowRecords(accountId: String, retainedRoot: String? = null): Boolean {
        if (isOverflowAvailable()) return false
        val normalizedRoot = retainedRoot?.let { FileOfflineKey(accountId, it).relativePath }
        val retention = loadFolderRetention(accountId)
        return load(accountId).blocks.any { block ->
            block.storageTier == CachedRangeStorageTier.Overflow &&
                retention.retentionFor(block.path) == VirtualFolderRetention.KeepOnDevice &&
                (normalizedRoot == null || block.path == normalizedRoot || block.path.startsWith("$normalizedRoot/"))
        }
    }

    @Synchronized
    fun cachedDirectChildren(accountId: String, directoryPath: String): Set<String> {
        val normalizedParent = directoryPath.trim('/')
        return load(accountId).blocks.mapNotNullTo(linkedSetOf()) { block ->
            val remainder = if (normalizedParent.isEmpty()) {
                block.path
            } else {
                block.path.removePrefix("$normalizedParent/").takeIf { it != block.path }
            } ?: return@mapNotNullTo null
            val childName = remainder.substringBefore('/')
            if (childName.isEmpty()) return@mapNotNullTo null
            if (normalizedParent.isEmpty()) childName else "$normalizedParent/$childName"
        }
    }

    @Synchronized
    fun beginRevisionStaging(
        accountId: String,
        path: String,
        remoteRevision: String,
        fileSize: Long,
        retention: VirtualFolderRetentionState? = null,
        preservePreviousRevisionUntilPublication: Boolean = false,
    ): RevisionStaging {
        require(remoteRevision.isNotBlank() && fileSize > 0L)
        val normalized = FileOfflineKey(accountId, path).relativePath
        val effectiveRetention = retention ?: loadFolderRetention(accountId)
        val storageTier = if (
            effectiveRetention.retentionFor(normalized) == VirtualFolderRetention.KeepOnDevice &&
            isOverflowAvailable()
        ) {
            CachedRangeStorageTier.Overflow
        } else {
            CachedRangeStorageTier.Primary
        }
        val directory = when (storageTier) {
            CachedRangeStorageTier.Primary -> writableAccountDirectory(accountId)
            CachedRangeStorageTier.Overflow -> overflowAccountDirectory(accountId, writable = true)
                ?: error("Reconnect the overflow cache drive before keeping this file offline.")
        }
        return RevisionStaging(
            accountId,
            normalized,
            remoteRevision,
            fileSize,
            directory,
            storageTier,
            retention,
            preservePreviousRevisionUntilPublication,
        )
    }

    @Synchronized
    fun hasRetainedRevisionStorageCapacity(
        accountId: String,
        path: String,
        fileSize: Long,
    ): Boolean {
        require(fileSize > 0L)
        val normalized = FileOfflineKey(accountId, path).relativePath
        val useOverflow = loadFolderRetention(accountId).retentionFor(normalized) ==
            VirtualFolderRetention.KeepOnDevice && isOverflowAvailable()
        val available = if (useOverflow) {
            overflowRoot?.takeIf(::isOverflowRootAvailable)?.usableSpace?.coerceAtLeast(0L) ?: 0L
        } else {
            root.usableSpace.coerceAtLeast(0L)
        }
        val reserve = if (useOverflow) policy().overflowMinimumFreeSpaceBytes else policy().minimumFreeSpaceBytes
        val required = if (Long.MAX_VALUE - fileSize < reserve) Long.MAX_VALUE else fileSize + reserve
        return available >= required
    }

    @Synchronized
    fun requireRevisionCapacity(accountId: String, path: String, fileSize: Long, blockBytes: Int) {
        requireRevisionCapacity(accountId, path, fileSize, blockBytes, retention = null)
    }

    @Synchronized
    fun requireRevisionCapacity(
        accountId: String,
        path: String,
        fileSize: Long,
        blockBytes: Int,
        retention: VirtualFolderRetentionState?,
    ) {
        requireRevisionsCapacity(
            accountId = accountId,
            revisions = listOf(
                VirtualRangeRevision(path, remoteRevision = "capacity-check", fileSize = fileSize),
            ),
            blockBytes = blockBytes,
            retention = retention,
        )
    }

    @Synchronized
    fun requireRevisionsCapacity(
        accountId: String,
        revisions: Collection<VirtualRangeRevision>,
        blockBytes: Int,
        retention: VirtualFolderRetentionState? = null,
        pendingRevisions: Collection<VirtualRangeRevision> = revisions,
    ) {
        require(blockBytes in 1..MAX_BLOCK_BYTES)
        if (revisions.isEmpty()) return
        val revisionsByPath = revisions.associateBy(VirtualRangeRevision::relativePath)
        require(revisionsByPath.size == revisions.size) {
            "The retained folder contains duplicate file paths."
        }
        val pendingByPath = pendingRevisions.associateBy(VirtualRangeRevision::relativePath)
        require(
            pendingByPath.size == pendingRevisions.size && pendingByPath.all { (path, revision) ->
                revisionsByPath[path] == revision
            },
        ) { "Pending retained revisions must belong to the complete retained snapshot." }
        val effectiveRetention = retention ?: loadFolderRetention(accountId)
        val currentBlocks = load(accountId).blocks
        val pinnedOtherBlocks = currentBlocks.count { block ->
            !block.pendingPublication &&
                block.path !in revisionsByPath &&
                effectiveRetention.retentionFor(block.path) == VirtualFolderRetention.KeepOnDevice
        }
        val availableBlocks = (maximumBlocks - pinnedOtherBlocks).toLong()
        val pendingOtherBlocks = currentBlocks.count { block ->
            if (!block.pendingPublication) return@count false
            val expected = pendingByPath[block.path] ?: return@count true
            block.remoteRevision != expected.remoteRevision || block.fileSize != expected.fileSize
        }
        val availablePendingBlocks = (maximumBlocks - pendingOtherBlocks).toLong()
        var requiredBlocks = 0L
        revisionsByPath.values.forEach { revision ->
            val revisionBlocks = (revision.fileSize - 1L) / blockBytes.toLong() + 1L
            require(requiredBlocks <= availableBlocks - revisionBlocks) {
                "The retained folders exceed the supported virtual-file cache index."
            }
            requiredBlocks += revisionBlocks
        }
        require(requiredBlocks <= availableBlocks) {
            "The retained folders exceed the supported virtual-file cache index."
        }
        var requiredPendingBlocks = 0L
        pendingByPath.values.forEach { revision ->
            val revisionBlocks = (revision.fileSize - 1L) / blockBytes.toLong() + 1L
            require(requiredPendingBlocks <= availablePendingBlocks - revisionBlocks) {
                "The pending retained-folder refresh exceeds the supported virtual-file cache index."
            }
            requiredPendingBlocks += revisionBlocks
        }
        val projectedPublishedBlocks = projectedExistingPublishedBlocks(
            currentBlocks,
            revisionsByPath.keys,
        ) + revisionsByPath.values.flatMap { revision ->
            projectedRangeBlocks(revision, blockBytes)
        }
        val projectedPublishedIndex = boundedIndex(
            accountId,
            RangeCacheIndex(blocks = projectedPublishedBlocks),
            effectiveRetention,
        )
        encodedIndex(projectedPublishedIndex)
    }

    private fun projectedExistingPublishedBlocks(
        currentBlocks: List<CachedRangeBlock>,
        replacedPaths: Set<String>,
    ): List<CachedRangeBlock> = currentBlocks.asSequence()
        .filter { block -> block.path !in replacedPaths }
        .groupBy(CachedRangeBlock::path)
        .values
        .flatMap { pathBlocks ->
            pathBlocks.groupBy { block -> block.remoteRevision to block.fileSize }
                .values
                .maxBy { revisionBlocks ->
                    rangeCacheJson.encodeToString(
                        RangeCacheIndex(
                            blocks = revisionBlocks.map { block -> block.copy(pendingPublication = false) },
                        ),
                    ).encodeToByteArray().size
                }
        }
        .map { block -> block.copy(pendingPublication = false) }

    private fun projectedRangeBlocks(
        revision: VirtualRangeRevision,
        blockBytes: Int,
    ): List<CachedRangeBlock> = buildList {
        var offset = 0L
        while (offset < revision.fileSize) {
            val length = minOf(blockBytes.toLong(), revision.fileSize - offset).toInt()
            add(
                CachedRangeBlock(
                    path = revision.relativePath,
                    remoteRevision = revision.remoteRevision,
                    fileSize = revision.fileSize,
                    offset = offset,
                    length = length,
                    blobName = PROJECTED_BLOCK_HASH + ".block",
                    sha256 = PROJECTED_BLOCK_HASH,
                    cachedAtEpochMillis = 0L,
                    lastAccessedAtEpochMillis = 0L,
                ),
            )
            offset += length
        }
    }

    @Synchronized
    fun setFolderRetention(accountId: String, path: String, retention: VirtualFolderRetention) {
        val current = loadRetention(accountId)
        val next = current.rules.toDomain().withRetention(path, retention)
        FileOfflineKey(accountId, path)
        val retainedStatusPaths = next.rules.asSequence()
            .filter { rule -> rule.retention == VirtualFolderRetention.KeepOnDevice }
            .mapTo(hashSetOf(), VirtualFolderRetentionRule::relativePath)
        saveRetention(
            accountId,
            current.copy(
                rules = next.rules.map { rule -> CachedVirtualFolderRule(rule.relativePath, rule.retention) },
                hydration = current.hydration.filter { status -> status.relativePath in retainedStatusPaths },
            ),
        )
    }

    @Synchronized
    fun setFolderHydrationStatus(accountId: String, status: VirtualFolderHydrationStatus) {
        val current = loadRetention(accountId)
        require(
            current.rules.toDomain().rules.any { rule ->
                rule.relativePath == status.relativePath && rule.retention == VirtualFolderRetention.KeepOnDevice
            },
        ) { "Hydration status requires an explicit keep-on-device rule." }
        saveRetention(
            accountId,
            current.copy(
                hydration = current.hydration.filterNot { it.relativePath == status.relativePath } +
                    CachedVirtualFolderHydration.fromDomain(status),
            ),
        )
    }

    @Synchronized
    fun retryFolderHydration(accountId: String, path: String) {
        val normalized = FileOfflineKey(accountId, path).relativePath
        val retention = loadFolderRetention(accountId)
        require(
            retention.rules.any { rule ->
                rule.relativePath == normalized && rule.retention == VirtualFolderRetention.KeepOnDevice
            },
        ) { "Only an explicitly retained folder can be retried." }
        val currentStatus = loadFolderHydrationStatuses(accountId)
            .firstOrNull { status -> status.relativePath == normalized }
        setFolderHydrationStatus(
            accountId,
            VirtualFolderHydrationStatus(
                normalized,
                if (currentStatus?.phase == VirtualFolderHydrationPhase.AvailableOffline) {
                    VirtualFolderHydrationPhase.AvailableOffline
                } else {
                    VirtualFolderHydrationPhase.Queued
                },
                refreshing = currentStatus?.phase == VirtualFolderHydrationPhase.AvailableOffline,
                verifiedAtEpochMillis = currentStatus?.verifiedAtEpochMillis,
            ),
        )
    }

    @Synchronized
    fun queueRetainedFoldersForRefresh(accountId: String, path: String): List<String> {
        val normalized = FileOfflineKey(accountId, path).relativePath
        return queueRetainedFoldersForListingRefresh(accountId, listOf(normalized))
    }

    @Synchronized
    fun queueRetainedFoldersForListingRefresh(accountId: String, changedPaths: Collection<String>): List<String> {
        val roots = retainedFoldersAffectedByListingChanges(accountId, changedPaths)
        if (roots.isEmpty()) return emptyList()
        return queueRetainedFolderRoots(loadRetention(accountId), accountId, roots)
    }

    @Synchronized
    fun retainedFoldersAffectedByListingChanges(accountId: String, changedPaths: Collection<String>): List<String> {
        if (changedPaths.isEmpty()) return emptyList()
        val normalizedPaths = changedPaths.mapTo(linkedSetOf()) { path ->
            FileOfflineKey(accountId, path).relativePath
        }
        val current = loadRetention(accountId)
        val retention = current.rules.toDomain()
        val roots = current.rules.asSequence()
            .filter { rule ->
                rule.retention == VirtualFolderRetention.KeepOnDevice && normalizedPaths.any { normalized ->
                    normalized == rule.relativePath ||
                        rule.relativePath.startsWith("$normalized/") ||
                        normalized.startsWith("${rule.relativePath}/") &&
                        retention.retentionFor(normalized) == VirtualFolderRetention.KeepOnDevice
                }
            }
            .map(CachedVirtualFolderRule::relativePath)
            .distinct()
            .toList()
        return roots
    }

    private fun queueRetainedFolderRoots(
        current: VirtualFolderRetentionIndex,
        accountId: String,
        roots: List<String>,
    ): List<String> {
        if (roots.isEmpty()) return emptyList()
        val queued = roots.map { root ->
            CachedVirtualFolderHydration.fromDomain(
                VirtualFolderHydrationStatus(root, VirtualFolderHydrationPhase.Queued),
            )
        }
        saveRetention(
            accountId,
            current.copy(
                hydration = current.hydration.filterNot { status -> status.relativePath in roots } + queued,
            ),
        )
        return roots
    }

    @Synchronized
    fun hasCompleteRetainedFolder(accountId: String, path: String): Boolean {
        val normalized = FileOfflineKey(accountId, path).relativePath
        val revisions = retainedFolderRevisions(accountId, normalized) ?: return false
        return completeRevisions(accountId, revisions).size == revisions.distinct().size
    }

    private fun hasVerifiedRetainedFolderCoverage(accountId: String, normalized: String): Boolean {
        val revisions = retainedFolderRevisions(accountId, normalized) ?: return false
        return completeRevisions(accountId, revisions).size == revisions.distinct().size
    }

    private fun retainedFolderRevisions(accountId: String, normalized: String): List<VirtualRangeRevision>? {
        val retention = loadFolderRetention(accountId)
        if (retention.retentionFor(normalized) != VirtualFolderRetention.KeepOnDevice) return null
        val metadataIndex = loadRetainedMetadataIndex(accountId)
        if (retainedFolderAncestorListings(normalized).any { ancestor ->
                loadRetainedListing(accountId, ancestor, metadataIndex, retention) == null
            }
        ) return null
        val pending = ArrayDeque<String>().apply { add(normalized) }
        val visited = hashSetOf<String>()
        val revisions = mutableListOf<VirtualRangeRevision>()
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            if (!visited.add(directory)) continue
            if (visited.size > MAX_VIRTUAL_FOLDER_RETAINED_LISTINGS) return null
            val listing = loadRetainedListing(accountId, directory, metadataIndex, retention) ?: return null
            listing.nodes.forEach { node ->
                if (retention.retentionFor(node.path) != VirtualFolderRetention.KeepOnDevice) return@forEach
                if (node.directory) {
                    pending.add(node.path)
                } else if (node.size > 0L) revisions += VirtualRangeRevision(
                    node.path,
                    node.remoteRevision,
                    node.size,
                )
            }
        }
        return revisions
    }

    @Synchronized
    fun loadRetainedListing(accountId: String, path: String): LinuxVirtualDirectorySnapshot? {
        val normalized = path.trim('/')
        val retention = loadFolderRetention(accountId)
        val covered = retention.rules.any { rule ->
            rule.retention == VirtualFolderRetention.KeepOnDevice &&
                (
                    normalized.isEmpty() ||
                        normalized == rule.relativePath ||
                        normalized.startsWith("${rule.relativePath}/") ||
                        rule.relativePath.startsWith("$normalized/")
                    )
        }
        if (!covered) return null
        return loadRetainedListing(accountId, normalized, loadRetainedMetadataIndex(accountId), retention)
    }

    private fun loadRetainedListing(
        accountId: String,
        normalized: String,
        index: RetainedMetadataIndex,
        retention: VirtualFolderRetentionState,
    ): LinuxVirtualDirectorySnapshot? {
        val reference = index.listings.firstOrNull { it.path == normalized } ?: return null
        val blob = File(accountDirectory(accountId), reference.blobName)
        if (!blob.isFile || blob.length() !in 1L..MAX_RETAINED_LISTING_BYTES) return null
        return runCatching {
            val encoded = blob.readBytes()
            require(sha256Hex(encoded) == reference.sha256)
            rangeCacheJson.decodeFromString<RetainedDirectoryListing>(encoded.decodeToString())
                .also { listing -> listing.requireValid(normalized) }
                .toSnapshot(
                    completeWhenUnspecified = retention.rules.any { rule ->
                        rule.retention == VirtualFolderRetention.KeepOnDevice &&
                            isCompleteRetainedTreeListing(normalized, rule.relativePath)
                    },
                )
        }.getOrNull()
    }

    @Synchronized
    fun retainedListingCountSurvivingPublication(
        accountId: String,
        retainedRoot: String,
        publishedPaths: Set<String>,
    ): Int {
        val normalizedRoot = FileOfflineKey(accountId, retainedRoot).relativePath
        require(normalizedRoot in publishedPaths)
        publishedPaths.forEach { path -> if (path.isNotEmpty()) FileOfflineKey(accountId, path) }
        return retainedListingsSurvivingPublication(
            accountId = accountId,
            normalizedRoot = normalizedRoot,
            publishedPaths = publishedPaths,
            current = loadRetainedMetadataIndex(accountId),
        ).size
    }

    @Synchronized
    internal fun retainedListingPaths(accountId: String): Set<String> =
        loadRetainedMetadataIndex(accountId).listings.mapTo(linkedSetOf(), RetainedListingReference::path)

    @Synchronized
    fun publishRetainedListings(
        accountId: String,
        retainedRoot: String,
        snapshots: Map<String, LinuxVirtualDirectorySnapshot>,
    ) {
        val normalizedRoot = FileOfflineKey(accountId, retainedRoot).relativePath
        require(snapshots.isNotEmpty() && normalizedRoot in snapshots)
        require(snapshots.size <= MAX_VIRTUAL_FOLDER_RETAINED_LISTINGS)
        val directory = writableAccountDirectory(accountId)
        val publishedPaths = snapshots.keys.mapTo(hashSetOf()) { path -> path.trim('/') }
        require(publishedPaths.size == snapshots.size) { "Retained folder listings contain duplicate paths." }
        val current = loadRetainedMetadataIndex(accountId)
        val surviving = retainedListingsSurvivingPublication(accountId, normalizedRoot, publishedPaths, current)
        check(snapshots.size <= MAX_VIRTUAL_FOLDER_RETAINED_LISTINGS - surviving.size) {
            "The retained virtual-folder metadata index contains too many listings."
        }
        val published = snapshots.map { (path, snapshot) ->
            val normalized = path.trim('/')
            RetainedDirectoryListing.fromSnapshot(normalized, snapshot).let { listing ->
                val encoded = rangeCacheJson.encodeToString(listing).encodeToByteArray()
                require(encoded.size.toLong() <= MAX_RETAINED_LISTING_BYTES) {
                    "A retained folder listing is too large to publish safely."
                }
                val hash = sha256Hex(encoded)
                val blobName = "$hash.listing"
                val blob = File(directory, blobName)
                if (!blob.isFile || runCatching { sha256Hex(blob) }.getOrNull() != hash) {
                    publishBytes(directory, blobName, encoded)
                }
                RetainedListingReference(normalized, blobName, hash)
            }
        }
        val next = RetainedMetadataIndex(
            listings = (surviving + published).sortedBy(RetainedListingReference::path),
        )
        try {
            saveRetainedMetadataIndex(accountId, next)
        } catch (failure: Throwable) {
            val previouslyReferenced = current.listings.mapTo(hashSetOf(), RetainedListingReference::blobName)
            published.asSequence()
                .filterNot { reference -> reference.blobName in previouslyReferenced }
                .forEach { reference -> File(directory, reference.blobName).delete() }
            throw failure
        }
    }

    private fun retainedListingsSurvivingPublication(
        accountId: String,
        normalizedRoot: String,
        publishedPaths: Set<String>,
        current: RetainedMetadataIndex,
    ): List<RetainedListingReference> {
        val nestedRetainedRoots = loadFolderRetention(accountId).rules.asSequence()
            .filter { rule ->
                rule.retention == VirtualFolderRetention.KeepOnDevice &&
                    rule.relativePath.startsWith("$normalizedRoot/")
            }
            .mapTo(hashSetOf(), VirtualFolderRetentionRule::relativePath)
        return current.listings.filterNot { reference ->
            val replacedByPublished = reference.path in publishedPaths
            val insidePublishedRoot = reference.path == normalizedRoot ||
                reference.path.startsWith("$normalizedRoot/")
            val requiredByNestedRoot = nestedRetainedRoots.any { nestedRoot ->
                reference.path == nestedRoot ||
                    reference.path.startsWith("$nestedRoot/") ||
                    nestedRoot.startsWith("${reference.path}/")
            }
            replacedByPublished || insidePublishedRoot && !requiredByNestedRoot
        }
    }

    @Synchronized
    fun publishRetainedRevisions(
        accountId: String,
        retainedRoot: String,
        revisions: Collection<VirtualRangeRevision>,
        retention: VirtualFolderRetentionState,
    ) {
        val normalizedRoot = FileOfflineKey(accountId, retainedRoot).relativePath
        val expectedByPath = revisions.associateBy(VirtualRangeRevision::relativePath)
        require(expectedByPath.size == revisions.size) { "A retained folder contains duplicate file paths." }
        val current = load(accountId)
        val next = current.copy(
            blocks = current.blocks.mapNotNull { block ->
                val insideRoot = block.path == normalizedRoot || block.path.startsWith("$normalizedRoot/")
                if (!insideRoot) return@mapNotNull block
                val expected = expectedByPath[block.path]
                val nestedRetainedRoot = retention.keepOnDeviceRootFor(block.path)
                    ?.takeIf { root -> root != normalizedRoot && root.startsWith("$normalizedRoot/") }
                if (expected != null) {
                    if (
                        block.remoteRevision == expected.remoteRevision &&
                        block.fileSize == expected.fileSize
                    ) {
                        block.copy(pendingPublication = false)
                    } else if (block.isActiveOrDeferred(accountId)) {
                        deferredInvalidationRevisions += block.activeRevision(accountId)
                        block.copy(pendingPublication = true)
                    } else {
                        null
                    }
                } else if (nestedRetainedRoot != null) {
                    block
                } else if (block.pendingPublication) {
                    null
                } else if (retention.retentionFor(block.path) == VirtualFolderRetention.KeepOnDevice) {
                    null
                } else {
                    block
                }
            },
        )
        save(accountId, next, retention)
    }

    @Synchronized
    fun invalidateRetainedListings(accountId: String, path: String) {
        val normalized = FileOfflineKey(accountId, path).relativePath
        val parent = normalized.substringBeforeLast('/', "")
        val current = loadRetainedMetadataIndex(accountId)
        val next = current.copy(
            listings = current.listings.filterNot { reference ->
                reference.path == normalized ||
                    reference.path.startsWith("$normalized/") ||
                    reference.path == parent
            },
        )
        if (next != current) saveRetainedMetadataIndex(accountId, next)
    }

    private fun removeDehydratedRetainedListings(accountId: String, path: String) {
        val normalized = FileOfflineKey(accountId, path).relativePath
        val current = loadRetainedMetadataIndex(accountId)
        val releasedAncestors = retainedFolderAncestorListings(normalized).toSet()
        val requiredByRemainingRoots = loadFolderRetention(accountId).rules.asSequence()
            .filter { rule -> rule.retention == VirtualFolderRetention.KeepOnDevice }
            .flatMap { rule ->
                (retainedFolderAncestorListings(rule.relativePath) + rule.relativePath).asSequence()
            }
            .toSet()
        val next = current.copy(
            listings = current.listings.filterNot { reference ->
                reference.path == normalized ||
                    reference.path.startsWith("$normalized/") ||
                    reference.path in releasedAncestors && reference.path !in requiredByRemainingRoots
            },
        )
        if (next != current) saveRetainedMetadataIndex(accountId, next)
    }

    /** Releases only inactive, automatic blocks in the selected subtree. */
    @Synchronized
    fun dehydrateFolder(accountId: String, path: String, protectedPaths: Set<String>): Long {
        val normalized = FileOfflineKey(accountId, path).relativePath
        val current = load(accountId)
        val retention = loadFolderRetention(accountId)
        val candidates = current.blocks.asSequence()
            .map(CachedRangeBlock::path)
            .distinct()
            .filter { candidate -> candidate == normalized || candidate.startsWith("$normalized/") }
            .filter { candidate -> retention.retentionFor(candidate) == VirtualFolderRetention.Automatic }
            .filter { candidate -> candidate !in protectedPaths }
            .toSet()
        candidates.asSequence()
            .flatMap { candidate ->
                current.blocks.asSequence()
                    .filter { block -> block.path == candidate }
                    .map { block -> block.activeRevision(accountId) }
            }
            .filter { revision ->
                activeRevisions.getOrDefault(revision, 0) > 0 ||
                    activePaths.getOrDefault(revision.file, 0) > 0 &&
                    activeRevisions.keys.none { active -> active.file == revision.file }
            }
            .forEach(deferredInvalidationRevisions::add)
        val removablePaths = candidates.filter { candidate ->
            activePaths.getOrDefault(FileOfflineKey(accountId, candidate), 0) == 0
        }.toSet()
        removeDehydratedRetainedListings(accountId, normalized)
        if (removablePaths.isEmpty()) return 0L
        val removed = current.blocks.filter { block -> block.path in removablePaths }
        removed.forEach { block -> blobFile(accountId, block).delete() }
        save(accountId, current.copy(blocks = current.blocks.filterNot { block -> block.path in removablePaths }))
        return removed.fold(0L) { total, block ->
            if (Long.MAX_VALUE - total < block.length) Long.MAX_VALUE else total + block.length
        }
    }

    @Synchronized
    fun summary(accountId: String): DesktopVirtualRangeCacheSummary {
        val index = load(accountId)
        val entries = index.toDomain(accountId)
        val primaryEntries = index.toDomain(accountId, CachedRangeStorageTier.Primary)
        val overflowEntries = index.toDomain(accountId, CachedRangeStorageTier.Overflow)
        val primaryPlan = planVirtualFileEviction(
            entries = primaryEntries.filter { entry -> entry.retention == VirtualFileRetention.Automatic },
            policy = policy(),
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
            nowEpochMillis = System.currentTimeMillis(),
        )
        val overflowPlan = planVirtualFileEviction(
            entries = overflowEntries.filter { entry -> entry.retention == VirtualFileRetention.Automatic },
            policy = overflowPolicy(),
            availableFreeBytes = overflowRoot?.takeIf(::isOverflowRootAvailable)?.usableSpace?.coerceAtLeast(0L) ?: 0L,
            nowEpochMillis = System.currentTimeMillis(),
        )
        return DesktopVirtualRangeCacheSummary(
            cachedBytes = entries.sumOf(VirtualFileCacheEntry::sizeBytes),
            reclaimableBytes = primaryPlan.reclaimableBytes + overflowPlan.reclaimableBytes,
            pinnedBytes = entries
                .filter { entry -> entry.retention == VirtualFileRetention.Pinned }
                .sumOf(VirtualFileCacheEntry::sizeBytes),
            fileCount = entries.size,
            pinnedFileCount = entries.count { entry -> entry.retention == VirtualFileRetention.Pinned },
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
            primaryCachedBytes = primaryEntries.sumOf(VirtualFileCacheEntry::sizeBytes),
            primaryReclaimableBytes = primaryPlan.reclaimableBytes,
            primaryPinnedBytes = primaryEntries.filter { it.retention == VirtualFileRetention.Pinned }
                .sumOf(VirtualFileCacheEntry::sizeBytes),
            overflowCachedBytes = overflowEntries.sumOf(VirtualFileCacheEntry::sizeBytes),
            overflowReclaimableBytes = overflowPlan.reclaimableBytes,
            overflowPinnedBytes = overflowEntries.filter { it.retention == VirtualFileRetention.Pinned }
                .sumOf(VirtualFileCacheEntry::sizeBytes),
            overflowAvailableFreeBytes = overflowRoot?.takeIf(::isOverflowRootAvailable)
                ?.usableSpace?.coerceAtLeast(0L),
            overflowAvailable = overflowRoot?.let(::isOverflowRootAvailable) == true,
            tierAttention = tierAttention,
        )
    }

    fun availableFreeBytes(): Long = root.usableSpace.coerceAtLeast(0L)

    @Synchronized
    fun consolidateOverflow(accountId: String) {
        val current = load(accountId)
        val overflowBlocks = current.blocks.filter { it.storageTier == CachedRangeStorageTier.Overflow }
        if (overflowBlocks.isEmpty()) return
        check(isOverflowAvailable()) { "Reconnect the overflow cache drive before changing its location." }
        val requiredBytes = overflowBlocks.sumOf { it.length.toLong() }
        val usableBeyondReserve = (root.usableSpace.coerceAtLeast(0L) - policy().minimumFreeSpaceBytes)
            .coerceAtLeast(0L)
        check(usableBeyondReserve >= requiredBytes) {
            "The primary cache does not have enough free space to preserve overflow content."
        }
        moveBlocks(accountId, current, overflowBlocks, CachedRangeStorageTier.Primary)
    }

    @Synchronized
    fun copyPrimaryAccountTo(accountId: String, destination: DesktopVirtualRangeCache) {
        check(activePaths.keys.none { it.accountId == accountId } && activeRevisions.keys.none { it.file.accountId == accountId }) {
            "Close files from this account before moving the primary cache."
        }
        flushAccessTimes()
        val sourceDirectory = accountDirectory(accountId)
        if (!sourceDirectory.isDirectory) return
        val sourceFiles = sourceDirectory.listFiles().orEmpty()
        require(sourceFiles.all { file -> file.isFile && !Files.isSymbolicLink(file.toPath()) }) {
            "The primary cache contains an active or invalid migration artifact."
        }
        val destinationDirectory = destination.writableAccountDirectory(accountId)
        val existingDestinationFiles = destinationDirectory.listFiles().orEmpty()
        if (existingDestinationFiles.isNotEmpty()) {
            require(
                existingDestinationFiles.all { file -> file.isFile && !Files.isSymbolicLink(file.toPath()) } &&
                    existingDestinationFiles.map(File::getName).toSet() == sourceFiles.map(File::getName).toSet() &&
                    sourceFiles.all { source ->
                        val existing = File(destinationDirectory, source.name)
                        existing.length() == source.length() && sha256Hex(existing) == sha256Hex(source)
                    },
            ) {
                "The selected primary cache contains different data for this account."
            }
            destination.loadedIndexes.remove(accountId)
            check(destination.load(accountId) == load(accountId)) {
                "The completed primary cache copy no longer matches the source index."
            }
            check(destination.loadFolderRetention(accountId) == loadFolderRetention(accountId)) {
                "The completed primary cache copy no longer matches the offline-folder rules."
            }
            return
        }
        val published = mutableListOf<File>()
        try {
            sourceFiles.sortedWith(
                compareBy<File> { it.name in setOf(LEGACY_INDEX_FILE, TIERED_INDEX_FILE) }.thenBy(File::getName),
            ).forEach { source ->
                val target = File(destinationDirectory, source.name)
                destination.publishFile(source, target, sha256Hex(source))
                published += target
            }
            destination.loadedIndexes.remove(accountId)
            check(destination.load(accountId) == load(accountId)) {
                "The primary cache index changed while it was being moved."
            }
            check(destination.loadFolderRetention(accountId) == loadFolderRetention(accountId)) {
                "The offline-folder rules changed while the primary cache was being moved."
            }
        } catch (failure: Throwable) {
            published.forEach(File::delete)
            destinationDirectory.delete()
            throw failure
        }
    }

    @Synchronized
    fun removeCopiedPrimaryAccount(accountId: String) {
        check(activePaths.keys.none { it.accountId == accountId } && activeRevisions.keys.none { it.file.accountId == accountId })
        val directory = accountDirectory(accountId)
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) return
        directory.listFiles().orEmpty()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .forEach(File::delete)
        directory.delete()
        loadedIndexes.remove(accountId)
        recoveredAccounts.remove(accountId)
    }

    @Synchronized
    fun freeUp(accountId: String, requestedBytes: Long): VirtualFileEvictionPlan =
        applyEviction(accountId, requestedBytes, System.currentTimeMillis())

    @Synchronized
    fun relievePrimaryPressure(accountId: String, requestedBytes: Long): Long {
        require(requestedBytes >= 0L)
        if (requestedBytes == 0L) return 0L
        val current = load(accountId)
        val primaryAutomatic = current.toDomain(accountId, CachedRangeStorageTier.Primary).filter { entry ->
            entry.retention == VirtualFileRetention.Automatic
        }
        val plan = planVirtualFileEviction(
            entries = primaryAutomatic,
            policy = policy().copy(automaticCleanup = false),
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
            nowEpochMillis = System.currentTimeMillis(),
            requestedBytesToFree = requestedBytes,
        )
        var next = current
        var relieved = 0L
        plan.evictions.forEach { eviction ->
            val matching = next.blocks.filter {
                it.path == eviction.key.relativePath && it.storageTier == CachedRangeStorageTier.Primary
            }
            if (matching.isEmpty() || activePaths.getOrDefault(eviction.key, 0) != 0) return@forEach
            if (matching.localRevision() != eviction.expectedLocalRevision) return@forEach
            val updated = if (isOverflowAvailable()) {
                runCatching { moveBlocks(accountId, next, matching, CachedRangeStorageTier.Overflow) }.getOrNull()
                    ?: return@forEach
            } else {
                matching.forEach { block -> blobFile(accountId, block).delete() }
                next.copy(blocks = next.blocks.filterNot { it in matching })
            }
            next = updated
            relieved += matching.sumOf { it.length.toLong() }
        }
        if (next != current) save(accountId, next)
        if (isOverflowAvailable()) {
            val afterDemotion = load(accountId)
            val overflowPlan = planVirtualFileEviction(
                entries = afterDemotion.toDomain(accountId, CachedRangeStorageTier.Overflow).filter { entry ->
                    entry.retention == VirtualFileRetention.Automatic
                },
                policy = overflowPolicy(),
                availableFreeBytes = overflowRoot?.takeIf(::isOverflowRootAvailable)
                    ?.usableSpace?.coerceAtLeast(0L) ?: 0L,
                nowEpochMillis = System.currentTimeMillis(),
                requestedBytesToFree = 0L,
            )
            if (overflowPlan.evictions.isNotEmpty()) {
                val bounded = removePlannedBlocks(accountId, afterDemotion, overflowPlan).first
                if (bounded != afterDemotion) save(accountId, bounded)
            }
        }
        return relieved
    }

    private fun applyEviction(accountId: String, requestedBytes: Long, nowEpochMillis: Long): VirtualFileEvictionPlan {
        val current = load(accountId)
        val overflowAvailable = isOverflowAvailable()
        val overflowEntries = if (overflowAvailable) {
            current.toDomain(accountId, CachedRangeStorageTier.Overflow).filter { entry ->
                entry.retention == VirtualFileRetention.Automatic
            }
        } else {
            emptyList()
        }
        val overflowPlan = planVirtualFileEviction(
            entries = overflowEntries,
            policy = overflowPolicy(),
            availableFreeBytes = overflowRoot?.takeIf(::isOverflowRootAvailable)?.usableSpace?.coerceAtLeast(0L) ?: 0L,
            nowEpochMillis = nowEpochMillis,
            requestedBytesToFree = requestedBytes,
        )
        var next = current
        var actuallyFreed = removePlannedBlocks(accountId, next, overflowPlan).also { result -> next = result.first }.second
        val remainingRequested = (requestedBytes - actuallyFreed).coerceAtLeast(0L)
        val allPrimaryEntries = next.toDomain(accountId, CachedRangeStorageTier.Primary)
        val primaryEntries = allPrimaryEntries.filter { entry ->
            entry.retention == VirtualFileRetention.Automatic
        }
        val primaryRequiredBytes = if (overflowAvailable && requestedBytes == 0L && policy().automaticCleanup) {
            val totalPrimaryBytes = allPrimaryEntries.sumOf(VirtualFileCacheEntry::sizeBytes)
            maxOf(
                policy().maximumCacheBytes?.let { (totalPrimaryBytes - it).coerceAtLeast(0L) } ?: 0L,
                (policy().minimumFreeSpaceBytes - root.usableSpace.coerceAtLeast(0L)).coerceAtLeast(0L),
            )
        } else {
            0L
        }
        val primaryPlan = planVirtualFileEviction(
            entries = primaryEntries,
            policy = policy(),
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
            nowEpochMillis = nowEpochMillis,
            requestedBytesToFree = maxOf(remainingRequested, primaryRequiredBytes),
        )
        primaryPlan.evictions.forEach { eviction ->
            val matching = next.blocks.filter {
                it.path == eviction.key.relativePath && it.storageTier == CachedRangeStorageTier.Primary
            }
            if (matching.isEmpty() || activePaths.getOrDefault(eviction.key, 0) != 0) return@forEach
            if (matching.localRevision() != eviction.expectedLocalRevision) return@forEach
            val shouldDelete = requestedBytes > 0L || !isOverflowAvailable()
            if (shouldDelete) {
                matching.forEach { block -> blobFile(accountId, block).delete() }
                next = next.copy(blocks = next.blocks.filterNot { it in matching })
                actuallyFreed += matching.sumOf { it.length.toLong() }
            } else {
                next = runCatching {
                    moveBlocks(accountId, next, matching, CachedRangeStorageTier.Overflow)
                }.getOrDefault(next)
            }
        }
        if (requestedBytes == 0L && overflowAvailable) {
            val remainingPrimaryEntries = next.toDomain(accountId, CachedRangeStorageTier.Primary)
            val remainingPressure = if (policy().automaticCleanup) {
                maxOf(
                    policy().maximumCacheBytes?.let { maximum ->
                        (remainingPrimaryEntries.sumOf(VirtualFileCacheEntry::sizeBytes) - maximum).coerceAtLeast(0L)
                    } ?: 0L,
                    (policy().minimumFreeSpaceBytes - root.usableSpace.coerceAtLeast(0L)).coerceAtLeast(0L),
                )
            } else {
                0L
            }
            val unusedCutoff = if (policy().automaticCleanup) {
                policy().unusedFileAgeMillis?.let { (nowEpochMillis - it).coerceAtLeast(0L) }
            } else {
                null
            }
            var selectedBytes = 0L
            val pinnedToDemote = remainingPrimaryEntries.asSequence()
                .filter { it.retention == VirtualFileRetention.Pinned && it.activeLeaseCount == 0 }
                .sortedBy(VirtualFileCacheEntry::lastAccessedAtEpochMillis)
                .filter { entry ->
                    val cold = unusedCutoff != null && entry.lastAccessedAtEpochMillis <= unusedCutoff
                    val neededForPressure = selectedBytes < remainingPressure
                    if (cold || neededForPressure) {
                        selectedBytes += entry.sizeBytes
                        true
                    } else {
                        false
                    }
                }
                .map(VirtualFileCacheEntry::key)
                .toSet()
            val pinnedBlocks = next.blocks.filter { block ->
                block.storageTier == CachedRangeStorageTier.Primary &&
                    FileOfflineKey(accountId, block.path) in pinnedToDemote
            }
            if (pinnedBlocks.isNotEmpty()) {
                next = runCatching {
                    moveBlocks(accountId, next, pinnedBlocks, CachedRangeStorageTier.Overflow)
                }.getOrDefault(next)
            }
        }
        val postDemotionOverflowPlan = if (overflowAvailable) {
            planVirtualFileEviction(
                entries = next.toDomain(accountId, CachedRangeStorageTier.Overflow).filter { entry ->
                    entry.retention == VirtualFileRetention.Automatic
                },
                policy = overflowPolicy(),
                availableFreeBytes = overflowRoot?.takeIf(::isOverflowRootAvailable)
                    ?.usableSpace?.coerceAtLeast(0L) ?: 0L,
                nowEpochMillis = nowEpochMillis,
                requestedBytesToFree = 0L,
            )
        } else {
            planVirtualFileEviction(
                entries = emptyList(),
                policy = overflowPolicy(),
                availableFreeBytes = 0L,
                nowEpochMillis = nowEpochMillis,
                requestedBytesToFree = 0L,
            )
        }
        if (postDemotionOverflowPlan.evictions.isNotEmpty()) {
            val removal = removePlannedBlocks(accountId, next, postDemotionOverflowPlan)
            next = removal.first
            actuallyFreed += removal.second
        }
        if (next != current) {
            save(accountId, next)
        }
        val allEntries = current.toDomain(accountId).filter { it.retention == VirtualFileRetention.Automatic }
        val allEvictions = (
            overflowPlan.evictions + primaryPlan.evictions + postDemotionOverflowPlan.evictions
        ).distinctBy(VirtualFileEviction::key)
        return VirtualFileEvictionPlan(
            evictions = allEvictions,
            cachedBytes = allEntries.sumOf(VirtualFileCacheEntry::sizeBytes),
            reclaimableBytes = allEntries.filter(VirtualFileCacheEntry::isEvictable)
                .sumOf(VirtualFileCacheEntry::sizeBytes),
            plannedFreedBytes = actuallyFreed,
            requiredFreedBytes = requestedBytes,
            unmetRequiredBytes = (requestedBytes - actuallyFreed).coerceAtLeast(0L),
        )
    }

    private fun removePlannedBlocks(
        accountId: String,
        index: RangeCacheIndex,
        plan: VirtualFileEvictionPlan,
    ): Pair<RangeCacheIndex, Long> {
        var next = index
        var freed = 0L
        plan.evictions.forEach { eviction ->
            val matching = next.blocks.filter {
                it.path == eviction.key.relativePath && it.storageTier == CachedRangeStorageTier.Overflow
            }
            if (matching.isEmpty() || activePaths.getOrDefault(eviction.key, 0) != 0) return@forEach
            if (matching.localRevision() != eviction.expectedLocalRevision) return@forEach
            matching.forEach { block -> blobFile(accountId, block).delete() }
            next = next.copy(blocks = next.blocks.filterNot { it in matching })
            freed += matching.sumOf { it.length.toLong() }
        }
        return next to freed
    }

    private fun RangeCacheIndex.toDomain(
        accountId: String,
        tier: CachedRangeStorageTier? = null,
    ): List<VirtualFileCacheEntry> {
        val retention = loadFolderRetention(accountId)
        return blocks.asSequence().filter { tier == null || it.storageTier == tier }
            .groupBy(CachedRangeBlock::path).map { (path, fileBlocks) ->
            VirtualFileCacheEntry(
                key = FileOfflineKey(accountId, path),
                remoteRevision = fileBlocks.first().remoteRevision,
                localRevision = fileBlocks.localRevision(),
                sizeBytes = fileBlocks.sumOf(CachedRangeBlock::length).toLong(),
                cachedAtEpochMillis = fileBlocks.minOf(CachedRangeBlock::cachedAtEpochMillis),
                lastAccessedAtEpochMillis = fileBlocks.maxOf(CachedRangeBlock::lastAccessedAtEpochMillis),
                retention = if (
                    retention.retentionFor(path) == VirtualFolderRetention.KeepOnDevice
                ) VirtualFileRetention.Pinned else VirtualFileRetention.Automatic,
                activeLeaseCount = activePaths.getOrDefault(FileOfflineKey(accountId, path), 0),
            )
        }
    }

    private fun overflowPolicy(): VirtualFileCachePolicy = policy().let { configured ->
        configured.copy(
            maximumCacheBytes = configured.overflowMaximumCacheBytes,
            minimumFreeSpaceBytes = configured.overflowMinimumFreeSpaceBytes,
            unusedFileAgeMillis = null,
        )
    }

    private fun List<CachedRangeBlock>.localRevision(): String = "sha256:" + sha256Hex(
        sortedBy(CachedRangeBlock::offset).joinToString("|") { block ->
            "${block.remoteRevision}:${block.offset}:${block.length}:${block.sha256}"
        },
    )

    private fun load(accountId: String): RangeCacheIndex {
        loadedIndexes[accountId]?.let { index ->
            recoverOverflowCacheArtifacts(accountId)
            return index
        }
        return (loadRangeIndexFromDirectory(accountDirectory(accountId)) ?: RangeCacheIndex()).also { index ->
            loadedIndexes[accountId] = index
            recoverOverflowCacheArtifacts(accountId)
        }
    }

    private fun loadRetention(accountId: String): VirtualFolderRetentionIndex {
        val directory = accountDirectory(accountId)
        val file = File(directory, RETENTION_INDEX_FILE)
        if (Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            require(
                file.isFile &&
                    !Files.isSymbolicLink(file.toPath()) &&
                    file.length() in 1L..MAX_RETENTION_INDEX_BYTES
            ) { "The virtual-folder retention index is unreadable." }
            return rangeCacheJson.decodeFromString<VirtualFolderRetentionIndex>(file.readText()).also { index ->
                index.requireValid()
            }
        }
        val legacy = load(accountId).folderRules
        if (legacy.isEmpty()) return VirtualFolderRetentionIndex()
        return VirtualFolderRetentionIndex(rules = legacy).also { migrated ->
            runCatching { saveRetention(accountId, migrated) }
        }
    }

    private fun loadRetainedMetadataIndex(accountId: String): RetainedMetadataIndex {
        val file = File(accountDirectory(accountId), RETAINED_METADATA_INDEX_FILE)
        if (!file.isFile || file.length() !in 1L..MAX_RETAINED_METADATA_INDEX_BYTES) {
            return RetainedMetadataIndex()
        }
        return runCatching {
            rangeCacheJson.decodeFromString<RetainedMetadataIndex>(file.readText()).also { it.requireValid() }
        }.getOrElse { RetainedMetadataIndex() }
    }

    private fun saveRetainedMetadataIndex(accountId: String, index: RetainedMetadataIndex) {
        index.requireValid()
        val encoded = rangeCacheJson.encodeToString(index).encodeToByteArray()
        require(encoded.size.toLong() <= MAX_RETAINED_METADATA_INDEX_BYTES) {
            "The retained virtual-folder metadata index is too large."
        }
        val directory = writableAccountDirectory(accountId)
        publishBytes(directory, RETAINED_METADATA_INDEX_FILE, encoded)
        val referenced = index.listings.mapTo(hashSetOf(), RetainedListingReference::blobName)
        directory.listFiles().orEmpty().asSequence()
            .filter { file -> file.isFile && file.extension == "listing" && file.name !in referenced }
            .take(MAX_VIRTUAL_FOLDER_RETAINED_LISTINGS)
            .forEach(File::delete)
    }

    private fun save(
        accountId: String,
        index: RangeCacheIndex,
        retention: VirtualFolderRetentionState? = null,
    ) {
        val directory = writableAccountDirectory(accountId)
        val bounded = boundedIndex(accountId, index, retention)
        publishRangeIndexes(directory, bounded)
        loadedIndexes[accountId] = bounded
        dirtyAccessTimeAccounts -= accountId
        val primaryReferenced = bounded.blocks.asSequence()
            .filter { it.storageTier == CachedRangeStorageTier.Primary }
            .mapTo(hashSetOf(), CachedRangeBlock::blobName)
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "block" && it.name !in primaryReferenced }
            .forEach(File::delete)
        overflowAccountDirectory(accountId, writable = false)?.let { overflowDirectory ->
            val overflowReferenced = bounded.blocks.asSequence()
                .filter { it.storageTier == CachedRangeStorageTier.Overflow }
                .mapTo(hashSetOf(), CachedRangeBlock::blobName)
            overflowDirectory.listFiles().orEmpty()
                .filter { it.isFile && it.extension == "block" && it.name !in overflowReferenced }
                .forEach(File::delete)
        }
    }

    private fun persistAccessTimes(accountId: String, index: RangeCacheIndex) {
        publishRangeIndexes(writableAccountDirectory(accountId), index)
    }

    private fun saveRetention(accountId: String, index: VirtualFolderRetentionIndex) {
        index.requireValid()
        val encoded = rangeCacheJson.encodeToString(index).encodeToByteArray()
        require(encoded.size.toLong() <= MAX_RETENTION_INDEX_BYTES) {
            "The desktop virtual folder retention index is too large."
        }
        val directory = writableAccountDirectory(accountId)
        publishBytes(directory, RETENTION_INDEX_FILE, encoded)
    }

    private fun requireIndexFits(accountId: String, index: RangeCacheIndex, required: CachedRangeBlock) {
        val bounded = boundedIndex(accountId, index)
        require(required in bounded.blocks) {
            "The retained folder is larger than the supported virtual-file cache index."
        }
        encodedIndex(bounded)
    }

    private fun boundedIndex(
        accountId: String,
        index: RangeCacheIndex,
        retention: VirtualFolderRetentionState? = null,
    ): RangeCacheIndex {
        val effectiveRetention = retention ?: loadFolderRetention(accountId)
        val (pending, published) = index.blocks.partition(CachedRangeBlock::pendingPublication)
        require(pending.size <= maximumBlocks) {
            "The pending retained-folder refresh exceeds the supported virtual-file cache index."
        }
        val (pinned, automatic) = published.partition { block ->
            effectiveRetention.retentionFor(block.path) == VirtualFolderRetention.KeepOnDevice
        }
        require(pinned.size <= maximumBlocks) {
            "The retained folders exceed the supported virtual-file cache index."
        }
        return index.copy(
            blocks = pending + pinned + automatic
                .sortedByDescending(CachedRangeBlock::lastAccessedAtEpochMillis)
                .take(maximumBlocks - pinned.size),
            folderRules = emptyList(),
        ).also { bounded -> bounded.requireValid() }
    }

    private fun encodedIndex(index: RangeCacheIndex): ByteArray =
        rangeCacheJson.encodeToString(index).encodeToByteArray().also { encoded ->
            val limit = if (index.blocks.any(CachedRangeBlock::pendingPublication)) {
                maximumSerializedIndexBytes()
            } else {
                maximumIndexBytes
            }
            require(encoded.size.toLong() <= limit) { "The desktop virtual range index is too large." }
        }

    private fun RangeCacheIndex.toLegacy(): LegacyRangeCacheIndex = LegacyRangeCacheIndex(
        blocks = blocks.filter { it.storageTier == CachedRangeStorageTier.Primary }.map { block ->
            LegacyCachedRangeBlock(
                path = block.path,
                remoteRevision = block.remoteRevision,
                fileSize = block.fileSize,
                offset = block.offset,
                length = block.length,
                blobName = block.blobName,
                sha256 = block.sha256,
                cachedAtEpochMillis = block.cachedAtEpochMillis,
                lastAccessedAtEpochMillis = block.lastAccessedAtEpochMillis,
                pendingPublication = block.pendingPublication,
            )
        },
        folderRules = folderRules,
    )

    private fun LegacyCachedRangeBlock.toTiered(): CachedRangeBlock = CachedRangeBlock(
        path = path,
        remoteRevision = remoteRevision,
        fileSize = fileSize,
        offset = offset,
        length = length,
        blobName = blobName,
        sha256 = sha256,
        cachedAtEpochMillis = cachedAtEpochMillis,
        lastAccessedAtEpochMillis = lastAccessedAtEpochMillis,
        pendingPublication = pendingPublication,
    )

    private fun CachedRangeBlock.identity(): String =
        "$path\u0000$remoteRevision\u0000$fileSize\u0000$offset"

    private fun publishRangeIndexes(directory: File, index: RangeCacheIndex) {
        val encodedTiered = encodedIndex(index)
        val legacy = index.toLegacy()
        val encodedLegacy = rangeCacheJson.encodeToString(legacy).encodeToByteArray()
        require(encodedLegacy.size.toLong() <= maximumSerializedIndexBytes()) {
            "The legacy desktop virtual range index is too large."
        }
        val previousTiered = snapshotPublishedFile(directory, TIERED_INDEX_FILE)
        val previousLegacy = snapshotPublishedFile(directory, LEGACY_INDEX_FILE)
        try {
            publishBytes(directory, TIERED_INDEX_FILE, encodedTiered)
            syncDirectoryMetadata(directory)
            beforeLegacyIndexPublication()
            publishBytes(directory, LEGACY_INDEX_FILE, encodedLegacy)
            syncDirectoryMetadata(directory)
        } catch (failure: Throwable) {
            val rollback = runCatching {
                restorePublishedFile(directory, TIERED_INDEX_FILE, previousTiered)
                restorePublishedFile(directory, LEGACY_INDEX_FILE, previousLegacy)
                syncDirectoryMetadata(directory)
            }
            if (rollback.isFailure) {
                tierAttention = "Cache metadata recovery needs attention. Newly written data was preserved."
                return
            }
            throw failure
        }
        afterDurableIndexPublication()
    }

    private fun snapshotPublishedFile(directory: File, name: String): ByteArray? {
        val file = File(directory, name)
        if (!Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(file.toPath())
        ) return null
        require(file.length() in 0L..maximumSerializedIndexBytes()) {
            "The existing desktop virtual range index is too large."
        }
        return file.readBytes()
    }

    private fun restorePublishedFile(directory: File, name: String, previous: ByteArray?) {
        if (previous == null) {
            Files.deleteIfExists(File(directory, name).toPath())
        } else {
            publishBytes(directory, name, previous)
        }
    }

    private fun maximumSerializedIndexBytes(): Long = maximumIndexBytes * 2L

    private fun removeRecord(accountId: String, index: RangeCacheIndex, record: CachedRangeBlock, blob: File) {
        if (record.storageTier == CachedRangeStorageTier.Overflow && !isOverflowAvailable()) return
        blob.delete()
        save(accountId, index.copy(blocks = index.blocks.filterNot { it == record }))
    }

    private fun removeExactRevision(revision: ActiveVirtualRangeRevision) {
        val current = load(revision.file.accountId)
        val removed = current.blocks.filter { block ->
            block.path == revision.file.relativePath &&
                block.remoteRevision == revision.remoteRevision &&
                block.fileSize == revision.fileSize
        }
        removed.forEach { block -> blobFile(revision.file.accountId, block).delete() }
        if (removed.isNotEmpty()) {
            save(
                revision.file.accountId,
                current.copy(blocks = current.blocks.filterNot { block -> block in removed }),
            )
        }
    }

    private fun CachedRangeBlock.isActiveOrDeferred(accountId: String): Boolean {
        val revision = activeRevision(accountId)
        return revision in deferredInvalidationRevisions ||
            activeRevisions.getOrDefault(revision, 0) > 0 ||
            activePaths.getOrDefault(revision.file, 0) > 0 &&
            activeRevisions.keys.none { active -> active.file == revision.file }
    }

    private fun CachedRangeBlock.activeRevision(accountId: String) = ActiveVirtualRangeRevision(
        FileOfflineKey(accountId, path),
        remoteRevision,
        fileSize,
    )

    private fun activeRevision(
        file: FileOfflineKey,
        remoteRevision: String?,
        fileSize: Long?,
    ): ActiveVirtualRangeRevision? {
        if (remoteRevision == null && fileSize == null) return null
        require(!remoteRevision.isNullOrBlank() && fileSize != null && fileSize > 0L)
        return ActiveVirtualRangeRevision(file, remoteRevision, fileSize)
    }

    internal inner class RevisionStaging internal constructor(
        private val accountId: String,
        private val path: String,
        private val remoteRevision: String,
        private val fileSize: Long,
        private val directory: File,
        private val storageTier: CachedRangeStorageTier,
        private val retention: VirtualFolderRetentionState?,
        private val preservePreviousRevisionUntilPublication: Boolean,
    ) : AutoCloseable {
        private val stageId = UUID.randomUUID().toString()
        private val leaseFile = File(directory, "range-revision.$stageId.lock")
        private val leaseChannel = RandomAccessFile(leaseFile, "rw").channel
        private val lease = leaseChannel.tryLock() ?: run {
            leaseChannel.close()
            leaseFile.delete()
            error("Could not lock the revision staging session.")
        }
        private val staged = linkedMapOf<Long, StagedRangeBlock>()
        private var closed = false

        @Synchronized
        fun store(offset: Long, bytes: ByteArray, nowEpochMillis: Long = System.currentTimeMillis()) {
            check(!closed)
            require(offset >= 0L && bytes.isNotEmpty() && bytes.size <= MAX_BLOCK_BYTES)
            require(offset + bytes.size <= fileSize)
            val identity = "$path\u0000$remoteRevision\u0000$fileSize\u0000$offset\u0000${bytes.size}"
            val record = CachedRangeBlock(
                path = path,
                remoteRevision = remoteRevision,
                fileSize = fileSize,
                offset = offset,
                length = bytes.size,
                blobName = "${sha256Hex(identity)}.block",
                sha256 = sha256Hex(bytes),
                cachedAtEpochMillis = nowEpochMillis,
                lastAccessedAtEpochMillis = nowEpochMillis,
                storageTier = storageTier,
            )
            val temporary = File.createTempFile("range-revision.$stageId.", ".stage", directory)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
            } catch (failure: Throwable) {
                temporary.delete()
                throw failure
            }
            staged.put(offset, StagedRangeBlock(record, temporary))?.temporary?.delete()
        }

        @Synchronized
        fun commitIfComplete(): Boolean {
            check(!closed)
            val ordered = staged.values.sortedBy { stagedBlock -> stagedBlock.record.offset }
            var expectedOffset = 0L
            ordered.forEach { stagedBlock ->
                if (stagedBlock.record.offset != expectedOffset) return false
                expectedOffset += stagedBlock.record.length
            }
            if (expectedOffset != fileSize) return false
            this@DesktopVirtualRangeCache.commitStagedRevision(
                accountId,
                path,
                stageId,
                ordered,
                retention,
                preservePreviousRevisionUntilPublication,
            )
            closed = true
            staged.clear()
            closeLease()
            return true
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            staged.values.forEach { stagedBlock -> stagedBlock.temporary.delete() }
            staged.clear()
            closeLease()
        }

        private fun closeLease() {
            runCatching(lease::release)
            runCatching(leaseChannel::close)
            if (!File(directory, "range-revision.$stageId.commit").exists()) leaseFile.delete()
        }
    }

    @Synchronized
    private fun commitStagedRevision(
        accountId: String,
        path: String,
        stageId: String,
        staged: List<StagedRangeBlock>,
        retention: VirtualFolderRetentionState?,
        preservePreviousRevisionUntilPublication: Boolean,
    ) {
        if (!preservePreviousRevisionUntilPublication) {
            check(activePaths.getOrDefault(FileOfflineKey(accountId, path), 0) <= 1) {
                "The previous retained revision is still open. The refresh will retry after it closes."
            }
        }
        val current = load(accountId)
        val records = staged.map { stagedBlock ->
            stagedBlock.record.copy(pendingPublication = preservePreviousRevisionUntilPublication)
        }
        val next = current.copy(
            blocks = current.blocks.filterNot { block ->
                if (preservePreviousRevisionUntilPublication) {
                    block.path == path && block.pendingPublication
                } else {
                    block.path == path
                }
            } + records,
        )
        val bounded = boundedIndex(accountId, next, retention)
        require(records.all { record -> record in bounded.blocks }) {
            "The retained folder is larger than the supported virtual-file cache index."
        }
        encodedIndex(bounded)
        val currentBlobs = current.blocks.mapTo(hashSetOf(), CachedRangeBlock::blobName)
        val moved = mutableListOf<File>()
        val stagingDirectory = staged.first().temporary.parentFile
        check(staged.all { stagedBlock -> stagedBlock.temporary.parentFile == stagingDirectory }) {
            "A retained revision cannot span multiple cache tiers."
        }
        val journal = File(stagingDirectory, "range-revision.$stageId.commit")
        publishBytes(
            journal.parentFile,
            journal.name,
            records.joinToString("\n", transform = CachedRangeBlock::blobName).encodeToByteArray(),
        )
        syncDirectoryMetadata(journal.parentFile)
        try {
            staged.forEach { stagedBlock ->
                val destination = blobFile(accountId, stagedBlock.record)
                try {
                    Files.move(
                        stagedBlock.temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        stagedBlock.temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                moved += destination
            }
            moved.map(File::getParentFile).distinctBy(File::getAbsolutePath).forEach(::syncDirectoryMetadata)
            save(accountId, next, retention)
            if (journal.delete()) syncDirectoryMetadata(journal.parentFile)
        } catch (failure: Throwable) {
            moved.filterNot { file -> file.name in currentBlobs }.forEach(File::delete)
            if (journal.delete()) runCatching { syncDirectoryMetadata(journal.parentFile) }
            throw failure
        } finally {
            staged.forEach { stagedBlock -> stagedBlock.temporary.delete() }
        }
    }

    private fun accountDirectory(accountId: String): File {
        require(accountId.length == 64 && accountId.all { it in '0'..'9' || it in 'a'..'f' })
        return File(root, accountId).also { directory ->
            require(!Files.isSymbolicLink(directory.toPath())) {
                "The desktop virtual range cache account directory cannot be a symbolic link."
            }
            if (directory.isDirectory && recoveredAccounts.add(accountId)) {
                recoverStaleRevisionStages(directory)
                recoverOrphanedCacheArtifacts(directory)
            }
        }
    }

    private fun blobFile(accountId: String, block: CachedRangeBlock): File = when (block.storageTier) {
        CachedRangeStorageTier.Primary -> File(accountDirectory(accountId), block.blobName)
        CachedRangeStorageTier.Overflow -> File(
            overflowAccountDirectory(accountId, writable = false) ?: File(requireNotNull(overflowRoot), accountId),
            block.blobName,
        )
    }

    private fun overflowAccountDirectory(accountId: String, writable: Boolean): File? {
        val configuredRoot = overflowRoot ?: return null
        if (!isOverflowRootAvailable(configuredRoot)) return null
        val directory = File(configuredRoot, accountId)
        require(!Files.isSymbolicLink(directory.toPath())) {
            "The overflow cache account directory cannot be a symbolic link."
        }
        if (writable && Files.notExists(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(directory.toPath())
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Another app process may have created the account directory.
            }
        }
        return directory.takeIf {
            Files.isDirectory(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(it.toPath())
        }
    }

    private fun isOverflowAvailable(): Boolean = overflowRoot?.let(::isOverflowRootAvailable) == true

    private fun promoteBlock(
        accountId: String,
        index: RangeCacheIndex,
        record: CachedRangeBlock,
        source: File,
    ): RangeCacheIndex = runCatching {
        moveBlocks(accountId, index, listOf(record), CachedRangeStorageTier.Primary).also {
            source.delete()
        }
    }.getOrDefault(index)

    private fun moveBlocks(
        accountId: String,
        index: RangeCacheIndex,
        blocks: List<CachedRangeBlock>,
        destinationTier: CachedRangeStorageTier,
    ): RangeCacheIndex {
        if (blocks.isEmpty() || blocks.all { it.storageTier == destinationTier }) return index
        val destinationDirectory = when (destinationTier) {
            CachedRangeStorageTier.Primary -> writableAccountDirectory(accountId)
            CachedRangeStorageTier.Overflow -> overflowAccountDirectory(accountId, writable = true)
                ?: error("Reconnect the overflow cache drive before moving cached files.")
        }
        val published = mutableListOf<File>()
        try {
            blocks.forEach { block ->
                val source = blobFile(accountId, block)
                check(source.isFile && source.length() == block.length.toLong()) {
                    "A cached file block disappeared while moving storage tiers."
                }
                val destination = File(destinationDirectory, block.blobName)
                publishFile(source, destination, block.sha256)
                published += destination
            }
            val movedBlobNames = blocks.mapTo(hashSetOf(), CachedRangeBlock::blobName)
            val next = index.copy(
                blocks = index.blocks.map { block ->
                    if (block.blobName in movedBlobNames) block.copy(storageTier = destinationTier) else block
                },
            )
            save(accountId, next)
            blocks.forEach { block -> blobFile(accountId, block).delete() }
            tierAttention = null
            return next
        } catch (failure: Throwable) {
            published.forEach(File::delete)
            tierAttention = failure.message
                ?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
                ?.take(512)
                ?: "Could not move cached content between storage tiers."
            throw failure
        }
    }

    private fun publishFile(source: File, destination: File, expectedSha256: String) {
        val temporary = File.createTempFile("${destination.name}.", ".tmp", destination.parentFile)
        try {
            source.inputStream().buffered().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(temporary.length() == source.length() && sha256Hex(temporary) == expectedSha256) {
                "A cached file block failed verification while moving storage tiers."
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            syncDirectoryMetadata(destination.parentFile)
        } finally {
            temporary.delete()
        }
    }

    private fun prepareCacheRoot(cacheRootFile: File, createParentDirectories: Boolean, required: Boolean) {
        val cacheRoot = cacheRootFile.toPath().toAbsolutePath().normalize()
        val parent = requireNotNull(cacheRoot.parent)
        if (!required && Files.notExists(cacheRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (createParentDirectories) {
            Files.createDirectories(parent)
        } else if (!Files.isDirectory(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            require(!required) { "The selected virtual-file storage drive is unavailable." }
            return
        }
        if (Files.notExists(cacheRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(cacheRoot)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Another app process may have created the same cache root.
            }
        }
        require(
            Files.isDirectory(cacheRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(cacheRoot),
        ) { "The desktop virtual range cache must be a regular local directory." }
    }

    private fun isCacheRootDirectory(cacheRootFile: File): Boolean {
        val path = cacheRootFile.toPath().toAbsolutePath().normalize()
        return Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
    }

    private fun isOverflowRootAvailable(cacheRootFile: File): Boolean {
        if (!isCacheRootDirectory(cacheRootFile)) return false
        val expected = expectedOverflowRootIdentity ?: adoptOverflowRootIdentity(cacheRootFile)?.also { adopted ->
            expectedOverflowRootIdentity = adopted
        } ?: return false
        return readOverflowRootIdentity(cacheRootFile) == expected
    }

    private fun isPrimaryRootAvailable(cacheRootFile: File): Boolean {
        if (!isCacheRootDirectory(cacheRootFile)) return false
        val expected = expectedPrimaryRootIdentity ?: return false
        return readPrimaryRootIdentity(cacheRootFile) == expected
    }

    private fun writableAccountDirectory(accountId: String): File {
        requireAvailable()
        val directory = accountDirectory(accountId)
        if (Files.notExists(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(directory.toPath())
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Another app process may have created this account directory concurrently.
            }
        }
        check(
            Files.isDirectory(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(directory.toPath()),
        ) { "Could not create the desktop virtual range cache." }
        return directory
    }

    private fun recoverStaleRevisionStages(directory: File) {
        val referencedBlocks = loadRangeIndexFromDirectory(directory)
            ?.blocks
            ?.mapTo(hashSetOf(), CachedRangeBlock::blobName)
            .orEmpty()
        Files.newDirectoryStream(directory.toPath(), "range-revision.*.commit").use { journals ->
            journals.forEach { journal ->
                if (!Files.isRegularFile(journal, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return@forEach
                val stageId = REVISION_COMMIT_FILE.matchEntire(journal.fileName.toString())?.groupValues?.get(1)
                    ?: return@forEach
                val leaseFile = directory.toPath().resolve("range-revision.$stageId.lock")
                if (!Files.isRegularFile(leaseFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    recoverPromotedRevision(directory, stageId, referencedBlocks)
                }
            }
        }
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            entries.forEach { path ->
                if (
                    !Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                    !path.fileName.toString().startsWith("range-revision.") ||
                    !path.fileName.toString().endsWith(".stage")
                ) return@forEach
                val stageId = REVISION_STAGE_FILE.matchEntire(path.fileName.toString())?.groupValues?.get(1)
                if (stageId == null) {
                    Files.deleteIfExists(path)
                    return@forEach
                }
                val leaseFile = directory.toPath().resolve("range-revision.$stageId.lock")
                if (!Files.isRegularFile(leaseFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(path)
                }
            }
        }
        Files.newDirectoryStream(directory.toPath(), "range-revision.*.lock").use { leases ->
            leases.forEach { leaseFile ->
                if (!Files.isRegularFile(leaseFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return@forEach
                val stageId = REVISION_LEASE_FILE.matchEntire(leaseFile.fileName.toString())?.groupValues?.get(1)
                    ?: return@forEach
                runCatching {
                    var acquired = false
                    RandomAccessFile(leaseFile.toFile(), "rw").channel.use { channel ->
                        val lock = try {
                            channel.tryLock()
                        } catch (_: java.nio.channels.OverlappingFileLockException) {
                            null
                        } ?: return@use
                        lock.use {
                            recoverPromotedRevision(directory, stageId, referencedBlocks)
                            Files.newDirectoryStream(
                                directory.toPath(),
                                "range-revision.$stageId.*.stage",
                            ).use { stages ->
                                stages.forEach { stage ->
                                    if (Files.isRegularFile(stage, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                                        Files.deleteIfExists(stage)
                                    }
                                }
                            }
                            acquired = true
                        }
                    }
                    if (acquired) Files.deleteIfExists(leaseFile)
                }
            }
        }
    }

    private fun loadRangeIndexFromDirectory(directory: File): RangeCacheIndex? {
        val tiered = File(directory, TIERED_INDEX_FILE).takeIf {
            it.isFile && it.length() in 1L..maximumSerializedIndexBytes()
        }?.let { file ->
            runCatching {
                rangeCacheJson.decodeFromString<RangeCacheIndex>(file.readText()).also { index -> index.requireValid() }
            }.getOrNull()
        }
        val legacy = File(directory, LEGACY_INDEX_FILE).takeIf {
            it.isFile && it.length() in 1L..maximumSerializedIndexBytes()
        }?.let { file ->
            runCatching {
                rangeCacheJson.decodeFromString<LegacyRangeCacheIndex>(file.readText()).also { index ->
                    index.requireValid()
                }
            }.getOrNull()
        }
        if (tiered == null) {
            return legacy?.let { index ->
                RangeCacheIndex(
                    blocks = index.blocks.map { block -> block.toTiered() },
                    folderRules = index.folderRules,
                ).also { migrated -> migrated.requireValid() }
            }
        }
        if (legacy == null) return tiered
        val legacyBlocks = legacy.blocks.map { block -> block.toTiered() }
        val legacyIdentities = legacyBlocks.mapTo(hashSetOf()) { block -> block.identity() }
        val merged = tiered.copy(
            blocks = tiered.blocks.filterNot { block -> block.identity() in legacyIdentities } + legacyBlocks,
            folderRules = if (tiered.folderRules.isEmpty()) legacy.folderRules else tiered.folderRules,
        )
        return runCatching { merged.also { index -> index.requireValid() } }
            .getOrElse {
                RangeCacheIndex(
                    blocks = legacyBlocks,
                    folderRules = legacy.folderRules,
                ).also { fallback -> fallback.requireValid() }
            }
    }

    private fun loadRetainedMetadataIndexFromDirectory(directory: File): RetainedMetadataIndex? {
        val file = File(directory, RETAINED_METADATA_INDEX_FILE)
        if (!file.isFile || file.length() !in 1L..MAX_RETAINED_METADATA_INDEX_BYTES) return null
        return runCatching {
            rangeCacheJson.decodeFromString<RetainedMetadataIndex>(file.readText()).also { index ->
                index.requireValid()
            }
        }.getOrNull()
    }

    private fun recoverOrphanedCacheArtifacts(directory: File) {
        val referencedBlocks = loadRangeIndexFromDirectory(directory)
            ?.blocks
            ?.mapTo(hashSetOf(), CachedRangeBlock::blobName)
            .orEmpty()
            .toMutableSet()
        directory.listFiles().orEmpty().asSequence()
            .filter { file -> file.isFile && REVISION_COMMIT_FILE.matches(file.name) }
            .filter { file -> file.length() in 1L..MAX_COMMIT_JOURNAL_BYTES }
            .mapNotNull { journal ->
                runCatching {
                    journal.readLines().also { names ->
                        require(names.isNotEmpty() && names.size <= maximumBlocks && names.distinct().size == names.size)
                        require(names.all(BLOCK_FILE::matches))
                    }
                }.getOrNull()
            }
            .flatten()
            .forEach(referencedBlocks::add)
        val referencedListings = loadRetainedMetadataIndexFromDirectory(directory)
            ?.listings
            ?.mapTo(hashSetOf(), RetainedListingReference::blobName)
            .orEmpty()
        directory.listFiles().orEmpty().forEach { artifact ->
            if (!artifact.isFile) return@forEach
            val orphaned = when {
                BLOCK_FILE.matches(artifact.name) -> artifact.name !in referencedBlocks
                LISTING_FILE.matches(artifact.name) -> artifact.name !in referencedListings
                PUBLISHED_TEMP_FILE.matches(artifact.name) -> true
                else -> false
            }
            if (orphaned) artifact.delete()
        }
    }

    private fun recoverOverflowCacheArtifacts(accountId: String) {
        if (accountId in recoveredOverflowAccounts) return
        if (!isOverflowAvailable()) return
        recoveredOverflowAccounts += accountId
        val directory = overflowAccountDirectory(accountId, writable = false) ?: return
        recoverStaleRevisionStages(directory)
    }

    private fun recoverPromotedRevision(directory: File, stageId: String, referencedBlocks: Set<String>) {
        val journal = File(directory, "range-revision.$stageId.commit")
        if (!journal.isFile || journal.length() !in 1L..MAX_COMMIT_JOURNAL_BYTES) {
            journal.delete()
            return
        }
        val promoted = runCatching {
            journal.readLines().also { names ->
                require(names.isNotEmpty() && names.size <= maximumBlocks && names.distinct().size == names.size)
                require(names.all { name -> BLOCK_FILE.matches(name) })
            }
        }.getOrNull()
        promoted.orEmpty()
            .filterNot(referencedBlocks::contains)
            .forEach { name -> File(directory, name).delete() }
        journal.delete()
    }

    private fun publishBytes(directory: File, name: String, bytes: ByteArray) {
        val temporary = File.createTempFile("$name.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    File(directory, name).toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), File(directory, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun syncDirectoryMetadata(directory: File) {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
        afterDirectoryMetadataSync(directory)
    }

    private fun RangeCacheIndex.requireValid() {
        val (pending, published) = blocks.partition(CachedRangeBlock::pendingPublication)
        require(version == 2 && pending.size <= maximumBlocks && published.size <= maximumBlocks)
        require(
            blocks.map { block ->
                "${block.path}\u0000${block.remoteRevision}\u0000${block.fileSize}\u0000${block.offset}"
            }.distinct().size == blocks.size,
        )
        blocks.forEach(CachedRangeBlock::requireValid)
        folderRules.toDomain()
    }

    private fun LegacyRangeCacheIndex.requireValid() {
        require(version == 1 && blocks.size <= maximumBlocks * 2)
        require(blocks.map { block -> block.toTiered().identity() }.distinct().size == blocks.size)
        blocks.forEach { block -> block.toTiered().requireValid() }
        folderRules.toDomain()
    }

    private fun VirtualFolderRetentionIndex.requireValid() {
        require(version == 1)
        val retention = rules.toDomain()
        require(hydration.map(CachedVirtualFolderHydration::relativePath).distinct().size == hydration.size)
        hydration.forEach { cached ->
            val status = cached.toDomain()
            require(
                retention.rules.any { rule ->
                    rule.relativePath == status.relativePath && rule.retention == VirtualFolderRetention.KeepOnDevice
                },
            )
        }
    }

    companion object {
        internal fun adoptPrimaryRootIdentity(root: File): String? {
            readPrimaryRootIdentity(root)?.let { return it }
            if (!isRecognizablePrimaryRoot(root)) return null
            return initializePrimaryRootIdentity(root)
        }

        internal fun initializePrimaryRootIdentity(root: File): String {
            require(
                Files.isDirectory(root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(root.toPath()),
            ) { "Reconnect the selected primary cache drive before using it." }
            readPrimaryRootIdentity(root)?.let { return it }
            val identity = UUID.randomUUID().toString()
            publishMarker(root, PRIMARY_IDENTITY_MARKER_FILE, primaryIdentityMarkerContent(identity))
            return identity
        }

        internal fun adoptOverflowRootIdentity(root: File): String? {
            readOverflowRootIdentity(root)?.let { return it }
            if (!isLegacyOverflowRoot(root)) return null
            return initializeOverflowRootIdentity(root)
        }

        internal fun initializeOverflowRootIdentity(root: File): String {
            require(
                Files.isDirectory(root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(root.toPath()),
            ) { "Reconnect the selected overflow cache drive before using it." }
            readOverflowRootIdentity(root)?.let { return it }
            val identity = UUID.randomUUID().toString()
            publishMarker(root, OVERFLOW_IDENTITY_MARKER_FILE, overflowIdentityMarkerContent(identity))
            if (!isLegacyOverflowRoot(root)) {
                publishMarker(root, OVERFLOW_MARKER_FILE, OVERFLOW_MARKER_CONTENT)
            }
            return identity
        }

        private fun readOverflowRootIdentity(root: File): String? {
            val marker = File(root, OVERFLOW_IDENTITY_MARKER_FILE)
            if (
                !Files.isRegularFile(marker.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(marker.toPath()) ||
                marker.length() !in 1L..256L
            ) return null
            val content = runCatching { marker.readText() }.getOrNull() ?: return null
            val lines = content.lines()
            if (lines.size != 3 || lines[0] != OVERFLOW_IDENTITY_MARKER_HEADER || lines[2].isNotEmpty()) return null
            return lines[1].takeIf(String::isValidDesktopVirtualCacheRootIdentity)
        }

        private fun readPrimaryRootIdentity(root: File): String? {
            val marker = File(root, PRIMARY_IDENTITY_MARKER_FILE)
            if (
                !Files.isRegularFile(marker.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(marker.toPath()) ||
                marker.length() !in 1L..256L
            ) return null
            val content = runCatching { marker.readText() }.getOrNull() ?: return null
            val lines = content.lines()
            if (lines.size != 3 || lines[0] != PRIMARY_IDENTITY_MARKER_HEADER || lines[2].isNotEmpty()) return null
            return lines[1].takeIf(String::isValidDesktopVirtualCacheRootIdentity)
        }

        private fun isRecognizablePrimaryRoot(root: File): Boolean {
            if (!Files.isDirectory(root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(root.toPath())
            ) return false
            return root.listFiles().orEmpty().any { child ->
                child.isDirectory && !Files.isSymbolicLink(child.toPath()) &&
                    child.name.length == 64 && child.name.all { character ->
                        character in '0'..'9' || character in 'a'..'f'
                    }
            }
        }

        private fun isLegacyOverflowRoot(root: File): Boolean {
            val marker = File(root, OVERFLOW_MARKER_FILE)
            return Files.isRegularFile(marker.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(marker.toPath()) &&
                marker.length() == OVERFLOW_MARKER_CONTENT.size.toLong() &&
                runCatching { marker.readBytes().contentEquals(OVERFLOW_MARKER_CONTENT) }.getOrDefault(false)
        }

        private fun overflowIdentityMarkerContent(identity: String): ByteArray =
            "$OVERFLOW_IDENTITY_MARKER_HEADER\n$identity\n".encodeToByteArray()

        private fun primaryIdentityMarkerContent(identity: String): ByteArray =
            "$PRIMARY_IDENTITY_MARKER_HEADER\n$identity\n".encodeToByteArray()

        private fun publishMarker(root: File, name: String, content: ByteArray) {
            val temporary = File.createTempFile("$name.", ".tmp", root)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(content)
                    output.fd.sync()
                }
                try {
                    Files.move(
                        temporary.toPath(),
                        File(root, name).toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), File(root, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    FileChannel.open(root.toPath(), StandardOpenOption.READ).use { channel -> channel.force(true) }
                }
            } finally {
                temporary.delete()
            }
        }

        const val LEGACY_INDEX_FILE = "range-index-v1.json"
        const val TIERED_INDEX_FILE = "range-index-v2.json"
        const val RETENTION_INDEX_FILE = "folder-retention-v1.json"
        const val RETAINED_METADATA_INDEX_FILE = "retained-metadata-v1.json"
        const val MAX_INDEX_BYTES = 16L * 1024L * 1024L
        const val MAX_RETENTION_INDEX_BYTES = 1024L * 1024L
        const val MAX_RETAINED_METADATA_INDEX_BYTES = 16L * 1024L * 1024L
        const val MAX_RETAINED_LISTING_BYTES = 16L * 1024L * 1024L
        const val MAX_COMMIT_JOURNAL_BYTES = 2L * 1024L * 1024L
        const val MAX_BLOCKS = 20_000
        const val MAX_BLOCK_BYTES = 4 * 1024 * 1024
        const val ACCESS_TIME_PERSISTENCE_INTERVAL_MILLIS = 30_000L
        const val PRIMARY_IDENTITY_MARKER_FILE = ".nextcloud-native-primary-v1"
        const val PRIMARY_IDENTITY_MARKER_HEADER = "nextcloud-native-primary-v1"
        const val OVERFLOW_MARKER_FILE = ".nextcloud-native-overflow-v1"
        const val OVERFLOW_IDENTITY_MARKER_FILE = ".nextcloud-native-overflow-v2"
        const val OVERFLOW_IDENTITY_MARKER_HEADER = "nextcloud-native-overflow-v2"
        val OVERFLOW_MARKER_CONTENT = "nextcloud-native-overflow-v1\n".encodeToByteArray()
        val PROJECTED_BLOCK_HASH = "0".repeat(64)
        val REVISION_STAGE_FILE = Regex(
            "range-revision\\.([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\..+\\.stage",
        )
        val REVISION_LEASE_FILE = Regex(
            "range-revision\\.([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.lock",
        )
        val REVISION_COMMIT_FILE = Regex(
            "range-revision\\.([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.commit",
        )
        val BLOCK_FILE = Regex("[0-9a-f]{64}\\.block")
        val LISTING_FILE = Regex("[0-9a-f]{64}\\.listing")
        val PUBLISHED_TEMP_FILE = Regex(
            "(?:[0-9a-f]{64}\\.(?:block|listing)|range-index-v[12]\\.json|folder-retention-v1\\.json|" +
                "retained-metadata-v1\\.json)\\.[^.]+\\.tmp",
        )
    }
}

internal fun defaultDesktopVirtualRangeCache(
    policy: () -> VirtualFileCachePolicy,
): DesktopVirtualRangeCache {
    val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
    val cacheRoot = xdgCache?.let(::File) ?: File(System.getProperty("user.home"), ".cache")
    return DesktopVirtualRangeCache(
        root = File(cacheRoot, "nextcloud-native/virtual-ranges"),
        policy = policy,
    )
}

@Serializable
private data class RangeCacheIndex(
    val version: Int = 2,
    val blocks: List<CachedRangeBlock> = emptyList(),
    val folderRules: List<CachedVirtualFolderRule> = emptyList(),
)

@Serializable
private data class LegacyRangeCacheIndex(
    val version: Int = 1,
    val blocks: List<LegacyCachedRangeBlock> = emptyList(),
    val folderRules: List<CachedVirtualFolderRule> = emptyList(),
)

@Serializable
private data class LegacyCachedRangeBlock(
    val path: String,
    val remoteRevision: String,
    val fileSize: Long,
    val offset: Long,
    val length: Int,
    val blobName: String,
    val sha256: String,
    val cachedAtEpochMillis: Long,
    val lastAccessedAtEpochMillis: Long,
    val pendingPublication: Boolean = false,
)

@Serializable
private data class CachedVirtualFolderRule(
    val relativePath: String,
    val retention: VirtualFolderRetention,
)

@Serializable
private data class VirtualFolderRetentionIndex(
    val version: Int = 1,
    val rules: List<CachedVirtualFolderRule> = emptyList(),
    val hydration: List<CachedVirtualFolderHydration> = emptyList(),
)

@Serializable
private data class RetainedMetadataIndex(
    val version: Int = 1,
    val listings: List<RetainedListingReference> = emptyList(),
) {
    fun requireValid() {
        require(version == 1 && listings.size <= 20_000)
        require(listings.map(RetainedListingReference::path).distinct().size == listings.size)
        listings.forEach(RetainedListingReference::requireValid)
    }
}

@Serializable
private data class RetainedListingReference(
    val path: String,
    val blobName: String,
    val sha256: String,
) {
    fun requireValid() {
        if (path.isNotEmpty()) FileOfflineKey("account", path)
        require(blobName.length == 72 && blobName.endsWith(".listing"))
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' })
        require(blobName == "$sha256.listing")
    }
}

@Serializable
private data class RetainedDirectoryListing(
    val version: Int = 1,
    val path: String,
    val fetchedAtEpochMillis: Long,
    val nodes: List<RetainedVirtualFileNode>,
    val complete: Boolean? = null,
) {
    fun requireValid(expectedPath: String) {
        require(version == 1 && path == expectedPath && fetchedAtEpochMillis >= 0L)
        if (path.isNotEmpty()) FileOfflineKey("account", path)
        require(nodes.size <= 50_000)
        require(nodes.map(RetainedVirtualFileNode::path).distinct().size == nodes.size)
        nodes.forEach(RetainedVirtualFileNode::requireValid)
    }

    fun toSnapshot(completeWhenUnspecified: Boolean) = LinuxVirtualDirectorySnapshot(
        nodes = nodes.map(RetainedVirtualFileNode::toDomain),
        fetchedAtEpochMillis = fetchedAtEpochMillis,
        complete = complete ?: completeWhenUnspecified,
    )

    companion object {
        fun fromSnapshot(path: String, snapshot: LinuxVirtualDirectorySnapshot) = RetainedDirectoryListing(
            path = path,
            fetchedAtEpochMillis = snapshot.fetchedAtEpochMillis,
            nodes = snapshot.nodes.map(RetainedVirtualFileNode.Companion::fromDomain),
            complete = snapshot.complete,
        ).also { it.requireValid(path) }
    }
}

@Serializable
private data class RetainedVirtualFileNode(
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val remoteRevision: String,
) {
    fun requireValid() {
        FileOfflineKey("account", path)
        require(name.isNotBlank() && name.none(Char::isISOControl))
        require(size >= 0L)
        require(remoteRevision.isNotBlank() && remoteRevision.none(Char::isISOControl))
    }

    fun toDomain() = LinuxVirtualFileNode(path, name, directory, size, remoteRevision)

    companion object {
        fun fromDomain(node: LinuxVirtualFileNode) = RetainedVirtualFileNode(
            node.path,
            node.name,
            node.directory,
            node.size,
            node.remoteRevision,
        )
    }
}

@Serializable
private data class CachedVirtualFolderHydration(
    val relativePath: String,
    val phase: VirtualFolderHydrationPhase,
    val detail: String? = null,
    val refreshFailure: String? = null,
    val refreshing: Boolean = false,
    val verifiedAtEpochMillis: Long? = null,
    val refreshRetryAtEpochMillis: Long? = null,
) {
    fun toDomain(): VirtualFolderHydrationStatus =
        VirtualFolderHydrationStatus(
            relativePath,
            phase,
            detail,
            refreshFailure,
            refreshing,
            verifiedAtEpochMillis,
            refreshRetryAtEpochMillis,
        )

    companion object {
        fun fromDomain(status: VirtualFolderHydrationStatus) = CachedVirtualFolderHydration(
            status.relativePath,
            status.phase,
            status.detail,
            status.refreshFailure,
            status.refreshing,
            status.verifiedAtEpochMillis,
            status.refreshRetryAtEpochMillis,
        )
    }
}

private fun List<CachedVirtualFolderRule>.toDomain(): VirtualFolderRetentionState =
    VirtualFolderRetentionState(map { rule -> VirtualFolderRetentionRule(rule.relativePath, rule.retention) })

@Serializable
internal enum class CachedRangeStorageTier {
    Primary,
    Overflow,
}

@Serializable
private data class CachedRangeBlock(
    val path: String,
    val remoteRevision: String,
    val fileSize: Long,
    val offset: Long,
    val length: Int,
    val blobName: String,
    val sha256: String,
    val cachedAtEpochMillis: Long,
    val lastAccessedAtEpochMillis: Long,
    val pendingPublication: Boolean = false,
    val storageTier: CachedRangeStorageTier = CachedRangeStorageTier.Primary,
) {
    fun requireValid() {
        FileOfflineKey("account", path)
        require(remoteRevision.isNotBlank())
        require(fileSize > 0L && offset >= 0L && length in 1..4 * 1024 * 1024)
        require(offset + length <= fileSize)
        require(blobName.length == 70 && blobName.endsWith(".block"))
        require(sha256.length == 64)
        require(cachedAtEpochMillis >= 0L && lastAccessedAtEpochMillis >= cachedAtEpochMillis)
    }
}

private data class StagedRangeBlock(
    val record: CachedRangeBlock,
    val temporary: File,
)

private fun sha256Hex(value: String): String = sha256Hex(value.encodeToByteArray())

private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private val rangeCacheJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = false
}
