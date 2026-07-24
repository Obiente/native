package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class NextcloudFileListingFailureTest {
    @Test
    fun `desktop folder listing failures expose a typed status to shared UI`() {
        assertEquals(404, NextcloudFileListingHttpException(404).status)
    }
}
