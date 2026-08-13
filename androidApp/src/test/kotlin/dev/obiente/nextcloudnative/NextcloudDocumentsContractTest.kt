package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NextcloudDocumentsContractTest {
    @Test
    fun `documents authority follows the runtime application id`() {
        assertEquals(
            "dev.obiente.nextcloudnative.documents",
            nextcloudDocumentsAuthority("dev.obiente.nextcloudnative"),
        )
        assertEquals(
            "dev.obiente.nextcloudnative.dev.documents",
            nextcloudDocumentsAuthority("dev.obiente.nextcloudnative.dev"),
        )
    }

    @Test
    fun `documents authority rejects a missing application id`() {
        assertFailsWith<IllegalArgumentException> { nextcloudDocumentsAuthority(" ") }
    }
}
