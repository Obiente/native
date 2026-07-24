package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileIdentityResolutionTest {
    @Test
    fun buildsBoundedDavSearchForAuthoritativeFilesPaths() {
        val request = filesByIdDavSearchRequest(
            userId = "ada&grace",
            fileIds = listOf(42L, 7L, 42L),
        )
        val body = request.body.decodeToString()

        assertEquals("SEARCH", request.method)
        assertEquals("/remote.php/dav/", request.relativePath)
        assertEquals("application/xml; charset=utf-8", request.contentType)
        assertTrue("<d:href>/files/ada&amp;grace</d:href>" in body)
        assertTrue("<d:depth>0</d:depth>" in body)
        assertEquals(1, Regex("<d:literal>42</d:literal>").findAll(body).count())
        assertEquals(1, Regex("<d:literal>7</d:literal>").findAll(body).count())
        assertTrue("<oc:permissions/>" in body)
        assertTrue("<d:getetag/>" in body)
        assertTrue("<nc:has-preview/>" in body)
    }

    @Test
    fun rejectsUnboundedOrUnsafeIdentitySearches() {
        assertFailsWith<IllegalArgumentException> {
            filesByIdDavSearchRequest("ada", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            filesByIdDavSearchRequest("ada", listOf(0L))
        }
        assertFailsWith<IllegalArgumentException> {
            filesByIdDavSearchRequest("ada/admin", listOf(1L))
        }
        assertFailsWith<IllegalArgumentException> {
            filesByIdDavSearchRequest("ada", (1L..101L).toList())
        }
    }
}
