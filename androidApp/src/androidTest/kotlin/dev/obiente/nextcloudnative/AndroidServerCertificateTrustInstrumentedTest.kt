package dev.obiente.nextcloudnative

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.obiente.nextcloudnative.app.AndroidServerCertificateTrust
import dev.obiente.nextcloudnative.app.useAndroidNextcloudCertificateTrust
import java.io.IOException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidServerCertificateTrustInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var trustedUrl: String? = null

    @After
    fun revokeFixtureTrust() {
        trustedUrl?.let { AndroidServerCertificateTrust.revoke(context, it) }
    }

    @Test
    fun exactReviewedCertificateIsTrustedOnlyUntilRevoked() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()

        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory())
            server.start()
            val url = server.url("/").toString()
            trustedUrl = url

            val beforeApproval = assertThrows(IOException::class.java) {
                trustedClient().newCall(Request.Builder().url(url).build()).execute().close()
            }
            assertTrue(AndroidServerCertificateTrust.isCertificateFailure(beforeApproval))

            val review = AndroidServerCertificateTrust.inspect(url)
            assertEquals("localhost:${server.port}", review.serverDisplayName)
            assertEquals(95, review.sha256Fingerprint.length)
            assertNull(AndroidServerCertificateTrust.trustedCertificate(context, url))

            AndroidServerCertificateTrust.approve(context, review)
            val trusted = AndroidServerCertificateTrust.trustedCertificate(context, url)
            assertNotNull(trusted)
            assertEquals(review.sha256Fingerprint, trusted?.sha256Fingerprint)

            server.enqueue(MockResponse.Builder().code(200).body("trusted").build())
            trustedClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("trusted", response.body.string())
            }

            assertTrue(AndroidServerCertificateTrust.revoke(context, url))
            assertNull(AndroidServerCertificateTrust.trustedCertificate(context, url))
            assertThrows(IOException::class.java) {
                trustedClient().newCall(Request.Builder().url(url).build()).execute().close()
            }
        }
    }

    private fun trustedClient(): OkHttpClient = OkHttpClient.Builder()
        .useAndroidNextcloudCertificateTrust(context)
        .build()
}
