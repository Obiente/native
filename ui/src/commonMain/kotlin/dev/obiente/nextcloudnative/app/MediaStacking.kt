package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

enum class MediaAssetFormat { Raw, Jpeg, Image, Video, Other }

data class MediaStack(
    val id: String,
    val cover: NextcloudFile,
    val members: List<NextcloudFile>,
    val hasRaw: Boolean,
    val hasRenderedImage: Boolean,
) {
    val badge: String?
        get() = when {
            hasRaw && members.count { it.mediaAssetFormat() == MediaAssetFormat.Jpeg } > 0 -> "RAW + JPG"
            hasRaw && hasRenderedImage -> "RAW + ${members.size - 1}"
            hasRaw -> "RAW"
            members.size > 1 -> members.size.toString()
            else -> null
    }
}

data class MediaStackViewerSequence(
    val navigationItems: List<NextcloudFile>,
    val sourceMembers: List<NextcloudFile>,
) {
    init {
        require(navigationItems.map(NextcloudFile::path).distinct().size == navigationItems.size) {
            "The media viewer navigation contains duplicate items."
        }
        require(sourceMembers.map(NextcloudFile::path).distinct().size == sourceMembers.size) {
            "The media viewer source list contains duplicate items."
        }
        require(navigationItems.all { item -> sourceMembers.any { source -> source.path == item.path } }) {
            "Every media viewer navigation item must remain available as a source."
        }
    }
}

data class MediaSourceChoice(
    val file: NextcloudFile,
    val label: String,
    val format: MediaAssetFormat,
) {
    val pickerLabel: String
        get() = "$label · ${file.name}"
}

data class MediaSourcePlan(
    val selected: MediaSourceChoice,
    val choices: List<MediaSourceChoice>,
    val previewCandidates: List<MediaSourceChoice>,
    val fullQualityCandidates: List<MediaSourceChoice>,
) {
    fun fullQualityCandidatesAtZoom(zoom: Float): List<MediaSourceChoice> {
        require(zoom.isFinite() && zoom >= 1f) { "The media zoom is invalid." }
        return if (zoom >= FULL_QUALITY_MEDIA_ZOOM_THRESHOLD) fullQualityCandidates else emptyList()
    }
}

data class LoadedMediaSource<T>(
    val value: T,
    val source: MediaSourceChoice,
    val usedFallback: Boolean,
)

data class LoadedFullResolutionMediaSource<T>(
    val value: T,
    val source: MediaSourceChoice,
    val usedFallback: Boolean,
    val payloadSource: FullResolutionPhotoSource,
)

fun fullQualityMediaPayloadKind(
    displayed: MediaSourceChoice,
    payloadSource: FullResolutionPhotoSource,
): MediaDisplayPayloadKind =
    if (
        payloadSource == FullResolutionPhotoSource.MemoriesTranscoded &&
        displayed.format == MediaAssetFormat.Raw
    ) {
        MediaDisplayPayloadKind.MemoriesRawRender
    } else {
        MediaDisplayPayloadKind.ServerPreview
    }

/**
 * Describes what is actually visible without confusing a rendered RAW preview with RAW bytes.
 * Mutations always continue to target [selected], even when a sibling is used for display.
 */
fun describeMediaDisplaySource(
    selected: MediaSourceChoice,
    displayed: MediaSourceChoice,
    highDetail: Boolean,
    payloadKind: MediaDisplayPayloadKind = MediaDisplayPayloadKind.ServerPreview,
): String {
    val source = when {
        payloadKind == MediaDisplayPayloadKind.MemoriesRawRender ->
            if (highDetail) "Generated high-detail RAW render" else "Generated RAW render"
        payloadKind == MediaDisplayPayloadKind.EmbeddedCameraPreview -> "RAW embedded camera preview"
        else -> when (displayed.format) {
            MediaAssetFormat.Raw ->
                if (highDetail) "High-detail RAW render" else "RAW server preview"
            MediaAssetFormat.Jpeg ->
                if (highDetail) "High-detail JPEG render" else "JPEG server preview"
            MediaAssetFormat.Image ->
                if (highDetail) "High-detail image render" else "Image preview"
            MediaAssetFormat.Video -> "Video preview"
            MediaAssetFormat.Other -> "File preview"
        }
    }
    return if (displayed.file.path == selected.file.path) {
        source
    } else {
        "$source fallback - actions target ${selected.file.name}"
    }
}

fun stackMediaFiles(files: List<NextcloudFile>): List<MediaStack> {
    return files.withIndex()
        .groupBy { indexed -> indexed.value.mediaDirectoryKey() }
        .values
        .sortedBy { directory -> directory.minOf { indexed -> indexed.index } }
        .flatMap { directory ->
            val rawByKey = directory
                .filter { indexed -> indexed.value.mediaAssetFormat() == MediaAssetFormat.Raw }
                .groupBy { indexed -> indexed.value.rawStackKey() }
            val assignedRawPaths = mutableSetOf<String>()
            val stackedByRenderedPath = linkedMapOf<String, List<NextcloudFile>>()
            directory.forEach { indexed ->
                val rendered = indexed.value
                if (rendered.mediaAssetFormat() !in setOf(MediaAssetFormat.Jpeg, MediaAssetFormat.Image)) {
                    return@forEach
                }
                val rawSiblings = rendered.renderedStackKeys()
                    .flatMap { key -> rawByKey[key].orEmpty() }
                    .map { it.value }
                    .distinctBy(NextcloudFile::path)
                    .filterNot { raw -> raw.path in assignedRawPaths }
                if (rawSiblings.isNotEmpty()) {
                    stackedByRenderedPath[rendered.path] = rawSiblings
                    rawSiblings.mapTo(assignedRawPaths, NextcloudFile::path)
                }
            }
            directory.mapNotNull { indexed ->
                val file = indexed.value
                when {
                    file.path in assignedRawPaths -> null
                    file.path in stackedByRenderedPath ->
                        createMediaStack(listOf(file) + stackedByRenderedPath.getValue(file.path))
                    else -> createMediaStack(listOf(file))
                }
            }
        }
}

fun mediaStackViewerSequence(stacks: List<MediaStack>): MediaStackViewerSequence =
    MediaStackViewerSequence(
        navigationItems = stacks.map(MediaStack::cover),
        sourceMembers = stacks
            .asSequence()
            .flatMap { stack -> stack.members.asSequence() }
            .distinctBy(NextcloudFile::path)
            .toList(),
    )

fun mediaViewerNavigationIndex(
    navigationItems: List<NextcloudFile>,
    selected: NextcloudFile,
): Int {
    val exact = navigationItems.indexOfFirst { item -> item.path == selected.path }
    if (exact >= 0) return exact
    return navigationItems.indexOfFirst { item -> item.sharesMediaStackWith(selected) }
}

/**
 * Reconstructs a same-folder, same-stem source group for a viewer item. The selected representation
 * always remains first; siblings are explicit fallbacks and are never silently treated as the
 * target of edits or metadata changes.
 */
fun planMediaSources(files: List<NextcloudFile>, selected: NextcloudFile): MediaSourcePlan {
    val siblings = (listOf(selected) + files)
        .filter { candidate ->
            candidate.isPhotoMedia() && selected.isPhotoMedia() &&
                candidate.sharesMediaStackWith(selected)
        }
        .distinctBy(NextcloudFile::path)
        .sortedWith(mediaSourceOrder)
        .ifEmpty { listOf(selected) }
    val ordered = (listOf(selected) + siblings.filterNot { it.path == selected.path })
        .map { file ->
            val format = file.mediaAssetFormat()
            MediaSourceChoice(file, format.sourceLabel(), format)
        }
    val previewCandidates = ordered.filter {
        (it.file.fileId != null && it.file.hasPreview) ||
            (it.file.isRawPhoto() && it.file.originalAccessAllowed)
    }
    val fullQualityCandidates = ordered.filter {
        it.file.fileId != null && it.file.originalAccessAllowed && it.format != MediaAssetFormat.Video
    }
    return MediaSourcePlan(
        selected = ordered.first(),
        choices = siblings.sortedWith(mediaSourceOrder).map { file ->
            val format = file.mediaAssetFormat()
            MediaSourceChoice(file, format.sourceLabel(), format)
        },
        previewCandidates = previewCandidates,
        fullQualityCandidates = fullQualityCandidates,
    )
}

/**
 * Tries each bounded source independently. A malformed RAW/server payload cannot prevent a rendered
 * sibling from loading, and callers can disclose whether the displayed image is a fallback.
 */
internal suspend fun <T> loadFirstUsableMediaSource(
    candidates: List<MediaSourceChoice>,
    maximumPayloadBytes: Int = MAX_MEDIA_PREVIEW_BYTES,
    load: suspend (NextcloudFile) -> ByteArray,
    decode: (ByteArray) -> T?,
): LoadedMediaSource<T>? {
    require(maximumPayloadBytes >= MIN_MEDIA_IMAGE_PAYLOAD_BYTES)
    candidates.forEachIndexed { index, candidate ->
        val decoded = runCatching {
            val bytes = load(candidate.file)
            require(isBoundedDisplayImagePayload(bytes, maximumPayloadBytes)) {
                "The server did not return a bounded display image."
            }
            requireNotNull(decode(bytes)) { "The display image could not be decoded." }
        }.getOrNull()
        if (decoded != null) return LoadedMediaSource(decoded, candidate, usedFallback = index > 0)
    }
    return null
}

internal suspend fun <T> loadFirstUsableFullResolutionMediaSource(
    candidates: List<MediaSourceChoice>,
    maximumPayloadBytes: Int = MAX_MEDIA_PREVIEW_BYTES,
    load: suspend (NextcloudFile) -> FullResolutionPhotoPayload,
    decode: (FullResolutionPhotoPayload) -> T?,
): LoadedFullResolutionMediaSource<T>? {
    require(maximumPayloadBytes >= MIN_MEDIA_IMAGE_PAYLOAD_BYTES)
    candidates.forEachIndexed { index, candidate ->
        val decoded = try {
            val payload = load(candidate.file)
            require(isBoundedDisplayImagePayload(payload.bytes, maximumPayloadBytes)) {
                "The server did not return a bounded full-resolution image."
            }
            requireNotNull(decode(payload)) { "The full-resolution image could not be decoded." } to payload.source
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            null
        }
        if (decoded != null) {
            return LoadedFullResolutionMediaSource(
                value = decoded.first,
                source = candidate,
                usedFallback = index > 0,
                payloadSource = decoded.second,
            )
        }
    }
    return null
}

fun isBoundedDisplayImagePayload(
    bytes: ByteArray,
    maximumPayloadBytes: Int = MAX_MEDIA_PREVIEW_BYTES,
): Boolean {
    require(maximumPayloadBytes >= MIN_MEDIA_IMAGE_PAYLOAD_BYTES)
    if (bytes.size !in MIN_MEDIA_IMAGE_PAYLOAD_BYTES..maximumPayloadBytes) return false
    return when {
        bytes.startsWithBytes(0xFF, 0xD8, 0xFF) -> true
        bytes.startsWithBytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> true
        bytes.startsWithAscii("GIF87a") || bytes.startsWithAscii("GIF89a") -> true
        bytes.size >= 12 && bytes.startsWithAscii("RIFF") &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> true
        bytes.startsWithBytes(0x42, 0x4D) -> true
        bytes.size >= 12 && bytes.copyOfRange(4, 8).decodeToString() == "ftyp" -> true
        else -> false
    }
}

fun NextcloudFile.isRawPhoto(): Boolean = mediaAssetFormat() == MediaAssetFormat.Raw

fun NextcloudFile.canOpenInMediaViewer(): Boolean =
    !isDirectory && (
        (
            fileId != null &&
                (hasPreview || (isRawPhoto() && originalAccessAllowed))
        ) ||
            canUseEmbeddedRafPreview()
    )

internal fun NextcloudFile.canUseEmbeddedRafPreview(): Boolean =
    !isDirectory &&
        isRawPhoto() &&
        name.substringAfterLast('.', missingDelimiterValue = "").equals("raf", ignoreCase = true) &&
        originalAccessAllowed &&
        davPathAuthoritative &&
        runCatching { requireSafeFilePath(path, allowRoot = false) }.isSuccess &&
        etag?.let { runCatching { requireSafeFileRangeEtag(it) }.isSuccess } == true

fun NextcloudFile.isPhotoMedia(): Boolean = mediaAssetFormat() in setOf(
    MediaAssetFormat.Raw,
    MediaAssetFormat.Jpeg,
    MediaAssetFormat.Image,
)

fun rawPhotoFileNameSearchPatterns(): List<String> =
    rawPhotoExtensions.sorted().map { extension -> "%.$extension" }

fun selectMediaSearchFiles(
    results: List<NextcloudFile>,
    maximumResults: Int = 80,
): List<NextcloudFile> {
    require(maximumResults > 0)
    return results.asSequence()
        .filterNot(NextcloudFile::isDirectory)
        .take(maximumResults)
        .toList()
}

fun NextcloudFile.mediaAssetFormat(): MediaAssetFormat {
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    val mime = mimeType?.substringBefore(';')?.lowercase().orEmpty()
    return when {
        extension in rawPhotoExtensions || mime in rawPhotoMimeTypes -> MediaAssetFormat.Raw
        extension in jpegExtensions || mime in setOf("image/jpeg", "image/jpg") -> MediaAssetFormat.Jpeg
        mime.startsWith("image/") || extension in renderedImageExtensions -> MediaAssetFormat.Image
        mime.startsWith("video/") || extension in videoExtensions -> MediaAssetFormat.Video
        else -> MediaAssetFormat.Other
    }
}

private fun createMediaStack(files: List<NextcloudFile>): MediaStack {
    val ordered = files.sortedWith(mediaSourceOrder)
    val cover = ordered.first()
    return MediaStack(
        id = files.joinToString("\u001f") { it.path }.lowercase(),
        cover = cover,
        members = ordered,
        hasRaw = ordered.any(NextcloudFile::isRawPhoto),
        hasRenderedImage = ordered.any {
            it.mediaAssetFormat() in setOf(MediaAssetFormat.Jpeg, MediaAssetFormat.Image)
        },
    )
}

private val mediaSourceOrder = compareBy<NextcloudFile> { file ->
    when (file.mediaAssetFormat()) {
        MediaAssetFormat.Jpeg -> 0
        MediaAssetFormat.Image -> 1
        MediaAssetFormat.Raw -> 2
        MediaAssetFormat.Video -> 3
        MediaAssetFormat.Other -> 4
    }
}.thenBy { it.name.lowercase() }

private fun MediaAssetFormat.sourceLabel(): String = when (this) {
    MediaAssetFormat.Raw -> "RAW"
    MediaAssetFormat.Jpeg -> "JPEG"
    MediaAssetFormat.Image -> "Rendered"
    MediaAssetFormat.Video -> "Video"
    MediaAssetFormat.Other -> "File"
}

internal fun NextcloudFile.sharesMediaStackWith(other: NextcloudFile): Boolean {
    if (path == other.path) return true
    if (mediaDirectoryKey() != other.mediaDirectoryKey()) return false
    val thisFormat = mediaAssetFormat()
    val otherFormat = other.mediaAssetFormat()
    return when {
        thisFormat == MediaAssetFormat.Raw &&
            otherFormat in setOf(MediaAssetFormat.Jpeg, MediaAssetFormat.Image) ->
            rawStackKey() in other.renderedStackKeys()
        otherFormat == MediaAssetFormat.Raw &&
            thisFormat in setOf(MediaAssetFormat.Jpeg, MediaAssetFormat.Image) ->
            other.rawStackKey() in renderedStackKeys()
        else -> false
    }
}

private fun NextcloudFile.mediaDirectoryKey(): String =
    path.substringBeforeLast('/', missingDelimiterValue = "").lowercase()

private fun NextcloudFile.mediaStem(): String =
    name.substringBeforeLast('.', missingDelimiterValue = name).lowercase()

/**
 * Memories treats Google-style `name.ORIGINAL.dng` files as the RAW sibling of `name.jpg`.
 * Ordinary dotted RAW names retain their complete stem to avoid unrelated stacks.
 */
private fun NextcloudFile.rawStackKey(): String = mediaStem().let { stem ->
    if (".original" in stem) stem.substringBefore('.') else stem
}

/**
 * A dotted rendered name can pair with either the exact RAW stem or Google's shortened RAW stem.
 */
private fun NextcloudFile.renderedStackKeys(): Set<String> = mediaStem().let { stem ->
    buildSet {
        add(stem)
        if ('.' in stem) add(stem.substringBefore('.'))
    }
}

private fun ByteArray.startsWithBytes(vararg expected: Int): Boolean =
    size >= expected.size && expected.indices.all { index -> this[index].toInt() and 0xFF == expected[index] }

private fun ByteArray.startsWithAscii(expected: String): Boolean =
    size >= expected.length && copyOfRange(0, expected.length).decodeToString() == expected

const val MAX_MEDIA_PREVIEW_BYTES = 16 * 1024 * 1024
const val FULL_QUALITY_MEDIA_ZOOM_THRESHOLD = 1.35f
private const val MIN_MEDIA_IMAGE_PAYLOAD_BYTES = 8

private val rawPhotoExtensions = setOf(
    "3fr", "arw", "cr2", "cr3", "dcr", "dng", "erf", "fff", "iiq", "k25", "kdc", "mef",
    "mos", "mrw", "nef", "nrw", "orf", "pef", "raf", "raw", "rw2", "rwl", "sr2", "srf",
    "srw", "x3f",
)
private val rawPhotoMimeTypes = setOf(
    "image/x-adobe-dng", "image/x-canon-cr2", "image/x-canon-cr3", "image/x-fuji-raf",
    "image/x-nikon-nef", "image/x-olympus-orf", "image/x-panasonic-rw2", "image/x-pentax-pef",
    "image/x-sony-arw", "image/x-dcraw",
)
private val jpegExtensions = setOf("jpg", "jpeg", "jpe")
private val renderedImageExtensions = setOf("avif", "bmp", "gif", "heic", "heif", "png", "tif", "tiff", "webp")
private val videoExtensions = setOf("3gp", "avi", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "webm")
