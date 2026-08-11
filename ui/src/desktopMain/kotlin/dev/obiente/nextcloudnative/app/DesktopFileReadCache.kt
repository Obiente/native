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

internal data class DesktopCachedFileListing(
    val files: List<NextcloudFile>,
    val fetchedAtEpochMillis: Long,
)

internal data class DesktopCachedVirtualListing(
    val nodes: List<LinuxVirtualFileNode>,
    val fetchedAtEpochMillis: Long,
    val freshAtEpochMillis: Long = fetchedAtEpochMillis,
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
    private val maximumLoadedAccountIndexes: Int = DEFAULT_MAXIMUM_LOADED_ACCOUNT_INDEXES,
    private val maximumTotalMetadataEntries: Int = MAX_TOTAL_METADATA_ENTRIES,
    private val maximumIndexBytes: Long = MAX_INDEX_BYTES,
    private val maximumHydratedMetadataBytes: Long = MAX_HYDRATED_METADATA_BYTES,
    private val metadataShardReadObserver: (File) -> Unit = {},
) {
    private val loadedIndexes = LinkedHashMap<String, CacheIndexV1>(16, 0.75f, true)
    private val failedVirtualListingInvalidations = mutableMapOf<String, Set<String>>()
    private val virtualListingInvalidationPreferences = preferences.node(
        "linux-virtual-metadata-invalidations-v1",
    )
    init {
        require(maximumContentBytes > 0L)
        require(maximumEntryBytes in 1L..maximumContentBytes)
        require(maximumLoadedAccountIndexes > 0)
        require(maximumTotalMetadataEntries >= 2)
        require(maximumIndexBytes in 1L..MAX_INDEX_BYTES)
        require(maximumHydratedMetadataBytes in 1L..MAX_HYDRATED_METADATA_BYTES)
    }

    @Synchronized
    fun cachedListing(accountId: String, path: String): List<NextcloudFile>? =
        cachedListingSnapshot(accountId, path)?.files

    @Synchronized
    fun cachedListingPaths(accountId: String): Set<String> =
        load(accountId).let { index ->
            buildSet {
                index.listings.mapTo(this, CachedListingV1::path)
                index.listingShards.mapTo(this, CachedListingShardReferenceV1::path)
            }
        }

    @Synchronized
    fun cachedListingSnapshot(accountId: String, path: String): DesktopCachedFileListing? {
        val normalized = path.cachePath()
        val index = load(accountId)
        val listing = index.listings.firstOrNull { it.path == normalized }
            ?: hydrateListingOnDemand(accountId, normalized, index)
            ?: return null
        return listing.let {
            DesktopCachedFileListing(listing.files, listing.fetchedAtEpochMillis)
        }
    }

    @Synchronized
    fun cachedVirtualListingPaths(accountId: String): Set<String> =
        load(accountId).let { index ->
            buildSet {
                index.virtualListings.mapTo(this, CachedVirtualListingV1::path)
                index.virtualListingShards.mapTo(this, CachedVirtualListingShardReferenceV1::path)
            }
        }

    @Synchronized
    fun cachedVirtualListingSnapshot(accountId: String, path: String): DesktopCachedVirtualListing? {
        val normalized = path.cachePath()
        val index = load(accountId)
        val listing = index.virtualListings.firstOrNull { it.path == normalized }
            ?: hydrateVirtualListingOnDemand(accountId, normalized, index)
            ?: return null
        return listing.let {
            DesktopCachedVirtualListing(
                nodes = listing.nodes.map(CachedVirtualFileNodeV1::toDomain),
                fetchedAtEpochMillis = listing.fetchedAtEpochMillis,
                freshAtEpochMillis = listing.freshAtEpochMillis,
            )
        }
    }

    @Synchronized
    fun failedVirtualListingInvalidations(accountId: String): Set<String> {
        failedVirtualListingInvalidations[accountId]?.let { return it.toSet() }
        return if (virtualListingInvalidationPreferences.getBoolean(accountId, false)) {
            setOf("")
        } else {
            emptySet()
        }
    }

    @Synchronized
    fun replaceFailedVirtualListingInvalidations(accountId: String, paths: Set<String>) {
        if (paths.isEmpty()) {
            failedVirtualListingInvalidations.remove(accountId)
            virtualListingInvalidationPreferences.remove(accountId)
        } else {
            failedVirtualListingInvalidations[accountId] = paths.toSet()
            // One durable bit deliberately quarantines every persisted virtual listing for this
            // account. It remains safe and bounded even when thousands of paths were affected.
            virtualListingInvalidationPreferences.putBoolean(accountId, true)
        }
        virtualListingInvalidationPreferences.flush()
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
        files.forEach(::requireValidCachedFile)
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
            listingShards = index.listingShards.filterNot { reference -> reference.path == normalized },
        ).bounded()
        save(accountId, index)
    }

    @Synchronized
    fun storeListingUnlessNewer(
        accountId: String,
        path: String,
        files: List<NextcloudFile>,
        fetchedAtEpochMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        require(fetchedAtEpochMillis >= 0L)
        require(nowEpochMillis >= 0L)
        val normalized = path.cachePath()
        val currentTimestamp = load(accountId).let { index ->
            index.listings.firstOrNull { listing -> listing.path == normalized }?.fetchedAtEpochMillis
                ?: index.listingShards.asSequence().filter { reference -> reference.path == normalized }
                    .maxOfOrNull(CachedListingShardReferenceV1::fetchedAtEpochMillis)
        }
        if (
            currentTimestamp != null &&
            currentTimestamp <= nowEpochMillis &&
            currentTimestamp >= fetchedAtEpochMillis
        ) {
            return false
        }
        storeListing(accountId, normalized, files, fetchedAtEpochMillis)
        return true
    }

    @Synchronized
    fun storeVirtualListingUnlessNewer(
        accountId: String,
        path: String,
        nodes: List<LinuxVirtualFileNode>,
        fetchedAtEpochMillis: Long,
        freshAtEpochMillis: Long = fetchedAtEpochMillis,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        require(fetchedAtEpochMillis >= 0L)
        require(freshAtEpochMillis >= fetchedAtEpochMillis)
        require(nowEpochMillis >= 0L)
        require(nodes.size <= MAX_FILES_PER_LISTING) { "The folder contains too many cacheable entries." }
        val normalized = path.cachePath()
        nodes.forEach(::requireValidVirtualNode)
        val index = load(accountId)
        val currentTimestamp =
            index.virtualListings.firstOrNull { listing -> listing.path == normalized }?.fetchedAtEpochMillis
                ?: index.virtualListingShards.asSequence().filter { reference -> reference.path == normalized }
                    .maxOfOrNull(CachedVirtualListingShardReferenceV1::fetchedAtEpochMillis)
        if (
            currentTimestamp != null &&
            currentTimestamp <= nowEpochMillis &&
            currentTimestamp >= fetchedAtEpochMillis
        ) {
            return false
        }
        save(
            accountId,
            index.copy(
                virtualListings = index.virtualListings.filterNot { it.path == normalized } +
                    CachedVirtualListingV1(
                        path = normalized,
                        fetchedAtEpochMillis = fetchedAtEpochMillis,
                        nodes = nodes.map(CachedVirtualFileNodeV1::fromDomain),
                        freshAtEpochMillis = freshAtEpochMillis,
                    ),
                virtualListingShards = index.virtualListingShards.filterNot { reference ->
                    reference.path == normalized
                },
            ),
        )
        return true
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
                        listing.path.isEmpty() ||
                        listing.path == normalized ||
                        listing.path.startsWith("$normalized/") ||
                        listing.path == parent ||
                        normalized.startsWith("${listing.path}/")
                },
                virtualListings = index.virtualListings.filterNot { listing ->
                    normalized.isEmpty() ||
                        listing.path.isEmpty() ||
                        listing.path == normalized ||
                        listing.path.startsWith("$normalized/") ||
                        listing.path == parent ||
                        normalized.startsWith("${listing.path}/")
                },
                listingShards = index.listingShards.filterNot { reference ->
                    normalized.isEmpty() ||
                        reference.path.isEmpty() ||
                        reference.path == normalized ||
                        reference.path.startsWith("$normalized/") ||
                        reference.path == parent ||
                        normalized.startsWith("${reference.path}/")
                },
                virtualListingShards = index.virtualListingShards.filterNot { reference ->
                    normalized.isEmpty() ||
                        reference.path.isEmpty() ||
                        reference.path == normalized ||
                        reference.path.startsWith("$normalized/") ||
                        reference.path == parent ||
                        normalized.startsWith("${reference.path}/")
                },
                content = index.content.filterNot { it in removed },
            ),
        )
    }

    private fun CacheIndexV1.bounded(): CacheIndexV1 {
        val perTypeReserve = minOf(MAX_FILES_PER_LISTING, maximumTotalMetadataEntries / 2)
        var retainedMetadataEntries = 0
        val ordinary = listings.sortedByDescending(CachedListingV1::fetchedAtEpochMillis).take(MAX_LISTINGS)
        val virtual = virtualListings.sortedByDescending(CachedVirtualListingV1::fetchedAtEpochMillis)
            .take(MAX_LISTINGS)
        val boundedListings = mutableListOf<CachedListingV1>()
        val boundedVirtualListings = mutableListOf<CachedVirtualListingV1>()
        var ordinaryIndex = 0
        var virtualIndex = 0
        var ordinaryEntries = 0
        var virtualEntries = 0
        while (ordinaryIndex < ordinary.size) {
            val candidate = ordinary[ordinaryIndex]
            if (ordinaryEntries + candidate.files.size > perTypeReserve) break
            boundedListings += candidate
            ordinaryEntries += candidate.files.size
            retainedMetadataEntries += candidate.files.size
            ordinaryIndex += 1
        }
        while (virtualIndex < virtual.size) {
            val candidate = virtual[virtualIndex]
            if (virtualEntries + candidate.nodes.size > perTypeReserve) break
            boundedVirtualListings += candidate
            virtualEntries += candidate.nodes.size
            retainedMetadataEntries += candidate.nodes.size
            virtualIndex += 1
        }
        while (ordinaryIndex < ordinary.size || virtualIndex < virtual.size) {
            val nextOrdinary = ordinary.getOrNull(ordinaryIndex)
            val nextVirtual = virtual.getOrNull(virtualIndex)
            val chooseOrdinary = nextVirtual == null ||
                nextOrdinary != null && nextOrdinary.fetchedAtEpochMillis >= nextVirtual.fetchedAtEpochMillis
            if (chooseOrdinary) {
                val candidate = requireNotNull(nextOrdinary)
                if (candidate.files.size <= maximumTotalMetadataEntries - retainedMetadataEntries) {
                    boundedListings += candidate
                    retainedMetadataEntries += candidate.files.size
                }
            } else {
                val candidate = requireNotNull(nextVirtual)
                if (candidate.nodes.size <= maximumTotalMetadataEntries - retainedMetadataEntries) {
                    boundedVirtualListings += candidate
                    retainedMetadataEntries += candidate.nodes.size
                }
            }
            if (chooseOrdinary) ordinaryIndex += 1 else virtualIndex += 1
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
        val hydratedOrdinaryPaths = listings.mapTo(hashSetOf(), CachedListingV1::path)
        val hydratedVirtualPaths = virtualListings.mapTo(hashSetOf(), CachedVirtualListingV1::path)
        val unhydratedGroups = buildList {
            listingShards.asSequence().filter { reference -> reference.path !in hydratedOrdinaryPaths }
                .groupBy(CachedListingShardReferenceV1::path)
                .forEach { (path, references) ->
                    add(
                        MetadataShardGroup(
                            ordinary = true,
                            path = path,
                            fetchedAtEpochMillis = references.maxOf(CachedListingShardReferenceV1::fetchedAtEpochMillis),
                            entryCount = references.sumOf(CachedListingShardReferenceV1::entryCount),
                        ),
                    )
                }
            virtualListingShards.asSequence().filter { reference -> reference.path !in hydratedVirtualPaths }
                .groupBy(CachedVirtualListingShardReferenceV1::path)
                .forEach { (path, references) ->
                    add(
                        MetadataShardGroup(
                            ordinary = false,
                            path = path,
                            fetchedAtEpochMillis =
                                references.maxOf(CachedVirtualListingShardReferenceV1::fetchedAtEpochMillis),
                            entryCount = references.sumOf(CachedVirtualListingShardReferenceV1::entryCount),
                        ),
                    )
                }
        }.sortedByDescending(MetadataShardGroup::fetchedAtEpochMillis)
        var remainingMetadataEntries = maximumTotalMetadataEntries - retainedMetadataEntries
        val retainedUnhydratedOrdinaryPaths = hashSetOf<String>()
        val retainedUnhydratedVirtualPaths = hashSetOf<String>()
        unhydratedGroups.forEach { group ->
            if (group.entryCount <= remainingMetadataEntries) {
                if (group.ordinary) retainedUnhydratedOrdinaryPaths += group.path
                else retainedUnhydratedVirtualPaths += group.path
                remainingMetadataEntries -= group.entryCount
            }
        }
        return copy(
            listings = boundedListings,
            virtualListings = boundedVirtualListings,
            content = retainedContent,
            listingShards = listingShards.filter { reference ->
                reference.path in retainedUnhydratedOrdinaryPaths || boundedListings.any { listing ->
                    listing.path == reference.path &&
                        listing.fetchedAtEpochMillis == reference.fetchedAtEpochMillis
                }
            },
            virtualListingShards = virtualListingShards.filter { reference ->
                reference.path in retainedUnhydratedVirtualPaths || boundedVirtualListings.any { listing ->
                    listing.path == reference.path &&
                        listing.fetchedAtEpochMillis == reference.fetchedAtEpochMillis
                }
            },
        )
    }

    private fun CacheIndexV1.persistMetadataShards(directory: File): CacheIndexV1 {
        val hydratedOrdinaryPaths = listings.mapTo(hashSetOf(), CachedListingV1::path)
        val ordinaryReferences = listings.flatMap { listing ->
            reusableListingShards(directory, listing)?.let { return@flatMap it }
            val chunks = listing.files.metadataChunks(
                emptyPayloadBytes = cacheJson.encodeToString(
                    CachedListingShardV1(
                        path = listing.path,
                        fetchedAtEpochMillis = listing.fetchedAtEpochMillis,
                        partIndex = MAX_METADATA_SHARDS_PER_LISTING - 1,
                        partCount = MAX_METADATA_SHARDS_PER_LISTING,
                        files = emptyList(),
                    ),
                ).encodeToByteArray().size.toLong(),
                encodedEntryBytes = { file ->
                    cacheJson.encodeToString(file).encodeToByteArray().size.toLong()
                },
            )
            chunks.mapIndexed { partIndex, files ->
                val payload = CachedListingShardV1(
                    path = listing.path,
                    fetchedAtEpochMillis = listing.fetchedAtEpochMillis,
                    partIndex = partIndex,
                    partCount = chunks.size,
                    files = files,
                )
                val encoded = cacheJson.encodeToString(payload).encodeToByteArray()
                val (blobName, sha256) = publishMetadataShard(directory, encoded)
                CachedListingShardReferenceV1(
                    path = listing.path,
                    fetchedAtEpochMillis = listing.fetchedAtEpochMillis,
                    partIndex = partIndex,
                    partCount = chunks.size,
                    entryCount = files.size,
                    blobName = blobName,
                    sha256 = sha256,
                )
            }
        } + listingShards.filter { reference -> reference.path !in hydratedOrdinaryPaths }
        val hydratedVirtualPaths = virtualListings.mapTo(hashSetOf(), CachedVirtualListingV1::path)
        val virtualReferences = virtualListings.flatMap { listing ->
            reusableVirtualListingShards(directory, listing)?.let { return@flatMap it }
            val chunks = listing.nodes.metadataChunks(
                emptyPayloadBytes = cacheJson.encodeToString(
                    CachedVirtualListingShardV1(
                        path = listing.path,
                        fetchedAtEpochMillis = listing.fetchedAtEpochMillis,
                        partIndex = MAX_METADATA_SHARDS_PER_LISTING - 1,
                        partCount = MAX_METADATA_SHARDS_PER_LISTING,
                        nodes = emptyList(),
                        freshAtEpochMillis = listing.freshAtEpochMillis,
                    ),
                ).encodeToByteArray().size.toLong(),
                encodedEntryBytes = { node ->
                    cacheJson.encodeToString(node).encodeToByteArray().size.toLong()
                },
            )
            chunks.mapIndexed { partIndex, nodes ->
                val payload = CachedVirtualListingShardV1(
                    path = listing.path,
                    fetchedAtEpochMillis = listing.fetchedAtEpochMillis,
                    partIndex = partIndex,
                    partCount = chunks.size,
                    nodes = nodes,
                    freshAtEpochMillis = listing.freshAtEpochMillis,
                )
                val encoded = cacheJson.encodeToString(payload).encodeToByteArray()
                val (blobName, sha256) = publishMetadataShard(directory, encoded)
                CachedVirtualListingShardReferenceV1(
                    path = listing.path,
                    fetchedAtEpochMillis = listing.fetchedAtEpochMillis,
                    partIndex = partIndex,
                    partCount = chunks.size,
                    entryCount = nodes.size,
                    blobName = blobName,
                    sha256 = sha256,
                    freshAtEpochMillis = listing.freshAtEpochMillis,
                )
            }
        } + virtualListingShards.filter { reference -> reference.path !in hydratedVirtualPaths }
        return copy(
            listings = emptyList(),
            virtualListings = emptyList(),
            listingShards = ordinaryReferences,
            virtualListingShards = virtualReferences,
        ).also { persisted -> persisted.requireValid() }
    }

    /**
     * Retains complete metadata listings while their references fit the independently bounded
     * account index. Reference paths are repeated in every shard, so entry-count bounds alone do
     * not constrain the serialized index when a large listing has a long path.
     */
    private fun CacheIndexV1.fitMetadataReferencesToIndexBudget(): CacheIndexV1 {
        val withoutMetadata = copy(
            listingShards = emptyList(),
            virtualListingShards = emptyList(),
        )
        val baseBytes = cacheJson.encodeToString(withoutMetadata).encodeToByteArray().size.toLong()
        require(baseBytes <= maximumIndexBytes) { "The Files cache index is too large." }
        var remainingBytes = maximumIndexBytes - baseBytes
        val retainedOrdinary = mutableListOf<CachedListingShardReferenceV1>()
        val retainedVirtual = mutableListOf<CachedVirtualListingShardReferenceV1>()
        val groups = buildList {
            listingShards.groupBy(CachedListingShardReferenceV1::path).forEach { (_, references) ->
                add(
                    MetadataShardIndexGroup(
                        fetchedAtEpochMillis = references.maxOf(CachedListingShardReferenceV1::fetchedAtEpochMillis),
                        ordinary = references.requireCompleteShardSet(),
                    ),
                )
            }
            virtualListingShards.groupBy(CachedVirtualListingShardReferenceV1::path).forEach { (_, references) ->
                add(
                    MetadataShardIndexGroup(
                        fetchedAtEpochMillis =
                            references.maxOf(CachedVirtualListingShardReferenceV1::fetchedAtEpochMillis),
                        virtual = references.requireCompleteVirtualShardSet(),
                    ),
                )
            }
        }.sortedByDescending(MetadataShardIndexGroup::fetchedAtEpochMillis)
        groups.forEach { group ->
            val encodedBytes = when {
                group.ordinary.isNotEmpty() -> group.ordinary.encodedArrayAdditionBytes(
                    remainingBytes = remainingBytes,
                    arrayAlreadyHasEntries = retainedOrdinary.isNotEmpty(),
                    encode = { reference -> cacheJson.encodeToString(reference) },
                )
                else -> group.virtual.encodedArrayAdditionBytes(
                    remainingBytes = remainingBytes,
                    arrayAlreadyHasEntries = retainedVirtual.isNotEmpty(),
                    encode = { reference -> cacheJson.encodeToString(reference) },
                )
            } ?: return@forEach
            remainingBytes -= encodedBytes
            retainedOrdinary += group.ordinary
            retainedVirtual += group.virtual
        }
        return withoutMetadata.copy(
            listingShards = retainedOrdinary,
            virtualListingShards = retainedVirtual,
        ).also { bounded ->
            bounded.requireValid()
            require(cacheJson.encodeToString(bounded).encodeToByteArray().size.toLong() <= maximumIndexBytes)
        }
    }

    private fun <T> List<T>.encodedArrayAdditionBytes(
        remainingBytes: Long,
        arrayAlreadyHasEntries: Boolean,
        encode: (T) -> String,
    ): Long? {
        var total = 0L
        forEachIndexed { index, entry ->
            val separatorBytes = if (arrayAlreadyHasEntries || index > 0) JSON_ARRAY_SEPARATOR_BYTES else 0L
            val entryBytes = encode(entry).encodeToByteArray().size.toLong()
            if (separatorBytes + entryBytes > remainingBytes - total) return null
            total += separatorBytes + entryBytes
        }
        return total
    }

    private fun CacheIndexV1.reusableListingShards(
        directory: File,
        listing: CachedListingV1,
    ): List<CachedListingShardReferenceV1>? {
        val references = listingShards.filter { reference ->
            reference.path == listing.path &&
                reference.fetchedAtEpochMillis == listing.fetchedAtEpochMillis
        }
        val ordered = runCatching { references.requireCompleteShardSet() }.getOrNull() ?: return null
        if (ordered.sumOf(CachedListingShardReferenceV1::entryCount) != listing.files.size) return null
        return ordered.takeIf { shards -> shards.all { reference -> reference.isAvailableIn(directory) } }
    }

    private fun CacheIndexV1.reusableVirtualListingShards(
        directory: File,
        listing: CachedVirtualListingV1,
    ): List<CachedVirtualListingShardReferenceV1>? {
        val references = virtualListingShards.filter { reference ->
            reference.path == listing.path &&
                reference.fetchedAtEpochMillis == listing.fetchedAtEpochMillis &&
                reference.freshAtEpochMillis == listing.freshAtEpochMillis
        }
        val ordered = runCatching { references.requireCompleteVirtualShardSet() }.getOrNull() ?: return null
        if (ordered.sumOf(CachedVirtualListingShardReferenceV1::entryCount) != listing.nodes.size) return null
        return ordered.takeIf { shards -> shards.all { reference -> reference.isAvailableIn(directory) } }
    }

    private fun CachedListingShardReferenceV1.isAvailableIn(directory: File): Boolean =
        File(directory, blobName).let { shard -> shard.isFile && shard.length() in 1L..MAX_METADATA_SHARD_BYTES }

    private fun CachedVirtualListingShardReferenceV1.isAvailableIn(directory: File): Boolean =
        File(directory, blobName).let { shard -> shard.isFile && shard.length() in 1L..MAX_METADATA_SHARD_BYTES }

    private fun CacheIndexV1.hydrateMetadataShards(directory: File): CacheIndexV1 {
        val hydrationBudget = MetadataHydrationBudget(maximumHydratedMetadataBytes)
        val hydratedListings = mutableListOf<CachedListingV1>()
        val invalidListingPaths = mutableSetOf<String>()
        listingShards.groupBy { reference -> reference.path to reference.fetchedAtEpochMillis }
            .forEach { (key, references) ->
                try {
                    hydratedListings += hydrateListingReferences(directory, references, hydrationBudget)
                } catch (_: MetadataHydrationBudgetExceededException) {
                    // Keep valid-looking references for demand loading without growing startup memory.
                } catch (_: Exception) {
                    invalidListingPaths += key.first
                }
            }
        val hydratedVirtualListings = mutableListOf<CachedVirtualListingV1>()
        val invalidVirtualListingPaths = mutableSetOf<String>()
        virtualListingShards.groupBy { reference -> reference.path to reference.fetchedAtEpochMillis }
            .forEach { (key, references) ->
                try {
                    hydratedVirtualListings += hydrateVirtualListingReferences(directory, references, hydrationBudget)
                } catch (_: MetadataHydrationBudgetExceededException) {
                    // Keep valid-looking references for demand loading without growing startup memory.
                } catch (_: Exception) {
                    invalidVirtualListingPaths += key.first
                }
            }
        val hydratedPaths = hydratedListings.mapTo(hashSetOf(), CachedListingV1::path)
        val hydratedVirtualPaths = hydratedVirtualListings.mapTo(hashSetOf(), CachedVirtualListingV1::path)
        return copy(
            listings = listings.filterNot { listing -> listing.path in hydratedPaths } + hydratedListings,
            virtualListings = virtualListings.filterNot { listing -> listing.path in hydratedVirtualPaths } +
                hydratedVirtualListings,
            listingShards = listingShards.filterNot { reference -> reference.path in invalidListingPaths },
            virtualListingShards = virtualListingShards.filterNot { reference ->
                reference.path in invalidVirtualListingPaths
            },
        )
    }

    private fun hydrateListingOnDemand(
        accountId: String,
        path: String,
        index: CacheIndexV1,
    ): CachedListingV1? {
        val references = index.listingShards.filter { reference -> reference.path == path }
        if (references.isEmpty()) return null
        return try {
            hydrateListingReferences(
                accountDirectory(accountId),
                references,
                MetadataHydrationBudget.perShard(MAX_METADATA_SHARD_BYTES),
            ).also { listing ->
                val inlineListings = index.listings.filter { existing ->
                    index.listingShards.none { reference -> reference.path == existing.path }
                }
                val inlineVirtualListings = index.virtualListings.filter { existing ->
                    index.virtualListingShards.none { reference -> reference.path == existing.path }
                }
                rememberLoadedIndex(
                    accountId,
                    index.copy(
                        listings = inlineListings.filterNot { it.path == path } + listing,
                        virtualListings = inlineVirtualListings,
                    )
                        .bounded().requireValid(),
                )
            }
        } catch (_: MetadataHydrationBudgetExceededException) {
            null
        } catch (_: Exception) {
            rememberLoadedIndex(
                accountId,
                index.copy(listingShards = index.listingShards.filterNot { it.path == path })
                    .bounded().requireValid(),
            )
            null
        }
    }

    private fun hydrateVirtualListingOnDemand(
        accountId: String,
        path: String,
        index: CacheIndexV1,
    ): CachedVirtualListingV1? {
        val references = index.virtualListingShards.filter { reference -> reference.path == path }
        if (references.isEmpty()) return null
        return try {
            hydrateVirtualListingReferences(
                accountDirectory(accountId),
                references,
                MetadataHydrationBudget.perShard(MAX_METADATA_SHARD_BYTES),
            ).also { listing ->
                val inlineListings = index.listings.filter { existing ->
                    index.listingShards.none { reference -> reference.path == existing.path }
                }
                val inlineVirtualListings = index.virtualListings.filter { existing ->
                    index.virtualListingShards.none { reference -> reference.path == existing.path }
                }
                rememberLoadedIndex(
                    accountId,
                    index.copy(
                        listings = inlineListings,
                        virtualListings = inlineVirtualListings.filterNot { it.path == path } + listing,
                    ).bounded().requireValid(),
                )
            }
        } catch (_: MetadataHydrationBudgetExceededException) {
            null
        } catch (_: Exception) {
            rememberLoadedIndex(
                accountId,
                index.copy(
                    virtualListingShards = index.virtualListingShards.filterNot { it.path == path },
                ).bounded().requireValid(),
            )
            null
        }
    }

    private fun hydrateListingReferences(
        directory: File,
        references: List<CachedListingShardReferenceV1>,
        hydrationBudget: MetadataHydrationBudget,
    ): CachedListingV1 {
        val ordered = references.requireCompleteShardSet()
        val files = ordered.flatMap { reference ->
            val payload = loadMetadataShard<CachedListingShardV1>(
                directory,
                reference.blobName,
                reference.sha256,
                hydrationBudget,
            )
            require(
                payload.path == reference.path &&
                    payload.fetchedAtEpochMillis == reference.fetchedAtEpochMillis &&
                    payload.partIndex == reference.partIndex &&
                    payload.partCount == reference.partCount &&
                    payload.files.size == reference.entryCount,
            ) { "A Files metadata shard does not match its index reference." }
            payload.files
        }
        return CachedListingV1(ordered.first().path, ordered.first().fetchedAtEpochMillis, files)
    }

    private fun hydrateVirtualListingReferences(
        directory: File,
        references: List<CachedVirtualListingShardReferenceV1>,
        hydrationBudget: MetadataHydrationBudget,
    ): CachedVirtualListingV1 {
        val ordered = references.requireCompleteVirtualShardSet()
        val nodes = ordered.flatMap { reference ->
            val payload = loadMetadataShard<CachedVirtualListingShardV1>(
                directory,
                reference.blobName,
                reference.sha256,
                hydrationBudget,
            )
            require(
                payload.path == reference.path &&
                    payload.fetchedAtEpochMillis == reference.fetchedAtEpochMillis &&
                    payload.freshAtEpochMillis == reference.freshAtEpochMillis &&
                    payload.partIndex == reference.partIndex &&
                    payload.partCount == reference.partCount &&
                    payload.nodes.size == reference.entryCount,
            ) { "A virtual Files metadata shard does not match its index reference." }
            payload.nodes
        }
        return CachedVirtualListingV1(
            path = ordered.first().path,
            fetchedAtEpochMillis = ordered.first().fetchedAtEpochMillis,
            nodes = nodes,
            freshAtEpochMillis = ordered.first().freshAtEpochMillis,
        )
    }

    /**
     * Keeps both the record-count and encoded-byte limits before any shard reaches disk.
     *
     * [emptyPayloadBytes] is encoded with the largest permitted part numbers, so the final
     * payload envelope can only be the same size or smaller. JSON encodes the empty array as two
     * bytes; replacing it with independently encoded entries plus commas gives the exact array
     * contribution without repeatedly serializing a growing chunk.
     */
    private fun <T> List<T>.metadataChunks(
        emptyPayloadBytes: Long,
        encodedEntryBytes: (T) -> Long,
    ): List<List<T>> {
        require(emptyPayloadBytes in 2L..MAX_METADATA_SHARD_BYTES)
        if (isEmpty()) return listOf(emptyList())
        val payloadEnvelopeBytes = emptyPayloadBytes - EMPTY_JSON_ARRAY_BYTES
        val chunks = mutableListOf<List<T>>()
        var current = mutableListOf<T>()
        var currentEntriesBytes = 0L
        for (entry in this) {
            val entryBytes = encodedEntryBytes(entry)
            val separatorBytes = if (current.isEmpty()) 0L else JSON_ARRAY_SEPARATOR_BYTES
            val candidateBytes = payloadEnvelopeBytes + currentEntriesBytes + separatorBytes + entryBytes
            if (
                current.isNotEmpty() &&
                (current.size >= MAX_METADATA_SHARD_ENTRIES || candidateBytes > MAX_METADATA_SHARD_BYTES)
            ) {
                chunks += current
                current = mutableListOf()
                currentEntriesBytes = 0L
            }
            val nextSeparatorBytes = if (current.isEmpty()) 0L else JSON_ARRAY_SEPARATOR_BYTES
            require(payloadEnvelopeBytes + currentEntriesBytes + nextSeparatorBytes + entryBytes <=
                MAX_METADATA_SHARD_BYTES) {
                "One Files metadata record is too large to persist safely."
            }
            current += entry
            currentEntriesBytes += nextSeparatorBytes + entryBytes
        }
        chunks += current
        require(chunks.size <= MAX_METADATA_SHARDS_PER_LISTING)
        return chunks
    }

    private fun publishMetadataShard(directory: File, encoded: ByteArray): Pair<String, String> {
        require(encoded.size.toLong() <= MAX_METADATA_SHARD_BYTES) { "A Files metadata shard is too large." }
        val sha256 = sha256Hex(encoded)
        val blobName = "$sha256.$METADATA_SHARD_EXTENSION"
        val current = File(directory, blobName)
        if (
            !current.isFile ||
            current.length() != encoded.size.toLong() ||
            metadataShardBytes(current).let(::sha256Hex) != sha256
        ) {
            publishBytes(directory, blobName, encoded)
        }
        return blobName to sha256
    }

    private inline fun <reified T> loadMetadataShard(
        directory: File,
        blobName: String,
        expectedSha256: String,
        hydrationBudget: MetadataHydrationBudget,
    ): T {
        val shard = File(directory, blobName)
        require(shard.isFile && shard.length() in 1L..MAX_METADATA_SHARD_BYTES) {
            "A Files metadata shard is missing or too large."
        }
        hydrationBudget.reserve(shard.length())
        val bytes = metadataShardBytes(shard)
        require(sha256Hex(bytes) == expectedSha256) { "A Files metadata shard failed integrity validation." }
        return cacheJson.decodeFromString(bytes.toString(Charsets.UTF_8))
    }

    private fun metadataShardBytes(shard: File): ByteArray {
        metadataShardReadObserver(shard)
        return shard.readBytes()
    }

    private fun List<CachedListingShardReferenceV1>.requireCompleteShardSet(): List<CachedListingShardReferenceV1> {
        val ordered = sortedBy(CachedListingShardReferenceV1::partIndex)
        val partCount = ordered.first().partCount
        require(ordered.size == partCount && ordered.map(CachedListingShardReferenceV1::partIndex) == (0 until partCount).toList())
        require(ordered.all { reference -> reference.partCount == partCount })
        return ordered
    }

    private fun List<CachedVirtualListingShardReferenceV1>.requireCompleteVirtualShardSet(): List<CachedVirtualListingShardReferenceV1> {
        val ordered = sortedBy(CachedVirtualListingShardReferenceV1::partIndex)
        val partCount = ordered.first().partCount
        require(
            ordered.size == partCount &&
                ordered.map(CachedVirtualListingShardReferenceV1::partIndex) == (0 until partCount).toList(),
        )
        require(ordered.all { reference -> reference.partCount == partCount })
        return ordered
    }

    private fun load(accountId: String): CacheIndexV1 {
        loadedIndexes[accountId]?.let { return it }
        val directory = accountDirectory(accountId)
        val indexFile = File(directory, INDEX_FILE_NAME)
        val loaded = if (!indexFile.isFile || indexFile.length() !in 1..maximumIndexBytes) {
            CacheIndexV1()
        } else {
            runCatching {
                val decoded = cacheJson.decodeFromString<CacheIndexV1>(indexFile.readText(Charsets.UTF_8))
                decoded.requireValid()
                    .hydrateMetadataShards(directory)
                    .requireValid()
                    .bounded()
            }.getOrElse { CacheIndexV1() }
        }
        rememberLoadedIndex(accountId, loaded)
        return loaded
    }

    private fun save(accountId: String, index: CacheIndexV1) {
        val directory = accountDirectory(accountId).apply {
            check(isDirectory || mkdirs()) { "Could not create the desktop Files cache." }
        }
        val bounded = index.bounded()
        val persisted = bounded.persistMetadataShards(directory).fitMetadataReferencesToIndexBudget()
        val encoded = cacheJson.encodeToString(persisted).encodeToByteArray()
        require(encoded.size.toLong() <= maximumIndexBytes) { "The Files cache index is too large." }
        publishBytes(directory, INDEX_FILE_NAME, encoded)
        val persistedOrdinaryPaths = persisted.listingShards.mapTo(hashSetOf(), CachedListingShardReferenceV1::path)
        val persistedVirtualPaths =
            persisted.virtualListingShards.mapTo(hashSetOf(), CachedVirtualListingShardReferenceV1::path)
        rememberLoadedIndex(
            accountId,
            bounded.copy(
                listings = bounded.listings.filter { listing -> listing.path in persistedOrdinaryPaths },
                virtualListings = bounded.virtualListings.filter { listing -> listing.path in persistedVirtualPaths },
                listingShards = bounded.listingShards.filter { reference -> reference.path in persistedOrdinaryPaths },
                virtualListingShards = bounded.virtualListingShards.filter { reference ->
                    reference.path in persistedVirtualPaths
                },
            ),
        )
        val referenced = bounded.content.mapTo(hashSetOf(), CachedContentV1::blobName)
        directory.listFiles().orEmpty()
            .filter { file -> file.isFile && file.extension == "blob" && file.name !in referenced }
            .forEach(File::delete)
        val referencedMetadata = buildSet {
            persisted.listingShards.mapTo(this, CachedListingShardReferenceV1::blobName)
            persisted.virtualListingShards.mapTo(this, CachedVirtualListingShardReferenceV1::blobName)
        }
        directory.listFiles().orEmpty()
            .filter { file -> file.isFile && file.extension == METADATA_SHARD_EXTENSION && file.name !in referencedMetadata }
            .forEach(File::delete)
    }

    private fun rememberLoadedIndex(accountId: String, index: CacheIndexV1) {
        loadedIndexes[accountId] = index
        while (loadedIndexes.size > maximumLoadedAccountIndexes) {
            loadedIndexes.entries.iterator().apply {
                next()
                remove()
            }
        }
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
        require(index.virtualListings.size <= MAX_LISTINGS)
        require(index.listingShards.size <= MAX_METADATA_SHARDS)
        require(index.virtualListingShards.size <= MAX_METADATA_SHARDS)
        require(index.content.size <= MAX_CONTENT_ENTRIES)
        require(index.listings.map(CachedListingV1::path).distinct().size == index.listings.size)
        require(index.virtualListings.map(CachedVirtualListingV1::path).distinct().size == index.virtualListings.size)
        require(index.content.map(CachedContentV1::path).distinct().size == index.content.size)
        require(
            index.listingShards.map { reference ->
                "${reference.path}\u0000${reference.fetchedAtEpochMillis}\u0000${reference.partIndex}"
            }.distinct().size == index.listingShards.size,
        )
        require(
            index.virtualListingShards.map { reference ->
                "${reference.path}\u0000${reference.fetchedAtEpochMillis}\u0000${reference.partIndex}"
            }.distinct().size == index.virtualListingShards.size,
        )
        require(index.listingShards.groupBy(CachedListingShardReferenceV1::path).all { (_, references) ->
            references.map(CachedListingShardReferenceV1::fetchedAtEpochMillis).distinct().size == 1
        })
        require(index.virtualListingShards.groupBy(CachedVirtualListingShardReferenceV1::path).all { (_, references) ->
            references.map { reference ->
                reference.fetchedAtEpochMillis to reference.freshAtEpochMillis
            }.distinct().size == 1
        })
        val ordinaryEntries = mutableMapOf<String, Long>()
        index.listings.forEach { listing -> ordinaryEntries[listing.path] = listing.files.size.toLong() }
        index.listingShards.groupBy(CachedListingShardReferenceV1::path).forEach { (path, references) ->
            ordinaryEntries[path] = maxOf(
                ordinaryEntries[path] ?: 0L,
                references.sumOf { reference -> reference.entryCount.toLong() },
            )
        }
        val virtualEntries = mutableMapOf<String, Long>()
        index.virtualListings.forEach { listing -> virtualEntries[listing.path] = listing.nodes.size.toLong() }
        index.virtualListingShards.groupBy(CachedVirtualListingShardReferenceV1::path).forEach { (path, references) ->
            virtualEntries[path] = maxOf(
                virtualEntries[path] ?: 0L,
                references.sumOf { reference -> reference.entryCount.toLong() },
            )
        }
        require(ordinaryEntries.values.sum() + virtualEntries.values.sum() <= maximumTotalMetadataEntries.toLong())
        index.listings.forEach { listing ->
            require(listing.path.cachePath() == listing.path)
            require(listing.fetchedAtEpochMillis >= 0L)
            require(listing.files.size <= MAX_FILES_PER_LISTING)
            listing.files.forEach(::requireValidCachedFile)
        }
        index.virtualListings.forEach { listing ->
            require(listing.path.cachePath() == listing.path)
            require(listing.fetchedAtEpochMillis >= 0L)
            require(listing.freshAtEpochMillis >= listing.fetchedAtEpochMillis)
            require(listing.nodes.size <= MAX_FILES_PER_LISTING)
            listing.nodes.forEach { node -> requireValidVirtualNode(node.toDomain()) }
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
        index.listingShards.forEach { reference -> reference.requireValid() }
        index.virtualListingShards.forEach { reference -> reference.requireValid() }
    }

    private fun CachedListingShardReferenceV1.requireValid() {
        path.cachePath()
        require(fetchedAtEpochMillis >= 0L)
        require(partCount in 1..MAX_METADATA_SHARDS_PER_LISTING && partIndex in 0 until partCount)
        require(entryCount in 0..MAX_METADATA_SHARD_ENTRIES)
        require(blobName == "$sha256.$METADATA_SHARD_EXTENSION" && sha256.isSha256Hex())
    }

    private fun CachedVirtualListingShardReferenceV1.requireValid() {
        path.cachePath()
        require(fetchedAtEpochMillis >= 0L)
        require(freshAtEpochMillis >= fetchedAtEpochMillis)
        require(partCount in 1..MAX_METADATA_SHARDS_PER_LISTING && partIndex in 0 until partCount)
        require(entryCount in 0..MAX_METADATA_SHARD_ENTRIES)
        require(blobName == "$sha256.$METADATA_SHARD_EXTENSION" && sha256.isSha256Hex())
    }

    private fun requireValidVirtualNode(node: LinuxVirtualFileNode) {
        require(node.path.cachePath() == node.path)
        require(node.name.length <= MAX_FILE_NAME_LENGTH && node.name.none(Char::isISOControl))
        require(node.remoteRevision.isNotBlank() && node.remoteRevision.length <= MAX_ETAG_LENGTH)
        require(node.remoteRevision.none(Char::isISOControl))
        require(node.size >= 0L)
    }

    private fun requireValidCachedFile(file: NextcloudFile) {
        require(file.path.cachePath() == file.path) { "A cached file path is not canonical." }
        require(file.name.length <= MAX_FILE_NAME_LENGTH && file.name.none(Char::isISOControl))
        require(
            file.mimeType == null ||
                file.mimeType.length <= MAX_MIME_TYPE_LENGTH && file.mimeType.none(Char::isISOControl),
        )
        require(
            file.lastModified == null ||
                file.lastModified.length <= MAX_LAST_MODIFIED_LENGTH && file.lastModified.none(Char::isISOControl),
        )
        require(file.etag == null || file.etag.length <= MAX_ETAG_LENGTH && file.etag.none(Char::isISOControl))
        require(
            file.permissions == null ||
                file.permissions.length <= MAX_PERMISSIONS_LENGTH && file.permissions.none(Char::isISOControl),
        )
        require(
            file.ownerId == null ||
                file.ownerId.length <= MAX_FILE_NAME_LENGTH && file.ownerId.none(Char::isISOControl),
        )
        require(
            file.ownerDisplayName == null ||
                file.ownerDisplayName.length <= MAX_FILE_NAME_LENGTH && file.ownerDisplayName.none(Char::isISOControl),
        )
        require(file.unreadComments in 0..1_000_000)
        require(file.checksums.size <= MAX_CHECKSUMS)
        require(file.checksums.all { checksum ->
            checksum.length <= MAX_CHECKSUM_LENGTH && checksum.none(Char::isISOControl)
        })
        require(file.directoryPreviewFileIds.size <= MAX_DIRECTORY_PREVIEW_FILE_IDS)
        require(file.size == null || file.size >= 0L)
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
        const val MAX_INDEX_BYTES = 64L * 1024L * 1024L
        const val MAX_LISTINGS = 256
        const val MAX_FILES_PER_LISTING = 50_000
        const val MAX_TOTAL_METADATA_ENTRIES = 250_000
        const val MAX_METADATA_SHARD_ENTRIES = 256
        // A valid maximum-size record may occupy its own byte-bounded shard. The entry and index
        // byte budgets remain the authoritative bounds; do not assume a minimum record density.
        const val MAX_METADATA_SHARDS_PER_LISTING = MAX_FILES_PER_LISTING
        const val MAX_METADATA_SHARDS = MAX_TOTAL_METADATA_ENTRIES + MAX_LISTINGS * 2
        const val MAX_METADATA_SHARD_BYTES = 4L * 1024L * 1024L
        const val MAX_HYDRATED_METADATA_BYTES = 32L * 1024L * 1024L
        const val METADATA_SHARD_EXTENSION = "metadata"
        const val MAX_CONTENT_ENTRIES = 256
        const val MAX_FILE_NAME_LENGTH = 1_024
        const val MAX_ETAG_LENGTH = 4_096
        const val MAX_MIME_TYPE_LENGTH = 512
        const val MAX_LAST_MODIFIED_LENGTH = 128
        const val MAX_PERMISSIONS_LENGTH = 256
        const val MAX_CHECKSUMS = 16
        const val MAX_CHECKSUM_LENGTH = 512
        const val MAX_DIRECTORY_PREVIEW_FILE_IDS = 64
        const val DEFAULT_MAXIMUM_ENTRY_BYTES = 512L * 1024L * 1024L
        const val DEFAULT_MAXIMUM_CONTENT_BYTES = 256L * 1024L * 1024L * 1024L
        const val DEFAULT_MAXIMUM_LOADED_ACCOUNT_INDEXES = 4
        const val KEY_AUTOMATIC_CLEANUP = "automatic-cleanup"
        const val KEY_MAXIMUM_CACHE_BYTES = "maximum-cache-bytes"
        const val KEY_MINIMUM_FREE_BYTES = "minimum-free-bytes"
        const val KEY_UNUSED_FILE_AGE = "unused-file-age"
        const val UNLIMITED_SENTINEL = -1L
        const val EMPTY_JSON_ARRAY_BYTES = 2L
        const val JSON_ARRAY_SEPARATOR_BYTES = 1L
    }
}

private data class MetadataShardIndexGroup(
    val fetchedAtEpochMillis: Long,
    val ordinary: List<CachedListingShardReferenceV1> = emptyList(),
    val virtual: List<CachedVirtualListingShardReferenceV1> = emptyList(),
) {
    init {
        require(ordinary.isEmpty() != virtual.isEmpty())
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

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

private fun String.isSha256Hex(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

@Serializable
private data class CacheIndexV1(
    val version: Int = 1,
    val listings: List<CachedListingV1> = emptyList(),
    val virtualListings: List<CachedVirtualListingV1> = emptyList(),
    val content: List<CachedContentV1> = emptyList(),
    val listingShards: List<CachedListingShardReferenceV1> = emptyList(),
    val virtualListingShards: List<CachedVirtualListingShardReferenceV1> = emptyList(),
)

@Serializable
private data class CachedListingV1(
    val path: String,
    val fetchedAtEpochMillis: Long,
    val files: List<NextcloudFile>,
)

@Serializable
private data class CachedVirtualListingV1(
    val path: String,
    val fetchedAtEpochMillis: Long,
    val nodes: List<CachedVirtualFileNodeV1>,
    val freshAtEpochMillis: Long = fetchedAtEpochMillis,
)

@Serializable
private data class CachedListingShardReferenceV1(
    val path: String,
    val fetchedAtEpochMillis: Long,
    val partIndex: Int,
    val partCount: Int,
    val entryCount: Int,
    val blobName: String,
    val sha256: String,
)

@Serializable
private data class CachedVirtualListingShardReferenceV1(
    val path: String,
    val fetchedAtEpochMillis: Long,
    val partIndex: Int,
    val partCount: Int,
    val entryCount: Int,
    val blobName: String,
    val sha256: String,
    val freshAtEpochMillis: Long = fetchedAtEpochMillis,
)

@Serializable
private data class CachedListingShardV1(
    val path: String,
    val fetchedAtEpochMillis: Long,
    val partIndex: Int,
    val partCount: Int,
    val files: List<NextcloudFile>,
)

@Serializable
private data class CachedVirtualListingShardV1(
    val path: String,
    val fetchedAtEpochMillis: Long,
    val partIndex: Int,
    val partCount: Int,
    val nodes: List<CachedVirtualFileNodeV1>,
    val freshAtEpochMillis: Long = fetchedAtEpochMillis,
)

@Serializable
private data class CachedVirtualFileNodeV1(
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long,
    val remoteRevision: String,
) {
    fun toDomain(): LinuxVirtualFileNode = LinuxVirtualFileNode(path, name, directory, size, remoteRevision)

    companion object {
        fun fromDomain(node: LinuxVirtualFileNode): CachedVirtualFileNodeV1 = CachedVirtualFileNodeV1(
            path = node.path,
            name = node.name,
            directory = node.directory,
            size = node.size,
            remoteRevision = node.remoteRevision,
        )
    }
}

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

private data class MetadataShardGroup(
    val ordinary: Boolean,
    val path: String,
    val fetchedAtEpochMillis: Long,
    val entryCount: Int,
)

private class MetadataHydrationBudget(
    private val maximumBytes: Long,
    private val cumulative: Boolean = true,
) {
    private var reservedBytes = 0L

    fun reserve(bytes: Long) {
        require(bytes > 0L)
        val alreadyReserved = if (cumulative) reservedBytes else 0L
        if (bytes > maximumBytes - alreadyReserved) throw MetadataHydrationBudgetExceededException()
        if (cumulative) reservedBytes += bytes
    }

    companion object {
        fun perShard(maximumBytes: Long): MetadataHydrationBudget =
            MetadataHydrationBudget(maximumBytes, cumulative = false)
    }
}

private class MetadataHydrationBudgetExceededException : IllegalStateException(
    "The Files metadata hydration budget was exceeded.",
)

private val cacheJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = false
}
