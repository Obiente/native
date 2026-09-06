package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidDocumentProviderIncarnationStoreTest {
    private val accountIdentity = "a".repeat(64)

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
    fun canonicallyEquivalentServerSpellingsCannotReactivateAnEarlierIncarnation() {
        val original = NextcloudSession(
            serverUrl = "https://cloud.example.test/Cloud",
            loginName = "alice",
            appPassword = "synthetic-password",
        )
        val equivalent = original.copy(serverUrl = "HTTPS://CLOUD.EXAMPLE.TEST:443/Cloud///")
        val fixture = fixture(incarnations = listOf("1".repeat(32), "2".repeat(32)))

        assertEquals(original.accountId, equivalent.accountId)
        assertNotEquals(NextcloudDocumentIds.accountKey(original), NextcloudDocumentIds.accountKey(equivalent))
        val first = fixture.store.prepareForAccountSave(
            original.documentProviderIncarnationAccountIdentity(),
            accountAlreadyStored = false,
        )
        val retainedDocumentId = NextcloudDocumentIds.documentId(original, first, "Documents/report.pdf")
        assertEquals(
            first,
            fixture.store.prepareForAccountSave(
                equivalent.documentProviderIncarnationAccountIdentity(),
                accountAlreadyStored = true,
            ),
        )

        fixture.store.complete(
            fixture.store.retireForRemoval(equivalent.documentProviderIncarnationAccountIdentity()),
        )
        val replacement = fixture.store.prepareForAccountSave(
            original.documentProviderIncarnationAccountIdentity(),
            accountAlreadyStored = false,
        )

        assertNotEquals(first, replacement)
        assertEquals(NextcloudDocumentIncarnation.Versioned("2".repeat(32)), replacement)
        assertEquals(setOf(original.accountId.storageKey), fixture.records.keys)
        assertFailsWith<IllegalArgumentException> {
            NextcloudDocumentIds.requireForSession(retainedDocumentId, original, replacement)
        }
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
    fun oversizedMalformedStateCannotCreateAnUnreadableRetirementJournal() {
        val malformed = "x".repeat(20_000)
        val records = mutableMapOf(accountIdentity to malformed)
        val store = fixture(records = records).store

        assertFailsWith<IllegalArgumentException> { store.retireForRemoval(accountIdentity) }

        assertEquals(mapOf(accountIdentity to malformed), records)
        assertFailsWith<IllegalArgumentException> { store.activeIncarnation(accountIdentity) }
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
    fun credentialReadSkipsRetirementRecoveryWhileACredentialMutationIsActive() {
        val active = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        val records = mutableMapOf(accountIdentity to encodeAndroidDocumentProviderIncarnationRecord(active))
        val store = fixture(records = records).store
        store.retireForRemoval(accountIdentity)
        val credentialMutations = Mutex(locked = true)
        var recoveryRan = false

        assertFalse(
            reconcileAndroidDocumentProviderAccountRemovalsWhenCredentialMutationIdle(credentialMutations) {
                recoveryRan = true
                store.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Present))
            },
        )

        assertFalse(recoveryRan)
        assertTrue("retirement:$accountIdentity" in records)
        assertFailsWith<IllegalStateException> { store.activeIncarnation(accountIdentity) }
        credentialMutations.unlock()
        assertTrue(
            reconcileAndroidDocumentProviderAccountRemovalsWhenCredentialMutationIdle(credentialMutations) {
                store.reconcilePending(ownership(AndroidDocumentProviderAccountOwnership.Present))
            },
        )
        assertEquals(active.incarnation, store.activeIncarnation(accountIdentity))
    }

    @Test
    fun malformedAndUnsupportedJournalsStayUnavailableWhileOtherAccountsRecover() {
        listOf("broken", "2:unsupported", "1:$accountIdentity:present:_w:_w").forEach { malformed ->
            val otherAccount = "b".repeat(64)
            val otherActive = AndroidDocumentProviderIncarnationRecord.Active(
                NextcloudDocumentIncarnation.Versioned("2".repeat(32)),
            )
            val records = mutableMapOf(
                otherAccount to encodeAndroidDocumentProviderIncarnationRecord(otherActive),
            )
            val store = fixture(records = records).store
            store.retireForRemoval(otherAccount)
            records["retirement:$accountIdentity"] = malformed
            val failures = mutableListOf<Exception>()

            store.reconcilePending(
                ownership = ownership(AndroidDocumentProviderAccountOwnership.Present),
                onMalformedJournal = failures::add,
            )

            assertEquals(1, failures.size)
            assertTrue("retirement:$accountIdentity" in records)
            assertFailsWith<IllegalStateException> { store.activeIncarnation(accountIdentity) }
            assertEquals(otherActive.incarnation, store.activeIncarnation(otherAccount))
        }
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

    @Test
    fun credentialResetRetiresEveryActiveIncarnationBeforeCredentialsDisappear() = runBlocking {
        val otherAccount = "b".repeat(64)
        val first = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        val second = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("2".repeat(32)),
        )
        val records = mutableMapOf(
            accountIdentity to encodeAndroidDocumentProviderIncarnationRecord(first),
            otherAccount to encodeAndroidDocumentProviderIncarnationRecord(second),
        )
        val fixture = fixture(records, incarnations = listOf("3".repeat(32), "4".repeat(32)))
        var credentialsCleared = false

        retireAndroidDocumentProviderIncarnationsForCredentialReset(
            store = fixture.store,
            lifetimeGuard = AndroidAccountRemovalLifetimeGuard(),
            clearCredentials = {
                assertFailsWith<IllegalStateException> { fixture.store.activeIncarnation(accountIdentity) }
                assertFailsWith<IllegalStateException> { fixture.store.activeIncarnation(otherAccount) }
                credentialsCleared = true
            },
        )

        assertTrue(credentialsCleared)
        assertFailsWith<IllegalStateException> { fixture.store.activeIncarnation(accountIdentity) }
        assertFailsWith<IllegalStateException> { fixture.store.activeIncarnation(otherAccount) }
        assertEquals(
            NextcloudDocumentIncarnation.Versioned("3".repeat(32)),
            fixture.store.prepareForAccountSave(accountIdentity, accountAlreadyStored = false),
        )
        assertEquals(
            NextcloudDocumentIncarnation.Versioned("4".repeat(32)),
            fixture.store.prepareForAccountSave(otherAccount, accountAlreadyStored = false),
        )
    }

    @Test
    fun credentialResetWaitsForCanonicalDocumentLifetimeLeases() = runBlocking {
        val active = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        val fixture = fixture(
            records = mutableMapOf(accountIdentity to encodeAndroidDocumentProviderIncarnationRecord(active)),
        )
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val descriptorLease = lifetimeGuard.acquireReadBlocking(accountIdentity)
        var credentialsCleared = false
        val reset = async(start = CoroutineStart.UNDISPATCHED) {
            retireAndroidDocumentProviderIncarnationsForCredentialReset(
                fixture.store,
                lifetimeGuard,
                clearCredentials = { credentialsCleared = true },
            )
        }
        yield()

        assertFalse(credentialsCleared)
        descriptorLease.close()
        reset.await()
        assertTrue(credentialsCleared)
    }

    @Test
    fun credentialResetWaitsForARecordlessLegacyDocumentLifetimeLease() = runBlocking {
        val fixture = fixture()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val descriptorLease = lifetimeGuard.acquireReadBlocking(accountIdentity)
        var credentialsCleared = false
        val reset = async(start = CoroutineStart.UNDISPATCHED) {
            retireAndroidDocumentProviderIncarnationsForCredentialReset(
                fixture.store,
                lifetimeGuard,
                clearCredentials = { credentialsCleared = true },
            )
        }
        yield()

        assertFalse(credentialsCleared)
        descriptorLease.close()
        reset.await()
        assertTrue(credentialsCleared)
        assertEquals(emptyMap(), fixture.records)
    }

    @Test
    fun credentialResetResumesAnInterruptedActiveRetirement() = runBlocking {
        val active = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        val activeEncoded = encodeAndroidDocumentProviderIncarnationRecord(active)
        val records = mutableMapOf(accountIdentity to activeEncoded)
        val fixture = fixture(records, incarnations = listOf("2".repeat(32)))
        fixture.store.retireForRemoval(accountIdentity)
        records[accountIdentity] = activeEncoded
        var credentialsCleared = false

        retireAndroidDocumentProviderIncarnationsForCredentialReset(
            fixture.store,
            AndroidAccountRemovalLifetimeGuard(),
            clearCredentials = { credentialsCleared = true },
        )

        assertTrue(credentialsCleared)
        assertFailsWith<IllegalStateException> { fixture.store.activeIncarnation(accountIdentity) }
        assertEquals(setOf(accountIdentity), records.keys)
        assertEquals(
            NextcloudDocumentIncarnation.Versioned("2".repeat(32)),
            fixture.store.prepareForAccountSave(accountIdentity, accountAlreadyStored = false),
        )
    }

    @Test
    fun failedCredentialResetRollsBackAResumedRetirement() = runBlocking {
        val active = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        val activeEncoded = encodeAndroidDocumentProviderIncarnationRecord(active)
        val records = mutableMapOf(accountIdentity to activeEncoded)
        val fixture = fixture(records)
        fixture.store.retireForRemoval(accountIdentity)
        records[accountIdentity] = activeEncoded

        assertFailsWith<IllegalStateException> {
            retireAndroidDocumentProviderIncarnationsForCredentialReset(
                fixture.store,
                AndroidAccountRemovalLifetimeGuard(),
                clearCredentials = { error("synthetic credential reset failure") },
            )
        }

        assertEquals(active.incarnation, fixture.store.activeIncarnation(accountIdentity))
        assertEquals(setOf(accountIdentity), records.keys)
    }

    @Test
    fun credentialResetTombstonesMalformedStateBeforeSafeReadd() = runBlocking {
        val records = mutableMapOf(accountIdentity to "broken")
        val fixture = fixture(records, incarnations = listOf("2".repeat(32)))

        retireAndroidDocumentProviderIncarnationsForCredentialReset(
            fixture.store,
            AndroidAccountRemovalLifetimeGuard(),
            clearCredentials = {},
        )

        assertFailsWith<IllegalStateException> { fixture.store.activeIncarnation(accountIdentity) }
        assertEquals(
            NextcloudDocumentIncarnation.Versioned("2".repeat(32)),
            fixture.store.prepareForAccountSave(accountIdentity, accountAlreadyStored = false),
        )
    }

    @Test
    fun credentialResetQuarantinesMalformedRetirementJournalsBeforeSafeReadd() = runBlocking {
        listOf("broken", "2:unsupported").forEach { malformed ->
            val active = AndroidDocumentProviderIncarnationRecord.Active(
                NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
            )
            val records = mutableMapOf(
                accountIdentity to encodeAndroidDocumentProviderIncarnationRecord(active),
                "retirement:$accountIdentity" to malformed,
            )
            val fixture = fixture(records, incarnations = listOf("2".repeat(32)))
            var credentialsCleared = false

            retireAndroidDocumentProviderIncarnationsForCredentialReset(
                fixture.store,
                AndroidAccountRemovalLifetimeGuard(),
                clearCredentials = { credentialsCleared = true },
            )

            assertTrue(credentialsCleared)
            assertFalse("retirement:$accountIdentity" in records)
            assertEquals(malformed, records["quarantined-retirement:$accountIdentity"])
            assertFailsWith<IllegalStateException> { fixture.store.activeIncarnation(accountIdentity) }
            assertEquals(
                NextcloudDocumentIncarnation.Versioned("2".repeat(32)),
                fixture.store.prepareForAccountSave(accountIdentity, accountAlreadyStored = false),
            )
        }
    }

    @Test
    fun credentialResetTombstonesWrongTypedStateBeforeSafeReadd() = runBlocking {
        val values = mutableMapOf<String, Any>(accountIdentity to setOf("wrong-type"))
        val incarnations = ArrayDeque(listOf("2".repeat(32)))
        val store = AndroidDocumentProviderIncarnationStore(
            read = { key -> values[key] as String? },
            commit = { key, value ->
                if (value == null) values.remove(key) else values[key] = value
                true
            },
            keys = { values.keys },
            createIncarnation = { NextcloudDocumentIncarnation.Versioned(incarnations.removeFirst()) },
        )

        retireAndroidDocumentProviderIncarnationsForCredentialReset(
            store,
            AndroidAccountRemovalLifetimeGuard(),
            clearCredentials = {},
        )

        assertFailsWith<IllegalStateException> { store.activeIncarnation(accountIdentity) }
        assertEquals(
            NextcloudDocumentIncarnation.Versioned("2".repeat(32)),
            store.prepareForAccountSave(accountIdentity, accountAlreadyStored = false),
        )
    }

    @Test
    fun failedCredentialResetRollsBackEveryPreparedIncarnation() = runBlocking {
        val otherAccount = "b".repeat(64)
        val active = AndroidDocumentProviderIncarnationRecord.Active(
            NextcloudDocumentIncarnation.Versioned("1".repeat(32)),
        )
        val records = mutableMapOf(
            accountIdentity to encodeAndroidDocumentProviderIncarnationRecord(active),
            otherAccount to encodeAndroidDocumentProviderIncarnationRecord(active),
        )
        val fixture = fixture(records)

        assertFailsWith<IllegalStateException> {
            retireAndroidDocumentProviderIncarnationsForCredentialReset(
                fixture.store,
                AndroidAccountRemovalLifetimeGuard(),
                clearCredentials = { error("synthetic credential reset failure") },
            )
        }

        assertEquals(active.incarnation, fixture.store.activeIncarnation(accountIdentity))
        assertEquals(active.incarnation, fixture.store.activeIncarnation(otherAccount))
        assertEquals(setOf(accountIdentity, otherAccount), records.keys)
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
