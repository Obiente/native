package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.net.Socket
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Desktop TLS trust for Nextcloud server traffic: the bundled JVM roots, plus the anchors the
 * operating system trusts. A server chain must still validate completely to one anchor set, and
 * OkHttp's hostname verification is unchanged.
 */
internal class DesktopTlsTrust(
    val trustManager: X509TrustManager,
    val sslSocketFactory: SSLSocketFactory,
    val systemAnchors: DesktopSystemTrustAnchors,
)

/** Applies the process-wide desktop trust. Never use it to relax validation for one request. */
internal fun OkHttpClient.Builder.useDesktopSystemTrust(): OkHttpClient.Builder {
    val trust = sharedDesktopTlsTrust
    return sslSocketFactory(trust.sslSocketFactory, trust.trustManager)
}

private val sharedDesktopTlsTrust: DesktopTlsTrust by lazy {
    desktopTlsTrust(loadDesktopSystemTrustAnchors())
}

internal fun desktopTlsTrust(
    systemAnchors: DesktopSystemTrustAnchors,
    bundled: X509TrustManager = bundledJvmTrustManager(),
): DesktopTlsTrust {
    val system = (systemAnchors as? DesktopSystemTrustAnchors.Loaded)?.let(::anchorTrustManager)
    val extendedBundled = bundled as? X509ExtendedTrustManager
    val effective: X509TrustManager = if (system != null && extendedBundled != null) {
        DesktopCompositeTrustManager(extendedBundled, system)
    } else {
        bundled
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(effective), null) }
    return DesktopTlsTrust(
        trustManager = effective,
        sslSocketFactory = context.socketFactory,
        systemAnchors = when {
            effective !== bundled -> systemAnchors
            systemAnchors is DesktopSystemTrustAnchors.Unavailable -> systemAnchors
            // Anchors were read but could not be composed safely, so only the bundled roots apply.
            else -> DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.StoreUnavailable)
        },
    )
}

/** The JVM default trust, including any `javax.net.ssl.trustStore` override. */
internal fun bundledJvmTrustManager(): X509TrustManager = trustManagerFor(null)
    ?: error("The Java runtime did not provide a single X.509 trust manager.")

private fun anchorTrustManager(anchors: DesktopSystemTrustAnchors.Loaded): X509ExtendedTrustManager? {
    val store = try {
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            anchors.certificates.forEachIndexed { index, certificate ->
                setCertificateEntry("system-anchor-$index", certificate)
            }
        }
    } catch (_: KeyStoreException) {
        return null
    } catch (_: NoSuchAlgorithmException) {
        return null
    } catch (_: CertificateException) {
        return null
    } catch (_: IOException) {
        return null
    }
    return trustManagerFor(store) as? X509ExtendedTrustManager
}

private fun trustManagerFor(store: KeyStore?): X509TrustManager? {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    try {
        factory.init(store)
    } catch (_: KeyStoreException) {
        return null
    }
    return factory.trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
}

/**
 * Accepts a server chain when the bundled roots or the operating-system anchors validate it. Both
 * delegates are full JSSE PKIX trust managers, so algorithm constraints, validity, key usage, and
 * the JDK CA distrust policies still apply. When both reject the chain, the bundled failure is
 * rethrown with the system failure suppressed.
 */
internal class DesktopCompositeTrustManager(
    private val bundled: X509ExtendedTrustManager,
    private val system: X509ExtendedTrustManager,
) : X509ExtendedTrustManager() {
    private val acceptedIssuers: Array<X509Certificate> =
        (bundled.acceptedIssuers.asSequence() + system.acceptedIssuers.asSequence())
            .distinctBy { Base64.getEncoder().encodeToString(it.encoded) }
            .toList()
            .toTypedArray()

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        firstAccepting(
            { bundled.checkServerTrusted(chain, authType) },
            { system.checkServerTrusted(chain, authType) },
        )

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        firstAccepting(
            { bundled.checkServerTrusted(chain, authType, socket) },
            { system.checkServerTrusted(chain, authType, socket) },
        )

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        firstAccepting(
            { bundled.checkServerTrusted(chain, authType, engine) },
            { system.checkServerTrusted(chain, authType, engine) },
        )

    // The desktop client never accepts inbound TLS clients; keep the JVM default decision.
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        bundled.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        bundled.checkClientTrusted(chain, authType, socket)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        bundled.checkClientTrusted(chain, authType, engine)

    override fun getAcceptedIssuers(): Array<X509Certificate> = acceptedIssuers.copyOf()

    private inline fun firstAccepting(bundledCheck: () -> Unit, systemCheck: () -> Unit) {
        try {
            bundledCheck()
        } catch (bundledFailure: CertificateException) {
            try {
                systemCheck()
            } catch (systemFailure: CertificateException) {
                bundledFailure.addSuppressed(systemFailure)
                throw bundledFailure
            }
        }
    }
}
