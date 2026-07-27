package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudApiMethod
import dev.obiente.nextcloudnative.app.NextcloudMultipartUploadRequest
import dev.obiente.nextcloudnative.app.localUploadFile
import dev.obiente.nextcloudnative.app.prepareMultipartUpload
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidStreamingMultipartRequestBodyTest {
    @Test
    fun multipartBodyCannotBeReplayedByOkHttp() {
        val file = localUploadFile("synthetic-selection-1", "sample.txt", "text/plain", 4)
        val upload = prepareMultipartUpload(
            NextcloudMultipartUploadRequest(
                method = NextcloudApiMethod.POST,
                relativePath = "/index.php/apps/deck/api/v1.1/boards/1/stacks/2/cards/3/attachments",
                file = file,
            ),
            "synthetic-boundary",
        )

        assertTrue(AndroidStreamingMultipartRequestBody(upload) { "test".byteInputStream() }.isOneShot())
    }
}
