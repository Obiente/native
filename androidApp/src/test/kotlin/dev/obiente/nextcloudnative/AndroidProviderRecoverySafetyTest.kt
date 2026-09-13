package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFileListing
import dev.obiente.nextcloudnative.app.NextcloudFileListingSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AndroidProviderRecoverySafetyTest {
    @Test
    fun recoveryNeverTreatsCachedAbsenceAsAuthoritative() = runBlocking {
        val cached = NextcloudFileListing(emptyList(), NextcloudFileListingSource.Cache)
        assertFailsWith<IllegalStateException> {
            loadAndroidProviderChildren(true, { cached.filesForProviderRecovery(true) },
                { error("Recovery must not use offline entries") }, { error("Recovery must not use stored directories") })
        }
        assertEquals(emptyList(), loadAndroidProviderChildren(false, { error("synthetic offline") }, { emptyList() }, { true }))
        assertEquals(emptyList(), NextcloudFileListing(emptyList(), NextcloudFileListingSource.Network).filesForProviderRecovery(true))
    }

    @Test
    fun crossProfileRecoveryUsesTheVerifiedLocalProviderAuthority() {
        val authority = "dev.example.documents"
        assertEquals(authority, androidLocalRecoveryAuthority("10@$authority", authority))
        assertEquals(authority, androidLocalRecoveryAuthority(authority, authority))
        listOf("10@other.documents", "x@$authority", "10@20@$authority").forEach {
            assertFailsWith<IllegalArgumentException> { androidLocalRecoveryAuthority(it, authority) }
        }
    }

    @Test
    fun aLateRangeRegistrationRemainsVisibleToFinalQuiesce() = runBlocking {
        val coordinator = AndroidFileRangeSessionCoordinator()
        val old = AndroidFileRangeSessionActivity()
        val cancelled = CompletableDeferred<Unit>()
        val finish = checkNotNull(old.start { cancelled.complete(Unit) })
        coordinator.register("synthetic-account", old, old::close)
        val firstQuiesce = async { coordinator.quiesce("synthetic-account") }
        cancelled.await()
        val late = AndroidFileRangeSessionActivity()
        coordinator.register("synthetic-account", late, late::close)
        finish()
        firstQuiesce.await()
        coordinator.quiesce("synthetic-account")
        assertNull(late.start())
    }
}
