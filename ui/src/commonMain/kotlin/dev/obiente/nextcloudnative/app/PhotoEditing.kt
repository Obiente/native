package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

@Serializable
data class NormalizedPhotoCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right - left >= MIN_CROP_SPAN && bottom - top >= MIN_CROP_SPAN)
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun reposition(centerX: Float, centerY: Float): NormalizedPhotoCrop {
        require(centerX in 0f..1f && centerY in 0f..1f)
        val nextLeft = (centerX - width / 2f).coerceIn(0f, 1f - width)
        val nextTop = (centerY - height / 2f).coerceIn(0f, 1f - height)
        return NormalizedPhotoCrop(nextLeft, nextTop, nextLeft + width, nextTop + height)
    }

    companion object {
        val Full = NormalizedPhotoCrop()

        fun centered(aspectRatio: Float, sourceAspectRatio: Float): NormalizedPhotoCrop {
            require(aspectRatio > 0f && sourceAspectRatio > 0f)
            return if (sourceAspectRatio > aspectRatio) {
                val width = aspectRatio / sourceAspectRatio
                NormalizedPhotoCrop((1f - width) / 2f, 0f, (1f + width) / 2f, 1f)
            } else {
                val height = sourceAspectRatio / aspectRatio
                NormalizedPhotoCrop(0f, (1f - height) / 2f, 1f, (1f + height) / 2f)
            }
        }
    }
}

@Serializable
data class PhotoAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val exposure: Float = 0f,
    val warmth: Float = 0f,
) {
    init {
        require(brightness in -1f..1f)
        require(contrast in -1f..1f)
        require(hue in -180f..180f)
        require(saturation in -1f..1f)
        require(exposure in -2f..2f)
        require(warmth in 0f..1f)
    }

    val isIdentity: Boolean get() = brightness == 0f && contrast == 0f && hue == 0f && saturation == 0f &&
        exposure == 0f && warmth == 0f
}

@Serializable
enum class PhotoFilter(val serverValue: String?) {
    None(null),
    Monochrome("Inkwell"),
    Sepia("Sepia"),
}

enum class PhotoExportFormat(val extension: String, val label: String) {
    Jpeg("jpg", "JPEG"),
    Png("png", "PNG"),
    Webp("webp", "WebP"),
}

@Serializable
data class PhotoEditRecipe(
    val rotationDegrees: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val crop: NormalizedPhotoCrop = NormalizedPhotoCrop.Full,
    val adjustments: PhotoAdjustments = PhotoAdjustments(),
    val filter: PhotoFilter = PhotoFilter.None,
) {
    init {
        require(rotationDegrees in setOf(0, 90, 180, 270))
    }

    val isIdentity: Boolean get() = rotationDegrees == 0 && !flipHorizontal && !flipVertical &&
        crop == NormalizedPhotoCrop.Full && adjustments.isIdentity && filter == PhotoFilter.None

    fun rotateClockwise(): PhotoEditRecipe = copy(rotationDegrees = (rotationDegrees + 90) % 360)
    fun rotateCounterClockwise(): PhotoEditRecipe = copy(rotationDegrees = (rotationDegrees + 270) % 360)
    fun toggleHorizontalFlip(): PhotoEditRecipe = copy(flipHorizontal = !flipHorizontal)
    fun toggleVerticalFlip(): PhotoEditRecipe = copy(flipVertical = !flipVertical)
}

data class PhotoEditHistory(
    val current: PhotoEditRecipe = PhotoEditRecipe(),
    val undoStack: List<PhotoEditRecipe> = emptyList(),
    val redoStack: List<PhotoEditRecipe> = emptyList(),
) {
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun commit(next: PhotoEditRecipe): PhotoEditHistory {
        if (next == current) return this
        return copy(
            current = next,
            undoStack = (undoStack + current).takeLast(MAX_PHOTO_EDIT_HISTORY_STEPS),
            redoStack = emptyList(),
        )
    }

    fun undo(): PhotoEditHistory {
        val previous = undoStack.lastOrNull() ?: return this
        return copy(
            current = previous,
            undoStack = undoStack.dropLast(1),
            redoStack = (redoStack + current).takeLast(MAX_PHOTO_EDIT_HISTORY_STEPS),
        )
    }

    fun redo(): PhotoEditHistory {
        val next = redoStack.lastOrNull() ?: return this
        return copy(
            current = next,
            undoStack = (undoStack + current).takeLast(MAX_PHOTO_EDIT_HISTORY_STEPS),
            redoStack = redoStack.dropLast(1),
        )
    }

    companion object {
        fun from(recipe: PhotoEditRecipe): PhotoEditHistory = PhotoEditHistory(current = recipe)
    }
}

@Serializable
data class PhotoEditSidecar(
    val format: String = PHOTO_EDIT_SIDECAR_FORMAT,
    val sourcePath: String,
    val sourceFileId: Long? = null,
    val sourceEtag: String? = null,
    val recipe: PhotoEditRecipe,
) {
    init {
        require(format == PHOTO_EDIT_SIDECAR_FORMAT)
        require(sourcePath.isNotBlank() && sourcePath.none(Char::isISOControl))
        require(sourceFileId == null || sourceFileId > 0L)
        require(sourceEtag == null || sourceEtag.isNotBlank() && sourceEtag.none(Char::isISOControl))
    }
}

data class PhotoEditOutputDimensions(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0 && height > 0)
    }
}

enum class PhotoEditSidecarFreshness {
    Current,
    Unversioned,
    SourceChanged,
}

data class ResolvedPhotoEditSidecar(
    val file: NextcloudFile,
    val sidecar: PhotoEditSidecar,
    val freshness: PhotoEditSidecarFreshness,
)

private const val MAX_PHOTO_EDIT_HISTORY_STEPS = 64

data class PhotoEditSidecarDiscovery(
    val davSource: NextcloudFile?,
    val sidecar: ResolvedPhotoEditSidecar?,
)

data class MemoriesPhotoFileIdentity(
    val fileId: Long,
    val path: String,
    val name: String,
    val etag: String?,
    val mimeType: String?,
) {
    init {
        require(fileId > 0L)
        require(path.isSafeDavRelativePath())
        require(name == path.substringAfterLast('/'))
        require(etag == null || etag.isSafePhotoIdentityText(MAX_PHOTO_IDENTITY_ETAG_LENGTH))
        require(mimeType == null || mimeType.isSafePhotoIdentityText(MAX_PHOTO_IDENTITY_MIME_LENGTH))
    }
}

@Serializable
private data class MemoriesPhotoFileIdentityWire(
    val fileid: Long,
    val filename: String,
    val basename: String? = null,
    val etag: String? = null,
    val mimetype: String? = null,
)

@Serializable
data class MemoriesPhotoEditRequest(
    val name: String,
    val width: Int,
    val height: Int,
    val quality: Float? = 0.92f,
    val extension: String = "jpg",
    val state: MemoriesPhotoEditorState,
) {
    init {
        require(name.isNotBlank())
        require(width > 0 && height > 0)
        require(quality == null || quality in 0f..1f)
        require(extension.matches(Regex("[a-z0-9]+")))
    }
}

@Serializable
data class MemoriesPhotoEditorState(
    val finetunes: List<String>,
    val finetunesProps: MemoriesFinetuneProperties,
    val adjustments: MemoriesTransformAdjustments,
    val filter: String? = null,
)

@Serializable
data class MemoriesFinetuneProperties(
    val brightness: Float,
    val contrast: Float,
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val warmth: Float,
)

@Serializable
data class MemoriesTransformAdjustments(
    val crop: MemoriesNormalizedCrop?,
    val rotation: Int,
    val isFlippedX: Boolean,
    val isFlippedY: Boolean,
)

@Serializable
data class MemoriesNormalizedCrop(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class MemoriesPhotoCopyPlan(
    val originalName: String,
    val copyName: String,
) {
    init {
        require(originalName.isNotBlank())
        require(copyName.isNotBlank() && copyName != originalName)
        require(copyName.none { it == '/' || it == '\\' || it == '\u0000' || it == '\r' || it == '\n' })
    }
}

/**
 * Creates a leaf filename for a new Memories copy. The nonce makes independent saves distinct,
 * while [existingNames] gives deterministic collision handling for callers that already have a
 * directory listing. Memories also rejects an existing target atomically instead of overwriting it.
 */
fun createMemoriesPhotoCopyPlan(
    originalName: String,
    extension: String,
    nonce: String,
    existingNames: Set<String> = emptySet(),
): MemoriesPhotoCopyPlan {
    require(originalName.isNotBlank())
    val normalizedExtension = normalizePhotoExportExtension(extension)
    val safeNonce = nonce.lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(MAX_COPY_NONCE_LENGTH)
    require(safeNonce.isNotBlank())
    val originalStem = originalName.substringBeforeLast('.', missingDelimiterValue = originalName)
        .map { character ->
            if (character == '/' || character == '\\' || character == '\u0000' || character.isISOControl()) '-' else character
        }
        .joinToString("")
        .trim(' ', '.', '-')
        .take(MAX_COPY_STEM_CHARACTERS)
        .ifBlank { "photo" }
    var sequence = 1
    var copyName: String
    do {
        val sequenceSuffix = if (sequence == 1) "" else "-$sequence"
        copyName = "$originalStem-edited-$safeNonce$sequenceSuffix.$normalizedExtension"
        sequence += 1
    } while (copyName == originalName || copyName in existingNames)
    return MemoriesPhotoCopyPlan(originalName, copyName)
}

/**
 * Projects the shared recipe onto Memories' official ImageController editImage request.
 * The generated name is always different from [originalName], which makes Memories create a copy.
 */
fun createMemoriesPhotoEditRequest(
    originalName: String,
    sourceWidth: Int,
    sourceHeight: Int,
    recipe: PhotoEditRecipe,
    extension: String = "jpg",
    quality: Float? = 0.92f,
    copyNonce: String,
    existingNames: Set<String> = emptySet(),
): MemoriesPhotoEditRequest {
    require(originalName.isNotBlank())
    val normalizedExtension = normalizePhotoExportExtension(extension)
    val copyPlan = createMemoriesPhotoCopyPlan(originalName, normalizedExtension, copyNonce, existingNames)
    val adjustments = recipe.adjustments
    val output = calculatePhotoEditOutputDimensions(sourceWidth, sourceHeight, recipe)
    val finetunes = buildList {
        if (adjustments.brightness != 0f) add("Brighten")
        if (adjustments.contrast != 0f) add("Contrast")
        if (adjustments.hue != 0f || adjustments.saturation != 0f || adjustments.exposure != 0f) add("HSV")
        if (adjustments.warmth != 0f) add("Warmth")
    }
    return MemoriesPhotoEditRequest(
        name = copyPlan.copyName,
        width = output.width,
        height = output.height,
        quality = quality,
        extension = normalizedExtension,
        state = MemoriesPhotoEditorState(
            finetunes = finetunes,
            finetunesProps = MemoriesFinetuneProperties(
                brightness = adjustments.brightness,
                contrast = adjustments.contrast * 100f,
                hue = adjustments.hue,
                saturation = adjustments.saturation,
                value = adjustments.exposure,
                warmth = adjustments.warmth * 200f,
            ),
            adjustments = MemoriesTransformAdjustments(
                crop = recipe.crop.takeUnless { it == NormalizedPhotoCrop.Full }?.let {
                    MemoriesNormalizedCrop(it.left, it.top, it.width, it.height)
                },
                rotation = recipe.rotationDegrees,
                isFlippedX = recipe.flipHorizontal,
                isFlippedY = recipe.flipVertical,
            ),
            filter = recipe.filter.serverValue,
        ),
    )
}

/**
 * Mirrors the dimensions produced by Memories after normalized crop and quarter-turn rotation.
 * Supplying the original dimensions for a crop makes Memories resize the crop back to the source
 * canvas, which can distort or needlessly upscale an otherwise lossless edit.
 */
fun calculatePhotoEditOutputDimensions(
    sourceWidth: Int,
    sourceHeight: Int,
    recipe: PhotoEditRecipe,
): PhotoEditOutputDimensions {
    require(sourceWidth > 0 && sourceHeight > 0)
    val croppedWidth = (sourceWidth * recipe.crop.width).roundToInt().coerceIn(1, sourceWidth)
    val croppedHeight = (sourceHeight * recipe.crop.height).roundToInt().coerceIn(1, sourceHeight)
    return if (recipe.rotationDegrees == 90 || recipe.rotationDegrees == 270) {
        PhotoEditOutputDimensions(croppedHeight, croppedWidth)
    } else {
        PhotoEditOutputDimensions(croppedWidth, croppedHeight)
    }
}

enum class FullResolutionPhotoSource(val label: String) {
    MemoriesPassthrough("Original via Memories"),
    MemoriesTranscoded("Memories optimized source"),
    FilesDav("Original from Files"),
}

fun FullResolutionPhotoSource.orientationPolicy(): EncodedImageOrientationPolicy = when (this) {
    FullResolutionPhotoSource.MemoriesTranscoded -> EncodedImageOrientationPolicy.PixelsAlreadyUpright
    FullResolutionPhotoSource.MemoriesPassthrough,
    FullResolutionPhotoSource.FilesDav,
    -> EncodedImageOrientationPolicy.ApplyExif
}

data class FullResolutionPhotoPayload(
    val bytes: ByteArray,
    val source: FullResolutionPhotoSource,
) {
    init {
        require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_PHOTO_EDIT_SOURCE_BYTES) {
            "The full-resolution photo payload is invalid."
        }
    }
}

/**
 * Loads a full-resolution editing source without making Memories a hard dependency.
 *
 * Memories remains first because its decodable endpoint can transcode RAW and uncommon photo
 * formats. A canonical, readable Files record then falls back to authenticated WebDAV. Synthetic
 * gallery paths are never sent to Files DAV.
 */
internal suspend fun loadFullResolutionPhotoPayload(
    original: NextcloudFile,
    loadMemories: suspend (fileId: Long, etag: String?) -> ByteArray,
    loadFilesDav: (suspend (path: String) -> ByteArray)?,
): FullResolutionPhotoPayload {
    val failures = mutableListOf<String>()
    original.fileId?.let { fileId ->
        try {
            return FullResolutionPhotoPayload(
                bytes = loadMemories(fileId, original.etag),
                source = original.memoriesFullResolutionPhotoSource(),
            )
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            failures += "Memories: ${failure.message ?: "source unavailable"}"
        }
    }
    if (
        original.originalAccessAllowed &&
        original.davPathAuthoritative &&
        original.path.isSafeDavRelativePath() &&
        loadFilesDav != null
    ) {
        try {
            return FullResolutionPhotoPayload(
                bytes = loadFilesDav(original.path),
                source = FullResolutionPhotoSource.FilesDav,
            )
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            failures += "Files: ${failure.message ?: "source unavailable"}"
        }
    }
    val detail = failures.joinToString("; ").takeIf(String::isNotBlank)
    error(
        if (detail == null) {
            "No authoritative full-resolution photo source is available."
        } else {
            "Could not load a full-resolution photo source. $detail"
        },
    )
}

internal fun NextcloudFile.memoriesFullResolutionPhotoSource(): FullResolutionPhotoSource {
    val normalizedMimeType = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return if (
        normalizedMimeType in MEMORIES_DECODABLE_PASSTHROUGH_MIME_TYPES ||
        normalizedMimeType in MEMORIES_UNINFORMATIVE_MIME_TYPES &&
        extension in MEMORIES_DECODABLE_PASSTHROUGH_EXTENSIONS
    ) {
        FullResolutionPhotoSource.MemoriesPassthrough
    } else {
        FullResolutionPhotoSource.MemoriesTranscoded
    }
}

fun memoriesPhotoDecodableApiRequest(
    fileId: Long,
    etag: String? = null,
    maximumResponseBytes: Long = MAX_PHOTO_EDIT_SOURCE_BYTES,
): NextcloudApiRequest {
    require(fileId > 0)
    require(maximumResponseBytes in 1..MAX_PHOTO_EDIT_SOURCE_BYTES)
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/image/decodable/$fileId",
        queryParameters = etag?.takeIf(String::isNotBlank)?.let { mapOf("etag" to it) }.orEmpty(),
        maximumResponseBytes = maximumResponseBytes,
    ).requireSafe()
}

/**
 * Resolves a Memories timeline/cluster record back to its real path under the user's DAV files
 * root. Memories' own `filename` field is authoritative for this stable file ID.
 */
fun memoriesPhotoFileIdentityRequest(fileId: Long): NextcloudApiRequest {
    require(fileId > 0L)
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/index.php/apps/memories/api/image/info/$fileId",
        queryParameters = mapOf("basic" to "1"),
        ocsApiRequest = true,
        maximumResponseBytes = MAX_PHOTO_IDENTITY_RESPONSE_BYTES,
    ).requireSafe()
}

fun parseMemoriesPhotoFileIdentity(
    response: NextcloudApiResponse,
    expectedFileId: Long,
): MemoriesPhotoFileIdentity {
    require(expectedFileId > 0L)
    require(response.status in 200..299) {
        "Memories could not resolve the photo's file location (HTTP ${response.status})."
    }
    val wire = photoEditJson.decodeFromString<MemoriesPhotoFileIdentityWire>(response.body.decodeToString())
    require(wire.fileid == expectedFileId) { "Memories returned a different photo identity." }
    val path = wire.filename.removePrefix("/").also { relative ->
        require(!relative.startsWith('/')) { "Memories returned an absolute photo path." }
    }
    val derivedName = path.substringAfterLast('/')
    require(wire.basename == null || wire.basename == derivedName) {
        "Memories returned conflicting photo names."
    }
    val etag = wire.etag?.trim()?.removeSurrounding("\"")?.takeIf(String::isNotEmpty)
    return MemoriesPhotoFileIdentity(
        fileId = wire.fileid,
        path = path,
        name = derivedName,
        etag = etag,
        mimeType = wire.mimetype?.trim()?.takeIf(String::isNotEmpty),
    )
}

/**
 * Converts synthetic gallery paths into canonical DAV paths before any folder-level operation.
 * Ordinary Files/Photos records already carry a DAV-relative path and require no extra request.
 */
internal suspend fun resolvePhotoEditDavSource(
    original: NextcloudFile,
    loadIdentity: suspend (Long) -> MemoriesPhotoFileIdentity,
): NextcloudFile? {
    if (!original.path.isSyntheticMemoriesMediaPath()) return original
    val fileId = original.fileId ?: return null
    val identity = loadIdentity(fileId)
    require(identity.fileId == fileId)
    return original.copy(
        path = identity.path,
        name = identity.name,
        mimeType = identity.mimeType ?: original.mimeType,
        etag = identity.etag ?: original.etag,
    )
}

suspend fun resolvePhotoEditDavSource(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    original: NextcloudFile,
): NextcloudFile? = resolvePhotoEditDavSource(original) { fileId ->
    parseMemoriesPhotoFileIdentity(
        services.executeNextcloudApi(session, memoriesPhotoFileIdentityRequest(fileId)),
        fileId,
    )
}

fun memoriesPhotoEditApiRequest(fileId: Long, request: MemoriesPhotoEditRequest): NextcloudApiRequest {
    require(fileId > 0)
    return NextcloudApiRequest(
        method = NextcloudApiMethod.PUT,
        relativePath = "/index.php/apps/memories/api/image/edit/$fileId",
        contentType = "application/json",
        body = photoEditJson.encodeToString(request).encodeToByteArray(),
        ocsApiRequest = true,
    ).requireSafe()
}

data class PhotoEditExportPlan(
    val originalPath: String,
    val sidecarPath: String,
) {
    init {
        require(originalPath.isNotBlank())
        require(sidecarPath.isNotBlank() && sidecarPath != originalPath)
        require(sidecarPath.endsWith(PHOTO_EDIT_SIDECAR_EXTENSION))
    }
}

sealed interface PhotoEditExportResult {
    data class Created(val path: String) : PhotoEditExportResult
    data class Failed(val message: String) : PhotoEditExportResult
}

fun createPhotoEditExportPlan(
    originalPath: String,
    existingPaths: Set<String>,
    nonce: String,
): PhotoEditExportPlan {
    require(originalPath.isNotBlank())
    val safeNonce = nonce.lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(24)
    require(safeNonce.isNotBlank())
    val parent = originalPath.substringBeforeLast('/', missingDelimiterValue = "")
    val name = originalPath.substringAfterLast('/')
    val stem = name.substringBeforeLast('.', missingDelimiterValue = name).ifBlank { "photo" }
    val prefix = if (parent.isBlank()) "" else "$parent/"
    var sequence = 1
    var candidate: String
    do {
        val suffix = if (sequence == 1) "" else "-$sequence"
        candidate = "$prefix$stem.nextcloud-native-$safeNonce$suffix$PHOTO_EDIT_SIDECAR_EXTENSION"
        sequence += 1
    } while (candidate == originalPath || candidate in existingPaths)
    return PhotoEditExportPlan(originalPath, candidate)
}

fun encodePhotoEditSidecar(original: NextcloudFile, recipe: PhotoEditRecipe): String = photoEditJson.encodeToString(
    PhotoEditSidecar(
        sourcePath = original.path,
        sourceFileId = original.fileId,
        sourceEtag = original.etag,
        recipe = recipe,
    ),
)

/**
 * Decodes only our bounded, versioned recipe format and binds it back to the selected source.
 * A sidecar cannot redirect editing to another path or stable server object.
 */
fun decodePhotoEditSidecar(
    sidecarFile: NextcloudFile,
    content: ByteArray,
    original: NextcloudFile,
): ResolvedPhotoEditSidecar {
    require(content.size in 1..MAX_PHOTO_EDIT_SIDECAR_BYTES)
    require(sidecarFile.isPhotoEditSidecarCandidate(original))
    val sidecar = photoEditJson.decodeFromString<PhotoEditSidecar>(
        content.decodeToString(throwOnInvalidSequence = true),
    )
    require(sidecar.sourcePath == original.path) { "The edit recipe belongs to another source path." }
    if (sidecar.sourceFileId != null) {
        require(sidecar.sourceFileId == original.fileId) {
            "The edit recipe belongs to another server object."
        }
    }
    val freshness = when {
        sidecar.sourceEtag == null || original.etag == null -> PhotoEditSidecarFreshness.Unversioned
        sidecar.sourceEtag == original.etag -> PhotoEditSidecarFreshness.Current
        else -> PhotoEditSidecarFreshness.SourceChanged
    }
    return ResolvedPhotoEditSidecar(sidecarFile, sidecar, freshness)
}

internal suspend fun loadLatestPhotoEditSidecar(
    original: NextcloudFile,
    listSiblingFiles: suspend (parentPath: String) -> List<NextcloudFile>,
    readSidecar: suspend (NextcloudFile) -> ByteArray,
): ResolvedPhotoEditSidecar? {
    val parent = original.path.substringBeforeLast('/', missingDelimiterValue = "")
    val candidateFiles = listSiblingFiles(parent)
        .asSequence()
        .filter { it.isPhotoEditSidecarCandidate(original) }
        .filter { it.size == null || it.size in 1L..MAX_PHOTO_EDIT_SIDECAR_BYTES.toLong() }
        .sortedWith(
            compareByDescending<NextcloudFile> { it.lastModified.orEmpty() }
                .thenByDescending(NextcloudFile::name),
        )
        .take(MAX_PHOTO_EDIT_SIDECAR_CANDIDATES)
        .toList()
    val candidates = mutableListOf<ResolvedPhotoEditSidecar>()
    candidateFiles.forEach { file ->
        try {
            candidates += decodePhotoEditSidecar(file, readSidecar(file), original)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // One malformed or foreign sidecar must not hide another valid recipe.
        }
    }
    return candidates.maxWithOrNull(
        compareBy<ResolvedPhotoEditSidecar> { resolved ->
            when (resolved.freshness) {
                PhotoEditSidecarFreshness.Current -> 2
                PhotoEditSidecarFreshness.Unversioned -> 1
                PhotoEditSidecarFreshness.SourceChanged -> 0
            }
        }.thenBy { it.file.lastModified.orEmpty() }
            .thenBy { it.file.name },
    )
}

suspend fun loadLatestPhotoEditSidecar(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    original: NextcloudFile,
): ResolvedPhotoEditSidecar? {
    val davSource = resolvePhotoEditDavSource(services, session, original) ?: return null
    return loadLatestPhotoEditSidecar(
        original = davSource,
        listSiblingFiles = { parent -> services.listFiles(session, userId, parent) },
        readSidecar = { file ->
            services.downloadFile(
                session,
                userId,
                file.path,
                maxBytes = MAX_PHOTO_EDIT_SIDECAR_BYTES.toLong(),
            ).bytes
        },
    )
}

/**
 * Sidecar discovery must never make the editor unusable. It is optional metadata layered over a
 * photo that has already loaded successfully, so an unresolvable virtual path or failed folder
 * read degrades to no sidecar. Coroutine cancellation remains cooperative.
 */
internal suspend fun discoverPhotoEditSidecar(
    original: NextcloudFile,
    resolveSource: suspend (NextcloudFile) -> NextcloudFile?,
    loadSidecar: suspend (NextcloudFile) -> ResolvedPhotoEditSidecar?,
): PhotoEditSidecarDiscovery {
    return try {
        val davSource = resolveSource(original)
            ?: return PhotoEditSidecarDiscovery(davSource = null, sidecar = null)
        PhotoEditSidecarDiscovery(
            davSource = davSource,
            sidecar = loadSidecar(davSource),
        )
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        PhotoEditSidecarDiscovery(davSource = null, sidecar = null)
    }
}

suspend fun discoverPhotoEditSidecar(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    original: NextcloudFile,
): PhotoEditSidecarDiscovery = discoverPhotoEditSidecar(
    original = original,
    resolveSource = { resolvePhotoEditDavSource(services, session, it) },
    loadSidecar = { loadLatestPhotoEditSidecar(services, session, userId, it) },
)

internal suspend fun exportPhotoEditSidecar(
    original: NextcloudFile,
    recipe: PhotoEditRecipe,
    nonce: String,
    listExistingPaths: suspend (parentPath: String) -> Set<String>,
    createSidecar: suspend (path: String, content: String) -> Boolean,
): PhotoEditExportResult {
    if (recipe.isIdentity) return PhotoEditExportResult.Failed("Make an edit before saving a recipe.")
    val parent = original.path.substringBeforeLast('/', missingDelimiterValue = "")
    val plan = createPhotoEditExportPlan(original.path, listExistingPaths(parent), nonce)
    val created = createSidecar(plan.sidecarPath, encodePhotoEditSidecar(original, recipe))
    return if (created) {
        PhotoEditExportResult.Created(plan.sidecarPath)
    } else {
        PhotoEditExportResult.Failed("The sidecar path already exists. Try saving again.")
    }
}

suspend fun savePhotoEditSidecar(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    original: NextcloudFile,
    recipe: PhotoEditRecipe,
    nonce: String,
): PhotoEditExportResult = exportPhotoEditSidecar(
    original = original,
    recipe = recipe,
    nonce = nonce,
    listExistingPaths = { parent -> services.listFiles(session, userId, parent).mapTo(mutableSetOf(), NextcloudFile::path) },
    createSidecar = { path, content -> services.createTextFileIfAbsent(session, userId, path, content).wasCreated },
)

private fun normalizePhotoExportExtension(extension: String): String =
    extension.trim().removePrefix(".").lowercase().also { normalized ->
        require(normalized.matches(Regex("[a-z0-9]{1,$MAX_EXPORT_EXTENSION_LENGTH}")))
    }

private val photoEditJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private fun NextcloudFile.isPhotoEditSidecarCandidate(original: NextcloudFile): Boolean {
    if (isDirectory || path.substringBeforeLast('/', missingDelimiterValue = "") !=
        original.path.substringBeforeLast('/', missingDelimiterValue = "")
    ) {
        return false
    }
    val originalStem = original.name.substringBeforeLast('.', missingDelimiterValue = original.name)
    return name.startsWith("$originalStem.nextcloud-native-") &&
        name.endsWith(PHOTO_EDIT_SIDECAR_EXTENSION)
}

private fun String.isSyntheticMemoriesMediaPath(): Boolean =
    startsWith("memories/people/") || startsWith("memories/collections/")

private fun String.isSafeDavRelativePath(): Boolean {
    if (isBlank() || startsWith('/') || endsWith('/') || any(Char::isISOControl)) return false
    return split('/').all { segment -> segment.isNotEmpty() && segment != "." && segment != ".." }
}

private fun String.isSafePhotoIdentityText(maximumLength: Int): Boolean =
    isNotBlank() && length <= maximumLength && none(Char::isISOControl)

private const val MIN_CROP_SPAN = 0.05f
private const val MAX_COPY_NONCE_LENGTH = 24
private const val MAX_COPY_STEM_CHARACTERS = 64
private const val MAX_EXPORT_EXTENSION_LENGTH = 10
const val PHOTO_EDIT_SIDECAR_FORMAT = "dev.obiente.nextcloud-native.photo-edit/1"
const val PHOTO_EDIT_SIDECAR_EXTENSION = ".photo-edit.json"
const val MAX_PHOTO_EDIT_SIDECAR_BYTES = 256 * 1024
const val MAX_PHOTO_EDIT_SOURCE_BYTES = 64L * 1024L * 1024L
private const val MAX_PHOTO_IDENTITY_RESPONSE_BYTES = 512L * 1024L
private const val MAX_PHOTO_IDENTITY_ETAG_LENGTH = 1_024
private const val MAX_PHOTO_IDENTITY_MIME_LENGTH = 256
private const val MAX_PHOTO_EDIT_SIDECAR_CANDIDATES = 16
private val MEMORIES_DECODABLE_PASSTHROUGH_MIME_TYPES = setOf(
    "image/gif",
    "image/jpeg",
    "image/jpg",
    "image/png",
    "image/webp",
)

private val MEMORIES_UNINFORMATIVE_MIME_TYPES = setOf(
    "",
    "application/octet-stream",
    "binary/octet-stream",
)

private val MEMORIES_DECODABLE_PASSTHROUGH_EXTENSIONS = setOf(
    "gif",
    "jpeg",
    "jpg",
    "png",
    "webp",
)
