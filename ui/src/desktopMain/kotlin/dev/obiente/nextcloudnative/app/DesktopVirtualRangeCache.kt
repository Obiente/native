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

internal data class DesktopVirtualRangeCacheSummary(
    val cachedBytes: Long,
    val reclaimableBytes: Long,
    val pinnedBytes: Long,
    val fileCount: Int,
    val pinnedFileCount: Int,
    val availableFreeBytes: Long,
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

/** Persistent exact-revision block cache used by the Linux virtual filesystem. */
internal class DesktopVirtualRangeCache(
    private val root: File,
    private val maximumIndexBytes: Long = MAX_INDEX_BYTES,
    private val maximumBlocks: Int = MAX_BLOCKS,
    private val createParentDirectories: Boolean = true,
    private val policy: () -> VirtualFileCachePolicy,
) {
    private val activePaths = mutableMapOf<FileOfflineKey, Int>()
    private val deferredInvalidationPaths = mutableSetOf<FileOfflineKey>()
    private val recoveredAccounts = mutableSetOf<String>()

    init {
        require(maximumIndexBytes in 1L..MAX_INDEX_BYTES)
        require(maximumBlocks in 1..MAX_BLOCKS)
        val cacheRoot = root.toPath().toAbsolutePath().normalize()
        val parent = requireNotNull(cacheRoot.parent)
        if (createParentDirectories) {
            Files.createDirectories(parent)
        } else {
            require(
                Files.isDirectory(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(parent),
            ) { "The selected virtual-file storage drive is unavailable." }
        }
        if (Files.notExists(cacheRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(cacheRoot)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Another app process may have created the same account cache concurrently.
            }
        }
        require(
            Files.isDirectory(cacheRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(cacheRoot),
        ) { "The desktop virtual range cache must be a regular local directory." }
    }

    @Synchronized
    fun acquire(accountId: String, path: String) {
        val key = FileOfflineKey(accountId, path)
        activePaths[key] = activePaths.getOrDefault(key, 0) + 1
    }

    @Synchronized
    fun release(accountId: String, path: String) {
        val key = FileOfflineKey(accountId, path)
        val remaining = activePaths.getOrDefault(key, 1) - 1
        if (remaining <= 0) {
            activePaths.remove(key)
            if (deferredInvalidationPaths.remove(key)) removeExactPath(accountId, key.relativePath)
        } else {
            activePaths[key] = remaining
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
        val blob = File(accountDirectory(accountId), record.blobName)
        if (!blob.isFile || blob.length() != length.toLong()) {
            removeRecord(accountId, index, record, blob)
            return null
        }
        val bytes = blob.readBytes()
        if (sha256Hex(bytes) != record.sha256) {
            removeRecord(accountId, index, record, blob)
            return null
        }
        save(
            accountId,
            index.copy(
                blocks = index.blocks.map { current ->
                    if (current == record) current.copy(lastAccessedAtEpochMillis = nowEpochMillis) else current
                },
            ),
        )
        return bytes
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
            if (activePaths.getOrDefault(key, 0) > 0) {
                deferredInvalidationPaths += key
                false
            } else {
                true
            }
        }
        removed.forEach { block -> File(accountDirectory(accountId), block.blobName).delete() }
        if (removed.isNotEmpty()) save(accountId, current.copy(blocks = current.blocks.filterNot { it in removed }))
    }

    @Synchronized
    fun loadFolderRetention(accountId: String): VirtualFolderRetentionState =
        loadRetention(accountId).rules.toDomain()

    @Synchronized
    fun loadFolderHydrationStatuses(accountId: String): List<VirtualFolderHydrationStatus> =
        loadRetention(accountId).hydration.map(CachedVirtualFolderHydration::toDomain)

    @Synchronized
    fun loadValidatedFolderHydrationStatus(accountId: String, path: String): VirtualFolderHydrationStatus? {
        val normalized = FileOfflineKey(accountId, path).relativePath
        val current = loadFolderHydrationStatuses(accountId)
            .firstOrNull { status -> status.relativePath == normalized }
            ?: return null
        if (
            current.phase != VirtualFolderHydrationPhase.AvailableOffline ||
            hasIndexedRetainedFolderCoverage(accountId, normalized)
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
            var expectedOffset = 0L
            val valid = records.isNotEmpty() && records.all { block ->
                if (block.offset != expectedOffset) return@all false
                val blob = File(accountDirectory(accountId), block.blobName)
                if (
                    !blob.isFile ||
                    blob.length() != block.length.toLong() ||
                    runCatching { sha256Hex(blob) }.getOrNull() != block.sha256
                ) {
                    return@all false
                }
                expectedOffset += block.length
                true
            } && expectedOffset == revision.fileSize
            if (valid) {
                complete += revision
            } else {
                invalidRecords += records
            }
        }
        if (invalidRecords.isNotEmpty()) {
            invalidRecords.forEach { block -> File(accountDirectory(accountId), block.blobName).delete() }
            save(accountId, index.copy(blocks = index.blocks.filterNot(invalidRecords::contains)))
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
                !Files.isSymbolicLink(cacheRoot),
        ) { "Reconnect the selected virtual-file storage drive before changing its location." }
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
    ): RevisionStaging {
        require(remoteRevision.isNotBlank() && fileSize > 0L)
        val normalized = FileOfflineKey(accountId, path).relativePath
        val directory = writableAccountDirectory(accountId)
        return RevisionStaging(accountId, normalized, remoteRevision, fileSize, directory)
    }

    @Synchronized
    fun requireRevisionCapacity(accountId: String, path: String, fileSize: Long, blockBytes: Int) {
        require(fileSize > 0L)
        require(blockBytes in 1..MAX_BLOCK_BYTES)
        val normalized = FileOfflineKey(accountId, path).relativePath
        val requiredBlocks = (fileSize - 1L) / blockBytes.toLong() + 1L
        val retention = loadFolderRetention(accountId)
        val pinnedOtherBlocks = load(accountId).blocks.count { block ->
            block.path != normalized &&
                retention.retentionFor(block.path) == VirtualFolderRetention.KeepOnDevice
        }
        require(requiredBlocks <= (maximumBlocks - pinnedOtherBlocks).toLong()) {
            "The retained folders exceed the supported virtual-file cache index."
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
        if (retention == VirtualFolderRetention.KeepOnDevice) {
            val normalized = FileOfflineKey(accountId, path).relativePath
            deferredInvalidationPaths.removeIf { key ->
                key.accountId == accountId &&
                    (key.relativePath == normalized || key.relativePath.startsWith("$normalized/"))
            }
        }
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
        val current = loadRetention(accountId)
        val roots = current.rules.asSequence()
            .filter { rule ->
                rule.retention == VirtualFolderRetention.KeepOnDevice &&
                    (
                        normalized == rule.relativePath ||
                            normalized.startsWith("${rule.relativePath}/") ||
                            rule.relativePath.startsWith("$normalized/")
                        )
            }
            .map(CachedVirtualFolderRule::relativePath)
            .distinct()
            .toList()
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

    private fun hasIndexedRetainedFolderCoverage(accountId: String, normalized: String): Boolean {
        val revisions = retainedFolderRevisions(accountId, normalized) ?: return false
        if (revisions.isEmpty()) return true
        val directory = accountDirectory(accountId)
        val index = loadRangeIndexFromDirectory(directory) ?: return false
        val blocksByRevision = index.blocks.groupBy { block ->
            VirtualRangeRevision(block.path, block.remoteRevision, block.fileSize)
        }
        return revisions.distinct().all { revision ->
            var expectedOffset = 0L
            val complete = blocksByRevision[revision].orEmpty().sortedBy(CachedRangeBlock::offset).all { block ->
                if (block.offset != expectedOffset) return@all false
                val blob = File(directory, block.blobName)
                if (!blob.isFile || blob.length() != block.length.toLong()) return@all false
                expectedOffset += block.length
                true
            }
            complete && expectedOffset == revision.fileSize
        }
    }

    private fun retainedFolderRevisions(accountId: String, normalized: String): List<VirtualRangeRevision>? {
        val retention = loadFolderRetention(accountId)
        if (retention.retentionFor(normalized) != VirtualFolderRetention.KeepOnDevice) return null
        val metadataIndex = loadRetainedMetadataIndex(accountId)
        if (retainedFolderAncestorListings(normalized).any { ancestor ->
                loadRetainedListing(accountId, ancestor, metadataIndex) == null
            }
        ) return null
        val pending = ArrayDeque<String>().apply { add(normalized) }
        val visited = hashSetOf<String>()
        val revisions = mutableListOf<VirtualRangeRevision>()
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            if (!visited.add(directory)) continue
            if (visited.size > MAX_RETAINED_LISTINGS) return null
            val listing = loadRetainedListing(accountId, directory, metadataIndex) ?: return null
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
        val covered = loadFolderRetention(accountId).rules.any { rule ->
            rule.retention == VirtualFolderRetention.KeepOnDevice &&
                (
                    normalized.isEmpty() ||
                        normalized == rule.relativePath ||
                        normalized.startsWith("${rule.relativePath}/") ||
                        rule.relativePath.startsWith("$normalized/")
                    )
        }
        if (!covered) return null
        return loadRetainedListing(accountId, normalized, loadRetainedMetadataIndex(accountId))
    }

    private fun loadRetainedListing(
        accountId: String,
        normalized: String,
        index: RetainedMetadataIndex,
    ): LinuxVirtualDirectorySnapshot? {
        val reference = index.listings.firstOrNull { it.path == normalized } ?: return null
        val blob = File(accountDirectory(accountId), reference.blobName)
        if (!blob.isFile || blob.length() !in 1L..MAX_RETAINED_LISTING_BYTES) return null
        return runCatching {
            val encoded = blob.readBytes()
            require(sha256Hex(encoded) == reference.sha256)
            rangeCacheJson.decodeFromString<RetainedDirectoryListing>(encoded.decodeToString())
                .also { listing -> listing.requireValid(normalized) }
                .toSnapshot()
        }.getOrNull()
    }

    @Synchronized
    fun publishRetainedListings(
        accountId: String,
        retainedRoot: String,
        snapshots: Map<String, LinuxVirtualDirectorySnapshot>,
    ) {
        val normalizedRoot = FileOfflineKey(accountId, retainedRoot).relativePath
        require(snapshots.isNotEmpty() && normalizedRoot in snapshots)
        require(snapshots.size <= MAX_RETAINED_LISTINGS)
        val directory = writableAccountDirectory(accountId)
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
        val publishedPaths = published.mapTo(hashSetOf(), RetainedListingReference::path)
        val current = loadRetainedMetadataIndex(accountId)
        val nestedRetainedRoots = loadFolderRetention(accountId).rules.asSequence()
            .filter { rule ->
                rule.retention == VirtualFolderRetention.KeepOnDevice &&
                    rule.relativePath.startsWith("$normalizedRoot/")
            }
            .mapTo(hashSetOf(), VirtualFolderRetentionRule::relativePath)
        val next = RetainedMetadataIndex(
            listings = (
                current.listings.filterNot { reference ->
                    val replacedByPublished = reference.path in publishedPaths
                    val insidePublishedRoot = reference.path == normalizedRoot ||
                        reference.path.startsWith("$normalizedRoot/")
                    val requiredByNestedRoot = nestedRetainedRoots.any { nestedRoot ->
                        reference.path == nestedRoot ||
                            reference.path.startsWith("$nestedRoot/") ||
                            nestedRoot.startsWith("${reference.path}/")
                    }
                    replacedByPublished || insidePublishedRoot && !requiredByNestedRoot
                } + published
                ).sortedBy(RetainedListingReference::path),
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

    @Synchronized
    fun invalidateRetainedListings(accountId: String, path: String) {
        val normalized = FileOfflineKey(accountId, path).relativePath
        val parent = normalized.substringBeforeLast('/', "")
        val current = loadRetainedMetadataIndex(accountId)
        val parentStillRequired = loadFolderRetention(accountId).rules.any { rule ->
            rule.retention == VirtualFolderRetention.KeepOnDevice &&
                (parent.isEmpty() || rule.relativePath == parent || rule.relativePath.startsWith("$parent/"))
        }
        val next = current.copy(
            listings = current.listings.filterNot { reference ->
                reference.path == normalized ||
                    reference.path.startsWith("$normalized/") ||
                    reference.path == parent && !parentStillRequired
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
            .map { candidate -> FileOfflineKey(accountId, candidate) }
            .filter { key -> activePaths.getOrDefault(key, 0) > 0 }
            .forEach(deferredInvalidationPaths::add)
        val removablePaths = candidates.filter { candidate ->
            activePaths.getOrDefault(FileOfflineKey(accountId, candidate), 0) == 0
        }.toSet()
        invalidateRetainedListings(accountId, normalized)
        if (removablePaths.isEmpty()) return 0L
        val removed = current.blocks.filter { block -> block.path in removablePaths }
        removed.forEach { block -> File(accountDirectory(accountId), block.blobName).delete() }
        save(accountId, current.copy(blocks = current.blocks.filterNot { block -> block.path in removablePaths }))
        return removed.fold(0L) { total, block ->
            if (Long.MAX_VALUE - total < block.length) Long.MAX_VALUE else total + block.length
        }
    }

    @Synchronized
    fun summary(accountId: String): DesktopVirtualRangeCacheSummary {
        val entries = load(accountId).toDomain(accountId)
        val plan = planVirtualFileEviction(
            entries = entries.filter { entry -> entry.retention == VirtualFileRetention.Automatic },
            policy = policy(),
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
            nowEpochMillis = System.currentTimeMillis(),
        )
        return DesktopVirtualRangeCacheSummary(
            cachedBytes = entries.sumOf(VirtualFileCacheEntry::sizeBytes),
            reclaimableBytes = plan.reclaimableBytes,
            pinnedBytes = entries
                .filter { entry -> entry.retention == VirtualFileRetention.Pinned }
                .sumOf(VirtualFileCacheEntry::sizeBytes),
            fileCount = entries.size,
            pinnedFileCount = entries.count { entry -> entry.retention == VirtualFileRetention.Pinned },
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
        )
    }

    @Synchronized
    fun freeUp(accountId: String, requestedBytes: Long): VirtualFileEvictionPlan =
        applyEviction(accountId, requestedBytes, System.currentTimeMillis())

    private fun applyEviction(accountId: String, requestedBytes: Long, nowEpochMillis: Long): VirtualFileEvictionPlan {
        val current = load(accountId)
        val automaticEntries = current.toDomain(accountId).filter { entry ->
            entry.retention == VirtualFileRetention.Automatic
        }
        val plan = planVirtualFileEviction(
            entries = automaticEntries,
            policy = policy(),
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
            nowEpochMillis = nowEpochMillis,
            requestedBytesToFree = requestedBytes,
        )
        val removedPaths = plan.evictions.mapNotNullTo(mutableSetOf()) { eviction ->
            val matching = current.blocks.filter { it.path == eviction.key.relativePath }
            if (matching.isEmpty() || activePaths.getOrDefault(eviction.key, 0) != 0) return@mapNotNullTo null
            if (matching.localRevision() != eviction.expectedLocalRevision) return@mapNotNullTo null
            matching.forEach { block -> File(accountDirectory(accountId), block.blobName).delete() }
            eviction.key.relativePath
        }
        if (removedPaths.isNotEmpty()) {
            save(accountId, current.copy(blocks = current.blocks.filterNot { it.path in removedPaths }))
        }
        return plan
    }

    private fun RangeCacheIndex.toDomain(accountId: String): List<VirtualFileCacheEntry> {
        val retention = loadFolderRetention(accountId)
        return blocks.groupBy(CachedRangeBlock::path).map { (path, fileBlocks) ->
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

    private fun List<CachedRangeBlock>.localRevision(): String = "sha256:" + sha256Hex(
        sortedBy(CachedRangeBlock::offset).joinToString("|") { block ->
            "${block.remoteRevision}:${block.offset}:${block.length}:${block.sha256}"
        },
    )

    private fun load(accountId: String): RangeCacheIndex {
        val file = File(accountDirectory(accountId), INDEX_FILE)
        if (!file.isFile || file.length() !in 1L..maximumIndexBytes) return RangeCacheIndex()
        return runCatching {
            rangeCacheJson.decodeFromString<RangeCacheIndex>(file.readText()).also { index -> index.requireValid() }
        }.getOrElse { RangeCacheIndex() }
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
            .take(MAX_RETAINED_LISTINGS)
            .forEach(File::delete)
    }

    private fun save(accountId: String, index: RangeCacheIndex) {
        val directory = writableAccountDirectory(accountId)
        val bounded = boundedIndex(accountId, index)
        val encoded = encodedIndex(bounded)
        publishBytes(directory, INDEX_FILE, encoded)
        val referenced = bounded.blocks.mapTo(hashSetOf(), CachedRangeBlock::blobName)
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "block" && it.name !in referenced }
            .forEach(File::delete)
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

    private fun boundedIndex(accountId: String, index: RangeCacheIndex): RangeCacheIndex {
        val retention = loadFolderRetention(accountId)
        val (pinned, automatic) = index.blocks.partition { block ->
            retention.retentionFor(block.path) == VirtualFolderRetention.KeepOnDevice
        }
        require(pinned.size <= maximumBlocks) {
            "The retained folders exceed the supported virtual-file cache index."
        }
        return index.copy(
            blocks = pinned + automatic
                .sortedByDescending(CachedRangeBlock::lastAccessedAtEpochMillis)
                .take(maximumBlocks - pinned.size),
            folderRules = emptyList(),
        ).also { bounded -> bounded.requireValid() }
    }

    private fun encodedIndex(index: RangeCacheIndex): ByteArray =
        rangeCacheJson.encodeToString(index).encodeToByteArray().also { encoded ->
            require(encoded.size.toLong() <= maximumIndexBytes) { "The desktop virtual range index is too large." }
        }

    private fun removeRecord(accountId: String, index: RangeCacheIndex, record: CachedRangeBlock, blob: File) {
        blob.delete()
        save(accountId, index.copy(blocks = index.blocks.filterNot { it == record }))
    }

    private fun removeExactPath(accountId: String, path: String) {
        val current = load(accountId)
        val removed = current.blocks.filter { block -> block.path == path }
        removed.forEach { block -> File(accountDirectory(accountId), block.blobName).delete() }
        if (removed.isNotEmpty()) save(accountId, current.copy(blocks = current.blocks.filterNot { it in removed }))
    }

    internal inner class RevisionStaging internal constructor(
        private val accountId: String,
        private val path: String,
        private val remoteRevision: String,
        private val fileSize: Long,
        private val directory: File,
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
    ) {
        check(activePaths.getOrDefault(FileOfflineKey(accountId, path), 0) <= 1) {
            "The previous retained revision is still open. The refresh will retry after it closes."
        }
        val current = load(accountId)
        val records = staged.map(StagedRangeBlock::record)
        val next = current.copy(blocks = current.blocks.filterNot { block -> block.path == path } + records)
        val bounded = boundedIndex(accountId, next)
        require(records.all { record -> record in bounded.blocks }) {
            "The retained folder is larger than the supported virtual-file cache index."
        }
        encodedIndex(bounded)
        val currentBlobs = current.blocks.mapTo(hashSetOf(), CachedRangeBlock::blobName)
        val moved = mutableListOf<File>()
        val journal = File(accountDirectory(accountId), "range-revision.$stageId.commit")
        publishBytes(
            journal.parentFile,
            journal.name,
            records.joinToString("\n", transform = CachedRangeBlock::blobName).encodeToByteArray(),
        )
        syncDirectoryMetadata(journal.parentFile)
        try {
            staged.forEach { stagedBlock ->
                val destination = File(accountDirectory(accountId), stagedBlock.record.blobName)
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
            syncDirectoryMetadata(journal.parentFile)
            save(accountId, next)
            syncDirectoryMetadata(journal.parentFile)
            journal.delete()
        } catch (failure: Throwable) {
            moved.filterNot { file -> file.name in currentBlobs }.forEach(File::delete)
            journal.delete()
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
            }
        }
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
        val file = File(directory, INDEX_FILE)
        if (!file.isFile || file.length() !in 1L..maximumIndexBytes) return null
        return runCatching {
            rangeCacheJson.decodeFromString<RangeCacheIndex>(file.readText()).also { index -> index.requireValid() }
        }.getOrNull()
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
    }

    private fun RangeCacheIndex.requireValid() {
        require(version == 1 && blocks.size <= maximumBlocks)
        require(blocks.map { "${it.path}\u0000${it.offset}" }.distinct().size == blocks.size)
        blocks.forEach(CachedRangeBlock::requireValid)
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

    private companion object {
        const val INDEX_FILE = "range-index-v1.json"
        const val RETENTION_INDEX_FILE = "folder-retention-v1.json"
        const val RETAINED_METADATA_INDEX_FILE = "retained-metadata-v1.json"
        const val MAX_INDEX_BYTES = 16L * 1024L * 1024L
        const val MAX_RETENTION_INDEX_BYTES = 1024L * 1024L
        const val MAX_RETAINED_METADATA_INDEX_BYTES = 16L * 1024L * 1024L
        const val MAX_RETAINED_LISTING_BYTES = 16L * 1024L * 1024L
        const val MAX_COMMIT_JOURNAL_BYTES = 2L * 1024L * 1024L
        const val MAX_RETAINED_LISTINGS = 20_000
        const val MAX_BLOCKS = 20_000
        const val MAX_BLOCK_BYTES = 4 * 1024 * 1024
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
    val version: Int = 1,
    val blocks: List<CachedRangeBlock> = emptyList(),
    val folderRules: List<CachedVirtualFolderRule> = emptyList(),
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
) {
    fun requireValid(expectedPath: String) {
        require(version == 1 && path == expectedPath && fetchedAtEpochMillis >= 0L)
        if (path.isNotEmpty()) FileOfflineKey("account", path)
        require(nodes.size <= 50_000)
        require(nodes.map(RetainedVirtualFileNode::path).distinct().size == nodes.size)
        nodes.forEach(RetainedVirtualFileNode::requireValid)
    }

    fun toSnapshot() = LinuxVirtualDirectorySnapshot(
        nodes = nodes.map(RetainedVirtualFileNode::toDomain),
        fetchedAtEpochMillis = fetchedAtEpochMillis,
    )

    companion object {
        fun fromSnapshot(path: String, snapshot: LinuxVirtualDirectorySnapshot) = RetainedDirectoryListing(
            path = path,
            fetchedAtEpochMillis = snapshot.fetchedAtEpochMillis,
            nodes = snapshot.nodes.map(RetainedVirtualFileNode.Companion::fromDomain),
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
