package dev.obiente.nextcloudnative.contracts

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

/** An older catalog download never replaces a newer catalog, in memory or on disk. */
class CatalogPublicationOrderTest {
    private val directory: File = Files.createTempDirectory("catalog-publication-order").toFile()
    private val request = ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2")
    private val releases = mutableListOf<CountDownLatch>()

    @AfterTest
    fun cleanUp() {
        releases.forEach(CountDownLatch::countDown)
        directory.deleteRecursively()
    }

    @Test
    fun `an automatic download that finishes after a refresh cannot bring back a removed release`() {
        MockWebServer().use { server ->
            val catalogs = ScriptedServer()
            server.dispatcher = catalogs
            server.start()
            val arrived = CountDownLatch(1)
            val release = gate()
            catalogs.on(PLATFORM, Step(MockResponse(body = catalog(server, "4.0.2")), arrived, release))
            catalogs.on(PLATFORM, Step(MockResponse(body = "[]")))
            catalogs.always(ALL, MockResponse(body = "[]"))
            catalogs.always(PACKAGE, MockResponse(body = "package"))
            val catalogCache = FileAppStoreCatalogCache(directory) { NOW }
            val acquirer = acquirer(server, catalogCache)

            // An automatic acquisition starts downloading the catalog that still lists the release.
            val automatic = Background { acquirer.acquireOutcome(request) }
            assertTrue(arrived.await(WAIT_SECONDS, TimeUnit.SECONDS))

            // A refresh downloads the newer catalog, where the release was removed.
            val noRelease = ContractAcquisitionOutcome.Unavailable(ContractUnavailableReason.NoMatchingRelease)
            assertEquals(noRelease, acquirer.acquireOutcome(request, AcquisitionCachePolicy.Refresh))

            // The older download finishes last; it neither replaces the catalog nor reports its contract.
            release.countDown()
            assertEquals(noRelease, automatic.await())
            assertEquals("[]", catalogCache.load(server.url(PLATFORM).toString())?.decodeToString())
            assertEquals("[]", FileAppStoreCatalogCache(directory) { NOW }.load(server.url(PLATFORM).toString())?.decodeToString())

            // The next automatic acquisition reads the newer catalog and still finds no release.
            val requests = catalogs.requests.get()
            assertEquals(noRelease, acquirer.acquireOutcome(request))
            assertEquals(requests, catalogs.requests.get())
        }
    }

    @Test
    fun `the newer of two refreshes wins, whether they run in turn or overlap`() {
        MockWebServer().use { server ->
            val catalogs = ScriptedServer()
            server.dispatcher = catalogs
            server.start()
            val url = server.url(PLATFORM).toString()
            val catalogCache = FileAppStoreCatalogCache(directory) { NOW }
            val reader = reader(catalogCache)

            catalogs.on(PLATFORM, Step(MockResponse(body = catalog(server, "1.0.0"))))
            catalogs.on(PLATFORM, Step(MockResponse(body = catalog(server, "2.0.0"))))
            reader.read(url, "cospend", AcquisitionCachePolicy.Refresh)
            reader.read(url, "cospend", AcquisitionCachePolicy.Refresh)
            assertEquals(listOf("2.0.0"), cachedVersions(reader, url))

            val arrived = CountDownLatch(1)
            val release = gate()
            catalogs.on(PLATFORM, Step(MockResponse(body = catalog(server, "3.0.0")), arrived, release))
            catalogs.on(PLATFORM, Step(MockResponse(body = catalog(server, "4.0.0"))))
            val older = Background { reader.read(url, "cospend", AcquisitionCachePolicy.Refresh) }
            assertTrue(arrived.await(WAIT_SECONDS, TimeUnit.SECONDS))
            reader.read(url, "cospend", AcquisitionCachePolicy.Refresh)
            release.countDown()
            // The older download still reports what it read, but the cache keeps the newer catalog.
            assertEquals(listOf("3.0.0"), older.await().releases.map(AppStoreRelease::version))
            assertEquals(listOf("4.0.0"), cachedVersions(reader, url))
            val restarted = reader(FileAppStoreCatalogCache(directory) { NOW })
            assertEquals(listOf("4.0.0"), cachedVersions(restarted, url))
        }
    }

    @Test
    fun `a newer download that fails transiently does not stop an older one from being stored`() {
        MockWebServer().use { server ->
            val catalogs = ScriptedServer()
            server.dispatcher = catalogs
            server.start()
            val url = server.url(PLATFORM).toString()
            val catalogCache = MemoryAppStoreCatalogCache()
            val reader = reader(catalogCache)
            val arrived = CountDownLatch(1)
            val release = gate()
            catalogs.on(PLATFORM, Step(MockResponse(body = catalog(server, "1.0.0")), arrived, release))
            catalogs.on(PLATFORM, Step(MockResponse(code = 503, body = "unavailable")))
            catalogs.on(PLATFORM, Step(MockResponse(body = "[truncated")))

            val older = Background { reader.read(url, "cospend", AcquisitionCachePolicy.Refresh) }
            assertTrue(arrived.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertFailsWith<ContractSourceHttpException> { reader.read(url, "cospend", AcquisitionCachePolicy.Refresh) }
            assertFalse(reader.read(url, "cospend", AcquisitionCachePolicy.Refresh).documentReadable)
            release.countDown()
            older.await()
            assertEquals(listOf("1.0.0"), cachedVersions(reader, url))
        }
    }

    @Test
    fun `a cancelled download leaves nothing held, so older and later downloads are still stored`() {
        MockWebServer().use { server ->
            val catalogs = ScriptedServer()
            server.dispatcher = catalogs
            server.start()
            val url = server.url(PLATFORM).toString()
            val catalogCache = MemoryAppStoreCatalogCache()
            val fence = CatalogPublicationFence()
            val olderClient = OkHttpClient()
            val cancelledClient = OkHttpClient()
            val reader = reader(catalogCache, fence, olderClient)
            val cancelling = reader(catalogCache, fence, cancelledClient)
            val olderArrived = CountDownLatch(1)
            val cancelledArrived = CountDownLatch(1)
            val release = gate()
            val cancelledRelease = gate()
            catalogs.on(PLATFORM, Step(MockResponse(body = catalog(server, "1.0.0")), olderArrived, release))
            catalogs.on(PLATFORM, Step(MockResponse(body = catalog(server, "9.9.9")), cancelledArrived, cancelledRelease))
            catalogs.on(PLATFORM, Step(MockResponse(body = catalog(server, "2.0.0"))))

            val older = Background { reader.read(url, "cospend", AcquisitionCachePolicy.Refresh) }
            assertTrue(olderArrived.await(WAIT_SECONDS, TimeUnit.SECONDS))
            val cancelled = Background { cancelling.read(url, "cospend", AcquisitionCachePolicy.Refresh) }
            assertTrue(cancelledArrived.await(WAIT_SECONDS, TimeUnit.SECONDS))
            cancelledClient.dispatcher.cancelAll()
            assertFailsWith<IOException> { cancelled.await() }
            cancelledRelease.countDown()

            release.countDown()
            older.await()
            assertEquals(listOf("1.0.0"), cachedVersions(reader, url))
            reader.read(url, "cospend", AcquisitionCachePolicy.Refresh)
            assertEquals(listOf("2.0.0"), cachedVersions(reader, url))
        }
    }

    @Test
    fun `a cached copy older than the newest accepted catalog is read as a miss`() {
        MockWebServer().use { server ->
            val catalogs = ScriptedServer()
            server.dispatcher = catalogs
            server.start()
            val url = server.url(PLATFORM).toString()
            val catalogCache = FailingStoreCache()
            catalogCache.entries[url] = catalog(server, "1.0.0").encodeToByteArray()
            val reader = reader(catalogCache)
            catalogs.on(PLATFORM, Step(MockResponse(body = catalog(server, "2.0.0"))))
            catalogs.on(PLATFORM, Step(MockResponse(body = catalog(server, "2.0.0"))))

            assertEquals(CatalogSource.Cached, reader.read(url, "cospend", AcquisitionCachePolicy.UseCache).source)
            // The refresh is accepted, but storing it fails, so the cache still holds the older copy.
            reader.read(url, "cospend", AcquisitionCachePolicy.Refresh)
            val requests = catalogs.requests.get()
            val read = reader.read(url, "cospend", AcquisitionCachePolicy.UseCache)
            assertEquals(CatalogSource.Downloaded, read.source)
            assertEquals(listOf("2.0.0"), read.releases.map(AppStoreRelease::version))
            assertEquals(requests + 1, catalogs.requests.get())
        }
    }

    private fun cachedVersions(reader: AppStoreCatalogReader, url: String): List<String> {
        val cached = reader.read(url, "cospend", AcquisitionCachePolicy.UseCache)
        assertEquals(CatalogSource.Cached, cached.source)
        return cached.releases.map(AppStoreRelease::version)
    }

    private fun gate() = CountDownLatch(1).also(releases::add)

    private fun reader(
        cache: AppStoreCatalogCache,
        fence: CatalogPublicationFence = CatalogPublicationFence(),
        client: OkHttpClient = OkHttpClient(),
    ) = AppStoreCatalogReader(
        httpClient = client,
        catalogCache = cache,
        // Every explicit refresh may download, so the order of downloads is what is under test.
        refreshThrottle = CatalogRefreshThrottle(intervalMillis = 0L, nowMillis = { NOW }),
        maximumCatalogBytes = ContractResponseLimits().catalogBytes,
        requireHttps = false,
        fence = fence,
    )

    private fun acquirer(server: MockWebServer, catalogCache: AppStoreCatalogCache) = SignedAppStoreContractAcquirer(
        httpClient = OkHttpClient(),
        appStoreBaseUrl = server.url("api/v1").toString(),
        requireHttps = false,
        trustVerifier = object : AppPackageTrustVerifier {
            override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) =
                VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
        },
        catalogCache = catalogCache,
        catalogRefreshThrottle = CatalogRefreshThrottle(nowMillis = { NOW }),
    )

    private fun catalog(server: MockWebServer, vararg versions: String): String {
        val download = server.url(PACKAGE)
        return versions.joinToString(",", """[{"id":"cospend","certificate":"c","releases":[""", "]}]") { version ->
            """{"version":"$version","download":"$download","signature":"c2lnbmF0dXJl",""" +
                """"signatureDigest":"sha512","isNightly":false}"""
        }
    }

    private class Step(
        val response: MockResponse,
        val arrived: CountDownLatch? = null,
        val release: CountDownLatch? = null,
    )

    /** Serves scripted responses per path; a step can signal its arrival and wait to be released. */
    private class ScriptedServer : Dispatcher() {
        val requests = AtomicInteger()
        private val steps = ConcurrentHashMap<String, LinkedBlockingQueue<Step>>()
        private val fixed = ConcurrentHashMap<String, MockResponse>()

        fun on(path: String, step: Step) {
            steps.getOrPut(path, ::LinkedBlockingQueue).add(step)
        }

        fun always(path: String, response: MockResponse) {
            fixed[path] = response
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests.incrementAndGet()
            val path = request.url.encodedPath
            val step = steps[path]?.poll() ?: return fixed[path] ?: MockResponse(code = 404)
            step.arrived?.countDown()
            step.release?.await(WAIT_SECONDS, TimeUnit.SECONDS)
            return step.response
        }
    }

    /** A catalog cache whose writes fail, as on a full disk. */
    private class FailingStoreCache : AppStoreCatalogCache {
        val entries = ConcurrentHashMap<String, ByteArray>()

        override fun load(url: String): ByteArray? = entries[url]

        override fun store(url: String, bytes: ByteArray) {
            throw IOException("No space left on device.")
        }
    }

    /** Runs [block] on its own thread; [await] returns its result or rethrows its failure. */
    private class Background<T>(block: () -> T) {
        private val result = AtomicReference<T>()
        private val failure = AtomicReference<Throwable>()
        private val worker = thread {
            try {
                result.set(block())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        fun await(): T {
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            assertFalse(worker.isAlive, "The background work did not finish.")
            failure.get()?.let { throw it }
            return result.get()
        }
    }

    private companion object {
        const val NOW = 50_000_000L
        const val WAIT_SECONDS = 10L
        const val PLATFORM = "/api/v1/platform/34.0.3/apps.json"
        const val ALL = "/api/v1/apps.json"
        const val PACKAGE = "/downloads/cospend-4.0.2.tar.gz"
    }
}
