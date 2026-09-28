package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class AdministrationAccessRepositoryTest {
    @Test
    fun unknownAndOtherAccountsNeverInheritAccess(): Unit = runBlocking {
        val admin = AdministrationAccessRepository()
        val regular = AdministrationAccessRepository()
        assertFalse(admin.state.value.canAdminister)
        admin.refresh { allowedResponse() }
        assertTrue(admin.state.value.canAdminister)
        assertFalse(regular.state.value.canAdminister)
        regular.refresh { response(status = 403) }
        assertFalse(regular.state.value.canAdminister)
        assertTrue(admin.state.value.canAdminister)
    }

    @Test
    fun successfulCatalogIsReusedUntilItsExactExpiry(): Unit = runBlocking {
        val clock = TestTimeSource()
        val repository = AdministrationAccessRepository(clock)
        var calls = 0
        val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse = { request ->
            assertEquals(NextcloudApiMethod.GET, request.method)
            assertEquals(NextcloudApiCachePolicy.ForceNetwork, request.cachePolicy)
            calls++
            allowedResponse()
        }
        repository.refresh(execute = execute)
        repeat(3) { repository.refresh(execute = execute) }
        assertEquals(1, calls)
        clock += 5.minutes
        assertFalse(repository.state.value.canAdminister)
        repository.refresh(execute = execute)
        assertEquals(2, calls)
        assertTrue(repository.state.value.canAdminister)
    }

    @Test
    fun legacyPermissionEvidenceAlsoBypassesTransportCaches(): Unit = runBlocking {
        val repository = AdministrationAccessRepository()
        var calls = 0
        repository.refresh { request ->
            assertEquals(NextcloudApiMethod.GET, request.method)
            assertEquals(NextcloudApiCachePolicy.ForceNetwork, request.cachePolicy)
            calls++
            if (calls == 1) response(status = 404) else response(
                body = """{"ocs":{"meta":{"status":"ok","statuscode":100},"data":{"apps":[]}}}""",
            )
        }
        assertEquals(3, calls)
        assertTrue(repository.state.value.canAdminister)
    }

    @Test
    fun deniedResultsAreCachedAndPromotionIsDiscoveredAfterExpiry(): Unit = runBlocking {
        val clock = TestTimeSource()
        val repository = AdministrationAccessRepository(clock)
        var calls = 0
        repeat(3) {
            repository.refresh { calls++; response(status = 403) }
        }
        assertEquals(1, calls)
        assertFalse(repository.state.value.canAdminister)
        clock += 5.minutes
        repository.refresh { allowedResponse() }
        assertTrue(repository.state.value.canAdminister)
    }

    @Test
    fun expiredOrExplicitlyRefreshedAccessIsHiddenBeforeNetworkCompletion(): Unit = runBlocking {
        for (force in listOf(false, true)) {
            val clock = TestTimeSource()
            val repository = AdministrationAccessRepository(clock)
            repository.refresh { allowedResponse() }
            if (!force) clock += 5.minutes
            val pending = CompletableDeferred<NextcloudApiResponse>()
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                repository.refresh(force) { pending.await() }
            }
            assertTrue(repository.state.value.checking)
            assertFalse(repository.state.value.canAdminister)
            pending.complete(response(status = 403))
            request.await()
            assertIs<NativeAppCatalogResult.Forbidden>(repository.state.value.result)
            assertFalse(repository.state.value.canAdminister)
        }
    }

    @Test
    fun concurrentChecksPerformOneRead(): Unit = runBlocking {
        val repository = AdministrationAccessRepository()
        val pending = CompletableDeferred<NextcloudApiResponse>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            repository.refresh { calls++; pending.await() }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            repository.refresh { calls++; allowedResponse() }
        }
        pending.complete(allowedResponse())
        first.await()
        second.await()
        assertEquals(1, calls)
        assertTrue(repository.state.value.canAdminister)
    }

    @Test
    fun cancellationCannotBecomeCachedSuccessEvenIfTransportSwallowsIt(): Unit = runBlocking {
        val repository = AdministrationAccessRepository()
        val pending = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.refresh {
                try {
                    pending.await()
                } catch (_: CancellationException) {
                    // Simulate an incorrectly cancellation-swallowing platform boundary.
                }
                allowedResponse()
            }
        }
        job.cancelAndJoin()
        assertFalse(repository.state.value.canAdminister)
        assertFalse(repository.state.value.checking)
        assertNull(repository.state.value.result)
        repository.refresh { response(status = 403) }
        assertIs<NativeAppCatalogResult.Forbidden>(repository.state.value.result)
    }

    @Test
    fun retiredAccountRejectsLateCompletionAndFurtherChecks(): Unit = runBlocking {
        val repository = AdministrationAccessRepository()
        val pending = CompletableDeferred<NextcloudApiResponse>()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            repository.refresh { pending.await() }
        }
        repository.retire()
        pending.complete(allowedResponse())
        request.await()
        repository.refresh { error("Retired accounts must not make requests") }
        assertTrue(repository.state.value.retired)
        assertFalse(repository.state.value.canAdminister)
        assertNull(repository.state.value.result)
    }

    @Test
    fun failedRefreshNeverRetainsEarlierPermission(): Unit = runBlocking {
        val repository = AdministrationAccessRepository()
        repository.refresh { allowedResponse() }
        repository.refresh(force = true) { error("Synthetic transport failure") }
        assertFalse(repository.state.value.canAdminister)
        assertIs<NativeAppCatalogResult.Unavailable>(repository.state.value.result)
    }

    @Test
    fun deniedUnavailableAndMalformedCatalogsCannotGrantAccess(): Unit = runBlocking {
        val responses = listOf(
            response(status = 401), response(status = 403), response(status = 503),
            response(body = "not json"), response(body = "{}"),
            response(body = """{"ocs":{"meta":{},"data":[]}}"""),
            response(body = """{"ocs":{"meta":{"status":"failure","statuscode":403},"data":[]}}"""),
            response(body = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":{}}}"""),
            allowedResponse().copy(contentType = "text/html"),
        )
        for (response in responses) {
            val repository = AdministrationAccessRepository()
            repository.refresh { response }
            assertFalse(repository.state.value.canAdminister)
        }
    }

    private fun allowedResponse() = response(
        body = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[]}}""",
    )

    private fun response(status: Int = 200, body: String = "{}") = NextcloudApiResponse(
        status = status,
        body = body.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )
}
