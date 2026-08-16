package dev.obiente.nextcloudnative.app

import android.content.Context
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Applies Android's normal trust policy first, then one exact certificate explicitly approved for
 * one HTTPS origin. Hostname verification remains OkHttp's responsibility and is never disabled.
 */
fun OkHttpClient.Builder.useAndroidNextcloudCertificateTrust(context: Context): OkHttpClient.Builder {
    val registry = AndroidServerCertificateTrustRegistry.get(context.applicationContext)
    return sslSocketFactory(registry.sslSocketFactory, registry.trustManager)
        .connectionPool(registry.connectionPool)
        .addInterceptor(AndroidInitialTransportInterceptor())
        .addNetworkInterceptor(AndroidCleartextOriginInterceptor())
}

private data class AndroidApprovedPlainHttpOrigin(
    val host: String,
    val port: Int,
)

private class AndroidInitialTransportInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val approvedOrigin = request.url.takeIf { it.scheme == "http" }?.let {
            AndroidApprovedPlainHttpOrigin(it.host, it.port)
        }
        return chain.proceed(
            request.newBuilder()
                .tag(AndroidApprovedPlainHttpOrigin::class.java, approvedOrigin)
                .build(),
        )
    }
}

private class AndroidCleartextOriginInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val approvedOrigin = request.tag(AndroidApprovedPlainHttpOrigin::class.java)
        if (!androidTransportRequestAllowed(approvedOrigin?.host, approvedOrigin?.port, request.url)) {
            throw IOException("A secure Nextcloud request refused a cleartext redirect.")
        }
        return chain.proceed(request)
    }
}

internal fun androidTransportRequestAllowed(
    approvedPlainHttpHost: String?,
    approvedPlainHttpPort: Int?,
    requestUrl: HttpUrl,
): Boolean = requestUrl.scheme == "https" || (
    requestUrl.scheme == "http" &&
        requestUrl.host == approvedPlainHttpHost &&
        requestUrl.port == approvedPlainHttpPort
    )

object AndroidServerCertificateTrust {
    fun isCertificateFailure(failure: Throwable): Boolean = generateSequence(failure) { it.cause }
        .take(16)
        .any { cause ->
            cause is CertificateException ||
                cause is SSLHandshakeException ||
                cause.javaClass.name == "java.security.cert.CertPathValidatorException"
        }

    fun inspect(serverUrl: String): ServerCertificateReview {
        val origin = serverUrl.requireHttpsOrigin()
        val certificate = probe(origin)
        require(certificate.isSelfSigned()) {
            "The server presented an untrusted certificate chain, not a self-signed certificate. " +
                "Install its issuing certificate authority in Android instead."
        }
        return certificate.toReview(origin)
    }

    fun approve(context: Context, review: ServerCertificateReview) {
        val origin = review.serverOrigin.requireHttpsOrigin()
        val currentlyPresented = probe(origin)
        check(currentlyPresented.isSelfSigned()) {
            "The server no longer presents the reviewed self-signed certificate."
        }
        val currentFingerprint = currentlyPresented.sha256Fingerprint()
        check(constantTimeEquals(currentFingerprint, review.sha256Fingerprint)) {
            "The server certificate changed before it could be trusted. Review the new certificate before connecting."
        }
        AndroidServerCertificateTrustRegistry.get(context.applicationContext)
            .store
            .put(origin, currentFingerprint)
    }

    fun trustedCertificate(context: Context, serverUrl: String): TrustedServerCertificate? {
        val origin = serverUrl.toHttpsOriginOrNull() ?: return null
        return AndroidServerCertificateTrustRegistry.get(context.applicationContext)
            .store
            .get(origin)
            ?.let(::TrustedServerCertificate)
    }

    fun revoke(context: Context, serverUrl: String): Boolean {
        val origin = serverUrl.toHttpsOriginOrNull() ?: return false
        val registry = AndroidServerCertificateTrustRegistry.get(context.applicationContext)
        val removed = registry.store.remove(origin)
        if (removed) registry.connectionPool.evictAll()
        return removed
    }

    private fun probe(origin: HttpUrl): X509Certificate {
        val capture = CapturingTrustManager()
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(capture), null)
        }
        val plainSocket = Socket()
        val socket = try {
            plainSocket.connect(InetSocketAddress(origin.host, origin.port), CERTIFICATE_PROBE_TIMEOUT_MILLIS)
            plainSocket.soTimeout = CERTIFICATE_PROBE_TIMEOUT_MILLIS
            context.socketFactory.createSocket(
                plainSocket,
                origin.host,
                origin.port,
                true,
            ) as SSLSocket
        } catch (failure: Throwable) {
            runCatching { plainSocket.close() }
            throw failure
        }
        socket.use { tlsSocket ->
            tlsSocket.soTimeout = CERTIFICATE_PROBE_TIMEOUT_MILLIS
            tlsSocket.sslParameters = tlsSocket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            tlsSocket.startHandshake()
            val certificate = capture.chain?.firstOrNull()
                ?: (tlsSocket.session.peerCertificates.firstOrNull() as? X509Certificate)
                ?: throw CertificateException("The server did not present an X.509 certificate.")
            certificate.checkValidity()
            check(HttpsURLConnection.getDefaultHostnameVerifier().verify(origin.host, tlsSocket.session)) {
                "The certificate does not match ${origin.host}."
            }
            return certificate
        }
    }
}

private class AndroidServerCertificateTrustRegistry private constructor(context: Context) {
    val store = AndroidServerCertificateTrustStore(context)
    val connectionPool = ConnectionPool()
    val trustManager = ExplicitServerCertificateTrustManager(platformTrustManager(), store)
    val sslSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), null)
    }.socketFactory

    companion object {
        @Volatile
        private var instance: AndroidServerCertificateTrustRegistry? = null

        fun get(context: Context): AndroidServerCertificateTrustRegistry =
            instance ?: synchronized(this) {
                instance ?: AndroidServerCertificateTrustRegistry(context.applicationContext).also {
                    instance = it
                }
            }
    }
}

private class AndroidServerCertificateTrustStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)

    fun get(origin: HttpUrl): String? = preferences.getString(origin.storageKey(), null)

    fun put(origin: HttpUrl, fingerprint: String) {
        check(preferences.edit().putString(origin.storageKey(), fingerprint).commit()) {
            "The trusted certificate could not be saved."
        }
    }

    fun remove(origin: HttpUrl): Boolean {
        val key = origin.storageKey()
        if (!preferences.contains(key)) return false
        return preferences.edit().remove(key).commit()
    }
}

private class ExplicitServerCertificateTrustManager(
    private val platform: X509TrustManager,
    private val store: AndroidServerCertificateTrustStore,
) : X509ExtendedTrustManager() {
    private val extendedPlatform = platform as? X509ExtendedTrustManager

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        platform.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = extendedPlatform?.checkClientTrusted(chain, authType, socket)
        ?: platform.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = extendedPlatform?.checkClientTrusted(chain, authType, engine)
        ?: platform.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        platform.checkServerTrusted(chain, authType)

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) {
        try {
            extendedPlatform?.checkServerTrusted(chain, authType, socket)
                ?: platform.checkServerTrusted(chain, authType)
        } catch (failure: CertificateException) {
            val sslSocket = socket as? SSLSocket ?: throw failure
            checkExplicitTrust(chain, sslSocket.handshakeSession, failure)
        }
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) {
        try {
            extendedPlatform?.checkServerTrusted(chain, authType, engine)
                ?: platform.checkServerTrusted(chain, authType)
        } catch (failure: CertificateException) {
            checkExplicitTrust(chain, engine?.handshakeSession, failure)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    private fun checkExplicitTrust(
        chain: Array<out X509Certificate>?,
        session: SSLSession?,
        platformFailure: CertificateException,
    ) {
        val leaf = chain?.firstOrNull() ?: throw platformFailure
        val host = session?.peerHost?.takeIf(String::isNotBlank) ?: throw platformFailure
        val port = session.peerPort.takeIf { it in 1..65_535 } ?: throw platformFailure
        val origin = httpsOrigin(host, port)
        val expected = store.get(origin) ?: throw platformFailure
        leaf.checkValidity()
        if (!leaf.isSelfSigned()) throw platformFailure
        if (!constantTimeEquals(expected, leaf.sha256Fingerprint())) throw platformFailure
    }
}

private class CapturingTrustManager : X509ExtendedTrustManager() {
    @Volatile
    var chain: Array<out X509Certificate>? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = rejectClientCertificate()
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) = rejectClientCertificate()
    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) = rejectClientCertificate()
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = capture(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = capture(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = capture(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun capture(presented: Array<out X509Certificate>?) {
        if (presented.isNullOrEmpty()) throw CertificateException("The server did not present a certificate chain.")
        chain = presented.copyOf()
    }

    private fun rejectClientCertificate(): Nothing =
        throw CertificateException("The certificate probe does not accept client certificates.")
}

private fun platformTrustManager(): X509TrustManager {
    val androidCertificateStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(androidCertificateStore)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
        ?: error("Android did not provide a single X.509 trust manager.")
}

private fun String.requireHttpsOrigin(): HttpUrl = toHttpsOriginOrNull()
    ?: throw IllegalArgumentException("Enter a valid HTTPS Nextcloud server address.")

private fun String.toHttpsOriginOrNull(): HttpUrl? {
    val value = trim().let { if ("://" in it) it else "https://$it" }
    val parsed = value.toHttpUrlOrNull() ?: return null
    if (!parsed.isHttps) return null
    return httpsOrigin(parsed.host, parsed.port)
}

private fun httpsOrigin(host: String, port: Int): HttpUrl = HttpUrl.Builder()
    .scheme("https")
    .host(host)
    .port(port)
    .build()

private fun HttpUrl.storageKey(): String = "origin_" +
    MessageDigest.getInstance("SHA-256")
        .digest("$host:$port".toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private fun X509Certificate.sha256Fingerprint(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(encoded)
        .joinToString(":") { byte -> "%02X".format(byte) }

private fun X509Certificate.isSelfSigned(): Boolean {
    if (subjectX500Principal != issuerX500Principal) return false
    return runCatching { verify(publicKey) }.isSuccess
}

private fun X509Certificate.toReview(origin: HttpUrl): ServerCertificateReview = ServerCertificateReview(
    serverOrigin = origin.toString().removeSuffix("/"),
    serverDisplayName = if (origin.port == 443) origin.host else "${origin.host}:${origin.port}",
    subject = subjectX500Principal.name.toSafeCertificateIdentity(),
    issuer = issuerX500Principal.name.toSafeCertificateIdentity(),
    sha256Fingerprint = sha256Fingerprint(),
    validFrom = notBefore.toInstant().atOffset(ZoneOffset.UTC).format(CERTIFICATE_DATE_FORMAT),
    validUntil = notAfter.toInstant().atOffset(ZoneOffset.UTC).format(CERTIFICATE_DATE_FORMAT),
)

private fun String.toSafeCertificateIdentity(): String = asSequence()
    .filter { character ->
        Character.getType(character) !in UNSAFE_CERTIFICATE_CHARACTER_TYPES
    }
    .joinToString("")
    .trim()
    .take(MAX_CERTIFICATE_IDENTITY_CHARACTERS)
    .ifBlank { "Not provided" }

private fun constantTimeEquals(first: String, second: String): Boolean = MessageDigest.isEqual(
    first.toByteArray(Charsets.US_ASCII),
    second.toByteArray(Charsets.US_ASCII),
)

private val CERTIFICATE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
private val UNSAFE_CERTIFICATE_CHARACTER_TYPES = setOf(
    Character.CONTROL.toInt(),
    Character.FORMAT.toInt(),
    Character.PRIVATE_USE.toInt(),
    Character.SURROGATE.toInt(),
    Character.UNASSIGNED.toInt(),
)
private const val CERTIFICATE_PROBE_TIMEOUT_MILLIS = 10_000
private const val MAX_CERTIFICATE_IDENTITY_CHARACTERS = 512
private const val PREFERENCE_NAME = "nextcloud_native_explicit_certificate_trust_v1"
