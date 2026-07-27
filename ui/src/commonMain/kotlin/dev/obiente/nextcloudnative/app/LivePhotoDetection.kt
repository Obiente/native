package dev.obiente.nextcloudnative.app

/**
 * A byte range within one immutable media object.
 *
 * The range is descriptive only. Reading it remains the responsibility of an ETag-bound platform
 * transport so detection cannot silently combine bytes from different generations.
 */
data class LivePhotoByteRange(
    val offset: Long,
    val length: Long,
) {
    init {
        require(offset >= 0L) { "The live-photo byte-range offset is invalid." }
        require(length > 0L) { "The live-photo byte-range length is invalid." }
        require(offset <= Long.MAX_VALUE - length) { "The live-photo byte range overflows." }
    }

    val endExclusive: Long
        get() = offset + length
}

enum class AndroidMotionPhotoMetadataKind {
    MotionPhotoV1,
    LegacyMicroVideoV1,
}

/**
 * Metadata evidence that identifies where an Android motion stream should begin.
 *
 * This is deliberately not a confirmed logical asset. Android's format warns that XMP can survive
 * after an editor strips the appended video, so callers must validate a bounded probe with
 * [confirmAndroidMotionPhoto].
 */
data class AndroidMotionPhotoCandidate(
    val metadataKind: AndroidMotionPhotoMetadataKind,
    val primaryMimeType: String,
    val motionMimeType: String,
    val videoRange: LivePhotoByteRange,
    val photoPresentationTimestampUs: Long?,
)

data class LivePhotoVideoProbe(
    val offset: Long,
    val bytes: ByteArray,
) {
    init {
        require(offset >= 0L) { "The live-photo probe offset is invalid." }
        require(bytes.size <= MAX_LIVE_PHOTO_VIDEO_PROBE_BYTES) {
            "The live-photo probe exceeds the bounded read limit."
        }
    }
}

sealed interface DetectedLivePhotoAsset {
    data class EmbeddedAndroidMotionPhoto(
        val metadataKind: AndroidMotionPhotoMetadataKind,
        val primaryMimeType: String,
        val motionMimeType: String,
        val videoRange: LivePhotoByteRange,
        val photoPresentationTimestampUs: Long?,
    ) : DetectedLivePhotoAsset

    data class PairedAppleLivePhoto(
        val contentIdentifier: String,
        val still: AppleLivePhotoComponent,
        val pairedVideo: AppleLivePhotoComponent,
    ) : DetectedLivePhotoAsset
}

enum class AppleLivePhotoComponentRole {
    Still,
    PairedVideo,
}

/**
 * Metadata extracted from one original Apple Photos resource.
 *
 * Platform code owns extraction from HEIC/JPEG MakerApple metadata and QuickTime metadata. This
 * platform-neutral model deliberately does not pair by basename, timestamp, or directory.
 */
data class AppleLivePhotoComponent(
    /** Account- or library-scoped identity shared only by components eligible to pair. */
    val pairingScopeIdentity: String,
    val resourceIdentity: String,
    val displayName: String,
    val mimeType: String,
    val role: AppleLivePhotoComponentRole,
    val contentIdentifier: String?,
)

/**
 * Parses bounded Android XMP into a candidate probe location.
 *
 * Modern Motion Photo metadata takes precedence over legacy MicroVideo metadata. An explicit
 * `MotionPhoto=0` always falls back to an ordinary photo. Unknown versions, unsupported primary
 * formats, inconsistent container items, and ranges outside [containerSize] are rejected.
 */
fun planAndroidMotionPhotoProbe(
    primaryMimeType: String,
    containerSize: Long,
    xmpBytes: ByteArray,
): AndroidMotionPhotoCandidate? {
    if (containerSize <= 0L || xmpBytes.isEmpty() || xmpBytes.size > MAX_LIVE_PHOTO_XMP_BYTES) return null
    val normalizedPrimaryMime = primaryMimeType.normalizedMediaMimeType() ?: return null
    val elements = parseBoundedXml(xmpBytes) ?: return null

    val motionPhotoValues = elements.namespacedValues(CAMERA_NAMESPACE, "MotionPhoto")
    if (motionPhotoValues.isNotEmpty()) {
        if (motionPhotoValues.size != 1) return null
        val motionPhoto = motionPhotoValues.single()
        if (motionPhoto != "1") return null
        if (elements.namespacedValue(CAMERA_NAMESPACE, "MotionPhotoVersion") != "1") return null
        if (normalizedPrimaryMime !in MODERN_MOTION_PHOTO_PRIMARY_MIME_TYPES) return null
        val items = elements.motionPhotoContainerItems() ?: return null
        if (items.firstOrNull()?.semantic != "Primary") return null
        if (items.count { it.semantic == "Primary" } != 1) return null
        val primary = items.first()
        if (primary.mimeType != normalizedPrimaryMime) return null
        if (
            normalizedPrimaryMime in BOXED_MOTION_PHOTO_PRIMARY_MIME_TYPES &&
            primary.padding != MOTION_PHOTO_VIDEO_DATA_BOX_BYTES
        ) {
            return null
        }
        val motionItems = items.filter { it.semantic == "MotionPhoto" }
        if (motionItems.size != 1 || items.last() != motionItems.single()) return null
        val motion = motionItems.single()
        if (motion.mimeType !in MOTION_VIDEO_MIME_TYPES) return null
        val videoLength = motion.length?.takeIf { it > 0L } ?: return null
        val declaredTrailerBytes = items.drop(1).fold(primary.padding ?: 0L) { total, item ->
            val itemLength = item.length ?: return null
            if (total > Long.MAX_VALUE - itemLength) return null
            total + itemLength
        }
        if (declaredTrailerBytes >= containerSize) return null
        val videoRange = trailerRange(containerSize, videoLength) ?: return null
        val presentationTimestamp = elements.presentationTimestampOrNull(
            "MotionPhotoPresentationTimestampUs",
        ) ?: return null
        return AndroidMotionPhotoCandidate(
            metadataKind = AndroidMotionPhotoMetadataKind.MotionPhotoV1,
            primaryMimeType = normalizedPrimaryMime,
            motionMimeType = motion.mimeType,
            videoRange = videoRange,
            photoPresentationTimestampUs = presentationTimestamp.value,
        )
    }

    if (normalizedPrimaryMime != LEGACY_MOTION_PHOTO_PRIMARY_MIME_TYPE) return null
    if (elements.namespacedValue(CAMERA_NAMESPACE, "MicroVideo") != "1") return null
    if (elements.namespacedValue(CAMERA_NAMESPACE, "MicroVideoVersion") != "1") return null
    val videoLength = elements.namespacedValue(CAMERA_NAMESPACE, "MicroVideoOffset")
        ?.strictPositiveLong()
        ?: return null
    val videoRange = trailerRange(containerSize, videoLength) ?: return null
    val presentationTimestamp = elements.presentationTimestampOrNull(
        "MicroVideoPresentationTimestampUs",
    ) ?: return null
    return AndroidMotionPhotoCandidate(
        metadataKind = AndroidMotionPhotoMetadataKind.LegacyMicroVideoV1,
        primaryMimeType = normalizedPrimaryMime,
        motionMimeType = "video/mp4",
        videoRange = videoRange,
        photoPresentationTimestampUs = presentationTimestamp.value,
    )
}

/**
 * Confirms that the candidate's exact trailer offset begins with a bounded ISO BMFF video probe.
 *
 * This prevents residual XMP from turning an edited ordinary photo into a false Motion Photo.
 */
fun confirmAndroidMotionPhoto(
    candidate: AndroidMotionPhotoCandidate,
    probe: LivePhotoVideoProbe,
): DetectedLivePhotoAsset.EmbeddedAndroidMotionPhoto? {
    if (probe.offset != candidate.videoRange.offset) return null
    if (!probe.bytes.hasIsoBaseMediaFileTypeBox(candidate.videoRange.length)) return null
    return DetectedLivePhotoAsset.EmbeddedAndroidMotionPhoto(
        metadataKind = candidate.metadataKind,
        primaryMimeType = candidate.primaryMimeType,
        motionMimeType = candidate.motionMimeType,
        videoRange = candidate.videoRange,
        photoPresentationTimestampUs = candidate.photoPresentationTimestampUs,
    )
}

/**
 * Pairs original Apple still and MOV resources only when their extracted identifiers match exactly.
 *
 * Filename, stem, timestamp, and folder proximity are intentionally ignored because they are not
 * authoritative pairing evidence.
 */
fun pairAppleLivePhoto(
    still: AppleLivePhotoComponent,
    pairedVideo: AppleLivePhotoComponent,
): DetectedLivePhotoAsset.PairedAppleLivePhoto? {
    if (still.role != AppleLivePhotoComponentRole.Still) return null
    if (pairedVideo.role != AppleLivePhotoComponentRole.PairedVideo) return null
    if (still.mimeType.normalizedMediaMimeType() !in APPLE_LIVE_PHOTO_STILL_MIME_TYPES) return null
    if (pairedVideo.mimeType.normalizedMediaMimeType() != APPLE_LIVE_PHOTO_VIDEO_MIME_TYPE) return null
    if (!still.hasSafeLivePhotoComponentIdentity() || !pairedVideo.hasSafeLivePhotoComponentIdentity()) return null
    if (still.pairingScopeIdentity != pairedVideo.pairingScopeIdentity) return null
    val stillIdentifier = still.contentIdentifier?.takeIf(String::isSafeAppleContentIdentifier) ?: return null
    val videoIdentifier = pairedVideo.contentIdentifier?.takeIf(String::isSafeAppleContentIdentifier) ?: return null
    if (stillIdentifier != videoIdentifier || still.resourceIdentity == pairedVideo.resourceIdentity) return null
    return DetectedLivePhotoAsset.PairedAppleLivePhoto(
        contentIdentifier = stillIdentifier,
        still = still.copy(contentIdentifier = stillIdentifier),
        pairedVideo = pairedVideo.copy(contentIdentifier = videoIdentifier),
    )
}

private data class MotionPhotoContainerItem(
    val mimeType: String,
    val semantic: String,
    val length: Long?,
    val padding: Long?,
)

private data class OptionalPresentationTimestamp(val value: Long?)

private data class ParsedXmlElement(
    val namespaceUri: String?,
    val localName: String,
    val attributes: List<ParsedXmlAttribute>,
) {
    fun attribute(namespaceUri: String, localName: String): String? =
        attributes.singleOrNull { it.namespaceUri == namespaceUri && it.localName == localName }?.value
}

private data class ParsedXmlAttribute(
    val namespaceUri: String?,
    val localName: String,
    val value: String,
)

private data class OpenXmlElement(
    val qualifiedName: String,
    val namespaces: Map<String, String>,
)

private data class ParsedStartTag(
    val qualifiedName: String,
    val attributes: Map<String, String>,
    val selfClosing: Boolean,
)

private fun List<ParsedXmlElement>.motionPhotoContainerItems(): List<MotionPhotoContainerItem>? {
    val itemElements = filter { it.namespaceUri == CONTAINER_NAMESPACE && it.localName == "Item" }
    if (itemElements.size !in 2..MAX_MOTION_PHOTO_CONTAINER_ITEMS) return null
    return itemElements.map { element ->
        val mimeType = element.attribute(CONTAINER_ITEM_NAMESPACE, "Mime")
            ?.normalizedMediaMimeType()
            ?: return null
        val semantic = element.attribute(CONTAINER_ITEM_NAMESPACE, "Semantic")
            ?.takeIf { it.isSafeMetadataToken(MAX_MOTION_PHOTO_SEMANTIC_LENGTH) }
            ?: return null
        val lengthText = element.attribute(CONTAINER_ITEM_NAMESPACE, "Length")
        val length = when {
            semantic == "Primary" && lengthText == null -> null
            semantic == "Primary" && lengthText == "0" -> 0L
            semantic == "Primary" -> return null
            else -> lengthText?.strictNonNegativeLong() ?: return null
        }
        val paddingText = element.attribute(CONTAINER_ITEM_NAMESPACE, "Padding")
        val padding = when {
            semantic != "Primary" && paddingText != null -> return null
            paddingText == null -> null
            else -> paddingText.strictNonNegativeLong() ?: return null
        }
        MotionPhotoContainerItem(mimeType, semantic, length, padding)
    }
}

private fun List<ParsedXmlElement>.presentationTimestampOrNull(
    localName: String,
): OptionalPresentationTimestamp? {
    val values = namespacedValues(CAMERA_NAMESPACE, localName)
    if (values.isEmpty()) return OptionalPresentationTimestamp(null)
    if (values.size != 1) return null
    val value = values.single()
    return OptionalPresentationTimestamp(value.toLongOrNull()?.takeIf { it >= -1L } ?: return null)
}

private fun List<ParsedXmlElement>.namespacedValue(namespaceUri: String, localName: String): String? {
    val values = namespacedValues(namespaceUri, localName)
    return values.singleOrNull()
}

private fun List<ParsedXmlElement>.namespacedValues(namespaceUri: String, localName: String): List<String> =
    flatMap { element ->
        element.attributes.filter { it.namespaceUri == namespaceUri && it.localName == localName }
    }.map(ParsedXmlAttribute::value)

private fun trailerRange(containerSize: Long, trailerLength: Long): LivePhotoByteRange? {
    if (trailerLength <= 0L || trailerLength >= containerSize) return null
    return LivePhotoByteRange(offset = containerSize - trailerLength, length = trailerLength)
}

private fun ByteArray.hasIsoBaseMediaFileTypeBox(videoLength: Long): Boolean {
    if (size < MIN_LIVE_PHOTO_VIDEO_PROBE_BYTES || videoLength < MIN_LIVE_PHOTO_VIDEO_PROBE_BYTES) return false
    val boxSize = readUnsignedBigEndianInt(0)
    if (boxSize !in MIN_LIVE_PHOTO_VIDEO_PROBE_BYTES.toLong()..videoLength) return false
    return copyOfRange(4, 8).decodeToString() == "ftyp"
}

private fun ByteArray.readUnsignedBigEndianInt(offset: Int): Long {
    if (offset < 0 || size - offset < Int.SIZE_BYTES) return -1L
    return (0 until Int.SIZE_BYTES).fold(0L) { value, index ->
        (value shl Byte.SIZE_BITS) or (this[offset + index].toLong() and 0xFFL)
    }
}

private fun parseBoundedXml(bytes: ByteArray): List<ParsedXmlElement>? {
    val xml = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
    if (xml.any { it.isISOControl() && it !in setOf('\t', '\n', '\r') }) return null
    val elements = mutableListOf<ParsedXmlElement>()
    val stack = mutableListOf<OpenXmlElement>()
    var index = 0
    var rootComplete = false
    while (index < xml.length) {
        val tagStart = xml.indexOf('<', index)
        if (tagStart < 0) {
            if (stack.isEmpty() && xml.substring(index).isNotBlank()) return null
            break
        }
        if (stack.isEmpty() && xml.substring(index, tagStart).isNotBlank()) return null
        when {
            xml.startsWith("<?", tagStart) -> {
                val end = xml.indexOf("?>", tagStart + 2)
                if (end < 0) return null
                index = end + 2
            }
            xml.startsWith("<!--", tagStart) -> {
                val end = xml.indexOf("-->", tagStart + 4)
                if (end < 0) return null
                index = end + 3
            }
            xml.startsWith("<![CDATA[", tagStart) -> {
                if (stack.isEmpty()) return null
                val end = xml.indexOf("]]>", tagStart + 9)
                if (end < 0) return null
                index = end + 3
            }
            xml.startsWith("<!", tagStart) -> return null
            xml.startsWith("</", tagStart) -> {
                val end = xml.indexOf('>', tagStart + 2)
                if (end < 0) return null
                val qualifiedName = xml.substring(tagStart + 2, end).trim()
                if (!qualifiedName.isValidQualifiedXmlName()) return null
                if (stack.lastOrNull()?.qualifiedName != qualifiedName) return null
                stack.removeAt(stack.lastIndex)
                if (stack.isEmpty()) rootComplete = true
                index = end + 1
            }
            else -> {
                val end = xml.findXmlTagEnd(tagStart + 1) ?: return null
                val startTag = parseStartTag(xml.substring(tagStart + 1, end)) ?: return null
                if (stack.isEmpty() && rootComplete) return null
                val namespaces = stack.lastOrNull()?.namespaces.orEmpty().toMutableMap()
                startTag.attributes.forEach { (name, value) ->
                    when {
                        name == "xmlns" -> namespaces[""] = value
                        name.startsWith("xmlns:") -> namespaces[name.substringAfter(':')] = value
                    }
                }
                val elementName = resolveQualifiedName(startTag.qualifiedName, namespaces, defaultApplies = true)
                    ?: return null
                val attributes = startTag.attributes.mapNotNull { (name, value) ->
                    if (name == "xmlns" || name.startsWith("xmlns:")) {
                        null
                    } else {
                        val resolved = resolveQualifiedName(name, namespaces, defaultApplies = false)
                            ?: return null
                        ParsedXmlAttribute(resolved.first, resolved.second, value)
                    }
                }
                elements += ParsedXmlElement(elementName.first, elementName.second, attributes)
                if (startTag.selfClosing) {
                    if (stack.isEmpty()) rootComplete = true
                } else {
                    stack += OpenXmlElement(startTag.qualifiedName, namespaces)
                }
                index = end + 1
            }
        }
    }
    return elements.takeIf { rootComplete && stack.isEmpty() && it.isNotEmpty() }
}

private fun String.findXmlTagEnd(start: Int): Int? {
    var quote: Char? = null
    for (index in start until length) {
        val character = this[index]
        if (quote != null) {
            if (character == quote) quote = null
        } else {
            when (character) {
                '\'', '"' -> quote = character
                '>' -> return index
                '<' -> return null
            }
        }
    }
    return null
}

private fun parseStartTag(source: String): ParsedStartTag? {
    var content = source.trim()
    val selfClosing = content.endsWith('/')
    if (selfClosing) content = content.dropLast(1).trimEnd()
    var index = 0
    val nameEnd = content.indexOfFirst { it.isWhitespace() }.let { if (it < 0) content.length else it }
    val qualifiedName = content.substring(0, nameEnd)
    if (!qualifiedName.isValidQualifiedXmlName()) return null
    index = nameEnd
    val attributes = linkedMapOf<String, String>()
    while (index < content.length) {
        while (index < content.length && content[index].isWhitespace()) index++
        if (index == content.length) break
        val attributeStart = index
        while (index < content.length && !content[index].isWhitespace() && content[index] != '=') index++
        val attributeName = content.substring(attributeStart, index)
        if (!attributeName.isValidQualifiedXmlName() || attributeName in attributes) return null
        while (index < content.length && content[index].isWhitespace()) index++
        if (index >= content.length || content[index] != '=') return null
        index++
        while (index < content.length && content[index].isWhitespace()) index++
        if (index >= content.length || content[index] !in setOf('\'', '"')) return null
        val quote = content[index++]
        val valueStart = index
        while (index < content.length && content[index] != quote) {
            if (content[index] == '<') return null
            index++
        }
        if (index >= content.length) return null
        val value = decodeXmlAttributeValue(content.substring(valueStart, index)) ?: return null
        attributes[attributeName] = value
        index++
    }
    return ParsedStartTag(qualifiedName, attributes, selfClosing)
}

private fun resolveQualifiedName(
    qualifiedName: String,
    namespaces: Map<String, String>,
    defaultApplies: Boolean,
): Pair<String?, String>? {
    val colon = qualifiedName.indexOf(':')
    if (colon < 0) {
        return (namespaces[""].takeIf { defaultApplies }) to qualifiedName
    }
    val prefix = qualifiedName.substring(0, colon)
    val localName = qualifiedName.substring(colon + 1)
    return (namespaces[prefix] ?: return null) to localName
}

private fun decodeXmlAttributeValue(value: String): String? {
    if ('&' !in value) return value
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] != '&') {
            result.append(value[index++])
            continue
        }
        val end = value.indexOf(';', index + 1)
        if (end < 0) return null
        val decoded = when (val entity = value.substring(index + 1, end)) {
            "amp" -> '&'
            "apos" -> '\''
            "gt" -> '>'
            "lt" -> '<'
            "quot" -> '"'
            else -> entity.decodeNumericXmlEntity() ?: return null
        }
        result.append(decoded)
        index = end + 1
    }
    return result.toString()
}

private fun String.decodeNumericXmlEntity(): Char? {
    if (!startsWith('#')) return null
    val codePoint = if (startsWith("#x", ignoreCase = true)) {
        substring(2).toIntOrNull(16)
    } else {
        substring(1).toIntOrNull()
    } ?: return null
    return codePoint.takeIf { it in 0..Char.MAX_VALUE.code }?.toChar()
}

private fun String.isValidQualifiedXmlName(): Boolean {
    if (isEmpty() || count { it == ':' } > 1) return false
    return split(':').all { part ->
        part.isNotEmpty() &&
            (part.first().isLetter() || part.first() == '_') &&
            part.drop(1).all { it.isLetterOrDigit() || it in setOf('_', '-', '.') }
    }
}

private fun String.normalizedMediaMimeType(): String? {
    val normalized = substringBefore(';').trim().lowercase()
    if (normalized.length !in 3..MAX_LIVE_PHOTO_MIME_LENGTH || normalized.count { it == '/' } != 1) return null
    val type = normalized.substringBefore('/')
    val subtype = normalized.substringAfter('/')
    if (type.isEmpty() || subtype.isEmpty()) return null
    if (!type.all(Char::isSafeMimeCharacter) || !subtype.all(Char::isSafeMimeCharacter)) return null
    return normalized
}

private fun Char.isSafeMimeCharacter(): Boolean =
    this in 'a'..'z' || this in '0'..'9' || this in "!#$&^_.+-"

private fun String.strictPositiveLong(): Long? {
    if (isEmpty() || any { it !in '0'..'9' }) return null
    return toLongOrNull()?.takeIf { it > 0L }
}

private fun String.strictNonNegativeLong(): Long? {
    if (isEmpty() || any { it !in '0'..'9' }) return null
    return toLongOrNull()
}

private fun String.isSafeMetadataToken(maximumLength: Int): Boolean =
    length in 1..maximumLength &&
        none { it.isISOControl() || it == '\u007f' || it.category == CharCategory.FORMAT }

private fun String.isSafeAppleContentIdentifier(): Boolean =
    length in 1..MAX_APPLE_CONTENT_IDENTIFIER_LENGTH &&
        this == trim() &&
        none { it.isISOControl() || it == '\u007f' || it.category == CharCategory.FORMAT }

private fun AppleLivePhotoComponent.hasSafeLivePhotoComponentIdentity(): Boolean =
    pairingScopeIdentity.isSafeMetadataToken(MAX_LIVE_PHOTO_RESOURCE_IDENTITY_LENGTH) &&
        resourceIdentity.isSafeMetadataToken(MAX_LIVE_PHOTO_RESOURCE_IDENTITY_LENGTH) &&
        displayName.isSafeMetadataToken(MAX_LIVE_PHOTO_DISPLAY_NAME_LENGTH)

private const val CAMERA_NAMESPACE = "http://ns.google.com/photos/1.0/camera/"
private const val CONTAINER_NAMESPACE = "http://ns.google.com/photos/1.0/container/"
private const val CONTAINER_ITEM_NAMESPACE = "http://ns.google.com/photos/1.0/container/item/"
private const val LEGACY_MOTION_PHOTO_PRIMARY_MIME_TYPE = "image/jpeg"
private const val APPLE_LIVE_PHOTO_VIDEO_MIME_TYPE = "video/quicktime"
private const val MAX_LIVE_PHOTO_XMP_BYTES = 256 * 1024
private const val MAX_LIVE_PHOTO_VIDEO_PROBE_BYTES = 4 * 1024
private const val MIN_LIVE_PHOTO_VIDEO_PROBE_BYTES = 12
private const val MAX_MOTION_PHOTO_CONTAINER_ITEMS = 32
private const val MAX_MOTION_PHOTO_SEMANTIC_LENGTH = 64
private const val MOTION_PHOTO_VIDEO_DATA_BOX_BYTES = 8L
private const val MAX_LIVE_PHOTO_MIME_LENGTH = 127
private const val MAX_APPLE_CONTENT_IDENTIFIER_LENGTH = 512
private const val MAX_LIVE_PHOTO_RESOURCE_IDENTITY_LENGTH = 2_048
private const val MAX_LIVE_PHOTO_DISPLAY_NAME_LENGTH = 512

private val MODERN_MOTION_PHOTO_PRIMARY_MIME_TYPES = setOf("image/jpeg", "image/heic", "image/avif")
private val BOXED_MOTION_PHOTO_PRIMARY_MIME_TYPES = setOf("image/heic", "image/avif")
private val MOTION_VIDEO_MIME_TYPES = setOf("video/mp4", "video/quicktime")
private val APPLE_LIVE_PHOTO_STILL_MIME_TYPES = setOf("image/jpeg", "image/heic", "image/heif")
