package dev.obiente.nextcloudnative

import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
    fun malformedCleanupTombstonesDoNotHideValidRecoveryState() {
        val valid = AndroidPendingAccountRemovalCleanup(
            accountStorageKey = "a".repeat(64),
            workIdentity = "1".repeat(32),
        )
        val encoded = linkedSetOf(encodeAndroidPendingAccountRemovalCleanup(valid), "truncated-row")
        var malformedRecorded = false

        assertEquals(
            setOf(valid),
            requireValidAndroidAccountRemovalCleanupJournal(encoded) {
                malformedRecorded = true
            },
        )

        assertTrue(malformedRecorded)
        assertTrue("truncated-row" in encoded)
        val snapshot = restoreAndroidPendingAccountRemovalCleanups(encoded)
        assertEquals(setOf(valid), snapshot.cleanups)
        assertEquals(1, snapshot.malformedEntryCount)
        assertFailsWith<IllegalStateException> {
            requireAndroidAccountRemovalCleanupJournalAllowsActivation(snapshot)
        }
        assertFalse(androidAccountRemovalCleanupRecoveryCompleted(true, snapshot, true))
    }

    @Test
    fun malformedOnlyCleanupJournalBlocksAccountReactivation() {
        val snapshot = restoreAndroidPendingAccountRemovalCleanups(setOf("truncated-row"))

        assertTrue(snapshot.cleanups.isEmpty())
        assertEquals(1, snapshot.malformedEntryCount)
        assertFailsWith<IllegalStateException> {
            requireAndroidAccountRemovalCleanupJournalAllowsActivation(snapshot)
        }
    }

    @Test
    fun malformedCleanupBlocksSelectionBeforePersistencePublicationOrUploadResume() = runBlocking {
        val events = mutableListOf<String>()
        val snapshot = restoreAndroidPendingAccountRemovalCleanups(setOf("truncated-row"))

        assertFailsWith<IllegalStateException> {
            selectAndroidAccountAfterRemovalCleanup(
                session = NextcloudSession("https://cloud.example.test", "alice", "secret"),
                retryPendingCleanup = { requireAndroidAccountRemovalCleanupJournalAllowsActivation(snapshot) },
                registerSessionPrivateValues = { events += "publish-private-state" },
                persistSelection = { events += listOf("persist-selection", "publish-account", "resume-uploads") },
            )
        }

        assertTrue(events.isEmpty())
    }

    @Test
    fun malformedCleanupBlocksStartupAndExplicitCredentialLoadsBeforePrivatePublication() {
        val session = NextcloudSession("https://cloud.example.test", "alice", "secret")
        val snapshot = restoreAndroidPendingAccountRemovalCleanups(setOf("truncated-row"))
        var privatePublications = 0
        var publicSessionPublications = 0
        val restore = {
            restoreAndroidSessionAfterRemovalCleanup(session.accountId, { snapshot }) {
                privatePublications += 1
                session
            }
        }

        val startup = AndroidFileSyncSessionSchedulingGuard().restorePersistedSession(
            load = restore,
            accountIdOf = NextcloudDocumentIds::accountKey,
            publishAccount = { restored, _ -> if (restored != null) publicSessionPublications += 1 },
        )

        assertEquals(null, startup)
        assertEquals(null, restore())
        assertEquals(0, privatePublications)
        assertEquals(0, publicSessionPublications)
    }

    @Test
    fun credentialLoadsStayBlockedWhileAsyncCleanupStillOwnsTheMatchingTombstone() = runBlocking {
        val session = NextcloudSession("https://cloud.example.test", "alice", "secret")
        val pending = AndroidPendingAccountRemovalCleanup(
            accountStorageKey = session.accountId.storageKey,
            workIdentity = NextcloudDocumentIds.accountKey(session),
        )
        var encoded = setOf(encodeAndroidPendingAccountRemovalCleanup(pending))
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val worker = async {
            recoverPendingAndroidAccountRemovalCleanups(
                pending = setOf(pending),
                accountOwnedByRegistry = { true },
                removeAccountOwnedWork = {},
                clearCleanup = {
                    cleanupEntered.complete(Unit)
                    releaseCleanup.await()
                    encoded = emptySet()
                },
                recordFailure = {},
            )
        }
        cleanupEntered.await()
        var privatePublications = 0
        val restore = {
            restoreAndroidSessionAfterRemovalCleanup(
                session.accountId,
                { restoreAndroidPendingAccountRemovalCleanups(encoded) },
            ) {
                privatePublications += 1
                session
            }
        }

        assertEquals(
            null,
            AndroidFileSyncSessionSchedulingGuard().restorePersistedSession(restore, NextcloudDocumentIds::accountKey),
        )
        assertEquals(null, restore())
        assertEquals(0, privatePublications)
        releaseCleanup.complete(Unit)
        assertTrue(worker.await())
        assertEquals(session, restore())
        assertEquals(1, privatePublications)
    }

    @Test
    fun malformedCleanupJournalReadDoesNotRewriteStoredTombstones() {
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

        assertEquals(setOf(valid), journal.pending())

        assertEquals(0, editCalls)
        assertEquals(0, commitCalls)
        assertEquals(linkedSetOf(encodeAndroidPendingAccountRemovalCleanup(valid), "truncated-row"), encoded)
    }

    @Test
    fun cleanupJournalEditsPreserveMalformedPeersWhileReplacingValidTombstones() {
        val original = AndroidPendingAccountRemovalCleanup(
            accountStorageKey = "a".repeat(64),
            workIdentity = "1".repeat(32),
        )
        val replacement = original.copy(workIdentity = "2".repeat(32))
        val peer = AndroidPendingAccountRemovalCleanup(
            accountStorageKey = "b".repeat(64),
            workIdentity = "3".repeat(32),
        )
        val malformed = "truncated-row"
        var malformedCount = 0
        val encoded = linkedSetOf(
            encodeAndroidPendingAccountRemovalCleanup(original),
            encodeAndroidPendingAccountRemovalCleanup(peer),
            malformed,
        )

        val replaced = replaceAndroidAccountRemovalCleanup(encoded, replacement) { malformedCount += 1 }
        val cleared = removeAndroidAccountRemovalCleanup(replaced, replacement.accountStorageKey) {
            malformedCount += 1
        }

        assertEquals(
            linkedSetOf(encodeAndroidPendingAccountRemovalCleanup(peer), malformed),
            cleared,
        )
        assertEquals(2, malformedCount)
    }
}
