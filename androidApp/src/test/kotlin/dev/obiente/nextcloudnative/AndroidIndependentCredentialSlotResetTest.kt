package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class AndroidIndependentCredentialSlotResetTest {
    @Test
    fun malformedRegistryResetRecoversEveryBoundedSlotIdentityBeforeDeletion() {
        val first = NextcloudSession("https://one.example.test", "alice", "first-secret")
        val second = NextcloudSession("https://two.example.test", "bob", "second-secret")
        val sessionsByKey = listOf(first, second).associateBy { session ->
            androidAccountCredentialSlotKey(session.accountId)
        }
        val ciphertexts = sessionsByKey.mapValues { (_, session) -> "encrypted-${session.accountId.storageKey}" }
        val payloads = sessionsByKey.mapValues { (_, session) ->
            encodeAndroidAccountCredentialState(AndroidAccountCredentialState.Empty.upsertAndSelect(session))
        }

        val recovered = recoverAndroidIndependentCredentialSlotsForReset(
            preferenceKeys = ciphertexts.keys,
            readEncrypted = ciphertexts::get,
            decrypt = { encrypted ->
                payloads.getValue(ciphertexts.entries.single { entry -> entry.value == encrypted }.key)
            },
        )

        assertEquals(
            setOf(first.accountId, second.accountId),
            recovered.mapTo(linkedSetOf()) { slot -> slot.session.accountId },
        )
        assertEquals(ciphertexts.keys, recovered.mapTo(linkedSetOf()) { slot -> slot.preferenceKey })
    }

    @Test
    fun malformedRegistryResetRejectsMismatchedOrUnreadableSlotIdentity() {
        val session = NextcloudSession("https://cloud.example.test", "alice", "secret")
        val wrongKey = "$ANDROID_ACCOUNT_CREDENTIAL_SLOT_KEY_PREFIX${"f".repeat(64)}"
        val payload = encodeAndroidAccountCredentialState(AndroidAccountCredentialState.Empty.upsertAndSelect(session))

        assertFailsWith<IllegalStateException> {
            recoverAndroidIndependentCredentialSlotsForReset(
                preferenceKeys = listOf(wrongKey),
                readEncrypted = { "encrypted" },
                decrypt = { payload },
            )
        }
        assertFailsWith<IllegalStateException> {
            recoverAndroidIndependentCredentialSlotsForReset(
                preferenceKeys = listOf(androidAccountCredentialSlotKey(session.accountId)),
                readEncrypted = { "encrypted" },
                decrypt = { "{malformed" },
            )
        }
    }

    @Test
    fun recoveredSlotsArePreparedAndJournaledBeforeDeletionAndCleanup() = runBlocking {
        val first = resetSlot(NextcloudSession("https://one.example.test", "alice", "first-secret"))
        val second = resetSlot(NextcloudSession("https://two.example.test", "bob", "second-secret"))
        val events = mutableListOf<String>()
        val presentSlots = mutableSetOf(first.preferenceKey, second.preferenceKey)
        val tombstones = mutableSetOf<String>()
        val completedRetirements = mutableListOf<String>()

        retireUnregisteredAndroidAccountCredentialSlots(
            slots = listOf(first, second),
            guard = AndroidAccountOperationGuard(),
            prepareAccountRemoval = { session ->
                events += "prepare-${session.loginName}"
                session.accountId.storageKey
            },
            rollbackPreparedRemoval = { error("prepared retirements must not roll back") },
            completePreparedRemoval = { retirement, accountStorageKey ->
                completedRetirements += retirement
                tombstones -= accountStorageKey
            },
            commitSlotRemoval = { slot, cleanup ->
                events += "commit-${slot.session.loginName}"
                presentSlots -= slot.preferenceKey
                tombstones += cleanup.accountStorageKey
            },
            rollbackSlotRemoval = { slot -> presentSlots += slot.preferenceKey },
            removeAccountOwnedState = { session ->
                assertFalse(androidAccountCredentialSlotKey(session.accountId) in presentSlots)
                assertTrue(session.accountId.storageKey in tombstones)
                events += "cleanup-${session.loginName}"
            },
            clearCleanup = { accountStorageKey -> tombstones -= accountStorageKey },
            recordCleanupFailure = { error("cleanup must succeed") },
        )

        assertEquals(
            listOf("prepare-alice", "commit-alice", "cleanup-alice", "prepare-bob", "commit-bob", "cleanup-bob"),
            events,
        )
        assertTrue(presentSlots.isEmpty())
        assertTrue(tombstones.isEmpty())
        assertEquals(listOf(first.session.accountId.storageKey, second.session.accountId.storageKey), completedRetirements)
    }

    @Test
    fun cleanupFailureKeepsOnlyItsTombstoneAndDoesNotDropLaterHealthyCleanup() = runBlocking {
        val first = resetSlot(NextcloudSession("https://one.example.test", "alice", "first-secret"))
        val second = resetSlot(NextcloudSession("https://two.example.test", "bob", "second-secret"))
        val tombstones = mutableSetOf<String>()
        val removed = mutableSetOf<String>()
        val completed = mutableSetOf<String>()
        val failures = mutableListOf<Exception>()

        retireUnregisteredAndroidAccountCredentialSlots(
            slots = listOf(first, second),
            guard = AndroidAccountOperationGuard(),
            prepareAccountRemoval = { it.accountId.storageKey },
            rollbackPreparedRemoval = { error("committed retirements must not roll back") },
            completePreparedRemoval = { retirement, accountStorageKey ->
                completed += retirement
                tombstones -= accountStorageKey
            },
            commitSlotRemoval = { slot, cleanup ->
                removed += slot.preferenceKey
                tombstones += cleanup.accountStorageKey
            },
            rollbackSlotRemoval = { error("committed slots must not be restored") },
            removeAccountOwnedState = { session ->
                if (session == first.session) error("synthetic cleanup failure")
            },
            clearCleanup = { accountStorageKey -> tombstones -= accountStorageKey },
            recordCleanupFailure = failures::add,
        )

        assertEquals(setOf(first.preferenceKey, second.preferenceKey), removed)
        assertEquals(setOf(first.session.accountId.storageKey), tombstones)
        assertEquals(setOf(second.session.accountId.storageKey), completed)
        assertEquals(1, failures.size)
    }

    @Test
    fun cancellationAfterCommittedSlotLeavesRetryTombstoneWithoutResurrection() = runBlocking {
        val first = resetSlot(NextcloudSession("https://one.example.test", "alice", "first-secret"))
        val second = resetSlot(NextcloudSession("https://two.example.test", "bob", "second-secret"))
        val removed = mutableSetOf<String>()
        val restored = mutableSetOf<String>()
        val tombstones = mutableSetOf<String>()

        assertFailsWith<CancellationException> {
            retireUnregisteredAndroidAccountCredentialSlots(
                slots = listOf(first, second),
                guard = AndroidAccountOperationGuard(),
                prepareAccountRemoval = { it.accountId.storageKey },
                rollbackPreparedRemoval = { error("committed retirements must not roll back") },
                completePreparedRemoval = { _, accountStorageKey -> tombstones -= accountStorageKey },
                commitSlotRemoval = { slot, cleanup ->
                    removed += slot.preferenceKey
                    tombstones += cleanup.accountStorageKey
                },
                rollbackSlotRemoval = { slot -> restored += slot.preferenceKey },
                removeAccountOwnedState = { session ->
                    if (session == second.session) throw CancellationException("synthetic cancellation")
                },
                clearCleanup = { accountStorageKey -> tombstones -= accountStorageKey },
                recordCleanupFailure = { error("cancellation must propagate") },
            )
        }

        assertEquals(setOf(first.preferenceKey, second.preferenceKey), removed)
        assertTrue(restored.isEmpty())
        assertEquals(setOf(second.session.accountId.storageKey), tombstones)
    }

    @Test
    fun rollbackRestoredSlotRetriesPreexistingTombstoneBeforeResettingIt() = runBlocking {
        val slot = resetSlot(NextcloudSession("https://one.example.test", "alice", "first-secret"))
        val tombstones = mutableSetOf(slot.session.accountId.storageKey)
        var retryFails = true
        var commitAttempted = false

        assertFailsWith<IllegalStateException> {
            retireUnregisteredAndroidAccountCredentialSlots(
                slots = listOf(slot),
                preexistingCleanupAccountStorageKeys = tombstones.toSet(),
                retryPreexistingCleanup = {
                    if (retryFails) error("synthetic persisted cleanup failure")
                    tombstones -= it.session.accountId.storageKey
                },
                guard = AndroidAccountOperationGuard(),
                prepareAccountRemoval = { it.accountId.storageKey },
                rollbackPreparedRemoval = {},
                completePreparedRemoval = { _, accountStorageKey -> tombstones -= accountStorageKey },
                commitSlotRemoval = { _, _ -> commitAttempted = true; error("synthetic commit failure") },
                rollbackSlotRemoval = { error("slot must remain untouched") },
                removeAccountOwnedState = { error("cleanup must not start") },
                clearCleanup = { tombstones -= it },
                recordCleanupFailure = { error("cleanup must not start") },
            )
        }

        assertFalse(commitAttempted)
        assertEquals(setOf(slot.session.accountId.storageKey), tombstones)

        retryFails = false
        assertFailsWith<IllegalStateException> {
            retireUnregisteredAndroidAccountCredentialSlots(
                slots = listOf(slot),
                preexistingCleanupAccountStorageKeys = tombstones.toSet(),
                retryPreexistingCleanup = { tombstones -= it.session.accountId.storageKey },
                guard = AndroidAccountOperationGuard(),
                prepareAccountRemoval = { it.accountId.storageKey },
                rollbackPreparedRemoval = {},
                completePreparedRemoval = { _, accountStorageKey -> tombstones -= accountStorageKey },
                commitSlotRemoval = { _, cleanup ->
                    commitAttempted = true
                    tombstones += cleanup.accountStorageKey
                    error("synthetic slot-removal commit failure")
                },
                rollbackSlotRemoval = {},
                removeAccountOwnedState = { error("cleanup must not start") },
                clearCleanup = { tombstones -= it },
                recordCleanupFailure = { error("cleanup must not start") },
            )
        }
        assertTrue(commitAttempted)
        assertTrue(tombstones.isEmpty())
    }

    @Test
    fun malformedSlotResetUsesCanonicalDocumentLifetimeFence() = runBlocking {
        val session = NextcloudSession("https://CLOUD.example.test:443/", "alice", "first-secret")
        val slot = resetSlot(session)
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val documentLease = lifetimeGuard.acquireReadBlocking(session.accountId.storageKey)
        val committed = CompletableDeferred<Unit>()

        val reset = async(Dispatchers.Default) {
            retireUnregisteredAndroidAccountCredentialSlots(
                slots = listOf(slot),
                guard = guard,
                lifetimeGuard = lifetimeGuard,
                prepareAccountRemoval = { it.accountId.storageKey },
                rollbackPreparedRemoval = {},
                completePreparedRemoval = { _, _ -> },
                commitSlotRemoval = { _, _ -> committed.complete(Unit) },
                rollbackSlotRemoval = {},
                removeAccountOwnedState = {},
                clearCleanup = {},
                recordCleanupFailure = { error("cleanup must succeed") },
            )
        }
        yield()
        assertFalse(committed.isCompleted)

        documentLease.close()
        withTimeout(1_000L) { reset.await() }
        assertTrue(committed.isCompleted)
    }

    @Test
    fun slotCommitFailureRollsBackItsExactPreparedRetirement() = runBlocking {
        val slot = resetSlot(NextcloudSession("https://one.example.test", "alice", "first-secret"))
        val token = "retirement-${slot.session.accountId.storageKey}"
        val rollbacks = mutableListOf<String>()
        var slotRestored = false

        assertFailsWith<IllegalStateException> {
            retireUnregisteredAndroidAccountCredentialSlots(
                slots = listOf(slot),
                guard = AndroidAccountOperationGuard(),
                lifetimeGuard = AndroidAccountRemovalLifetimeGuard(),
                prepareAccountRemoval = { token },
                rollbackPreparedRemoval = { rollbacks += it },
                completePreparedRemoval = { _, _ -> error("failed commit must not complete retirement") },
                commitSlotRemoval = { _, _ -> error("synthetic slot commit failure") },
                rollbackSlotRemoval = { slotRestored = true },
                removeAccountOwnedState = { error("cleanup must not start") },
                clearCleanup = {},
                recordCleanupFailure = { error("cleanup must not start") },
            )
        }

        assertTrue(slotRestored)
        assertEquals(listOf(token), rollbacks)
    }

    private fun resetSlot(session: NextcloudSession) = AndroidIndependentCredentialSlotReset(
        preferenceKey = androidAccountCredentialSlotKey(session.accountId),
        encrypted = "encrypted-${session.accountId.storageKey}",
        session = session,
    )
}
