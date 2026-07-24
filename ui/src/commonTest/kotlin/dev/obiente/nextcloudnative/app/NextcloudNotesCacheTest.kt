package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextcloudNotesCacheTest {
    @Test
    fun isolatesAccountsWithoutUsingTheAppPasswordAsIdentity() {
        val cache = NextcloudNotesCache()
        val firstLogin = session("alice", "first password")
        val sameLoginNewPassword = session("alice", "rotated password")
        val otherLogin = session("bob", "first password")
        cache.storeList(firstLogin, listOf(note(1, "Alice note")))

        assertEquals("Alice note", cache.list(sameLoginNewPassword)?.single()?.title)
        assertNull(cache.list(otherLogin))
    }

    @Test
    fun savedDetailUpdatesListMetadataButKeepsFullContentSeparate() {
        val cache = NextcloudNotesCache()
        val session = session("alice", "password")
        cache.storeList(session, listOf(note(1, "Before")))

        cache.storeDetail(session, note(1, "After", content = "# Full content"))

        assertEquals("After", cache.list(session)?.single()?.title)
        assertNull(cache.list(session)?.single()?.content)
        assertEquals("# Full content", cache.detail(session, 1)?.content)
    }

    @Test
    fun listEtagTracksTheMetadataPayloadWithoutEnteringDetailCache() {
        val cache = NextcloudNotesCache()
        val session = session("alice", "password")

        cache.storeList(session, listOf(note(1, "Metadata only")), etag = "\"list-v1\"")

        assertEquals("\"list-v1\"", cache.listEtag(session))
        assertNull(cache.detail(session, 1))
        cache.storeList(session, listOf(note(1, "Changed metadata")), etag = null)
        assertNull(cache.listEtag(session))
    }

    private fun session(login: String, password: String) = NextcloudSession(
        serverUrl = "https://cloud.example.test/",
        loginName = login,
        appPassword = password,
    )

    private fun note(id: Long, title: String, content: String? = null) = NextcloudNote(
        id = id,
        title = title,
        modified = 1,
        category = "Personal",
        favorite = false,
        readOnly = false,
        content = content,
        etag = "etag-$id",
    )
}
