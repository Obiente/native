package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.KeyStoreException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okhttp3.tls.HeldCertificate
import okhttp3.tls.certificatePem

class DesktopSystemTrustAnchorsTest {
    private val firstAuthority = syntheticTrustAuthority("Synthetic system CA one")
    private val secondAuthority = syntheticTrustAuthority("Synthetic system CA two")

    @Test
    fun completeBundleWithCommentsLoadsEveryDistinctCertificate() {
        val bundle = buildString {
            appendLine("# Synthetic bundle with a non-ASCII comment: Főtanúsítvány")
            append(firstAuthority.certificate.certificatePem())
            appendLine()
            append(secondAuthority.certificate.certificatePem())
            append(firstAuthority.certificate.certificatePem())
        }
        val loaded = assertIs<DesktopSystemTrustAnchors.Loaded>(parsePemTrustBundle(bundle))
        assertEquals(DesktopSystemTrustSource.LinuxCaBundle, loaded.source)
        assertEquals(
            listOf(firstAuthority.certificate, secondAuthority.certificate),
            loaded.certificates,
        )
    }

    @Test
    fun crlfLineEndingsAreAccepted() {
        val pem = firstAuthority.certificate.certificatePem().replace("\n", "\r\n")
        val loaded = assertIs<DesktopSystemTrustAnchors.Loaded>(parsePemTrustBundle(pem))
        assertEquals(listOf(firstAuthority.certificate), loaded.certificates)
    }

    @Test
    fun anyMalformedCertificateRejectsTheWholeBundle() {
        val valid = firstAuthority.certificate.certificatePem()
        val body = valid.lines().filterNot { it.startsWith("-----") }.joinToString("")
        val trailingBytes = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(firstAuthority.certificate.encoded + byteArrayOf(0))
        val malformedBundles = listOf(
            "truncated" to valid + valid.substringBefore("-----END"),
            "invalid base64" to "$valid-----BEGIN CERTIFICATE-----\n@@@@\n-----END CERTIFICATE-----\n",
            "not a certificate" to "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n$valid",
            "empty block" to "-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----\n$valid",
            "nested header" to "-----BEGIN CERTIFICATE-----\n$body\n-----BEGIN CERTIFICATE-----\n",
            "trailing DER bytes" to "-----BEGIN CERTIFICATE-----\n$trailingBytes\n-----END CERTIFICATE-----\n",
            "unterminated other label" to "$valid-----BEGIN TRUSTED CERTIFICATE-----\n$body\n",
        )
        for ((name, bundle) in malformedBundles) {
            assertEquals(
                DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.Malformed),
                parsePemTrustBundle(bundle),
                name,
            )
        }
    }

    @Test
    fun openSslTrustedCertificateBlocksAreNotTreatedAsAnchors() {
        val body = firstAuthority.certificate.certificatePem().lines()
            .filterNot { it.startsWith("-----") }
            .joinToString("\n")
        val auxiliaryOnly = "-----BEGIN TRUSTED CERTIFICATE-----\n$body\n-----END TRUSTED CERTIFICATE-----\n"
        assertEquals(
            DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.Empty),
            parsePemTrustBundle(auxiliaryOnly),
        )
        val mixed = assertIs<DesktopSystemTrustAnchors.Loaded>(
            parsePemTrustBundle(auxiliaryOnly + secondAuthority.certificate.certificatePem()),
        )
        assertEquals(listOf(secondAuthority.certificate), mixed.certificates)
    }

    @Test
    fun emptyAndCommentOnlyBundlesAddNoAnchors() {
        for (bundle in listOf("", "# no certificates\n\n")) {
            assertEquals(
                DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.Empty),
                parsePemTrustBundle(bundle),
            )
        }
    }

    @Test
    fun bundleFilesAreBoundedAndMustBeRegularFiles() = withTrustTestDirectory { directory ->
        assertEquals(
            DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.NotFound),
            readPemTrustBundle(directory.resolve("missing.pem")),
        )
        assertEquals(
            DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.Unreadable),
            readPemTrustBundle(directory),
        )
        val oversized = directory.resolve("oversized.pem")
        Files.write(oversized, ByteArray((MAX_SYSTEM_TRUST_BUNDLE_BYTES + 1).toInt()) { '#'.code.toByte() })
        assertEquals(
            DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.TooLarge),
            readPemTrustBundle(oversized),
        )
    }

    @Test
    fun linuxDiscoveryUsesTheFirstCompleteBundleAndReportsTheFirstFailure() = withTrustTestDirectory { directory ->
        val missing = directory.resolve("missing.pem")
        val malformed = directory.resolve("malformed.pem").also {
            Files.writeString(it, "-----BEGIN CERTIFICATE-----\nAAAA\n")
        }
        val valid = directory.resolve("valid.pem").also {
            Files.writeString(it, secondAuthority.certificate.certificatePem())
        }

        val loaded = assertIs<DesktopSystemTrustAnchors.Loaded>(
            loadLinuxSystemCaBundle(listOf(missing, malformed, valid)),
        )
        assertEquals(listOf(secondAuthority.certificate), loaded.certificates)
        assertEquals(
            DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.Malformed),
            loadLinuxSystemCaBundle(listOf(missing, malformed)),
        )
        assertEquals(
            DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.NotFound),
            loadLinuxSystemCaBundle(listOf(missing)),
        )
    }

    @Test
    fun unsupportedPlatformsAddNoAnchors() {
        assertEquals(
            DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.UnsupportedPlatform),
            loadDesktopSystemTrustAnchors(osName = "Mac OS X"),
        )
    }

    @Test
    fun windowsRootStoreFailuresAddNoAnchors() {
        assertEquals(
            DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.StoreUnavailable),
            loadWindowsRootTrustAnchors { throw KeyStoreException("Windows-ROOT not found") },
        )
        assertEquals(
            DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.Empty),
            loadWindowsRootTrustAnchors { emptyKeyStore() },
        )
    }

    @Test
    fun windowsRootStoreCertificateEntriesBecomeAnchors() {
        val store = emptyKeyStore().apply {
            setCertificateEntry("one", firstAuthority.certificate)
            setCertificateEntry("duplicate", firstAuthority.certificate)
            setCertificateEntry("two", secondAuthority.certificate)
        }
        val loaded = assertIs<DesktopSystemTrustAnchors.Loaded>(loadWindowsRootTrustAnchors { store })
        assertEquals(DesktopSystemTrustSource.WindowsRootStore, loaded.source)
        assertEquals(
            setOf(firstAuthority.certificate, secondAuthority.certificate),
            loaded.certificates.toSet(),
        )
        assertEquals(2, loaded.certificates.size)
    }

    /** Supplementary host check: the test JDK on Windows exposes the real ROOT store. */
    @Test
    fun windowsHostRootStoreLoadsThroughSunMscapi() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
        val loaded = assertIs<DesktopSystemTrustAnchors.Loaded>(loadDesktopSystemTrustAnchors())
        assertEquals(DesktopSystemTrustSource.WindowsRootStore, loaded.source)
        assertTrue(loaded.certificates.isNotEmpty())
    }

    /** Supplementary host check: a Linux host with a standard bundle parses it completely. */
    @Test
    fun linuxHostStandardBundleParsesCompletely() {
        if (!System.getProperty("os.name").orEmpty().contains("Linux", ignoreCase = true)) return
        if (LINUX_SYSTEM_CA_BUNDLE_PATHS.none(Files::isRegularFile)) return
        assertIs<DesktopSystemTrustAnchors.Loaded>(loadDesktopSystemTrustAnchors())
    }
}

internal fun syntheticTrustAuthority(name: String): HeldCertificate = HeldCertificate.Builder()
    .commonName(name)
    .certificateAuthority(0)
    .build()

private fun emptyKeyStore(): KeyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }

internal fun withTrustTestDirectory(block: (Path) -> Unit) {
    val directory = Files.createTempDirectory("desktop-system-trust-test")
    try {
        block(directory)
    } finally {
        File(directory.toString()).deleteRecursively()
    }
}
