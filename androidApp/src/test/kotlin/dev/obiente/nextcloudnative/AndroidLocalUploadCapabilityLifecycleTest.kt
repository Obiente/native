package dev.obiente.nextcloudnative

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
}
