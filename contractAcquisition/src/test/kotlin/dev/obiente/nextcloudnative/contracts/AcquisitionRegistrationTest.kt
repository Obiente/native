package dev.obiente.nextcloudnative.contracts

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/** An acquisition's generation is reserved in the same step that registers it as in flight. */
class AcquisitionRegistrationTest {
    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cache = MemoryVerifiedContractCache()
    private val verifications = AtomicInteger()
    private val registrations = Collections.synchronizedList(mutableListOf<Long>())
    private val firstRegistered = CountDownLatch(1)
    private val releaseFirst = CountDownLatch(1)

    @AfterTest
    fun shutDown() {
        releaseFirst.countDown()
        executor.shutdownNow()
    }

    @Test
    fun `joining an acquisition in flight takes no generation`() {
        val fence = ContractPublicationFence()
        assertEquals(null, fence.startIfRegistered(request) { false })
        assertEquals(1L, fence.startIfRegistered(request) { true })
        assertEquals(2L, fence.start(request))
    }

    @Test
    fun `a refresh that starts after a registered acquisition gets the higher generation and wins`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            val older = executor.submit<ContractAcquisitionOutcome> { acquirer.acquireOutcome(request) }
            assertTrue(firstRegistered.await(10, TimeUnit.SECONDS))

            enqueueCatalogAndPackage(server)
            assertEquals(document(1), document(acquirer.acquireOutcome(request, AcquisitionCachePolicy.Refresh)))
            releaseFirst.countDown()

            assertEquals(document(1), document(older.get(10, TimeUnit.SECONDS)))
            assertEquals(listOf(1L, 2L), registrations.toList())
            assertEquals(document(1), cache.load(request)?.document)
        }
    }

    @Test
    fun `an invalidation after registration supersedes the registered acquisition`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            val registered = executor.submit<ContractAcquisitionOutcome> { acquirer.acquireOutcome(request) }
            assertTrue(firstRegistered.await(10, TimeUnit.SECONDS))

            acquirer.invalidateVerifiedContract(request)
            enqueueCatalogAndPackage(server)
            releaseFirst.countDown()

            // It verifies a contract, but may neither publish nor report it.
            assertEquals(
                ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.NoUsableContract),
                registered.get(10, TimeUnit.SECONDS),
            )
            assertEquals(1, verifications.get())
            assertNull(cache.load(request))
        }
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
            override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) =
                VerifiedPackageContract(release.appId, release.version, "openapi.json", document(verifications.incrementAndGet()))
        },
        verifiedContractCache = cache,
    ).also { acquirer ->
        // Pause the first acquisition right after it is registered, before it reads anything.
        acquirer.afterRegistration = { _, generation ->
            registrations += generation
            if (registrations.size == 1) {
                firstRegistered.countDown()
                check(releaseFirst.await(10, TimeUnit.SECONDS)) { "The paused acquisition was never released." }
            }
        }
    }
}
