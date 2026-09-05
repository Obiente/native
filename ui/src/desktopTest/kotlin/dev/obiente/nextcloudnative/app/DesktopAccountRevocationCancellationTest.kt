package dev.obiente.nextcloudnative.app

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class DesktopAccountRevocationCancellationTest {
    @Test
    fun ambiguousRemoteRevocationFailureStillCompletesLocalRemovalAndReturnsOriginalFailure() = runBlocking {
        val events = mutableListOf<String>()
        val revocationFailure = IOException("remote response was lost")

        val thrown = assertFailsWith<IOException> {
            completeDesktopSignOutAfterRemoteRevocation(
                session = "account",
                revokeRemoteSession = {
                    events += "remote-revocation-attempted"
                    throw revocationFailure
                },
                completeLocalRemoval = { events += "local-removed" },
            )
        }

        assertTrue(thrown === revocationFailure)
        assertEquals(listOf("remote-revocation-attempted", "local-removed"), events)
    }

    @Test
    fun cancellationReturningFromRemoteRevocationStillCompletesLocalRemoval() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<CancellationException> {
            completeDesktopSignOutAfterRemoteRevocation(
                session = "account",
                revokeRemoteSession = {
                    events += "remote-revoked"
                    throw CancellationException("cancelled while returning from revocation")
                },
                completeLocalRemoval = { events += "local-removed" },
            )
        }

        assertEquals(listOf("remote-revoked", "local-removed"), events)
    }

    @Test
    fun cancellationWhileJoiningHydrationStillCompletesLocalRemoval() = runBlocking {
        val hydrationJoinStarted = CompletableDeferred<Unit>()
        val hydrationCanFinish = CompletableDeferred<Unit>()
        val localRemovalFinished = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val signOut = async {
            completeDesktopSignOutAfterRemoteRevocation(
                session = "account",
                revokeRemoteSession = { events += "remote-revoked" },
                completeLocalRemoval = {
                    events += "join-hydration"
                    hydrationJoinStarted.complete(Unit)
                    hydrationCanFinish.await()
                    events += "local-removed"
                    localRemovalFinished.complete(Unit)
                },
            )
        }
        hydrationJoinStarted.await()

        signOut.cancel(CancellationException("cancelled while joining hydration"))
        hydrationCanFinish.complete(Unit)
        localRemovalFinished.await()

        assertFailsWith<CancellationException> { signOut.await() }
        assertTrue(signOut.isCancelled)
        assertEquals(listOf("remote-revoked", "join-hydration", "local-removed"), events)
    }
}
