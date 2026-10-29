package dev.obiente.nextcloudnative.contracts

import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SignatureException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class ContractAcquisitionFailureTest {
    @Test
    fun `typed acquisition exceptions win over their JVM supertypes`() {
        assertKind(ContractAcquisitionFailureKind.SourceUnavailable, ContractSourceHttpException(503, "synthetic"))
        assertKind(ContractAcquisitionFailureKind.VerificationFailed, ContractSourceVerificationException("synthetic"))
        assertKind(ContractAcquisitionFailureKind.InvalidRequest, ContractAcquisitionRequestException("synthetic"))
        assertKind(ContractAcquisitionFailureKind.Network, IOException("synthetic"))
        assertKind(ContractAcquisitionFailureKind.Network, SocketTimeoutException("synthetic"))
        assertKind(ContractAcquisitionFailureKind.Network, UnknownHostException("fixture.invalid"))
        assertKind(ContractAcquisitionFailureKind.VerificationFailed, SignatureException("synthetic"))
    }

    @Test
    fun `runtime initialization failures are client faults and never read their class-name message`() {
        val initializer = ExceptionInInitializerError(IllegalArgumentException("Syntax error near index 3"))
        assertKind(ContractAcquisitionFailureKind.RuntimeIncompatible, initializer)
        assertKind(
            ContractAcquisitionFailureKind.RuntimeIncompatible,
            NoClassDefFoundError("dev.obiente.nextcloudnative.contracts.AppOwnedOpenApiContractKt"),
        )
        assertKind(ContractAcquisitionFailureKind.RuntimeIncompatible, NoSuchMethodError("org.json.JSONObject.keySet"))
    }

    @Test
    fun `unknown and wrapped failures are not reclassified from causes or messages`() {
        assertKind(ContractAcquisitionFailureKind.Unexpected, IllegalStateException("HTTP 503 network timeout"))
        assertKind(ContractAcquisitionFailureKind.Unexpected, RuntimeException(IOException("synthetic")))
        assertKind(ContractAcquisitionFailureKind.Unexpected, StackOverflowError())
    }

    @Test
    fun `malformed request values are invalid requests before any network access`() {
        MockWebServer().use { server ->
            server.start()
            val acquirer = acquirer(server)
            for (request in listOf(
                ContractAcquisitionRequest("../escape", "34.0.3", "1.0.0"),
                ContractAcquisitionRequest("example", "34", "1.0.0"),
                ContractAcquisitionRequest("example", "not-a-version", "1.0.0"),
                ContractAcquisitionRequest("example", "34.0.3", "-1.0.0"),
            )) {
                assertKind(ContractAcquisitionFailureKind.InvalidRequest, assertFails(acquirer, request))
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `catalog and package status failures are typed source failures with their status`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(code = 503, body = "synthetic"))
            val catalog = assertIs<ContractSourceHttpException>(assertFails(acquirer(server), request()))
            assertEquals(503, catalog.status)
        }
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(server)))
            server.enqueue(MockResponse(code = 500, body = "synthetic"))
            val download = assertIs<ContractSourceHttpException>(assertFails(acquirer(server), request()))
            assertEquals(500, download.status)
            assertEquals(ContractAcquisitionFailureKind.SourceUnavailable, classifyContractAcquisitionFailure(download))
        }
    }

    @Test
    fun `rejected packages keep their cause and remain security failures`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(server)))
            server.enqueue(MockResponse(body = "tampered"))
            val rejection = IllegalStateException("The downloaded app package signature is invalid.")
            val failure = assertIs<ContractSourceVerificationException>(
                assertFails(acquirer(server) { throw rejection }, request()),
            )
            assertSame(rejection, failure.cause)
            assertIs<SecurityException>(failure)
            assertKind(ContractAcquisitionFailureKind.VerificationFailed, failure)
        }
    }

    @Test
    fun `cancellation during package verification propagates unchanged`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(server)))
            server.enqueue(MockResponse(body = "package"))
            val cancelled = CancellationException("stop")
            val failure = assertFailsWith<CancellationException> {
                acquirer(server) { throw cancelled }.acquire(request())
            }
            assertSame(cancelled, failure)
        }
    }

    @Test
    fun `a local catalog cache write failure does not fail a downloaded acquisition`() {
        val diskFull = object : AppStoreCatalogCache {
            override fun load(url: String): ByteArray? = null
            override fun store(url: String, bytes: ByteArray) = throw IOException("No space left on device")
        }
        val blocker = Files.createTempFile("catalog-cache-blocker", ".tmp").toFile()
        try {
            // A regular file where the cache directory belongs makes real cache publication fail.
            for (cache in listOf(diskFull, FileAppStoreCatalogCache(File(blocker, "cache")))) {
                MockWebServer().use { server ->
                    server.start()
                    server.enqueue(MockResponse(body = catalog(server)))
                    server.enqueue(MockResponse(body = "package"))
                    val contract = assertNotNull(acquirer(server, catalogCache = cache) { release ->
                        VerifiedPackageContract(release.appId, release.version, "openapi.json", "{}")
                    }.acquire(request()))
                    assertEquals(OpenApiContractSourceKind.SignedAppPackage, contract.sourceKind)
                    assertEquals(2, server.requestCount)
                }
            }
        } finally {
            blocker.delete()
        }
    }

    @Test
    fun `cancellation while publishing the catalog cache is not swallowed`() {
        val cancelled = CancellationException("stop")
        val cache = object : AppStoreCatalogCache {
            override fun load(url: String): ByteArray? = null
            override fun store(url: String, bytes: ByteArray) = throw cancelled
        }
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(server)))
            assertSame(cancelled, assertFailsWith<CancellationException> {
                acquirer(server, catalogCache = cache).acquire(request())
            })
        }
    }

    @Test
    fun `an initializer failure inside package extraction escapes unwrapped for runtime classification`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = catalog(server)))
            server.enqueue(MockResponse(body = "package"))
            val initializer = ExceptionInInitializerError(IllegalArgumentException("synthetic"))
            val failure = assertFailsWith<ExceptionInInitializerError> {
                acquirer(server) { throw initializer }.acquire(request())
            }
            assertSame(initializer, failure)
            assertKind(ContractAcquisitionFailureKind.RuntimeIncompatible, failure)
        }
    }

    @Test
    fun `an unreachable catalog is a network failure`() {
        val port = MockWebServer().use { server ->
            server.start()
            server.port
        }
        val acquirer = SignedAppStoreContractAcquirer(
            httpClient = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
            appStoreBaseUrl = "http://127.0.0.1:$port/api/v1",
            requireHttps = false,
        )
        assertKind(ContractAcquisitionFailureKind.Network, assertFails(acquirer, request()))
    }

    @Test
    fun `a plain HTTP catalog is a verification failure when HTTPS is required`() {
        val acquirer = SignedAppStoreContractAcquirer(appStoreBaseUrl = "http://fixture.invalid/api/v1")
        val failure = assertFails(acquirer, request())
        assertIs<ContractSourceVerificationException>(failure)
        assertKind(ContractAcquisitionFailureKind.VerificationFailed, failure)
    }

    private fun assertKind(expected: ContractAcquisitionFailureKind, failure: Throwable) {
        assertEquals(expected, classifyContractAcquisitionFailure(failure), failure.javaClass.simpleName)
    }

    private fun assertFails(acquirer: SignedAppStoreContractAcquirer, request: ContractAcquisitionRequest): Throwable =
        assertFailsWith<Throwable> { acquirer.acquire(request) }

    private fun request() = ContractAcquisitionRequest("example", "34.0.3", "1.0.0")

    private fun catalog(server: MockWebServer): String {
        val download = server.url("example-1.0.0.tar.gz")
        return """[{"id":"example","certificate":"certificate","releases":[""" +
            """{"version":"1.0.0","download":"$download","signature":"c2lnbmF0dXJl",""" +
            """"signatureDigest":"sha512","isNightly":false}]}]"""
    }

    private fun acquirer(
        server: MockWebServer,
        catalogCache: AppStoreCatalogCache = MemoryAppStoreCatalogCache(),
        verify: (AppStoreRelease) -> VerifiedPackageContract = { error("The verifier must not run.") },
    ) = SignedAppStoreContractAcquirer(
        httpClient = OkHttpClient(),
        appStoreBaseUrl = server.url("api/v1").toString(),
        requireHttps = false,
        trustVerifier = object : AppPackageTrustVerifier {
            override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) = verify(release)
        },
        catalogCache = catalogCache,
    )
}
