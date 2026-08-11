package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class DesktopFileReadCacheTest {
    @Test
    fun `metadata and content survive a new cache instance without storing credentials`() = withCache { root, cache ->
        val session = session(password = "first-secret")
        val accountId = desktopFileCacheAccountId(session)
        val file = file("Notes/vault.md", "\"etag-1\"").copy(
            favorite = true,
            ownerId = "morgan",
            ownerDisplayName = "Morgan Lee",
            unreadComments = 4,
        )
        cache.storeListing(accountId, "Notes", listOf(file), nowEpochMillis = 10)
        assertTrue(
            cache.storeContent(
                accountId,
                file.path,
                NextcloudFileContent("hello".encodeToByteArray(), "text/markdown", file.etag),
                nowEpochMillis = 20,
            ),
        )

        val restored = DesktopFileReadCache(root, preferences = testPreferences(root))

        assertEquals(listOf(file), restored.cachedListing(accountId, "Notes"))
        assertContentEquals(
            "hello".encodeToByteArray(),
            restored.cachedContent(accountId, file.path, 100)?.bytes,
        )
        assertEquals(accountId, desktopFileCacheAccountId(session(password = "rotated-secret")))
        assertFalse(root.walkTopDown().filter { it.isFile }.any { stored ->
            stored.readBytes().decodeToString().contains("first-secret")
        })
    }

    @Test
    fun `failed virtual metadata invalidation quarantines persisted listings after restart`() =
        withCache { root, cache ->
            val accountId = desktopFileCacheAccountId(session())
            val preferences = testPreferences(root)
            cache.replaceFailedVirtualListingInvalidations(accountId, setOf("Photos/Changed"))

            val restarted = DesktopFileReadCache(root, preferences = preferences)
            assertEquals(setOf(""), restarted.failedVirtualListingInvalidations(accountId))

            restarted.replaceFailedVirtualListingInvalidations(accountId, emptySet())
            assertEquals(
                emptySet(),
                DesktopFileReadCache(root, preferences = preferences)
                    .failedVirtualListingInvalidations(accountId),
            )
        }

    @Test
    fun `virtual metadata preserves response completion freshness after restart`() =
        withCache { root, cache ->
            val accountId = desktopFileCacheAccountId(session())
            cache.storeVirtualListingUnlessNewer(
                accountId = accountId,
                path = "Photos",
                nodes = listOf(LinuxVirtualFileNode("Photos/a.jpg", "a.jpg", false, 4L, "etag-a")),
                fetchedAtEpochMillis = 10L,
                freshAtEpochMillis = 6_010L,
            )

            val restored = DesktopFileReadCache(root, preferences = testPreferences(root))
            val snapshot = restored.cachedVirtualListingSnapshot(accountId, "Photos")

            assertEquals(10L, snapshot?.fetchedAtEpochMillis)
            assertEquals(6_010L, snapshot?.freshAtEpochMillis)
        }

    @Test
    fun `folder refresh invalidates only removed or changed ETag generations`() = withCache { _, cache ->
        val accountId = desktopFileCacheAccountId(session())
        val stable = file("Notes/stable.md", "\"same\"")
        val changed = file("Notes/changed.md", "\"old\"")
        cache.storeListing(accountId, "Notes", listOf(stable, changed), 10)
        cache.storeContent(
            accountId,
            stable.path,
            NextcloudFileContent(byteArrayOf(1), stable.mimeType, stable.etag),
            20,
        )
        cache.storeContent(
            accountId,
            changed.path,
            NextcloudFileContent(byteArrayOf(2), changed.mimeType, changed.etag),
            30,
        )

        cache.storeListing(
            accountId,
            "Notes",
            listOf(stable, changed.copy(etag = "\"new\"")),
            40,
        )

        assertContentEquals(byteArrayOf(1), cache.cachedContent(accountId, stable.path, 10)?.bytes)
        assertNull(cache.cachedContent(accountId, changed.path, 10))
    }

    @Test
    fun `content cache enforces per entry request and total byte bounds`() = withCache(
        maximumContentBytes = 8,
        maximumEntryBytes = 6,
    ) { _, cache ->
        val accountId = desktopFileCacheAccountId(session())
        assertTrue(
            cache.storeContent(
                accountId,
                "one.bin",
                NextcloudFileContent(ByteArray(5) { 1 }, "application/octet-stream", "\"one\""),
                10,
            ),
        )
        assertTrue(
            cache.storeContent(
                accountId,
                "two.bin",
                NextcloudFileContent(ByteArray(5) { 2 }, "application/octet-stream", "\"two\""),
                20,
            ),
        )

        assertNull(cache.cachedContent(accountId, "one.bin", 10))
        assertContentEquals(ByteArray(5) { 2 }, cache.cachedContent(accountId, "two.bin", 10)?.bytes)
        assertNull(cache.cachedContent(accountId, "two.bin", 4))
        assertFalse(
            cache.storeContent(
                accountId,
                "large.bin",
                NextcloudFileContent(ByteArray(7), "application/octet-stream", "\"large\""),
                30,
            ),
        )
    }

    @Test
    fun `invalidation removes exact content descendants and affected listings`() = withCache { _, cache ->
        val accountId = desktopFileCacheAccountId(session())
        val child = file("Folder/child.txt", "\"child\"")
        cache.storeListing(accountId, "", listOf(directory("Folder")), 10)
        cache.storeListing(accountId, "Folder", listOf(child), 20)
        cache.storeContent(
            accountId,
            child.path,
            NextcloudFileContent(byteArrayOf(7), child.mimeType, child.etag),
            30,
        )

        cache.invalidate(accountId, "Folder")

        assertNull(cache.cachedListing(accountId, ""))
        assertNull(cache.cachedListing(accountId, "Folder"))
        assertNull(cache.cachedContent(accountId, child.path, 10))
    }

    @Test
    fun `nested invalidation removes every persisted ancestor listing`() = withCache { _, cache ->
        val accountId = desktopFileCacheAccountId(session())
        cache.storeListing(accountId, "", listOf(directory("Photos"), directory("Archive")), 10)
        cache.storeListing(accountId, "Photos", listOf(directory("Photos/Album")), 20)
        cache.storeListing(accountId, "Photos/Album", listOf(directory("Photos/Album/Day")), 30)
        cache.storeListing(accountId, "Photos/Album/Day", listOf(file("Photos/Album/Day/photo.raf", "e1")), 40)
        cache.storeListing(accountId, "Archive", emptyList(), 50)

        cache.invalidate(accountId, "Photos/Album/Day/photo.raf")

        assertNull(cache.cachedListing(accountId, ""))
        assertNull(cache.cachedListing(accountId, "Photos"))
        assertNull(cache.cachedListing(accountId, "Photos/Album"))
        assertNull(cache.cachedListing(accountId, "Photos/Album/Day"))
        assertEquals(emptyList(), cache.cachedListing(accountId, "Archive"))
    }

    @Test
    fun `corrupt disposable index never exposes orphaned content`() = withCache { root, cache ->
        val accountId = desktopFileCacheAccountId(session())
        cache.storeContent(
            accountId,
            "safe.txt",
            NextcloudFileContent(byteArrayOf(1), "text/plain", "\"safe\""),
            10,
        )
        val index = root.resolve(accountId).resolve("index-v1.json")
        index.writeText("{not-json")

        val restored = DesktopFileReadCache(root, preferences = testPreferences(root))

        assertNull(restored.cachedContent(accountId, "safe.txt", 10))
    }

    @Test
    fun `large folder metadata and refresh timestamp survive restart`() = withCache { root, cache ->
        val accountId = desktopFileCacheAccountId(session())
        val files = List(6_001) { index ->
            file("Library/photo-${index.toString().padStart(5, '0')}.jpg", "\"etag-$index\"")
        }

        cache.storeListing(accountId, "Library", files, nowEpochMillis = 123_456)
        assertEquals(
            24,
            root.resolve(accountId).listFiles().orEmpty().count { it.extension == "metadata" },
        )
        val restored = DesktopFileReadCache(root, preferences = testPreferences(root))
        val listing = restored.cachedListingSnapshot(accountId, "Library")

        assertEquals(6_001, listing?.files?.size)
        assertEquals(123_456, listing?.fetchedAtEpochMillis)
    }

    @Test
    fun `large listings are sharded beneath the bounded account index`() {
        val root = Files.createTempDirectory("ncn-files-cache-shards-").toFile()
        val preferences = testPreferences(root)
        val maximumIndexBytes = 64L * 1024L
        try {
            val accountId = "a".repeat(64)
            val files = List(300) { index ->
                val name = "${index.toString().padStart(3, '0')}-${"x".repeat(900)}.jpg"
                file("Library/$name", "\"etag-${"y".repeat(500)}-$index\"")
            }
            DesktopFileReadCache(
                root = root,
                preferences = preferences,
                maximumIndexBytes = maximumIndexBytes,
            ).storeListing(accountId, "Library", files, nowEpochMillis = 123_456L)

            val accountDirectory = root.resolve(accountId)
            assertTrue(accountDirectory.resolve("index-v1.json").length() <= maximumIndexBytes)
            assertTrue(accountDirectory.listFiles().orEmpty().count { it.extension == "metadata" } > 1)

            val restored = DesktopFileReadCache(
                root = root,
                preferences = preferences,
                maximumIndexBytes = maximumIndexBytes,
            ).cachedListingSnapshot(accountId, "Library")
            assertEquals(files, restored?.files)
            assertEquals(123_456L, restored?.fetchedAtEpochMillis)
        } finally {
            runCatching { preferences.removeNode() }
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy v1 shard counts do not discard unrelated cached content`() = withCache { root, cache ->
        val accountId = desktopFileCacheAccountId(session())
        cache.storeListing(accountId, "Library", listOf(file("Library/photo.jpg", "library-etag")), 10L)
        assertTrue(
            cache.storeContent(
                accountId,
                "safe.bin",
                NextcloudFileContent(byteArrayOf(7), "application/octet-stream", "safe-etag"),
                20L,
            ),
        )
        val index = root.resolve(accountId).resolve("index-v1.json")
        val document = Json.parseToJsonElement(index.readText()).jsonObject
        val template = document.getValue("listingShards").jsonArray.single().jsonObject
        val legacyReferences = JsonArray(
            List(197) { partIndex ->
                JsonObject(
                    template.toMutableMap().apply {
                        put("partIndex", JsonPrimitive(partIndex))
                        put("partCount", JsonPrimitive(197))
                    },
                )
            },
        )
        index.writeText(
            JsonObject(
                document.toMutableMap().apply { put("listingShards", legacyReferences) },
            ).toString(),
        )

        val restored = DesktopFileReadCache(root, preferences = testPreferences(root))

        assertContentEquals(byteArrayOf(7), restored.cachedContent(accountId, "safe.bin", 10)?.bytes)
    }

    @Test
    fun `metadata shards stay byte bounded for maximum escaped records`() = withCache { root, cache ->
        val accountId = desktopFileCacheAccountId(session())
        val files = List(256) { index ->
            file("Library/photo-$index.jpg", "etag-$index").copy(
                name = "\"".repeat(1_024),
                mimeType = "\"".repeat(512),
                lastModified = "\"".repeat(128),
                etag = "\"".repeat(4_096),
                permissions = "\"".repeat(256),
                ownerId = "\"".repeat(1_024),
                ownerDisplayName = "\"".repeat(1_024),
                checksums = List(16) { "\"".repeat(512) },
            )
        }

        cache.storeListing(accountId, "Library", files, nowEpochMillis = 123_456L)
        val shards = root.resolve(accountId).listFiles().orEmpty().filter { it.extension == "metadata" }

        assertTrue(shards.size > 1)
        assertTrue(shards.all { shard -> shard.length() <= 4L * 1024L * 1024L })
        assertEquals(
            files,
            DesktopFileReadCache(root, preferences = testPreferences(root))
                .cachedListing(accountId, "Library"),
        )
    }

    @Test
    fun `one corrupt metadata shard does not discard unrelated listings`() = withCache { root, cache ->
        val accountId = desktopFileCacheAccountId(session())
        cache.storeListing(accountId, "Photos", listOf(file("Photos/photo.jpg", "photos")), 10L)
        cache.storeListing(accountId, "Notes", listOf(file("Notes/note.txt", "notes")), 20L)
        val shards = root.resolve(accountId).listFiles().orEmpty().filter { it.extension == "metadata" }
        assertEquals(2, shards.size)
        shards.first().writeText("corrupt")

        val restored = DesktopFileReadCache(root, preferences = testPreferences(root))
        assertEquals(1, restored.cachedListingPaths(accountId).size)
    }

    @Test
    fun `content-only saves reuse verified metadata shards without reading them again`() = withCache { root, cache ->
        val accountId = desktopFileCacheAccountId(session())
        val files = List(200) { index -> file("Library/photo-$index.jpg", "etag-$index") }
        cache.storeListing(accountId, "Library", files, 10L)
        val shardReads = AtomicInteger()
        val restored = DesktopFileReadCache(
            root = root,
            preferences = testPreferences(root),
            metadataShardReadObserver = { shardReads.incrementAndGet() },
        )
        assertEquals(files, restored.cachedListing(accountId, "Library"))
        val readsAfterHydration = shardReads.get()

        assertTrue(
            restored.storeContent(
                accountId,
                "unrelated.bin",
                NextcloudFileContent(byteArrayOf(1), "application/octet-stream", "unrelated-etag"),
                20L,
            ),
        )

        assertEquals(readsAfterHydration, shardReads.get())
    }

    @Test
    fun `startup preserves over-budget metadata references for demand loading`() = withCache { root, cache ->
        val accountId = desktopFileCacheAccountId(session())
        val largeListing = List(257) { index -> file("First/$index.jpg", "first-$index") }
        cache.storeListing(accountId, "First", largeListing, 10L)
        cache.storeListing(accountId, "Second", listOf(file("Second/two.jpg", "second")), 20L)
        val shardBytes = root.resolve(accountId).listFiles().orEmpty()
            .filter { file -> file.extension == "metadata" }
            .maxOf { file -> file.length() }

        val shardReads = AtomicInteger()
        val restored = DesktopFileReadCache(
            root = root,
            preferences = testPreferences(root),
            maximumHydratedMetadataBytes = shardBytes,
            metadataShardReadObserver = { shardReads.incrementAndGet() },
        )

        assertEquals(setOf("First", "Second"), restored.cachedListingPaths(accountId))
        assertEquals(1, shardReads.get())
        assertTrue(
            restored.storeContent(
                accountId,
                "unrelated.bin",
                NextcloudFileContent(byteArrayOf(1), "application/octet-stream", "unrelated"),
                30L,
            ),
        )
        assertEquals(1, shardReads.get())
        assertEquals(largeListing, restored.cachedListing(accountId, "First"))
        assertEquals(listOf(file("Second/two.jpg", "second")), restored.cachedListing(accountId, "Second"))
        assertEquals(4, shardReads.get())

        val restarted = DesktopFileReadCache(
            root = root,
            preferences = testPreferences(root),
            maximumHydratedMetadataBytes = shardBytes,
        )
        assertEquals(setOf("First", "Second"), restarted.cachedListingPaths(accountId))
    }

    @Test
    fun `authoritative listing replaces a future timestamp after clock rollback`() = withCache { _, cache ->
        val accountId = desktopFileCacheAccountId(session())
        cache.storeListing(accountId, "Photos", listOf(file("Photos/old.jpg", "old")), 10_000L)

        assertTrue(
            cache.storeListingUnlessNewer(
                accountId = accountId,
                path = "Photos",
                files = listOf(file("Photos/current.jpg", "current")),
                fetchedAtEpochMillis = 5_000L,
                nowEpochMillis = 5_000L,
            ),
        )

        assertEquals("Photos/current.jpg", cache.cachedListing(accountId, "Photos")?.single()?.path)
        assertEquals(5_000L, cache.cachedListingSnapshot(accountId, "Photos")?.fetchedAtEpochMillis)
    }

    @Test
    fun `delayed listing cannot replace a request that started later`() = withCache { _, cache ->
        val accountId = desktopFileCacheAccountId(session())
        cache.storeListing(accountId, "Photos", listOf(file("Photos/current.jpg", "current")), 200L)

        assertFalse(
            cache.storeListingUnlessNewer(
                accountId = accountId,
                path = "Photos",
                files = listOf(file("Photos/stale.jpg", "stale")),
                fetchedAtEpochMillis = 100L,
                nowEpochMillis = 300L,
            ),
        )

        assertEquals("Photos/current.jpg", cache.cachedListing(accountId, "Photos")?.single()?.path)
    }

    @Test
    fun `decoded account indexes are retained within an LRU bound`() {
        val root = Files.createTempDirectory("ncn-files-cache-accounts-").toFile()
        val preferences = testPreferences(root)
        try {
            val cache = DesktopFileReadCache(
                root = root,
                preferences = preferences,
                maximumLoadedAccountIndexes = 1,
            )
            val firstAccount = "1".repeat(64)
            val secondAccount = "2".repeat(64)
            cache.storeListing(firstAccount, "Notes", listOf(file("Notes/first.txt", "e1")), 1L)
            cache.storeListing(secondAccount, "Notes", listOf(file("Notes/second.txt", "e2")), 2L)

            assertTrue(root.resolve(firstAccount).resolve("index-v1.json").delete())
            assertNull(cache.cachedListing(firstAccount, "Notes"))
            assertEquals("Notes/second.txt", cache.cachedListing(secondAccount, "Notes")?.single()?.path)
        } finally {
            runCatching { preferences.removeNode() }
            root.deleteRecursively()
        }
    }

    @Test
    fun `Files metadata cannot consume the reserved virtual listing capacity`() {
        val root = Files.createTempDirectory("ncn-files-cache-reserve-").toFile()
        val preferences = testPreferences(root)
        try {
            val accountId = "0".repeat(64)
            val cache = DesktopFileReadCache(
                root = root,
                preferences = preferences,
                maximumTotalMetadataEntries = 10,
            )
            repeat(3) { listing ->
                cache.storeListing(
                    accountId,
                    "Folder-$listing",
                    List(4) { index -> file("Folder-$listing/file-$index.txt", "e-$listing-$index") },
                    nowEpochMillis = listing.toLong(),
                )
            }
            cache.storeVirtualListingUnlessNewer(
                accountId = accountId,
                path = "Virtual",
                nodes = listOf(LinuxVirtualFileNode("Virtual/photo.raf", "photo.raf", false, 4L, "virtual-e1")),
                fetchedAtEpochMillis = 10L,
            )

            val restarted = DesktopFileReadCache(
                root = root,
                preferences = preferences,
                maximumTotalMetadataEntries = 10,
            )
            assertEquals("virtual-e1", restarted.cachedVirtualListingSnapshot(accountId, "Virtual")
                ?.nodes?.single()?.remoteRevision)
        } finally {
            runCatching { preferences.removeNode() }
            root.deleteRecursively()
        }
    }

    private fun session(password: String = "secret") = NextcloudSession(
        serverUrl = "https://cloud.example.test",
        loginName = "alice",
        appPassword = password,
    )

    private fun file(path: String, etag: String) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = "text/plain",
        size = 5,
        lastModified = "2026-07-23T10:00:00Z",
        fileId = 7,
        hasPreview = false,
        etag = etag,
    )

    private fun directory(path: String) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = true,
        mimeType = null,
        size = null,
        lastModified = null,
        fileId = 8,
        hasPreview = false,
        etag = "\"folder\"",
    )

    private fun withCache(
        maximumContentBytes: Long = 128L * 1024L * 1024L,
        maximumEntryBytes: Long = 16L * 1024L * 1024L,
        block: (java.io.File, DesktopFileReadCache) -> Unit,
    ) {
        val root = Files.createTempDirectory("ncn-files-cache-").toFile()
        val preferences = testPreferences(root)
        try {
            preferences.clear()
            preferences.putBoolean("automatic-cleanup", false)
            block(
                root,
                DesktopFileReadCache(root, maximumContentBytes, maximumEntryBytes, preferences),
            )
        } finally {
            preferences.removeNode()
            root.deleteRecursively()
        }
    }

    private fun testPreferences(root: java.io.File): Preferences = Preferences.userRoot()
        .node("dev/obiente/nextcloudnative/tests/file-cache/${root.name}")
}
