package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    fun `valid cached bytes remain readable when access time persistence fails`() {
        val directory = Files.createTempDirectory("virtual-range-read-only-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.storeBlock(ACCOUNT_ID, "Photos/example.raf", "etag-1", 4L, 0L, "data".encodeToByteArray())
            val accountDirectory = directory.resolve(ACCOUNT_ID).toPath()
            val indexFile = accountDirectory.resolve("range-index-v1.json").toFile()
            val indexBeforeRead = indexFile.readText()
            val originalPermissions = runCatching { Files.getPosixFilePermissions(accountDirectory) }.getOrNull()
                ?: return
            try {
                Files.setPosixFilePermissions(
                    accountDirectory,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
                )

                assertContentEquals(
                    "data".encodeToByteArray(),
                    cache.readBlock(
                        ACCOUNT_ID,
                        "Photos/example.raf",
                        "etag-1",
                        4L,
                        0L,
                        4,
                        nowEpochMillis = 42L,
                    ),
                )
                assertEquals(indexBeforeRead, indexFile.readText())
            } finally {
                Files.setPosixFilePermissions(accountDirectory, originalPermissions)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `startup removes unreferenced cache artifacts without deleting indexed blocks`() {
        val directory = Files.createTempDirectory("virtual-range-orphans-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.storeBlock(ACCOUNT_ID, "Photos/example.raf", "etag-1", 4L, 0L, "data".encodeToByteArray())
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Photos",
                mapOf(
                    "Photos" to LinuxVirtualDirectorySnapshot(
                        listOf(LinuxVirtualFileNode("Photos/example.raf", "example.raf", false, 4L, "etag-1")),
                        42L,
                    ),
                ),
            )
            val accountDirectory = directory.resolve(ACCOUNT_ID)
            val orphanBlock = accountDirectory.resolve("${"a".repeat(64)}.block").apply { writeText("orphan") }
            val orphanListing = accountDirectory.resolve("${"b".repeat(64)}.listing").apply { writeText("orphan") }
            val orphanTemporary = accountDirectory.resolve("${"c".repeat(64)}.block.123.tmp")
                .apply { writeText("orphan") }

            val restarted = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            assertEquals(4L, restarted.summary(ACCOUNT_ID).cachedBytes)

            assertFalse(orphanBlock.exists())
            assertFalse(orphanListing.exists())
            assertFalse(orphanTemporary.exists())
            assertEquals(
                listOf("example.raf"),
                restarted.loadRetainedListing(ACCOUNT_ID, "Photos")?.nodes?.map(LinuxVirtualFileNode::name),
            )
            assertContentEquals(
                "data".encodeToByteArray(),
                restarted.readBlock(ACCOUNT_ID, "Photos/example.raf", "etag-1", 4L, 0L, 4),
            )
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
    fun `reselecting retained folder preserves nested online-only exclusions`() {
        val directory = Files.createTempDirectory("virtual-range-reselect-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.setFolderRetention(ACCOUNT_ID, "Photos/Archive", VirtualFolderRetention.Automatic)

            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)

            assertEquals(
                VirtualFolderRetention.Automatic,
                cache.loadFolderRetention(ACCOUNT_ID).retentionFor("Photos/Archive/old.raf"),
            )
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
                    refreshRetryAtEpochMillis = 1_800_000L,
                ),
            )

            val restarted = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            val status = restarted.loadFolderHydrationStatuses(ACCOUNT_ID).single()
            assertEquals(VirtualFolderHydrationPhase.AvailableOffline, status.phase)
            assertEquals("Server unavailable.", status.refreshFailure)
            assertEquals(1_800_000L, status.refreshRetryAtEpochMillis)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `available folder verification time survives restart`() {
        val directory = Files.createTempDirectory("virtual-range-refresh-time-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.setFolderHydrationStatus(
                ACCOUNT_ID,
                VirtualFolderHydrationStatus(
                    "Photos",
                    VirtualFolderHydrationPhase.AvailableOffline,
                    verifiedAtEpochMillis = 42L,
                ),
            )

            val restarted = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }

            assertEquals(42L, restarted.loadFolderHydrationStatuses(ACCOUNT_ID).single().verifiedAtEpochMillis)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `available folders refresh only after their independent freshness interval`() {
        val fresh = VirtualFolderHydrationStatus(
            "Photos",
            VirtualFolderHydrationPhase.AvailableOffline,
            verifiedAtEpochMillis = 10_000L,
        )

        assertFalse(shouldScheduleVirtualFolderHydration(fresh, 10_999L, refreshIntervalMillis = 1_000L))
        assertTrue(shouldScheduleVirtualFolderHydration(fresh, 11_000L, refreshIntervalMillis = 1_000L))
        assertTrue(shouldScheduleVirtualFolderHydration(fresh, 9_999L, refreshIntervalMillis = 1_000L))
        assertTrue(
            shouldScheduleVirtualFolderHydration(
                fresh.copy(refreshing = true),
                10_001L,
                refreshIntervalMillis = 1_000L,
            ),
        )
        assertFalse(
            shouldScheduleVirtualFolderHydration(
                VirtualFolderHydrationStatus("Photos", VirtualFolderHydrationPhase.Failed, "Offline."),
                20_000L,
                refreshIntervalMillis = 1_000L,
            ),
        )
        val backedOff = fresh.copy(
            refreshFailure = "Server unavailable.",
            refreshRetryAtEpochMillis = 20_000L,
        )
        assertFalse(shouldScheduleVirtualFolderHydration(backedOff, 19_999L, refreshIntervalMillis = 1_000L))
        assertTrue(shouldScheduleVirtualFolderHydration(backedOff, 20_000L, refreshIntervalMillis = 1_000L))
        assertTrue(shouldScheduleVirtualFolderHydration(backedOff, 9_999L, refreshIntervalMillis = 1_000L))
        assertTrue(
            shouldScheduleVirtualFolderHydration(
                backedOff.copy(verifiedAtEpochMillis = 19_500L),
                20_000L,
                refreshIntervalMillis = 10_000L,
            ),
        )
    }

    @Test
    fun `fresh status can be read without running retained content verification`() {
        val directory = Files.createTempDirectory("virtual-range-cheap-status-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.setFolderHydrationStatus(
                ACCOUNT_ID,
                VirtualFolderHydrationStatus(
                    "Photos",
                    VirtualFolderHydrationPhase.AvailableOffline,
                    verifiedAtEpochMillis = 42L,
                ),
            )

            assertEquals(
                VirtualFolderHydrationPhase.AvailableOffline,
                cache.loadFolderHydrationStatus(ACCOUNT_ID, "Photos")?.phase,
            )
            assertEquals(
                VirtualFolderHydrationPhase.Queued,
                cache.loadValidatedFolderHydrationStatus(ACCOUNT_ID, "Photos")?.phase,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `remote listing changes queue only affected retained roots`() {
        val directory = Files.createTempDirectory("virtual-range-listing-refresh-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.setFolderRetention(ACCOUNT_ID, "Photos/Excluded", VirtualFolderRetention.Automatic)
            cache.setFolderRetention(ACCOUNT_ID, "Documents", VirtualFolderRetention.KeepOnDevice)
            listOf("Photos", "Documents").forEach { root ->
                cache.setFolderHydrationStatus(
                    ACCOUNT_ID,
                    VirtualFolderHydrationStatus(
                        root,
                        VirtualFolderHydrationPhase.AvailableOffline,
                        verifiedAtEpochMillis = 42L,
                    ),
                )
            }

            assertEquals(
                listOf("Documents"),
                cache.queueRetainedFoldersForListingRefresh(
                    ACCOUNT_ID,
                    setOf(
                        "Documents/new.txt",
                        "Photos/Excluded/online-only.txt",
                        "Unrelated/file.txt",
                    ),
                ),
            )
            val statusByPath = cache.loadFolderHydrationStatuses(ACCOUNT_ID).associateBy(
                VirtualFolderHydrationStatus::relativePath,
            )
            assertEquals(VirtualFolderHydrationPhase.AvailableOffline, statusByPath.getValue("Photos").phase)
            assertEquals(VirtualFolderHydrationPhase.Queued, statusByPath.getValue("Documents").phase)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `retained metadata budget rejects the next listing before accumulation`() {
        assertEquals(100, nextVirtualFolderRetainedMetadataCount(40, 60, maximumEntries = 100))
        assertFailsWith<IllegalStateException> {
            nextVirtualFolderRetainedMetadataCount(40, 61, maximumEntries = 100)
        }
    }

    @Test
    fun `retained directory budget rejects traversal before another listing`() {
        requireVirtualFolderListingCapacity(99, maximumListings = 100)
        assertFailsWith<IllegalStateException> {
            requireVirtualFolderListingCapacity(100, maximumListings = 100)
        }
    }

    @Test
    fun `a missing retained sibling does not reject the available hydration target`() {
        val available = DesktopRemoteSyncDocument(
            RemoteSyncEntry("Photos/Available", SyncEntryKind.Directory, "directory-etag", null),
            isDirectory = true,
        )

        assertEquals(
            setOf("Photos/Available"),
            retainedFolderAvailableNavigationTargets("Photos/Available", listOf(available)),
        )
        assertEquals(
            setOf("Photos/Missing/Album"),
            retainedRootsMissingNavigationTarget(
                parentPath = "Photos",
                retainedRoots = listOf("Photos/Available", "Photos/Missing/Album"),
                availableTargets = setOf("Photos/Available"),
            ),
        )
        assertFailsWith<IllegalStateException> {
            retainedFolderAvailableNavigationTargets("Photos/Missing", listOf(available))
        }
    }

    @Test
    fun `lazy hydration jobs occupy their key and only owners remove it`() = runBlocking {
        val owner = launch(start = CoroutineStart.LAZY) {}
        val replacement = launch(start = CoroutineStart.LAZY) {}
        val jobs = mutableMapOf("folder" to owner)

        assertFalse(owner.isActive)
        assertTrue(owner.occupiesVirtualFolderHydrationSlot())
        assertFalse(removeVirtualFolderHydrationJobIfOwned(jobs, "folder", replacement))
        assertEquals(owner, jobs["folder"])
        assertTrue(removeVirtualFolderHydrationJobIfOwned(jobs, "folder", owner))
        assertNull(jobs["folder"])

        owner.cancelAndJoin()
        replacement.cancelAndJoin()
        assertFalse(owner.occupiesVirtualFolderHydrationSlot())
    }

    @Test
    fun `remote mutation generations advance only affected retained roots`() {
        val photos = "$ACCOUNT_ID\u0000Photos"
        val documents = "$ACCOUNT_ID\u0000Documents"
        val generations = mutableMapOf(photos to 2L, documents to 7L)
        val completed = mutableMapOf(photos to 2L, documents to 7L)

        advanceAffectedVirtualFolderGenerations(
            generations,
            completed,
            ACCOUNT_ID,
            retainedRoots = listOf("Photos"),
        )

        assertEquals(3L, generations[photos])
        assertEquals(7L, generations[documents])
        assertEquals(7L, completed[documents])
    }

    @Test
    fun `partial retained navigation survives restart without claiming completeness`() {
        val directory = Files.createTempDirectory("virtual-range-navigation-completeness-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos/2026", VirtualFolderRetention.KeepOnDevice)
            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Photos/2026",
                mapOf(
                    "" to LinuxVirtualDirectorySnapshot(
                        listOf(LinuxVirtualFileNode("Photos", "Photos", true, 0L, "photos-new")),
                        42L,
                        complete = false,
                    ),
                    "Photos" to LinuxVirtualDirectorySnapshot(
                        listOf(LinuxVirtualFileNode("Photos/2026", "2026", true, 0L, "2026-new")),
                        42L,
                        complete = false,
                    ),
                    "Photos/2026" to LinuxVirtualDirectorySnapshot(emptyList(), 42L),
                ),
            )

            val restarted = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            assertFalse(requireNotNull(restarted.loadRetainedListing(ACCOUNT_ID, "")).complete)
            assertFalse(requireNotNull(restarted.loadRetainedListing(ACCOUNT_ID, "Photos")).complete)
            assertTrue(requireNotNull(restarted.loadRetainedListing(ACCOUNT_ID, "Photos/2026")).complete)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `capacity checks can reuse the traversal retention snapshot`() {
        val directory = Files.createTempDirectory("virtual-range-retention-snapshot-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(
                root = directory,
                maximumBlocks = 1,
                policy = { nonEvictingTestPolicy() },
            )
            cache.storeBlock(ACCOUNT_ID, "Photos/existing.raf", "e1", 4L, 0L, "data".encodeToByteArray())
            val traversalRetention = VirtualFolderRetentionState()
                .withRetention("Photos", VirtualFolderRetention.KeepOnDevice)

            assertFailsWith<IllegalArgumentException> {
                cache.requireRevisionCapacity(
                    ACCOUNT_ID,
                    "Documents/new.raf",
                    4L,
                    4,
                    traversalRetention,
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `retrying an available folder persists refresh progress`() {
        val directory = Files.createTempDirectory("virtual-range-refresh-progress-").toFile()
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

            cache.retryFolderHydration(ACCOUNT_ID, "Photos")

            val status = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
                .loadFolderHydrationStatuses(ACCOUNT_ID).single()
            assertEquals(VirtualFolderHydrationPhase.AvailableOffline, status.phase)
            assertTrue(status.refreshing)
            assertEquals(null, status.refreshFailure)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `committed retained edit queues its root and invalid coverage is not available offline`() {
        val directory = Files.createTempDirectory("virtual-range-retained-edit-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.storeBlock(ACCOUNT_ID, "Photos/photo.raf", "e1", 4L, 0L, "data".encodeToByteArray())
            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Photos",
                mapOf(
                    "Photos" to LinuxVirtualDirectorySnapshot(
                        listOf(LinuxVirtualFileNode("Photos/photo.raf", "photo.raf", false, 4L, "e1")),
                        42L,
                    ),
                ),
            )
            cache.setFolderHydrationStatus(
                ACCOUNT_ID,
                VirtualFolderHydrationStatus("Photos", VirtualFolderHydrationPhase.AvailableOffline),
            )
            directory.resolve(ACCOUNT_ID).listFiles().orEmpty().single { it.extension == "block" }.delete()

            assertFalse(cache.hasCompleteRetainedFolder(ACCOUNT_ID, "Photos"))
            assertEquals(listOf("Photos"), cache.queueRetainedFoldersForRefresh(ACCOUNT_ID, "Photos/photo.raf"))
            assertEquals(VirtualFolderHydrationPhase.Queued, cache.loadFolderHydrationStatuses(ACCOUNT_ID).single().phase)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `mutating an ancestor queues every nested retained root`() {
        val directory = Files.createTempDirectory("virtual-range-retained-ancestor-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos/2026", VirtualFolderRetention.KeepOnDevice)
            cache.setFolderHydrationStatus(
                ACCOUNT_ID,
                VirtualFolderHydrationStatus("Photos/2026", VirtualFolderHydrationPhase.AvailableOffline),
            )

            assertEquals(listOf("Photos/2026"), cache.queueRetainedFoldersForRefresh(ACCOUNT_ID, "Photos"))
            assertEquals(VirtualFolderHydrationPhase.Queued, cache.loadFolderHydrationStatuses(ACCOUNT_ID).single().phase)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `nested retained folder is incomplete without its durable navigation ancestors`() {
        val directory = Files.createTempDirectory("virtual-range-retained-navigation-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos/2026", VirtualFolderRetention.KeepOnDevice)
            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Photos/2026",
                mapOf("Photos/2026" to LinuxVirtualDirectorySnapshot(emptyList(), 42L)),
            )

            assertFalse(cache.hasCompleteRetainedFolder(ACCOUNT_ID, "Photos/2026"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `corrupt retention intent blocks eviction instead of converting pins to cache`() {
        val directory = Files.createTempDirectory("virtual-range-corrupt-retention-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.storeBlock(ACCOUNT_ID, "Photos/photo.raf", "e1", 4L, 0L, "data".encodeToByteArray())
            val accountDirectory = directory.resolve(ACCOUNT_ID)
            val block = accountDirectory.listFiles().orEmpty().single { it.extension == "block" }
            accountDirectory.resolve("folder-retention-v1.json").writeText("not-json")

            val restarted = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            assertFailsWith<Throwable> { restarted.freeUp(ACCOUNT_ID, 4L) }
            assertTrue(block.exists())
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
    fun `retained revision capacity is rejected before staging starts`() {
        val directory = Files.createTempDirectory("virtual-range-capacity-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(
                root = directory,
                maximumBlocks = 2,
                policy = { nonEvictingTestPolicy() },
            )
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.storeBlock(ACCOUNT_ID, "Photos/kept.raf", "e1", 1L, 0L, byteArrayOf(1))

            assertFailsWith<IllegalArgumentException> {
                cache.requireRevisionCapacity(ACCOUNT_ID, "Photos/next.raf", 3L, blockBytes = 2)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `retained tree capacity is rejected before any revision is staged`() {
        val directory = Files.createTempDirectory("virtual-range-tree-capacity-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(
                root = directory,
                maximumBlocks = 2,
                policy = { nonEvictingTestPolicy() },
            )
            val retention = VirtualFolderRetentionState(
                listOf(VirtualFolderRetentionRule("Photos", VirtualFolderRetention.KeepOnDevice)),
            )

            assertFailsWith<IllegalArgumentException> {
                cache.requireRevisionsCapacity(
                    accountId = ACCOUNT_ID,
                    revisions = listOf(
                        VirtualRangeRevision("Photos/one.raf", "e1", 1L),
                        VirtualRangeRevision("Photos/two.raf", "e2", 1L),
                        VirtualRangeRevision("Photos/three.raf", "e3", 1L),
                    ),
                    blockBytes = 1,
                    retention = retention,
                )
            }
            assertEquals(0, cache.summary(ACCOUNT_ID).fileCount)
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
    fun `retained refresh preserves the published revision until new listings commit`() {
        val directory = Files.createTempDirectory("virtual-range-pending-publication-").toFile()
        try {
            val retention = VirtualFolderRetentionState(
                listOf(VirtualFolderRetentionRule("Photos", VirtualFolderRetention.KeepOnDevice)),
            )
            val cache = DesktopVirtualRangeCache(
                root = directory,
                maximumBlocks = 1,
                policy = { nonEvictingTestPolicy() },
            )
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.storeBlock(ACCOUNT_ID, "Photos/photo.raf", "e1", 4L, 0L, "old!".encodeToByteArray())
            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Photos",
                mapOf(
                    "Photos" to LinuxVirtualDirectorySnapshot(
                        listOf(LinuxVirtualFileNode("Photos/photo.raf", "photo.raf", false, 4L, "e1")),
                        10L,
                    ),
                ),
            )

            cache.beginRevisionStaging(
                ACCOUNT_ID,
                "Photos/photo.raf",
                "e2",
                4L,
                retention,
                preservePreviousRevisionUntilPublication = true,
            ).use { staging ->
                staging.store(0L, "new!".encodeToByteArray())
                assertTrue(staging.commitIfComplete())
            }

            cache.beginRevisionStaging(
                ACCOUNT_ID,
                "Photos/photo.raf",
                "e3",
                4L,
                retention,
                preservePreviousRevisionUntilPublication = true,
            ).use { staging ->
                staging.store(0L, "last".encodeToByteArray())
                assertTrue(staging.commitIfComplete())
            }

            val restartedBeforePublication = DesktopVirtualRangeCache(
                root = directory,
                maximumBlocks = 1,
                policy = { nonEvictingTestPolicy() },
            )
            assertEquals(
                "e1",
                restartedBeforePublication.loadRetainedListing(ACCOUNT_ID, "Photos")
                    ?.nodes?.single()?.remoteRevision,
            )
            assertContentEquals(
                "old!".encodeToByteArray(),
                restartedBeforePublication.readBlock(ACCOUNT_ID, "Photos/photo.raf", "e1", 4L, 0L, 4),
            )
            assertNull(
                restartedBeforePublication.readBlock(ACCOUNT_ID, "Photos/photo.raf", "e2", 4L, 0L, 4),
            )
            assertContentEquals(
                "last".encodeToByteArray(),
                restartedBeforePublication.readBlock(ACCOUNT_ID, "Photos/photo.raf", "e3", 4L, 0L, 4),
            )

            restartedBeforePublication.publishRetainedListings(
                ACCOUNT_ID,
                "Photos",
                mapOf(
                    "Photos" to LinuxVirtualDirectorySnapshot(
                        listOf(LinuxVirtualFileNode("Photos/photo.raf", "photo.raf", false, 4L, "e3")),
                        20L,
                    ),
                ),
            )
            restartedBeforePublication.publishRetainedRevisions(
                ACCOUNT_ID,
                "Photos",
                listOf(VirtualRangeRevision("Photos/photo.raf", "e3", 4L)),
                retention,
            )

            assertNull(
                restartedBeforePublication.readBlock(ACCOUNT_ID, "Photos/photo.raf", "e1", 4L, 0L, 4),
            )
            assertContentEquals(
                "last".encodeToByteArray(),
                restartedBeforePublication.readBlock(ACCOUNT_ID, "Photos/photo.raf", "e3", 4L, 0L, 4),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `staged replacement waits while a previous generation is open`() {
        val directory = Files.createTempDirectory("virtual-range-open-generation-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.storeBlock(ACCOUNT_ID, "Photos/photo.raf", "e1", 4L, 0L, "old!".encodeToByteArray())
            cache.acquire(ACCOUNT_ID, "Photos/photo.raf")
            cache.acquire(ACCOUNT_ID, "Photos/photo.raf")

            cache.beginRevisionStaging(ACCOUNT_ID, "Photos/photo.raf", "e2", 4L).use { staging ->
                staging.store(0L, "new!".encodeToByteArray())
                assertFailsWith<IllegalStateException> { staging.commitIfComplete() }
            }

            assertContentEquals(
                "old!".encodeToByteArray(),
                cache.readBlock(ACCOUNT_ID, "Photos/photo.raf", "e1", 4L, 0L, 4),
            )
            assertNull(cache.readBlock(ACCOUNT_ID, "Photos/photo.raf", "e2", 4L, 0L, 4))
            cache.release(ACCOUNT_ID, "Photos/photo.raf")
            cache.release(ACCOUNT_ID, "Photos/photo.raf")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `revision coverage validates many files from one index snapshot`() {
        val directory = Files.createTempDirectory("virtual-range-batch-coverage-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.storeBlock(ACCOUNT_ID, "Photos/a.raf", "a1", 4L, 0L, "aaaa".encodeToByteArray())
            cache.storeBlock(ACCOUNT_ID, "Photos/b.raf", "b1", 4L, 0L, "bbbb".encodeToByteArray())
            val expected = listOf(
                VirtualRangeRevision("Photos/a.raf", "a1", 4L),
                VirtualRangeRevision("Photos/b.raf", "b1", 4L),
            )

            assertEquals(expected.toSet(), cache.completeRevisions(ACCOUNT_ID, expected))
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
    fun `releasing deep retained roots removes every ancestor no longer in use`() {
        val directory = Files.createTempDirectory("virtual-range-retained-deep-release-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Top/Mid/Leaf", VirtualFolderRetention.KeepOnDevice)
            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Top/Mid/Leaf",
                mapOf(
                    "" to LinuxVirtualDirectorySnapshot(emptyList(), 42L),
                    "Top" to LinuxVirtualDirectorySnapshot(emptyList(), 42L),
                    "Top/Mid" to LinuxVirtualDirectorySnapshot(emptyList(), 42L),
                    "Top/Mid/Leaf" to LinuxVirtualDirectorySnapshot(emptyList(), 42L),
                ),
            )
            cache.setFolderRetention(ACCOUNT_ID, "Documents/Reports", VirtualFolderRetention.KeepOnDevice)
            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Documents/Reports",
                mapOf(
                    "" to LinuxVirtualDirectorySnapshot(emptyList(), 43L),
                    "Documents" to LinuxVirtualDirectorySnapshot(emptyList(), 43L),
                    "Documents/Reports" to LinuxVirtualDirectorySnapshot(emptyList(), 43L),
                ),
            )

            cache.setFolderRetention(ACCOUNT_ID, "Top/Mid/Leaf", VirtualFolderRetention.Automatic)
            cache.dehydrateFolder(ACCOUNT_ID, "Top/Mid/Leaf", emptySet())

            assertEquals(
                setOf("", "Documents", "Documents/Reports"),
                cache.retainedListingPaths(ACCOUNT_ID),
            )

            cache.setFolderRetention(ACCOUNT_ID, "Documents/Reports", VirtualFolderRetention.Automatic)
            cache.dehydrateFolder(ACCOUNT_ID, "Documents/Reports", emptySet())
            assertEquals(emptySet(), cache.retainedListingPaths(ACCOUNT_ID))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `publication preflight counts listings retained by other roots`() {
        val directory = Files.createTempDirectory("virtual-range-retained-preflight-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos/A", VirtualFolderRetention.KeepOnDevice)
            cache.setFolderRetention(ACCOUNT_ID, "Photos/B", VirtualFolderRetention.KeepOnDevice)
            val photos = LinuxVirtualDirectorySnapshot(emptyList(), 42L)
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

            assertEquals(
                3,
                cache.retainedListingCountSurvivingPublication(
                    ACCOUNT_ID,
                    "Documents",
                    setOf("", "Documents"),
                ),
            )
            assertEquals(
                1,
                cache.retainedListingCountSurvivingPublication(
                    ACCOUNT_ID,
                    "Photos/A",
                    setOf("", "Photos", "Photos/A"),
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `refreshing a parent preserves listings required by a nested retained root`() {
        val directory = Files.createTempDirectory("virtual-range-retained-nested-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.setFolderRetention(ACCOUNT_ID, "Photos/Archive", VirtualFolderRetention.Automatic)
            cache.setFolderRetention(
                ACCOUNT_ID,
                "Photos/Archive/Favorites",
                VirtualFolderRetention.KeepOnDevice,
            )
            val archive = LinuxVirtualDirectorySnapshot(
                listOf(LinuxVirtualFileNode("Photos/Archive/Favorites", "Favorites", true, 0L, "favorites")),
                42L,
            )
            val favorites = LinuxVirtualDirectorySnapshot(
                listOf(
                    LinuxVirtualFileNode(
                        "Photos/Archive/Favorites/kept.raf",
                        "kept.raf",
                        false,
                        4L,
                        "kept",
                    ),
                ),
                42L,
            )
            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Photos/Archive/Favorites",
                mapOf(
                    "" to LinuxVirtualDirectorySnapshot(emptyList(), 42L),
                    "Photos" to LinuxVirtualDirectorySnapshot(emptyList(), 42L),
                    "Photos/Archive" to archive,
                    "Photos/Archive/Favorites" to favorites,
                ),
            )

            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Photos",
                mapOf("Photos" to LinuxVirtualDirectorySnapshot(emptyList(), 43L)),
            )

            assertEquals(
                listOf("Favorites"),
                requireNotNull(cache.loadRetainedListing(ACCOUNT_ID, "Photos/Archive"))
                    .nodes.map(LinuxVirtualFileNode::name),
            )
            assertEquals(
                listOf("kept.raf"),
                requireNotNull(cache.loadRetainedListing(ACCOUNT_ID, "Photos/Archive/Favorites"))
                    .nodes.map(LinuxVirtualFileNode::name),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `refreshing a parent preserves blocks owned by a nested retained root`() {
        val directory = Files.createTempDirectory("virtual-range-retained-nested-blocks-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.setFolderRetention(ACCOUNT_ID, "Photos/Archive", VirtualFolderRetention.Automatic)
            cache.setFolderRetention(
                ACCOUNT_ID,
                "Photos/Archive/Favorites",
                VirtualFolderRetention.KeepOnDevice,
            )
            cache.storeBlock(ACCOUNT_ID, "Photos/old.raf", "old", 4L, 0L, "old!".encodeToByteArray())
            cache.storeBlock(
                ACCOUNT_ID,
                "Photos/Archive/Favorites/kept.raf",
                "kept",
                4L,
                0L,
                "keep".encodeToByteArray(),
            )
            val retention = cache.loadFolderRetention(ACCOUNT_ID)

            cache.publishRetainedRevisions(ACCOUNT_ID, "Photos", emptyList(), retention)

            assertNull(cache.readBlock(ACCOUNT_ID, "Photos/old.raf", "old", 4L, 0L, 4))
            assertContentEquals(
                "keep".encodeToByteArray(),
                cache.readBlock(
                    ACCOUNT_ID,
                    "Photos/Archive/Favorites/kept.raf",
                    "kept",
                    4L,
                    0L,
                    4,
                ),
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
    fun `startup recovery removes promoted blocks from an abandoned commit journal`() {
        val directory = Files.createTempDirectory("virtual-range-promoted-recovery-").toFile()
        try {
            val accountDirectory = directory.resolve(ACCOUNT_ID)
            Files.createDirectory(accountDirectory.toPath())
            val stageId = "00000000-0000-0000-0000-000000000001"
            val blockName = "a".repeat(64) + ".block"
            val promoted = accountDirectory.resolve(blockName).apply { writeText("unpublished") }
            val journal = accountDirectory.resolve("range-revision.$stageId.commit").apply {
                writeText(blockName)
            }
            val lease = accountDirectory.resolve("range-revision.$stageId.lock").apply { writeText("") }

            DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }.summary(ACCOUNT_ID)

            assertFalse(promoted.exists())
            assertFalse(journal.exists())
            assertFalse(lease.exists())

            val orphanId = "00000000-0000-0000-0000-000000000002"
            val orphanName = "b".repeat(64) + ".block"
            val orphan = accountDirectory.resolve(orphanName).apply { writeText("unpublished") }
            val orphanJournal = accountDirectory.resolve("range-revision.$orphanId.commit").apply {
                writeText(orphanName)
            }
            DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }.summary(ACCOUNT_ID)
            assertFalse(orphan.exists())
            assertFalse(orphanJournal.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `unreadable range index queues previously available offline folders`() {
        val directory = Files.createTempDirectory("virtual-range-index-revalidation-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.storeBlock(ACCOUNT_ID, "Photos/photo.raf", "e1", 4L, 0L, "data".encodeToByteArray())
            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Photos",
                mapOf(
                    "Photos" to LinuxVirtualDirectorySnapshot(
                        listOf(LinuxVirtualFileNode("Photos/photo.raf", "photo.raf", false, 4L, "e1")),
                        42L,
                    ),
                ),
            )
            cache.setFolderHydrationStatus(
                ACCOUNT_ID,
                VirtualFolderHydrationStatus("Photos", VirtualFolderHydrationPhase.AvailableOffline),
            )
            directory.resolve(ACCOUNT_ID).resolve("range-index-v1.json").writeText("not-json")

            assertEquals(
                VirtualFolderHydrationPhase.Queued,
                cache.loadValidatedFolderHydrationStatus(ACCOUNT_ID, "Photos")?.phase,
            )
            assertEquals(
                VirtualFolderHydrationPhase.Queued,
                DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
                    .loadFolderHydrationStatuses(ACCOUNT_ID).single().phase,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `same length block corruption queues a previously available offline folder`() {
        val directory = Files.createTempDirectory("virtual-range-digest-revalidation-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.storeBlock(ACCOUNT_ID, "Photos/photo.raf", "e1", 4L, 0L, "data".encodeToByteArray())
            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Photos",
                mapOf(
                    "Photos" to LinuxVirtualDirectorySnapshot(
                        listOf(LinuxVirtualFileNode("Photos/photo.raf", "photo.raf", false, 4L, "e1")),
                        42L,
                    ),
                ),
            )
            cache.setFolderHydrationStatus(
                ACCOUNT_ID,
                VirtualFolderHydrationStatus("Photos", VirtualFolderHydrationPhase.AvailableOffline),
            )
            val block = requireNotNull(directory.resolve(ACCOUNT_ID).listFiles())
                .single { candidate ->
                    candidate.name.endsWith(".block") &&
                        candidate.readBytes().contentEquals("data".encodeToByteArray())
                }
            block.writeBytes("evil".encodeToByteArray())

            assertEquals(
                VirtualFolderHydrationPhase.Queued,
                cache.loadValidatedFolderHydrationStatus(ACCOUNT_ID, "Photos")?.phase,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `namespace mutation invalidates a retained parent listing`() {
        val directory = Files.createTempDirectory("virtual-range-parent-invalidation-").toFile()
        try {
            val cache = DesktopVirtualRangeCache(directory) { nonEvictingTestPolicy() }
            cache.setFolderRetention(ACCOUNT_ID, "Photos", VirtualFolderRetention.KeepOnDevice)
            cache.publishRetainedListings(
                ACCOUNT_ID,
                "Photos",
                mapOf(
                    "Photos" to LinuxVirtualDirectorySnapshot(
                        listOf(LinuxVirtualFileNode("Photos/old.jpg", "old.jpg", false, 4L, "e1")),
                        42L,
                    ),
                ),
            )

            cache.invalidateRetainedListings(ACCOUNT_ID, "Photos/new.jpg")

            assertEquals(null, cache.loadRetainedListing(ACCOUNT_ID, "Photos"))
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
            assertEquals("Photos", retainedFolderNavigationChild("", "Photos/2026/August"))
            assertEquals("Photos/2026", retainedFolderNavigationChild("Photos", "Photos/2026/August"))
            assertNull(retainedFolderNavigationChild("Documents", "Photos/2026/August"))
            assertFalse(isCompleteRetainedTreeListing("", "Photos/2026"))
            assertFalse(isCompleteRetainedTreeListing("Photos", "Photos/2026"))
            assertTrue(isCompleteRetainedTreeListing("Photos/2026", "Photos/2026"))
            assertTrue(isCompleteRetainedTreeListing("Photos/2026/August", "Photos/2026"))
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
