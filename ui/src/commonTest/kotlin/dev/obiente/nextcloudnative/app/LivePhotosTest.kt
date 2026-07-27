package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LivePhotosTest {
    @Test
    fun plansBoundedSameOriginLookupAndEncodedPlaybackUrl() {
        val info = memoriesLivePhotoInfoRequest(42L)
        val day = memoriesLivePhotoDayRequest(20260727L)
        val source = MemoriesLivePhotoSource(
            fileId = 42L,
            reference = NextcloudLivePhotoReference("motion id/with+symbols"),
            etag = "generation-1",
        )
        val playback = memoriesLivePhotoVideoRequest(source)

        assertEquals("/index.php/apps/memories/api/image/info/42", info.relativePath)
        assertEquals(mapOf("basic" to "1"), info.queryParameters)
        assertTrue(info.ocsApiRequest)
        assertEquals("/index.php/apps/memories/api/days/20260727", day.relativePath)
        assertEquals(
            "https://cloud.invalid/index.php/apps/memories/api/video/livephoto/42" +
                "?etag=generation-1&liveid=motion%20id%2Fwith%2Bsymbols",
            buildNextcloudApiUrl("https://cloud.invalid", playback),
        )
    }

    @Test
    fun resolvesOnlyTheRequestedFileFromTheReportedDay() {
        val dayId = parseMemoriesLivePhotoDayId(
            apiResponse(200, """{"fileid":42,"dayid":20260727,"ignored":true}"""),
            expectedFileId = 42L,
        )
        val source = parseMemoriesLivePhotoSource(
            apiResponse(
                200,
                """[
                    {"fileid":41,"dayid":20260727,"etag":"still","basename":"ordinary.jpg"},
                    {"fileid":42,"dayid":20260727,"etag":"motion","liveid":"self__trailer"}
                ]""",
            ),
            expectedFileId = 42L,
            expectedDayId = dayId,
        )

        assertEquals(20260727L, dayId)
        assertEquals(42L, source?.fileId)
        assertEquals("self__trailer", source?.reference?.serverToken)
        assertEquals("motion", source?.etag)
    }

    @Test
    fun ordinaryPhotosRemainOrdinaryAndUnsupportedFormatsSkipLookup() {
        val source = parseMemoriesLivePhotoSource(
            apiResponse(
                200,
                """[{"fileid":42,"dayid":20260727,"etag":"still","basename":"ordinary.jpg"}]""",
            ),
            expectedFileId = 42L,
            expectedDayId = 20260727L,
        )

        assertNull(source)
        assertTrue(photo("ordinary.jpg", "image/jpeg").canResolveMemoriesLivePhoto())
        assertTrue(photo("capture.heic", "image/heic").canResolveMemoriesLivePhoto())
        assertFalse(photo("capture.png", "image/png").canResolveMemoriesLivePhoto())
        assertFalse(photo("capture.raf", "image/x-fuji-raf").canResolveMemoriesLivePhoto())
    }

    @Test
    fun rejectsWrongIdentitiesDaysDuplicatesAndUnsafeTokens() {
        assertFailsWith<IllegalArgumentException> {
            parseMemoriesLivePhotoDayId(
                apiResponse(200, """{"fileid":41,"dayid":20260727}"""),
                expectedFileId = 42L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseMemoriesLivePhotoSource(
                apiResponse(200, """[{"fileid":42,"dayid":20260726,"liveid":"self__trailer"}]"""),
                expectedFileId = 42L,
                expectedDayId = 20260727L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseMemoriesLivePhotoSource(
                apiResponse(
                    200,
                    """[
                        {"fileid":42,"dayid":20260727,"liveid":"self__trailer"},
                        {"fileid":42,"dayid":20260727,"liveid":"self__trailer"}
                    ]""",
                ),
                expectedFileId = 42L,
                expectedDayId = 20260727L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NextcloudLivePhotoReference("unsafe\u0000token")
        }
    }

    @Test
    fun playbackUsesMotionOnlyAfterExplicitActivation() {
        val photo = photo("motion.jpg", "image/jpeg")
        val live = MemoriesLivePhotoSource(
            fileId = 42L,
            reference = NextcloudLivePhotoReference("self__trailer"),
            etag = "generation-1",
        )

        assertNull(
            photo.nativeVideoPlaybackSource(
                userId = "user",
                nativePlaybackAvailable = true,
                livePhotoSource = live,
                motionPlaying = false,
            ),
        )
        assertIs<NativeVideoPlaybackSource.MemoriesLivePhoto>(
            photo.nativeVideoPlaybackSource(
                userId = "user",
                nativePlaybackAvailable = true,
                livePhotoSource = live,
                motionPlaying = true,
            ),
        )
        assertNull(
            photo.nativeVideoPlaybackSource(
                userId = "user",
                nativePlaybackAvailable = false,
                livePhotoSource = live,
                motionPlaying = true,
            ),
        )
    }

    private fun photo(name: String, mimeType: String) = NextcloudFile(
        path = "Photos/$name",
        name = name,
        isDirectory = false,
        mimeType = mimeType,
        size = 1_024L,
        lastModified = "2026-07-27T12:00:00Z",
        fileId = 42L,
        hasPreview = true,
        etag = "generation-1",
    )

    private fun apiResponse(status: Int, body: String) = NextcloudApiResponse(
        status = status,
        body = body.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )
}
