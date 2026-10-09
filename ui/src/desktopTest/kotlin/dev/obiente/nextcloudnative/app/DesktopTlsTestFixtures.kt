package dev.obiente.nextcloudnative.app

import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/** Synthetic certificates and a loopback TLS server for desktop trust tests. No real hosts. */
internal fun syntheticTrustAuthority(name: String): HeldCertificate = HeldCertificate.Builder()
    .commonName(name)
    .certificateAuthority(0)
    .build()

internal fun syntheticLeaf(host: String, issuer: HeldCertificate): HeldCertificate = HeldCertificate.Builder()
    .commonName(host)
    .addSubjectAlternativeName(host)
    .signedBy(issuer)
    .build()

internal fun syntheticExpiredLeaf(host: String, issuer: HeldCertificate): HeldCertificate {
    val now = System.currentTimeMillis()
    return HeldCertificate.Builder()
        .commonName(host)
        .addSubjectAlternativeName(host)
        .validityInterval(now - TimeUnit.DAYS.toMillis(30), now - TimeUnit.DAYS.toMillis(1))
        .signedBy(issuer)
        .build()
}

/** A PKIX trust manager limited to [authorities], standing in for the bundled JVM roots. */
internal fun pkixTrustManagerFor(vararg authorities: HeldCertificate): X509TrustManager {
    val store = KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        authorities.forEachIndexed { index, authority -> setCertificateEntry("anchor-$index", authority.certificate) }
    }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(store)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
}

internal fun withSyntheticTlsServer(leaf: HeldCertificate, block: (String) -> Unit) {
    val serverCertificates = HandshakeCertificates.Builder().heldCertificate(leaf).build()
    MockWebServer().use { server ->
        server.useHttps(serverCertificates.sslSocketFactory())
        server.enqueue(MockResponse.Builder().code(200).body("trusted").build())
        server.start(TRUST_TEST_LOOPBACK, 0)
        block("https://localhost:${server.port}/status.php")
    }
}

internal fun fetchWithTrust(trust: DesktopTlsTrust, url: String): String {
    val client = OkHttpClient.Builder()
        .sslSocketFactory(trust.sslSocketFactory, trust.trustManager)
        .retryOnConnectionFailure(false)
        // One loopback route keeps the TLS failure from being masked by another address family.
        .dns { hostname -> if (hostname == "localhost") listOf(TRUST_TEST_LOOPBACK) else Dns.SYSTEM.lookup(hostname) }
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    return client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
}

internal fun withTrustTestDirectory(block: (Path) -> Unit) {
    val directory = Files.createTempDirectory("desktop-system-trust-test")
    try {
        block(directory)
    } finally {
        File(directory.toString()).deleteRecursively()
    }
}

private val TRUST_TEST_LOOPBACK: InetAddress =
    InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
