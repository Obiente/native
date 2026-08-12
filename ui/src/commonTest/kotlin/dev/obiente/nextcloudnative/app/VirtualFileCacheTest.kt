package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VirtualFileCacheTest {
    @Test
    fun `automatic cleanup expires old files and then uses least recently used order`() {
        val entries = listOf(
            entry("old.raf", size = 40L, accessed = 10L),
            entry("recent.jpg", size = 30L, accessed = 900L),
            entry("older.jpg", size = 50L, accessed = 500L),
        )
        val plan = planVirtualFileEviction(
            entries = entries,
            policy = VirtualFileCachePolicy(
                maximumCacheBytes = 60L,
                minimumFreeSpaceBytes = 0L,
                unusedFileAgeMillis = 800L,
            ),
            availableFreeBytes = 1_000L,
            nowEpochMillis = 1_000L,
        )

        assertEquals(listOf("old.raf", "older.jpg"), plan.evictions.map { it.key.relativePath })
        assertEquals(90L, plan.plannedFreedBytes)
        assertEquals(60L, plan.requiredFreedBytes)
        assertEquals(0L, plan.unmetRequiredBytes)
        assertTrue(VirtualFileEvictionReason.UnusedAge in plan.evictions.first().reasons)
        assertTrue(VirtualFileEvictionReason.CacheBudget in plan.evictions.first().reasons)
    }

    @Test
    fun `pinned dirty open and active files are never evicted`() {
        val protected = listOf(
            entry("pinned.raf", retention = VirtualFileRetention.Pinned),
            entry("dirty.xmp", dirty = true),
            entry("open.mov", leaseCount = 1),
            entry("uploading.jpg", activity = VirtualFileActivity.Uploading),
            entry("conflict.md", activity = VirtualFileActivity.NeedsAttention),
        )
        val disposable = entry("preview.jpg", size = 20L)
        val plan = planVirtualFileEviction(
            entries = protected + disposable,
            policy = VirtualFileCachePolicy(
                maximumCacheBytes = 1L,
                minimumFreeSpaceBytes = 100L,
                unusedFileAgeMillis = null,
            ),
            availableFreeBytes = 0L,
            nowEpochMillis = 1_000L,
            requestedBytesToFree = 1_000L,
        )

        assertEquals(listOf("preview.jpg"), plan.evictions.map { it.key.relativePath })
        assertEquals(20L, plan.reclaimableBytes)
        assertEquals(980L, plan.unmetRequiredBytes)
    }

    @Test
    fun `manual free up works while automatic cleanup is disabled`() {
        val plan = planVirtualFileEviction(
            entries = listOf(
                entry("a.jpg", size = 25L, accessed = 20L),
                entry("b.jpg", size = 30L, accessed = 10L),
            ),
            policy = VirtualFileCachePolicy(
                automaticCleanup = false,
                maximumCacheBytes = 1L,
                minimumFreeSpaceBytes = 1_000L,
                unusedFileAgeMillis = 1L,
            ),
            availableFreeBytes = 0L,
            nowEpochMillis = 1_000L,
            requestedBytesToFree = 26L,
        )

        assertEquals(listOf("b.jpg"), plan.evictions.map { it.key.relativePath })
        assertEquals(setOf(VirtualFileEvictionReason.ManualFreeUp), plan.evictions.single().reasons)
    }

    @Test
    fun `open uses exact cached revision and otherwise hydrates or reports offline`() {
        val cached = entry("photo.raf", remote = "etag-1", local = "sha256:one")
        assertEquals(
            VirtualFileOpenPlan.ServeCached("sha256:one"),
            planVirtualFileOpen(cached, expectedRemoteRevision = "etag-1", networkAvailable = false),
        )
        assertEquals(
            VirtualFileOpenPlan.Hydrate("etag-2"),
            planVirtualFileOpen(cached, expectedRemoteRevision = "etag-2", networkAvailable = true),
        )
        assertIs<VirtualFileOpenPlan.UnavailableOffline>(
            planVirtualFileOpen(cached, expectedRemoteRevision = "etag-2", networkAvailable = false),
        )
        assertIs<VirtualFileOpenPlan.NeedsAttention>(
            planVirtualFileOpen(cached.copy(dirty = true), "etag-1", networkAvailable = true),
        )
    }

    @Test
    fun `invalid policies and duplicate entries fail closed`() {
        assertFailsWith<IllegalArgumentException> { VirtualFileCachePolicy(maximumCacheBytes = 0L) }
        assertFailsWith<IllegalArgumentException> { VirtualFileCachePolicy(minimumFreeSpaceBytes = -1L) }
        assertFailsWith<IllegalArgumentException> { VirtualFileCachePolicy(overflowMaximumCacheBytes = 0L) }
        assertFailsWith<IllegalArgumentException> { VirtualFileCachePolicy(overflowMinimumFreeSpaceBytes = -1L) }
        val duplicate = entry("same.jpg")
        assertFailsWith<IllegalArgumentException> {
            planVirtualFileEviction(
                entries = listOf(duplicate, duplicate),
                policy = VirtualFileCachePolicy(),
                availableFreeBytes = 1L,
                nowEpochMillis = 1L,
            )
        }
    }

    private fun entry(
        path: String,
        size: Long = 10L,
        accessed: Long = 100L,
        remote: String = "etag-$path",
        local: String = "sha256:$path",
        retention: VirtualFileRetention = VirtualFileRetention.Automatic,
        dirty: Boolean = false,
        leaseCount: Int = 0,
        activity: VirtualFileActivity = VirtualFileActivity.Idle,
    ) = VirtualFileCacheEntry(
        key = FileOfflineKey("account-a", path),
        remoteRevision = remote,
        localRevision = local,
        sizeBytes = size,
        cachedAtEpochMillis = 0L,
        lastAccessedAtEpochMillis = accessed,
        retention = retention,
        dirty = dirty,
        activeLeaseCount = leaseCount,
        activity = activity,
    )
}
