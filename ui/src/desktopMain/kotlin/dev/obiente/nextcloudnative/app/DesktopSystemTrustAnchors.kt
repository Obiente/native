package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/** Operating-system trust source used in addition to the bundled JVM roots. */
internal enum class DesktopSystemTrustSource {
    LinuxCaBundle,
    WindowsRootStore,
}

/** Bounded, non-secret reason why no operating-system anchors were added. */
internal enum class DesktopSystemTrustUnavailableReason {
    UnsupportedPlatform,
    NotFound,
    Unreadable,
    Malformed,
    TooLarge,
    Empty,
    StoreUnavailable,
}

internal sealed interface DesktopSystemTrustAnchors {
    data class Loaded(
        val source: DesktopSystemTrustSource,
        val certificates: List<X509Certificate>,
    ) : DesktopSystemTrustAnchors {
        init {
            require(certificates.isNotEmpty())
        }
    }

    data class Unavailable(val reason: DesktopSystemTrustUnavailableReason) : DesktopSystemTrustAnchors
}

/**
 * System CA bundles maintained by the distribution trust tooling (`update-ca-certificates`,
 * `update-ca-trust`, or `trust extract-compat`). Administrator-added CAs appear here after that
 * tooling runs, which is the same trust that curl and OpenSSL use by default.
 */
internal val LINUX_SYSTEM_CA_BUNDLE_PATHS: List<Path> = listOf(
    // Debian, Ubuntu, Arch Linux, Gentoo, NixOS, and freedesktop Flatpak runtimes.
    "/etc/ssl/certs/ca-certificates.crt",
    // Fedora and RHEL.
    "/etc/pki/tls/certs/ca-bundle.crt",
    // RHEL and CentOS 7 or later through p11-kit extraction.
    "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",
    // openSUSE.
    "/etc/ssl/ca-bundle.pem",
    // Alpine and other musl-based distributions.
    "/etc/ssl/cert.pem",
).map { Path.of(it) }

internal fun loadDesktopSystemTrustAnchors(
    osName: String = System.getProperty("os.name").orEmpty(),
): DesktopSystemTrustAnchors {
    val normalized = osName.lowercase()
    return when {
        normalized.startsWith("windows") -> loadWindowsRootTrustAnchors()
        normalized.contains("linux") -> loadLinuxSystemCaBundle()
        else -> DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.UnsupportedPlatform)
    }
}

/**
 * Uses the first candidate bundle that parses completely. A bundle that is unreadable, oversized,
 * empty, or malformed contributes no anchors; it never weakens validation.
 */
internal fun loadLinuxSystemCaBundle(
    candidates: List<Path> = LINUX_SYSTEM_CA_BUNDLE_PATHS,
): DesktopSystemTrustAnchors {
    var firstFailure: DesktopSystemTrustAnchors.Unavailable? = null
    for (candidate in candidates) {
        val result = readPemTrustBundle(candidate)
        if (result is DesktopSystemTrustAnchors.Loaded) return result
        result as DesktopSystemTrustAnchors.Unavailable
        if (result.reason != DesktopSystemTrustUnavailableReason.NotFound && firstFailure == null) {
            firstFailure = result
        }
    }
    return firstFailure ?: DesktopSystemTrustAnchors.Unavailable(DesktopSystemTrustUnavailableReason.NotFound)
}

internal fun readPemTrustBundle(path: Path): DesktopSystemTrustAnchors {
    val bytes = try {
        if (Files.notExists(path)) return unavailable(DesktopSystemTrustUnavailableReason.NotFound)
        if (!Files.isRegularFile(path)) return unavailable(DesktopSystemTrustUnavailableReason.Unreadable)
        readBoundedTrustBundle(path) ?: return unavailable(DesktopSystemTrustUnavailableReason.TooLarge)
    } catch (_: NoSuchFileException) {
        return unavailable(DesktopSystemTrustUnavailableReason.NotFound)
    } catch (_: IOException) {
        return unavailable(DesktopSystemTrustUnavailableReason.Unreadable)
    } catch (_: SecurityException) {
        return unavailable(DesktopSystemTrustUnavailableReason.Unreadable)
    }
    return parsePemTrustBundle(String(bytes, Charsets.ISO_8859_1))
}

/** Returns null when the file exceeds the bound, including when it grows while being read. */
private fun readBoundedTrustBundle(path: Path): ByteArray? {
    if (Files.size(path) > MAX_SYSTEM_TRUST_BUNDLE_BYTES) return null
    Files.newInputStream(path).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.size() > MAX_SYSTEM_TRUST_BUNDLE_BYTES) return null
        }
        return output.toByteArray()
    }
}

/**
 * Accepts only complete `CERTIFICATE` blocks. OpenSSL `TRUSTED CERTIFICATE` blocks and other PEM
 * labels are skipped because their auxiliary trust restrictions cannot be honored here. Any
 * truncated or undecodable certificate rejects the whole bundle.
 */
internal fun parsePemTrustBundle(text: String): DesktopSystemTrustAnchors {
    val factory = try {
        CertificateFactory.getInstance("X.509")
    } catch (_: CertificateException) {
        return unavailable(DesktopSystemTrustUnavailableReason.Malformed)
    }
    val certificates = LinkedHashMap<String, X509Certificate>()
    var block: StringBuilder? = null
    var skippedLabel: String? = null
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        when {
            skippedLabel != null -> if (line == "-----END $skippedLabel-----") skippedLabel = null
            block != null -> when {
                line == PEM_CERTIFICATE_END -> {
                    val certificate = decodeCertificate(factory, block.toString())
                        ?: return unavailable(DesktopSystemTrustUnavailableReason.Malformed)
                    certificates.putIfAbsent(Base64.getEncoder().encodeToString(certificate.encoded), certificate)
                    if (certificates.size > MAX_SYSTEM_TRUST_ANCHORS) {
                        return unavailable(DesktopSystemTrustUnavailableReason.TooLarge)
                    }
                    block = null
                }
                line.startsWith("-----") -> return unavailable(DesktopSystemTrustUnavailableReason.Malformed)
                else -> block.append(line)
            }
            line == PEM_CERTIFICATE_BEGIN -> block = StringBuilder()
            line.startsWith("-----BEGIN ") && line.endsWith("-----") ->
                skippedLabel = line.removePrefix("-----BEGIN ").removeSuffix("-----")
        }
    }
    if (block != null || skippedLabel != null) return unavailable(DesktopSystemTrustUnavailableReason.Malformed)
    if (certificates.isEmpty()) return unavailable(DesktopSystemTrustUnavailableReason.Empty)
    return DesktopSystemTrustAnchors.Loaded(DesktopSystemTrustSource.LinuxCaBundle, certificates.values.toList())
}

private fun decodeCertificate(factory: CertificateFactory, base64: String): X509Certificate? {
    val encoded = try {
        Base64.getDecoder().decode(base64)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (encoded.isEmpty()) return null
    val certificate = try {
        factory.generateCertificate(ByteArrayInputStream(encoded)) as? X509Certificate
    } catch (_: CertificateException) {
        null
    } ?: return null
    // Trailing bytes after the DER structure are not a certificate the operating system trusts.
    return certificate.takeIf { it.encoded.contentEquals(encoded) }
}

/**
 * Reads the Windows ROOT store through SunMSCAPI. This is the current user's logical view, which
 * normally includes machine-wide roots and roots the user installed. Windows certificate
 * properties such as purpose restrictions are not visible here; JSSE still requires a
 * server-authentication leaf.
 */
internal fun loadWindowsRootTrustAnchors(
    openStore: () -> KeyStore = { KeyStore.getInstance(WINDOWS_ROOT_STORE_TYPE).apply { load(null, null) } },
): DesktopSystemTrustAnchors {
    val store = try {
        openStore()
    } catch (_: KeyStoreException) {
        return unavailable(DesktopSystemTrustUnavailableReason.StoreUnavailable)
    } catch (_: NoSuchAlgorithmException) {
        return unavailable(DesktopSystemTrustUnavailableReason.StoreUnavailable)
    } catch (_: CertificateException) {
        return unavailable(DesktopSystemTrustUnavailableReason.Unreadable)
    } catch (_: IOException) {
        return unavailable(DesktopSystemTrustUnavailableReason.Unreadable)
    }
    val certificates = LinkedHashMap<String, X509Certificate>()
    try {
        for (alias in store.aliases().asSequence()) {
            if (!store.isCertificateEntry(alias)) continue
            val certificate = store.getCertificate(alias) as? X509Certificate ?: continue
            certificates.putIfAbsent(Base64.getEncoder().encodeToString(certificate.encoded), certificate)
            if (certificates.size > MAX_SYSTEM_TRUST_ANCHORS) {
                return unavailable(DesktopSystemTrustUnavailableReason.TooLarge)
            }
        }
    } catch (_: KeyStoreException) {
        return unavailable(DesktopSystemTrustUnavailableReason.Unreadable)
    } catch (_: CertificateException) {
        return unavailable(DesktopSystemTrustUnavailableReason.Unreadable)
    }
    if (certificates.isEmpty()) return unavailable(DesktopSystemTrustUnavailableReason.Empty)
    return DesktopSystemTrustAnchors.Loaded(DesktopSystemTrustSource.WindowsRootStore, certificates.values.toList())
}

private fun unavailable(reason: DesktopSystemTrustUnavailableReason) =
    DesktopSystemTrustAnchors.Unavailable(reason)

private const val PEM_CERTIFICATE_BEGIN = "-----BEGIN CERTIFICATE-----"
private const val PEM_CERTIFICATE_END = "-----END CERTIFICATE-----"
private const val WINDOWS_ROOT_STORE_TYPE = "Windows-ROOT"
internal const val MAX_SYSTEM_TRUST_BUNDLE_BYTES = 4L * 1024L * 1024L
internal const val MAX_SYSTEM_TRUST_ANCHORS = 2_048
