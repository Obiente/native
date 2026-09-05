package dev.obiente.nextcloudnative

import androidx.work.ExistingWorkPolicy
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord
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
            accountOwnedByRegistry = { cleanup -> cleanup.accountStorageKey == restored.accountStorageKey },
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
    fun handoffCleanupJournalIsClearedOnlyAfterDurableCleanupSucceeds() {
        val events = mutableListOf<String>()

        val firstCompleted = retryPendingAndroidExternalHandoffCleanup(
            pending = true,
            clearHandoffs = {
                events += "clear-handoffs"
                error("synthetic persistence failure")
            },
            clearJournal = { events += "clear-journal" },
            recordFailure = { events += "failure" },
        )
        val retryCompleted = retryPendingAndroidExternalHandoffCleanup(
            pending = true,
            clearHandoffs = { events += "retry-handoffs" },
            clearJournal = { events += "clear-journal" },
            recordFailure = { events += "failure" },
        )

        assertFalse(firstCompleted)
        assertTrue(retryCompleted)
        assertEquals(listOf("clear-handoffs", "failure", "retry-handoffs", "clear-journal"), events)
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

    @Test
    fun crossedCleanupIdentityCannotDeleteARetainedAccountsState() = runBlocking {
        val retained = NextcloudSession(
            serverUrl = "https://cloud.example.test/nextcloud",
            loginName = "retained-user",
            appPassword = "fixture-password",
        )
        val removed = NextcloudSession(
            serverUrl = "https://cloud.example.test/nextcloud",
            loginName = "removed-user",
            appPassword = "fixture-password",
        )
        val retainedIdentity = pendingAndroidAccountRemovalCleanup(retained)
        val crossed = pendingAndroidAccountRemovalCleanup(removed).copy(
            workIdentity = retainedIdentity.workIdentity,
            previewCacheIdentity = retainedIdentity.previewCacheIdentity,
        )
        val events = mutableListOf<String>()

        val completed = recoverPendingAndroidAccountRemovalCleanups(
            pending = listOf(crossed),
            accountOwnedByRegistry = { cleanup ->
                androidAccountRemovalCleanupOwnedByRegistry(cleanup, listOf(retained.accountRecord()))
            },
            removeAccountOwnedWork = { events += "remove" },
            clearCleanup = { events += "clear" },
            recordFailure = { events += "failure" },
        )

        assertFalse(completed)
        assertEquals(listOf("failure"), events)
    }

    @Test
    fun matchingCleanupIdentityRecognizesItsRetainedAccount() {
        val retained = NextcloudSession(
            serverUrl = "https://cloud.example.test/nextcloud",
            loginName = "retained-user",
            appPassword = "fixture-password",
        )

        assertEquals(
            true,
            androidAccountRemovalCleanupOwnedByRegistry(
                pendingAndroidAccountRemovalCleanup(retained),
                listOf(retained.accountRecord()),
            ),
        )
    }

    private fun cleanup(accountCharacter: String, workCharacter: String) =
        AndroidPendingAccountRemovalCleanup(
            accountStorageKey = accountCharacter.repeat(64),
            workIdentity = workCharacter.repeat(32),
        )
}
