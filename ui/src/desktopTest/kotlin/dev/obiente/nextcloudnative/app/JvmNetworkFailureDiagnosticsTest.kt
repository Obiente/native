package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException

class JvmNetworkFailureDiagnosticsTest {
    @Test
    fun clientListenerTracksDnsPhaseForTaggedRequests() {
        val attempt = JvmNetworkRequestAttempt()
        val client = OkHttpClient.Builder()
            .dns(Dns { throw UnknownHostException("private-cloud.example.test") })
            .trackJvmNetworkFailures()
            .build()
        val request = Request.Builder()
            .url("https://private-cloud.example.test/status.php")
            .tag(JvmNetworkRequestAttempt::class.java, attempt)
            .build()

        runCatching { client.newCall(request).execute().close() }

        assertEquals(JvmNetworkFailurePhase.Dns, attempt.phase)
        assertFalse(attempt.exchangeStarted)
    }

    @Test
    fun classifiesDnsFailuresWithoutRetainingTheHostname() {
        val diagnostic = UnknownHostException("private-cloud.example.test")
            .toJvmNetworkFailureDiagnostic(JvmNetworkRequestAttempt(), true, true)

        assertEquals("NETWORK_DNS_UNRESOLVED", diagnostic.code)
        assertTrue(diagnostic.retryable)
        assertNull(diagnostic.http2Error)
        assertFalse(diagnostic.fields().any { "private-cloud" in it.value })
    }

    @Test
    fun timeoutCodeIdentifiesTheLastSafeNetworkPhase() {
        val attempt = JvmNetworkRequestAttempt().apply {
            markPhase(JvmNetworkFailurePhase.ResponseBody)
            markExchangeStarted()
        }

        val diagnostic = SocketTimeoutException("timeout")
            .toJvmNetworkFailureDiagnostic(attempt, true, true)

        assertEquals("NETWORK_READ_TIMEOUT", diagnostic.code)
        assertTrue(diagnostic.exchangeStarted)
        assertEquals("response_body", diagnostic.phase.storageValue)
    }

    @Test
    fun http2ResetExportsOnlyAnAllowlistedReason() {
        val diagnostic = StreamResetException(ErrorCode.REFUSED_STREAM)
            .toJvmNetworkFailureDiagnostic(JvmNetworkRequestAttempt(), true, true)

        assertEquals("NETWORK_HTTP2_STREAM_RESET", diagnostic.code)
        assertEquals("REFUSED_STREAM", diagnostic.http2Error)
        assertTrue(diagnostic.retryable)
    }

    @Test
    fun mutationsAreNeverReportedAsRetryable() {
        val diagnostic = IOException("temporary transport problem")
            .toJvmNetworkFailureDiagnostic(JvmNetworkRequestAttempt(), false, true)

        assertEquals("NETWORK_IO_FAILED", diagnostic.code)
        assertFalse(diagnostic.retryable)
    }

    @Test
    fun certificateFailureInTheCauseChainIsNotRetryable() {
        val failure = SSLHandshakeException("handshake failed").apply {
            initCause(CertificateException("private certificate detail"))
        }

        val diagnostic = failure.toJvmNetworkFailureDiagnostic(JvmNetworkRequestAttempt(), true, true)

        assertEquals("NETWORK_CERTIFICATE_REJECTED", diagnostic.code)
        assertFalse(diagnostic.retryable)
        assertFalse(diagnostic.fields().any { "certificate" in it.value })
    }
}
