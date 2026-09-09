package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFileListingHttpException
import dev.obiente.nextcloudnative.app.NextcloudFileSearchHttpException
import kotlin.test.Test
import kotlin.test.assertEquals

class NextcloudFileListingFailureTest {
    @Test
    fun `android folder listing failures expose a typed status to shared UI`() {
        assertEquals(404, NextcloudFileListingHttpException(404).status)
    }

    @Test
    fun `android file search failures are distinct from folder listing failures`() {
        val failure = NextcloudFileSearchHttpException(500)

        assertEquals(500, failure.status)
        assertEquals("File search failed (HTTP 500).", failure.message)
    }
}
