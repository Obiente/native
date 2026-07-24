package dev.obiente.nextcloudnative.contracts

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifiedContractCacheTest {
    @Test
    fun verifiedContractSurvivesProcessRestartWithoutExposingIdentityInFilename() {
        val directory = Files.createTempDirectory("ncn-verified-contract-cache-").toFile()
        try {
            val request = ContractAcquisitionRequest("mail", "34.0.1", "5.4.2")
            val contract = contract()
            FileVerifiedContractCache(directory).store(request, contract)

            val restored = FileVerifiedContractCache(directory).load(request)

            assertEquals(contract, restored)
            val file = directory.listFiles().orEmpty().single()
            assertTrue(file.name.matches(Regex("[0-9a-f]{64}\\.json")))
            assertTrue("mail" !in file.name)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun appOrServerVersionChangesCannotReuseAnOlderContract() {
        val directory = Files.createTempDirectory("ncn-verified-contract-cache-").toFile()
        try {
            val cache = FileVerifiedContractCache(directory)
            cache.store(ContractAcquisitionRequest("mail", "34.0.1", "5.4.2"), contract())

            assertNull(cache.load(ContractAcquisitionRequest("mail", "34.0.1", "5.4.3")))
            assertNull(cache.load(ContractAcquisitionRequest("mail", "35.0.0", "5.4.2")))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun staleOrCorruptEntriesAreCacheMisses() {
        val directory = Files.createTempDirectory("ncn-verified-contract-cache-").toFile()
        try {
            var now = 10_000L
            val request = ContractAcquisitionRequest("mail", "34.0.1", "5.4.2")
            val cache = FileVerifiedContractCache(directory) { now }
            cache.store(request, contract())
            now += 8L * 24L * 60L * 60L * 1_000L
            assertNull(cache.load(request))

            val file = directory.listFiles().orEmpty().single()
            file.writeText("not-json")
            file.setLastModified(now)
            assertNull(cache.load(request))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun acquirerReturnsPersistedVerifiedContractWithoutAnyNetworkDiscovery() {
        val request = ContractAcquisitionRequest("mail", "34.0.1", "5.4.2")
        val expected = contract()
        val cache = object : VerifiedContractCache {
            override fun load(request: ContractAcquisitionRequest): VerifiedOpenApiContract = expected
            override fun store(
                request: ContractAcquisitionRequest,
                contract: VerifiedOpenApiContract,
            ) = error("A cache hit must not be stored again.")
        }

        val loaded = SignedAppStoreContractAcquirer(verifiedContractCache = cache).acquire(request)

        assertEquals(expected, loaded)
    }

    @Test
    fun concurrentAcquisitionOfTheSameContractReadsItsBackingCacheOnce() {
        val request = ContractAcquisitionRequest("mail", "34.0.1", "5.4.2")
        val expected = contract()
        val cacheReads = AtomicInteger()
        val firstReadStarted = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val cache = object : VerifiedContractCache {
            override fun load(request: ContractAcquisitionRequest): VerifiedOpenApiContract {
                cacheReads.incrementAndGet()
                firstReadStarted.countDown()
                check(releaseFirstRead.await(5, TimeUnit.SECONDS))
                return expected
            }

            override fun store(request: ContractAcquisitionRequest, contract: VerifiedOpenApiContract) = Unit
        }
        val acquirer = SignedAppStoreContractAcquirer(verifiedContractCache = cache)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = List(4) { executor.submit<VerifiedOpenApiContract?> { acquirer.acquire(request) } }
            assertTrue(firstReadStarted.await(5, TimeUnit.SECONDS))
            releaseFirstRead.countDown()

            assertEquals(List(4) { expected }, results.map { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, cacheReads.get())
        } finally {
            releaseFirstRead.countDown()
            executor.shutdownNow()
        }
    }

    private fun contract() = VerifiedOpenApiContract(
        appId = "mail",
        appVersion = "5.4.2",
        contractVersion = "5.4.2",
        specFile = "openapi.json",
        document = """{"openapi":"3.0.3","paths":{}}""",
        catalogUrl = "https://apps.nextcloud.com/api/v1/platform/34.0.1/apps.json",
        packageUrl = "https://example.test/mail.tar.gz",
        sourceUrl = "https://example.test/mail.tar.gz#openapi.json",
        sourceKind = OpenApiContractSourceKind.SignedAppPackage,
        contractKind = VerifiedContractKind.OpenApi,
    )
}
