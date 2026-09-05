package dev.obiente.nextcloudnative

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidMediaBackupAccountCleanupTest {
    @Test
    fun cleanupDeletesOnlyTheRemovedAccountsLedgerRowsAndIsIdempotent(): Unit = runBlocking {
        val removed = "a".repeat(64)
        val retained = "b".repeat(64)
        val rows = mutableMapOf(removed to mutableSetOf("removed"), retained to mutableSetOf("retained"))
        val cleanup = AndroidMediaBackupAccountCleanup { accountId -> rows.remove(accountId) }

        repeat(2) { cleanup.removeForAccount(removed) }

        assertEquals(mapOf(retained to setOf("retained")), rows)
    }

    @Test
    fun failedOpenLeavesRowsForJournaledRetry(): Unit = runBlocking {
        val removed = "c".repeat(64)
        val rows = mutableMapOf(removed to mutableSetOf("pending"))
        var failOpen = true
        val cleanup = AndroidMediaBackupAccountCleanup { accountId ->
            if (failOpen) error("synthetic ledger open failure")
            rows.remove(accountId)
        }

        assertFailsWith<IllegalStateException> { cleanup.removeForAccount(removed) }
        assertTrue(rows[removed] == mutableSetOf("pending"))
        failOpen = false

        cleanup.removeForAccount(removed)

        assertTrue(rows.isEmpty())
    }

    @Test
    fun accountRemovalWaitsForAStartedLedgerWriterAndDeletesItsResult(): Unit = runBlocking {
        val accountId = "d".repeat(64)
        val rows = mutableMapOf<String, MutableSet<String>>()
        val guard = AndroidAccountOperationGuard()
        val writerStarted = CompletableDeferred<Unit>()
        val finishWriter = CompletableDeferred<Unit>()
        var removalFinished = false
        val cleanup = AndroidMediaBackupAccountCleanup { removedAccountId -> rows.remove(removedAccountId) }
        val writer = async {
            guard.withAccount(accountId) {
                writerStarted.complete(Unit)
                finishWriter.await()
                rows.getOrPut(accountId, ::mutableSetOf).add("late")
            }
        }
        writerStarted.await()
        val removal = async(start = CoroutineStart.UNDISPATCHED) {
            guard.withAccount(accountId) {
                cleanup.removeForAccount(accountId)
                removalFinished = true
            }
        }

        assertFalse(removalFinished)
        finishWriter.complete(Unit)
        writer.await()
        removal.await()
        assertTrue(rows.isEmpty())
    }
}
