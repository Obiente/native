package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFileListingHttpException
import kotlin.test.Test
import kotlin.test.assertEquals

class NextcloudFileListingFailureTest {
    @Test
    fun `android folder listing failures expose a typed status to shared UI`() {
        assertEquals(404, NextcloudFileListingHttpException(404).status)
    }
}
