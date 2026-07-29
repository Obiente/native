package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlatformVideoPlaybackTest {
    @Test
    fun capabilityFailureUsesSanitizedFormatDetails() {
        val failure = NativeVideoPlaybackFailure.FormatExceedsCapabilities(
            NativeVideoFormatSummary(
                mimeType = "video/hevc",
                codec = "hvc1.2.4.L150.B0",
                width = 1_728,
                height = 1_296,
                frameRate = 30.03f,
            ),
        )

        assertEquals(
            "This video cannot play with the device decoder",
            failure.userTitle(),
        )
        assertEquals(
            "Its HEVC, 1728 x 1296 stream exceeds this device's decoder capabilities. " +
                "The original file is unchanged.",
            failure.userDetail(),
        )
    }

    @Test
    fun playbackFailureCopyNeverContainsTransportOrRendererDiagnostics() {
        val failure = NativeVideoPlaybackFailure.FormatUnsupported(
            NativeVideoFormatSummary(
                mimeType = "video/example",
                codec = "private-codec-value",
                width = null,
                height = null,
                frameRate = null,
            ),
        )

        val copy = failure.userTitle() + " " + failure.userDetail()

        assertFalse(copy.contains("private-codec-value"))
        assertFalse(copy.contains("MediaCodecVideoRenderer"))
        assertFalse(copy.contains("http://"))
        assertFalse(copy.contains("https://"))
        assertFalse(copy.contains("Authorization"))
    }

    @Test
    fun softwareFallbackIsLimitedToDecoderFailures() {
        assertTrue(
            NativeVideoPlaybackFailure.FormatExceedsCapabilities(format = null)
                .canUseSoftwareFallback(),
        )
        assertTrue(
            NativeVideoPlaybackFailure.FormatUnsupported(format = null)
                .canUseSoftwareFallback(),
        )
        assertTrue(
            NativeVideoPlaybackFailure.DecoderInitializationFailed(format = null)
                .canUseSoftwareFallback(),
        )
        assertTrue(
            NativeVideoPlaybackFailure.DecodeFailed(format = null)
                .canUseSoftwareFallback(),
        )
        assertFalse(NativeVideoPlaybackFailure.NetworkUnavailable.canUseSoftwareFallback())
        assertFalse(NativeVideoPlaybackFailure.AccessDenied.canUseSoftwareFallback())
        assertFalse(NativeVideoPlaybackFailure.SourceChanged.canUseSoftwareFallback())
        assertFalse(NativeVideoPlaybackFailure.MalformedMedia.canUseSoftwareFallback())
        assertFalse(NativeVideoPlaybackFailure.Unknown.canUseSoftwareFallback())
    }

    @Test
    fun livePhotoMotionFailureKeepsCompatibilityWithoutOfferingStillHandoff() {
        val actions = nativeVideoFailureActionPolicy(
            failure = NativeVideoPlaybackFailure.DecodeFailed(format = null),
            compatibilityAvailable = true,
            motionOnly = true,
        )

        assertTrue(actions.showCompatibilityAction)
        assertFalse(actions.showExternalAction)
    }

    @Test
    fun ordinaryVideoFailureCanStillOfferExternalHandoff() {
        val actions = nativeVideoFailureActionPolicy(
            failure = NativeVideoPlaybackFailure.DecodeFailed(format = null),
            compatibilityAvailable = false,
            motionOnly = false,
        )

        assertFalse(actions.showCompatibilityAction)
        assertTrue(actions.showExternalAction)
    }

    @Test
    fun seekableVideoReadsAheadWithoutDownloadingTheWholeFile() = runBlocking {
        val content = ByteArray(2_048) { index -> (index % 251).toByte() }
        val reads = mutableListOf<Pair<Long, Int>>()
        val cache = NativeVideoRangeCache(
            source = NativeVideoRangeSource(
                size = content.size.toLong(),
                readBlock = { offset, length ->
                    reads += offset to length
                    content.copyOfRange(offset.toInt(), offset.toInt() + length)
                },
            ),
            readAheadBytes = 512,
        )

        assertEquals(content.copyOfRange(100, 164).toList(), cache.read(100, 64).toList())
        assertEquals(content.copyOfRange(164, 228).toList(), cache.read(164, 64).toList())
        assertEquals(listOf(100L to 512), reads)
        assertEquals(content.copyOfRange(1_900, 2_048).toList(), cache.read(1_900, 148).toList())
        assertEquals(listOf(100L to 512, 1_900L to 148), reads)
        assertEquals(content.copyOfRange(228, 292).toList(), cache.read(228, 64).toList())
        assertEquals(listOf(100L to 512, 1_900L to 148), reads)
    }

    @Test
    fun seekableVideoCacheRejectsOutOfBoundsAndIncompleteReads() = runBlocking {
        val incomplete = NativeVideoRangeCache(
            source = NativeVideoRangeSource(
                size = 1_024,
                readBlock = { _, length -> ByteArray(length - 1) },
            ),
            readAheadBytes = 128,
        )

        assertFailsWith<IllegalArgumentException> {
            incomplete.read(offset = 1_000, length = 25)
        }
        assertFailsWith<IllegalArgumentException> {
            incomplete.read(offset = 0, length = 64)
        }
        Unit
    }

    @Test
    fun httpFailuresRetainTheirActualMeaning() {
        assertEquals(
            NativeVideoPlaybackFailure.AccessDenied,
            nativeVideoPlaybackFailureForHttpStatus(403),
        )
        assertEquals(
            NativeVideoPlaybackFailure.SourceChanged,
            nativeVideoPlaybackFailureForHttpStatus(412),
        )
        assertEquals(
            NativeVideoPlaybackFailure.NetworkUnavailable,
            nativeVideoPlaybackFailureForHttpStatus(429),
        )
        assertEquals(
            NativeVideoPlaybackFailure.NetworkUnavailable,
            nativeVideoPlaybackFailureForHttpStatus(503),
        )
        assertEquals(
            NativeVideoPlaybackFailure.Unknown,
            nativeVideoPlaybackFailureForHttpStatus(400),
        )
    }
}
