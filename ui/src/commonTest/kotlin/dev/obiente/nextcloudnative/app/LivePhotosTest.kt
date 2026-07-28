package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
    fun discoveryRequiresVerifiedMemoriesRoutesAndNativePlayback() {
        val candidate = photo("ordinary.jpg", "image/jpeg")
        val compatible = MemoriesLivePhotoCapability.CompatibleVersion("8.1.0")

        assertTrue(
            candidate.shouldDiscoverMemoriesLivePhoto(
                capability = compatible,
                nativePlaybackAvailable = true,
            ),
        )
        assertFalse(
            candidate.shouldDiscoverMemoriesLivePhoto(
                capability = MemoriesLivePhotoCapability.NotAdvertised,
                nativePlaybackAvailable = true,
            ),
        )
        assertFalse(
            candidate.shouldDiscoverMemoriesLivePhoto(
                capability = MemoriesLivePhotoCapability.UnsupportedVersion("9.0.0"),
                nativePlaybackAvailable = true,
            ),
        )
        assertFalse(
            candidate.shouldDiscoverMemoriesLivePhoto(
                capability = compatible,
                nativePlaybackAvailable = false,
            ),
        )
        assertFalse(
            photo("capture.png", "image/png").shouldDiscoverMemoriesLivePhoto(
                capability = compatible,
                nativePlaybackAvailable = true,
            ),
        )
    }

    @Test
    fun describeCapabilityAcceptsOnlyTheAuditedMemoriesVersionRange() {
        val request = memoriesDescribeRequest()

        assertEquals(NextcloudApiMethod.GET, request.method)
        assertEquals("/index.php/apps/memories/api/describe", request.relativePath)
        assertTrue(request.maximumResponseBytes <= 64L * 1_024L)
        assertEquals(NextcloudApiCachePolicy.ForceNetwork, request.cachePolicy)
        assertIs<MemoriesLivePhotoCapability.CompatibleVersion>(
            parseMemoriesLivePhotoCapability(apiResponse(200, """{"version":"5.2.0"}""")),
        )
        assertIs<MemoriesLivePhotoCapability.CompatibleVersion>(
            parseMemoriesLivePhotoCapability(apiResponse(200, """{"version":"8.1.0"}""")),
        )
        assertIs<MemoriesLivePhotoCapability.UnsupportedVersion>(
            parseMemoriesLivePhotoCapability(apiResponse(200, """{"version":"5.1.0"}""")),
        )
        assertIs<MemoriesLivePhotoCapability.UnsupportedVersion>(
            parseMemoriesLivePhotoCapability(apiResponse(200, """{"version":"8.1.1"}""")),
        )
        assertIs<MemoriesLivePhotoCapability.Unverified>(
            parseMemoriesLivePhotoCapability(apiResponse(404, """{"version":"8.1.0"}""")),
        )
        assertIs<MemoriesLivePhotoCapability.Unverified>(
            parseMemoriesLivePhotoCapability(apiResponse(200, """{"version":"next"}""")),
        )
    }

    @Test
    fun validatedMediaReferenceIsDirectCapabilityEvidence() {
        val candidate = photo("motion.jpg", "image/jpeg").copy(
            livePhoto = NextcloudLivePhotoReference("self__trailer"),
        )

        assertIs<MemoriesLivePhotoCapability.ObservedReference>(
            candidate.effectiveLivePhotoCapability(MemoriesLivePhotoCapability.Unverified),
        )
        assertTrue(
            candidate.shouldDiscoverMemoriesLivePhoto(
                capability = candidate.effectiveLivePhotoCapability(
                    MemoriesLivePhotoCapability.Unverified,
                ),
                nativePlaybackAvailable = true,
            ),
        )
    }

    @Test
    fun lookupFailuresNeverConsumeCancellation() = runBlocking {
        assertEquals("motion", livePhotoLookupOrNull { "motion" })
        assertNull(livePhotoLookupOrNull<String> { error("lookup failed") })

        val cancellation = CancellationException("selected photo changed")
        val thrown = assertFailsWith<CancellationException> {
            livePhotoLookupOrNull<String> { throw cancellation }
        }

        assertTrue(thrown === cancellation)
    }

    @Test
    fun discoveryIdentityChangesWithRemoteGeneration() {
        val first = photo("ordinary.jpg", "image/jpeg")
        val second = first.copy(etag = "generation-2")
        val third = second.copy(
            size = 2_048L,
            lastModified = "2026-07-28T12:00:00Z",
        )

        assertFalse(first.livePhotoDiscoveryIdentity() == second.livePhotoDiscoveryIdentity())
        assertFalse(second.livePhotoDiscoveryIdentity() == third.livePhotoDiscoveryIdentity())
    }

    @Test
    fun memoriesPlaybackAddsOcsHeaderAndRestoresStillAtEnd() {
        val authorization = "Basic opaque"
        val still = photo("motion.jpg", "image/jpeg")
        val live = MemoriesLivePhotoSource(
            fileId = 42L,
            reference = NextcloudLivePhotoReference("self__trailer"),
            etag = "generation-1",
        )
        val davSource = NativeVideoPlaybackSource.DavFile(still)
        val memoriesSource = NativeVideoPlaybackSource.MemoriesLivePhoto(live)

        assertEquals(
            mapOf("Authorization" to authorization),
            davSource.authenticatedRequestProperties(authorization),
        )
        assertEquals(
            mapOf(
                "Authorization" to authorization,
                "OCS-APIRequest" to "true",
            ),
            memoriesSource.authenticatedRequestProperties(authorization),
        )
        assertFalse(davSource.restoresStillAfterPlaybackEnds())
        assertTrue(memoriesSource.restoresStillAfterPlaybackEnds())
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
