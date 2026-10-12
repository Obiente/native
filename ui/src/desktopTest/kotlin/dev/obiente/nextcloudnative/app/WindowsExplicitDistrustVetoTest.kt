package dev.obiente.nextcloudnative.app

import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A chain the bundled roots accept is still rejected when Windows explicitly distrusts it, but an
 * unknown Windows root or a failed native call keeps the bundled-only decision.
 */
class WindowsExplicitDistrustVetoTest {
    private val bundledAuthority = syntheticTrustAuthority("Synthetic bundled CA")
    private val privateAuthority = syntheticTrustAuthority("Synthetic private CA")

    @Test
    fun explicitWindowsDistrustOverridesBundledAcceptance() {
        for (verdict in listOf(
            WindowsChainVerdict.Rejected(CERT_TRUST_IS_EXPLICIT_DISTRUST, TRUST_E_EXPLICIT_DISTRUST),
            WindowsChainVerdict.Rejected(CERT_TRUST_IS_NOT_VALID_FOR_USAGE, CERT_E_WRONG_USAGE),
            WindowsChainVerdict.Rejected(CERT_TRUST_IS_REVOKED, CERT_E_REVOKED),
            WindowsChainVerdict.Rejected(trustErrorStatus = 0, policyError = CERT_E_WRONG_USAGE),
        )) {
            val trust = trustWithWindowsVerdict { verdict }
            withSyntheticTlsServer(syntheticLeaf("localhost", bundledAuthority)) { url ->
                val failure = assertFailsWith<SSLHandshakeException>(verdict.toString()) { fetchWithTrust(trust, url) }
                assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { "explicitly distrusts" in it.message.orEmpty() })
            }
        }
    }

    @Test
    fun unknownRootTrustedVerdictAndNativeFailureKeepBundledAcceptance() {
        for (verdict in listOf(
            WindowsChainVerdict.Rejected(CERT_TRUST_IS_UNTRUSTED_ROOT, CERT_E_UNTRUSTEDROOT),
            WindowsChainVerdict.Rejected(CERT_TRUST_IS_PARTIAL_CHAIN, CERT_E_CHAINING),
            WindowsChainVerdict.Rejected(CERT_TRUST_REVOCATION_STATUS_UNKNOWN, CRYPT_E_NO_REVOCATION_CHECK),
            WindowsChainVerdict.Rejected(
                CERT_TRUST_REVOCATION_STATUS_UNKNOWN or CERT_TRUST_IS_OFFLINE_REVOCATION,
                CRYPT_E_REVOCATION_OFFLINE,
            ),
            WindowsChainVerdict.Failed(win32Error = 87),
            WindowsChainVerdict.Trusted,
        )) {
            val calls = AtomicInteger()
            val trust = trustWithWindowsVerdict { calls.incrementAndGet(); verdict }
            withSyntheticTlsServer(syntheticLeaf("localhost", bundledAuthority)) { url ->
                assertEquals("trusted", fetchWithTrust(trust, url), verdict.toString())
            }
            assertEquals(1, calls.get(), "The veto consults Windows once per bundled acceptance.")
        }
    }

    @Test
    fun unknownOrOfflineRevocationAloneNeverRejectsAChain() {
        val unknown = CERT_TRUST_REVOCATION_STATUS_UNKNOWN or CERT_TRUST_IS_OFFLINE_REVOCATION
        for ((status, policy) in listOf(
            unknown to 0,
            CERT_TRUST_REVOCATION_STATUS_UNKNOWN to CRYPT_E_NO_REVOCATION_CHECK,
            unknown to CRYPT_E_REVOCATION_OFFLINE,
        )) {
            assertEquals(WindowsChainVerdict.Trusted, windowsChainVerdict(status, policy))
        }
    }

    @Test
    fun definiteRevocationIsRejectedAndVetoedEvenWithOtherwiseUnknownStatus() {
        val verdict = windowsChainVerdict(CERT_TRUST_IS_REVOKED or CERT_TRUST_REVOCATION_STATUS_UNKNOWN, CERT_E_REVOKED)
        val rejected = assertIs<WindowsChainVerdict.Rejected>(verdict)
        assertTrue(rejected.isExplicitWindowsDistrust())
        val trust = trustWithWindowsVerdict { verdict }
        withSyntheticTlsServer(syntheticLeaf("localhost", bundledAuthority)) { url ->
            assertFailsWith<SSLHandshakeException> { fetchWithTrust(trust, url) }
        }
    }

    @Test
    fun otherWindowsErrorsAreRejectedWithoutBecomingAVeto() {
        val unknown = CERT_TRUST_REVOCATION_STATUS_UNKNOWN or CERT_TRUST_IS_OFFLINE_REVOCATION
        val untrusted = assertIs<WindowsChainVerdict.Rejected>(
            windowsChainVerdict(CERT_TRUST_IS_UNTRUSTED_ROOT or unknown, CERT_E_UNTRUSTEDROOT),
        )
        assertFalse(untrusted.isExplicitWindowsDistrust())
        // An unknown-revocation policy code cannot hide a definite chain error.
        assertIs<WindowsChainVerdict.Rejected>(windowsChainVerdict(CERT_TRUST_IS_UNTRUSTED_ROOT, CRYPT_E_REVOCATION_OFFLINE))
    }

    @Test
    fun unknownRevocationDoesNotNewlyFailTheWindowsTrustFallback() {
        val verdict = windowsChainVerdict(CERT_TRUST_REVOCATION_STATUS_UNKNOWN or CERT_TRUST_IS_OFFLINE_REVOCATION, 0)
        val trust = trustWithWindowsVerdict { verdict }
        // The bundled roots reject this private CA; the Windows decision accepts it.
        withSyntheticTlsServer(syntheticLeaf("localhost", privateAuthority)) { url ->
            assertEquals("trusted", fetchWithTrust(trust, url))
        }
    }

    @Test
    fun vetoDoesNotTurnABundledRejectionIntoAcceptance() {
        val trust = trustWithWindowsVerdict { WindowsChainVerdict.Rejected(CERT_TRUST_IS_UNTRUSTED_ROOT, CERT_E_UNTRUSTEDROOT) }
        withSyntheticTlsServer(syntheticLeaf("localhost", privateAuthority)) { url ->
            assertFailsWith<SSLHandshakeException> { fetchWithTrust(trust, url) }
        }
    }

    @Test
    fun vetoIgnoresChainsItCannotEvaluate() {
        var calls = 0
        val veto = WindowsExplicitDistrustVeto { calls++; WindowsChainVerdict.Rejected(CERT_TRUST_IS_EXPLICIT_DISTRUST, 0) }
        val leaf = syntheticLeaf("localhost", bundledAuthority).certificate
        assertNull(veto.explicitDistrust(emptyArray()))
        assertNull(veto.explicitDistrust(Array(MAX_WINDOWS_VERIFIED_CHAIN_LENGTH + 1) { leaf }))
        assertEquals(0, calls)
    }

    private fun trustWithWindowsVerdict(verifier: WindowsServerChainVerifier): DesktopTlsTrust = desktopTlsTrust(
        DesktopSystemTrust.Available(
            DesktopSystemTrustSource.WindowsChainEngine,
            WindowsChainEngineTrustManager(verifier),
            WindowsExplicitDistrustVeto(verifier),
        ),
        bundled = pkixTrustManagerFor(bundledAuthority),
    )
}

private const val CERT_TRUST_IS_UNTRUSTED_ROOT = 0x00000020
private const val CERT_TRUST_IS_PARTIAL_CHAIN = 0x00010000
private val CERT_E_UNTRUSTEDROOT = 0x800B0109.toInt()
private val CERT_E_CHAINING = 0x800B010A.toInt()
private val CERT_E_REVOKED = 0x800B010C.toInt()
private val CERT_E_WRONG_USAGE = 0x800B0110.toInt()
private val TRUST_E_EXPLICIT_DISTRUST = 0x800B0111.toInt()
