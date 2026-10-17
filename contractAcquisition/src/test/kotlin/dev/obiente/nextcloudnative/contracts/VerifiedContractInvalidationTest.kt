package dev.obiente.nextcloudnative.contracts

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class VerifiedContractInvalidationTest {
    private val directory: File = Files.createTempDirectory("verified-contract-invalidation").toFile()
    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `a successful deletion removes the entry and leaves no marker`() {
        val cache = FileVerifiedContractCache(directory)
        cache.store(request, contract("{\"version\":1}"))
        assertEquals(VerifiedContractRemoval.Removed, cache.remove(request))
        assertNull(cache.load(request))
        assertTrue(directory.listFiles().orEmpty().isEmpty())

        cache.store(request, contract("{\"version\":2}"))
        assertEquals("{\"version\":2}", cache.load(request)?.document)
        assertEquals(VerifiedContractRemoval.Removed, cache.remove(ContractAcquisitionRequest("absent", "34.0.3", "1.0.0")))
    }

    @Test
    fun `an undeletable entry is marked invalid and never served again, even after a restart`() {
        val files = FakeFiles(deletable = false)
        val cache = FileVerifiedContractCache(directory, { NOW }, files)
        cache.store(request, contract("{\"version\":1}"))

        assertEquals(VerifiedContractRemoval.MarkedInvalid, cache.remove(request))
        assertNull(cache.load(request))
        // A new process without the in-memory record still honors the marker on disk.
        assertNull(FileVerifiedContractCache(directory, { NOW }, files).load(request))
        assertEquals(2, directory.listFiles().orEmpty().size)
    }

    @Test
    fun `an entry that can be neither deleted nor marked is a miss for the rest of the process`() {
        val files = FakeFiles(deletable = false, markable = false)
        val cache = FileVerifiedContractCache(directory, { NOW }, files)
        cache.store(request, contract("{\"version\":1}"))

        assertEquals(VerifiedContractRemoval.InvalidatedForProcess, cache.remove(request))
        assertNull(cache.load(request))
        assertEquals(1, directory.listFiles().orEmpty().size)

        // The next read retries the deletion; once it succeeds, a newly stored contract is served.
        files.deletable = true
        assertNull(cache.load(request))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        cache.store(request, contract("{\"version\":2}"))
        assertEquals("{\"version\":2}", cache.load(request)?.document)
    }

    @Test
    fun `a newly stored contract clears an earlier marker`() {
        val files = FakeFiles(deletable = false)
        val cache = FileVerifiedContractCache(directory, { NOW }, files)
        cache.store(request, contract("{\"version\":1}"))
        assertEquals(VerifiedContractRemoval.MarkedInvalid, cache.remove(request))

        files.deletable = true
        cache.store(request, contract("{\"version\":2}"))
        assertEquals("{\"version\":2}", cache.load(request)?.document)
        assertEquals(1, directory.listFiles().orEmpty().size)
    }

    @Test
    fun `automatic discovery acquires again after an undeletable contract is invalidated`() {
        MockWebServer().use { server ->
            server.start()
            val cache = FileVerifiedContractCache(directory, { NOW }, FakeFiles(deletable = false))
            val acquirer = acquirer(server, cache)
            packageDocument = "{\"version\":1}"
            enqueueCatalogAndPackage(server)
            assertEquals("{\"version\":1}", document(acquirer.acquireOutcome(request)))

            assertEquals(VerifiedContractRemoval.MarkedInvalid, acquirer.invalidateVerifiedContract(request))
            packageDocument = "{\"version\":2}"
            server.enqueue(MockResponse(body = "package"))
            // The catalog stays cached; the package is downloaded again instead of serving the invalid entry.
            assertEquals("{\"version\":2}", document(acquirer.acquireOutcome(request)))
            assertEquals(3, server.requestCount)
            // The replacement could not be written over the undeletable file, so the disk entry stays a miss.
            assertNull(cache.load(request))
        }
    }

    private var packageDocument = "{\"version\":1}"

    private class FakeFiles(var deletable: Boolean, val markable: Boolean = true) : VerifiedContractFileOperations {
        override fun delete(file: File): Boolean = deletable && file.delete()

        override fun createMarker(file: File): Boolean = markable && file.createNewFile()
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

    private fun document(outcome: ContractAcquisitionOutcome): String =
        assertIs<ContractAcquisitionOutcome.Acquired>(outcome).contract.document

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
                VerifiedPackageContract(release.appId, release.version, "openapi.json", packageDocument)
        },
        verifiedContractCache = cache,
        catalogRefreshThrottle = CatalogRefreshThrottle(nowMillis = { NOW }),
    )

    private companion object {
        const val NOW = 50_000_000L
    }
}
