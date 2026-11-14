package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicHttpBinding
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.OcsMetadata
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionRequest
import dev.obiente.nextcloudnative.nativeui.runtime.NativeExactlyOnceSend
import dev.obiente.nextcloudnative.nativeui.runtime.NativeExactlyOnceSendOutcome
import dev.obiente.nextcloudnative.nativeui.runtime.NativeSendAttempt
import dev.obiente.nextcloudnative.nativeui.runtime.NativeSendAttemptState
import dev.obiente.nextcloudnative.nativeui.runtime.NativeSendAttemptStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/** OCS failures inside HTTP 2xx: a refusal is rejected, a server error may have applied. */
class DynamicOcsFailureTest {
    @Test
    fun `client error codes are rejections`() {
        listOf(102L, 103L, 104L, 400L, 403L, 404L, 409L, 997L, 998L).forEach { code ->
            assertEquals(NativeActionFailureOutcome.Rejected, outcomeOf(code), "OCS $code")
        }
    }

    @Test
    fun `server errors and unknown codes are unknown`() {
        listOf(500L, 502L, 503L, 996L, 999L, 0L, 300L, 1000L).forEach { code ->
            assertEquals(NativeActionFailureOutcome.Unknown, outcomeOf(code), "OCS $code")
        }
    }

    @Test
    fun `an unknown OCS result does not claim a rejection`() {
        val failure = result(status = "failure", code = 996L, message = "Internal error")
        assertEquals(NativeActionFailureOutcome.Unknown, failure.outcome)
        assertFalse("rejected" in failure.message, failure.message)
    }

    @Test
    fun `contradictory metadata is unknown`() {
        assertEquals(NativeActionFailureOutcome.Unknown, result(status = "failure", code = 100L, message = "Odd").outcome)
    }

    @Test
    fun `a send answered with an OCS server error stays may-have-been-sent`() = runBlocking<Unit> {
        val store = object : NativeSendAttemptStore {
            var value: NativeSendAttempt? = null
            override suspend fun load() = value
            override suspend fun save(attempt: NativeSendAttempt) { value = attempt }
            override suspend fun clear() { value = null }
        }
        val request = NativeActionRequest.Submit(
            ActionSpec(
                "mail-send", "Send message", "messages",
                ApiBinding(HttpMethod.POST, "/apps/mailbox/api/send", "mail-send"),
                ActionIntent.execute, ActionRisk.mutating, false, Confidence.high,
            ),
            values = mapOf("to" to "a@example.test"),
            confirmed = true,
        )
        val outcome = NativeExactlyOnceSend(store, clock = { 1L }).send(
            request,
            { result(status = "failure", code = 996L, message = "Internal error") },
            "1 recipient",
            confirmedResend = false,
            declaresIdempotencyKey = false,
        )
        assertIs<NativeExactlyOnceSendOutcome.MaybeSent>(outcome)
        assertEquals(NativeSendAttemptState.MaybeSent, store.value?.state, "The attempt is kept so nothing resends unconfirmed.")
    }

    private fun outcomeOf(code: Long): NativeActionFailureOutcome = result(status = "failure", code = code, message = "Failure").outcome

    private fun result(status: String, code: Long, message: String): NativeActionExecutionResult.Failure = assertIs(
        NextcloudApiResponse(
            200,
            """{"ocs":{"meta":{"status":"$status","statuscode":$code,"message":"$message"},"data":[]}}""".encodeToByteArray(),
            "application/json",
            etag = null,
        ).toDynamicActionExecutionResult(ACTION),
    )

    private companion object {
        val ACTION = DynamicAction(
            id = "items.update", label = "Update item", resourceId = "items", intent = ActionIntent.update,
            risk = ActionRisk.mutating, requiresConfirmation = false,
            binding = DynamicHttpBinding(
                method = HttpMethod.PUT,
                path = "/ocs/v2.php/apps/example/api/items",
                ocs = OcsMetadata(apiRequestHeader = true, responseDataPointer = "/ocs/data", responseMetaPointer = "/ocs/meta", formatQueryParameter = "format"),
            ),
            confidence = Confidence.high,
        )
    }
}
