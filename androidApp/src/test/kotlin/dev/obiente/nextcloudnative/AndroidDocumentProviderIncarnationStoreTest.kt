package dev.obiente.nextcloudnative

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidDocumentProviderIncarnationStoreTest {
    private val accountIdentity = "a".repeat(32)

    @Test
    fun legacyIdentityRemainsUsableUntilItsFirstRemoval() {
        val fixture = fixture()

        assertEquals(
            NextcloudDocumentIncarnation.Legacy,
            fixture.store.prepareForAccountSave(accountIdentity, accountAlreadyStored = true),
        )
        assertEquals(NextcloudDocumentIncarnation.Legacy, fixture.store.activeIncarnation(accountIdentity))
        assertEquals(emptyMap(), fixture.records)
    }

    @Test
    fun aNewIdentityStartsWithAVersionedIncarnation() {
        val fixture = fixture(incarnations = listOf("1".repeat(32)))

        assertEquals(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
            fixture.store.prepareForAccountSave(accountIdentity, accountAlreadyStored = false),
        )
    }

    @Test
    fun removalPersistsALegacyTombstoneBeforeReturning() {
        val fixture = fixture()

        assertEquals(NextcloudDocumentIncarnation.Legacy, fixture.store.retire(accountIdentity))
        assertEquals(
            AndroidDocumentProviderIncarnationRecord.Retired(NextcloudDocumentIncarnation.Legacy),
            decodeAndroidDocumentProviderIncarnationRecord(requireNotNull(fixture.records[accountIdentity])),
        )
    }

    @Test
    fun processRestartAfterRemovalCreatesANewIncarnationForTheReaddedIdentity() {
        val records = mutableMapOf<String, String>()
        fixture(records = records).store.retire(accountIdentity)

        val restarted = fixture(records = records, incarnations = listOf("1".repeat(32))).store
        assertFailsWith<IllegalStateException> { restarted.activeIncarnation(accountIdentity) }
        restarted.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Absent))
        val replacement = restarted.prepareForAccountSave(accountIdentity, accountAlreadyStored = false)

        assertEquals(NextcloudDocumentIncarnation.Versioned("1".repeat(32)), replacement)
        assertEquals(
            AndroidDocumentProviderIncarnationRecord.Active(replacement),
            decodeAndroidDocumentProviderIncarnationRecord(requireNotNull(records[accountIdentity])),
        )
    }

    @Test
    fun everyRemovalAndReaddChangesTheIncarnationAgain() {
        val fixture = fixture(incarnations = listOf("1".repeat(32), "2".repeat(32)))
        fixture.store.complete(fixture.store.retireForRemoval(accountIdentity))
        val first = fixture.store.prepareForAccountSave(accountIdentity, accountAlreadyStored = false)
        fixture.store.complete(fixture.store.retireForRemoval(accountIdentity))
        val replacement = fixture.store.prepareForAccountSave(accountIdentity, accountAlreadyStored = false)

        assertNotEquals(first, replacement)
        assertEquals(NextcloudDocumentIncarnation.Versioned("2".repeat(32)), replacement)
    }

    @Test
    fun interruptedTombstoneCommitBlocksRemoval() {
        val active = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        val records = mutableMapOf(accountIdentity to encodeAndroidDocumentProviderIncarnationRecord(active))
        val store = AndroidDocumentProviderIncarnationStore(
            read = records::get,
            commit = { _, _ -> false },
        )

        assertFailsWith<IllegalStateException> { store.retire(accountIdentity) }
        assertEquals(active, decodeAndroidDocumentProviderIncarnationRecord(requireNotNull(records[accountIdentity])))
    }

    @Test
    fun malformedStateCannotAuthorizeDocumentsButRemovalCanReplaceItWithATombstone() {
        val records = mutableMapOf(accountIdentity to "broken")
        val fixture = fixture(records = records)

        assertFailsWith<IllegalArgumentException> { fixture.store.activeIncarnation(accountIdentity) }
        assertEquals(NextcloudDocumentIncarnation.Legacy, fixture.store.retire(accountIdentity))
        assertEquals(
            AndroidDocumentProviderIncarnationRecord.Retired(NextcloudDocumentIncarnation.Legacy),
            decodeAndroidDocumentProviderIncarnationRecord(requireNotNull(records[accountIdentity])),
        )
    }

    @Test
    fun aRetiredIdentityCannotBeReactivatedWhileCredentialsStillExist() {
        val fixture = fixture(incarnations = listOf("1".repeat(32)))
        fixture.store.retire(accountIdentity)

        assertFailsWith<IllegalStateException> {
            fixture.store.prepareForAccountSave(accountIdentity, accountAlreadyStored = true)
        }
        assertFailsWith<IllegalStateException> { fixture.store.activeIncarnation(accountIdentity) }
    }

    @Test
    fun rollbackRestoresTheExactActiveIncarnationAfterCredentialPersistenceFails() {
        val active = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        val fixture = fixture(
            records = mutableMapOf(accountIdentity to encodeAndroidDocumentProviderIncarnationRecord(active)),
        )

        val retirement = fixture.store.retireForRemoval(accountIdentity)
        fixture.store.rollback(retirement)

        assertEquals(active, decodeAndroidDocumentProviderIncarnationRecord(requireNotNull(fixture.records[accountIdentity])))
        assertEquals(active.incarnation, fixture.store.activeIncarnation(accountIdentity))
    }

    @Test
    fun rollbackRemovesANewLegacyTombstoneAfterCredentialPersistenceFails() {
        val fixture = fixture()

        val retirement = fixture.store.retireForRemoval(accountIdentity)
        fixture.store.rollback(retirement)

        assertEquals(emptyMap(), fixture.records)
        assertEquals(NextcloudDocumentIncarnation.Legacy, fixture.store.activeIncarnation(accountIdentity))
    }

    @Test
    fun rollbackCannotOverwriteAnIncarnationChangedAfterRetirement() {
        val fixture = fixture(incarnations = listOf("1".repeat(32)))
        val retirement = fixture.store.retireForRemoval(accountIdentity)
        val replacement = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        fixture.records[accountIdentity] = encodeAndroidDocumentProviderIncarnationRecord(replacement)

        assertFailsWith<IllegalStateException> { fixture.store.rollback(retirement) }
        assertEquals(
            replacement,
            decodeAndroidDocumentProviderIncarnationRecord(requireNotNull(fixture.records[accountIdentity])),
        )
    }

    @Test
    fun retirementJournalCommitsBeforeTheTombstone() {
        val records = mutableMapOf<String, String>()
        val committedKeys = mutableListOf<String>()
        val store = AndroidDocumentProviderIncarnationStore(
            read = records::get,
            commit = { key, value ->
                committedKeys += key
                if (value == null) records.remove(key) else records[key] = value
                true
            },
            keys = { records.keys },
        )

        store.retireForRemoval(accountIdentity)

        assertTrue(committedKeys.first().startsWith("retirement:"))
        assertEquals(accountIdentity, committedKeys[1])
    }

    @Test
    fun restartAfterJournalButBeforeRetirementKeepsThePriorIncarnation() {
        val active = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        val records = mutableMapOf(accountIdentity to encodeAndroidDocumentProviderIncarnationRecord(active))
        var commits = 0
        val interrupted = AndroidDocumentProviderIncarnationStore(
            read = records::get,
            commit = { key, value ->
                commits += 1
                if (commits == 2) return@AndroidDocumentProviderIncarnationStore false
                if (value == null) records.remove(key) else records[key] = value
                true
            },
            keys = { records.keys },
        )

        assertFailsWith<IllegalStateException> { interrupted.retireForRemoval(accountIdentity) }
        val restarted = fixture(records = records).store
        assertFailsWith<IllegalStateException> { restarted.activeIncarnation(accountIdentity) }

        restarted.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Present))

        assertEquals(active.incarnation, restarted.activeIncarnation(accountIdentity))
        assertEquals(setOf(accountIdentity), records.keys)
    }

    @Test
    fun restartAfterRetirementRestoresThePriorIncarnationWhenCredentialsRemain() {
        val active = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        val records = mutableMapOf(accountIdentity to encodeAndroidDocumentProviderIncarnationRecord(active))
        fixture(records = records).store.retireForRemoval(accountIdentity)

        val restarted = fixture(records = records).store
        restarted.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Present))

        assertEquals(active.incarnation, restarted.activeIncarnation(accountIdentity))
        assertEquals(setOf(accountIdentity), records.keys)
    }

    @Test
    fun restartAfterCredentialRemovalKeepsTheRetiredTombstone() {
        val records = mutableMapOf<String, String>()
        fixture(records = records).store.retireForRemoval(accountIdentity)

        val restarted = fixture(records = records).store
        restarted.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Absent))

        assertFailsWith<IllegalStateException> { restarted.activeIncarnation(accountIdentity) }
        assertEquals(setOf(accountIdentity), records.keys)
    }

    @Test
    fun restartDuringRemoteRevocationRestoresAccessWhenTheLocalAccountStillExists() {
        val active = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        val records = mutableMapOf(accountIdentity to encodeAndroidDocumentProviderIncarnationRecord(active))
        fixture(records = records).store.retireForRemoval(accountIdentity)

        fixture(records = records).store.reconcilePending(
            ownership(AndroidDocumentProviderAccountOwnership.Present),
        )

        assertEquals(active.incarnation, fixture(records = records).store.activeIncarnation(accountIdentity))
    }

    @Test
    fun malformedJournalFailsClosedWithoutChangingTheTombstone() {
        val retired = encodeAndroidDocumentProviderIncarnationRecord(
            AndroidDocumentProviderIncarnationRecord.Retired(NextcloudDocumentIncarnation.Legacy),
        )
        val records = mutableMapOf(
            accountIdentity to retired,
            "retirement:$accountIdentity" to "broken",
        )
        val store = fixture(records = records).store

        assertFailsWith<IllegalArgumentException> {
            store.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Present))
        }

        assertEquals(retired, records[accountIdentity])
        assertTrue("retirement:$accountIdentity" in records)
        assertFailsWith<IllegalStateException> { store.activeIncarnation(accountIdentity) }
    }

    @Test
    fun malformedPriorStoreIsRestoredExactlyAndStillCannotAuthorizeDocuments() {
        val records = mutableMapOf(accountIdentity to "broken")
        fixture(records = records).store.retireForRemoval(accountIdentity)

        val restarted = fixture(records = records).store
        restarted.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Present))

        assertEquals("broken", records[accountIdentity])
        assertEquals(setOf(accountIdentity), records.keys)
        assertFailsWith<IllegalArgumentException> { restarted.activeIncarnation(accountIdentity) }
    }

    @Test
    fun ambiguousCredentialOwnershipLeavesTheRetirementPendingAndUnavailable() {
        val records = mutableMapOf<String, String>()
        fixture(records = records).store.retireForRemoval(accountIdentity)
        val restarted = fixture(records = records).store

        assertFailsWith<IllegalStateException> {
            restarted.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Unknown))
        }

        assertTrue("retirement:$accountIdentity" in records)
        assertFailsWith<IllegalStateException> { restarted.activeIncarnation(accountIdentity) }
    }

    @Test
    fun failedJournalCleanupRetriesWithoutReactivatingACommittedRemoval() {
        val records = mutableMapOf<String, String>()
        fixture(records = records).store.retireForRemoval(accountIdentity)
        var failCleanup = true
        val restarted = AndroidDocumentProviderIncarnationStore(
            read = records::get,
            commit = { key, value ->
                if (key.startsWith("retirement:") && value == null && failCleanup) {
                    failCleanup = false
                    false
                } else {
                    if (value == null) records.remove(key) else records[key] = value
                    true
                }
            },
            keys = { records.keys },
        )

        assertFailsWith<IllegalStateException> {
            restarted.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Absent))
        }
        restarted.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Absent))

        assertFailsWith<IllegalStateException> { restarted.activeIncarnation(accountIdentity) }
        assertEquals(setOf(accountIdentity), records.keys)
    }

    @Test
    fun failedRollbackJournalCleanupRetriesAfterRestoringThePriorIncarnation() {
        val active = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        val records = mutableMapOf(accountIdentity to encodeAndroidDocumentProviderIncarnationRecord(active))
        fixture(records = records).store.retireForRemoval(accountIdentity)
        var failCleanup = true
        val restarted = AndroidDocumentProviderIncarnationStore(
            read = records::get,
            commit = { key, value ->
                if (key.startsWith("retirement:") && value == null && failCleanup) {
                    failCleanup = false
                    false
                } else {
                    if (value == null) records.remove(key) else records[key] = value
                    true
                }
            },
            keys = { records.keys },
        )

        assertFailsWith<IllegalStateException> {
            restarted.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Present))
        }
        restarted.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Present))

        assertEquals(active.incarnation, restarted.activeIncarnation(accountIdentity))
        assertEquals(setOf(accountIdentity), records.keys)
    }

    @Test
    fun committedRemovalReconcilesBeforeTheSameIdentityIsReadded() {
        val records = mutableMapOf<String, String>()
        fixture(records = records).store.retireForRemoval(accountIdentity)
        val restarted = fixture(records = records, incarnations = listOf("1".repeat(32))).store

        restarted.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Absent))
        val replacement = restarted.prepareForAccountSave(accountIdentity, accountAlreadyStored = false)

        assertEquals(NextcloudDocumentIncarnation.Versioned("1".repeat(32)), replacement)
        assertEquals(
            AndroidDocumentProviderIncarnationRecord.Active(replacement),
            decodeAndroidDocumentProviderIncarnationRecord(requireNotNull(records[accountIdentity])),
        )
    }

    @Test
    fun activeCredentialPersistenceFailureRestoresTheDocumentIncarnation() = runBlocking {
        val fixture = fixture()
        lateinit var retirement: AndroidDocumentProviderIncarnationRetirement

        assertFailsWith<IllegalStateException> {
            removeAndroidAccountCredentialData(
                active = true,
                prepareAccountRemoval = { retirement = fixture.store.retireForRemoval(accountIdentity) },
                removeQueuedUploads = {},
                clearActiveAccount = { error("synthetic active credential persistence failure") },
                rollbackActiveRemoval = { fixture.store.rollback(retirement) },
                persistInactiveRemoval = {},
                rollbackInactiveRemoval = {},
            )
        }

        assertEquals(emptyMap(), fixture.records)
        assertEquals(NextcloudDocumentIncarnation.Legacy, fixture.store.activeIncarnation(accountIdentity))
    }

    @Test
    fun inactiveCredentialPersistenceFailureRestoresTheDocumentIncarnation() = runBlocking {
        val active = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        val fixture = fixture(
            records = mutableMapOf(accountIdentity to encodeAndroidDocumentProviderIncarnationRecord(active)),
        )
        lateinit var retirement: AndroidDocumentProviderIncarnationRetirement

        assertFailsWith<IllegalStateException> {
            removeAndroidAccountCredentialData(
                active = false,
                prepareAccountRemoval = { retirement = fixture.store.retireForRemoval(accountIdentity) },
                removeQueuedUploads = {},
                clearActiveAccount = {},
                rollbackActiveRemoval = {},
                persistInactiveRemoval = { error("synthetic inactive credential persistence failure") },
                rollbackInactiveRemoval = { fixture.store.rollback(retirement) },
            )
        }

        assertEquals(active, decodeAndroidDocumentProviderIncarnationRecord(requireNotNull(fixture.records[accountIdentity])))
        assertEquals(active.incarnation, fixture.store.activeIncarnation(accountIdentity))
    }

    private fun fixture(
        records: MutableMap<String, String> = mutableMapOf(),
        incarnations: List<String> = emptyList(),
    ): Fixture {
        val available = ArrayDeque(incarnations)
        return Fixture(
            records = records,
            store = AndroidDocumentProviderIncarnationStore(
                read = records::get,
                commit = { key, value ->
                    if (value == null) records.remove(key) else records[key] = value
                    true
                },
                keys = { records.keys },
                createIncarnation = {
                    NextcloudDocumentIncarnation.Versioned(available.removeFirst())
                },
            ),
        )
    }

    private data class Fixture(
        val records: MutableMap<String, String>,
        val store: AndroidDocumentProviderIncarnationStore,
    )

    private fun ownership(
        value: AndroidDocumentProviderAccountOwnership,
    ): (String) -> AndroidDocumentProviderAccountOwnership = { value }
}
