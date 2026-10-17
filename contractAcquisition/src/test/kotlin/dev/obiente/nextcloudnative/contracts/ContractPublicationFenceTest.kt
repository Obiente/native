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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class ContractPublicationFenceTest {
    private companion object {
        val DEFINITIVE = listOf(
            ContractUnavailableReason.NoMatchingRelease,
            ContractUnavailableReason.NoUsableContract,
            ContractUnavailableReason.MalformedSource,
        )
        val TRANSIENT = ContractUnavailableReason.entries - DEFINITIVE.toSet()
    }

    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cache = MemoryVerifiedContractCache()
    private val verifications = AtomicInteger()
    private val firstEntered = CountDownLatch(1)
    private val releaseFirst = CountDownLatch(1)
    private var firstFails = false
    private var cancelVerification: Int? = null
    private var now = 50_000_000L

    @AfterTest
    fun shutDown() {
        releaseFirst.countDown()
        executor.shutdownNow()
    }

    @Test
    fun `the fence follows its state table for every kind of ending`() {
        val fence = ContractPublicationFence()
        val published = mutableListOf<String>()
        fun publish(generation: Long, version: Int) =
            fence.publish(request, generation, contract(version)) { published += it.document }

        // A newer contract supersedes an older one, which reports the newer contract.
        val older = fence.start(request)
        val newer = fence.start(request)
        assertEquals(contract(2), acquired(publish(newer, 2)))
        assertEquals(contract(2), acquired(publish(older, 1)))
        assertEquals(listOf(document(2)), published)

        // Transient endings and cancellation of a newer generation fence nothing.
        val waiting = fence.start(request)
        for (reason in TRANSIENT) {
            val transient = ContractAcquisitionOutcome.Unavailable(reason)
            assertEquals(transient, fence.complete(request, fence.start(request), transient))
        }
        fence.start(request) // A cancelled generation never reports back.
        assertEquals(contract(3), acquired(publish(waiting, 3)))

        // A definitive no-contract ending supersedes older generations, which report it.
        for (reason in DEFINITIVE) {
            val stale = fence.start(request)
            val definitive = ContractAcquisitionOutcome.Unavailable(reason)
            assertEquals(definitive, fence.complete(request, fence.start(request), definitive))
            assertEquals(definitive, publish(stale, 9))
            assertEquals(definitive, fence.complete(request, stale, unavailable(ContractUnavailableReason.NoUsableContract)))
        }
        assertEquals(listOf(document(2), document(3)), published)

        // Invalidation supersedes every started generation; later ones publish normally.
        val running = fence.start(request)
        fence.invalidate(request) {}
        assertEquals(unavailable(ContractUnavailableReason.NoUsableContract), publish(running, 4))
        assertEquals(contract(5), acquired(publish(fence.start(request), 5)))
        // Requests are fenced independently.
        assertEquals(contract(6), acquired(fence.publish(request.copy(installedAppVersion = "4.0.3"), 1, contract(6)) {}))
        assertEquals(listOf(document(2), document(3), document(5)), published)
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
            // The rejected contract is never reported as acquired.
            assertEquals(unavailable(ContractUnavailableReason.NoUsableContract), running.get(10, TimeUnit.SECONDS))
            assertNull(cache.load(request))

            // The next automatic discovery acquires again; the catalog is still cached.
            server.enqueue(MockResponse(body = "package"))
            assertEquals(document(2), document(acquirer.acquireOutcome(request)))
            assertEquals(document(2), cache.load(request)?.document)
        }
    }

    @Test
    fun `a definitive no-contract refresh supersedes an older acquisition`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            val stale = startBlockedAcquisition(server, acquirer)

            // Neither refreshed catalog lists the app any more.
            repeat(2) { server.enqueue(MockResponse(body = "[]")) }
            val noRelease = unavailable(ContractUnavailableReason.NoMatchingRelease)
            assertEquals(noRelease, acquirer.acquireOutcome(request, AcquisitionCachePolicy.Refresh))
            releaseFirst.countDown()

            assertEquals(noRelease, stale.get(10, TimeUnit.SECONDS))
            assertNull(cache.load(request))
        }
    }

    @Test
    fun `a transient refresh failure leaves an older acquisition free to publish`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            val older = startBlockedAcquisition(server, acquirer)

            server.enqueue(MockResponse(code = 503, body = "unavailable"))
            assertFailsWith<ContractSourceHttpException> { acquirer.acquireOutcome(request, AcquisitionCachePolicy.Refresh) }
            now += 61_000L
            // Both catalogs are unreadable, which proves nothing about the contract.
            repeat(2) { server.enqueue(MockResponse(body = "[truncated")) }
            assertEquals(
                unavailable(ContractUnavailableReason.UnreadableCatalog),
                acquirer.acquireOutcome(request, AcquisitionCachePolicy.Refresh),
            )
            releaseFirst.countDown()

            assertEquals(document(1), document(older.get(10, TimeUnit.SECONDS)))
            assertEquals(document(1), cache.load(request)?.document)
        }
    }

    @Test
    fun `a cancelled newer acquisition leaves an older acquisition free to publish`() {
        MockWebServer().use { server ->
            server.start()
            cancelVerification = 2
            val acquirer = acquirer(server)
            val older = startBlockedAcquisition(server, acquirer)

            enqueueCatalogAndPackage(server)
            assertFailsWith<CancellationException> { acquirer.acquireOutcome(request, AcquisitionCachePolicy.Refresh) }
            releaseFirst.countDown()

            assertEquals(document(1), document(older.get(10, TimeUnit.SECONDS)))
            assertEquals(document(1), cache.load(request)?.document)
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

    private fun unavailable(reason: ContractUnavailableReason) = ContractAcquisitionOutcome.Unavailable(reason)

    private fun acquired(outcome: ContractAcquisitionOutcome) = assertIs<ContractAcquisitionOutcome.Acquired>(outcome).contract

    private fun contract(version: Int) = VerifiedOpenApiContract(
        appId = "cospend",
        appVersion = "4.0.2",
        contractVersion = "4.0.2",
        specFile = "openapi.json",
        document = document(version),
        catalogUrl = "https://fixture.invalid/apps.json",
        packageUrl = "https://fixture.invalid/cospend.tar.gz",
        sourceUrl = "https://fixture.invalid/cospend.tar.gz#openapi.json",
        sourceKind = OpenApiContractSourceKind.SignedAppPackage,
        contractKind = VerifiedContractKind.OpenApi,
    )

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
                if (call == cancelVerification) throw CancellationException("synthetic cancellation")
                return VerifiedPackageContract(release.appId, release.version, "openapi.json", document(call))
            }
        },
        verifiedContractCache = cache,
        catalogRefreshThrottle = CatalogRefreshThrottle(nowMillis = { now }),
    )
}
