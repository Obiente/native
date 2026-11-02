package dev.obiente.nextcloudnative.nativeui.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Technique A, with synthetic feed items and board cards only. */
class NativeObservedIdentityBindingTest {
    private val collection = NativeIdentityRoute("/apps/reader/api/items")
    private val command = NativeIdentityRoute("/apps/reader/api/items/{itemId}/read", mapOf("itemId" to "integer"))

    @Test
    fun `an id from the current collection load binds a command on the same family`() {
        val proof = assertIs<NativeObservedIdentityProof.Bound>(bind())
        assertEquals(mapOf("itemId" to "41"), proof.values)
        assertEquals(NativeIdentityRoundTrip.ReadBackByIdentity, proof.roundTrip, "Without an item read the read-back must find the id.")
    }

    @Test
    fun `an item read of the same family requires a round trip before the first write`() {
        val item = NativeIdentityRoute("/apps/reader/api/items/{id}")
        val proof = assertIs<NativeObservedIdentityProof.Bound>(bind(itemRead = item))
        assertEquals(NativeIdentityRoundTrip.ItemRead("/apps/reader/api/items/{id}"), proof.roundTrip)
        assertTrue(confirmsObservedIdentity("41", listOf(item(41))))
        assertTrue(!confirmsObservedIdentity("41", listOf(item(42))), "The item read must return the same identity.")
        assertTrue(!confirmsObservedIdentity("41", listOf(item(41), item(41))))
    }

    @Test
    fun `ids colliding in one page are refused`() {
        assertRefused(NativeIdentityBindingRefusal.AmbiguousIdentity, page = listOf(item(41), item(41)))
    }

    @Test
    fun `a uid beside a different id is refused and a uid placeholder is not an identity name`() {
        val withUid = item(41).let { it.copy(values = it.values + ("itemId" to "9001")) }
        assertRefused(NativeIdentityBindingRefusal.AmbiguousIdentity, record = withUid, page = listOf(withUid))
        val uidRoute = NativeIdentityRoute("/apps/reader/api/items/{uid}/read")
        assertRefused(NativeIdentityBindingRefusal.PlaceholderNameMismatch, write = uidRoute)
    }

    @Test
    fun `a non-digit value for an integer placeholder is refused`() {
        val text = item("abc")
        assertRefused(NativeIdentityBindingRefusal.PlaceholderTypeMismatch, record = text, page = listOf(text))
        val stringRoute = NativeIdentityRoute(command.path, mapOf("itemId" to "string"))
        assertIs<NativeObservedIdentityProof.Bound>(bind(write = stringRoute, record = text, page = listOf(text)))
    }

    @Test
    fun `ids with slashes or control characters are refused`() {
        listOf("41/../42", "41\u0000", "4 1").forEach { unsafe ->
            val record = item(unsafe)
            val route = NativeIdentityRoute(command.path, mapOf("itemId" to "string"))
            assertRefused(NativeIdentityBindingRefusal.UnsafeValue, write = route, record = record, page = listOf(record))
        }
    }

    @Test
    fun `a stale or cached load generation is refused`() {
        assertRefused(NativeIdentityBindingRefusal.StaleGeneration, recordGeneration = 3, currentGeneration = 4)
        assertRefused(NativeIdentityBindingRefusal.StaleGeneration, recordGeneration = 0, currentGeneration = 0)
    }

    @Test
    fun `a placeholder named for another resource is refused`() {
        val cards = NativeIdentityRoute("/apps/board/api/boards/{boardId}/cards")
        val wrongName = NativeIdentityRoute("/apps/board/api/boards/{boardId}/cards/{boardId2}")
        val card = item(5)
        assertEquals(
            NativeObservedIdentityProof.Refused(NativeIdentityBindingRefusal.PlaceholderNameMismatch),
            bindObservedIdentity(wrongName, cards, null, card, listOf(card), 1, 1, mapOf("boardId" to "2")),
        )
        val boardIdForCard = NativeIdentityRoute("/apps/board/api/cards/{boardId}")
        assertEquals(
            NativeObservedIdentityProof.Refused(NativeIdentityBindingRefusal.PlaceholderNameMismatch),
            bindObservedIdentity(boardIdForCard, NativeIdentityRoute("/apps/board/api/cards"), null, card, listOf(card), 1, 1, emptyMap()),
        )
    }

    @Test
    fun `parents bind by route position from navigation, never from the record body`() {
        val cards = NativeIdentityRoute("/apps/board/api/boards/{boardId}/cards")
        val write = NativeIdentityRoute("/apps/board/api/boards/{bid}/cards/{cardId}/archive")
        val card = item(5).let { it.copy(values = it.values + ("boardId" to "2")) }
        assertEquals(
            NativeObservedIdentityProof.Refused(NativeIdentityBindingRefusal.ParentUnbound),
            bindObservedIdentity(write, cards, null, card, listOf(card), 1, 1, emptyMap()),
            "A parent present only in the record body does not bind.",
        )
        val proof = assertIs<NativeObservedIdentityProof.Bound>(
            bindObservedIdentity(write, cards, null, card, listOf(card), 1, 1, mapOf("boardId" to "2")),
        )
        assertEquals(mapOf("bid" to "2", "cardId" to "5"), proof.values, "Different placeholder names bind by position.")
    }

    @Test
    fun `a write outside the route family is refused`() {
        val other = NativeIdentityRoute("/apps/reader/api/feeds/{feedId}/read")
        assertRefused(NativeIdentityBindingRefusal.DifferentRouteFamily, write = other)
        val multiple = NativeIdentityRoute("/apps/reader/api/items/read/multiple")
        assertRefused(NativeIdentityBindingRefusal.DifferentRouteFamily, write = multiple)
    }

    private fun assertRefused(
        reason: NativeIdentityBindingRefusal,
        write: NativeIdentityRoute = command,
        record: NativeRecord = item(41),
        page: List<NativeRecord> = listOf(item(40), record, item(42)),
        recordGeneration: Long = 7,
        currentGeneration: Long = 7,
    ) = assertEquals(
        NativeObservedIdentityProof.Refused(reason),
        bind(write = write, record = record, page = page, recordGeneration = recordGeneration, currentGeneration = currentGeneration),
    )

    private fun bind(
        write: NativeIdentityRoute = command,
        itemRead: NativeIdentityRoute? = null,
        record: NativeRecord = item(41),
        page: List<NativeRecord> = listOf(item(40), record, item(42)),
        recordGeneration: Long = 7,
        currentGeneration: Long = 7,
    ) = bindObservedIdentity(write, collection, itemRead, record, page, recordGeneration, currentGeneration, emptyMap())

    private fun item(id: Int) = item(id.toString())

    private fun item(id: String) = NativeRecord(id, mapOf("id" to id, "title" to "Synthetic item"), actionSafeIdentity = false)
}
