package dev.obiente.nextcloudnative

import java.io.OutputStream
import java.security.MessageDigest

/**
 * Verifies a DAV checksum hint against bytes read from the exact ETag generation.
 *
 * Nextcloud documents that regular-upload checksum properties are client supplied and are not
 * always server validated. They can narrow candidates, but only this bounded GET makes them
 * safe evidence for automatically accepting identical local and remote content.
 */
internal fun AndroidFileSyncRemoteTree.verifyContentHash(
    relativePath: String,
    expectedRemoteEtag: String,
    expectedContentHash: String,
    expectedBytes: Long,
    maximumBytes: Long,
): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    val sink = object : OutputStream() {
        override fun write(byte: Int) {
            digest.update(byte.toByte())
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            digest.update(bytes, offset, length)
        }
    }
    val result = webDav.readFile(
        session = session,
        userId = userId,
        path = fullPath(relativePath),
        destination = sink,
        maximumBytes = maximumBytes,
        expectedEtag = expectedRemoteEtag,
        cancellation = transferCancellation,
    )
    require(result.byteCount == expectedBytes) { "The server returned truncated content during verification." }
    require(result.etag == null || result.etag == expectedRemoteEtag) {
        "The server file changed during content verification."
    }
    val actual = "sha256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    return actual == expectedContentHash
}

internal fun AndroidFileSyncRemoteTree.contentRangeHash(
    relativePath: String,
    expectedRemoteEtag: String,
    expectedBytes: Long,
    offset: Long,
    length: Int,
): String {
    val hash = webDav.readFileRangeHash(
        session = session,
        userId = userId,
        path = fullPath(relativePath),
        expectedEtag = expectedRemoteEtag,
        expectedBytes = expectedBytes,
        offset = offset,
        length = length,
        cancellation = transferCancellation,
    )
    val after = requireNotNull(resolve(relativePath)) {
        "The server file disappeared during content verification."
    }
    require(after.entry.etag == expectedRemoteEtag && after.entry.size == expectedBytes && !after.isDirectory) {
        "The server file changed during content verification."
    }
    return hash
}
