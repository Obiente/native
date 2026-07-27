package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopStreamingMultipartRequestBodyTest {
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

        assertTrue(DesktopStreamingMultipartRequestBody(upload) { "test".byteInputStream() }.isOneShot())
    }
}
