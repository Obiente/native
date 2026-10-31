package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountPasswordConfirmationTest {
    private val session = NextcloudSession("https://cloud.example.test", "alice", "app-token")
    private val write = confirmedOcsFormRequest(NextcloudApiMethod.PUT, "/ocs/v2.php/cloud/users/alice", listOf("key" to "phone", "value" to "1"))

    @Test
    fun `secret is redacted and unusable after clear`() {
        val secret = AccountPasswordConfirmationSecret("hunter2".toCharArray())
        assertFalse("hunter2" in secret.toString())
        assertEquals("hunter2", secret.read { String(it) })
        secret.clear()
        assertTrue(secret.isCleared)
        assertFailsWith<IllegalStateException> { secret.read { it } }
        assertFailsWith<IllegalArgumentException> { AccountPasswordConfirmationSecret(CharArray(0)) }
    }

    @Test
    fun `success is applied only after the postcondition read confirms it`() = runBlocking {
        assertEquals(ConfirmedWriteOutcome.Applied, run(delivered(200, ok(200))) { true })
        assertEquals(ConfirmedWriteOutcome.Unverified, run(delivered(200, ok(200))) { false })
        // Verification failures are reported as unknown, never as success.
        assertEquals(ConfirmedWriteOutcome.Unverified, run(delivered(200, ok(200))) { error("read failed") })
    }

    @Test
    fun `server decisions are definitive rejections without verification`() = runBlocking {
        var verified = 0
        val verify: suspend () -> Boolean = { verified++; true }
        assertEquals(ConfirmedWriteRejection.PasswordNotAccepted, rejection(run(delivered(401, ""), verify)))
        assertEquals(ConfirmedWriteRejection.Forbidden, rejection(run(delivered(403, failure(403, "Not allowed")), verify)))
        assertEquals(ConfirmedWriteRejection.NotFound, rejection(run(delivered(404, ""), verify)))
        assertEquals(ConfirmedWriteRejection.Throttled, rejection(run(delivered(429, ""), verify)))
        assertEquals(ConfirmedWriteRejection.Invalid, rejection(run(delivered(400, failure(400, "Invalid")), verify)))
        // OCS v1 reports failures inside HTTP 200.
        assertEquals(ConfirmedWriteRejection.PasswordNotAccepted, rejection(run(delivered(200, failure(997, "")), verify)))
        assertEquals(ConfirmedWriteRejection.NotFound, rejection(run(delivered(200, failure(998, "")), verify)))
        assertEquals(ConfirmedWriteRejection.Invalid, rejection(run(delivered(200, failure(102, "Exists")), verify)))
        assertEquals(0, verified)
    }

    @Test
    fun `server messages are bounded and stripped of control characters`() = runBlocking {
        val outcome = run(delivered(400, failure(400, "Bad\u0007 value " + "x".repeat(500)))) { true }
        val message = (outcome as ConfirmedWriteOutcome.Rejected).serverMessage.orEmpty()
        assertFalse('\u0007' in message)
        assertTrue(message.length <= 300)
    }

    @Test
    fun `ambiguous and gateway results are verified and never retried`() = runBlocking {
        var sent = 0
        val transport = AccountPasswordConfirmedTransport { _, _, _ -> sent++; PasswordConfirmedApiResult.Ambiguous }
        val secret = AccountPasswordConfirmationSecret("pw".toCharArray())
        assertEquals(ConfirmedWriteOutcome.Applied, runPasswordConfirmedWrite(transport, session, write, secret) { true })
        assertEquals(ConfirmedWriteOutcome.Unverified, runPasswordConfirmedWrite(transport, session, write, secret) { false })
        assertEquals(2, sent)
        assertEquals(ConfirmedWriteOutcome.Unverified, run(delivered(504, "")) { false })
        assertEquals(ConfirmedWriteOutcome.Applied, run(delivered(502, "")) { true })
    }

    @Test
    fun `a request that never left the device changed nothing`() = runBlocking {
        var verified = false
        val outcome = run(PasswordConfirmedApiResult.NotSent) { verified = true; true }
        assertEquals(ConfirmedWriteRejection.NotSent, rejection(outcome))
        assertFalse(verified)
    }

    @Test
    fun `cancellation is propagated instead of becoming an outcome`() {
        assertFailsWith<CancellationException> {
            runBlocking { run(delivered(200, ok(200))) { throw CancellationException("left screen") } }
        }
    }

    @Test
    fun `password confirmation refuses reads and non-OCS requests`(): Unit = runBlocking {
        val transport = AccountPasswordConfirmedTransport { _, _, _ -> error("must not send") }
        val secret = AccountPasswordConfirmationSecret("pw".toCharArray())
        assertFailsWith<IllegalArgumentException> {
            runPasswordConfirmedWrite(transport, session, write.copy(method = NextcloudApiMethod.GET), secret) { true }
        }
        assertFailsWith<IllegalArgumentException> {
            runPasswordConfirmedWrite(transport, session, write.copy(ocsApiRequest = false), secret) { true }
        }
        assertFailsWith<IllegalArgumentException> { confirmedOcsFormRequest(NextcloudApiMethod.PUT, "/ocs/v1.php/cloud/users/a") }
        Unit
    }

    @Test
    fun `form request encodes fields and forces network reads`() {
        val request = confirmedOcsFormRequest(NextcloudApiMethod.POST, "/ocs/v2.php/cloud/groups", listOf("groupid" to "a b&c"))
        assertEquals("groupid=a%20b%26c", request.body?.decodeToString())
        assertEquals("application/x-www-form-urlencoded", request.contentType)
        assertEquals(mapOf("format" to "json"), request.queryParameters)
        assertTrue(request.ocsApiRequest)
    }

    private suspend fun run(result: PasswordConfirmedApiResult, verify: suspend () -> Boolean): ConfirmedWriteOutcome =
        runPasswordConfirmedWrite(
            AccountPasswordConfirmedTransport { _, _, _ -> result },
            session,
            write,
            AccountPasswordConfirmationSecret("pw".toCharArray()),
            verify,
        )

    private fun rejection(outcome: ConfirmedWriteOutcome): ConfirmedWriteRejection? =
        (outcome as? ConfirmedWriteOutcome.Rejected)?.reason

    private fun delivered(status: Int, body: String) =
        PasswordConfirmedApiResult.Delivered(NextcloudApiResponse(status, body.encodeToByteArray(), "application/json", null))

    private fun ok(code: Int) = """{"ocs":{"meta":{"status":"ok","statuscode":$code,"message":"OK"},"data":[]}}"""

    private fun failure(code: Int, message: String) =
        """{"ocs":{"meta":{"status":"failure","statuscode":$code,"message":"$message"},"data":[]}}"""
}
