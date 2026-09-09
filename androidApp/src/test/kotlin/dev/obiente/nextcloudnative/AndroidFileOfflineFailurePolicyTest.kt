package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidFileOfflineFailurePolicyTest {
    @Test
    fun `rejected redirect is a permanent offline download failure`() {
        val rejectedRedirect = DocumentWebDavException(
            DocumentWebDavError.RedirectRejected,
            307,
            "Unsafe redirect.",
        )

        assertFalse(rejectedRedirect.isRetryableOfflineDownloadFailure())
        assertTrue(
            DocumentWebDavException(DocumentWebDavError.Locked, 423, "Locked.")
                .isRetryableOfflineDownloadFailure(),
        )
        assertTrue(
            DocumentWebDavException(DocumentWebDavError.Throttled, 429, "Wait.")
                .isRetryableOfflineDownloadFailure(),
        )
        assertTrue(
            DocumentWebDavException(DocumentWebDavError.Server, 503, "Unavailable.")
                .isRetryableOfflineDownloadFailure(),
        )
    }
}
