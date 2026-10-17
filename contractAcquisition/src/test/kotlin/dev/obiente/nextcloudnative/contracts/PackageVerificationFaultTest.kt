package dev.obiente.nextcloudnative.contracts

import java.security.SignatureException
import javax.xml.parsers.ParserConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class PackageVerificationFaultTest {
    @Test
    fun `a missing bundled trust resource is a local client fault`() {
        val missing = assertFailsWith<ContractTrustResourceMissingException> {
            loadBundledTrustResource("nextcloud-codesigning/synthetic-missing.crt")
        }
        assertEquals(ContractAcquisitionFailureKind.RuntimeIncompatible, classifyContractAcquisitionFailure(missing))

        val verifier = NextcloudAppPackageTrustVerifier(trustResourceLoader = { name ->
            throw ContractTrustResourceMissingException("The bundled Nextcloud trust resource is missing: $name")
        })
        val failure = assertFailsWith<ContractTrustResourceMissingException> { acquireWith(verifier) }
        assertEquals(ContractAcquisitionFailureKind.RuntimeIncompatible, classifyContractAcquisitionFailure(failure))
    }

    @Test
    fun `a local parser configuration fault is not reported as a verification failure`() {
        val configuration = ParserConfigurationException("synthetic")
        val failure = assertFailsWith<ParserConfigurationException> {
            acquireWith(object : AppPackageTrustVerifier {
                override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) = throw configuration
            })
        }
        assertSame(configuration, failure)
        assertEquals(ContractAcquisitionFailureKind.Unexpected, classifyContractAcquisitionFailure(failure))
    }

    @Test
    fun `genuine certificate and signature failures remain verification failures`() {
        // The bundled trust store loads, and the synthetic catalog certificate is not a certificate.
        val certificate = assertFailsWith<ContractSourceVerificationException> { acquireWith(NextcloudAppPackageTrustVerifier()) }
        assertEquals(ContractAcquisitionFailureKind.VerificationFailed, classifyContractAcquisitionFailure(certificate))

        val signature = SignatureException("synthetic")
        val signed = assertFailsWith<ContractSourceVerificationException> {
            acquireWith(object : AppPackageTrustVerifier {
                override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray) = throw signature
            })
        }
        assertSame(signature, signed.cause)
        assertIs<SecurityException>(signed)
    }

    private fun acquireWith(verifier: AppPackageTrustVerifier): ContractAcquisitionOutcome = MockWebServer().use { server ->
        server.start()
        val download = server.url("downloads/cospend-4.0.2.tar.gz")
        server.enqueue(MockResponse(body = """[{"id":"cospend","certificate":"certificate","releases":[""" +
            """{"version":"4.0.2","download":"$download","signature":"c2lnbmF0dXJl",""" +
            """"signatureDigest":"sha512","isNightly":false}]}]"""))
        server.enqueue(MockResponse(body = "package"))
        SignedAppStoreContractAcquirer(
            httpClient = OkHttpClient(),
            appStoreBaseUrl = server.url("api/v1").toString(),
            requireHttps = false,
            trustVerifier = verifier,
        ).acquireOutcome(ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2"))
    }
}
