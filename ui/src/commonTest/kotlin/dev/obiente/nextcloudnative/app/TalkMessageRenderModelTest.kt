package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TalkMessageRenderModelTest {
    @Test
    fun classifiesVoiceMessageWithoutRequestingRasterPreview() {
        val model = parseAttachmentModel(
            messageType = "voice-message",
            mimeType = "audio/ogg",
            previewAvailable = true,
        )

        assertEquals(TalkAttachmentVisual.Voice, model.visual)
        assertFalse(model.canLoadServerRaster)
        assertTrue(model.canDownloadOriginal)
    }

    @Test
    fun classifiesVideoRecordingAndAllowsBoundedServerPreview() {
        val model = parseAttachmentModel(
            messageType = "record-video",
            mimeType = "video/mp4",
            previewAvailable = true,
        )

        assertEquals(TalkAttachmentVisual.VideoRecording, model.visual)
        assertTrue(model.canLoadServerRaster)
    }

    @Test
    fun preservesPreviewButBlocksOriginalForNoDownloadImage() {
        val model = parseAttachmentModel(
            messageType = "comment",
            mimeType = "image/jpeg",
            previewAvailable = true,
            hideDownload = true,
        )

        assertEquals(TalkAttachmentVisual.Image, model.visual)
        assertTrue(model.canLoadServerRaster)
        assertFalse(model.canDownloadOriginal)
        assertTrue(model.canOpen)
        assertFalse(model.asNextcloudFile().originalAccessAllowed)
    }

    @Test
    fun blocksNoDownloadAudioWhenNoReadOnlyPreviewExists() {
        val model = parseAttachmentModel(
            messageType = "record-audio",
            mimeType = "audio/mp4",
            previewAvailable = false,
            hideDownload = true,
        )

        assertEquals(TalkAttachmentVisual.AudioRecording, model.visual)
        assertFalse(model.canLoadServerRaster)
        assertFalse(model.canDownloadOriginal)
        assertFalse(model.canOpen)
    }

    @Test
    fun originalAccessPolicySurvivesGenericFileHandoff() {
        val downloadable = parseAttachmentModel(
            messageType = "voice-message",
            mimeType = "audio/wav",
            previewAvailable = false,
        )
        val previewOnly = parseAttachmentModel(
            messageType = "comment",
            mimeType = "image/png",
            previewAvailable = true,
            hideDownload = true,
        )

        assertTrue(downloadable.asNextcloudFile().originalAccessAllowed)
        assertFalse(previewOnly.asNextcloudFile().originalAccessAllowed)
        assertTrue(previewOnly.asNextcloudFile().hasPreview)
    }

    @Test
    fun mapsDeletedAndCallMessagesToDedicatedRenderModels() {
        val deleted = requireNotNull(
            parseTalkMessageJson(
                """{"id":1,"message":"","messageParameters":{},"messageType":"comment_deleted"}""",
            ),
        ).toRenderModel()
        val call = requireNotNull(
            parseTalkMessageJson(
                """{"id":2,"message":"Ada missed a call","messageParameters":{},"messageType":"system","systemMessage":"call_missed","actorDisplayName":"Ada"}""",
            ),
        ).toRenderModel()

        assertIs<TalkMessageRenderModel.Deleted>(deleted)
        val event = assertIs<TalkMessageRenderModel.Event>(call)
        assertEquals(TalkMessageRenderKind.CallEvent, event.kind)
        assertEquals(TalkEventTone.Warning, event.tone)
    }

    @Test
    fun parsesLiveCompatibleEmptyParametersAndRichVoiceFileShape() {
        val text = requireNotNull(
            parseTalkMessageJson(
                """{
                  "id":41,
                  "message":"Plain text",
                  "messageParameters":[],
                  "messageType":"comment",
                  "markdown":false
                }""",
            ),
        )
        val voice = requireNotNull(
            parseTalkMessageJson(
                """{
                  "id":42,
                  "message":"{file}",
                  "messageParameters":{
                    "file":{
                      "type":"file",
                      "id":"904",
                      "name":"voice.wav",
                      "path":"Talk/voice.wav",
                      "link":"/f/904",
                      "mimetype":"audio/wav",
                      "size":"1024",
                      "preview-available":"no",
                      "hide-download":"no",
                      "width":"0",
                      "height":"0",
                      "permissions":"1",
                      "etag":"synthetic"
                    }
                  },
                  "messageType":"voice-message"
                }""",
            ),
        )

        assertIs<TalkMessageContent.Text>(text.content)
        val attachment = assertIs<TalkMessageContent.FileShare>(voice.content).attachment
        assertEquals(TalkAttachmentKind.Voice, attachment.kind)
        assertEquals("audio/wav", attachment.mimeType)
        assertEquals(1_024L, attachment.size)
        assertFalse(attachment.hideDownload)
    }

    @Test
    fun olderTalkPagesMergeWithoutRepeatingCursorBoundary() {
        fun message(id: Long) = requireNotNull(
            parseTalkMessageJson(
                """{"id":$id,"message":"Message","messageParameters":[],"messageType":"comment"}""",
            ),
        )
        val current = listOf(message(5), message(4), message(3))
        val older = listOf(message(3), message(2), message(1))

        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), mergeTalkMessageHistory(current, older).map(TalkMessage::id))
    }

    @Test
    fun readerNeverDownloadsWhenTalkHidesTheOriginal() = runBlocking {
        val backend = FakeDownloadBackend()
        val model = parseAttachmentModel(
            messageType = "comment",
            mimeType = "image/jpeg",
            previewAvailable = true,
            hideDownload = true,
        )

        val result = TalkAttachmentReader(backend).read(model)

        assertEquals(0, backend.calls)
        assertEquals(
            TalkAttachmentReadUnavailableReason.DownloadHidden,
            assertIs<TalkAttachmentReadResult.Unavailable>(result).reason,
        )
    }

    @Test
    fun readerRejectsDeclaredOversizeAudioBeforeTransport() = runBlocking {
        val backend = FakeDownloadBackend()
        val attachment = parseAttachmentModel(
            messageType = "record-audio",
            mimeType = "audio/ogg",
            previewAvailable = false,
            size = 65L * 1024L * 1024L,
        )

        val result = TalkAttachmentReader(backend).read(attachment)

        assertEquals(0, backend.calls)
        assertEquals(
            TalkAttachmentReadUnavailableReason.FileTooLarge,
            assertIs<TalkAttachmentReadResult.Unavailable>(result).reason,
        )
    }

    private fun parseAttachmentModel(
        messageType: String,
        mimeType: String,
        previewAvailable: Boolean,
        hideDownload: Boolean = false,
        size: Long = 4_096,
    ): TalkAttachmentRenderModel {
        val message = requireNotNull(
            parseTalkMessageJson(
                """
                {
                  "id": 11,
                  "actorDisplayName": "Ada",
                  "message": "{file}",
                  "messageParameters": {
                    "file": {
                      "type": "file",
                      "id": "901",
                      "name": "recording.bin",
                      "path": "Talk/recording.bin",
                      "mimetype": "$mimeType",
                      "size": "$size",
                      "preview-available": "${if (previewAvailable) "yes" else "no"}",
                      "hide-download": "${if (hideDownload) "yes" else "no"}"
                    }
                  },
                  "messageType": "$messageType"
                }
                """.trimIndent(),
            ),
        )
        return assertIs<TalkMessageRenderModel.Attachments>(message.toRenderModel()).items.single()
    }

    private class FakeDownloadBackend : TalkAttachmentDownloadBackend {
        var calls = 0

        override suspend fun download(path: String, maxBytes: Long): NextcloudFileContent {
            calls += 1
            return NextcloudFileContent(byteArrayOf(1), "application/octet-stream", "etag")
        }
    }
}
