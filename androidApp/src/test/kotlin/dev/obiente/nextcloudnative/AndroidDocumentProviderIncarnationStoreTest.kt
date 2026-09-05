package dev.obiente.nextcloudnative

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

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
        fixture.store.retire(accountIdentity)
        val first = fixture.store.prepareForAccountSave(accountIdentity, accountAlreadyStored = false)
        fixture.store.retire(accountIdentity)
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
        val replacement = fixture.store.prepareForAccountSave(accountIdentity, accountAlreadyStored = false)

        assertFailsWith<IllegalStateException> { fixture.store.rollback(retirement) }
        assertEquals(replacement, fixture.store.activeIncarnation(accountIdentity))
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
}
