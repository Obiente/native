package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.mediaSearchDavRequestBody
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidMediaSearchDavTest {
    @Test
    fun androidMediaSearchExcludesCollectionsBeforeApplyingTheServerLimit() {
        val body = mediaSearchDavRequestBody("account")
        val collectionExclusion = "<d:not><d:is-collection/></d:not>"

        assertTrue(collectionExclusion in body)
        assertTrue(body.indexOf(collectionExclusion) < body.indexOf("<d:limit>"))
    }
}
