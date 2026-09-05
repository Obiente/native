package dev.obiente.nextcloudnative

import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAccountRemovalRecoveryTest {
    @Test
    fun unavailableActiveCredentialRemovalUsesActiveTeardown() = runBlocking {
        val events = mutableListOf<String>()

        removeUnavailableAndroidAccountCredentialData(
            accountIdentity = "account-identity",
            active = true,
            prepareAccountRemoval = { events += "prepare" },
            removeAccountOwnedWorkWithoutCredentials = { events += "remove:$it" },
            persistRemoval = { events += "persist-inactive" },
            clearActiveAccount = { events += "clear-active" },
            rollbackRemoval = { events += "rollback" },
            completeCommittedCleanup = { events += "clear-cleanup" },
        )

        assertEquals(
            listOf("prepare", "clear-active", "remove:account-identity", "clear-cleanup"),
            events,
        )
    }

    @Test
    fun unavailableRemovalTargetPreservesCredentialFreeActiveOwnership() {
        val session = NextcloudSession("https://cloud.example.test", "alice", "unused-secret")
        val registry = NextcloudAccountRegistry.Empty.upsertAndSelect(session.accountRecord())

        val target = requireNotNull(resolveAndroidUnavailableAccountRemovalTarget(registry, session.accountId))

        assertEquals(session.accountRecord(), target.record)
        assertTrue(target.wasActive)
    }

    @Test
    fun activeFallbackIsExcludedFromThePersistedReplacement() {
        val fallback = NextcloudSession("https://cloud.example.test", "alice", "must-not-persist")
        val recovered = AndroidAccountCredentialState(
            registry = NextcloudAccountRegistry.Empty.upsertAndSelect(fallback.accountRecord())
                .copy(activeAccountId = null),
            sessions = emptyMap(),
        )

        val removal = requireNotNull(resolveAndroidActiveAccountRemovalTransition(recovered, fallback))

        assertTrue(removal.replacement.registry.accounts.isEmpty())
        assertTrue(removal.replacement.sessions.isEmpty())
        assertFalse(encodeAndroidAccountCredentialState(removal.replacement).contains("must-not-persist"))
    }

    @Test
    fun failedActiveRemovalPersistenceDoesNotOverwriteCredentialFreeOwnership() = runBlocking {
        val events = mutableListOf<String>()

        rollbackUnavailableAndroidAccountRemoval(
            active = true,
            recovered = AndroidAccountCredentialState.Empty,
            persistRecovered = { events += "persist-reconstructed-state" },
            clearCleanup = { events += "clear-uncommitted-cleanup" },
        )

        assertEquals(listOf("clear-uncommitted-cleanup"), events)
    }

    @Test
    fun unavailableCredentialRemovalCleansCommittedStateByIdentity() = runBlocking {
        val events = mutableListOf<String>()

        removeUnavailableAndroidAccountCredentialData(
            accountIdentity = "account-identity",
            prepareAccountRemoval = { events += "prepare" },
            removeAccountOwnedWorkWithoutCredentials = { identity -> events += "remove:$identity" },
            persistRemoval = { events += "persist" },
            rollbackRemoval = { events += "rollback" },
            completeCommittedCleanup = { events += "clear" },
            recordCommittedCleanupFailure = { events += "failure" },
        )

        assertEquals(
            listOf("prepare", "persist", "remove:account-identity", "clear"),
            events,
        )
    }

    @Test
    fun unavailableCredentialRemovalPreservesCleanupCancellation() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<CancellationException> {
            removeUnavailableAndroidAccountCredentialData(
                accountIdentity = "account-identity",
                prepareAccountRemoval = {},
                removeAccountOwnedWorkWithoutCredentials = {
                    events += "remove"
                    throw CancellationException("synthetic cancellation")
                },
                persistRemoval = { events += "persist" },
                rollbackRemoval = { events += "rollback" },
                completeCommittedCleanup = { events += "clear" },
                recordCommittedCleanupFailure = { events += "failure" },
            )
        }

        assertEquals(listOf("persist", "remove"), events)
    }

    @Test
    fun restoredAccountRetriesMarkerClearWithoutDeletingOwnedWork() = runBlocking {
        var failClear = true
        val events = mutableListOf<String>()
        val retry = suspend {
            retryAndroidAccountRemovalCleanup(
                accountOwnedByRegistry = true,
                removeAccountOwnedWork = { events += "remove-owned-work" },
                clearCleanup = {
                    events += "clear-cleanup"
                    if (failClear) {
                        failClear = false
                        error("synthetic cleanup marker commit failure")
                    }
                },
            )
        }

        assertFailsWith<IllegalStateException> { retry() }
        retry()

        assertEquals(listOf("clear-cleanup", "clear-cleanup"), events)
    }

    @Test
    fun unknownAccountOwnershipFailsClosedBeforeCleanup() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            retryAndroidAccountRemovalCleanup(
                accountOwnedByRegistry = null,
                removeAccountOwnedWork = { events += "remove-owned-work" },
                clearCleanup = { events += "clear-cleanup" },
            )
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun malformedCleanupTombstonesRemainBlockingRecoveryState() {
        val valid = AndroidPendingAccountRemovalCleanup(
            accountStorageKey = "a".repeat(64),
            workIdentity = "1".repeat(32),
        )
        val encoded = linkedSetOf(encodeAndroidPendingAccountRemovalCleanup(valid), "truncated-row")
        var malformedRecorded = false

        assertFailsWith<AndroidAccountRemovalCleanupJournalException> {
            requireValidAndroidAccountRemovalCleanupJournal(encoded) {
                malformedRecorded = true
            }
        }

        assertTrue(malformedRecorded)
        assertTrue("truncated-row" in encoded)
    }

    @Test
    fun malformedCleanupJournalDoesNotRewriteStoredTombstones() {
        val valid = AndroidPendingAccountRemovalCleanup(
            accountStorageKey = "a".repeat(64),
            workIdentity = "1".repeat(32),
        )
        val encoded = linkedSetOf(encodeAndroidPendingAccountRemovalCleanup(valid), "truncated-row")
        var editCalls = 0
        var commitCalls = 0
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getStringSet" -> encoded
                "edit" -> {
                    editCalls += 1
                    error("Malformed cleanup recovery must not edit preferences")
                }
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences
        val journal = AndroidAccountRemovalCleanupJournal(
            preferences = preferences,
            commit = { commitCalls += 1 },
            recordMalformed = {},
        )

        assertFailsWith<AndroidAccountRemovalCleanupJournalException> { journal.pending() }

        assertEquals(0, editCalls)
        assertEquals(0, commitCalls)
        assertEquals(linkedSetOf(encodeAndroidPendingAccountRemovalCleanup(valid), "truncated-row"), encoded)
    }
}
