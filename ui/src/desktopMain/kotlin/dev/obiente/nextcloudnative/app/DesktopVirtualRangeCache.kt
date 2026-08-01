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
    val fileCount: Int,
    val availableFreeBytes: Long,
)

/** Persistent exact-revision block cache used by the Linux virtual filesystem. */
internal class DesktopVirtualRangeCache(
    private val root: File,
    private val policy: () -> VirtualFileCachePolicy,
) {
    private val activePaths = mutableMapOf<FileOfflineKey, Int>()

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
        publishBytes(directory, blobName, bytes)
        val current = load(accountId)
        val obsolete = current.blocks.filter { block ->
            block.path == normalized &&
                (block.remoteRevision != remoteRevision || block.fileSize != fileSize || block.offset == offset)
        }
        obsolete.forEach { block -> File(directory, block.blobName).delete() }
        val next = current.copy(
            blocks = current.blocks.filterNot { it in obsolete } + CachedRangeBlock(
                path = normalized,
                remoteRevision = remoteRevision,
                fileSize = fileSize,
                offset = offset,
                length = bytes.size,
                blobName = blobName,
                sha256 = sha256Hex(bytes),
                cachedAtEpochMillis = nowEpochMillis,
                lastAccessedAtEpochMillis = nowEpochMillis,
            ),
        )
        save(accountId, next)
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
    fun summary(accountId: String): DesktopVirtualRangeCacheSummary {
        val entries = load(accountId).toDomain(accountId)
        val plan = planVirtualFileEviction(
            entries = entries,
            policy = policy(),
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
            nowEpochMillis = System.currentTimeMillis(),
        )
        return DesktopVirtualRangeCacheSummary(
            cachedBytes = plan.cachedBytes,
            reclaimableBytes = plan.reclaimableBytes,
            fileCount = entries.size,
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
        )
    }

    @Synchronized
    fun freeUp(accountId: String, requestedBytes: Long): VirtualFileEvictionPlan =
        applyEviction(accountId, requestedBytes, System.currentTimeMillis())

    private fun applyEviction(accountId: String, requestedBytes: Long, nowEpochMillis: Long): VirtualFileEvictionPlan {
        val current = load(accountId)
        val plan = planVirtualFileEviction(
            entries = current.toDomain(accountId),
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

    private fun RangeCacheIndex.toDomain(accountId: String): List<VirtualFileCacheEntry> =
        blocks.groupBy(CachedRangeBlock::path).map { (path, fileBlocks) ->
            VirtualFileCacheEntry(
                key = FileOfflineKey(accountId, path),
                remoteRevision = fileBlocks.first().remoteRevision,
                localRevision = fileBlocks.localRevision(),
                sizeBytes = fileBlocks.sumOf(CachedRangeBlock::length).toLong(),
                cachedAtEpochMillis = fileBlocks.minOf(CachedRangeBlock::cachedAtEpochMillis),
                lastAccessedAtEpochMillis = fileBlocks.maxOf(CachedRangeBlock::lastAccessedAtEpochMillis),
                retention = VirtualFileRetention.Automatic,
                activeLeaseCount = activePaths.getOrDefault(FileOfflineKey(accountId, path), 0),
            )
        }

    private fun List<CachedRangeBlock>.localRevision(): String = "sha256:" + sha256Hex(
        sortedBy(CachedRangeBlock::offset).joinToString("|") { block ->
            "${block.remoteRevision}:${block.offset}:${block.length}:${block.sha256}"
        },
    )

    private fun load(accountId: String): RangeCacheIndex {
        val file = File(accountDirectory(accountId), INDEX_FILE)
        if (!file.isFile || file.length() !in 1L..MAX_INDEX_BYTES) return RangeCacheIndex()
        return runCatching {
            rangeCacheJson.decodeFromString<RangeCacheIndex>(file.readText()).also { index -> index.requireValid() }
        }.getOrElse { RangeCacheIndex() }
    }

    private fun save(accountId: String, index: RangeCacheIndex) {
        val directory = accountDirectory(accountId).apply {
            check(isDirectory || mkdirs()) { "Could not create the desktop virtual range cache." }
        }
        val bounded = index.copy(
            blocks = index.blocks.sortedByDescending(CachedRangeBlock::lastAccessedAtEpochMillis).take(MAX_BLOCKS),
        )
        bounded.requireValid()
        val encoded = rangeCacheJson.encodeToString(bounded).encodeToByteArray()
        require(encoded.size <= MAX_INDEX_BYTES)
        publishBytes(directory, INDEX_FILE, encoded)
        val referenced = bounded.blocks.mapTo(hashSetOf(), CachedRangeBlock::blobName)
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "block" && it.name !in referenced }
            .forEach(File::delete)
    }

    private fun removeRecord(accountId: String, index: RangeCacheIndex, record: CachedRangeBlock, blob: File) {
        blob.delete()
        save(accountId, index.copy(blocks = index.blocks.filterNot { it == record }))
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
        require(version == 1 && blocks.size <= MAX_BLOCKS)
        require(blocks.map { "${it.path}\u0000${it.offset}" }.distinct().size == blocks.size)
        blocks.forEach(CachedRangeBlock::requireValid)
    }

    private companion object {
        const val INDEX_FILE = "range-index-v1.json"
        const val MAX_INDEX_BYTES = 16L * 1024L * 1024L
        const val MAX_BLOCKS = 20_000
        const val MAX_BLOCK_BYTES = 4 * 1024 * 1024
    }
}

internal fun defaultDesktopVirtualRangeCache(
    policy: () -> VirtualFileCachePolicy,
): DesktopVirtualRangeCache {
    val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
    val cacheRoot = xdgCache?.let(::File) ?: File(System.getProperty("user.home"), ".cache")
    return DesktopVirtualRangeCache(File(cacheRoot, "nextcloud-native/virtual-ranges"), policy)
}

@Serializable
private data class RangeCacheIndex(
    val version: Int = 1,
    val blocks: List<CachedRangeBlock> = emptyList(),
)

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

private fun sha256Hex(value: String): String = sha256Hex(value.encodeToByteArray())

private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private val rangeCacheJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = false
}
