package dev.obiente.nextcloudnative.app

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinCrypt
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.lang.ref.Reference
import java.net.Socket
import java.security.cert.CertificateEncodingException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/** Outcome of asking the Windows certificate chain engine about one presented server chain. */
internal sealed interface WindowsChainVerdict {
    data object Trusted : WindowsChainVerdict

    /** Windows evaluated the chain and rejected it. Codes are Win32 trust and policy values. */
    data class Rejected(val trustErrorStatus: Int, val policyError: Int) : WindowsChainVerdict

    /** The native call failed before Windows could evaluate the chain. */
    data class Failed(val win32Error: Int) : WindowsChainVerdict
}

/** Narrow boundary over the native chain engine so policy mapping is testable without Windows. */
internal fun interface WindowsServerChainVerifier {
    fun verify(encodedChain: List<ByteArray>): WindowsChainVerdict
}

/**
 * Operating-system trust for Windows. The Windows chain engine, not a copy of its roots, decides
 * whether a chain is trusted, so roots installed for the user or the machine, the Disallowed store,
 * and purpose restrictions on stored certificates all apply. Hostname verification stays with OkHttp.
 */
internal class WindowsChainEngineTrustManager(
    private val verifier: WindowsServerChainVerifier,
) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        verify(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        verify(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = rejectClient()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        rejectClient()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        rejectClient()

    // Windows roots are not enumerated; the chain engine owns that decision.
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun verify(chain: Array<out X509Certificate>?) {
        if (chain.isNullOrEmpty()) throw CertificateException("The server did not present a certificate chain.")
        if (chain.size > MAX_WINDOWS_VERIFIED_CHAIN_LENGTH) {
            throw CertificateException("The server certificate chain is too long.")
        }
        val encoded = try {
            chain.map { it.encoded }
        } catch (failure: CertificateEncodingException) {
            throw CertificateException("The server certificate chain could not be encoded.", failure)
        }
        when (val verdict = verifier.verify(encoded)) {
            WindowsChainVerdict.Trusted -> Unit
            is WindowsChainVerdict.Rejected -> throw CertificateException(
                "Windows rejected the server certificate chain (trust 0x%08X, policy 0x%08X)."
                    .format(verdict.trustErrorStatus, verdict.policyError),
            )
            is WindowsChainVerdict.Failed -> throw CertificateException(
                "Windows could not evaluate the server certificate chain (error %d).".format(verdict.win32Error),
            )
        }
    }

    private fun rejectClient(): Nothing =
        throw CertificateException("Windows operating-system trust is used only for server certificates.")
}

/** Loads the Windows chain engine trust, or reports why it is unavailable. */
internal fun windowsChainEngineTrust(): DesktopSystemTrust {
    val verifier = try {
        JnaWindowsServerChainVerifier()
    } catch (_: UnsatisfiedLinkError) {
        return DesktopSystemTrust.Unavailable(DesktopSystemTrustUnavailableReason.StoreUnavailable)
    }
    return DesktopSystemTrust.Available(
        DesktopSystemTrustSource.WindowsChainEngine,
        WindowsChainEngineTrustManager(verifier),
    )
}

/**
 * Builds the chain with `CertGetCertificateChain` and checks it with the SSL server policy.
 * [chainEngine] null selects the current user's default engine. Only locally cached data is used:
 * missing intermediates and root updates are never downloaded from URLs a server certificate names.
 */
internal class JnaWindowsServerChainVerifier(
    private val chainEngine: Pointer? = null,
) : WindowsServerChainVerifier {
    private val crypt32: WindowsCrypt32 = WindowsCrypt32.instance

    override fun verify(encodedChain: List<ByteArray>): WindowsChainVerdict {
        require(encodedChain.isNotEmpty())
        val store = crypt32.CertOpenStore(CERT_STORE_PROV_MEMORY, 0, null, 0, null)
            ?: return WindowsChainVerdict.Failed(Native.getLastError())
        var leaf: Pointer? = null
        try {
            encodedChain.forEachIndexed { index, encoded ->
                val context = if (index == 0) PointerByReference() else null
                if (!crypt32.CertAddEncodedCertificateToStore(
                        store, X509_ASN_ENCODING, encoded, encoded.size, CERT_STORE_ADD_ALWAYS, context,
                    )
                ) {
                    return WindowsChainVerdict.Failed(Native.getLastError())
                }
                if (context != null) leaf = context.value
            }
            return verifyLeaf(requireNotNull(leaf), store)
        } finally {
            leaf?.let(crypt32::CertFreeCertificateContext)
            crypt32.CertCloseStore(store, 0)
        }
    }

    private fun verifyLeaf(leaf: Pointer, additionalStore: Pointer): WindowsChainVerdict {
        val serverAuthOid = Memory(SERVER_AUTH_OID.length + 1L).apply { setString(0, SERVER_AUTH_OID, "US-ASCII") }
        val usageArray = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, serverAuthOid) }
        val chainPara = WinCrypt.CERT_CHAIN_PARA().apply {
            cbSize = size()
            RequestedUsage.dwType = USAGE_MATCH_TYPE_AND
            RequestedUsage.Usage.cUsageIdentifier = 1
            RequestedUsage.Usage.rgpszUsageIdentifier = usageArray
        }
        try {
            val chainReference = PointerByReference()
            if (!crypt32.CertGetCertificateChain(
                    chainEngine, leaf, null, additionalStore, chainPara, CHAIN_FLAGS, null, chainReference,
                )
            ) {
                return WindowsChainVerdict.Failed(Native.getLastError())
            }
            val chain = chainReference.value ?: return WindowsChainVerdict.Failed(0)
            try {
                return checkSslPolicy(chain)
            } finally {
                crypt32.CertFreeCertificateChain(chain)
            }
        } finally {
            // The usage array points at native memory that only these objects own.
            Reference.reachabilityFence(serverAuthOid)
            Reference.reachabilityFence(usageArray)
        }
    }

    private fun checkSslPolicy(chain: Pointer): WindowsChainVerdict {
        val sslPara = WindowsSslExtraPolicyPara().apply {
            cbSize = size()
            dwAuthType = AUTHTYPE_SERVER
            // OkHttp verifies the hostname after the handshake.
            fdwChecks = SECURITY_FLAG_IGNORE_CERT_CN_INVALID
            pwszServerName = null
            write()
        }
        val policyPara = WinCrypt.CERT_CHAIN_POLICY_PARA().apply {
            cbSize = size()
            dwFlags = 0
            pvExtraPolicyPara = sslPara.pointer
        }
        val policyStatus = WinCrypt.CERT_CHAIN_POLICY_STATUS().apply { cbSize = size() }
        try {
            if (!crypt32.CertVerifyCertificateChainPolicy(CERT_CHAIN_POLICY_SSL, chain, policyPara, policyStatus)) {
                return WindowsChainVerdict.Failed(Native.getLastError())
            }
        } finally {
            Reference.reachabilityFence(sslPara)
        }
        // CERT_CHAIN_CONTEXT starts with cbSize followed by CERT_TRUST_STATUS.dwErrorStatus.
        val trustErrorStatus = chain.getInt(4)
        return if (trustErrorStatus == 0 && policyStatus.dwError == 0) {
            WindowsChainVerdict.Trusted
        } else {
            WindowsChainVerdict.Rejected(trustErrorStatus, policyStatus.dwError)
        }
    }
}

@Structure.FieldOrder("cbSize", "dwAuthType", "fdwChecks", "pwszServerName")
internal class WindowsSslExtraPolicyPara : Structure() {
    @JvmField var cbSize: Int = 0
    @JvmField var dwAuthType: Int = 0
    @JvmField var fdwChecks: Int = 0
    @JvmField var pwszServerName: Pointer? = null
}

@Suppress("FunctionName")
internal interface WindowsCrypt32 : StdCallLibrary {
    fun CertOpenStore(
        storeProvider: Pointer,
        encodingType: Int,
        cryptProvider: Pointer?,
        flags: Int,
        parameter: Pointer?,
    ): Pointer?

    fun CertAddEncodedCertificateToStore(
        store: Pointer,
        encodingType: Int,
        encoded: ByteArray,
        encodedLength: Int,
        addDisposition: Int,
        context: PointerByReference?,
    ): Boolean

    fun CertGetCertificateChain(
        chainEngine: Pointer?,
        certificateContext: Pointer,
        time: Pointer?,
        additionalStore: Pointer?,
        chainPara: WinCrypt.CERT_CHAIN_PARA,
        flags: Int,
        reserved: Pointer?,
        chainContext: PointerByReference,
    ): Boolean

    fun CertVerifyCertificateChainPolicy(
        policyOid: Pointer,
        chainContext: Pointer,
        policyPara: WinCrypt.CERT_CHAIN_POLICY_PARA,
        policyStatus: WinCrypt.CERT_CHAIN_POLICY_STATUS,
    ): Boolean

    fun CertFreeCertificateChain(chainContext: Pointer)

    fun CertFreeCertificateContext(certificateContext: Pointer): Boolean

    fun CertCloseStore(store: Pointer, flags: Int): Boolean

    companion object {
        val instance: WindowsCrypt32 by lazy { Native.load("crypt32", WindowsCrypt32::class.java) }
    }
}

internal val CERT_STORE_PROV_MEMORY: Pointer = Pointer.createConstant(2)
private val CERT_CHAIN_POLICY_SSL: Pointer = Pointer.createConstant(4)
internal const val X509_ASN_ENCODING = 0x00000001
internal const val CERT_STORE_ADD_ALWAYS = 4
private const val USAGE_MATCH_TYPE_AND = 0
private const val SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1"
private const val AUTHTYPE_SERVER = 2
private const val SECURITY_FLAG_IGNORE_CERT_CN_INVALID = 0x00001000
private const val CERT_CHAIN_CACHE_ONLY_URL_RETRIEVAL = 0x00000004
private const val CERT_CHAIN_DISABLE_AUTH_ROOT_AUTO_UPDATE = 0x00000100
private const val CERT_CHAIN_DISABLE_MD2_MD4 = 0x00001000
private const val CERT_CHAIN_DISABLE_AIA = 0x00002000
private const val CHAIN_FLAGS = CERT_CHAIN_CACHE_ONLY_URL_RETRIEVAL or
    CERT_CHAIN_DISABLE_AUTH_ROOT_AUTO_UPDATE or
    CERT_CHAIN_DISABLE_MD2_MD4 or
    CERT_CHAIN_DISABLE_AIA
internal const val MAX_WINDOWS_VERIFIED_CHAIN_LENGTH = 16
