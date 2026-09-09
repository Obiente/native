package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidFileReadCacheTest {
    @Test
    fun listingMetadataSurvivesProcessRestartWithFullDavIdentity() = withCache { root, cache ->
        val file = file("Notes/vault.md", "\"etag-1\"").copy(
            permissions = "RGDNVW",
            checksums = listOf("SHA256:abc"),
            originalAccessAllowed = false,
            favorite = true,
            ownerId = "morgan",
            ownerDisplayName = "Morgan Lee",
            unreadComments = 4,
        )
        cache.storeListing(ACCOUNT_A, "Notes", listOf(file), nowEpochMillis = 42)

        val restored = AndroidFileReadCache(root).cachedListing(ACCOUNT_A, "Notes")

        assertEquals(42, restored?.fetchedAtEpochMillis)
        assertEquals(listOf(file), restored?.files)
        assertTrue(root.walkTopDown().filter(File::isFile).none { stored ->
            stored.readBytes().decodeToString().contains("app-password")
        })
    }

    @Test
    fun accountIndexesCannotReadOrEvictEachOther() = withCache { _, cache ->
        val alice = file("Notes/alice.md", "\"a\"")
        val bob = file("Notes/bob.md", "\"b\"")
        cache.storeListing(ACCOUNT_A, "Notes", listOf(alice), 10)
        cache.storeListing(ACCOUNT_B, "Notes", listOf(bob), 20)

        assertEquals(listOf(alice), cache.cachedListing(ACCOUNT_A, "Notes")?.files)
        assertEquals(listOf(bob), cache.cachedListing(ACCOUNT_B, "Notes")?.files)

        cache.invalidate(ACCOUNT_A, "Notes/alice.md")

        assertNull(cache.cachedListing(ACCOUNT_A, "Notes"))
        assertEquals(listOf(bob), cache.cachedListing(ACCOUNT_B, "Notes")?.files)
    }

    @Test
    fun newestListingsWinBoundedMetadataQuota() = withCache(
        maximumListings = 3,
        maximumMetadataEntries = 3,
    ) { _, cache ->
        cache.storeListing(
            ACCOUNT_A,
            "Old",
            listOf(file("Old/one.txt", "\"1\""), file("Old/two.txt", "\"2\"")),
            10,
        )
        cache.storeListing(
            ACCOUNT_A,
            "New",
            listOf(file("New/three.txt", "\"3\""), file("New/four.txt", "\"4\"")),
            20,
        )

        assertNull(cache.cachedListing(ACCOUNT_A, "Old"))
        assertEquals(2, cache.cachedListing(ACCOUNT_A, "New")?.files?.size)
    }

    @Test
    fun changedDirectoryGenerationInvalidatesItsCachedDescendants() = withCache { _, cache ->
        val directory = directory("Projects", "\"folder-1\"")
        cache.storeListing(ACCOUNT_A, "", listOf(directory), 10)
        cache.storeListing(
            ACCOUNT_A,
            "Projects",
            listOf(file("Projects/readme.md", "\"file-1\"")),
            20,
        )
        cache.storeListing(
            ACCOUNT_A,
            "",
            listOf(directory.copy(etag = "\"folder-2\"")),
            30,
        )

        assertEquals("\"folder-2\"", cache.cachedListing(ACCOUNT_A, "")?.files?.single()?.etag)
        assertNull(cache.cachedListing(ACCOUNT_A, "Projects"))
    }

    @Test
    fun corruptOrInterruptedDisposableStateIsOnlyACacheMiss() = withCache { root, cache ->
        cache.storeListing(ACCOUNT_A, "Notes", listOf(file("Notes/a.md", "\"a\"")), 10)
        val accountDirectory = File(root, ACCOUNT_A)
        File(accountDirectory, "listings-v1.bin").writeText("not-a-cache")
        File(accountDirectory, "listings-v1.bin.interrupted.tmp").writeText("partial")

        val restored = AndroidFileReadCache(root)

        assertNull(restored.cachedListing(ACCOUNT_A, "Notes"))
    }

    private fun file(path: String, etag: String) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = "text/markdown",
        size = 12,
        lastModified = "Wed, 23 Jul 2026 10:00:00 GMT",
        fileId = 42,
        hasPreview = true,
        etag = etag,
    )

    private fun directory(path: String, etag: String) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = true,
        mimeType = null,
        size = null,
        lastModified = null,
        fileId = 7,
        hasPreview = false,
        etag = etag,
        permissions = "RGDNV",
    )

    private fun withCache(
        maximumListings: Int = 256,
        maximumMetadataEntries: Int = 20_000,
        block: (File, AndroidFileReadCache) -> Unit,
    ) {
        val root = Files.createTempDirectory("ncn-android-file-cache-").toFile()
        try {
            block(root, AndroidFileReadCache(root, maximumListings, maximumMetadataEntries))
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val ACCOUNT_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ACCOUNT_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
