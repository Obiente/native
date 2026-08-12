package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import okio.Buffer
import okio.ForwardingSink
import okio.blackholeSink
import okio.buffer

class DesktopStreamingMultipartRequestBodyTest {
    @Test
    fun multipartBodyCannotBeReplayedByOkHttp() {
        assertTrue(DesktopStreamingMultipartRequestBody(upload()) { "test".byteInputStream() }.isOneShot())
    }

    @Test
    fun sourceReadFailureIsTypedAsLocalUploadIo() {
        val sourceFailure = IOException("selected file became unreadable")

        val thrown = assertFailsWith<JvmLocalUploadSourceIOException> {
            DesktopStreamingMultipartRequestBody(upload()) {
                object : InputStream() {
                    override fun read(): Int = throw sourceFailure
                }
            }.writeTo(Buffer())
        }

        assertSame(sourceFailure, thrown.cause)
    }

    @Test
    fun sinkFailureIsNotTypedAsLocalUploadIo() {
        val sinkFailure = IOException("network sink failed")
        val sink = object : ForwardingSink(blackholeSink()) {
            override fun write(source: Buffer, byteCount: Long) = throw sinkFailure
        }.buffer()

        val thrown = assertFailsWith<IOException> {
            DesktopStreamingMultipartRequestBody(upload(16_384)) {
                ByteArray(16_384).inputStream()
            }.writeTo(sink)
        }

        assertSame(sinkFailure, thrown)
    }

    private fun upload(size: Int = 4): PreparedMultipartUpload = prepareMultipartUpload(
        NextcloudMultipartUploadRequest(
            method = NextcloudApiMethod.POST,
            relativePath = "/index.php/apps/deck/api/v1.1/boards/1/stacks/2/cards/3/attachments",
            file = localUploadFile("synthetic-selection-1", "sample.txt", "text/plain", size.toLong()),
        ),
        "synthetic-boundary",
    )
}
