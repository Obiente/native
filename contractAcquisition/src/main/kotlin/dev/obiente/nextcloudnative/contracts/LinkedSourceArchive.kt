package dev.obiente.nextcloudnative.contracts

import java.io.IOException
import java.util.concurrent.CancellationException
import org.xml.sax.SAXException

/**
 * Reads a contract from an App Store-linked source archive, which is a lower-trust fallback.
 *
 * A truncated or corrupt archive, malformed app metadata XML, an unsafe entry path, or an archive
 * whose app identity does not match the release is unusable source data. It yields no candidate,
 * exactly like an archive without a contract, instead of escaping as an unexpected client failure.
 * Cancellation and genuine runtime failures propagate.
 */
internal fun extractLinkedSourceContractOrNull(
    archive: ByteArray,
    release: AppStoreRelease,
): VerifiedPackageContract? = try {
    extractOpenApiContract(archive, release)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: IllegalStateException) {
    // Includes a missing contract, identity mismatches, and malformed archive structure.
    null
} catch (_: IllegalArgumentException) {
    null
} catch (_: IOException) {
    // Truncated archives and unsupported XML encodings.
    null
} catch (_: SAXException) {
    // Malformed appinfo/info.xml.
    null
}
