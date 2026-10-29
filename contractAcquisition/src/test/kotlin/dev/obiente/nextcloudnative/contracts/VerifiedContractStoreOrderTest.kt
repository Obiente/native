package dev.obiente.nextcloudnative.contracts

import java.io.File
import java.io.IOException
import java.nio.file.Files
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

/**
 * A store deletes an invalidated entry before it clears the invalidation, and clears the invalidation
 * before it writes the replacement, so no failure can make an invalidated or replaced entry readable.
 */
class VerifiedContractStoreOrderTest {
    private val directory: File = Files.createTempDirectory("verified-contract-store-order").toFile()
    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `the invalidated entry is deleted before its marker, and the replacement survives a restart`() {
        val files = ScriptedFiles()
        val cache = invalidatedOldEntry(files)

        assertEquals(VerifiedContractStore.Stored, cache.store(request, contract("new")))
        assertEquals(listOf("delete entry", "delete marker", "write temporary"), files.events)
        assertEquals(listOf("entry"), kinds())
        assertEquals("new", restarted().load(request)?.document)
    }

    @Test
    fun `an invalidated entry that cannot be deleted keeps its invalidation and nothing is written`() {
        val files = ScriptedFiles()
        val cache = invalidatedOldEntry(files)
        files.entryDeletable = false

        assertEquals(VerifiedContractStore.InvalidationNotCleared, cache.store(request, contract("new")))
        assertEquals(listOf("delete entry (failed)"), files.events)
        assertEquals(listOf("entry", "marker"), kinds())
        assertEquals(listOf("old"), documents())
        assertNull(cache.load(request))
        // The in-process record was kept as well: losing the marker does not expose the entry here.
        markers().forEach(File::delete)
        assertNull(cache.load(request))
    }

    @Test
    fun `a full disk after the invalidated entry is deleted leaves nothing readable, even after a restart`() {
        val files = ScriptedFiles()
        val cache = invalidatedOldEntry(files)
        files.writable = false

        assertFailsWith<IOException> { cache.store(request, contract("new")) }
        assertEquals(listOf("delete entry", "delete marker", "write temporary (failed)"), files.events)
        // No invalidated entry remains, so no marker is needed, and no temporary file is left.
        assertEquals(emptyList<String>(), kinds())
        assertNull(cache.load(request))
        assertNull(restarted().load(request))

        files.writable = true
        assertEquals(VerifiedContractStore.Stored, cache.store(request, contract("newer")))
        assertEquals("newer", restarted().load(request)?.document)
    }

    @Test
    fun `a marker left without its entry is cleared before the replacement is written`() {
        val files = ScriptedFiles()
        invalidatedOldEntry(files)
        // The process died after a store deleted the invalidated entry but before it cleared the marker.
        entries().forEach(File::delete)
        val cache = FileVerifiedContractCache(directory, { NOW }, files)

        invalidatedMarkerOnly()
        assertEquals(VerifiedContractStore.Stored, cache.store(request, contract("new")))
        assertEquals(listOf("delete marker", "write temporary"), files.events)
        assertEquals("new", restarted().load(request)?.document)
    }

    @Test
    fun `a marker without its entry that cannot be cleared refuses the store`() {
        val files = ScriptedFiles()
        invalidatedOldEntry(files)
        entries().forEach(File::delete)
        files.markerDeletable = false
        val cache = FileVerifiedContractCache(directory, { NOW }, files)

        assertEquals(VerifiedContractStore.InvalidationNotCleared, cache.store(request, contract("new")))
        assertEquals(listOf("delete marker (failed)"), files.events)
        assertEquals(listOf("marker"), kinds())
    }

    @Test
    fun `a store without any invalidation is unchanged`() {
        val files = ScriptedFiles()
        val cache = FileVerifiedContractCache(directory, { NOW }, files)
        assertEquals(VerifiedContractStore.Stored, cache.store(request, contract("first")))
        assertEquals(VerifiedContractStore.Stored, cache.store(request, contract("second")))
        assertEquals(listOf("write temporary", "write temporary", "delete entry"), files.events)
        assertEquals(listOf("entry"), kinds())
        assertEquals("second", restarted().load(request)?.document)
    }

    @Test
    fun `a valid entry that cannot be replaced is invalidated instead of outliving the newer contract`() {
        val files = ScriptedFiles()
        val cache = FileVerifiedContractCache(directory, { NOW }, files)
        cache.store(request, contract("old"))
        files.entryDeletable = false

        assertFailsWith<IllegalStateException> { cache.store(request, contract("new")) }
        assertEquals(listOf("entry", "marker"), kinds())
        assertNull(cache.load(request))
        assertNull(FileVerifiedContractCache(directory, { NOW }, files).load(request))
    }

    @Test
    fun `a full disk while replacing a valid entry deletes the older entry`() {
        val files = ScriptedFiles()
        val cache = FileVerifiedContractCache(directory, { NOW }, files)
        cache.store(request, contract("old"))
        files.writable = false

        assertFailsWith<IOException> { cache.store(request, contract("new")) }
        assertEquals(emptyList<String>(), kinds())
        assertNull(restarted().load(request))
    }

    @Test
    fun `the acquirer serves an unpersisted contract from memory and reports why`() {
        for ((breakStore, expected) in listOf(
            { files: ScriptedFiles -> files.entryDeletable = false } to VerifiedContractStore.InvalidationNotCleared,
            { files: ScriptedFiles -> files.writable = false } to VerifiedContractStore.Failed,
        )) {
            directory.listFiles().orEmpty().forEach(File::delete)
            MockWebServer().use { server ->
                server.start()
                val files = ScriptedFiles()
                val cache = invalidatedOldEntry(files)
                breakStore(files)
                val acquirer = acquirer(server, cache)
                enqueueCatalogAndPackage(server)

                val outcome = assertIs<ContractAcquisitionOutcome.Acquired>(acquirer.acquireOutcome(request))
                assertEquals("{}", outcome.contract.document)
                assertEquals(expected, outcome.cacheStore)
                // The next automatic discovery is served from memory without a request.
                val requests = server.requestCount
                val next = assertIs<ContractAcquisitionOutcome.Acquired>(acquirer.acquireOutcome(request))
                assertEquals("{}", next.contract.document)
                assertEquals(requests, server.requestCount)
            }
        }
    }

    /** Stores "old", fails to delete it on invalidation so it is marked, and returns that cache. */
    private fun invalidatedOldEntry(files: ScriptedFiles): FileVerifiedContractCache {
        val cache = FileVerifiedContractCache(directory, { NOW }, files)
        cache.store(request, contract("old"))
        files.entryDeletable = false
        assertEquals(VerifiedContractRemoval.MarkedInvalid, cache.remove(request))
        files.entryDeletable = true
        files.events.clear()
        return cache
    }

    private fun invalidatedMarkerOnly() = assertEquals(listOf("marker"), kinds())

    private fun restarted() = FileVerifiedContractCache(directory, { NOW })

    private fun markers() = directory.listFiles().orEmpty().filter { it.name.endsWith(".invalid") }

    private fun entries() = directory.listFiles().orEmpty().filter { it.name.endsWith(".json") }

    private fun documents() = entries().map { org.json.JSONObject(it.readText()).getString("document") }

    /** The kinds of files left in the cache directory, so a leftover temporary file is visible too. */
    private fun kinds() = directory.listFiles().orEmpty().map { file ->
        when {
            file.name.endsWith(".invalid") -> "marker"
            file.name.endsWith(".json") -> "entry"
            else -> "other: ${file.name}"
        }
    }.sorted()

    /** File operations whose deletions and writes can be made to fail, recording each attempt. */
    private class ScriptedFiles : VerifiedContractFileOperations {
        var entryDeletable = true
        var markerDeletable = true
        var writable = true
        val events = mutableListOf<String>()

        override fun delete(file: File): Boolean {
            val marker = file.name.endsWith(".invalid")
            val allowed = if (marker) markerDeletable else entryDeletable
            events += (if (marker) "delete marker" else "delete entry") + if (allowed) "" else " (failed)"
            return allowed && file.delete()
        }

        override fun createMarker(file: File): Boolean = file.createNewFile()

        override fun write(file: File, bytes: ByteArray) {
            events += "write temporary" + if (writable) "" else " (failed)"
            if (!writable) throw IOException("No space left on device.")
            super.write(file, bytes)
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
                VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
        },
        verifiedContractCache = cache,
    )

    private companion object {
        const val NOW = 50_000_000L
    }
}
