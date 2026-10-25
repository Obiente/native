package dev.obiente.nextcloudnative.contracts

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/** An invalidation marker is cleared before a replacement is published, never after. */
class VerifiedContractStoreOrderTest {
    private val directory: File = Files.createTempDirectory("verified-contract-store-order").toFile()
    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `a marker is cleared before the replacement is published, and the replacement survives a restart`() {
        val files = RecordingFiles(deletable = false)
        val cache = FileVerifiedContractCache(directory, { NOW }, files)
        cache.store(request, contract("old"))
        assertEquals(VerifiedContractRemoval.MarkedInvalid, cache.remove(request))

        files.deletable = true
        assertEquals(VerifiedContractStore.Stored, cache.store(request, contract("new")))
        // When the marker was deleted, the new contract had not been published yet.
        assertEquals(listOf("marker deleted while the entry held: old"), files.markerDeletions)
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".invalid") })
        assertEquals("new", FileVerifiedContractCache(directory, { NOW }).load(request)?.document)
    }

    @Test
    fun `a marker that cannot be cleared leaves the replacement unpersisted instead of self-discarding`() {
        val files = RecordingFiles(deletable = false)
        val cache = FileVerifiedContractCache(directory, { NOW }, files)
        cache.store(request, contract("old"))
        assertEquals(VerifiedContractRemoval.MarkedInvalid, cache.remove(request))

        assertEquals(VerifiedContractStore.InvalidationNotCleared, cache.store(request, contract("new")))
        // The new contract was never written where the next load would delete it.
        val entries = directory.listFiles().orEmpty().filterNot { it.name.endsWith(".invalid") }
        assertEquals(listOf("old"), entries.map { org.json.JSONObject(it.readText()).getString("document") })
        assertNull(FileVerifiedContractCache(directory, { NOW }, files).load(request))
    }

    @Test
    fun `the acquirer serves an unpersisted contract from memory and reports it`() {
        MockWebServer().use { server ->
            server.start()
            val files = RecordingFiles(deletable = false)
            val cache = FileVerifiedContractCache(directory, { NOW }, files)
            cache.store(request, contract("old"))
            cache.remove(request)
            val acquirer = acquirer(server, cache)
            enqueueCatalogAndPackage(server)

            val outcome = assertIs<ContractAcquisitionOutcome.Acquired>(acquirer.acquireOutcome(request))
            assertEquals("{}", outcome.contract.document)
            assertEquals(VerifiedContractStore.InvalidationNotCleared, outcome.cacheStore)
            // The next automatic discovery is served from memory without a request.
            val requests = server.requestCount
            assertEquals("{}", assertIs<ContractAcquisitionOutcome.Acquired>(acquirer.acquireOutcome(request)).contract.document)
            assertEquals(requests, server.requestCount)
        }
    }

    @Test
    fun `a store without any invalidation is unchanged`() {
        val files = RecordingFiles(deletable = true)
        val cache = FileVerifiedContractCache(directory, { NOW }, files)
        assertEquals(VerifiedContractStore.Stored, cache.store(request, contract("first")))
        assertEquals(VerifiedContractStore.Stored, cache.store(request, contract("second")))
        assertEquals(emptyList<String>(), files.markerDeletions)
        assertEquals("second", FileVerifiedContractCache(directory, { NOW }).load(request)?.document)
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".invalid") })
    }

    private class RecordingFiles(var deletable: Boolean) : VerifiedContractFileOperations {
        val markerDeletions = mutableListOf<String>()

        override fun delete(file: File): Boolean {
            if (!deletable) return false
            if (file.name.endsWith(".invalid")) {
                val entry = File(file.parentFile, file.name.removeSuffix(".invalid"))
                val document = entry.takeIf(File::isFile)?.let { org.json.JSONObject(it.readText()).getString("document") }
                markerDeletions += "marker deleted while the entry held: $document"
            }
            return file.delete()
        }

        override fun createMarker(file: File): Boolean = file.createNewFile()
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
                VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
        },
        verifiedContractCache = cache,
    )

    private companion object {
        const val NOW = 50_000_000L
    }
}
