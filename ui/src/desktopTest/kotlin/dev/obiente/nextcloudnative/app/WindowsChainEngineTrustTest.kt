package dev.obiente.nextcloudnative.app

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.lang.ref.Reference
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okhttp3.tls.HeldCertificate

/**
 * Exercises the real Windows chain engine on a Windows test host. Synthetic roots live only in an
 * in-memory exclusive-root engine, so no user or machine certificate store is read for trust or
 * modified. Other hosts return early; the policy mapping is covered by [DesktopTlsTrustTest].
 */
class WindowsChainEngineTrustTest {
    private val privateAuthority = syntheticTrustAuthority("Synthetic Windows private CA")
    private val unrelatedAuthority = syntheticTrustAuthority("Synthetic Windows unrelated CA")

    @Test
    fun chainToAnEngineTrustedRootIsAccepted() = withExclusiveRootEngine(privateAuthority) { verifier ->
        val leaf = syntheticLeaf("localhost", privateAuthority)
        assertEquals(WindowsChainVerdict.Trusted, verifier.verify(listOf(leaf.certificate.encoded)))
    }

    @Test
    fun chainToAnotherRootIsRejected() = withExclusiveRootEngine(privateAuthority) { verifier ->
        val leaf = syntheticLeaf("localhost", unrelatedAuthority)
        assertIs<WindowsChainVerdict.Rejected>(verifier.verify(listOf(leaf.certificate.encoded)))
    }

    @Test
    fun expiredLeafIsRejected() = withExclusiveRootEngine(privateAuthority) { verifier ->
        val leaf = syntheticExpiredLeaf("localhost", privateAuthority)
        val verdict = assertIs<WindowsChainVerdict.Rejected>(verifier.verify(listOf(leaf.certificate.encoded)))
        assertTrue(verdict.trustErrorStatus and CERT_TRUST_IS_NOT_TIME_VALID != 0)
    }

    @Test
    fun rootWhoseStorePurposeExcludesServerAuthenticationIsRejected() =
        withExclusiveRootEngine(privateAuthority, rootPurpose = CODE_SIGNING_ONLY_EKU) { verifier ->
            val leaf = syntheticLeaf("localhost", privateAuthority)
            val verdict = assertIs<WindowsChainVerdict.Rejected>(verifier.verify(listOf(leaf.certificate.encoded)))
            assertTrue(verdict.trustErrorStatus and CERT_TRUST_IS_NOT_VALID_FOR_USAGE != 0)
        }

    @Test
    fun currentUserEngineDoesNotTrustAnUninstalledSyntheticRoot() {
        if (!isWindowsHost()) return
        val leaf = syntheticLeaf("localhost", privateAuthority)
        assertIs<WindowsChainVerdict.Rejected>(JnaWindowsServerChainVerifier().verify(listOf(leaf.certificate.encoded)))
    }

    @Test
    fun handshakeUsesTheWindowsDecisionAndKeepsHostnameVerification() =
        withExclusiveRootEngine(privateAuthority) { verifier ->
            val trust = desktopTlsTrust(
                DesktopSystemTrust.Available(
                    DesktopSystemTrustSource.WindowsChainEngine,
                    WindowsChainEngineTrustManager(verifier),
                ),
                bundled = pkixTrustManagerFor(unrelatedAuthority),
            )
            withSyntheticTlsServer(syntheticLeaf("localhost", privateAuthority)) { url ->
                assertEquals("trusted", fetchWithTrust(trust, url))
            }
            withSyntheticTlsServer(syntheticLeaf("nextcloud.invalid", privateAuthority)) { url ->
                assertFailsWith<SSLPeerUnverifiedException> { fetchWithTrust(trust, url) }
            }
            withSyntheticTlsServer(syntheticExpiredLeaf("localhost", privateAuthority)) { url ->
                assertFailsWith<SSLHandshakeException> { fetchWithTrust(trust, url) }
            }
        }

    private fun withExclusiveRootEngine(
        root: HeldCertificate,
        rootPurpose: ByteArray? = null,
        block: (WindowsServerChainVerifier) -> Unit,
    ) {
        if (!isWindowsHost()) return
        val crypt32 = WindowsCrypt32.instance
        val testCrypt32 = TestCrypt32.instance
        val rootStore = requireNotNull(crypt32.CertOpenStore(CERT_STORE_PROV_MEMORY, 0, null, 0, null))
        try {
            val encoded = root.certificate.encoded
            val rootContext = PointerByReference()
            check(crypt32.CertAddEncodedCertificateToStore(
                rootStore, X509_ASN_ENCODING, encoded, encoded.size, CERT_STORE_ADD_ALWAYS, rootContext,
            ))
            try {
                if (rootPurpose != null) setEnhancedKeyUsage(testCrypt32, rootContext.value, rootPurpose)
            } finally {
                crypt32.CertFreeCertificateContext(rootContext.value)
            }
            val config = ChainEngineConfig().apply {
                cbSize = size()
                hExclusiveRoot = rootStore
            }
            val engine = PointerByReference()
            check(testCrypt32.CertCreateCertificateChainEngine(config, engine)) {
                "CertCreateCertificateChainEngine failed: ${Native.getLastError()}"
            }
            try {
                block(JnaWindowsServerChainVerifier(chainEngine = engine.value))
            } finally {
                testCrypt32.CertFreeCertificateChainEngine(engine.value)
            }
        } finally {
            crypt32.CertCloseStore(rootStore, 0)
        }
    }

    private fun setEnhancedKeyUsage(crypt32: TestCrypt32, context: Pointer, encodedUsage: ByteArray) {
        val data = Memory(encodedUsage.size.toLong()).apply { write(0, encodedUsage, 0, encodedUsage.size) }
        // CRYPT_DATA_BLOB: DWORD cbData, then a pointer aligned to the pointer size.
        val blob = Memory(2L * Native.POINTER_SIZE).apply {
            clear()
            setInt(0, encodedUsage.size)
            setPointer(Native.POINTER_SIZE.toLong(), data)
        }
        check(crypt32.CertSetCertificateContextProperty(context, CERT_ENHKEY_USAGE_PROP_ID, 0, blob)) {
            "CertSetCertificateContextProperty failed: ${Native.getLastError()}"
        }
        Reference.reachabilityFence(data)
    }

    @Structure.FieldOrder(
        "cbSize", "hRestrictedRoot", "hRestrictedTrust", "hRestrictedOther", "cAdditionalStore",
        "rghAdditionalStore", "dwFlags", "dwUrlRetrievalTimeout", "MaximumCachedCertificates",
        "CycleDetectionModulus", "hExclusiveRoot", "hExclusiveTrustedPeople", "dwExclusiveFlags",
    )
    class ChainEngineConfig : Structure() {
        @JvmField var cbSize: Int = 0
        @JvmField var hRestrictedRoot: Pointer? = null
        @JvmField var hRestrictedTrust: Pointer? = null
        @JvmField var hRestrictedOther: Pointer? = null
        @JvmField var cAdditionalStore: Int = 0
        @JvmField var rghAdditionalStore: Pointer? = null
        @JvmField var dwFlags: Int = 0
        @JvmField var dwUrlRetrievalTimeout: Int = 0
        @JvmField var MaximumCachedCertificates: Int = 0
        @JvmField var CycleDetectionModulus: Int = 0
        @JvmField var hExclusiveRoot: Pointer? = null
        @JvmField var hExclusiveTrustedPeople: Pointer? = null
        @JvmField var dwExclusiveFlags: Int = 0
    }

    @Suppress("FunctionName")
    interface TestCrypt32 : StdCallLibrary {
        fun CertCreateCertificateChainEngine(config: ChainEngineConfig, engine: PointerByReference): Boolean

        fun CertFreeCertificateChainEngine(engine: Pointer)

        fun CertSetCertificateContextProperty(context: Pointer, propertyId: Int, flags: Int, data: Pointer): Boolean

        companion object {
            val instance: TestCrypt32 by lazy { Native.load("crypt32", TestCrypt32::class.java) }
        }
    }

    private fun isWindowsHost(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}

private const val CERT_ENHKEY_USAGE_PROP_ID = 9
private const val CERT_TRUST_IS_NOT_TIME_VALID = 0x00000001
private const val CERT_TRUST_IS_NOT_VALID_FOR_USAGE = 0x00000010

/** DER EnhancedKeyUsage SEQUENCE containing only id-kp-codeSigning (1.3.6.1.5.5.7.3.3). */
private val CODE_SIGNING_ONLY_EKU = byteArrayOf(
    0x30, 0x0A, 0x06, 0x08, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x03,
)
