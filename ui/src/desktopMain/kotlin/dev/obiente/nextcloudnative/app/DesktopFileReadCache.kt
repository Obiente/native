package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.prefs.Preferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class DesktopCachedFileContent(
    val bytes: ByteArray,
    val mimeType: String?,
    val etag: String,
)

internal data class DesktopVirtualFileCacheSummary(
    val policy: VirtualFileCachePolicy,
    val cachedBytes: Long,
    val reclaimableBytes: Long,
    val entryCount: Int,
    val availableFreeBytes: Long,
)

/**
 * Disposable, account-private Files read cache for desktop.
 *
 * Metadata and content are persisted separately. Content is addressed by canonical path plus ETag,
 * and a successful folder refresh removes generations that disappeared or changed. All index and
 * blob writes publish atomically and every dimension has a hard bound.
 */
internal class DesktopFileReadCache(
    private val root: File,
    private val maximumContentBytes: Long = DEFAULT_MAXIMUM_CONTENT_BYTES,
    private val maximumEntryBytes: Long = DEFAULT_MAXIMUM_ENTRY_BYTES,
    private val preferences: Preferences = Preferences.userRoot()
        .node("dev/obiente/nextcloudnative/virtual-file-cache"),
) {
    init {
        require(maximumContentBytes > 0L)
        require(maximumEntryBytes in 1L..maximumContentBytes)
    }

    @Synchronized
    fun cachedListing(accountId: String, path: String): List<NextcloudFile>? {
        val normalized = path.cachePath()
        return load(accountId).listings.firstOrNull { it.path == normalized }?.files
    }

    @Synchronized
    fun storeListing(
        accountId: String,
        path: String,
        files: List<NextcloudFile>,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        require(nowEpochMillis >= 0L)
        require(files.size <= MAX_FILES_PER_LISTING) { "The folder contains too many cacheable entries." }
        val normalized = path.cachePath()
        files.forEach { file ->
            require(file.path.cachePath() == file.path) { "A cached file path is not canonical." }
            require(file.name.length <= MAX_FILE_NAME_LENGTH && file.name.none(Char::isISOControl))
            require(file.etag == null || file.etag.length <= MAX_ETAG_LENGTH && file.etag.none(Char::isISOControl))
        }
        val accountDirectory = accountDirectory(accountId)
        var index = load(accountId)
        val currentByPath = files.associateBy(NextcloudFile::path)
        val invalidContent = index.content.filter { cached ->
            cached.path.parentCachePath() == normalized &&
                currentByPath[cached.path]?.etag != cached.etag
        }
        invalidContent.forEach { cached -> File(accountDirectory, cached.blobName).delete() }
        val listings = (
            index.listings.filterNot { it.path == normalized } +
                CachedListingV1(normalized, nowEpochMillis, files)
            ).sortedByDescending(CachedListingV1::fetchedAtEpochMillis)
            .take(MAX_LISTINGS)
        index = index.copy(
            listings = listings,
            content = index.content.filterNot { cached -> cached in invalidContent },
        ).bounded()
        save(accountId, index)
    }

    @Synchronized
    fun cachedContent(
        accountId: String,
        path: String,
        maximumBytes: Long,
    ): DesktopCachedFileContent? {
        require(maximumBytes > 0L)
        val normalized = path.cachePath()
        val record = load(accountId).content.firstOrNull { it.path == normalized } ?: return null
        if (record.size > maximumBytes || record.size > maximumEntryBytes) return null
        val blob = File(accountDirectory(accountId), record.blobName)
        if (!blob.isFile || blob.length() != record.size) return null
        val bytes = blob.readBytes()
        if (bytes.size.toLong() != record.size) return null
        if (sha256Hex(bytes) != record.sha256) return null
        val current = load(accountId)
        save(
            accountId,
            current.copy(
                content = current.content.map { cached ->
                    if (cached.path == normalized) {
                        cached.copy(lastAccessedAtEpochMillis = System.currentTimeMillis())
                    } else {
                        cached
                    }
                },
            ),
        )
        return DesktopCachedFileContent(bytes, record.mimeType, record.etag)
    }

    @Synchronized
    fun storeContent(
        accountId: String,
        path: String,
        content: NextcloudFileContent,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        require(nowEpochMillis >= 0L)
        val normalized = path.cachePath()
        val etag = content.etag?.takeIf(String::isNotBlank) ?: return false
        require(etag.length <= MAX_ETAG_LENGTH)
        if (content.bytes.size.toLong() > maximumEntryBytes) return false
        require(
            content.mimeType == null ||
                content.mimeType.length <= MAX_MIME_TYPE_LENGTH && content.mimeType.none(Char::isISOControl),
        )
        val accountDirectory = accountDirectory(accountId).apply {
            check(isDirectory || mkdirs()) { "Could not create the desktop Files cache." }
        }
        val blobName = "${sha256Hex("$normalized\u0000$etag")}.blob"
        publishBytes(accountDirectory, blobName, content.bytes)
        var index = load(accountId)
        index.content.filter { cached -> cached.path == normalized && cached.blobName != blobName }
            .forEach { cached -> File(accountDirectory, cached.blobName).delete() }
        index = index.copy(
            content = index.content.filterNot { it.path == normalized } +
                CachedContentV1(
                    path = normalized,
                    etag = etag,
                    mimeType = content.mimeType,
                    size = content.bytes.size.toLong(),
                    blobName = blobName,
                    sha256 = sha256Hex(content.bytes),
                    storedAtEpochMillis = nowEpochMillis,
                    lastAccessedAtEpochMillis = nowEpochMillis,
                ),
        ).bounded()
        save(accountId, index)
        applyEviction(accountId, requestedBytesToFree = 0L, nowEpochMillis = nowEpochMillis)
        return load(accountId).content.any { cached ->
            cached.path == normalized && cached.blobName == blobName
        }
    }

    @Synchronized
    fun loadPolicy(): VirtualFileCachePolicy = VirtualFileCachePolicy(
        automaticCleanup = preferences.getBoolean(KEY_AUTOMATIC_CLEANUP, true),
        maximumCacheBytes = preferences.getLong(
            KEY_MAXIMUM_CACHE_BYTES,
            DEFAULT_VIRTUAL_FILE_CACHE_BYTES,
        ).optionalPositiveOrDefault(DEFAULT_VIRTUAL_FILE_CACHE_BYTES),
        minimumFreeSpaceBytes = preferences.getLong(
            KEY_MINIMUM_FREE_BYTES,
            DEFAULT_VIRTUAL_FILE_MINIMUM_FREE_BYTES,
        ).coerceAtLeast(0L),
        unusedFileAgeMillis = preferences.getLong(
            KEY_UNUSED_FILE_AGE,
            DEFAULT_VIRTUAL_FILE_UNUSED_AGE_MILLIS,
        ).optionalPositiveOrDefault(DEFAULT_VIRTUAL_FILE_UNUSED_AGE_MILLIS),
    )

    private fun Long.optionalPositiveOrDefault(defaultValue: Long): Long? = when {
        this == UNLIMITED_SENTINEL -> null
        this > 0L -> this
        else -> defaultValue
    }

    @Synchronized
    fun savePolicy(policy: VirtualFileCachePolicy) {
        preferences.putBoolean(KEY_AUTOMATIC_CLEANUP, policy.automaticCleanup)
        preferences.putLong(KEY_MAXIMUM_CACHE_BYTES, policy.maximumCacheBytes ?: UNLIMITED_SENTINEL)
        preferences.putLong(KEY_MINIMUM_FREE_BYTES, policy.minimumFreeSpaceBytes)
        preferences.putLong(KEY_UNUSED_FILE_AGE, policy.unusedFileAgeMillis ?: UNLIMITED_SENTINEL)
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.isSha256Hex() }
            .forEach { applyEviction(it.name, requestedBytesToFree = 0L) }
    }

    @Synchronized
    fun virtualFileSummary(
        accountId: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): DesktopVirtualFileCacheSummary {
        val entries = load(accountId).content.toVirtualFileEntries(accountId)
        val plan = planVirtualFileEviction(
            entries = entries,
            policy = loadPolicy(),
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
            nowEpochMillis = nowEpochMillis,
        )
        return DesktopVirtualFileCacheSummary(
            policy = loadPolicy(),
            cachedBytes = plan.cachedBytes,
            reclaimableBytes = plan.reclaimableBytes,
            entryCount = entries.size,
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
        )
    }

    @Synchronized
    fun freeUpVirtualFiles(
        accountId: String,
        requestedBytesToFree: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): VirtualFileEvictionPlan = applyEviction(accountId, requestedBytesToFree, nowEpochMillis)

    @Synchronized
    fun invalidate(accountId: String, path: String) {
        val normalized = path.cachePath()
        val parent = normalized.parentCachePath()
        val accountDirectory = accountDirectory(accountId)
        val index = load(accountId)
        val removed = index.content.filter { cached ->
            normalized.isEmpty() || cached.path == normalized || cached.path.startsWith("$normalized/")
        }
        removed.forEach { cached -> File(accountDirectory, cached.blobName).delete() }
        save(
            accountId,
            index.copy(
                listings = index.listings.filterNot { listing ->
                    normalized.isEmpty() ||
                        listing.path == normalized ||
                        listing.path.startsWith("$normalized/") ||
                        listing.path == parent
                },
                content = index.content.filterNot { it in removed },
            ),
        )
    }

    private fun CacheIndexV1.bounded(): CacheIndexV1 {
        val boundedListings = listings
            .sortedByDescending(CachedListingV1::fetchedAtEpochMillis)
            .take(MAX_LISTINGS)
        require(boundedListings.sumOf { it.files.size } <= MAX_TOTAL_METADATA_ENTRIES) {
            "The Files metadata cache exceeds its entry limit."
        }
        val policyBudget = if (loadPolicy().automaticCleanup) {
            loadPolicy().maximumCacheBytes ?: maximumContentBytes
        } else {
            maximumContentBytes
        }
        val effectiveMaximum = minOf(maximumContentBytes, policyBudget)
        var retainedBytes = 0L
        val retainedContent = content
            .sortedByDescending(CachedContentV1::lastAccessedAtEpochMillis)
            .filter { entry ->
                if (retainedBytes + entry.size > effectiveMaximum) {
                    false
                } else {
                    retainedBytes += entry.size
                    true
                }
            }
            .take(MAX_CONTENT_ENTRIES)
        return copy(listings = boundedListings, content = retainedContent)
    }

    private fun load(accountId: String): CacheIndexV1 {
        val directory = accountDirectory(accountId)
        val indexFile = File(directory, INDEX_FILE_NAME)
        if (!indexFile.isFile || indexFile.length() !in 1..MAX_INDEX_BYTES) return CacheIndexV1()
        return runCatching {
            val decoded = cacheJson.decodeFromString<CacheIndexV1>(indexFile.readText(Charsets.UTF_8))
            decoded.requireValid().bounded()
        }.getOrElse { CacheIndexV1() }
    }

    private fun save(accountId: String, index: CacheIndexV1) {
        val directory = accountDirectory(accountId).apply {
            check(isDirectory || mkdirs()) { "Could not create the desktop Files cache." }
        }
        val bounded = index.bounded()
        val encoded = cacheJson.encodeToString(bounded).encodeToByteArray()
        require(encoded.size.toLong() <= MAX_INDEX_BYTES) { "The Files cache index is too large." }
        publishBytes(directory, INDEX_FILE_NAME, encoded)
        val referenced = bounded.content.mapTo(hashSetOf(), CachedContentV1::blobName)
        directory.listFiles().orEmpty()
            .filter { file -> file.isFile && file.extension == "blob" && file.name !in referenced }
            .forEach(File::delete)
    }

    private fun applyEviction(
        accountId: String,
        requestedBytesToFree: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): VirtualFileEvictionPlan {
        require(requestedBytesToFree >= 0L)
        val current = load(accountId)
        val plan = planVirtualFileEviction(
            entries = current.content.toVirtualFileEntries(accountId),
            policy = loadPolicy(),
            availableFreeBytes = root.usableSpace.coerceAtLeast(0L),
            nowEpochMillis = nowEpochMillis,
            requestedBytesToFree = requestedBytesToFree,
        )
        val byPath = current.content.associateBy(CachedContentV1::path)
        val removed = plan.evictions.mapNotNullTo(mutableSetOf()) { eviction ->
            val record = byPath[eviction.key.relativePath] ?: return@mapNotNullTo null
            if ("sha256:${record.sha256}" != eviction.expectedLocalRevision) return@mapNotNullTo null
            val blob = File(accountDirectory(accountId), record.blobName)
            if (!blob.exists() || blob.delete()) record.path else null
        }
        if (removed.isNotEmpty()) {
            save(accountId, current.copy(content = current.content.filterNot { it.path in removed }))
        }
        return plan
    }

    private fun List<CachedContentV1>.toVirtualFileEntries(accountId: String): List<VirtualFileCacheEntry> =
        map { record ->
            VirtualFileCacheEntry(
                key = FileOfflineKey(accountId, record.path),
                remoteRevision = record.etag,
                localRevision = "sha256:${record.sha256}",
                sizeBytes = record.size,
                cachedAtEpochMillis = record.storedAtEpochMillis,
                lastAccessedAtEpochMillis = record.lastAccessedAtEpochMillis,
                retention = VirtualFileRetention.Automatic,
                activity = VirtualFileActivity.Idle,
            )
        }

    private fun accountDirectory(accountId: String): File {
        require(accountId.isSha256Hex())
        return File(root, accountId)
    }

    private fun CacheIndexV1.requireValid(): CacheIndexV1 = also { index ->
        require(index.version == FORMAT_VERSION)
        require(index.listings.size <= MAX_LISTINGS)
        require(index.content.size <= MAX_CONTENT_ENTRIES)
        require(index.listings.map(CachedListingV1::path).distinct().size == index.listings.size)
        require(index.content.map(CachedContentV1::path).distinct().size == index.content.size)
        index.listings.forEach { listing ->
            require(listing.path.cachePath() == listing.path)
            require(listing.fetchedAtEpochMillis >= 0L)
            require(listing.files.size <= MAX_FILES_PER_LISTING)
            listing.files.forEach { file ->
                require(file.path.cachePath() == file.path)
                require(file.name.length <= MAX_FILE_NAME_LENGTH && file.name.none(Char::isISOControl))
                require(file.etag == null || file.etag.length <= MAX_ETAG_LENGTH && file.etag.none(Char::isISOControl))
            }
        }
        index.content.forEach { content ->
            require(content.path.cachePath() == content.path)
            require(content.etag.isNotBlank() && content.etag.length <= MAX_ETAG_LENGTH)
            require(content.etag.none(Char::isISOControl))
            require(content.mimeType == null ||
                content.mimeType.length <= MAX_MIME_TYPE_LENGTH && content.mimeType.none(Char::isISOControl))
            require(content.size in 0..maximumEntryBytes)
            require(content.blobName.length == 69 && content.blobName.endsWith(".blob"))
            require(content.blobName.removeSuffix(".blob").isSha256Hex())
            require(content.sha256.isSha256Hex())
            require(content.storedAtEpochMillis >= 0L)
            require(content.lastAccessedAtEpochMillis >= content.storedAtEpochMillis)
        }
    }

    private fun publishBytes(directory: File, name: String, bytes: ByteArray) {
        val temporary = File.createTempFile("$name.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            val destination = File(directory, name)
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
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val INDEX_FILE_NAME = "index-v1.json"
        const val MAX_INDEX_BYTES = 8L * 1024L * 1024L
        const val MAX_LISTINGS = 256
        const val MAX_FILES_PER_LISTING = 5_000
        const val MAX_TOTAL_METADATA_ENTRIES = 20_000
        const val MAX_CONTENT_ENTRIES = 256
        const val MAX_FILE_NAME_LENGTH = 1_024
        const val MAX_ETAG_LENGTH = 4_096
        const val MAX_MIME_TYPE_LENGTH = 512
        const val DEFAULT_MAXIMUM_ENTRY_BYTES = 512L * 1024L * 1024L
        const val DEFAULT_MAXIMUM_CONTENT_BYTES = 256L * 1024L * 1024L * 1024L
        const val KEY_AUTOMATIC_CLEANUP = "automatic-cleanup"
        const val KEY_MAXIMUM_CACHE_BYTES = "maximum-cache-bytes"
        const val KEY_MINIMUM_FREE_BYTES = "minimum-free-bytes"
        const val KEY_UNUSED_FILE_AGE = "unused-file-age"
        const val UNLIMITED_SENTINEL = -1L
    }
}

internal fun desktopFileCacheAccountId(session: NextcloudSession): String =
    sha256Hex("${session.serverUrl}\u0000${session.loginName}")

private fun desktopFilesCacheDirectory(): File {
    val xdgCache = System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
    val cacheRoot = xdgCache?.let(::File) ?: File(System.getProperty("user.home"), ".cache")
    return File(cacheRoot, "nextcloud-native/files")
}

internal fun defaultDesktopFileReadCache(): DesktopFileReadCache =
    DesktopFileReadCache(desktopFilesCacheDirectory())

private fun String.cachePath(): String {
    require(length <= 8_192)
    require(none { it == '\u0000' || it == '\n' || it == '\r' || it == '\\' })
    val normalized = trim('/')
    if (normalized.isEmpty()) return ""
    require(normalized.split('/').none { it.isEmpty() || it == "." || it == ".." })
    return normalized
}

private fun String.parentCachePath(): String = substringBeforeLast('/', "")

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .toHex()

private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value)
    .toHex()

private fun ByteArray.toHex(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun String.isSha256Hex(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

@Serializable
private data class CacheIndexV1(
    val version: Int = 1,
    val listings: List<CachedListingV1> = emptyList(),
    val content: List<CachedContentV1> = emptyList(),
)

@Serializable
private data class CachedListingV1(
    val path: String,
    val fetchedAtEpochMillis: Long,
    val files: List<NextcloudFile>,
)

@Serializable
private data class CachedContentV1(
    val path: String,
    val etag: String,
    val mimeType: String?,
    val size: Long,
    val blobName: String,
    val sha256: String,
    val storedAtEpochMillis: Long,
    val lastAccessedAtEpochMillis: Long = storedAtEpochMillis,
)

private val cacheJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = false
}
