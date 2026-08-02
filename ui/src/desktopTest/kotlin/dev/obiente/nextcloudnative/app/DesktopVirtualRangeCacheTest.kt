package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopVirtualRangeCacheTest {
    @Test
    fun `combined automatic cache budget is shared while pinned Windows bytes are excluded`() {
        assertEquals(
            7L,
            combinedAutomaticCacheExcess(
                maximumBytes = 20L,
                completeFileBytes = 9L,
                rangeBytes = 8L,
                windowsCachedBytes = 15L,
                windowsPinnedBytes = 5L,
            ),
        )
        assertEquals(
            0L,
            combinedAutomaticCacheExcess(20L, 5L, 5L, windowsCachedBytes = 15L, windowsPinnedBytes = 5L),
        )
    }

    @Test
    fun `exact revision blocks survive cache restart`() {
        val directory = Files.createTempDirectory("virtual-range-cache-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.storeBlock(ACCOUNT_ID, "Photos/example.raf", "etag-1", 8L, 0L, "abcd".encodeToByteArray())

            val restarted = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            assertContentEquals(
                "abcd".encodeToByteArray(),
                restarted.readBlock(ACCOUNT_ID, "Photos/example.raf", "etag-1", 8L, 0L, 4),
            )
            assertEquals(null, restarted.readBlock(ACCOUNT_ID, "Photos/example.raf", "etag-2", 8L, 0L, 4))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `active virtual file blocks are protected until the final handle closes`() {
        val directory = Files.createTempDirectory("virtual-range-cache-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) {
                VirtualFileCachePolicy(maximumCacheBytes = 1L, minimumFreeSpaceBytes = 0L, unusedFileAgeMillis = null)
            }
            cache.acquire(ACCOUNT_ID, "Photos/example.raf")
            cache.storeBlock(ACCOUNT_ID, "Photos/example.raf", "etag-1", 4L, 0L, "data".encodeToByteArray())
            assertEquals(4L, cache.summary(ACCOUNT_ID).cachedBytes)

            cache.freeUp(ACCOUNT_ID, 4L)
            assertEquals(4L, cache.summary(ACCOUNT_ID).cachedBytes)

            cache.release(ACCOUNT_ID, "Photos/example.raf")
            cache.freeUp(ACCOUNT_ID, 4L)
            assertEquals(0L, cache.summary(ACCOUNT_ID).cachedBytes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalidation defers an active range until its final lease closes`() {
        val directory = Files.createTempDirectory("virtual-range-invalidation-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.storeBlock(ACCOUNT_ID, "Photos/open.raf", "e1", 4L, 0L, "data".encodeToByteArray())
            cache.acquire(ACCOUNT_ID, "Photos/open.raf")

            cache.invalidate(ACCOUNT_ID, "Photos")
            assertNotNull(cache.readBlock(ACCOUNT_ID, "Photos/open.raf", "e1", 4L, 0L, 4))

            cache.release(ACCOUNT_ID, "Photos/open.raf")
            assertNull(cache.readBlock(ACCOUNT_ID, "Photos/open.raf", "e1", 4L, 0L, 4))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `folder retention survives restart and safe release protects active and writeback paths`() {
        val directory = Files.createTempDirectory("virtual-range-retention-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos/Album", VirtualFolderRetention.KeepOnDevice)
            cache.storeBlock(ACCOUNT_ID, "Photos/Album/safe.raf", "e1", 4L, 0L, "safe".encodeToByteArray())
            cache.storeBlock(ACCOUNT_ID, "Photos/Album/open.raf", "e2", 4L, 0L, "open".encodeToByteArray())
            cache.storeBlock(ACCOUNT_ID, "Photos/Album/dirty.raf", "e3", 5L, 0L, "dirty".encodeToByteArray())
            cache.acquire(ACCOUNT_ID, "Photos/Album/open.raf")

            val restarted = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            assertEquals(
                VirtualFolderRetention.KeepOnDevice,
                restarted.loadFolderRetention(ACCOUNT_ID).retentionFor("Photos/Album/safe.raf"),
            )
            assertEquals(13L, restarted.summary(ACCOUNT_ID).pinnedBytes)
            assertEquals(3, restarted.summary(ACCOUNT_ID).pinnedFileCount)

            cache.setFolderRetention(ACCOUNT_ID, "Photos/Album", VirtualFolderRetention.Automatic)
            assertEquals(
                4L,
                cache.dehydrateFolder(ACCOUNT_ID, "Photos/Album", setOf("Photos/Album/dirty.raf")),
            )
            assertEquals(9L, cache.summary(ACCOUNT_ID).cachedBytes)
            cache.release(ACCOUNT_ID, "Photos/Album/open.raf")
            assertEquals(4L, cache.dehydrateFolder(ACCOUNT_ID, "Photos/Album", setOf("Photos/Album/dirty.raf")))
            assertEquals(5L, cache.summary(ACCOUNT_ID).cachedBytes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `retention intent and hydration failure survive a corrupt disposable range index`() {
        val directory = Files.createTempDirectory("virtual-range-retention-index-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos/Album", VirtualFolderRetention.KeepOnDevice)
            cache.setFolderHydrationStatus(
                ACCOUNT_ID,
                VirtualFolderHydrationStatus(
                    "Photos/Album",
                    VirtualFolderHydrationPhase.Failed,
                    "The storage drive became unavailable.",
                ),
            )
            cache.storeBlock(ACCOUNT_ID, "Photos/Album/photo.raf", "e1", 4L, 0L, "data".encodeToByteArray())
            directory.resolve(ACCOUNT_ID).resolve("range-index-v1.json").writeText("not-json")

            val restarted = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            assertEquals(
                VirtualFolderRetention.KeepOnDevice,
                restarted.loadFolderRetention(ACCOUNT_ID).retentionFor("Photos/Album/photo.raf"),
            )
            assertEquals(
                VirtualFolderHydrationPhase.Failed,
                restarted.loadFolderHydrationStatuses(ACCOUNT_ID).single().phase,
            )
            assertEquals(0L, restarted.summary(ACCOUNT_ID).cachedBytes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `retrying hydration preserves nested online-only exclusions`() {
        val directory = Files.createTempDirectory("virtual-range-retry-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.setFolderRetention(ACCOUNT_ID, "Photos/Archive", VirtualFolderRetention.Automatic)
            cache.setFolderHydrationStatus(
                ACCOUNT_ID,
                VirtualFolderHydrationStatus("Photos", VirtualFolderHydrationPhase.Failed, "Network unavailable."),
            )

            cache.retryFolderHydration(ACCOUNT_ID, "Photos")

            assertEquals(VirtualFolderRetention.Automatic, cache.loadFolderRetention(ACCOUNT_ID)
                .retentionFor("Photos/Archive/old.raf"))
            assertEquals(VirtualFolderHydrationPhase.Queued, cache.loadFolderHydrationStatuses(ACCOUNT_ID).single().phase)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a failed refresh preserves durable offline availability`() {
        val directory = Files.createTempDirectory("virtual-range-refresh-status-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.setFolderHydrationStatus(
                ACCOUNT_ID,
                VirtualFolderHydrationStatus(
                    "Photos",
                    VirtualFolderHydrationPhase.AvailableOffline,
                    refreshFailure = "Server unavailable.",
                ),
            )

            val restarted = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            val status = restarted.loadFolderHydrationStatuses(ACCOUNT_ID).single()
            assertEquals(VirtualFolderHydrationPhase.AvailableOffline, status.phase)
            assertEquals("Server unavailable.", status.refreshFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `pinned blocks are rejected instead of truncated at the index bound`() {
        val directory = Files.createTempDirectory("virtual-range-pinned-bound-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(
                root = directory,
                maximumBlocks = 2,
                policy = { nonEvictingTestPolicy() },
            )
            cache.setFolderRetention(ACCOUNT_ID, "Photos/Album", VirtualFolderRetention.KeepOnDevice)
            cache.storeBlock(ACCOUNT_ID, "Photos/Album/large.raf", "e1", 3L, 0L, byteArrayOf(1))
            cache.storeBlock(ACCOUNT_ID, "Photos/Album/large.raf", "e1", 3L, 1L, byteArrayOf(2))

            assertFailsWith<IllegalArgumentException> {
                cache.storeBlock(ACCOUNT_ID, "Photos/Album/large.raf", "e1", 3L, 2L, byteArrayOf(3))
            }
            assertContentEquals(
                byteArrayOf(1),
                cache.readBlock(ACCOUNT_ID, "Photos/Album/large.raf", "e1", 3L, 0L, 1),
            )
            assertContentEquals(
                byteArrayOf(2),
                cache.readBlock(ACCOUNT_ID, "Photos/Album/large.raf", "e1", 3L, 1L, 1),
            )
            assertEquals(2L, cache.summary(ACCOUNT_ID).pinnedBytes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `pinned bytes do not consume the automatic range cache budget`() {
        val directory = Files.createTempDirectory("virtual-range-pinned-budget-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) {
                VirtualFileCachePolicy(maximumCacheBytes = 4L, minimumFreeSpaceBytes = 0L, unusedFileAgeMillis = null)
            }
            cache.storeBlock(ACCOUNT_ID, "Photos/automatic.raf", "e1", 4L, 0L, "auto".encodeToByteArray())
            cache.setFolderRetention(ACCOUNT_ID, "Photos/Album", VirtualFolderRetention.KeepOnDevice)
            cache.storeBlock(ACCOUNT_ID, "Photos/Album/pinned.raf", "e2", 4L, 0L, "keep".encodeToByteArray())

            assertContentEquals(
                "auto".encodeToByteArray(),
                cache.readBlock(ACCOUNT_ID, "Photos/automatic.raf", "e1", 4L, 0L, 4),
            )
            assertContentEquals(
                "keep".encodeToByteArray(),
                cache.readBlock(ACCOUNT_ID, "Photos/Album/pinned.raf", "e2", 4L, 0L, 4),
            )
            assertEquals(8L, cache.summary(ACCOUNT_ID).cachedBytes)
            assertEquals(4L, cache.summary(ACCOUNT_ID).pinnedBytes)
            assertTrue(cache.summary(ACCOUNT_ID).reclaimableBytes >= 4L)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `incomplete staged revision preserves the previous complete revision`() {
        val directory = Files.createTempDirectory("virtual-range-generation-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos/Album", VirtualFolderRetention.KeepOnDevice)
            cache.storeBlock(ACCOUNT_ID, "Photos/Album/photo.raf", "etag-1", 8L, 0L, "old-data".encodeToByteArray())

            assertFailsWith<IllegalStateException> {
                cache.storeBlock(
                    ACCOUNT_ID,
                    "Photos/Album/photo.raf",
                    "etag-2",
                    8L,
                    0L,
                    "new-".encodeToByteArray(),
                )
            }

            cache.beginRevisionStaging(ACCOUNT_ID, "Photos/Album/photo.raf", "etag-2", 8L).use { staging ->
                staging.store(0L, "new-".encodeToByteArray())
                assertEquals(false, staging.commitIfComplete())
            }

            assertContentEquals(
                "old-data".encodeToByteArray(),
                cache.readBlock(ACCOUNT_ID, "Photos/Album/photo.raf", "etag-1", 8L, 0L, 8),
            )
            assertEquals(null, cache.readBlock(ACCOUNT_ID, "Photos/Album/photo.raf", "etag-2", 8L, 0L, 4))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `complete staged revision atomically replaces the previous revision`() {
        val directory = Files.createTempDirectory("virtual-range-generation-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos/Album", VirtualFolderRetention.KeepOnDevice)
            cache.storeBlock(ACCOUNT_ID, "Photos/Album/photo.raf", "etag-1", 8L, 0L, "old-data".encodeToByteArray())

            cache.beginRevisionStaging(ACCOUNT_ID, "Photos/Album/photo.raf", "etag-2", 8L).use { staging ->
                staging.store(0L, "new-".encodeToByteArray())
                staging.store(4L, "data".encodeToByteArray())
                assertTrue(staging.commitIfComplete())
            }

            assertEquals(null, cache.readBlock(ACCOUNT_ID, "Photos/Album/photo.raf", "etag-1", 8L, 0L, 8))
            assertContentEquals(
                "new-".encodeToByteArray(),
                cache.readBlock(ACCOUNT_ID, "Photos/Album/photo.raf", "etag-2", 8L, 0L, 4),
            )
            assertContentEquals(
                "data".encodeToByteArray(),
                cache.readBlock(ACCOUNT_ID, "Photos/Album/photo.raf", "etag-2", 8L, 4L, 4),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `complete revision validation rejects a missing block blob`() {
        val directory = Files.createTempDirectory("virtual-range-coverage-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.storeBlock(ACCOUNT_ID, "Photos/photo.raf", "e1", 8L, 0L, "left".encodeToByteArray())
            cache.storeBlock(ACCOUNT_ID, "Photos/photo.raf", "e1", 8L, 4L, "rght".encodeToByteArray())
            assertTrue(cache.hasCompleteRevision(ACCOUNT_ID, "Photos/photo.raf", "e1", 8L))
            directory.resolve(ACCOUNT_ID).listFiles().orEmpty().first { it.extension == "block" }.delete()

            assertFalse(cache.hasCompleteRevision(ACCOUNT_ID, "Photos/photo.raf", "e1", 8L))
            assertEquals(0L, cache.summary(ACCOUNT_ID).cachedBytes)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `retained metadata remains available beyond the disposable listing bound`() {
        val directory = Files.createTempDirectory("virtual-range-retained-metadata-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            val snapshots = buildMap {
                put("Photos", LinuxVirtualDirectorySnapshot(emptyList(), 42L))
                repeat(300) { index ->
                    val path = "Photos/Album-$index"
                    put(
                        path,
                        LinuxVirtualDirectorySnapshot(
                            listOf(LinuxVirtualFileNode("$path/photo.jpg", "photo.jpg", false, 4L, "e$index")),
                            42L,
                        ),
                    )
                }
            }
            cache.publishRetainedListings(ACCOUNT_ID, "Photos", snapshots)

            val restarted = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            assertEquals("e299", restarted.loadRetainedListing(ACCOUNT_ID, "Photos/Album-299")
                ?.nodes?.single()?.remoteRevision)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `releasing one retained sibling preserves their shared ancestor listing`() {
        val directory = Files.createTempDirectory("virtual-range-retained-siblings-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos/A", VirtualFolderRetention.KeepOnDevice)
            cache.setFolderRetention(ACCOUNT_ID, "Photos/B", VirtualFolderRetention.KeepOnDevice)
            val photos = LinuxVirtualDirectorySnapshot(
                listOf(
                    LinuxVirtualFileNode("Photos/A", "A", true, 0L, "a"),
                    LinuxVirtualFileNode("Photos/B", "B", true, 0L, "b"),
                ),
                42L,
            )
            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Photos/A",
                mapOf("Photos" to photos, "Photos/A" to LinuxVirtualDirectorySnapshot(emptyList(), 42L)),
            )
            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Photos/B",
                mapOf("Photos" to photos, "Photos/B" to LinuxVirtualDirectorySnapshot(emptyList(), 42L)),
            )

            cache.setFolderRetention(ACCOUNT_ID, "Photos/A", VirtualFolderRetention.Automatic)
            cache.dehydrateFolder(ACCOUNT_ID, "Photos/A", emptySet())

            assertEquals(
                setOf("A", "B"),
                requireNotNull(cache.loadRetainedListing(ACCOUNT_ID, "Photos"))
                    .nodes.mapTo(hashSetOf(), LinuxVirtualFileNode::name),
            )
            assertEquals(
                emptyList<LinuxVirtualFileNode>(),
                requireNotNull(cache.loadRetainedListing(ACCOUNT_ID, "Photos/B")).nodes,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `missing selected drive is not recreated while starting revision staging`() {
        val directory = Files.createTempDirectory("virtual-range-drive-").toFile()
        val selectedDrive = directory.resolve("selected-drive").apply { mkdir() }
        val cacheRoot = selectedDrive.resolve("cache")
        try {
            val cache = DesktopVirtualRangeCache(
                root = cacheRoot,
                createParentDirectories = false,
                policy = { nonEvictingTestPolicy() },
            )
            cacheRoot.deleteRecursively()
            selectedDrive.delete()

            assertFailsWith<IllegalArgumentException> {
                cache.beginRevisionStaging(ACCOUNT_ID, "Photos/photo.raf", "e1", 4L)
            }
            assertFalse(selectedDrive.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `startup recovery removes abandoned stages but preserves an active staging lease`() {
        val directory = Files.createTempDirectory("virtual-range-stage-recovery-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            val active = cache.beginRevisionStaging(ACCOUNT_ID, "Photos/active.raf", "e1", 4L)
            active.store(0L, "data".encodeToByteArray())
            val accountDirectory = directory.resolve(ACCOUNT_ID)
            val activeStage = accountDirectory.listFiles().orEmpty().single { it.extension == "stage" }
            val abandonedId = "00000000-0000-0000-0000-000000000001"
            val abandoned = accountDirectory.resolve("range-revision.$abandonedId.1.stage").apply { writeText("stale") }
            accountDirectory.resolve("range-revision.$abandonedId.lock").writeText("")

            DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }.summary(ACCOUNT_ID)

            assertFalse(abandoned.exists())
            assertTrue(activeStage.exists())
            active.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `authoritative directory reconciliation removes stale ranges but protects writebacks`() {
        val directory = Files.createTempDirectory("virtual-range-reconcile-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.storeBlock(ACCOUNT_ID, "Photos/live.raf", "e1", 4L, 0L, "live".encodeToByteArray())
            cache.storeBlock(ACCOUNT_ID, "Photos/Gone/old.raf", "e2", 3L, 0L, "old".encodeToByteArray())
            cache.storeBlock(ACCOUNT_ID, "Photos/dirty.raf", "e3", 5L, 0L, "dirty".encodeToByteArray())

            assertEquals(
                setOf("Photos/live.raf", "Photos/Gone", "Photos/dirty.raf"),
                cache.cachedDirectChildren(ACCOUNT_ID, "Photos"),
            )
            reconcileVirtualRangeChildren(
                cache = cache,
                accountId = ACCOUNT_ID,
                parent = "Photos",
                documents = listOf(
                    DesktopRemoteSyncDocument(
                        RemoteSyncEntry("Photos/live.raf", SyncEntryKind.File, "e1", 4L),
                        isDirectory = false,
                    ),
                ),
                protectedPaths = setOf("Photos/dirty.raf"),
            )

            assertContentEquals(
                "live".encodeToByteArray(),
                cache.readBlock(ACCOUNT_ID, "Photos/live.raf", "e1", 4L, 0L, 4),
            )
            assertEquals(null, cache.readBlock(ACCOUNT_ID, "Photos/Gone/old.raf", "e2", 3L, 0L, 3))
            assertContentEquals(
                "dirty".encodeToByteArray(),
                cache.readBlock(ACCOUNT_ID, "Photos/dirty.raf", "e3", 5L, 0L, 5),
            )
            assertEquals(listOf("", "Photos", "Photos/2026"), retainedFolderAncestorListings("Photos/2026/August"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `oversized range index rejects a block without leaving an orphan blob`() {
        val directory = Files.createTempDirectory("virtual-range-cache-index-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(
                root = directory,
                policy = { nonEvictingTestPolicy() },
                maximumIndexBytes = 1_024L,
            )
            cache.storeBlock(ACCOUNT_ID, "Photos/kept.raf", "etag-1", 4L, 0L, "kept".encodeToByteArray())
            val accountDirectory = directory.resolve(ACCOUNT_ID)
            val originalBlocks = accountDirectory.listFiles().orEmpty().filter { it.extension == "block" }

            assertFailsWith<IllegalArgumentException> {
                cache.storeBlock(
                    ACCOUNT_ID,
                    "Photos/${"a".repeat(900)}.raf",
                    "etag-2",
                    4L,
                    0L,
                    "next".encodeToByteArray(),
                )
            }

            assertEquals(originalBlocks.map { it.name }, accountDirectory.listFiles().orEmpty()
                .filter { it.extension == "block" }.map { it.name })
            assertContentEquals(
                "kept".encodeToByteArray(),
                cache.readBlock(ACCOUNT_ID, "Photos/kept.raf", "etag-1", 4L, 0L, 4),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val ACCOUNT_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        fun nonEvictingTestPolicy() = VirtualFileCachePolicy(
            automaticCleanup = false,
            minimumFreeSpaceBytes = 0L,
            unusedFileAgeMillis = null,
        )
    }
}
