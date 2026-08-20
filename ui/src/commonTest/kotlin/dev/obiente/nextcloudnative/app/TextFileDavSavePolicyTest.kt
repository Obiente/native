package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextFileDavSavePolicyTest {
    @Test
    fun `request preserves UTF-8 content and optimistic concurrency token`() {
        val request = textFileDavSaveRequest("A cafe costs €2.", "\"etag-42\"")

        assertContentEquals("A cafe costs €2.".encodeToByteArray(), request.body)
        assertEquals("\"etag-42\"", request.headers["If-Match"])
        assertEquals("*/*", request.headers["Accept"])
        assertEquals("text/plain; charset=utf-8", request.contentType)
    }

    @Test
    fun `request rejects missing or header-injecting versions`() {
        listOf("", "   ", "\"etag\"\rInjected: value", "\"etag\"\nInjected: value").forEach { etag ->
            assertFailsWith<IllegalArgumentException> {
                textFileDavSaveRequest("content", etag)
            }
        }
    }

    @Test
    fun `response distinguishes creation from replacement`() {
        assertTrue(confirmTextFileDavSave(201).created)
        assertFalse(confirmTextFileDavSave(200).created)
        assertFalse(confirmTextFileDavSave(204).created)
    }

    @Test
    fun `precondition failures retain the conflict recovery message`() {
        val failure = assertFailsWith<IllegalStateException> {
            confirmTextFileDavSave(412)
        }

        assertEquals(
            "The file changed on the server. Reload it before saving your changes.",
            failure.message,
        )
    }

    @Test
    fun `other unsuccessful responses include the HTTP status`() {
        val failure = assertFailsWith<IllegalStateException> {
            confirmTextFileDavSave(507)
        }

        assertEquals("Saving the text file failed (HTTP 507).", failure.message)
    }
}
