package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
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

/** Persistent exact-revision block cache used by the Linux virtual filesystem. */
internal class DesktopVirtualRangeCache(
    private val root: File,
    private val maximumIndexBytes: Long = MAX_INDEX_BYTES,
    private val maximumBlocks: Int = MAX_BLOCKS,
    private val createParentDirectories: Boolean = true,
    private val policy: () -> VirtualFileCachePolicy,
) {
    private val activePaths = mutableMapOf<FileOfflineKey, Int>()

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
        if (remaining <= 0) activePaths.remove(key) else activePaths[key] = remaining
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
        val directory = accountDirectory(accountId).apply {
            check(isDirectory || mkdirs()) { "Could not create the desktop virtual range cache." }
        }
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
        val current = load(accountId)
        val removed = current.blocks.filter { block ->
            block.path == normalized || block.path.startsWith("$normalized/")
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
    fun cachedBytesForRevision(accountId: String, path: String, remoteRevision: String, fileSize: Long): Long {
        val normalized = FileOfflineKey(accountId, path).relativePath
        return load(accountId).blocks.asSequence()
            .filter { block ->
                block.path == normalized && block.remoteRevision == remoteRevision && block.fileSize == fileSize
            }
            .sumOf { block -> block.length.toLong() }
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
        val directory = accountDirectory(accountId).apply {
            check(isDirectory || mkdirs()) { "Could not create the desktop virtual range cache." }
        }
        return RevisionStaging(accountId, normalized, remoteRevision, fileSize, directory)
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

    /** Releases only inactive, automatic blocks in the selected subtree. */
    @Synchronized
    fun dehydrateFolder(accountId: String, path: String, protectedPaths: Set<String>): Long {
        val normalized = FileOfflineKey(accountId, path).relativePath
        val current = load(accountId)
        val retention = loadFolderRetention(accountId)
        val removablePaths = current.blocks.asSequence()
            .map(CachedRangeBlock::path)
            .distinct()
            .filter { candidate -> candidate == normalized || candidate.startsWith("$normalized/") }
            .filter { candidate -> retention.retentionFor(candidate) == VirtualFolderRetention.Automatic }
            .filter { candidate -> candidate !in protectedPaths }
            .filter { candidate -> activePaths.getOrDefault(FileOfflineKey(accountId, candidate), 0) == 0 }
            .toSet()
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
        if (file.isFile && file.length() in 1L..MAX_RETENTION_INDEX_BYTES) {
            runCatching {
                return rangeCacheJson.decodeFromString<VirtualFolderRetentionIndex>(file.readText()).also { index ->
                    index.requireValid()
                }
            }
        }
        val legacy = load(accountId).folderRules
        if (legacy.isEmpty()) return VirtualFolderRetentionIndex()
        return VirtualFolderRetentionIndex(rules = legacy).also { migrated ->
            runCatching { saveRetention(accountId, migrated) }
        }
    }

    private fun save(accountId: String, index: RangeCacheIndex) {
        val directory = accountDirectory(accountId).apply {
            check(isDirectory || mkdirs()) { "Could not create the desktop virtual range cache." }
        }
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
        val directory = accountDirectory(accountId).apply {
            check(isDirectory || mkdirs()) { "Could not create the desktop virtual range cache." }
        }
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

    internal inner class RevisionStaging internal constructor(
        private val accountId: String,
        private val path: String,
        private val remoteRevision: String,
        private val fileSize: Long,
        private val directory: File,
    ) : AutoCloseable {
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
            val temporary = File.createTempFile("range-revision.", ".stage", directory)
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
                ordered,
            )
            closed = true
            staged.clear()
            return true
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            staged.values.forEach { stagedBlock -> stagedBlock.temporary.delete() }
            staged.clear()
        }
    }

    @Synchronized
    private fun commitStagedRevision(
        accountId: String,
        path: String,
        staged: List<StagedRangeBlock>,
    ) {
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
            save(accountId, next)
        } catch (failure: Throwable) {
            moved.filterNot { file -> file.name in currentBlobs }.forEach(File::delete)
            throw failure
        } finally {
            staged.forEach { stagedBlock -> stagedBlock.temporary.delete() }
        }
    }

    private fun accountDirectory(accountId: String): File {
        require(accountId.length == 64 && accountId.all { it in '0'..'9' || it in 'a'..'f' })
        return File(root, accountId)
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
        const val MAX_INDEX_BYTES = 16L * 1024L * 1024L
        const val MAX_RETENTION_INDEX_BYTES = 1024L * 1024L
        const val MAX_BLOCKS = 20_000
        const val MAX_BLOCK_BYTES = 4 * 1024 * 1024
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
private data class CachedVirtualFolderHydration(
    val relativePath: String,
    val phase: VirtualFolderHydrationPhase,
    val detail: String? = null,
) {
    fun toDomain(): VirtualFolderHydrationStatus = VirtualFolderHydrationStatus(relativePath, phase, detail)

    companion object {
        fun fromDomain(status: VirtualFolderHydrationStatus) = CachedVirtualFolderHydration(
            status.relativePath,
            status.phase,
            status.detail,
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

private val rangeCacheJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = false
}
