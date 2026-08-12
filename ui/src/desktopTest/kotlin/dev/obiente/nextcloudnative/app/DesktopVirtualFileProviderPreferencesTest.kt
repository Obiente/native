package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopVirtualFileProviderPreferencesTest {
    @Test
    fun `provider activation key fits Java Preferences and remains account scoped`() {
        val firstAccountId = "a".repeat(64)
        val secondAccountId = "b".repeat(64)

        val firstKey = virtualFileProviderPreferenceKey(firstAccountId)
        val secondKey = virtualFileProviderPreferenceKey(secondAccountId)

        assertEquals("vfp-active.$firstAccountId", firstKey)
        assertTrue(firstKey.length <= Preferences.MAX_KEY_LENGTH)
        assertNotEquals(firstKey, secondKey)
    }

    @Test
    fun `failed provider cleanup retains the provider and blocks replacement`() {
        val cleanupFailure = IllegalStateException("simulated disconnect failure")
        var detached = false

        val returnedFailure = closeVirtualFileProviderForReplacement(
            provider = AutoCloseable { throw cleanupFailure },
            detach = { detached = true },
        )

        assertFalse(detached)
        assertSame(cleanupFailure, returnedFailure)
    }

    @Test
    fun `successful provider cleanup permits replacement detachment`() {
        var detached = false

        val returnedFailure = closeVirtualFileProviderForReplacement(
            provider = AutoCloseable {},
            detach = { detached = true },
        )

        assertTrue(detached)
        assertEquals(null, returnedFailure)
    }
}
