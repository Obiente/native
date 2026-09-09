package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FileVersionRestoreHttpPolicyTest {
    @Test
    fun `all successful MOVE responses confirm restoration`() {
        listOf(200, 201, 204, 207, 299).forEach { status ->
            assertEquals(
                FileVersionRestoreHttpResult.Restored,
                classifyFileVersionRestoreHttpResponse(status),
            )
        }
    }

    @Test
    fun `known DAV failures keep actionable messages`() {
        val expected = mapOf(
            403 to "You do not have permission to restore this file version.",
            404 to "This historical version no longer exists.",
            409 to "The server could not restore this version to the current file.",
        )

        expected.forEach { (status, message) ->
            val result = assertIs<FileVersionRestoreHttpResult.Rejected>(
                classifyFileVersionRestoreHttpResponse(status),
            )
            assertEquals(status, result.status)
            assertEquals(message, result.message)
        }
    }

    @Test
    fun `unexpected responses retain their status`() {
        val result = assertIs<FileVersionRestoreHttpResult.Rejected>(
            classifyFileVersionRestoreHttpResponse(507),
        )

        assertEquals(507, result.status)
        assertEquals("Restoring the file version failed (HTTP 507).", result.message)
    }
}
