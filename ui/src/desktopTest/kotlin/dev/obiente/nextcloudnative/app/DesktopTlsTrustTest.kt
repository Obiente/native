package dev.obiente.nextcloudnative.app

import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

class DesktopTlsTrustTest {
    private val privateAuthority = syntheticTrustAuthority("Synthetic private CA")
    private val unrelatedAuthority = syntheticTrustAuthority("Synthetic unrelated CA")
    private val bundledAuthority = syntheticTrustAuthority("Synthetic bundled CA")

    @Test
    fun serverIssuedByOperatingSystemTrustedPrivateCaIsAccepted() {
        val trust = desktopTlsTrust(systemAnchors(privateAuthority), bundled = bundledOnly())
        assertIs<DesktopCompositeTrustManager>(trust.trustManager)
        withTlsServer(leafFor("localhost", privateAuthority)) { url ->
            assertEquals("trusted", fetch(trust, url))
        }
    }

    @Test
    fun bundledRootsRemainTrustedAlongsideOperatingSystemAnchors() {
        val trust = desktopTlsTrust(systemAnchors(privateAuthority), bundled = bundledOnly())
        withTlsServer(leafFor("localhost", bundledAuthority)) { url ->
            assertEquals("trusted", fetch(trust, url))
        }
    }

    @Test
    fun serverIssuedByAnUntrustedCaIsRejected() {
        val trust = desktopTlsTrust(systemAnchors(privateAuthority), bundled = bundledOnly())
        withTlsServer(leafFor("localhost", unrelatedAuthority)) { url ->
            val failure = assertFailsWith<SSLHandshakeException> { fetch(trust, url) }
            val rejected = generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<CertificateException>()
                .first()
            // Both anchor sets were consulted and both rejected the chain.
            assertEquals(1, rejected.suppressed.size)
        }
    }

    @Test
    fun hostnameMismatchIsRejectedEvenWhenTheCaIsTrusted() {
        val trust = desktopTlsTrust(systemAnchors(privateAuthority), bundled = bundledOnly())
        withTlsServer(leafFor("nextcloud.invalid", privateAuthority)) { url ->
            assertFailsWith<SSLPeerUnverifiedException> { fetch(trust, url) }
        }
    }

    @Test
    fun expiredLeafFromATrustedCaIsRejected() {
        val now = System.currentTimeMillis()
        val expired = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .validityInterval(now - TimeUnit.DAYS.toMillis(30), now - TimeUnit.DAYS.toMillis(1))
            .signedBy(privateAuthority)
            .build()
        val trust = desktopTlsTrust(systemAnchors(privateAuthority), bundled = bundledOnly())
        withTlsServer(expired) { url ->
            assertFailsWith<SSLHandshakeException> { fetch(trust, url) }
        }
    }

    @Test
    fun malformedOrMissingBundleFallsBackToBundledTrustOnly() = withTrustTestDirectory { directory ->
        val malformed = directory.resolve("ca-certificates.crt").also {
            Files.writeString(it, "-----BEGIN CERTIFICATE-----\nnot base64\n-----END CERTIFICATE-----\n")
        }
        val bundled = bundledOnly()
        for (anchors in listOf(
            loadLinuxSystemCaBundle(listOf(malformed)),
            loadLinuxSystemCaBundle(listOf(directory.resolve("missing.crt"))),
        )) {
            val trust = desktopTlsTrust(anchors, bundled = bundled)
            assertIs<DesktopSystemTrustAnchors.Unavailable>(trust.systemAnchors)
            assertSame(bundled, trust.trustManager)
            withTlsServer(leafFor("localhost", privateAuthority)) { url ->
                assertFailsWith<SSLHandshakeException> { fetch(trust, url) }
            }
            withTlsServer(leafFor("localhost", bundledAuthority)) { url ->
                assertEquals("trusted", fetch(trust, url))
            }
        }
    }

    @Test
    fun acceptedIssuersIncludeBothAnchorSetsForChainCleaning() {
        val trust = desktopTlsTrust(systemAnchors(privateAuthority), bundled = bundledOnly())
        val issuers = trust.trustManager.acceptedIssuers.toSet()
        assertEquals(setOf(bundledAuthority.certificate, privateAuthority.certificate), issuers)
        trust.trustManager.acceptedIssuers[0] = unrelatedAuthority.certificate
        assertEquals(issuers, trust.trustManager.acceptedIssuers.toSet())
    }

    @Test
    fun sharedDesktopTrustBuildsOnTheTestHost() {
        val client = OkHttpClient.Builder().useDesktopSystemTrust().build()
        assertTrue(client.x509TrustManager != null)
    }

    private fun systemAnchors(vararg authorities: HeldCertificate) =
        DesktopSystemTrustAnchors.Loaded(
            DesktopSystemTrustSource.LinuxCaBundle,
            authorities.map { it.certificate },
        )

    /** Stands in for the bundled JVM roots so tests never depend on public CAs or the network. */
    private fun bundledOnly(): X509TrustManager {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("bundled", bundledAuthority.certificate)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    private fun leafFor(host: String, issuer: HeldCertificate): HeldCertificate = HeldCertificate.Builder()
        .commonName(host)
        .addSubjectAlternativeName(host)
        .signedBy(issuer)
        .build()

    private fun withTlsServer(leaf: HeldCertificate, block: (String) -> Unit) {
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(leaf).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory())
            server.enqueue(MockResponse.Builder().code(200).body("trusted").build())
            server.start(LOOPBACK, 0)
            block("https://localhost:${server.port}/status.php")
        }
    }

    private fun fetch(trust: DesktopTlsTrust, url: String): String {
        val client = OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory, trust.trustManager)
            .retryOnConnectionFailure(false)
            // One loopback route keeps the TLS failure from being masked by another address family.
            .dns { hostname -> if (hostname == "localhost") listOf(LOOPBACK) else Dns.SYSTEM.lookup(hostname) }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
    }
}

private val LOOPBACK: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
