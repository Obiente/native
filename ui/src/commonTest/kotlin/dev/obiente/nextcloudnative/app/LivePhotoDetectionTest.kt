package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LivePhotoDetectionTest {
    @Test
    fun detectsModernAndroidMotionPhotoOnlyAfterExactVideoProbe() {
        val fixture = SyntheticLivePhotoFixtures.modernAndroid

        val candidate = planAndroidMotionPhotoProbe(
            primaryMimeType = fixture.primaryMimeType,
            containerSize = fixture.containerSize,
            xmpBytes = fixture.xmp,
        )

        assertEquals(AndroidMotionPhotoMetadataKind.MotionPhotoV1, candidate?.metadataKind)
        assertEquals(LivePhotoByteRange(8_192L, 2_048L), candidate?.videoRange)
        assertEquals(750_000L, candidate?.photoPresentationTimestampUs)
        val detected = confirmAndroidMotionPhoto(
            requireNotNull(candidate),
            LivePhotoVideoProbe(candidate.videoRange.offset, fixture.videoProbe),
        )
        assertIs<DetectedLivePhotoAsset.EmbeddedAndroidMotionPhoto>(detected)
        assertEquals("video/mp4", detected.motionMimeType)
    }

    @Test
    fun supportsNamespaceAliasesAndQuickTimeMotionItem() {
        val fixture = SyntheticLivePhotoFixtures.modernAndroid.copy(
            primaryMimeType = "image/heic",
            xmp = SyntheticLivePhotoFixtures.modernXmp(
                primaryMimeType = "image/heic",
                motionMimeType = "video/quicktime",
                videoLength = 2_048L,
                cameraPrefix = "Camera",
                containerPrefix = "Media",
                itemPrefix = "Part",
                primaryPadding = 8L,
            ).decodeToString()
                .replace(" Camera:MotionPhotoPresentationTimestampUs=\"750000\"", "")
                .encodeToByteArray(),
        )

        val candidate = planAndroidMotionPhotoProbe(
            fixture.primaryMimeType,
            fixture.containerSize,
            fixture.xmp,
        )

        assertEquals("image/heic", candidate?.primaryMimeType)
        assertEquals("video/quicktime", candidate?.motionMimeType)
        assertNull(candidate?.photoPresentationTimestampUs)
    }

    @Test
    fun detectsLegacyMicroVideoTrailer() {
        val fixture = SyntheticLivePhotoFixtures.legacyAndroid

        val candidate = planAndroidMotionPhotoProbe(
            fixture.primaryMimeType,
            fixture.containerSize,
            fixture.xmp,
        )

        assertEquals(AndroidMotionPhotoMetadataKind.LegacyMicroVideoV1, candidate?.metadataKind)
        assertEquals(LivePhotoByteRange(7_168L, 3_072L), candidate?.videoRange)
        assertEquals(-1L, candidate?.photoPresentationTimestampUs)
        assertIs<DetectedLivePhotoAsset.EmbeddedAndroidMotionPhoto>(
            confirmAndroidMotionPhoto(
                requireNotNull(candidate),
                LivePhotoVideoProbe(candidate.videoRange.offset, fixture.videoProbe),
            ),
        )
    }

    @Test
    fun residualOrMismatchedMetadataRemainsAnOrdinaryPhoto() {
        val fixture = SyntheticLivePhotoFixtures.modernAndroid
        val candidate = requireNotNull(
            planAndroidMotionPhotoProbe(fixture.primaryMimeType, fixture.containerSize, fixture.xmp),
        )

        assertNull(
            confirmAndroidMotionPhoto(
                candidate,
                LivePhotoVideoProbe(candidate.videoRange.offset, "not a video".encodeToByteArray()),
            ),
        )
        assertNull(
            confirmAndroidMotionPhoto(
                candidate,
                LivePhotoVideoProbe(candidate.videoRange.offset + 1L, fixture.videoProbe),
            ),
        )
        assertNull(
            planAndroidMotionPhotoProbe(
                fixture.primaryMimeType,
                fixture.containerSize,
                fixture.xmp.decodeToString()
                    .replace("Camera:MotionPhoto=\"1\"", "Camera:MotionPhoto=\"0\"")
                    .encodeToByteArray(),
            ),
        )
    }

    @Test
    fun rejectsMalformedTruncatedAndContradictoryModernMetadata() {
        val fixture = SyntheticLivePhotoFixtures.modernAndroid
        val malformed = listOf(
            fixture.xmp.copyOf(fixture.xmp.size - 7),
            fixture.xmp.decodeToString()
                .replace("Item:Length=\"2048\"", "Item:Length=\"999999\"")
                .encodeToByteArray(),
            fixture.xmp.decodeToString()
                .replace("Item:Semantic=\"MotionPhoto\"", "Item:Semantic=\"Primary\"")
                .encodeToByteArray(),
            fixture.xmp.decodeToString()
                .replace(
                    "Camera:MotionPhotoPresentationTimestampUs=\"750000\"",
                    "Camera:MotionPhotoPresentationTimestampUs=\"invalid\"",
                )
                .encodeToByteArray(),
            fixture.xmp.decodeToString()
                .replace(
                    "Camera:MotionPhotoVersion=\"1\"",
                    "Camera:MotionPhotoVersion=\"1\" CameraAlias:MotionPhotoVersion=\"1\"",
                )
                .replace(
                    "xmlns:Camera=\"http://ns.google.com/photos/1.0/camera/\"",
                    """
                        xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
                        xmlns:CameraAlias="http://ns.google.com/photos/1.0/camera/"
                    """.trimIndent(),
                )
                .encodeToByteArray(),
            fixture.xmp.decodeToString()
                .replace("</rdf:Seq>", "<Container:Item")
                .encodeToByteArray(),
            byteArrayOf(0xC3.toByte(), 0x28),
        )

        malformed.forEach { xmp ->
            assertNull(planAndroidMotionPhotoProbe(fixture.primaryMimeType, fixture.containerSize, xmp))
        }
    }

    @Test
    fun rejectsUnknownVersionsUnsupportedRawAndLegacyOverflows() {
        val modern = SyntheticLivePhotoFixtures.modernAndroid
        val legacy = SyntheticLivePhotoFixtures.legacyAndroid

        assertNull(
            planAndroidMotionPhotoProbe(
                modern.primaryMimeType,
                modern.containerSize,
                modern.xmp.decodeToString()
                    .replace("Camera:MotionPhotoVersion=\"1\"", "Camera:MotionPhotoVersion=\"2\"")
                    .encodeToByteArray(),
            ),
        )
        assertNull(planAndroidMotionPhotoProbe("image/x-fuji-raf", modern.containerSize, modern.xmp))
        assertNull(
            planAndroidMotionPhotoProbe(
                legacy.primaryMimeType,
                legacy.containerSize,
                legacy.xmp.decodeToString()
                    .replace("Camera:MicroVideoOffset=\"3072\"", "Camera:MicroVideoOffset=\"10240\"")
                    .encodeToByteArray(),
            ),
        )
    }

    @Test
    fun pairsAppleStillAndMovOnlyByExactContentIdentifier() {
        val still = SyntheticLivePhotoFixtures.appleStill
        val video = SyntheticLivePhotoFixtures.appleVideo

        val paired = pairAppleLivePhoto(still, video)

        assertIs<DetectedLivePhotoAsset.PairedAppleLivePhoto>(paired)
        assertEquals(SyntheticLivePhotoFixtures.appleContentIdentifier, paired.contentIdentifier)
        assertEquals(still.resourceIdentity, paired.still.resourceIdentity)
        assertEquals(video.resourceIdentity, paired.pairedVideo.resourceIdentity)
    }

    @Test
    fun appleFilenameAndTimestampStyleSimilarityNeverOverridesIdentifierEvidence() {
        val still = SyntheticLivePhotoFixtures.appleStill
        val video = SyntheticLivePhotoFixtures.appleVideo

        assertNull(pairAppleLivePhoto(still, video.copy(contentIdentifier = "different-identifier")))
        assertNull(pairAppleLivePhoto(still.copy(contentIdentifier = null), video))
        assertNull(pairAppleLivePhoto(still, video.copy(contentIdentifier = " ${video.contentIdentifier}")))
        assertNull(pairAppleLivePhoto(still, video.copy(mimeType = "video/mp4")))
        assertNull(pairAppleLivePhoto(still.copy(mimeType = "image/x-canon-cr3"), video))
        assertNull(pairAppleLivePhoto(still, video.copy(resourceIdentity = still.resourceIdentity)))
        assertNull(pairAppleLivePhoto(still, video.copy(pairingScopeIdentity = "another-account")))
        assertNull(
            pairAppleLivePhoto(
                still.copy(displayName = "renamed-still.heic"),
                video.copy(
                    displayName = "unrelated-name.mov",
                    contentIdentifier = "different-identifier",
                ),
            ),
        )
    }
}

private data class SyntheticEmbeddedMotionPhotoFixture(
    val primaryMimeType: String,
    val containerSize: Long,
    val xmp: ByteArray,
    val videoProbe: ByteArray,
)

private object SyntheticLivePhotoFixtures {
    const val appleContentIdentifier = "8EA9DEB3-47C6-4B3F-894B-27A79D7EE012"

    val modernAndroid = SyntheticEmbeddedMotionPhotoFixture(
        primaryMimeType = "image/jpeg",
        containerSize = 10_240L,
        xmp = modernXmp(
            primaryMimeType = "image/jpeg",
            motionMimeType = "video/mp4",
            videoLength = 2_048L,
            cameraPrefix = "Camera",
            containerPrefix = "Container",
            itemPrefix = "Item",
            primaryPadding = 0L,
        ),
        videoProbe = isoBaseMediaVideoProbe("isom"),
    )

    val legacyAndroid = SyntheticEmbeddedMotionPhotoFixture(
        primaryMimeType = "image/jpeg",
        containerSize = 10_240L,
        xmp = """
            <?xpacket begin=""?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF
                  xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                  xmlns:Camera="http://ns.google.com/photos/1.0/camera/">
                <rdf:Description
                    Camera:MicroVideo="1"
                    Camera:MicroVideoVersion="1"
                    Camera:MicroVideoOffset="3072"
                    Camera:MicroVideoPresentationTimestampUs="-1"/>
              </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent().encodeToByteArray(),
        videoProbe = isoBaseMediaVideoProbe("mp42"),
    )

    val appleStill = AppleLivePhotoComponent(
        pairingScopeIdentity = "account:test:photos-asset:42",
        resourceIdentity = "photos-resource:still:42",
        displayName = "IMG_0042.HEIC",
        mimeType = "image/heic",
        role = AppleLivePhotoComponentRole.Still,
        contentIdentifier = appleContentIdentifier,
    )

    val appleVideo = AppleLivePhotoComponent(
        pairingScopeIdentity = "account:test:photos-asset:42",
        resourceIdentity = "photos-resource:paired-video:42",
        displayName = "IMG_0042.MOV",
        mimeType = "video/quicktime",
        role = AppleLivePhotoComponentRole.PairedVideo,
        contentIdentifier = appleContentIdentifier,
    )

    fun modernXmp(
        primaryMimeType: String,
        motionMimeType: String,
        videoLength: Long,
        cameraPrefix: String,
        containerPrefix: String,
        itemPrefix: String,
        primaryPadding: Long,
    ): ByteArray = """
        <?xpacket begin=""?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
          <rdf:RDF
              xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
              xmlns:$cameraPrefix="http://ns.google.com/photos/1.0/camera/"
              xmlns:$containerPrefix="http://ns.google.com/photos/1.0/container/"
              xmlns:$itemPrefix="http://ns.google.com/photos/1.0/container/item/">
            <rdf:Description
                $cameraPrefix:MotionPhoto="1"
                $cameraPrefix:MotionPhotoVersion="1"
                $cameraPrefix:MotionPhotoPresentationTimestampUs="750000">
              <$containerPrefix:Directory>
                <rdf:Seq>
                  <rdf:li rdf:parseType="Resource">
                    <$containerPrefix:Item
                        $itemPrefix:Mime="$primaryMimeType"
                        $itemPrefix:Semantic="Primary"
                        $itemPrefix:Length="0"
                        $itemPrefix:Padding="$primaryPadding"/>
                  </rdf:li>
                  <rdf:li rdf:parseType="Resource">
                    <$containerPrefix:Item
                        $itemPrefix:Mime="$motionMimeType"
                        $itemPrefix:Semantic="MotionPhoto"
                        $itemPrefix:Length="$videoLength"/>
                  </rdf:li>
                </rdf:Seq>
              </$containerPrefix:Directory>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent().encodeToByteArray()

    private fun isoBaseMediaVideoProbe(majorBrand: String): ByteArray =
        byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            majorBrand[0].code.toByte(),
            majorBrand[1].code.toByte(),
            majorBrand[2].code.toByte(),
            majorBrand[3].code.toByte(),
            0x00, 0x00, 0x00, 0x00,
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte(),
        )
}
