package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException

class JvmNetworkFailureDiagnosticsTest {
    @Test
    fun cleanShortResponseIsTypedAndClassifiedAsTruncation() {
        val failure = assertFailsWith<JvmNetworkResponseTruncatedIOException> {
            byteArrayOf(1, 2, 3).requireExactJvmNetworkResponseBytes(4)
        }
        val attempt = JvmNetworkRequestAttempt().apply {
            markPhase(JvmNetworkFailurePhase.ResponseBody)
            markExchangeStarted()
        }

        val diagnostic = failure.toJvmNetworkFailureDiagnostic(attempt, true, true)

        assertEquals("NETWORK_RESPONSE_TRUNCATED", diagnostic.code)
        assertTrue(diagnostic.retryable)
    }

    @Test
    fun exactResponseLengthIsAccepted() {
        val bytes = byteArrayOf(1, 2, 3)

        assertSame(bytes, bytes.requireExactJvmNetworkResponseBytes(3))
    }

    @Test
    fun localUploadFailureProducesAStorageDiagnosticWithoutLocationFields() {
        val failure = JvmLocalUploadSourceIOException(IOException("/private/path/report.pdf"))

        val event = failure.toJvmLocalUploadSourceDiagnosticEvent("POST", 42L)

        assertEquals(SupportDiagnosticComponent.Storage, event.component)
        assertEquals("local-upload.read", event.operation)
        assertEquals("LOCAL_UPLOAD_SOURCE_IO", event.code)
        assertEquals(listOf("method", "mutation"), event.fields.map { it.name })
        assertTrue(failure.isJvmLocalUploadSourceFailure())
    }

    @Test
    fun localOutputFailureIsNotReportedAsANetworkReadFailure() {
        val outputFailure = IOException("local storage unavailable")
        var reportedFailure: IOException? = null

        val thrown = assertFailsWith<IOException> {
            ByteArrayInputStream(byteArrayOf(1, 2, 3)).copyBoundedNetworkResponseTo(
                output = object : OutputStream() {
                    override fun write(value: Int) {
                        throw outputFailure
                    }
                },
                maxBytes = 3L,
                onLimitExceeded = { error("unexpected limit") },
                onNetworkReadFailure = { reportedFailure = it },
            )
        }

        assertSame(outputFailure, thrown)
        assertNull(reportedFailure)
    }

    @Test
    fun responseReadFailureIsReportedBeforeItPropagates() {
        val networkFailure = IOException("response stream failed")
        var reportedFailure: IOException? = null

        val thrown = assertFailsWith<IOException> {
            object : InputStream() {
                override fun read(): Int = throw networkFailure
            }.copyBoundedNetworkResponseTo(
                output = OutputStream.nullOutputStream(),
                maxBytes = 3L,
                onLimitExceeded = { error("unexpected limit") },
                onNetworkReadFailure = { reportedFailure = it },
            )
        }

        assertSame(networkFailure, thrown)
        assertSame(networkFailure, reportedFailure)
    }

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
    fun clientListenerMarksTheResponseWaitAsAReadPhase() {
        val releaseServer = CountDownLatch(1)
        ServerSocket(0).use { server ->
            val worker = thread(name = "network-response-wait-test") {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (true) {
                        if (input.readLine().isNullOrEmpty()) break
                    }
                    releaseServer.await(5L, TimeUnit.SECONDS)
                }
            }
            try {
                val attempt = JvmNetworkRequestAttempt()
                val client = OkHttpClient.Builder()
                    .readTimeout(100L, TimeUnit.MILLISECONDS)
                    .trackJvmNetworkFailures()
                    .build()
                val request = Request.Builder()
                    .url("http://127.0.0.1:${server.localPort}/wait")
                    .tag(JvmNetworkRequestAttempt::class.java, attempt)
                    .build()

                val failure = assertFailsWith<SocketTimeoutException> {
                    client.newCall(request).execute().close()
                }
                val diagnostic = failure.toJvmNetworkFailureDiagnostic(attempt, true, true)

                assertEquals(JvmNetworkFailurePhase.ResponseHeaders, attempt.phase)
                assertEquals("NETWORK_READ_TIMEOUT", diagnostic.code)
            } finally {
                releaseServer.countDown()
                worker.join(5_000L)
            }
        }
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
    fun exactOkHttpResponseEndFailureIsClassifiedAsTruncation() {
        val attempt = JvmNetworkRequestAttempt().apply {
            markPhase(JvmNetworkFailurePhase.ResponseBody)
            markExchangeStarted()
        }

        val diagnostic = ProtocolException("unexpected end of stream")
            .toJvmNetworkFailureDiagnostic(attempt, true, true)

        assertEquals("NETWORK_RESPONSE_TRUNCATED", diagnostic.code)
        assertTrue(diagnostic.retryable)
    }

    @Test
    fun otherProtocolFailuresRemainNonRetryable() {
        val attempt = JvmNetworkRequestAttempt().apply {
            markPhase(JvmNetworkFailurePhase.ResponseBody)
        }

        val diagnostic = ProtocolException("unexpected status line")
            .toJvmNetworkFailureDiagnostic(attempt, true, true)

        assertEquals("NETWORK_PROTOCOL_FAILED", diagnostic.code)
        assertFalse(diagnostic.retryable)
    }

    @Test
    fun callerCancellationCanBeSuppressedByStreamingCallers() {
        val attempt = JvmNetworkRequestAttempt().apply { markCancelled() }

        val diagnostic = IOException("Canceled")
            .toJvmNetworkFailureDiagnostic(attempt, true, true)

        assertEquals("NETWORK_CANCELLED", diagnostic.code)
        assertTrue(diagnostic.isCancellation)
        assertFalse(diagnostic.retryable)
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

    @Test
    fun tlsPhaseProtocolFailureIsNotReportedAsRetryableIo() {
        val attempt = JvmNetworkRequestAttempt().apply {
            markPhase(JvmNetworkFailurePhase.Tls)
        }

        val diagnostic = SSLProtocolException("incompatible TLS record")
            .toJvmNetworkFailureDiagnostic(attempt, true, true)

        assertEquals("NETWORK_TLS_HANDSHAKE", diagnostic.code)
        assertFalse(diagnostic.retryable)
    }
}
