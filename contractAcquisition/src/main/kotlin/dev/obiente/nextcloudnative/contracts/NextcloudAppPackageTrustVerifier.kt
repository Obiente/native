package dev.obiente.nextcloudnative.contracts

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.util.Base64

class NextcloudAppPackageTrustVerifier(
    private val trustResourceLoader: (String) -> ByteArray = ::loadBundledTrustResource,
) : AppPackageTrustVerifier {
    override fun verifyAndExtract(release: AppStoreRelease, archive: ByteArray): VerifiedPackageContract {
        require(release.signatureDigest.equals("sha512", ignoreCase = true)) {
            "The package uses an unsupported signature digest."
        }
        val factory = CertificateFactory.getInstance("X.509")
        val trustedCertificates = factory.generateCertificates(
            ByteArrayInputStream(trustResourceLoader(ROOT_CERTIFICATES_RESOURCE)),
        ).map { certificate -> certificate as X509Certificate }
        check(trustedCertificates.isNotEmpty()) { "The Nextcloud trust bundle is empty." }
        trustedCertificates.forEach(X509Certificate::checkValidity)
        val appCertificate = factory.generateCertificate(
            ByteArrayInputStream(release.certificatePem.encodeToByteArray()),
        ) as X509Certificate
        appCertificate.checkValidity()
        check(appCertificate.subjectCommonName() == release.appId) {
            "The package certificate does not belong to ${release.appId}."
        }
        check(appCertificate.sigAlgName.contains("RSA", ignoreCase = true)) {
            "The package certificate uses an unsupported key algorithm."
        }
        verifyCertificateChain(appCertificate, trustedCertificates)
        val crl = factory.generateCRL(
            ByteArrayInputStream(trustResourceLoader(ROOT_REVOCATION_LIST_RESOURCE)),
        ) as X509CRL
        val crlIssuer = trustedCertificates.firstOrNull { certificate ->
            certificate.subjectX500Principal == crl.issuerX500Principal
        } ?: error("The CRL issuer is missing from the Nextcloud trust bundle.")
        crl.verify(crlIssuer.publicKey)
        check(!crl.isRevoked(appCertificate)) { "The package certificate has been revoked." }
        val signature = Signature.getInstance("SHA512withRSA")
        signature.initVerify(appCertificate.publicKey)
        signature.update(archive)
        val signatureBytes = Base64.getMimeDecoder().decode(release.signature)
        check(signature.verify(signatureBytes)) { "The downloaded app package signature is invalid." }
        return extractOpenApiContract(archive, release)
    }
}

private fun verifyCertificateChain(
    leaf: X509Certificate,
    trustedCertificates: List<X509Certificate>,
) {
    var current = leaf
    val visited = mutableSetOf<String>()
    repeat(trustedCertificates.size + 1) {
        val identity = current.subjectX500Principal.name
        check(visited.add(identity)) { "The package certificate chain contains a cycle." }
        val issuer = trustedCertificates.firstOrNull { candidate ->
            candidate.subjectX500Principal == current.issuerX500Principal
        } ?: error("The package certificate was not issued by Nextcloud's trusted authority.")
        current.verify(issuer.publicKey)
        if (issuer.subjectX500Principal == issuer.issuerX500Principal) {
            issuer.verify(issuer.publicKey)
            return
        }
        current = issuer
    }
    error("The package certificate chain is too deep.")
}

private fun X509Certificate.subjectCommonName(): String? {
    val principal = subjectX500Principal.getName("RFC2253")
    return Regex("(?:^|,)CN=([^,]+)").find(principal)?.groupValues?.get(1)
}

private fun loadBundledTrustResource(name: String): ByteArray =
    NextcloudAppPackageTrustVerifier::class.java.classLoader.getResourceAsStream(name)?.use(InputStream::readBytes)
        ?: error("The bundled Nextcloud trust resource is missing: $name")

private const val ROOT_CERTIFICATES_RESOURCE = "nextcloud-codesigning/root.crt"
private const val ROOT_REVOCATION_LIST_RESOURCE = "nextcloud-codesigning/root.crl"
