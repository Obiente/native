package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PhotoEditingTest {
    @Test
    fun fullResolutionLoaderPrefersMemoriesForRawCompatibility() = runBlocking {
        var filesReads = 0
        val payload = loadFullResolutionPhotoPayload(
            original = file(path = "Photos/source.raw", etag = "current").copy(mimeType = "image/x-dcraw"),
            loadMemories = { fileId, etag ->
                assertEquals(7L, fileId)
                assertEquals("current", etag)
                byteArrayOf(1, 2, 3)
            },
            loadFilesDav = {
                filesReads += 1
                byteArrayOf(4)
            },
        )

        assertEquals(FullResolutionPhotoSource.MemoriesTranscoded, payload.source)
        assertEquals(EncodedImageOrientationPolicy.PixelsAlreadyUpright, payload.source.orientationPolicy())
        assertEquals(listOf<Byte>(1, 2, 3), payload.bytes.toList())
        assertEquals(0, filesReads)
    }

    @Test
    fun memoriesKeepsPassthroughImageExifSemanticsSeparateFromTranscodedRaw() = runBlocking {
        val passthroughMimeTypes = listOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
            "image/gif",
        )

        passthroughMimeTypes.forEach { mimeType ->
            val payload = loadFullResolutionPhotoPayload(
                original = file(path = "Photos/source.jpg").copy(mimeType = mimeType),
                loadMemories = { _, _ -> byteArrayOf(1) },
                loadFilesDav = null,
            )

            assertEquals(FullResolutionPhotoSource.MemoriesPassthrough, payload.source)
            assertEquals(EncodedImageOrientationPolicy.ApplyExif, payload.source.orientationPolicy())
        }

        val rawPayload = loadFullResolutionPhotoPayload(
            original = file(path = "Photos/source.raw").copy(mimeType = "image/x-dcraw"),
            loadMemories = { _, _ -> byteArrayOf(2) },
            loadFilesDav = null,
        )

        assertEquals(FullResolutionPhotoSource.MemoriesTranscoded, rawPayload.source)
        assertEquals(EncodedImageOrientationPolicy.PixelsAlreadyUpright, rawPayload.source.orientationPolicy())

        assertEquals(
            FullResolutionPhotoSource.MemoriesPassthrough,
            file(path = "Photos/source.jpg").copy(mimeType = null).memoriesFullResolutionPhotoSource(),
        )
        assertEquals(
            FullResolutionPhotoSource.MemoriesPassthrough,
            file(path = "Photos/source.jpg")
                .copy(mimeType = "application/octet-stream")
                .memoriesFullResolutionPhotoSource(),
        )
        assertEquals(
            FullResolutionPhotoSource.MemoriesTranscoded,
            file(path = "Photos/source.raw").copy(mimeType = null).memoriesFullResolutionPhotoSource(),
        )
    }

    @Test
    fun fullResolutionLoaderFallsBackToCanonicalFilesDav() = runBlocking {
        val requestedPaths = mutableListOf<String>()
        val payload = loadFullResolutionPhotoPayload(
            original = file(path = "Photos/portrait.jpg"),
            loadMemories = { _, _ -> error("Memories is not installed") },
            loadFilesDav = { path ->
                requestedPaths += path
                byteArrayOf(9, 8)
            },
        )

        assertEquals(FullResolutionPhotoSource.FilesDav, payload.source)
        assertEquals(listOf("Photos/portrait.jpg"), requestedPaths)
    }

    @Test
    fun fullResolutionLoaderNeverSendsSyntheticOrReadOnlyPathsToFilesDav() = runBlocking {
        val unsafeFiles = listOf(
            file(path = "memories/people/recognize/1/2/7").copy(
                originalAccessAllowed = false,
            ),
            file(path = "Talk/attachment.jpg").copy(
                davPathAuthoritative = false,
            ),
        )

        unsafeFiles.forEach { unsafe ->
            var filesRead = false
            kotlin.test.assertFails {
                loadFullResolutionPhotoPayload(
                    original = unsafe,
                    loadMemories = { _, _ -> error("Memories unavailable") },
                    loadFilesDav = {
                        filesRead = true
                        byteArrayOf(1)
                    },
                )
            }
            assertFalse(filesRead)
        }
    }

    @Test
    fun fullResolutionLoaderPreservesCancellation() = runBlocking {
        kotlin.test.assertFailsWith<CancellationException> {
            loadFullResolutionPhotoPayload(
                original = file(),
                loadMemories = { _, _ -> throw CancellationException("cancelled") },
                loadFilesDav = { byteArrayOf(1) },
            )
        }
        Unit
    }

    @Test
    fun rotationsAndFlipsRemainTypedAndReversible() {
        val recipe = PhotoEditRecipe()
            .rotateClockwise()
            .rotateClockwise()
            .toggleHorizontalFlip()

        assertEquals(180, recipe.rotationDegrees)
        assertTrue(recipe.flipHorizontal)
        assertEquals(0, recipe.rotateClockwise().rotateClockwise().rotationDegrees)
        assertFalse(recipe.toggleHorizontalFlip().flipHorizontal)
    }

    @Test
    fun editHistorySupportsBoundedUndoRedoAndClearsRedoOnNewBranch() {
        var history = PhotoEditHistory()
            .commit(PhotoEditRecipe(rotationDegrees = 90))
            .commit(PhotoEditRecipe(rotationDegrees = 180))

        history = history.undo()
        assertEquals(90, history.current.rotationDegrees)
        assertTrue(history.canUndo)
        assertTrue(history.canRedo)

        history = history.redo()
        assertEquals(180, history.current.rotationDegrees)

        history = history.undo().commit(PhotoEditRecipe(flipHorizontal = true))
        assertTrue(history.current.flipHorizontal)
        assertFalse(history.canRedo)

        repeat(100) { index ->
            history = history.commit(PhotoEditRecipe(rotationDegrees = (index % 4) * 90))
        }
        assertTrue(history.undoStack.size <= 64)
    }

    @Test
    fun quarterTurnPreviewFitsSwappedBoundsInsideViewport() {
        val landscape = calculatePhotoPreviewLayout(
            imageWidth = 6000,
            imageHeight = 4000,
            canvasWidth = 900f,
            canvasHeight = 500f,
            crop = NormalizedPhotoCrop.Full,
            rotationDegrees = 90,
        )
        val portrait = calculatePhotoPreviewLayout(
            imageWidth = 4000,
            imageHeight = 6000,
            canvasWidth = 900f,
            canvasHeight = 500f,
            crop = NormalizedPhotoCrop.Full,
            rotationDegrees = 270,
        )

        assertEquals(500, landscape.destinationSize.width)
        assertEquals(333, landscape.destinationSize.height)
        assertEquals(500, portrait.destinationSize.width)
        assertEquals(750, portrait.destinationSize.height)
        assertTrue(landscape.destinationSize.height <= 900)
        assertTrue(landscape.destinationSize.width <= 500)
        assertTrue(portrait.destinationSize.height <= 900)
        assertTrue(portrait.destinationSize.width <= 500)
    }

    @Test
    fun centeredCropPreservesRequestedAspectWithinNormalizedBounds() {
        val squareFromLandscape = NormalizedPhotoCrop.centered(aspectRatio = 1f, sourceAspectRatio = 4f / 3f)
        assertEquals(0f, squareFromLandscape.top)
        assertEquals(1f, squareFromLandscape.bottom)
        assertEquals(1f, squareFromLandscape.width * (4f / 3f), absoluteTolerance = 0.0001f)

        val landscapeFromPortrait = NormalizedPhotoCrop.centered(aspectRatio = 4f / 3f, sourceAspectRatio = 3f / 4f)
        assertEquals(1f, landscapeFromPortrait.width)
        assertEquals(4f / 3f, (3f / 4f) / landscapeFromPortrait.height, absoluteTolerance = 0.0001f)
    }

    @Test
    fun cropPositionMovesWithinBoundsWithoutChangingItsSize() {
        val crop = NormalizedPhotoCrop.centered(aspectRatio = 1f, sourceAspectRatio = 16f / 9f)
        val movedLeft = crop.reposition(0f, 0.5f)
        val movedRight = crop.reposition(1f, 0.5f)

        assertEquals(crop.width, movedLeft.width, absoluteTolerance = 0.0001f)
        assertEquals(crop.height, movedLeft.height, absoluteTolerance = 0.0001f)
        assertEquals(0f, movedLeft.left)
        assertEquals(1f, movedRight.right)
    }

    @Test
    fun exportPlanNeverTargetsOriginalAndAvoidsExistingSidecars() {
        val original = "Photos/Trip/sunrise.raw.jpg"
        val first = createPhotoEditExportPlan(original, emptySet(), "ABC-123")
        val second = createPhotoEditExportPlan(original, setOf(first.sidecarPath), "ABC-123")

        assertNotEquals(original, first.sidecarPath)
        assertEquals("Photos/Trip/sunrise.raw.nextcloud-native-abc-123.photo-edit.json", first.sidecarPath)
        assertEquals("Photos/Trip/sunrise.raw.nextcloud-native-abc-123-2.photo-edit.json", second.sidecarPath)
    }

    @Test
    fun fakeExporterConstructsSidecarWithoutWritingOriginal() = runBlocking {
        val original = file(path = "Photos/portrait.jpg", etag = "source-etag")
        val writes = mutableListOf<Pair<String, String>>()
        val result = exportPhotoEditSidecar(
            original = original,
            recipe = PhotoEditRecipe(rotationDegrees = 90, adjustments = PhotoAdjustments(brightness = 0.2f)),
            nonce = "test",
            listExistingPaths = { setOf(original.path) },
            createSidecar = { path, content ->
                writes += path to content
                true
            },
        )

        val created = assertIs<PhotoEditExportResult.Created>(result)
        assertEquals(writes.single().first, created.path)
        assertNotEquals(original.path, created.path)
        assertTrue(writes.single().second.contains("\"sourcePath\": \"Photos/portrait.jpg\""))
        assertTrue(writes.single().second.contains("\"sourceFileId\": 7"))
        assertTrue(writes.single().second.contains("\"sourceEtag\": \"source-etag\""))
        assertTrue(writes.single().second.contains("\"rotationDegrees\": 90"))
    }

    @Test
    fun identityRecipeDoesNotInvokeWriter() = runBlocking {
        var listed = false
        var wrote = false
        val result = exportPhotoEditSidecar(
            original = file(),
            recipe = PhotoEditRecipe(),
            nonce = "test",
            listExistingPaths = {
                listed = true
                emptySet()
            },
            createSidecar = { _, _ ->
                wrote = true
                true
            },
        )

        assertIs<PhotoEditExportResult.Failed>(result)
        assertFalse(listed)
        assertFalse(wrote)
    }

    @Test
    fun memoriesRequestUsesOfficialFieldsAndAlwaysNamesACopy() {
        val recipe = PhotoEditRecipe(
            rotationDegrees = 90,
            flipHorizontal = true,
            crop = NormalizedPhotoCrop(0.1f, 0.2f, 0.9f, 0.8f),
            adjustments = PhotoAdjustments(
                brightness = 0.2f,
                contrast = 0.4f,
                hue = 45f,
                saturation = -0.5f,
                exposure = 0.75f,
                warmth = 0.25f,
            ),
            filter = PhotoFilter.Monochrome,
        )
        val edit = createMemoriesPhotoEditRequest(
            originalName = "portrait.raw",
            sourceWidth = 6000,
            sourceHeight = 4000,
            recipe = recipe,
            copyNonce = "test",
        )
        val request = memoriesPhotoEditApiRequest(42, edit)
        val body = Json.parseToJsonElement(requireNotNull(request.body).decodeToString()).jsonObject

        assertEquals(NextcloudApiMethod.PUT, request.method)
        assertEquals("/index.php/apps/memories/api/image/edit/42", request.relativePath)
        assertTrue(request.ocsApiRequest)
        assertEquals(setOf("name", "width", "height", "quality", "extension", "state"), body.keys)
        assertEquals("portrait-edited-test.jpg", body.getValue("name").jsonPrimitive.content)
        assertNotEquals("portrait.raw", body.getValue("name").jsonPrimitive.content)
        val state = body.getValue("state").jsonObject
        assertEquals(listOf("Brighten", "Contrast", "HSV", "Warmth"), state.getValue("finetunes").let {
            (it as kotlinx.serialization.json.JsonArray).map { value -> value.jsonPrimitive.content }
        })
        val finetunes = state.getValue("finetunesProps").jsonObject
        assertEquals("45.0", finetunes.getValue("hue").jsonPrimitive.content)
        assertEquals("0.75", finetunes.getValue("value").jsonPrimitive.content)
        assertEquals("50.0", finetunes.getValue("warmth").jsonPrimitive.content)
        assertEquals("Inkwell", state.getValue("filter").jsonPrimitive.content)
        val transform = state.getValue("adjustments").jsonObject
        assertEquals("90", transform.getValue("rotation").jsonPrimitive.content)
        assertEquals("true", transform.getValue("isFlippedX").jsonPrimitive.content)
        val crop = transform.getValue("crop").jsonObject
        assertEquals("0.1", crop.getValue("x").jsonPrimitive.content)
        assertEquals(0.8f, crop.getValue("width").jsonPrimitive.content.toFloat(), absoluteTolerance = 0.0001f)
        assertEquals("2400", body.getValue("width").jsonPrimitive.content)
        assertEquals("4800", body.getValue("height").jsonPrimitive.content)
    }

    @Test
    fun outputDimensionsFollowCropAndQuarterTurnWithoutUpscalingToOriginalCanvas() {
        val cropped = calculatePhotoEditOutputDimensions(
            sourceWidth = 6240,
            sourceHeight = 4160,
            recipe = PhotoEditRecipe(crop = NormalizedPhotoCrop(0.25f, 0.1f, 0.75f, 0.9f)),
        )
        val rotated = calculatePhotoEditOutputDimensions(
            sourceWidth = 6240,
            sourceHeight = 4160,
            recipe = PhotoEditRecipe(
                crop = NormalizedPhotoCrop(0.25f, 0.1f, 0.75f, 0.9f),
                rotationDegrees = 270,
            ),
        )

        assertEquals(PhotoEditOutputDimensions(3120, 3328), cropped)
        assertEquals(PhotoEditOutputDimensions(3328, 3120), rotated)
    }

    @Test
    fun latestCurrentSidecarIsRestorableWhileNewerStaleAndForeignRecipesStayIsolated() = runBlocking {
        val original = file(path = "Photos/portrait.jpg", etag = "current")
        val currentFile = sidecarFile(
            "Photos/portrait.nextcloud-native-current.photo-edit.json",
            modified = "2026-07-20T10:00:00Z",
        )
        val staleFile = sidecarFile(
            "Photos/portrait.nextcloud-native-stale.photo-edit.json",
            modified = "2026-07-21T10:00:00Z",
        )
        val foreignFile = sidecarFile(
            "Photos/portrait.nextcloud-native-foreign.photo-edit.json",
            modified = "2026-07-22T10:00:00Z",
        )
        val currentRecipe = PhotoEditRecipe(rotationDegrees = 90)
        val payloads = mapOf(
            currentFile.path to encodePhotoEditSidecar(original, currentRecipe).encodeToByteArray(),
            staleFile.path to encodePhotoEditSidecar(
                original.copy(etag = "older"),
                PhotoEditRecipe(filter = PhotoFilter.Sepia),
            ).encodeToByteArray(),
            foreignFile.path to encodePhotoEditSidecar(
                original.copy(path = "Photos/other.jpg", name = "other.jpg"),
                PhotoEditRecipe(flipHorizontal = true),
            ).encodeToByteArray(),
        )

        val resolved = loadLatestPhotoEditSidecar(
            original = original,
            listSiblingFiles = { listOf(staleFile, foreignFile, currentFile) },
            readSidecar = { payloads.getValue(it.path) },
        )

        assertEquals(currentFile, resolved?.file)
        assertEquals(PhotoEditSidecarFreshness.Current, resolved?.freshness)
        assertEquals(currentRecipe, resolved?.sidecar?.recipe)
    }

    @Test
    fun staleSidecarIsNeverPresentedAsCurrent() {
        val original = file(path = "Photos/portrait.jpg", etag = "current")
        val sidecar = sidecarFile("Photos/portrait.nextcloud-native-old.photo-edit.json")

        val resolved = decodePhotoEditSidecar(
            sidecar,
            encodePhotoEditSidecar(
                original.copy(etag = "old"),
                PhotoEditRecipe(flipVertical = true),
            ).encodeToByteArray(),
            original,
        )

        assertEquals(PhotoEditSidecarFreshness.SourceChanged, resolved.freshness)
    }

    @Test
    fun sourceBoundSidecarCannotAttachToAnUnidentifiedOrDifferentServerObject() {
        val original = file(path = "Photos/portrait.jpg", etag = "current")
        val sidecar = sidecarFile("Photos/portrait.nextcloud-native-bound.photo-edit.json")
        val encoded = encodePhotoEditSidecar(original, PhotoEditRecipe(rotationDegrees = 90))
            .encodeToByteArray()

        kotlin.test.assertFails {
            decodePhotoEditSidecar(sidecar, encoded, original.copy(fileId = null))
        }
        kotlin.test.assertFails {
            decodePhotoEditSidecar(sidecar, encoded, original.copy(fileId = 8))
        }
    }

    @Test
    fun memoriesIdentityRealShapeResolvesSyntheticPersonItemToCanonicalDavPath() = runBlocking {
        val virtual = file(
            path = "memories/people/recognize/1/20337/2169263",
            etag = "68c5218394cd5",
        ).copy(
            name = "20250906_020658.jpg",
            fileId = 2169263,
        )
        val response = NextcloudApiResponse(
            status = 200,
            body = """
                {
                  "fileid": 2169263,
                  "filename": "/Photos/DCIM/Camera/20250906_020658.jpg",
                  "basename": "20250906_020658.jpg",
                  "etag": "68c5218394cd5",
                  "mimetype": "image/jpeg",
                  "w": 1728,
                  "h": 2304,
                  "datetaken": 1757124418
                }
            """.trimIndent().encodeToByteArray(),
            contentType = "application/json",
            etag = null,
        )
        var requestedFileId: Long? = null

        val resolved = resolvePhotoEditDavSource(virtual) { fileId ->
            requestedFileId = fileId
            parseMemoriesPhotoFileIdentity(response, fileId)
        }

        assertEquals(2169263, requestedFileId)
        assertEquals("Photos/DCIM/Camera/20250906_020658.jpg", resolved?.path)
        assertEquals("20250906_020658.jpg", resolved?.name)
        assertEquals(2169263, resolved?.fileId)
        assertTrue(resolved?.davPathAuthoritative == true)
    }

    @Test
    fun ordinaryDavPhotoDoesNotNeedMemoriesIdentityLookup() = runBlocking {
        val original = file(path = "Photos/DCIM/Camera/photo.jpg")
        var identityRequests = 0

        val resolved = resolvePhotoEditDavSource(original) {
            identityRequests += 1
            error("Ordinary DAV paths must not be looked up.")
        }

        assertEquals(original, resolved)
        assertEquals(0, identityRequests)
    }

    @Test
    fun nonAuthoritativeOrdinaryPathCannotBecomeSidecarSource() = runBlocking {
        val placeholder = file(path = "Talk/44321").copy(
            fileId = 44321,
            davPathAuthoritative = false,
        )
        var identityRequests = 0

        val resolved = resolvePhotoEditDavSource(placeholder) {
            identityRequests += 1
            error("Non-Memories placeholders must not be looked up through Memories.")
        }

        assertEquals(null, resolved)
        assertEquals(0, identityRequests)
    }

    @Test
    fun optionalSidecarDiscoveryDegradesFolderFailureToNoSidecar() = runBlocking {
        val virtual = file(path = "memories/collections/albums/trip/1/7")
        val canonical = virtual.copy(path = "Photos/Trip/photo.jpg", name = "photo.jpg")

        val discovery = discoverPhotoEditSidecar(
            original = virtual,
            resolveSource = { canonical },
            loadSidecar = { error("WebDAV folder listing failed (HTTP 404).") },
        )

        assertEquals(null, discovery.davSource)
        assertEquals(null, discovery.sidecar)
    }

    @Test
    fun memoriesIdentityRejectsTraversalAndMismatchedFileId() {
        val traversal = photoIdentityResponse(
            fileId = 7,
            filename = "/Photos/../Secrets/photo.jpg",
            basename = "photo.jpg",
        )
        val wrongIdentity = photoIdentityResponse(
            fileId = 8,
            filename = "/Photos/photo.jpg",
            basename = "photo.jpg",
        )

        kotlin.test.assertFails { parseMemoriesPhotoFileIdentity(traversal, expectedFileId = 7) }
        kotlin.test.assertFails { parseMemoriesPhotoFileIdentity(wrongIdentity, expectedFileId = 7) }
    }

    @Test
    fun exportFormatAndQualityRemainExplicitServerRenderSettings() {
        val edit = createMemoriesPhotoEditRequest(
            originalName = "portrait.raf",
            sourceWidth = 6240,
            sourceHeight = 4160,
            recipe = PhotoEditRecipe(filter = PhotoFilter.Sepia),
            extension = PhotoExportFormat.Webp.extension,
            quality = 0.8f,
            copyNonce = "web",
        )

        assertEquals("portrait-edited-web.webp", edit.name)
        assertEquals("webp", edit.extension)
        assertEquals(0.8f, edit.quality)
        assertEquals("Sepia", edit.state.filter)
    }

    @Test
    fun fullResolutionSourceRequestIsBoundedAndSameOrigin() {
        val request = memoriesPhotoDecodableApiRequest(42)

        assertEquals(NextcloudApiMethod.GET, request.method)
        assertEquals("/index.php/apps/memories/api/image/decodable/42", request.relativePath)
        assertEquals(MAX_PHOTO_EDIT_SOURCE_BYTES, request.maximumResponseBytes)
    }

    private fun file(path: String = "Photos/photo.jpg", etag: String? = null) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 1024,
        lastModified = null,
        fileId = 7,
        hasPreview = true,
        etag = etag,
    )

    private fun sidecarFile(
        path: String,
        modified: String? = null,
    ) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = "application/json",
        size = 1024,
        lastModified = modified,
        fileId = path.hashCode().toLong().let { if (it == 0L) 1L else kotlin.math.abs(it) },
        hasPreview = false,
        etag = "etag-$path",
    )

    private fun photoIdentityResponse(
        fileId: Long,
        filename: String,
        basename: String,
    ) = NextcloudApiResponse(
        status = 200,
        body = """
            {
              "fileid": $fileId,
              "filename": "$filename",
              "basename": "$basename",
              "etag": "etag",
              "mimetype": "image/jpeg"
            }
        """.trimIndent().encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )
}
