package dev.obiente.nextcloudnative

import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidLocalUploadCapabilityLifecycleTest {
    @Test
    fun `permission is taken before metadata commit and retained after success`() {
        val events = mutableListOf<String>()

        acquireDurableUploadCapability(
            takePermission = { events += "permission" },
            persistMetadata = {
                events += "metadata"
                true
            },
            releasePermission = { events += "release" },
        )

        assertEquals(listOf("permission", "metadata"), events)
    }

    @Test
    fun `failed metadata commit rolls back the persisted uri permission`() {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            acquireDurableUploadCapability(
                takePermission = { events += "permission" },
                persistMetadata = {
                    events += "metadata"
                    false
                },
                releasePermission = { events += "release" },
            )
        }

        assertEquals(listOf("permission", "metadata", "release"), events)
    }

    @Test
    fun `permission release precedes synchronous metadata deletion`() {
        val events = mutableListOf<String>()

        val released = releaseDurableUploadCapability(
            releasePermission = { events += "permission" },
            removeMetadata = {
                events += "metadata"
                true
            },
        )

        assertTrue(released)
        assertEquals(listOf("permission", "metadata"), events)
    }

    @Test
    fun `failed metadata deletion still revokes permission`() {
        var permissionReleased = false

        val released = releaseDurableUploadCapability(
            releasePermission = { permissionReleased = true },
            removeMetadata = { false },
        )

        assertFalse(released)
        assertTrue(permissionReleased)
    }

    @Test
    fun `uncached unreadable encrypted metadata is retained without claiming capability release`() {
        var encryptedMetadata: String? = "unreadable-encrypted-capability"
        var permissionReleased = false
        var cleanupPending = true

        val result = resultAfterDurableUploadCapabilityRelease(
            releaseCapability = {
                releaseStoredDurableUploadCapability<String>(
                    cachedCapability = null,
                    loadCapability = { throw GeneralSecurityException("synthetic decryption failure") },
                    releasePermission = { permissionReleased = true },
                    removeMetadata = { true.also { encryptedMetadata = null } },
                )
            },
            completeCapabilityCleanup = { cleanupPending = false },
            releasedResult = "finished",
            retainedResult = "retry",
        )

        assertEquals("retry", result)
        assertTrue(cleanupPending)
        assertFalse(permissionReleased)
        assertEquals("unreadable-encrypted-capability", encryptedMetadata)
    }

    @Test
    fun `restored capability release revokes permission before deleting metadata`() {
        val events = mutableListOf<String>()

        val released = releaseStoredDurableUploadCapability(
            cachedCapability = null,
            loadCapability = {
                events += "restore"
                "content://synthetic/upload"
            },
            releasePermission = { events += "permission:$it" },
            removeMetadata = {
                events += "metadata"
                true
            },
        )

        assertTrue(released)
        assertEquals(
            listOf("restore", "permission:content://synthetic/upload", "metadata"),
            events,
        )
    }

    @Test
    fun `missing capability metadata is an idempotent cleanup success`() {
        var metadataRemovals = 0

        val released = releaseStoredDurableUploadCapability<String>(
            cachedCapability = null,
            loadCapability = { null },
            releasePermission = { error("Missing metadata has no URI grant to release.") },
            removeMetadata = {
                metadataRemovals += 1
                true
            },
        )

        assertTrue(released)
        assertEquals(1, metadataRemovals)
    }

    @Test
    fun `cached capability releases without reading redundant stored metadata`() {
        val events = mutableListOf<String>()

        val released = releaseStoredDurableUploadCapability(
            cachedCapability = "content://cached/upload",
            loadCapability = { error("Cached cleanup must not read stored metadata.") },
            releasePermission = { events += "permission:$it" },
            removeMetadata = {
                events += "metadata"
                true
            },
        )

        assertTrue(released)
        assertEquals(
            listOf("permission:content://cached/upload", "metadata"),
            events,
        )
    }

    @Test
    fun `capability restore preserves cancellation`() {
        assertFailsWith<CancellationException> {
            releaseStoredDurableUploadCapability<String>(
                cachedCapability = null,
                loadCapability = { throw CancellationException("cleanup stopped") },
                releasePermission = {},
                removeMetadata = { true },
            )
        }
    }
}
