package dev.obiente.nextcloudnative.app

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request

class JvmNetworkResponseTruncatedIOException :
    IOException("The network response ended before the expected byte count.")

fun ByteArray.requireExactJvmNetworkResponseBytes(expectedBytes: Int): ByteArray {
    require(expectedBytes >= 0)
    if (size < expectedBytes) throw JvmNetworkResponseTruncatedIOException()
    return this
}

enum class JvmNetworkFailurePhase(val storageValue: String) {
    Unknown("unknown"),
    Dns("dns"),
    Connect("connect"),
    Tls("tls"),
    RequestHeaders("request_headers"),
    RequestBody("request_body"),
    ResponseHeaders("response_headers"),
    ResponseBody("response_body"),
}

class JvmNetworkRequestAttempt {
    @Volatile
    var phase: JvmNetworkFailurePhase = JvmNetworkFailurePhase.Unknown
        private set

    @Volatile
    var protocol: String? = null
        private set

    @Volatile
    var exchangeStarted: Boolean = false
        private set

    @Volatile
    var cancelled: Boolean = false
        private set

    @Volatile
    var attempts: Int = 1
        private set

    internal fun markPhase(value: JvmNetworkFailurePhase) {
        phase = value
    }

    internal fun markConnection(connection: Connection) {
        protocol = connection.protocol().toString().takeIf { it in SAFE_NETWORK_PROTOCOLS }
    }

    internal fun markExchangeStarted() {
        exchangeStarted = true
    }

    internal fun markCancelled() {
        cancelled = true
    }

    internal fun markRetry() {
        if (attempts < MAX_DIAGNOSTIC_NETWORK_ATTEMPTS) attempts += 1
    }
}

data class JvmNetworkFailureDiagnostic(
    val code: String,
    val phase: JvmNetworkFailurePhase,
    val retryable: Boolean,
    val attempt: Int,
    val exchangeStarted: Boolean,
    val protocol: String?,
    val http2Error: String? = null,
) {
    val isCancellation: Boolean
        get() = code == "NETWORK_CANCELLED"

    fun fields(): List<SupportDiagnosticFieldDraft> = buildList {
        add(SupportDiagnosticFieldDraft("failure_phase", phase.storageValue))
        add(SupportDiagnosticFieldDraft("retryable", retryable.toString()))
        add(SupportDiagnosticFieldDraft("exchange_started", exchangeStarted.toString()))
        protocol?.let { add(SupportDiagnosticFieldDraft("protocol", it)) }
        http2Error?.let { add(SupportDiagnosticFieldDraft("http2_error", it)) }
    }
}

fun OkHttpClient.Builder.trackJvmNetworkFailures(): OkHttpClient.Builder =
    eventListenerFactory { call ->
        call.request().tag(JvmNetworkRequestAttempt::class.java)
            ?.let(::JvmNetworkEventListener)
            ?: EventListener.NONE
    }

fun String.isReadOnlyJvmNetworkMethod(): Boolean = uppercase() in READ_ONLY_NETWORK_METHODS

fun Throwable.toJvmNetworkFailureDiagnostic(
    attempt: JvmNetworkRequestAttempt,
    readOnlyRequest: Boolean,
    replayableRequest: Boolean,
): JvmNetworkFailureDiagnostic {
    val causes = boundedCauses()
    val http2Reset = causes.firstNotNullOfOrNull(::safeHttp2ResetReason)
    val classified = when {
        attempt.cancelled || causes.any { it is CancellationException } ||
            causes.any { it is IOException && it.message.equals("canceled", ignoreCase = true) } ->
            ClassifiedJvmNetworkFailure("NETWORK_CANCELLED", retryable = false)
        causes.any { it is UnknownHostException } ->
            ClassifiedJvmNetworkFailure("NETWORK_DNS_UNRESOLVED", retryable = true)
        causes.any { it is CertificateException || it is SSLPeerUnverifiedException } ->
            ClassifiedJvmNetworkFailure("NETWORK_CERTIFICATE_REJECTED", retryable = false)
        causes.any { it is SSLHandshakeException } ->
            ClassifiedJvmNetworkFailure("NETWORK_TLS_HANDSHAKE", retryable = false)
        attempt.phase == JvmNetworkFailurePhase.Tls && causes.any { it is SSLException } ->
            ClassifiedJvmNetworkFailure("NETWORK_TLS_HANDSHAKE", retryable = false)
        http2Reset != null ->
            ClassifiedJvmNetworkFailure(
                code = "NETWORK_HTTP2_STREAM_RESET",
                retryable = http2Reset in RETRYABLE_HTTP2_ERRORS,
                http2Error = http2Reset,
            )
        causes.any { it.javaClass.name == OKHTTP_CONNECTION_SHUTDOWN_EXCEPTION } ->
            ClassifiedJvmNetworkFailure("NETWORK_CONNECTION_SHUTDOWN", retryable = true)
        causes.any { it is NoRouteToHostException } ->
            ClassifiedJvmNetworkFailure("NETWORK_UNREACHABLE", retryable = true)
        causes.any { it is ConnectException } ->
            ClassifiedJvmNetworkFailure("NETWORK_CONNECT_FAILED", retryable = true)
        causes.any { it is SocketTimeoutException } ->
            ClassifiedJvmNetworkFailure(attempt.phase.timeoutCode(), retryable = true)
        causes.any { it is JvmNetworkResponseTruncatedIOException } ->
            ClassifiedJvmNetworkFailure("NETWORK_RESPONSE_TRUNCATED", retryable = true)
        causes.any { failure ->
            attempt.phase == JvmNetworkFailurePhase.ResponseBody &&
                failure is ProtocolException &&
                failure.message == OKHTTP_UNEXPECTED_END_OF_STREAM
        } -> ClassifiedJvmNetworkFailure("NETWORK_RESPONSE_TRUNCATED", retryable = true)
        causes.any { it is EOFException } ->
            ClassifiedJvmNetworkFailure("NETWORK_RESPONSE_TRUNCATED", retryable = true)
        causes.any { failure ->
            failure is SocketException && failure.message.orEmpty().contains("reset", ignoreCase = true)
        } -> ClassifiedJvmNetworkFailure("NETWORK_CONNECTION_RESET", retryable = true)
        causes.any { failure ->
            failure is SocketException && failure.message.orEmpty().contains("broken pipe", ignoreCase = true)
        } -> ClassifiedJvmNetworkFailure("NETWORK_WRITE_FAILED", retryable = true)
        causes.any { it is SocketException } ->
            ClassifiedJvmNetworkFailure("NETWORK_SOCKET_FAILED", retryable = true)
        causes.any { it is ProtocolException } ->
            ClassifiedJvmNetworkFailure("NETWORK_PROTOCOL_FAILED", retryable = false)
        causes.any { it is IOException } ->
            ClassifiedJvmNetworkFailure("NETWORK_IO_FAILED", retryable = true)
        else -> ClassifiedJvmNetworkFailure("NETWORK_UNKNOWN_FAILED", retryable = false)
    }
    return JvmNetworkFailureDiagnostic(
        code = classified.code,
        phase = attempt.phase,
        retryable = classified.retryable && readOnlyRequest && replayableRequest,
        attempt = attempt.attempts,
        exchangeStarted = attempt.exchangeStarted,
        protocol = attempt.protocol,
        http2Error = classified.http2Error,
    )
}

private class JvmNetworkEventListener(
    private val attempt: JvmNetworkRequestAttempt,
) : EventListener() {
    override fun dnsStart(call: Call, domainName: String) {
        attempt.markPhase(JvmNetworkFailurePhase.Dns)
    }

    override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
        attempt.markPhase(JvmNetworkFailurePhase.Connect)
    }

    override fun secureConnectStart(call: Call) {
        attempt.markPhase(JvmNetworkFailurePhase.Tls)
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        attempt.markConnection(connection)
    }

    override fun requestHeadersStart(call: Call) {
        attempt.markPhase(JvmNetworkFailurePhase.RequestHeaders)
        attempt.markExchangeStarted()
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        attempt.markPhase(JvmNetworkFailurePhase.ResponseHeaders)
    }

    override fun requestBodyStart(call: Call) {
        attempt.markPhase(JvmNetworkFailurePhase.RequestBody)
        attempt.markExchangeStarted()
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        attempt.markPhase(JvmNetworkFailurePhase.ResponseHeaders)
    }

    override fun responseHeadersStart(call: Call) {
        attempt.markPhase(JvmNetworkFailurePhase.ResponseHeaders)
        attempt.markExchangeStarted()
    }

    override fun responseBodyStart(call: Call) {
        attempt.markPhase(JvmNetworkFailurePhase.ResponseBody)
        attempt.markExchangeStarted()
    }

    override fun canceled(call: Call) {
        attempt.markCancelled()
    }

    override fun retryDecision(call: Call, exception: IOException, retry: Boolean) {
        if (retry) attempt.markRetry()
    }
}

private data class ClassifiedJvmNetworkFailure(
    val code: String,
    val retryable: Boolean,
    val http2Error: String? = null,
)

private fun Throwable.boundedCauses(): List<Throwable> = buildList {
    var current: Throwable? = this@boundedCauses
    while (current != null && size < MAX_DIAGNOSTIC_CAUSE_DEPTH && current !in this) {
        add(current)
        current = current.cause
    }
}

private fun safeHttp2ResetReason(failure: Throwable): String? {
    if (failure.javaClass.name != OKHTTP_STREAM_RESET_EXCEPTION) return null
    val reason = failure.message
        ?.substringAfterLast(':')
        ?.trim()
        ?.takeIf { it in SAFE_HTTP2_ERRORS }
    return reason ?: "UNKNOWN"
}

private fun JvmNetworkFailurePhase.timeoutCode(): String = when (this) {
    JvmNetworkFailurePhase.Dns,
    JvmNetworkFailurePhase.Connect,
    -> "NETWORK_CONNECT_TIMEOUT"
    JvmNetworkFailurePhase.Tls -> "NETWORK_TLS_TIMEOUT"
    JvmNetworkFailurePhase.RequestHeaders,
    JvmNetworkFailurePhase.RequestBody,
    -> "NETWORK_WRITE_TIMEOUT"
    JvmNetworkFailurePhase.ResponseHeaders,
    JvmNetworkFailurePhase.ResponseBody,
    -> "NETWORK_READ_TIMEOUT"
    JvmNetworkFailurePhase.Unknown -> "NETWORK_TIMEOUT"
}

private const val OKHTTP_STREAM_RESET_EXCEPTION = "okhttp3.internal.http2.StreamResetException"
private const val OKHTTP_CONNECTION_SHUTDOWN_EXCEPTION =
    "okhttp3.internal.http2.ConnectionShutdownException"
private const val MAX_DIAGNOSTIC_CAUSE_DEPTH = 8
private const val MAX_DIAGNOSTIC_NETWORK_ATTEMPTS = 16
private const val OKHTTP_UNEXPECTED_END_OF_STREAM = "unexpected end of stream"
private val READ_ONLY_NETWORK_METHODS = setOf("GET", "HEAD", "OPTIONS", "PROPFIND", "REPORT", "SEARCH")
private val SAFE_NETWORK_PROTOCOLS = setOf("h2", "http/1.1")
private val RETRYABLE_HTTP2_ERRORS = setOf("REFUSED_STREAM", "INTERNAL_ERROR")
private val SAFE_HTTP2_ERRORS = setOf(
    "NO_ERROR",
    "PROTOCOL_ERROR",
    "INTERNAL_ERROR",
    "FLOW_CONTROL_ERROR",
    "SETTINGS_TIMEOUT",
    "STREAM_CLOSED",
    "FRAME_SIZE_ERROR",
    "REFUSED_STREAM",
    "CANCEL",
    "COMPRESSION_ERROR",
    "CONNECT_ERROR",
    "ENHANCE_YOUR_CALM",
    "INADEQUATE_SECURITY",
    "HTTP_1_1_REQUIRED",
)
