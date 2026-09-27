package dev.obiente.nextcloudnative

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidUploadPeerEvidenceTest {
    @Test
    fun oldUnknownPeerDoesNotConsumeCapacityForEachNewUnownedGrant() {
        val peer = DurableUploadPermissionPeer<String>("unknown", null, null)
        val proof = readPriorUnownedUploadPeerDigests(JSONObject().put("priorUnownedPeerDigests", JSONArray(listOf(uploadPeerEvidenceKey("unknown")))))
        repeat(128) { index ->
            assertEquals(DurableUploadMalformedPeerCleanupDisposition.Proceed,
                durableUploadMalformedPeerCleanupDisposition(listOf(peer), "selection-$index", "content://synthetic/$index",
                    String::equals, priorUnownedPeerDigests = proof))
        }
        assertEquals(DurableUploadMalformedPeerCleanupDisposition.Quarantine,
            durableUploadMalformedPeerCleanupDisposition(listOf(peer), "new", "content://synthetic/source", String::equals))
        assertEquals(DurableUploadMalformedPeerCleanupDisposition.Quarantine,
            durableUploadMalformedPeerCleanupDisposition(listOf(peer.copy(selectionId = "later")), "new", "content://synthetic/source",
                String::equals, priorUnownedPeerDigests = proof))
    }

    @Test
    fun malformedOrOversizedPeerEvidenceCannotAuthorizeCleanup() {
        assertEquals(emptySet(), readPriorUnownedUploadPeerDigests(JSONObject()))
        listOf(JSONArray(listOf("invalid")), JSONArray(List(65) { "a".repeat(64) })).forEach { entries ->
            assertFailsWith<IllegalArgumentException> {
                readPriorUnownedUploadPeerDigests(JSONObject().put("priorUnownedPeerDigests", entries))
            }
        }
    }
}
