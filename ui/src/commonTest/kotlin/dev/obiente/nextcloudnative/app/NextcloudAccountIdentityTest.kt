package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NextcloudAccountIdentityTest {
    @Test
    fun credentialRotationKeepsTheSameAccountIdentity() {
        val session = session()

        assertEquals(session.accountId, session.copy(appPassword = "rotated-secret").accountId)
    }

    @Test
    fun trailingServerSlashKeepsTheSameAccountIdentity() {
        val session = session()

        assertEquals(session.accountId, session.copy(serverUrl = "https://cloud.example.test/Cloud/").accountId)
    }

    @Test
    fun caseSensitiveServerPathsRemainDifferentAccounts() {
        val session = session()

        assertNotEquals(session.accountId, session.copy(serverUrl = "https://cloud.example.test/cloud").accountId)
    }

    @Test
    fun schemeHostAndDefaultPortUseCanonicalUrlIdentity() {
        val canonical = session()
        val equivalent = canonical.copy(serverUrl = "HTTPS://CLOUD.EXAMPLE.TEST:443/Cloud///")

        assertEquals(canonical.accountId, equivalent.accountId)
    }

    @Test
    fun canonicalIdentityRetainsThePreviousPersistenceDigestForMigration() {
        val canonical = session()
        val equivalent = canonical.copy(serverUrl = "HTTPS://CLOUD.EXAMPLE.TEST:443/Cloud/")

        assertEquals(
            accountPersistenceScopeDigests(canonical).current,
            accountPersistenceScopeDigests(equivalent).current,
        )
        assertNotEquals(
            legacyPreviewCacheDigest(canonical),
            legacyPreviewCacheDigest(equivalent),
        )
        assertEquals(
            legacyPreviewCacheDigest(equivalent),
            accountPersistenceScopeDigests(equivalent).legacy,
        )
    }

    @Test
    fun nonDefaultPortsRemainDifferentAccounts() {
        val canonical = session()

        assertNotEquals(
            canonical.accountId,
            canonical.copy(serverUrl = "https://cloud.example.test:8443/Cloud").accountId,
        )
    }

    @Test
    fun accountIdentityRejectsUrlCredentialsQueriesAndFragments() {
        listOf(
            "https://alice:secret@cloud.example.test/Cloud",
            "https://cloud.example.test/Cloud?account=alice",
            "https://cloud.example.test/Cloud#account",
        ).forEach { serverUrl ->
            assertFailsWith<IllegalArgumentException> {
                session().copy(serverUrl = serverUrl).accountId
            }
        }
    }

    @Test
    fun sessionAndAccountIdentityDoNotRenderPrivateValues() {
        val session = session()

        assertEquals("NextcloudAccountId(<redacted>)", session.accountId.toString())
        assertEquals(
            "NextcloudSession(serverUrl=<redacted>, loginName=<redacted>, appPassword=<redacted>)",
            session.toString(),
        )
        assertNotEquals(session.serverUrl, session.accountId.storageKey)
        assertNotEquals(session.loginName, session.accountId.storageKey)
        assertNotEquals(session.appPassword, session.accountId.storageKey)
    }

    @Test
    fun notesCacheDoesNotCollideForCaseSensitiveServerPaths() {
        val cache = NextcloudNotesCache()
        val upper = session()
        val lower = upper.copy(serverUrl = "https://cloud.example.test/cloud")
        cache.storeList(upper, listOf(note(id = 1, title = "Upper")), cache.producer(upper))

        assertNotNull(cache.list(upper))
        assertNull(cache.list(lower))
    }

    private fun session() = NextcloudSession(
        serverUrl = "https://cloud.example.test/Cloud",
        loginName = "alice",
        appPassword = "private-app-password",
    )

    private fun note(id: Long, title: String) = NextcloudNote(
        id = id,
        title = title,
        modified = 1L,
        category = "Work",
        favorite = false,
        readOnly = false,
        content = null,
        etag = null,
    )
}
