package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.security.cert.CertificateException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate

class DesktopTlsTrustTest {
    private val privateAuthority = syntheticTrustAuthority("Synthetic private CA")
    private val unrelatedAuthority = syntheticTrustAuthority("Synthetic unrelated CA")
    private val bundledAuthority = syntheticTrustAuthority("Synthetic bundled CA")

    @Test
    fun serverIssuedByOperatingSystemTrustedPrivateCaIsAccepted() {
        val trust = desktopTlsTrust(linuxAnchors(privateAuthority), bundled = pkixTrustManagerFor(bundledAuthority))
        assertIs<DesktopCompositeTrustManager>(trust.trustManager)
        assertEquals(DesktopSystemTrustStatus.Active(DesktopSystemTrustSource.LinuxCaBundle), trust.systemTrust)
        withSyntheticTlsServer(syntheticLeaf("localhost", privateAuthority)) { url ->
            assertEquals("trusted", fetchWithTrust(trust, url))
        }
    }

    @Test
    fun bundledRootsRemainTrustedAlongsideOperatingSystemAnchors() {
        val trust = desktopTlsTrust(linuxAnchors(privateAuthority), bundled = pkixTrustManagerFor(bundledAuthority))
        withSyntheticTlsServer(syntheticLeaf("localhost", bundledAuthority)) { url ->
            assertEquals("trusted", fetchWithTrust(trust, url))
        }
    }

    @Test
    fun serverIssuedByAnUntrustedCaIsRejected() {
        val trust = desktopTlsTrust(linuxAnchors(privateAuthority), bundled = pkixTrustManagerFor(bundledAuthority))
        withSyntheticTlsServer(syntheticLeaf("localhost", unrelatedAuthority)) { url ->
            val failure = assertFailsWith<SSLHandshakeException> { fetchWithTrust(trust, url) }
            // Both trust sources were consulted and both rejected the chain.
            assertEquals(1, rejectedCertificate(failure).suppressed.size)
        }
    }

    @Test
    fun hostnameMismatchIsRejectedEvenWhenTheCaIsTrusted() {
        val trust = desktopTlsTrust(linuxAnchors(privateAuthority), bundled = pkixTrustManagerFor(bundledAuthority))
        withSyntheticTlsServer(syntheticLeaf("nextcloud.invalid", privateAuthority)) { url ->
            assertFailsWith<SSLPeerUnverifiedException> { fetchWithTrust(trust, url) }
        }
    }

    @Test
    fun expiredLeafFromATrustedCaIsRejected() {
        val trust = desktopTlsTrust(linuxAnchors(privateAuthority), bundled = pkixTrustManagerFor(bundledAuthority))
        withSyntheticTlsServer(syntheticExpiredLeaf("localhost", privateAuthority)) { url ->
            assertFailsWith<SSLHandshakeException> { fetchWithTrust(trust, url) }
        }
    }

    @Test
    fun malformedOrMissingBundleFallsBackToBundledTrustOnly() = withTrustTestDirectory { directory ->
        val malformed = directory.resolve("ca-certificates.crt").also {
            Files.writeString(it, "-----BEGIN CERTIFICATE-----\nnot base64\n-----END CERTIFICATE-----\n")
        }
        val bundled = pkixTrustManagerFor(bundledAuthority)
        for (anchors in listOf(
            loadLinuxSystemCaBundle(listOf(malformed)),
            loadLinuxSystemCaBundle(listOf(directory.resolve("missing.crt"))),
        )) {
            val trust = desktopTlsTrust(linuxBundleTrust(anchors), bundled = bundled)
            assertIs<DesktopSystemTrustStatus.Unavailable>(trust.systemTrust)
            assertSame(bundled, trust.trustManager)
            withSyntheticTlsServer(syntheticLeaf("localhost", privateAuthority)) { url ->
                assertFailsWith<SSLHandshakeException> { fetchWithTrust(trust, url) }
            }
            withSyntheticTlsServer(syntheticLeaf("localhost", bundledAuthority)) { url ->
                assertEquals("trusted", fetchWithTrust(trust, url))
            }
        }
    }

    @Test
    fun acceptedIssuersIncludeBothAnchorSetsForChainCleaning() {
        val trust = desktopTlsTrust(linuxAnchors(privateAuthority), bundled = pkixTrustManagerFor(bundledAuthority))
        val issuers = trust.trustManager.acceptedIssuers.toSet()
        assertEquals(setOf(bundledAuthority.certificate, privateAuthority.certificate), issuers)
        trust.trustManager.acceptedIssuers[0] = unrelatedAuthority.certificate
        assertEquals(issuers, trust.trustManager.acceptedIssuers.toSet())
    }

    @Test
    fun windowsVerifierDecidesOnlyAfterTheBundledRootsRejectAChain() {
        val presented = CopyOnWriteArrayList<List<ByteArray>>()
        val trust = desktopTlsTrust(
            windowsTrust { chain -> presented.add(chain); WindowsChainVerdict.Trusted },
            bundled = pkixTrustManagerFor(bundledAuthority),
        )
        assertEquals(DesktopSystemTrustStatus.Active(DesktopSystemTrustSource.WindowsChainEngine), trust.systemTrust)
        withSyntheticTlsServer(syntheticLeaf("localhost", bundledAuthority)) { url ->
            assertEquals("trusted", fetchWithTrust(trust, url))
        }
        assertTrue(presented.isEmpty())

        val leaf = syntheticLeaf("localhost", privateAuthority)
        withSyntheticTlsServer(leaf) { url ->
            assertEquals("trusted", fetchWithTrust(trust, url))
        }
        assertEquals(1, presented.size)
        assertContentEquals(leaf.certificate.encoded, presented.single().first())
    }

    @Test
    fun windowsRejectionOrNativeFailureRejectsTheServer() {
        for (verdict in listOf(
            WindowsChainVerdict.Rejected(trustErrorStatus = 0x20, policyError = 0x800B0109.toInt()),
            WindowsChainVerdict.Failed(win32Error = 87),
        )) {
            val trust = desktopTlsTrust(windowsTrust { verdict }, bundled = pkixTrustManagerFor(bundledAuthority))
            withSyntheticTlsServer(syntheticLeaf("localhost", privateAuthority)) { url ->
                val failure = assertFailsWith<SSLHandshakeException> { fetchWithTrust(trust, url) }
                assertEquals(1, rejectedCertificate(failure).suppressed.size)
            }
        }
    }

    @Test
    fun windowsTrustStillLeavesHostnameVerificationToOkHttp() {
        val trust = desktopTlsTrust(windowsTrust { WindowsChainVerdict.Trusted }, bundled = pkixTrustManagerFor(bundledAuthority))
        withSyntheticTlsServer(syntheticLeaf("nextcloud.invalid", privateAuthority)) { url ->
            assertFailsWith<SSLPeerUnverifiedException> { fetchWithTrust(trust, url) }
        }
    }

    @Test
    fun windowsTrustManagerRejectsEmptyOversizedAndClientChainsWithoutCallingWindows() {
        var calls = 0
        val manager = WindowsChainEngineTrustManager { calls++; WindowsChainVerdict.Trusted }
        val leaf = syntheticLeaf("localhost", privateAuthority).certificate
        assertFailsWith<CertificateException> { manager.checkServerTrusted(emptyArray(), "ECDHE_ECDSA") }
        assertFailsWith<CertificateException> { manager.checkServerTrusted(null, "ECDHE_ECDSA") }
        assertFailsWith<CertificateException> {
            manager.checkServerTrusted(Array(MAX_WINDOWS_VERIFIED_CHAIN_LENGTH + 1) { leaf }, "ECDHE_ECDSA")
        }
        assertFailsWith<CertificateException> { manager.checkClientTrusted(arrayOf(leaf), "ECDHE_ECDSA") }
        assertEquals(0, calls)
        assertEquals(0, manager.acceptedIssuers.size)
    }

    @Test
    fun unsupportedPlatformsUseOnlyTheBundledRoots() {
        val system = loadDesktopSystemTrust(osName = "Mac OS X")
        assertEquals(DesktopSystemTrust.Unavailable(DesktopSystemTrustUnavailableReason.UnsupportedPlatform), system)
        val bundled = pkixTrustManagerFor(bundledAuthority)
        assertSame(bundled, desktopTlsTrust(system, bundled = bundled).trustManager)
    }

    @Test
    fun sharedDesktopTrustBuildsOnTheTestHost() {
        val client = OkHttpClient.Builder().useDesktopSystemTrust().build()
        assertTrue(client.x509TrustManager != null)
    }

    private fun linuxAnchors(vararg authorities: HeldCertificate): DesktopSystemTrust =
        linuxBundleTrust(DesktopSystemTrustAnchors.Loaded(authorities.map { it.certificate }))

    private fun windowsTrust(verifier: WindowsServerChainVerifier): DesktopSystemTrust =
        DesktopSystemTrust.Available(DesktopSystemTrustSource.WindowsChainEngine, WindowsChainEngineTrustManager(verifier))

    private fun rejectedCertificate(failure: Throwable): CertificateException =
        generateSequence(failure) { it.cause }.filterIsInstance<CertificateException>().first()
}
