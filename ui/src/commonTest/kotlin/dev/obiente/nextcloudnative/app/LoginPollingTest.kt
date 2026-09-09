package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoginPollingTest {
    @Test
    fun `pending diagnostic identifies protocol state without endpoint data`() {
        val primary = loginPollPendingDiagnostic(usedFallback = false)
        val fallback = loginPollPendingDiagnostic(usedFallback = true)

        assertEquals("pending", primary.outcome)
        assertEquals("HTTP:404", primary.code)
        assertEquals("primary", primary.fields.single { it.name == "endpoint_role" }.value)
        assertEquals("fallback", fallback.fields.single { it.name == "endpoint_role" }.value)
        assertEquals("awaiting-browser-approval", primary.fields.single { it.name == "protocol_state" }.value)
        assertNull(primary.message)
        assertNull(primary.exception)
        assertTrue(!shouldRecordHttpStatusDiagnostic(404, setOf(404)))
        assertTrue(shouldRecordHttpStatusDiagnostic(401, setOf(404)))
        assertTrue(shouldRecordHttpStatusDiagnostic(500))
    }

    @Test
    fun transientDnsFailuresRetryWithoutLosingTheApprovalWindow() = runBlocking {
        val session = NextcloudSession("https://cloud.example.com", "person", "secret")
        val results = ArrayDeque<LoginPollResult>().apply {
            add(LoginPollResult.RetryablePreExchangeFailure("NETWORK_DNS_UNRESOLVED"))
            add(LoginPollResult.RetryablePreExchangeFailure("NETWORK_DNS_UNRESOLVED"))
            add(LoginPollResult.Pending)
            add(LoginPollResult.Approved(session))
        }
        val waits = mutableListOf<Pair<Long, Boolean>>()
        val statuses = mutableListOf<String>()

        val approved = pollLoginUntilApproved(
            poll = { results.removeFirst() },
            waitBeforeNextPoll = { delay, awaitNetwork -> waits += delay to awaitNetwork },
            hasTimedOut = { false },
            onStatus = statuses::add,
        )

        assertEquals(session, approved)
        assertEquals(listOf(2_000L to true, 4_000L to true, 2_000L to false), waits)
        assertTrue(statuses.first().contains("temporarily unavailable"))
        assertEquals("Finish signing in in your browser, then return here.", statuses.last())
    }

    @Test
    fun anAmbiguousPostExchangeFailureIsNeverRetried() = runBlocking {
        var polls = 0

        val failure = assertFailsWith<IllegalStateException> {
            pollLoginUntilApproved(
                poll = {
                    polls += 1
                    LoginPollResult.AmbiguousAfterExchangeFailure("Restart sign-in.")
                },
                waitBeforeNextPoll = { _, _ -> error("must not wait") },
                hasTimedOut = { false },
            )
        }

        assertEquals(1, polls)
        assertEquals("Restart sign-in.", failure.message)
    }

    @Test
    fun permanentDnsFailureEndsAtTheExistingDeadline() = runBlocking {
        var timedOut = false
        var polls = 0

        val failure = assertFailsWith<IllegalStateException> {
            pollLoginUntilApproved(
                poll = {
                    polls += 1
                    LoginPollResult.RetryablePreExchangeFailure("NETWORK_DNS_UNRESOLVED")
                },
                waitBeforeNextPoll = { _, awaitNetwork ->
                    assertTrue(awaitNetwork)
                    if (polls == 3) timedOut = true
                },
                hasTimedOut = { timedOut },
            )
        }

        assertEquals(3, polls)
        assertTrue(failure.message.orEmpty().contains("timed out"))
    }

    @Test
    fun cancellationPropagatesWithoutTurningIntoALoginFailure() = runBlocking {
        assertFailsWith<CancellationException> {
            pollLoginUntilApproved(
                poll = { throw CancellationException("closed") },
                waitBeforeNextPoll = { _, _ -> error("must not wait") },
                hasTimedOut = { false },
            )
        }
        Unit
    }

    @Test
    fun approvalIsReturnedExactlyOnceWithoutAnotherWaitOrPoll() = runBlocking {
        val session = NextcloudSession("https://cloud.example.com", "person", "secret")
        var polls = 0

        val result = pollLoginUntilApproved(
            poll = {
                polls += 1
                LoginPollResult.Approved(session)
            },
            waitBeforeNextPoll = { _, _ -> error("must not wait") },
            hasTimedOut = { false },
        )

        assertEquals(session, result)
        assertEquals(1, polls)
    }

    @Test
    fun retryBackoffIsBounded() {
        assertEquals(2_000L, loginPollRetryDelayMillis(1))
        assertEquals(4_000L, loginPollRetryDelayMillis(2))
        assertEquals(8_000L, loginPollRetryDelayMillis(3))
        assertEquals(15_000L, loginPollRetryDelayMillis(4))
        assertEquals(15_000L, loginPollRetryDelayMillis(30))
    }

    @Test
    fun retryDiagnosticsDescribeSafetyWithoutHostnamesOrCredentials() {
        val event = LoginPollResult.RetryablePreExchangeFailure("NETWORK_DNS_UNRESOLVED")
            .toLoginPollFailureDiagnostic()

        assertEquals(SupportDiagnosticComponent.Authentication, event?.component)
        assertEquals("transient-network", event?.outcome)
        assertEquals("NETWORK_DNS_UNRESOLVED", event?.code)
        assertEquals(
            mapOf("safe_to_retry" to "true", "exchange_ambiguous" to "false"),
            event?.fields?.associate { it.name to it.value },
        )
    }
}
