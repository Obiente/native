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
        cache.storeList(firstLogin, listOf(note(1, "Alice note")), requireNotNull(cache.producer(firstLogin)))

        assertEquals("Alice note", cache.list(sameLoginNewPassword)?.single()?.title)
        assertNull(cache.list(otherLogin))
    }

    @Test
    fun savedDetailUpdatesListMetadataButKeepsFullContentSeparate() {
        val cache = NextcloudNotesCache()
        val session = session("alice", "password")
        val producer = requireNotNull(cache.producer(session))
        cache.storeList(session, listOf(note(1, "Before")), producer)

        cache.storeDetail(session, note(1, "After", content = "# Full content"), producer)

        assertEquals("After", cache.list(session)?.single()?.title)
        assertNull(cache.list(session)?.single()?.content)
        assertEquals("# Full content", cache.detail(session, 1)?.content)
    }

    @Test
    fun listEtagTracksTheMetadataPayloadWithoutEnteringDetailCache() {
        val cache = NextcloudNotesCache()
        val session = session("alice", "password")
        val producer = requireNotNull(cache.producer(session))

        cache.storeList(session, listOf(note(1, "Metadata only")), producer, etag = "\"list-v1\"")

        assertEquals("\"list-v1\"", cache.listEtag(session))
        assertNull(cache.detail(session, 1))
        cache.storeList(session, listOf(note(1, "Changed metadata")), producer, etag = null)
        assertNull(cache.listEtag(session))
    }

    @Test
    fun `retirement purges target note data and preserves another account`() {
        val cache = NextcloudNotesCache()
        val target = session("alice", "password")
        val other = session("bob", "password")
        val targetProducer = requireNotNull(cache.producer(target))
        val otherProducer = requireNotNull(cache.producer(other))
        cache.storeList(target, listOf(note(1, "Target", content = "private")), targetProducer, "target-etag")
        cache.storeDetail(target, note(1, "Target", content = "private"), targetProducer)
        cache.storeList(other, listOf(note(2, "Other", content = "retained")), otherProducer, "other-etag")
        cache.storeDetail(other, note(2, "Other", content = "retained"), otherProducer)

        cache.retireAccount(target.accountId.storageKey)

        assertNull(cache.list(target))
        assertNull(cache.listEtag(target))
        assertNull(cache.detail(target, 1L))
        assertEquals("Other", cache.list(other)?.single()?.title)
        assertEquals("other-etag", cache.listEtag(other))
        assertEquals("retained", cache.detail(other, 2L)?.content)
    }

    @Test
    fun `stale note producer cannot write or remove after reactivation`() {
        val cache = NextcloudNotesCache()
        val session = session("alice", "password")
        val staleProducer = requireNotNull(cache.producer(session))
        cache.storeList(session, listOf(note(1, "Before")), staleProducer, "before-etag")

        cache.retireAccount(session.accountId.storageKey)
        cache.storeList(session, listOf(note(1, "Closed")), staleProducer, "closed-etag")
        assertNull(cache.list(session))
        cache.activateAccount(session.accountId.storageKey)

        val currentProducer = requireNotNull(cache.producer(session))
        cache.storeList(session, listOf(note(1, "Current")), currentProducer, "current-etag")
        cache.storeDetail(session, note(1, "Current", content = "current body"), currentProducer)
        cache.storeList(session, listOf(note(1, "Late")), staleProducer, "late-etag")
        cache.storeDetail(session, note(1, "Late", content = "late body"), staleProducer)
        cache.remove(session, 1L, staleProducer)

        assertEquals("Current", cache.list(session)?.single()?.title)
        assertEquals("current-etag", cache.listEtag(session))
        assertEquals("current body", cache.detail(session, 1L)?.content)
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
            cache.storeDetail(session, note(1, "Crossing", content = "stale"), staleProducer)
        }
        started.await()

        cache.retireAccount(session.accountId.storageKey)
        cache.activateAccount(session.accountId.storageKey)
        release.complete(Unit)
        crossingRequest.await()
        assertNull(cache.detail(session, 1L))

        val retryProducer = requireNotNull(cache.producer(session))
        cache.storeDetail(session, note(1, "Retried", content = "current"), retryProducer)

        assertEquals("current", cache.detail(session, 1L)?.content)
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
                    cache.storeDetail(session, note(id, "Note $id", content = "body"), producer)
                    cache.detail(session, id)
                    cache.remove(session, id, producer)
                }
            }
        }
        val retirement = async(Dispatchers.Default) { cache.retireAccount(session.accountId.storageKey) }
        (workers + retirement).awaitAll()

        assertNull(cache.list(session))
        assertNull(cache.listEtag(session))
        repeat(800) { id -> assertNull(cache.detail(session, id.toLong())) }
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
