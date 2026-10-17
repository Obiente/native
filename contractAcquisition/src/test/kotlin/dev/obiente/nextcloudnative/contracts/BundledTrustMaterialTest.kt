package dev.obiente.nextcloudnative.contracts

import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

class BundledTrustMaterialTest {
    private val roots = loadBundledTrustResource(ROOTS)
    private val revocations = loadBundledTrustResource(REVOCATIONS)

    @Test
    fun `a corrupt bundled root certificate is a client fault`() {
        assertClientFault(verifier(roots = "-----BEGIN CERTIFICATE-----\nnot base64\n-----END CERTIFICATE-----\n".encodeToByteArray()))
        assertClientFault(verifier(roots = ByteArray(0)))
    }

    @Test
    fun `an expired bundled revocation list is a client fault`() {
        // The bundled certificates stay valid until 2041; the revocation list's next update is in 2036.
        val validUntil2036 = verifier(now = Date(EARLY_2037_MILLIS))
        assertClientFault(validUntil2036)
    }

    @Test
    fun `bundled trust resources of an unsupported format are client faults`() {
        assertClientFault(verifier(revocations = roots))
        assertClientFault(verifier(roots = revocations))
    }

    @Test
    fun `a package certificate that fails identity checks is still a verification failure`() {
        // A well-formed certificate from the bundled chain, presented for another app.
        val failure = assertFailsWith<ContractSourceVerificationException> {
            acquireWith(verifier(), certificate = intermediatePem())
        }
        assertEquals(ContractAcquisitionFailureKind.VerificationFailed, classifyContractAcquisitionFailure(failure))
        assertFalse(failure.cause is ContractTrustMaterialInvalidException)
    }

    @Test
    fun `a bad package signature over valid trust material is still a verification failure`() {
        // The certificate chains to the bundled root and is not revoked, so only the signature fails.
        val release = AppStoreRelease(
            appId = INTERMEDIATE_COMMON_NAME,
            version = "1.0.0",
            downloadUrl = "https://fixture.invalid/package.tar.gz",
            signature = "c2lnbmF0dXJl",
            signatureDigest = "sha512",
            certificatePem = intermediatePem(),
            catalogUrl = "https://fixture.invalid/apps.json",
        )
        val failure = assertFailsWith<Exception> { verifier().verifyAndExtract(release, "package".encodeToByteArray()) }
        assertFalse(failure is ContractTrustMaterialInvalidException, failure.toString())
        assertFalse(failure is ContractTrustResourceMissingException, failure.toString())
        assertEquals(
            ContractAcquisitionFailureKind.VerificationFailed,
            classifyContractAcquisitionFailure(failure.asSourceVerificationFailure("example")),
        )
    }

    private fun assertClientFault(verifier: NextcloudAppPackageTrustVerifier) {
        val failure = assertFailsWith<ContractTrustMaterialInvalidException> { acquireWith(verifier) }
        assertEquals(ContractAcquisitionFailureKind.RuntimeIncompatible, classifyContractAcquisitionFailure(failure))
    }

    private fun verifier(roots: ByteArray = this.roots, revocations: ByteArray = this.revocations, now: Date? = null) =
        NextcloudAppPackageTrustVerifier(
            trustResourceLoader = { name -> if (name == ROOTS) roots else revocations },
            now = { now ?: Date() },
        )

    private fun intermediatePem(): String {
        val text = roots.decodeToString()
        return text.substring(0, text.indexOf("-----END CERTIFICATE-----") + "-----END CERTIFICATE-----".length) + "\n"
    }

    private fun acquireWith(
        verifier: AppPackageTrustVerifier,
        certificate: String = "certificate",
    ): ContractAcquisitionOutcome = MockWebServer().use { server ->
        server.start()
        val release = JSONObject()
            .put("version", "4.0.2")
            .put("download", server.url("downloads/cospend-4.0.2.tar.gz").toString())
            .put("signature", "c2lnbmF0dXJl")
            .put("signatureDigest", "sha512")
            .put("isNightly", false)
        val catalog = JSONArray().put(
            JSONObject().put("id", "cospend").put("certificate", certificate).put("releases", JSONArray().put(release)),
        )
        server.enqueue(MockResponse(body = catalog.toString()))
        server.enqueue(MockResponse(body = "package"))
        SignedAppStoreContractAcquirer(
            httpClient = OkHttpClient(),
            appStoreBaseUrl = server.url("api/v1").toString(),
            requireHttps = false,
            trustVerifier = verifier,
        ).acquireOutcome(ContractAcquisitionRequest("cospend", "34.0.3", "4.0.2"))
    }

    private companion object {
        const val ROOTS = "nextcloud-codesigning/root.crt"
        const val REVOCATIONS = "nextcloud-codesigning/root.crl"
        const val INTERMEDIATE_COMMON_NAME = "Nextcloud Code Signing Intermediate Authority"
        const val EARLY_2037_MILLIS = 2_114_380_800_000L
    }
}
