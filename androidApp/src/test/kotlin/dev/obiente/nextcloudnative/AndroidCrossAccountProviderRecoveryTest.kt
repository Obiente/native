package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AndroidCrossAccountProviderRecoveryTest {
    private val session = NextcloudSession("https://cloud.example.test", "reader", "synthetic")
    private val root = NextcloudDocumentIds.rootId(session)

    @Test fun busyRootAccountFailsWithoutWaitingOrRunningRecovery(): Unit = runBlocking {
        val guard = AndroidAccountOperationGuard()
        var ran = false
        guard.withAccount(NextcloudDocumentIds.accountKey(session)) {
            assertFailsWith<IllegalStateException> {
                withAndroidCrossAccountProviderRecovery(root, true, { session }, guard) { ran = true }
            }
        }
        assertFalse(ran)
        assertEquals(session, withAndroidCrossAccountProviderRecovery(root, true, { session }, guard) { it })
    }

    @Test fun missingMismatchedAndCrossProfileAccountsCannotAuthorizeRecovery() {
        assertFailsWith<IllegalStateException> { withAndroidCrossAccountProviderRecovery(root, false, { session }) { kotlin.test.fail("Unexpected recovery") } }
        assertFailsWith<IllegalStateException> { withAndroidCrossAccountProviderRecovery(root, true, { null }) { kotlin.test.fail("Unexpected recovery") } }
        assertFailsWith<IllegalStateException> {
            withAndroidCrossAccountProviderRecovery(root, true, { session.copy(loginName = "other") }) { kotlin.test.fail("Unexpected recovery") }
        }
        var reads = 0
        assertFailsWith<IllegalStateException> {
            withAndroidCrossAccountProviderRecovery(root, true, { if (reads++ == 0) session else session.copy(appPassword = "new") }) {
                kotlin.test.fail("Unexpected recovery")
            }
        }
    }

    @Test fun cancellationReleasesTheRootAccountLease() {
        val guard = AndroidAccountOperationGuard()
        assertFailsWith<CancellationException> {
            withAndroidCrossAccountProviderRecovery(root, true, { session }, guard) { throw CancellationException("synthetic") }
        }
        assertEquals(session, withAndroidCrossAccountProviderRecovery(root, true, { session }, guard) { it })
    }
}
