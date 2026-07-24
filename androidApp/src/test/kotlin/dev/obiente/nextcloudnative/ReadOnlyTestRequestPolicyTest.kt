package dev.obiente.nextcloudnative

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadOnlyTestRequestPolicyTest {
    @Test
    fun `read only emulator sessions allow retrieval protocols`() {
        listOf("GET", "HEAD", "OPTIONS", "PROPFIND", "REPORT", "SEARCH").forEach { method ->
            assertTrue(method.isReadOnlyTestRequestMethod(), method)
            assertTrue(method.lowercase(Locale.ROOT).isReadOnlyTestRequestMethod(), method)
        }
    }

    @Test
    fun `read only request matching is independent of the process locale`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue("options".isReadOnlyTestRequestMethod())
            assertTrue("propfind".isReadOnlyTestRequestMethod())
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `read only emulator sessions reject every mutation protocol`() {
        listOf("POST", "PUT", "PATCH", "DELETE", "MKCOL", "MOVE", "COPY", "LOCK", "UNLOCK").forEach { method ->
            assertFalse(method.isReadOnlyTestRequestMethod(), method)
        }
    }
}
