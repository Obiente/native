package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudFileContent
import java.io.File
import java.security.MessageDigest

internal data class AndroidOfflineContent(
    val file: NextcloudFile,
    val content: File,
    val localRevision: String,
)

internal fun AndroidOfflineContent.readVerified(maximumBytes: Long): NextcloudFileContent? {
    require(maximumBytes > 0L)
    if (!content.isFile || content.length() > maximumBytes) return null
    val bytes = content.readBytes()
    if (bytes.size.toLong() > maximumBytes) return null
    val expectedHash = localRevision.removePrefix("sha256:")
    if (expectedHash.length != 64 || expectedHash.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    val actualHash = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    if (actualHash != expectedHash) return null
    return NextcloudFileContent(bytes, file.mimeType, file.etag)
}

private fun ByteArray.toHexString(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
