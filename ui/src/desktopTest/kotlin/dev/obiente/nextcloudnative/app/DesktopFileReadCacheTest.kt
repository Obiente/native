package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopFileReadCacheTest {
    @Test
    fun `metadata and content survive a new cache instance without storing credentials`() = withCache { root, cache ->
        val session = session(password = "first-secret")
        val accountId = desktopFileCacheAccountId(session)
        val file = file("Notes/vault.md", "\"etag-1\"")
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
        val restored = DesktopFileReadCache(root, preferences = testPreferences(root))
        val listing = restored.cachedListingSnapshot(accountId, "Library")

        assertEquals(6_001, listing?.files?.size)
        assertEquals(123_456, listing?.fetchedAtEpochMillis)
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
