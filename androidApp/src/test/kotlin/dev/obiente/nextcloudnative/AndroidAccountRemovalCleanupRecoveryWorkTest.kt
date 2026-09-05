package dev.obiente.nextcloudnative

import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAccountRemovalCleanupRecoveryWorkTest {
    @Test
    fun newCleanupMarkersAppendBehindAStillRunningRecovery() {
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            ANDROID_ACCOUNT_REMOVAL_CLEANUP_WORK_POLICY,
        )
    }

    @Test
    fun removedAccountsAreCleanedWithoutBeingSavedAgain() = runBlocking {
        val removed = cleanup("a", "1")
        val restored = cleanup("b", "2")
        val events = mutableListOf<String>()

        val completed = recoverPendingAndroidAccountRemovalCleanups(
            pending = listOf(removed, restored),
            accountOwnedByRegistry = { key -> key == restored.accountStorageKey },
            removeAccountOwnedWork = { events += "remove:${it.workIdentity}" },
            clearCleanup = { events += "clear:$it" },
            recordFailure = { events += "failure" },
        )

        assertTrue(completed)
        assertEquals(
            listOf(
                "remove:${removed.workIdentity}",
                "clear:${removed.accountStorageKey}",
                "clear:${restored.accountStorageKey}",
            ),
            events,
        )
    }

    @Test
    fun unreadableRegistryDefersCleanupWithoutDeletingAccountOwnedState() = runBlocking {
        val pending = cleanup("a", "1")
        val events = mutableListOf<String>()

        val completed = recoverPendingAndroidAccountRemovalCleanups(
            pending = listOf(pending),
            accountOwnedByRegistry = { null },
            removeAccountOwnedWork = { events += "remove" },
            clearCleanup = { events += "clear" },
            recordFailure = { events += "failure" },
        )

        assertFalse(completed)
        assertEquals(listOf("failure"), events)
    }

    @Test
    fun cleanupCancellationIsNotReportedAsARecoverableFailure() = runBlocking {
        val pending = cleanup("a", "1")
        val events = mutableListOf<String>()

        kotlin.test.assertFailsWith<CancellationException> {
            recoverPendingAndroidAccountRemovalCleanups(
                pending = listOf(pending),
                accountOwnedByRegistry = { false },
                removeAccountOwnedWork = {
                    events += "remove"
                    throw CancellationException("synthetic cancellation")
                },
                clearCleanup = { events += "clear" },
                recordFailure = { events += "failure" },
            )
        }

        assertEquals(listOf("remove"), events)
    }

    @Test
    fun recoveryFailureLogDoesNotExposeTheFailureMessage() = runBlocking {
        val pending = cleanup("a", "1")
        val messages = mutableListOf<String>()

        val completed = recoverPendingAndroidAccountRemovalCleanups(
            pending = listOf(pending),
            accountOwnedByRegistry = { false },
            removeAccountOwnedWork = {
                error("private/path/account-secret")
            },
            clearCleanup = {},
            recordFailure = {
                logAndroidAccountRemovalCleanupRecoveryDeferred(messages::add)
            },
        )

        assertFalse(completed)
        assertEquals(listOf("Account-removal cleanup recovery deferred"), messages)
        assertFalse(messages.single().contains("private/path/account-secret"))
    }

    @Test
    fun unreadableCleanupJournalDefersRecoveryWithABoundedMessage() {
        val messages = mutableListOf<String>()

        val pending = readPendingAndroidAccountRemovalCleanups(
            readPending = { error("private/path/account-secret") },
            recordFailure = {
                logAndroidAccountRemovalCleanupRecoveryDeferred(messages::add)
            },
        )

        assertEquals(null, pending)
        assertEquals(listOf("Account-removal cleanup recovery deferred"), messages)
        assertFalse(messages.single().contains("private/path/account-secret"))
    }

    @Test
    fun cleanupJournalReadCancellationIsPropagated() {
        var recorded = false

        kotlin.test.assertFailsWith<CancellationException> {
            readPendingAndroidAccountRemovalCleanups(
                readPending = { throw CancellationException("synthetic cancellation") },
                recordFailure = { recorded = true },
            )
        }

        assertFalse(recorded)
    }

    private fun cleanup(accountCharacter: String, workCharacter: String) =
        AndroidPendingAccountRemovalCleanup(
            accountStorageKey = accountCharacter.repeat(64),
            workIdentity = workCharacter.repeat(32),
        )
}
