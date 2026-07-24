package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadOnlyTestRequestPolicyTest {
    @Test
    fun `read only emulator sessions allow retrieval protocols`() {
        listOf("GET", "HEAD", "OPTIONS", "PROPFIND", "REPORT", "SEARCH").forEach { method ->
            assertTrue(method.isReadOnlyTestRequestMethod(), method)
            assertTrue(method.lowercase().isReadOnlyTestRequestMethod(), method)
        }
    }

    @Test
    fun `read only emulator sessions reject every mutation protocol`() {
        listOf("POST", "PUT", "PATCH", "DELETE", "MKCOL", "MOVE", "COPY", "LOCK", "UNLOCK").forEach { method ->
            assertFalse(method.isReadOnlyTestRequestMethod(), method)
        }
    }
}
