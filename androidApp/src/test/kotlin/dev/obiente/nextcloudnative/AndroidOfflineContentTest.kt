package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import java.security.MessageDigest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class AndroidOfflineContentTest {
    @Test
    fun durableGenerationMustMatchItsRecordedHashAndCallerBound() {
        val content = "offline vault".encodeToByteArray()
        val path = Files.createTempFile("ncn-offline-content-", ".blob").toFile()
        try {
            path.writeBytes(content)
            val revision = "sha256:" + MessageDigest.getInstance("SHA-256")
                .digest(content)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val stored = AndroidOfflineContent(file(), path, revision)

            assertContentEquals(content, stored.readVerified(1_024)?.bytes)
            assertNull(stored.readVerified(content.size.toLong() - 1L))

            path.appendBytes(byteArrayOf(1))
            assertNull(stored.readVerified(1_024))
        } finally {
            path.delete()
        }
    }

    private fun file() = NextcloudFile(
        path = "Notes/vault.md",
        name = "vault.md",
        isDirectory = false,
        mimeType = "text/markdown",
        size = 13,
        lastModified = null,
        fileId = null,
        hasPreview = false,
        etag = "\"remote-1\"",
    )
}
