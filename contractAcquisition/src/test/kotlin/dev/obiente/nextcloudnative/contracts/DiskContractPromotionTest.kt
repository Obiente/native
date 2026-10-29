package dev.obiente.nextcloudnative.contracts

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/** A contract read from disk reaches memory only through the publication fence. */
class DiskContractPromotionTest {
    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")

    @Test
    fun `a disk read superseded by a refresh does not replace the refreshed contract in memory`() {
        MockWebServer().use { server ->
            server.start()
            val cache = PausingCache(contract("old"))
            val acquirer = acquirer(server, cache)
            val stale = AtomicReference<ContractAcquisitionOutcome>()
            val failure = AtomicReference<Throwable>()

            // A cache-only read loads the old entry from disk and pauses before promoting it.
            val reader = thread {
                try {
                    stale.set(acquirer.acquireOutcome(request))
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            assertTrue(cache.loaded.await(WAIT_SECONDS, TimeUnit.SECONDS))

            // A refresh verifies and publishes a replacement meanwhile.
            enqueueCatalogAndPackage(server)
            val refreshed = acquirer.acquireOutcome(request, AcquisitionCachePolicy.Refresh)
            assertEquals("new", assertIs<ContractAcquisitionOutcome.Acquired>(refreshed).contract.document)

            cache.resume.countDown()
            reader.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            assertFalse(reader.isAlive)
            failure.get()?.let { throw it }
            // The superseded read reports the newer contract rather than its own.
            assertEquals("new", assertIs<ContractAcquisitionOutcome.Acquired>(stale.get()).contract.document)

            // Memory still holds the replacement: the next acquisition serves it without disk or network.
            val loads = cache.loads.get()
            val requests = server.requestCount
            val next = assertIs<ContractAcquisitionOutcome.Acquired>(acquirer.acquireOutcome(request))
            assertEquals("new", next.contract.document)
            assertEquals(loads, cache.loads.get())
            assertEquals(requests, server.requestCount)
        }
    }

    @Test
    fun `a current disk read is promoted into memory`() {
        MockWebServer().use { server ->
            server.start()
            val cache = PausingCache(contract("old"), pauseFirstLoad = false)
            val acquirer = acquirer(server, cache)
            assertEquals("old", assertIs<ContractAcquisitionOutcome.Acquired>(acquirer.acquireOutcome(request)).contract.document)
            val loads = cache.loads.get()
            assertEquals("old", assertIs<ContractAcquisitionOutcome.Acquired>(acquirer.acquireOutcome(request)).contract.document)
            assertEquals(loads, cache.loads.get())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `the fence promotes only reads that nothing newer superseded`() {
        val fence = ContractPublicationFence()
        var promotions = 0

        // Nothing recorded yet, for example after a process start.
        fence.promote(request, fence.start(request)) { promotions++ }
        assertEquals(1, promotions)

        // A read that started before an accepted result is superseded by it.
        val reading = fence.start(request)
        fence.publish(request, fence.start(request), contract("new")) {}
        fence.promote(request, reading) { promotions++ }
        assertEquals(1, promotions)

        // A read after a definitive no-contract result or an invalidation does not promote.
        fence.complete(
            request,
            fence.start(request),
            ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.NoMatchingRelease),
        )
        fence.promote(request, fence.start(request)) { promotions++ }
        fence.invalidate(request) {}
        fence.promote(request, fence.start(request)) { promotions++ }
        assertEquals(1, promotions)

        // A read after a newer published contract promotes again.
        fence.publish(request, fence.start(request), contract("newer")) {}
        fence.promote(request, fence.start(request)) { promotions++ }
        assertEquals(2, promotions)
    }

    /** A persistent cache whose first load pauses after reading the entry, before the acquirer promotes it. */
    private class PausingCache(
        initial: VerifiedOpenApiContract,
        private val pauseFirstLoad: Boolean = true,
    ) : VerifiedContractCache {
        val loaded = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val loads = AtomicInteger()
        private val stored = AtomicReference<VerifiedOpenApiContract?>(initial)

        override fun load(request: ContractAcquisitionRequest): VerifiedOpenApiContract? {
            val contract = stored.get()
            if (loads.getAndIncrement() == 0 && pauseFirstLoad) {
                loaded.countDown()
                check(resume.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "The paused read was never resumed." }
            }
            return contract
        }

        override fun store(request: ContractAcquisitionRequest, contract: VerifiedOpenApiContract): VerifiedContractStore {
            stored.set(contract)
            return VerifiedContractStore.Stored
        }

        override fun remove(request: ContractAcquisitionRequest): VerifiedContractRemoval {
            stored.set(null)
            return VerifiedContractRemoval.Removed
        }
    }

    private fun contract(document: String) = VerifiedOpenApiContract(
        appId = "cospend",
        appVersion = "4.0.2",
        contractVersion = "4.0.2",
        specFile = "openapi.json",
        document = document,
        catalogUrl = "https://fixture.invalid/apps.json",
        packageUrl = "https://fixture.invalid/cospend.tar.gz",
        sourceUrl = "https://fixture.invalid/cospend.tar.gz#openapi.json",
        sourceKind = OpenApiContractSourceKind.SignedAppPackage,
        contractKind = VerifiedContractKind.OpenApi,
    )

    private fun enqueueCatalogAndPackage(server: MockWebServer) {
        val download = server.url("downloads/cospend-4.0.2.tar.gz")
        server.enqueue(MockResponse(body = """[{"id":"cospend","certificate":"c","releases":[""" +
            """{"version":"4.0.2","download":"$download","signature":"c2lnbmF0dXJl",""" +
            """"signatureDigest":"sha512","isNightly":false}]}]"""))
        server.enqueue(MockResponse(body = "package"))
    }

    private fun acquirer(server: MockWebServer, cache: VerifiedContractCache) = SignedAppStoreContractAcquirer(
        httpClient = OkHttpClient(),
        appStoreBaseUrl = server.url("api/v1").toString(),
        requireHttps = false,
        trustVerifier = object : AppPackageTrustVerifier {
            override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) =
                VerifiedPackageContract(release.appId, release.version, "openapi.json", "new")
        },
        verifiedContractCache = cache,
        catalogRefreshThrottle = CatalogRefreshThrottle(nowMillis = { 50_000_000L }),
    )

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
