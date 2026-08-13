package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudApiMethod
import dev.obiente.nextcloudnative.app.NextcloudMultipartUploadRequest
import dev.obiente.nextcloudnative.app.JvmLocalUploadSourceIOException
import dev.obiente.nextcloudnative.app.PreparedMultipartUpload
import dev.obiente.nextcloudnative.app.localUploadFile
import dev.obiente.nextcloudnative.app.prepareMultipartUpload
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import okio.Buffer
import okio.ForwardingSink
import okio.blackholeSink
import okio.buffer

class AndroidStreamingMultipartRequestBodyTest {
    @Test
    fun multipartBodyCannotBeReplayedByOkHttp() {
        assertTrue(AndroidStreamingMultipartRequestBody(upload()) { "test".byteInputStream() }.isOneShot())
    }

    @Test
    fun sourceOpenFailureIsTypedAsLocalUploadIo() {
        val sourceFailure = IOException("content URI permission expired")

        val thrown = assertFailsWith<JvmLocalUploadSourceIOException> {
            AndroidStreamingMultipartRequestBody(upload()) { throw sourceFailure }.writeTo(Buffer())
        }

        assertSame(sourceFailure, thrown.cause)
    }

    @Test
    fun staleSourceOpenFailureIsTypedAsLocalUploadIo() {
        val sourceFailure = IllegalStateException("local selection expired")

        val thrown = assertFailsWith<JvmLocalUploadSourceIOException> {
            AndroidStreamingMultipartRequestBody(upload()) { throw sourceFailure }.writeTo(Buffer())
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
            AndroidStreamingMultipartRequestBody(upload(16_384)) {
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
