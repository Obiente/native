package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AndroidProviderRecoveryIdentityTest {
    private val oldSession = NextcloudSession("https://CLOUD.example.test:443/", "alice", "old-synthetic")
    private val session = NextcloudSession("https://cloud.example.test", "alice", "new-synthetic")

    @Test
    fun retiredCanonicalAndVerifiedHistoricalRootsUseTheSuppliedSessionWithoutCredentialLookup() {
        for (legacy in listOf(true, false)) {
            val records = mutableMapOf<String, String>()
            val aliases = mutableMapOf<String, String>()
            val store = AndroidDocumentProviderIncarnationStore(records::get, { key, value ->
                if (value == null) records.remove(key) else records[key] = value
                true
            })
            val aliasStore = AndroidDocumentLegacyAliases(aliases::get, { key, value -> aliases[key] = value; true })
            val incarnation = store.prepareForAccountSave(session.accountId.storageKey, accountAlreadyStored = legacy)
            aliasStore.remember(session, oldSession, incarnation)
            store.retireForRemoval(session.accountId.storageKey)
            val identity = androidProviderRecoveryIdentity(session, store, aliasStore)
            for (key in listOf(NextcloudDocumentIds.documentAccountKey(session), NextcloudDocumentIds.accountKey(oldSession))) {
                val root = NextcloudDocumentIds.documentId(key, incarnation, "old/subtree")
                val moved = NextcloudDocumentIds.documentId(key, incarnation, "elsewhere/recovery")
                assertEquals(identity, androidRootBoundProviderRecoveryIdentity(root, identity))
                val discovery = androidSafRetirementDiscoveryIdentityRoot(root, identity)
                assertNotNull(androidSafOwnedDownloadRecoveryDirectory(discovery, moved))
                assertEquals(key, NextcloudDocumentIds.parse(discovery).accountKey)
                withAndroidDocumentsProviderRecoveryPermit(session, moved, AndroidDocumentsProviderRecoveryOperation.QueryChildren,
                    identity = identity) {
                    val resolved = resolveAndroidDocumentsProviderSession(moved, AndroidDocumentsProviderRecoveryOperation.QueryChildren, true) {
                        error("Retired recovery must not reload unavailable credentials")
                    }
                    assertEquals(session, resolved?.session)
                    assertEquals(incarnation, resolved?.reference?.incarnation)
                }
            }
        }
    }

    @Test
    fun malformedOptionalAliasesDoNotBlockCurrentCanonicalRecoveryBeforeRetirement() {
        for (wrongType in listOf(false, true)) {
            val store = AndroidDocumentProviderIncarnationStore({ null }, { _, _ -> true })
            val aliases = AndroidDocumentLegacyAliases({
                if (wrongType) throw ClassCastException("synthetic preference type")
                "damaged optional alias metadata"
            }, { _, _ -> true })
            val identity = androidProviderRecoveryIdentity(session, store, aliases)
            val current = NextcloudDocumentIds.documentId(session, identity.incarnation, "folder")
            val unknown = NextcloudDocumentIds.documentId("c".repeat(32), identity.incarnation, "folder")
            assertNotNull(androidRootBoundProviderRecoveryIdentity(current, identity))
            assertNull(androidRootBoundProviderRecoveryIdentity(unknown, identity))
            assertEquals(emptySet(), identity.legacyAliases)
        }
    }

    @Test
    fun unavailableAliasStorageStillStopsRecovery() {
        val store = AndroidDocumentProviderIncarnationStore({ null }, { _, _ -> true })
        val aliases = AndroidDocumentLegacyAliases({ throw java.io.IOException("synthetic unavailable storage") }, { _, _ -> true })
        assertFailsWith<java.io.IOException> { androidProviderRecoveryIdentity(session, store, aliases) }
    }

    @Test
    fun unknownAliasesOtherAccountsAndEarlierIncarnationsCannotBorrowRecoveryAuthority() {
        val incarnation = NextcloudDocumentIncarnation.Versioned("a".repeat(32))
        val identity = AndroidProviderRecoveryIdentity(session, incarnation, setOf(NextcloudDocumentIds.accountKey(oldSession)))
        val wrongIncarnation = NextcloudDocumentIds.documentId(session, NextcloudDocumentIncarnation.Versioned("b".repeat(32)), "file")
        val other = NextcloudDocumentIds.documentId(session.copy(loginName = "bob"), incarnation, "file")
        val unverified = NextcloudDocumentIds.documentId("c".repeat(32), incarnation, "file")
        for (id in listOf(wrongIncarnation, other, unverified)) {
            assertNull(androidRootBoundProviderRecoveryIdentity(id, identity))
            assertFailsWith<IllegalArgumentException> {
                withAndroidDocumentsProviderRecoveryPermit(session, id, AndroidDocumentsProviderRecoveryOperation.OpenRead,
                    identity = identity) { error("Invalid identity must never reach provider access") }
            }
        }
        val legacy = NextcloudDocumentIds.documentId(NextcloudDocumentIds.accountKey(oldSession), NextcloudDocumentIncarnation.Legacy, "file")
        assertNull(androidRootBoundProviderRecoveryIdentity(legacy, identity))
    }
}
