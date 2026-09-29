package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicReadFailureTest {
    @Test
    fun `read failures explain status without technical request details`() {
        val cases = mapOf(
            401 to (DynamicReadFailureKind.Authentication to "Sign in again"),
            403 to (DynamicReadFailureKind.Permission to "does not have permission"),
            404 to (DynamicReadFailureKind.Missing to "no longer available"),
            410 to (DynamicReadFailureKind.Missing to "no longer available"),
            429 to (DynamicReadFailureKind.Throttled to "Wait a moment"),
            500 to (DynamicReadFailureKind.Server to "Try again in a moment"),
            503 to (DynamicReadFailureKind.Server to "Try again in a moment"),
            400 to (DynamicReadFailureKind.Rejected to "Refresh and try again"),
        )
        cases.forEach { (status, expected) ->
            val failure = response(status).toDynamicReadLoadException("albums", HttpMethod.GET)
            assertEquals(expected.first, failure.kind)
            assertTrue(failure.message.orEmpty().contains(expected.second))
            assertFalse(failure.message.orEmpty().contains("HTTP"))
            assertFalse(failure.message.orEmpty().contains("GET"))
            assertEquals(status, failure.httpStatus)
            assertEquals(HttpMethod.GET, failure.method)
        }
    }

    @Test
    fun `arbitrary server messages cannot leak content paths credentials or class names`() {
        val bodies = listOf(
            """{"message":"private-song.mp3 /apps/music/api/albums java.lang.IllegalStateException token=secret"}""",
            """{"data":{"message":"Authorization: Basic synthetic-secret"}}""",
            """{"error":{"message":"https://private.example/account/sensitive"}}""",
            """{"ocs":{"meta":{"message":"personal-server-exception"}}}""",
        )
        for (status in listOf(400, 500)) for (body in bodies) {
            val failure = response(status, body).toDynamicReadLoadException("albums", HttpMethod.GET)
            assertEquals(response(status).toDynamicReadLoadException("albums", HttpMethod.GET).message, failure.message)
            assertEquals(1, failure.specificity)
        }
    }

    @Test
    fun `reviewed mailbox translation remains specific without exposing mailbox identity`() {
        for (body in listOf(
            """{"message":"mailbox 42 is not cached"}""",
            """{"data":{"message":"mailbox 42 is not cached"}}""",
            """{"error":{"message":"mailbox 42 is not cached"}}""",
            """{"ocs":{"meta":{"message":"mailbox 42 is not cached"}}}""",
        )) {
            val failure = response(400, body).toDynamicReadLoadException("messages", HttpMethod.GET)
            assertEquals(DynamicReadFailureKind.MailboxNotSynchronized, failure.kind)
            assertEquals(2, failure.specificity)
            assertTrue(failure.message.orEmpty().contains("has not been synchronized"))
            assertFalse(failure.message.orEmpty().contains("42"))
        }
    }

    @Test
    fun `malformed oversized and invalid text fall back to bounded status copy`() {
        val bodies = listOf(
            "{broken".encodeToByteArray(),
            ("[".repeat(2000) + "0" + "]".repeat(2000)).encodeToByteArray(),
            "x".repeat(8193).encodeToByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28),
            """{"message":"mailbox ${"a".repeat(250)} is not cached"}""".encodeToByteArray(),
        )
        for (body in bodies) {
            val failure = response(400).copy(body = body).toDynamicReadLoadException("albums", HttpMethod.GET)
            assertEquals(DynamicReadFailureKind.Rejected, failure.kind)
            assertTrue(failure.message.orEmpty().length < 200)
        }
    }

    @Test
    fun `mailbox message cannot override authentication denial`() {
        val failure = response(401, """{"message":"mailbox 42 is not cached"}""")
            .toDynamicReadLoadException("messages", HttpMethod.GET)
        assertEquals(DynamicReadFailureKind.Authentication, failure.kind)
    }

    private fun response(status: Int, body: String = "") = NextcloudApiResponse(
        status = status,
        body = body.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )
}
