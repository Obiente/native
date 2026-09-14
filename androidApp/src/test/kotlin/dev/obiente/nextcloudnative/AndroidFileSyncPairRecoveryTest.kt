package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class AndroidFileSyncPairRecoveryTest {
    private val pair = FileSyncPair(
        id = "pair", accountId = "account-a", localRootId = "content://example.documents/tree/root",
        remoteRootPath = "Documents", configuration = FileSyncConfiguration(deviceLabel = "Phone"),
    )

    @Test
    fun providerRecoveryDoesNotHoldEngineWhileWaitingForAnotherAccount() = runBlocking {
        val engine = Mutex()
        val otherAccount = Mutex(locked = true)
        val providerEntered = CompletableDeferred<Unit>()
        val recovery = async {
            withRecoveredFileSyncPairSnapshot(
                engine, listOf(pair), { listOf(pair) },
                reconcile = {
                    assertFalse(engine.isLocked)
                    providerEntered.complete(Unit)
                    otherAccount.withLock { true }
                },
                onRecoveryRejected = { "rejected" }, onSnapshotChanged = { "changed" },
                commit = { assertTrue(engine.isLocked); "committed" },
            )
        }
        withTimeout(5_000) {
            providerEntered.await()
            // The other account can finish its engine work and release its lease.
            engine.withLock { otherAccount.unlock() }
            assertEquals("committed", recovery.await())
        }
    }

    @Test
    fun changedPairSnapshotCannotCommitAfterProviderRecovery() = runBlocking {
        val engine = Mutex()
        var current = listOf(pair)
        var committed = false
        val outcome = withRecoveredFileSyncPairSnapshot(
            engine, current, { current },
            reconcile = { current = listOf(pair.copy(remoteRootPath = "Changed")); true },
            onRecoveryRejected = { "rejected" }, onSnapshotChanged = { "changed" },
            commit = { committed = true; "committed" },
        )
        assertEquals("changed", outcome)
        assertFalse(committed)
    }

    @Test
    fun rejectedRecoveryPreservesThePairAndDoesNotCommit() = runBlocking {
        var committed = false
        val outcome = withRecoveredFileSyncPairSnapshot(
            Mutex(), listOf(pair), { listOf(pair) }, { false },
            onRecoveryRejected = { "rejected" }, onSnapshotChanged = { "changed" },
            commit = { committed = true; "committed" },
        )
        assertEquals("rejected", outcome)
        assertFalse(committed)
    }

    @Test
    fun cancellationCannotCommitRemoval() = runBlocking {
        var committed = false
        assertFailsWith<CancellationException> {
            withRecoveredFileSyncPairSnapshot(
                Mutex(), listOf(pair), { listOf(pair) }, { throw CancellationException() },
                onRecoveryRejected = {}, onSnapshotChanged = {}, commit = { committed = true },
            )
        }
        assertFalse(committed)
    }
}
