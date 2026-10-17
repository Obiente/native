package dev.obiente.nextcloudnative.contracts

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class ContractPublicationFenceTest {
    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cache = MemoryVerifiedContractCache()
    private val verifications = AtomicInteger()
    private val firstEntered = CountDownLatch(1)
    private val releaseFirst = CountDownLatch(1)
    private var firstFails = false
    private var now = 50_000_000L

    @AfterTest
    fun shutDown() {
        releaseFirst.countDown()
        executor.shutdownNow()
    }

    @Test
    fun `generations publish in start order and invalidation fences running acquisitions`() {
        val fence = ContractPublicationFence()
        val older = fence.start(request)
        val newer = fence.start(request)
        assertTrue(fence.publishIfCurrent(request, newer) {})
        assertFalse(fence.publishIfCurrent(request, older) { error("A superseded result must not publish.") })

        val running = fence.start(request)
        fence.invalidate(request) {}
        assertFalse(fence.publishIfCurrent(request, running) { error("An invalidated result must not publish.") })
        assertTrue(fence.publishIfCurrent(request, fence.start(request)) {})
        // Requests are fenced independently.
        assertTrue(fence.publishIfCurrent(request.copy(installedAppVersion = "4.0.3"), 1) {})
    }

    @Test
    fun `a slower acquisition that started before a refresh cannot overwrite the refresh result`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            val stale = startBlockedAcquisition(server, acquirer)

            enqueueCatalogAndPackage(server)
            assertEquals(document(2), document(acquirer.acquireOutcome(request, AcquisitionCachePolicy.Refresh)))
            releaseFirst.countDown()

            // The superseded caller receives the newer contract, and the cache keeps it.
            assertEquals(document(2), document(stale.get(10, TimeUnit.SECONDS)))
            assertEquals(document(2), cache.load(request)?.document)
            val requests = server.requestCount
            assertEquals(document(2), document(acquirer.acquireOutcome(request)))
            assertEquals(requests, server.requestCount)
        }
    }

    @Test
    fun `the newer of two refreshes wins over each older acquisition`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            val stale = startBlockedAcquisition(server, acquirer)

            enqueueCatalogAndPackage(server)
            assertEquals(document(2), document(acquirer.acquireOutcome(request, AcquisitionCachePolicy.Refresh)))
            now += 61_000L
            enqueueCatalogAndPackage(server)
            assertEquals(document(3), document(acquirer.acquireOutcome(request, AcquisitionCachePolicy.Refresh)))
            releaseFirst.countDown()

            assertEquals(document(3), document(stale.get(10, TimeUnit.SECONDS)))
            assertEquals(document(3), cache.load(request)?.document)
        }
    }

    @Test
    fun `a cancelled older acquisition never holds back later publication`() {
        MockWebServer().use { server ->
            server.start()
            firstFails = true
            val acquirer = acquirer(server)
            val cancelled = startBlockedAcquisition(server, acquirer)

            // A refresh publishes while the older acquisition is still running.
            enqueueCatalogAndPackage(server)
            assertEquals(document(2), document(acquirer.acquireOutcome(request, AcquisitionCachePolicy.Refresh)))
            releaseFirst.countDown()
            assertIs<CancellationException>(assertFailsWith<Exception> { cancelled.get(10, TimeUnit.SECONDS) }.cause)

            now += 61_000L
            enqueueCatalogAndPackage(server)
            assertEquals(document(3), document(acquirer.acquireOutcome(request, AcquisitionCachePolicy.Refresh)))
            assertEquals(document(3), cache.load(request)?.document)
        }
    }

    @Test
    fun `an acquisition running during invalidation cannot bring the contract back`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            val running = startBlockedAcquisition(server, acquirer)

            assertEquals(VerifiedContractRemoval.Removed, acquirer.invalidateVerifiedContract(request))
            releaseFirst.countDown()
            running.get(10, TimeUnit.SECONDS)
            assertNull(cache.load(request))

            // The next automatic discovery acquires again; the catalog is still cached.
            server.enqueue(MockResponse(body = "package"))
            assertEquals(document(2), document(acquirer.acquireOutcome(request)))
            assertEquals(document(2), cache.load(request)?.document)
        }
    }

    /** Starts an automatic acquisition and waits until it is blocked verifying its package. */
    private fun startBlockedAcquisition(
        server: MockWebServer,
        acquirer: SignedAppStoreContractAcquirer,
    ): Future<ContractAcquisitionOutcome> {
        enqueueCatalogAndPackage(server)
        val acquisition = executor.submit<ContractAcquisitionOutcome> { acquirer.acquireOutcome(request) }
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS))
        return acquisition
    }

    private fun document(version: Int) = "{\"version\":$version}"

    private fun document(outcome: ContractAcquisitionOutcome): String =
        assertIs<ContractAcquisitionOutcome.Acquired>(outcome).contract.document

    private fun enqueueCatalogAndPackage(server: MockWebServer) {
        val download = server.url("downloads/cospend-4.0.2.tar.gz")
        server.enqueue(MockResponse(body = """[{"id":"cospend","certificate":"c","releases":[""" +
            """{"version":"4.0.2","download":"$download","signature":"c2lnbmF0dXJl",""" +
            """"signatureDigest":"sha512","isNightly":false}]}]"""))
        server.enqueue(MockResponse(body = "package"))
    }

    private fun acquirer(server: MockWebServer) = SignedAppStoreContractAcquirer(
        httpClient = OkHttpClient(),
        appStoreBaseUrl = server.url("api/v1").toString(),
        requireHttps = false,
        trustVerifier = object : AppPackageTrustVerifier {
            override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray): VerifiedPackageContract {
                val call = verifications.incrementAndGet()
                if (call == 1) {
                    firstEntered.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS)) { "The blocked verification was never released." }
                    if (firstFails) throw CancellationException("synthetic cancellation")
                }
                return VerifiedPackageContract(release.appId, release.version, "openapi.json", document(call))
            }
        },
        verifiedContractCache = cache,
        catalogRefreshThrottle = CatalogRefreshThrottle(nowMillis = { now }),
    )
}
