package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaStackingTest {
    @Test
    fun mediaSearchRequestsResourceTypeAndBoundsItsResultSet() {
        val body = mediaSearchDavRequestBody("account<&\"'")

        assertTrue("<d:resourcetype/>" in body)
        assertTrue("<d:href>/files/account&lt;&amp;&quot;&apos;</d:href>" in body)
        assertTrue("<d:nresults>$MAXIMUM_MEDIA_SEARCH_RESULTS</d:nresults>" in body)
        assertTrue("<d:literal>image/%</d:literal>" in body)
        assertTrue("<d:literal>video/%</d:literal>" in body)
        assertTrue(rawPhotoFileNameSearchPatterns().none { pattern -> pattern in body })
        assertFailsWith<IllegalArgumentException> {
            mediaSearchDavRequestBody("account", MAXIMUM_MEDIA_SEARCH_RESULTS + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            mediaSearchDavRequestBody("account", rawFileNamePatterns = listOf("%.raf", "../secret"))
        }
    }

    @Test
    fun mediaSearchUsesDisjointMimeAndBoundedRawPartitions() {
        val patterns = rawPhotoFileNameSearchPatterns()
        val requests = mediaSearchDavRequests("account")
        val expectedRawBodyCount =
            (patterns.size + MAXIMUM_RAW_MEDIA_SEARCH_PATTERNS_PER_REQUEST - 1) /
                MAXIMUM_RAW_MEDIA_SEARCH_PATTERNS_PER_REQUEST

        assertEquals(expectedRawBodyCount + 2, requests.size)
        assertEquals(MediaSearchDavPartition.ImageMime, requests[0].partition)
        assertEquals(MediaSearchDavPartition.VideoMime, requests[1].partition)
        assertTrue("<d:literal>image/%</d:literal>" in requests.first().body)
        assertFalse("<d:literal>video/%</d:literal>" in requests.first().body)
        assertFalse("<d:literal>image/%</d:literal>" in requests[1].body)
        assertTrue("<d:literal>video/%</d:literal>" in requests[1].body)
        assertFalse("<d:is-collection/>" in requests.first().body)
        assertFalse("<d:is-collection/>" in requests[1].body)
        assertTrue(patterns.none { pattern -> pattern in requests[0].body || pattern in requests[1].body })
        requests.drop(2).forEach { request ->
            assertEquals(MediaSearchDavPartition.Raw, request.partition)
            assertFalse("<d:literal>image/%</d:literal>" in request.body)
            assertFalse("<d:literal>video/%</d:literal>" in request.body)
            assertTrue("<d:not><d:is-collection/></d:not>" in request.body)
            assertTrue("""<d:like caseless="yes">""" in request.body)
            assertTrue(
                patterns.count { pattern -> "<d:literal>$pattern</d:literal>" in request.body } <=
                    MAXIMUM_RAW_MEDIA_SEARCH_PATTERNS_PER_REQUEST,
            )
        }
        patterns.forEach { pattern ->
            assertEquals(
                1,
                requests.count { request -> "<d:literal>$pattern</d:literal>" in request.body },
                "Expected exactly one bounded request for $pattern.",
            )
        }
        assertTrue(file("Photos/DSCF0001.RAF", "application/octet-stream").isRawPhoto())
        assertTrue(file("Photos/DSCF0002.raf", "application/octet-stream").isRawPhoto())
    }

    @Test
    fun mediaSearchCompatibilityFallbackNeverTreatsAuthOrServerFailuresAsCompatibility() {
        assertTrue(isMediaSearchCompatibilityRejection(400))
        assertTrue(isMediaSearchCompatibilityRejection(422))
        assertFalse(isMediaSearchCompatibilityRejection(401))
        assertFalse(isMediaSearchCompatibilityRejection(403))
        assertFalse(isMediaSearchCompatibilityRejection(500))
    }

    @Test
    fun rawCompatibilityRejectionKeepsCompletedPartitionsWithoutRefetchingMime() = runBlocking {
        val requests = mediaSearchDavRequests("account")
        val executed = mutableListOf<String>()
        var call = 0

        val pages = collectMediaSearchDavPages(
            requests = requests,
            execute = { body ->
                executed += body
                val currentCall = call++
                if (currentCall == 3) {
                    MediaSearchDavTransportResponse(status = 400, body = "rejected".encodeToByteArray())
                } else {
                    MediaSearchDavTransportResponse(
                        status = 207,
                        body = "page-$currentCall".encodeToByteArray(),
                    )
                }
            },
            parse = { body -> listOf(body.decodeToString()) },
        )

        assertEquals(listOf(listOf("page-0"), listOf("page-1"), listOf("page-2")), pages)
        assertEquals(requests.take(4).map(MediaSearchDavRequest::body), executed)
        assertEquals(1, executed.count { body -> body == requests.first().body })
    }

    @Test
    fun mimeAuthPermissionAndServerFailuresAreNeverCompatibilityFallbacks() {
        listOf(400, 401, 403, 500).forEach { status ->
            val failure = assertFailsWith<IllegalStateException> {
                runBlocking {
                    collectMediaSearchDavPages(
                        requests = mediaSearchDavRequests("account"),
                        execute = {
                            MediaSearchDavTransportResponse(
                                status = status,
                                body = "failure".encodeToByteArray(),
                            )
                        },
                        parse = { body -> listOf(body.decodeToString()) },
                    )
                }
            }
            assertEquals("WebDAV media search failed (HTTP $status).", failure.message)
        }
    }

    @Test
    fun rawAuthPermissionAndServerFailuresAreNeverCompatibilityFallbacks() {
        listOf(401, 403, 500).forEach { status ->
            var call = 0
            val failure = assertFailsWith<IllegalStateException> {
                runBlocking {
                    collectMediaSearchDavPages(
                        requests = mediaSearchDavRequests("account"),
                        execute = {
                            if (call++ < 2) {
                                MediaSearchDavTransportResponse(
                                    status = 207,
                                    body = "mime".encodeToByteArray(),
                                )
                            } else {
                                MediaSearchDavTransportResponse(
                                    status = status,
                                    body = "failure".encodeToByteArray(),
                                )
                            }
                        },
                        parse = { body -> listOf(body.decodeToString()) },
                    )
                }
            }
            assertEquals("WebDAV media search failed (HTTP $status).", failure.message)
        }
    }

    @Test
    fun malformedSuccessfulResponseStopsBeforeAnotherPartitionIsRequested() {
        var executions = 0
        val collect: suspend () -> List<List<String>> = {
            collectMediaSearchDavPages(
                requests = mediaSearchDavRequests("account"),
                execute = {
                    executions += 1
                    MediaSearchDavTransportResponse(
                        status = 207,
                        body = "malformed".encodeToByteArray(),
                    )
                },
                parse = { throw IllegalArgumentException("Malformed DAV response.") },
            )
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            runBlocking { collect() }
        }

        assertEquals("Malformed DAV response.", failure.message)
        assertEquals(1, executions)
    }

    @Test
    fun mediaSearchPagesAreGloballySortedDeduplicatedAndBounded() {
        val newest = file("Photos/newest.jpg", "image/jpeg")
            .copy(lastModified = "Sun, 27 Jul 2026 09:00:00 GMT")
        val middleOld = file("Photos/middle.RAF", "application/octet-stream")
            .copy(lastModified = "Sat, 26 Jul 2026 09:00:00 GMT")
        val middleNew = middleOld.copy(
            mimeType = "image/x-fuji-raf",
            lastModified = "Sat, 26 Jul 2026 10:00:00 GMT",
        )
        val oldest = file("Photos/oldest.mp4", "video/mp4")
            .copy(lastModified = "Fri, 25 Jul 2026 09:00:00 GMT")
        val directory = file("Photos/archive.raw", "httpd/unix-directory")
            .copy(isDirectory = true, lastModified = "Mon, 28 Jul 2026 09:00:00 GMT")

        assertEquals(
            listOf(newest, middleNew),
            mergeMediaSearchResultPages(
                pages = listOf(
                    listOf(oldest, middleOld, directory),
                    listOf(newest, middleNew),
                ),
                maximumResults = 2,
            ),
        )
    }

    @Test
    fun mediaSearchDropsCollectionsEvenWhenTheirNamesLookLikeRawFiles() {
        val rawDirectory = file("Photos/archive.raw", "httpd/unix-directory").copy(isDirectory = true)
        val rawFile = file("Photos/frame.RAF", "application/octet-stream")
        val jpegFile = file("Photos/frame.JPG", "image/jpeg")

        assertEquals(
            listOf(rawFile, jpegFile),
            selectMediaSearchFiles(listOf(rawDirectory, rawFile, jpegFile)),
        )
    }

    @Test
    fun rawAndJpegWithTheSameFolderAndStemBecomeOneStack() {
        val raw = file("Photos/Trip/DSCF0001.RAF", "image/x-fuji-raf")
        val jpeg = file("Photos/Trip/DSCF0001.JPG", "image/jpeg")

        val stack = stackMediaFiles(listOf(raw, jpeg)).single()

        assertEquals(jpeg, stack.cover)
        assertEquals(listOf(jpeg, raw), stack.members)
        assertEquals("RAW + JPG", stack.badge)
        assertTrue(stack.hasRaw)
    }

    @Test
    fun sameStemInDifferentFoldersNeverStacks() {
        val stacks = stackMediaFiles(
            listOf(
                file("Photos/A/DSCF0001.RAF", "image/x-fuji-raf"),
                file("Photos/B/DSCF0001.JPG", "image/jpeg"),
            ),
        )

        assertEquals(2, stacks.size)
        assertTrue(stacks.first().hasRaw)
        assertFalse(stacks.last().hasRaw)
    }

    @Test
    fun googleOriginalRawNamingStacksWithItsRenderedSiblingWithoutDuplicatingRaw() {
        val raw = file("Photos/Pixel/PXL_0001.ORIGINAL.dng", "image/x-adobe-dng")
        val jpeg = file("Photos/Pixel/PXL_0001.PORTRAIT.jpg", "image/jpeg")
        val unrelated = file("Photos/Pixel/PXL_0002.jpg", "image/jpeg")

        val stacks = stackMediaFiles(listOf(raw, jpeg, unrelated))

        assertEquals(2, stacks.size)
        assertEquals(listOf(jpeg, raw), stacks.first().members)
        assertEquals("RAW + JPG", stacks.first().badge)
        assertEquals(listOf(jpeg, raw), planMediaSources(listOf(raw, jpeg, unrelated), raw).choices.map { it.file })
    }

    @Test
    fun ordinaryDottedNamesRequireTheirFullStemToMatch() {
        val raw = file("Photos/Trip/scene.one.dng", "image/x-adobe-dng")
        val matching = file("Photos/Trip/scene.one.jpg", "image/jpeg")
        val samePrefix = file("Photos/Trip/scene.two.jpg", "image/jpeg")

        val stacks = stackMediaFiles(listOf(raw, matching, samePrefix))

        assertEquals(listOf(matching, raw), stacks.first().members)
        assertEquals(listOf(samePrefix), stacks.last().members)
    }

    @Test
    fun rawFilesRemainIndividuallyVisibleWithoutARenderedPair() {
        val raw = file("Photos/DSCF0002.RAF", "application/octet-stream")
        val stack = stackMediaFiles(listOf(raw)).single()

        assertEquals(raw, stack.cover)
        assertEquals("RAW", stack.badge)
        assertTrue(raw.isPhotoMedia())
    }

    @Test
    fun clickingAStackCanUseASequenceContainingBothRepresentationsAndFollowingMedia() {
        val raw = file("Photos/DSCF0001.RAF", "image/x-fuji-raf")
        val jpeg = file("Photos/DSCF0001.JPG", "image/jpeg")
        val next = file("Photos/DSCF0002.JPG", "image/jpeg")
        val sequence = stackMediaFiles(listOf(raw, jpeg, next)).flatMap(MediaStack::members)

        assertEquals(listOf(jpeg, raw, next), sequence)
    }

    @Test
    fun selectedRawAndRenderedSiblingRemainExplicitSourceChoices() {
        val raw = file("Photos/DSCF0001.RAF", "image/x-fuji-raf")
        val jpeg = file("Photos/DSCF0001.JPG", "image/jpeg")
        val unrelated = file("Photos/DSCF0002.JPG", "image/jpeg")

        val plan = planMediaSources(listOf(jpeg, raw, unrelated), raw)

        assertEquals(raw, plan.selected.file)
        assertEquals(listOf("JPEG", "RAW"), plan.choices.map(MediaSourceChoice::label))
        assertEquals(listOf(raw, jpeg), plan.previewCandidates.map(MediaSourceChoice::file))
        assertEquals("RAW · DSCF0001.RAF", plan.selected.pickerLabel)
        assertEquals(emptyList(), plan.fullQualityCandidatesAtZoom(1f))
        assertEquals(
            listOf(raw, jpeg),
            plan.fullQualityCandidatesAtZoom(FULL_QUALITY_MEDIA_ZOOM_THRESHOLD)
                .map(MediaSourceChoice::file),
        )
    }

    @Test
    fun rawRenderAndJpegFallbackAreDescribedWithoutChangingTheActionTarget() {
        val raw = file("Photos/DSCF0001.RAF", "image/x-fuji-raf")
        val jpeg = file("Photos/DSCF0001.JPG", "image/jpeg")
        val plan = planMediaSources(listOf(raw, jpeg), raw)
        val rawChoice = plan.previewCandidates.first()
        val jpegChoice = plan.previewCandidates.last()

        assertEquals(
            "RAW server preview",
            describeMediaDisplaySource(plan.selected, rawChoice, highDetail = false),
        )
        assertEquals(
            "High-detail RAW render",
            describeMediaDisplaySource(plan.selected, rawChoice, highDetail = true),
        )
        assertEquals(
            "JPEG server preview fallback - actions target DSCF0001.RAF",
            describeMediaDisplaySource(plan.selected, jpegChoice, highDetail = false),
        )
        assertEquals(
            "High-detail JPEG render fallback - actions target DSCF0001.RAF",
            describeMediaDisplaySource(plan.selected, jpegChoice, highDetail = true),
        )
    }

    @Test
    fun malformedRawPayloadFallsBackToBoundedJpegFixture() = runBlocking {
        val raw = file("Photos/DSCF0001.RAF", "image/x-fuji-raf")
        val jpeg = file("Photos/DSCF0001.JPG", "image/jpeg")
        val plan = planMediaSources(listOf(raw, jpeg), raw)
        val rawFixture = "FUJIFILMCCD-RAW malformed fixture".encodeToByteArray()
        val jpegFixture = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0, 0, 0, 0,
        )

        val loaded = loadFirstUsableMediaSource(
            candidates = plan.previewCandidates,
            load = { source -> if (source.path == raw.path) rawFixture else jpegFixture },
            decode = { bytes -> "decoded".takeIf { bytes === jpegFixture } },
        )

        assertEquals("decoded", loaded?.value)
        assertEquals(jpeg, loaded?.source?.file)
        assertTrue(loaded?.usedFallback == true)
    }

    @Test
    fun malformedServerBodiesAreRejectedBeforePlatformDecode() = runBlocking {
        val jpeg = file("Photos/DSCF0001.JPG", "image/jpeg")
        val candidate = planMediaSources(listOf(jpeg), jpeg).previewCandidates
        var decodeCalls = 0

        val loaded = loadFirstUsableMediaSource(
            candidates = candidate,
            load = { "<html>not an image</html>".encodeToByteArray() },
            decode = {
                decodeCalls += 1
                "decoded"
            },
        )

        assertNull(loaded)
        assertEquals(0, decodeCalls)
        assertFalse(isBoundedDisplayImagePayload("FUJIFILMCCD-RAW".encodeToByteArray()))
        assertFalse(isBoundedDisplayImagePayload(ByteArray(0)))
    }

    @Test
    fun fullQualityLoaderUsesItsLargerExplicitBoundWithoutWeakeningPreviewBound() = runBlocking {
        val jpeg = file("Photos/DSCF0001.JPG", "image/jpeg")
        val payload = ByteArray(32).also {
            it[0] = 0xFF.toByte()
            it[1] = 0xD8.toByte()
            it[2] = 0xFF.toByte()
        }
        val candidate = planMediaSources(listOf(jpeg), jpeg).fullQualityCandidates
        var previewDecodeCalls = 0

        val rejected = loadFirstUsableMediaSource(
            candidates = candidate,
            maximumPayloadBytes = 16,
            load = { payload },
            decode = {
                previewDecodeCalls += 1
                "decoded"
            },
        )
        val accepted = loadFirstUsableMediaSource(
            candidates = candidate,
            maximumPayloadBytes = 64,
            load = { payload },
            decode = { "decoded" },
        )

        assertNull(rejected)
        assertEquals(0, previewDecodeCalls)
        assertEquals("decoded", accepted?.value)
    }

    @Test
    fun inaccessibleOriginalNeverBecomesAFullQualityCandidate() {
        val raw = file("Photos/DSCF0001.RAF", "image/x-fuji-raf").copy(originalAccessAllowed = false)
        val jpeg = file("Photos/DSCF0001.JPG", "image/jpeg")

        val plan = planMediaSources(listOf(raw, jpeg), raw)

        assertEquals(listOf(jpeg), plan.fullQualityCandidates.map(MediaSourceChoice::file))
    }

    @Test
    fun cancelledFullQualityLoadRecoversTheRetryGate() = runBlocking {
        var recovered = false

        assertFailsWith<CancellationException> {
            withFullQualityCancellationRecovery(
                onCancelled = { recovered = true },
                load = { throw CancellationException("zoom fell below the high-detail threshold") },
            )
        }

        assertTrue(recovered)
    }

    @Test
    fun restoredViewerGetsANewLoadIdentityWhenTheFilesUserArrives() {
        val beforeServerInfo = MediaViewerSourceLoadIdentity(
            selectedPath = "Photos/Samples/SAMPLE0001.RAF",
            filesUserId = "",
        )
        val afterServerInfo = beforeServerInfo.copy(filesUserId = "account")

        assertNotEquals(beforeServerInfo, afterServerInfo)
        assertEquals(beforeServerInfo.selectedPath, afterServerInfo.selectedPath)
    }

    @Test
    fun viewerLoadIdentityChangesWithAccountAndSourceGeneration() {
        val original = file("Photos/Samples/SAMPLE0001.RAF", "image/x-fuji-raf")
        val firstGeneration = original.copy(etag = "first")
            .mediaViewerSourceGenerationIdentity()
        val baseline = MediaViewerSourceLoadIdentity(
            selectedPath = original.path,
            filesUserId = "account",
            serverUrl = "https://cloud.example.test",
            loginName = "account",
            candidates = listOf(firstGeneration),
        )

        assertNotEquals(
            baseline,
            baseline.copy(
                candidates = listOf(
                    firstGeneration.copy(etag = "second"),
                ),
            ),
        )
        assertNotEquals(
            baseline,
            baseline.copy(loginName = "another-account"),
        )
    }

    private fun file(path: String, mime: String) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = mime,
        size = 1,
        lastModified = null,
        fileId = path.hashCode().toLong(),
        hasPreview = true,
        etag = "etag-$path",
    )
}
