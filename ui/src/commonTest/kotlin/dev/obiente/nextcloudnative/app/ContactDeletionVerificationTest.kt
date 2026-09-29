package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class ContactDeletionVerificationTest {
    private val href = "/remote.php/dav/addressbooks/users/alice/contacts/synthetic.vcf"
    @Test fun onlyAuthoritativeAbsenceConfirmsDeletion() = runBlocking {
        for (status in listOf(404, 410)) {
            assertTrue(verifyContactDeletion(href) { request ->
                assertEquals("GET", request.method)
                assertEquals(href, request.relativePath)
                response(status)
            })
        }
        for (status in listOf(200, 204, 401, 403, 500)) {
            assertFalse(verifyContactDeletion(href) { response(status) })
        }
    }
    @Test fun failedOrCancelledVerificationCannotConfirmDeletion(): Unit = runBlocking {
        assertFalse(verifyContactDeletion(href) { throw IllegalStateException("Synthetic transport failure") })
        assertFailsWith<CancellationException> {
            verifyContactDeletion(href) { throw CancellationException("Synthetic cancellation") }
        }
    }
    private fun response(status: Int) = NextcloudApiResponse(status, byteArrayOf(), null, null)
}
