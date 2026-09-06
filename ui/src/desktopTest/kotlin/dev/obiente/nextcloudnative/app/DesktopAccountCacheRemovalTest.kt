package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAccountCacheRemovalTest {
    @Test
    fun accountRemovalPurgesPrivateFilesPersistentInvalidationsAndRangeIndexes() {
        val root = Files.createTempDirectory("desktop-account-cache-removal-").toFile()
        val filesRoot = root.resolve("files").apply { mkdir() }
        val rangesRoot = root.resolve("ranges").apply { mkdir() }
        val preferences = Preferences.userRoot().node("desktop-account-cache-removal-${UUID.randomUUID()}")
        try {
            val files = DesktopFileReadCache(filesRoot, preferences = preferences)
            val ranges = DesktopVirtualRangeCache(rangesRoot) { testPolicy() }
            files.storeContent(
                ACCOUNT_ID,
                "Private/secret.txt",
                NextcloudFileContent("private bytes".encodeToByteArray(), "text/plain", "etag-1"),
            )
            files.replaceFailedVirtualListingInvalidations(ACCOUNT_ID, setOf("Private"))
            ranges.storeBlock(ACCOUNT_ID, "Private/secret.bin", "etag-2", 4L, 0L, "data".encodeToByteArray())

            files.removeAccount(ACCOUNT_ID)
            ranges.removeAccount(ACCOUNT_ID)

            assertFalse(filesRoot.resolve(ACCOUNT_ID).exists())
            assertFalse(rangesRoot.resolve(ACCOUNT_ID).exists())
            assertNull(files.cachedContent(ACCOUNT_ID, "Private/secret.txt", 64))
            assertTrue(files.failedVirtualListingInvalidations(ACCOUNT_ID).isEmpty())
            assertNull(ranges.readBlock(ACCOUNT_ID, "Private/secret.bin", "etag-2", 4L, 0L, 4))
        } finally {
            preferences.removeNode()
            root.deleteRecursively()
        }
    }

    @Test
    fun unavailableOverflowKeepsPrimaryCacheForJournalRetry() {
        val root = Files.createTempDirectory("desktop-account-cache-overflow-").toFile()
        val primary = root.resolve("primary").apply { mkdir() }
        val overflow = root.resolve("overflow").apply { mkdir() }
        val disconnected = root.resolve("disconnected")
        try {
            val ranges = DesktopVirtualRangeCache(
                root = primary,
                overflowRoot = overflow,
                initializeOverflowMarker = true,
                policy = { testPolicy() },
            )
            ranges.storeBlock(ACCOUNT_ID, "Private/secret.bin", "etag-1", 4L, 0L, "data".encodeToByteArray())
            overflow.resolve(ACCOUNT_ID).apply { mkdir() }.resolve("private.block").writeText("private")
            assertTrue(overflow.renameTo(disconnected))

            assertFailsWith<IllegalStateException> { ranges.removeAccount(ACCOUNT_ID) }
            assertTrue(primary.resolve(ACCOUNT_ID).isDirectory)
            assertTrue(disconnected.resolve(ACCOUNT_ID).isDirectory)

            assertTrue(disconnected.renameTo(overflow))
            ranges.removeAccount(ACCOUNT_ID)
            assertFalse(primary.resolve(ACCOUNT_ID).exists())
            assertFalse(overflow.resolve(ACCOUNT_ID).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun accountRemovalPurgesVirtualFileLocationPreferences() {
        val preferences = Preferences.userRoot().node("desktop-account-locations-${UUID.randomUUID()}")
        try {
            val rootKey = virtualFileProviderRootPreferenceKey(ACCOUNT_ID)
            val primaryKey = "vfpc-primary.$ACCOUNT_ID"
            val overflowKey = "vfpc-overflow.$ACCOUNT_ID"
            preferences.put(rootKey, "/private/mount")
            preferences.put(primaryKey, "/private/primary")
            preferences.put(overflowKey, "/private/overflow")
            preferences.flush()

            removeDesktopAccountVirtualFilePreferences(preferences, ACCOUNT_ID)

            assertNull(preferences.get(rootKey, null))
            assertNull(preferences.get(primaryKey, null))
            assertNull(preferences.get(overflowKey, null))
        } finally {
            preferences.removeNode()
        }
    }

    private companion object {
        const val ACCOUNT_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        fun testPolicy() = VirtualFileCachePolicy(
            automaticCleanup = false,
            minimumFreeSpaceBytes = 0L,
            unusedFileAgeMillis = null,
        )
    }
}
