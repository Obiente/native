package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
        cache.storeList(firstLogin, "notes", listOf(note(1, "Alice note")), requireNotNull(cache.producer(firstLogin)))

        assertEquals("Alice note", cache.list(sameLoginNewPassword, "notes")?.single()?.title)
        assertNull(cache.list(otherLogin, "notes"))
    }

    @Test
    fun twoNotesAppsOnOneAccountNeverShareCachedNotes() {
        val cache = NextcloudNotesCache()
        val session = session("alice", "password")
        val producer = requireNotNull(cache.producer(session))
        cache.storeDetail(session, "jotter", note(1, "Jotter note", content = "a"), producer)

        assertEquals("Jotter note", cache.detail(session, "jotter", 1)?.title)
        assertNull(cache.detail(session, "scribble", 1))
        assertNull(cache.list(session, "scribble"))
    }

    @Test
    fun savedDetailUpdatesListMetadataButKeepsFullContentSeparate() {
        val cache = NextcloudNotesCache()
        val session = session("alice", "password")
        val producer = requireNotNull(cache.producer(session))
        cache.storeList(session, "notes", listOf(note(1, "Before")), producer)

        cache.storeDetail(session, "notes", note(1, "After", content = "# Full content"), producer)

        assertEquals("After", cache.list(session, "notes")?.single()?.title)
        assertNull(cache.list(session, "notes")?.single()?.content)
        assertEquals("# Full content", cache.detail(session, "notes", 1)?.content)
    }

    @Test
    fun listEtagTracksTheMetadataPayloadWithoutEnteringDetailCache() {
        val cache = NextcloudNotesCache()
        val session = session("alice", "password")
        val producer = requireNotNull(cache.producer(session))

        cache.storeList(session, "notes", listOf(note(1, "Metadata only")), producer, etag = "\"list-v1\"")

        assertEquals("\"list-v1\"", cache.listEtag(session, "notes"))
        assertNull(cache.detail(session, "notes", 1))
        cache.storeList(session, "notes", listOf(note(1, "Changed metadata")), producer, etag = null)
        assertNull(cache.listEtag(session, "notes"))
    }

    @Test
    fun `retirement purges target note data and preserves another account`() {
        val cache = NextcloudNotesCache()
        val target = session("alice", "password")
        val other = session("bob", "password")
        val targetProducer = requireNotNull(cache.producer(target))
        val otherProducer = requireNotNull(cache.producer(other))
        cache.storeList(target, "notes", listOf(note(1, "Target", content = "private")), targetProducer, "target-etag")
        cache.storeDetail(target, "notes", note(1, "Target", content = "private"), targetProducer)
        cache.storeList(other, "notes", listOf(note(2, "Other", content = "retained")), otherProducer, "other-etag")
        cache.storeDetail(other, "notes", note(2, "Other", content = "retained"), otherProducer)

        cache.retireAccount(target.accountId.storageKey)

        assertNull(cache.list(target, "notes"))
        assertNull(cache.listEtag(target, "notes"))
        assertNull(cache.detail(target, "notes", 1L))
        assertEquals("Other", cache.list(other, "notes")?.single()?.title)
        assertEquals("other-etag", cache.listEtag(other, "notes"))
        assertEquals("retained", cache.detail(other, "notes", 2L)?.content)
    }

    @Test
    fun `stale note producer cannot write or remove after reactivation`() {
        val cache = NextcloudNotesCache()
        val session = session("alice", "password")
        val staleProducer = requireNotNull(cache.producer(session))
        cache.storeList(session, "notes", listOf(note(1, "Before")), staleProducer, "before-etag")

        cache.retireAccount(session.accountId.storageKey)
        cache.storeList(session, "notes", listOf(note(1, "Closed")), staleProducer, "closed-etag")
        assertNull(cache.list(session, "notes"))
        cache.activateAccount(session.accountId.storageKey)

        val currentProducer = requireNotNull(cache.producer(session))
        cache.storeList(session, "notes", listOf(note(1, "Current")), currentProducer, "current-etag")
        cache.storeDetail(session, "notes", note(1, "Current", content = "current body"), currentProducer)
        cache.storeList(session, "notes", listOf(note(1, "Late")), staleProducer, "late-etag")
        cache.storeDetail(session, "notes", note(1, "Late", content = "late body"), staleProducer)
        cache.remove(session, "notes", 1L, staleProducer)

        assertEquals("Current", cache.list(session, "notes")?.single()?.title)
        assertEquals("current-etag", cache.listEtag(session, "notes"))
        assertEquals("current body", cache.detail(session, "notes", 1L)?.content)
    }

    @Test
    fun `same screen can cache a new request after a crossing completion is rejected`() = runBlocking {
        val cache = NextcloudNotesCache()
        val session = session("reactivated", "password")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staleProducer = requireNotNull(cache.producer(session))
        val crossingRequest = async(start = CoroutineStart.UNDISPATCHED) {
            started.complete(Unit)
            release.await()
            cache.storeDetail(session, "notes", note(1, "Crossing", content = "stale"), staleProducer)
        }
        started.await()

        cache.retireAccount(session.accountId.storageKey)
        cache.activateAccount(session.accountId.storageKey)
        release.complete(Unit)
        crossingRequest.await()
        assertNull(cache.detail(session, "notes", 1L))

        val retryProducer = requireNotNull(cache.producer(session))
        cache.storeDetail(session, "notes", note(1, "Retried", content = "current"), retryProducer)

        assertEquals("current", cache.detail(session, "notes", 1L)?.content)
    }

    @Test
    fun `concurrent note access remains safe across retirement`() = runBlocking {
        val cache = NextcloudNotesCache()
        val session = session("parallel", "password")
        val producer = requireNotNull(cache.producer(session))
        val workers = List(8) { worker ->
            async(Dispatchers.Default) {
                repeat(100) { iteration ->
                    val id = (worker * 100 + iteration).toLong()
                    cache.storeDetail(session, "notes", note(id, "Note $id", content = "body"), producer)
                    cache.detail(session, "notes", id)
                    cache.remove(session, "notes", id, producer)
                }
            }
        }
        val retirement = async(Dispatchers.Default) { cache.retireAccount(session.accountId.storageKey) }
        (workers + retirement).awaitAll()

        assertNull(cache.list(session, "notes"))
        assertNull(cache.listEtag(session, "notes"))
        repeat(800) { id -> assertNull(cache.detail(session, "notes", id.toLong())) }
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
