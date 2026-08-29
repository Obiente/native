package dev.obiente.nextcloudnative.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okio.Buffer

class JvmFileRangeRequestBodyTest {
    @Test
    fun `range body streams only the requested bytes`() {
        val source = temporaryFile(ByteArray(128 * 1024) { index -> (index % 251).toByte() })
        try {
            val body = jvmFileRangeRequestBody(source, offsetBytes = 17_003L, sizeBytes = 70_011L)
            val sink = Buffer()

            body.writeTo(sink)

            assertEquals(70_011L, body.contentLength())
            assertContentEquals(source.readBytes().copyOfRange(17_003, 87_014), sink.readByteArray())
        } finally {
            source.delete()
        }
    }

    @Test
    fun `range body observes cancellation between bounded reads`() {
        val source = temporaryFile(ByteArray(96 * 1024))
        var checks = 0
        try {
            val body = jvmFileRangeRequestBody(source, 0L, source.length()) {
                checks += 1
                if (checks == 2) throw InterruptedException("stopped")
            }

            assertFailsWith<InterruptedException> { body.writeTo(Buffer()) }
        } finally {
            source.delete()
        }
    }

    private fun temporaryFile(bytes: ByteArray): File =
        File.createTempFile("nextcloud-native-range-", ".bin").also { it.writeBytes(bytes) }
}
