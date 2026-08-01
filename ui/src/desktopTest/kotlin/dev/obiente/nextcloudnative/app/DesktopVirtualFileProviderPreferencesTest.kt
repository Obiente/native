package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
}
