package dev.obiente.nextcloudnative.app

/**
 * Platform-independent policy and safety model for on-demand file content.
 *
 * A virtual file remains visible from remote metadata while its content may be absent locally.
 * Hydration, pinning, and eviction are intentionally separate from folder synchronization: cached
 * content is disposable, pinned content is durable offline data, and dirty synchronized content is
 * never a cache eviction candidate.
 */
enum class VirtualFileRetention {
    Automatic,
    Pinned,
}

enum class VirtualFileActivity {
    Idle,
    Hydrating,
    Uploading,
    Evicting,
    NeedsAttention,
}

data class VirtualFileCachePolicy(
    val automaticCleanup: Boolean = true,
    val maximumCacheBytes: Long? = DEFAULT_VIRTUAL_FILE_CACHE_BYTES,
    val minimumFreeSpaceBytes: Long = DEFAULT_VIRTUAL_FILE_MINIMUM_FREE_BYTES,
    val unusedFileAgeMillis: Long? = DEFAULT_VIRTUAL_FILE_UNUSED_AGE_MILLIS,
) {
    init {
        require(maximumCacheBytes == null || maximumCacheBytes > 0L) {
            "The virtual file cache budget must be positive."
        }
        require(minimumFreeSpaceBytes >= 0L) {
            "The minimum free-space reserve cannot be negative."
        }
        require(unusedFileAgeMillis == null || unusedFileAgeMillis > 0L) {
            "The unused-file age must be positive."
        }
    }
}

data class VirtualFileCacheEntry(
    val key: FileOfflineKey,
    val remoteRevision: String,
    val localRevision: String,
    val sizeBytes: Long,
    val cachedAtEpochMillis: Long,
    val lastAccessedAtEpochMillis: Long,
    val retention: VirtualFileRetention,
    val dirty: Boolean = false,
    val activeLeaseCount: Int = 0,
    val activity: VirtualFileActivity = VirtualFileActivity.Idle,
) {
    init {
        require(remoteRevision.isNotBlank()) { "A cached virtual file needs a remote revision." }
        require(localRevision.isNotBlank()) { "A cached virtual file needs a local revision." }
        require(sizeBytes >= 0L) { "A cached virtual file size cannot be negative." }
        require(cachedAtEpochMillis >= 0L) { "A virtual file cache timestamp cannot be negative." }
        require(lastAccessedAtEpochMillis >= cachedAtEpochMillis) {
            "A virtual file cannot be accessed before its cached generation exists."
        }
        require(activeLeaseCount >= 0) { "A virtual file lease count cannot be negative." }
    }

    val isEvictable: Boolean
        get() = retention == VirtualFileRetention.Automatic &&
            !dirty &&
            activeLeaseCount == 0 &&
            activity == VirtualFileActivity.Idle
}

enum class VirtualFileEvictionReason {
    CacheBudget,
    MinimumFreeSpace,
    UnusedAge,
    ManualFreeUp,
}

data class VirtualFileEviction(
    val key: FileOfflineKey,
    val expectedLocalRevision: String,
    val sizeBytes: Long,
    val reasons: Set<VirtualFileEvictionReason>,
) {
    init {
        require(expectedLocalRevision.isNotBlank())
        require(sizeBytes >= 0L)
        require(reasons.isNotEmpty())
    }
}

data class VirtualFileEvictionPlan(
    val evictions: List<VirtualFileEviction>,
    val cachedBytes: Long,
    val reclaimableBytes: Long,
    val plannedFreedBytes: Long,
    val requiredFreedBytes: Long,
    val unmetRequiredBytes: Long,
) {
    init {
        require(cachedBytes >= 0L)
        require(reclaimableBytes in 0L..cachedBytes)
        require(plannedFreedBytes in 0L..reclaimableBytes)
        require(requiredFreedBytes >= 0L)
        require(unmetRequiredBytes >= 0L)
        require(evictions.map(VirtualFileEviction::key).distinct().size == evictions.size)
    }
}

/**
 * Produces an immutable, revision-guarded eviction plan without touching storage.
 *
 * Age-expired files are selected first, then least-recently-used generations. Pinned, dirty,
 * open, transferring, evicting, and attention-required files are never selected. Executors must
 * still compare [VirtualFileEviction.expectedLocalRevision] immediately before deleting bytes.
 */
fun planVirtualFileEviction(
    entries: List<VirtualFileCacheEntry>,
    policy: VirtualFileCachePolicy,
    availableFreeBytes: Long,
    nowEpochMillis: Long,
    requestedBytesToFree: Long = 0L,
): VirtualFileEvictionPlan {
    require(availableFreeBytes >= 0L)
    require(nowEpochMillis >= 0L)
    require(requestedBytesToFree >= 0L)
    require(entries.map(VirtualFileCacheEntry::key).distinct().size == entries.size) {
        "The virtual file cache contains duplicate entries."
    }

    val cachedBytes = entries.saturatedSizeSum()
    val candidates = entries.filter(VirtualFileCacheEntry::isEvictable)
    val reclaimableBytes = candidates.saturatedSizeSum()
    val budgetDeficit = if (policy.automaticCleanup) {
        policy.maximumCacheBytes?.let { maximum -> (cachedBytes - maximum).coerceAtLeast(0L) } ?: 0L
    } else {
        0L
    }
    val freeSpaceDeficit = if (policy.automaticCleanup) {
        (policy.minimumFreeSpaceBytes - availableFreeBytes).coerceAtLeast(0L)
    } else {
        0L
    }
    val requiredBytes = maxOf(budgetDeficit, freeSpaceDeficit, requestedBytesToFree)
    val unusedCutoff = if (policy.automaticCleanup) {
        policy.unusedFileAgeMillis?.let { age -> (nowEpochMillis - age).coerceAtLeast(0L) }
    } else {
        null
    }

    val ordered = candidates.sortedWith(
        compareByDescending<VirtualFileCacheEntry> { entry ->
            unusedCutoff != null && entry.lastAccessedAtEpochMillis <= unusedCutoff
        }
            .thenBy(VirtualFileCacheEntry::lastAccessedAtEpochMillis)
            .thenByDescending(VirtualFileCacheEntry::sizeBytes)
            .thenBy(VirtualFileCacheEntry::key),
    )
    val selected = mutableListOf<VirtualFileEviction>()
    var plannedBytes = 0L
    ordered.forEach { entry ->
        val expired = unusedCutoff != null && entry.lastAccessedAtEpochMillis <= unusedCutoff
        if (!expired && plannedBytes >= requiredBytes) return@forEach
        val reasons = buildSet {
            if (expired) add(VirtualFileEvictionReason.UnusedAge)
            if (plannedBytes < budgetDeficit) add(VirtualFileEvictionReason.CacheBudget)
            if (plannedBytes < freeSpaceDeficit) add(VirtualFileEvictionReason.MinimumFreeSpace)
            if (plannedBytes < requestedBytesToFree) add(VirtualFileEvictionReason.ManualFreeUp)
        }
        if (reasons.isNotEmpty()) {
            selected += VirtualFileEviction(
                key = entry.key,
                expectedLocalRevision = entry.localRevision,
                sizeBytes = entry.sizeBytes,
                reasons = reasons,
            )
            plannedBytes = plannedBytes.saturatedPlus(entry.sizeBytes)
        }
    }

    return VirtualFileEvictionPlan(
        evictions = selected,
        cachedBytes = cachedBytes,
        reclaimableBytes = reclaimableBytes,
        plannedFreedBytes = plannedBytes,
        requiredFreedBytes = requiredBytes,
        unmetRequiredBytes = (requiredBytes - plannedBytes).coerceAtLeast(0L),
    )
}

sealed interface VirtualFileOpenPlan {
    data class ServeCached(val localRevision: String) : VirtualFileOpenPlan
    data class Hydrate(val expectedRemoteRevision: String) : VirtualFileOpenPlan
    data object UnavailableOffline : VirtualFileOpenPlan
    data class NeedsAttention(val reason: String) : VirtualFileOpenPlan
}

/** Plans hydrate-on-open without ever serving a known-stale generation as current. */
fun planVirtualFileOpen(
    entry: VirtualFileCacheEntry?,
    expectedRemoteRevision: String,
    networkAvailable: Boolean,
): VirtualFileOpenPlan {
    require(expectedRemoteRevision.isNotBlank())
    if (entry?.activity == VirtualFileActivity.NeedsAttention || entry?.dirty == true) {
        return VirtualFileOpenPlan.NeedsAttention(
            "This file has local changes or a conflict that must be resolved before hydration.",
        )
    }
    if (entry != null && entry.remoteRevision == expectedRemoteRevision) {
        return VirtualFileOpenPlan.ServeCached(entry.localRevision)
    }
    return if (networkAvailable) {
        VirtualFileOpenPlan.Hydrate(expectedRemoteRevision)
    } else {
        VirtualFileOpenPlan.UnavailableOffline
    }
}

private fun List<VirtualFileCacheEntry>.saturatedSizeSum(): Long = fold(0L) { total, entry ->
    total.saturatedPlus(entry.sizeBytes)
}

private fun Long.saturatedPlus(other: Long): Long =
    if (Long.MAX_VALUE - this < other) Long.MAX_VALUE else this + other

const val DEFAULT_VIRTUAL_FILE_CACHE_BYTES = 20L * 1024L * 1024L * 1024L
const val DEFAULT_VIRTUAL_FILE_MINIMUM_FREE_BYTES = 10L * 1024L * 1024L * 1024L
const val DEFAULT_VIRTUAL_FILE_UNUSED_AGE_MILLIS = 30L * 24L * 60L * 60L * 1_000L
