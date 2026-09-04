package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `account removal rejects retained document writebacks`() {
        requireAndroidAccountRemovalWritebacksResolved(resolved = true)

        val failure = assertFailsWith<IllegalStateException> {
            requireAndroidAccountRemovalWritebacksResolved(resolved = false)
        }

        assertTrue(failure.message.orEmpty().contains("pending document changes"))
    }

    @Test
    fun `document grant revocation covers reads writes and descendants`() {
        assertTrue(NEXTCLOUD_DOCUMENTS_URI_GRANT_FLAGS and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(NEXTCLOUD_DOCUMENTS_URI_GRANT_FLAGS and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(NEXTCLOUD_DOCUMENTS_URI_GRANT_FLAGS and android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION != 0)
    }
}
