package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NextcloudDocumentIdsTest {
    private val legacy = NextcloudDocumentIncarnation.Legacy

    @Test
    fun cacheAccountIdIsAFullSha256Digest() {
        val digest = NextcloudDocumentIds.cacheAccountId(session)

        assertEquals(64, digest.length)
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(digest.startsWith(NextcloudDocumentIds.accountKey(session)))
    }

    private val session = NextcloudSession(
        serverUrl = "https://cloud.example",
        loginName = "alice",
        appPassword = "not-used-by-id-mapping",
    )

    @Test
    fun rootAndUnicodePathsRoundTripWithoutLeakingAccountDetails() {
        val root = NextcloudDocumentIds.rootId(session, legacy)
        assertEquals("", NextcloudDocumentIds.requireForSession(root, session, legacy).path)

        val id = NextcloudDocumentIds.documentId(session, legacy, "/Photos/July & August/旅行.jpg/")
        assertEquals(
            "Photos/July & August/旅行.jpg",
            NextcloudDocumentIds.requireForSession(id, session, legacy).path,
        )
        assertFalse(id.contains("cloud.example"))
        assertFalse(id.contains("alice"))
        assertFalse(id.contains("not-used"))
    }

    @Test
    fun documentIdsAreStableAcrossCredentialRotation() {
        val rotated = session.copy(appPassword = "new-app-password")
        assertEquals(
            NextcloudDocumentIds.documentId(session, legacy, "Documents/report.pdf"),
            NextcloudDocumentIds.documentId(rotated, legacy, "Documents/report.pdf"),
        )
    }

    @Test
    fun accountWorkIdentityRetainsThePreRegistryRawServerDigest() {
        val legacySession = session.copy(serverUrl = "https://CLOUD.EXAMPLE:443/")

        assertEquals(
            "c21f46fbb8dbbf9611423baaaf1dd45a",
            NextcloudDocumentIds.accountKey(legacySession),
        )
        assertEquals(
            "c21f46fbb8dbbf9611423baaaf1dd45a664f9593a1d14bb41d486e01b0e54c24",
            NextcloudDocumentIds.cacheAccountId(legacySession),
        )
    }

    @Test
    fun accountIdentitySeparatesOtherwiseEqualPaths() {
        val other = session.copy(loginName = "bob")
        val aliceId = NextcloudDocumentIds.documentId(session, legacy, "Documents/report.pdf")
        val bobId = NextcloudDocumentIds.documentId(other, legacy, "Documents/report.pdf")
        assertNotEquals(aliceId, bobId)
        assertFailsWith<IllegalArgumentException> {
            NextcloudDocumentIds.requireForSession(aliceId, other, legacy)
        }
    }

    @Test
    fun rejectsTraversalAndMalformedIds() {
        assertFailsWith<IllegalArgumentException> {
            NextcloudDocumentIds.documentId(session, legacy, "Documents/../secrets.txt")
        }
        assertFailsWith<IllegalArgumentException> { NextcloudDocumentIds.parse("not-a-nextcloud-document") }
        val rootWithNonCanonicalPadding = NextcloudDocumentIds.rootId(session, legacy) + "=="
        assertFailsWith<IllegalArgumentException> { NextcloudDocumentIds.parse(rootWithNonCanonicalPadding) }
    }

    @Test
    fun versionedIdsRejectEarlierFileAndSubfolderGrantIds() {
        val first = NextcloudDocumentIncarnation.Versioned("1".repeat(32))
        val replacement = NextcloudDocumentIncarnation.Versioned("2".repeat(32))
        val fileId = NextcloudDocumentIds.documentId(session, first, "Documents/report.pdf")
        val subfolderId = NextcloudDocumentIds.documentId(session, first, "Documents/Private")

        assertFailsWith<IllegalArgumentException> {
            NextcloudDocumentIds.requireForSession(fileId, session, replacement)
        }
        assertFailsWith<IllegalArgumentException> {
            NextcloudDocumentIds.requireForSession(subfolderId, session, replacement)
        }
        assertEquals(
            "Documents/report.pdf",
            NextcloudDocumentIds.requireForSession(
                NextcloudDocumentIds.documentId(session, replacement, "Documents/report.pdf"),
                session,
                replacement,
            ).path,
        )
    }

    @Test
    fun versionedRootIdentityChangesWithTheAccountIncarnation() {
        val first = NextcloudDocumentIncarnation.Versioned("1".repeat(32))
        val replacement = NextcloudDocumentIncarnation.Versioned("2".repeat(32))

        assertNotEquals(
            NextcloudDocumentIds.rootId(session, first),
            NextcloudDocumentIds.rootId(session, replacement),
        )
        assertNotEquals(
            NextcloudDocumentIds.providerRootId(session, first),
            NextcloudDocumentIds.providerRootId(session, replacement),
        )
    }

    @Test
    fun resolvesParentPathsCanonically() {
        assertEquals("Documents/Reports", NextcloudDocumentIds.parentPath("Documents/Reports/2026.pdf"))
        assertEquals("", NextcloudDocumentIds.parentPath("top-level.txt"))
    }
}
