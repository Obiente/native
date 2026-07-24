package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NextcloudDocumentIdsTest {
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
        val root = NextcloudDocumentIds.rootId(session)
        assertEquals("", NextcloudDocumentIds.requireForSession(root, session).path)

        val id = NextcloudDocumentIds.documentId(session, "/Photos/July & August/旅行.jpg/")
        assertEquals(
            "Photos/July & August/旅行.jpg",
            NextcloudDocumentIds.requireForSession(id, session).path,
        )
        assertFalse(id.contains("cloud.example"))
        assertFalse(id.contains("alice"))
        assertFalse(id.contains("not-used"))
    }

    @Test
    fun documentIdsAreStableAcrossCredentialRotation() {
        val rotated = session.copy(appPassword = "new-app-password")
        assertEquals(
            NextcloudDocumentIds.documentId(session, "Documents/report.pdf"),
            NextcloudDocumentIds.documentId(rotated, "Documents/report.pdf"),
        )
    }

    @Test
    fun accountIdentitySeparatesOtherwiseEqualPaths() {
        val other = session.copy(loginName = "bob")
        val aliceId = NextcloudDocumentIds.documentId(session, "Documents/report.pdf")
        val bobId = NextcloudDocumentIds.documentId(other, "Documents/report.pdf")
        assertNotEquals(aliceId, bobId)
        assertFailsWith<IllegalArgumentException> {
            NextcloudDocumentIds.requireForSession(aliceId, other)
        }
    }

    @Test
    fun rejectsTraversalAndMalformedIds() {
        assertFailsWith<IllegalArgumentException> {
            NextcloudDocumentIds.documentId(session, "Documents/../secrets.txt")
        }
        assertFailsWith<IllegalArgumentException> { NextcloudDocumentIds.parse("not-a-nextcloud-document") }
        val rootWithNonCanonicalPadding = NextcloudDocumentIds.rootId(session) + "=="
        assertFailsWith<IllegalArgumentException> { NextcloudDocumentIds.parse(rootWithNonCanonicalPadding) }
    }

    @Test
    fun resolvesParentPathsCanonically() {
        assertEquals("Documents/Reports", NextcloudDocumentIds.parentPath("Documents/Reports/2026.pdf"))
        assertEquals("", NextcloudDocumentIds.parentPath("top-level.txt"))
    }
}
