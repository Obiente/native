package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.MediaSearchDavPartition
import dev.obiente.nextcloudnative.app.mediaSearchDavRequests
import dev.obiente.nextcloudnative.app.rawPhotoFileNameSearchPatterns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidMediaSearchDavTest {
    @Test
    fun androidMediaSearchKeepsMimeAndRawPartitionsCompatibleAndDirectorySafe() {
        val requests = mediaSearchDavRequests("account")
        val collectionExclusion = "<d:not><d:is-collection/></d:not>"

        assertTrue(requests[0].partition == MediaSearchDavPartition.ImageMime)
        assertTrue(requests[1].partition == MediaSearchDavPartition.VideoMime)
        assertFalse(collectionExclusion in requests.first().body)
        assertFalse(collectionExclusion in requests[1].body)
        assertTrue(requests.take(2).all { request -> request.rawFileNamePatterns.isEmpty() })
        requests.drop(2).forEach { request ->
            assertTrue(request.partition == MediaSearchDavPartition.Raw)
            assertTrue(request.rawFileNamePatterns.isNotEmpty())
            assertTrue(collectionExclusion in request.body)
            assertTrue(request.body.indexOf(collectionExclusion) < request.body.indexOf("<d:limit>"))
        }
        assertEquals(
            rawPhotoFileNameSearchPatterns(),
            requests.drop(2).flatMap { request -> request.rawFileNamePatterns },
        )
    }
}
