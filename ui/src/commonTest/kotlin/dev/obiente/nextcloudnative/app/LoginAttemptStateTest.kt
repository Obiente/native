package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginAttemptStateTest {
    @Test
    fun `cancel during network retry restores controls and releases the challenge`() = runBlocking {
        val owner = LoginAttemptState()
        val waiting = CompletableDeferred<Unit>()
        var finishes = 0
        coroutineScope {
            owner.start(
                scope = this,
                begin = { challenge },
                openBrowser = {},
                poll = { LoginPollResult.RetryablePreExchangeFailure("NETWORK_DNS_UNRESOLVED") },
                awaitNetwork = { waiting.complete(Unit); awaitCancellation() },
                finish = { finishes++ },
                onAuthenticated = { error("cancelled approval must not authenticate") },
            )
            waiting.await()
            assertEquals(LoginAttemptPhase.WaitingForNetwork, owner.phase)
            owner.cancel()
            assertNull(owner.phase)
        }
        assertEquals(1, finishes)
        assertNull(owner.failure)
    }

    @Test
    fun `late cancelled approval cannot authenticate or overwrite a replacement attempt`() = runBlocking {
        val owner = LoginAttemptState()
        val oldPolling = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val newPolling = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        var authenticated = false
        coroutineScope {
            owner.start(
                scope = this,
                begin = { challenge },
                openBrowser = {},
                poll = {
                    withContext(NonCancellable) {
                        oldPolling.complete(Unit)
                        releaseOld.await()
                    }
                    LoginPollResult.Approved(session)
                },
                awaitNetwork = {},
                finish = { oldFinished.complete(Unit) },
                onAuthenticated = { authenticated = true },
            )
            oldPolling.await()
            owner.start(
                scope = this,
                begin = { challenge.copy(token = "replacement-token") },
                openBrowser = {},
                poll = { newPolling.complete(Unit); awaitCancellation() },
                awaitNetwork = {},
                finish = {},
                onAuthenticated = { authenticated = true },
            )
            newPolling.await()
            releaseOld.complete(Unit)
            oldFinished.await()
            assertEquals(LoginAttemptPhase.AwaitingApproval, owner.phase)
            assertTrue(!authenticated)
            owner.cancel()
        }
    }

    @Test
    fun `overall deadline interrupts a stalled network wait and releases state`() = runBlocking {
        val owner = LoginAttemptState(timeoutMillis = 100)
        var finishes = 0
        coroutineScope {
            owner.start(
                scope = this,
                begin = { challenge },
                openBrowser = {},
                poll = { LoginPollResult.RetryablePreExchangeFailure("NETWORK_DNS_UNRESOLVED") },
                awaitNetwork = { awaitCancellation() },
                finish = { finishes++ },
                onAuthenticated = { error("must not authenticate") },
            )
        }
        assertNull(owner.phase)
        assertTrue(owner.failure?.message.orEmpty().contains("timed out"))
        assertEquals(1, finishes)
    }

    @Test
    fun `browser handoff failure releases challenge and restores retry controls`() = runBlocking {
        val owner = LoginAttemptState()
        var finishes = 0
        coroutineScope {
            owner.start(
                scope = this,
                begin = { challenge },
                openBrowser = { error("No browser available") },
                poll = { error("must not poll") },
                awaitNetwork = {},
                finish = { finishes++ },
                onAuthenticated = { error("must not authenticate") },
            )
        }
        assertNull(owner.phase)
        assertEquals("No browser available", owner.failure?.message)
        assertEquals(1, finishes)
    }

    @Test
    fun `approval is delivered once and completion is distinct from waiting`() = runBlocking {
        val owner = LoginAttemptState()
        var authenticated = 0
        var finishes = 0
        coroutineScope {
            owner.start(
                scope = this,
                begin = { challenge },
                openBrowser = {},
                poll = { LoginPollResult.Approved(session) },
                awaitNetwork = {},
                finish = { finishes++ },
                onAuthenticated = {
                    assertEquals(session, it)
                    assertEquals(LoginAttemptPhase.Completing, owner.phase)
                    authenticated++
                },
            )
        }
        assertEquals(1, authenticated)
        assertEquals(1, finishes)
        assertNull(owner.phase)
        assertNull(owner.failure)
    }

    private val session = NextcloudSession("https://cloud.example.test", "fixture-user", "fixture-password")
    private val challenge = LoginChallenge(
        enteredServerUrl = "https://cloud.example.test",
        pollEndpoint = "https://cloud.example.test/login/v2/poll",
        pollFallbackEndpoint = null,
        token = "synthetic-token",
        loginUrl = "https://cloud.example.test/login/v2/flow",
    )
}
