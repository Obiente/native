package dev.obiente.nextcloudnative.app

/**
 * Credential-free metadata for a file explicitly selected through a platform picker.
 *
 * [selectionId] is an opaque, platform-private capability. A platform may persist it for a
 * user-approved background transfer, but it is never a filesystem path or content URI, so shared
 * UI and protocol adapters cannot inspect files outside the platform service.
 */
data class LocalUploadFile(
    val selectionId: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
) {
    init {
        require(selectionId.length in 16..96 && selectionId.all { it.isLetterOrDigit() || it == '-' }) {
            "The local upload selection is invalid."
        }
        require(displayName == sanitizeUploadFilename(displayName)) {
            "The local upload filename is not safe."
        }
        require(mimeType == null || mimeType.isSafeMultipartMimeType()) {
            "The local upload MIME type is invalid."
        }
        require(sizeBytes == null || sizeBytes >= 0L) {
            "The local upload size is invalid."
        }
    }
}

sealed interface LocalUploadSelectionResult {
    data class Selected(val file: LocalUploadFile) : LocalUploadSelectionResult
    data object Cancelled : LocalUploadSelectionResult
    data class Rejected(val reason: String) : LocalUploadSelectionResult
    data class Unavailable(val reason: String) : LocalUploadSelectionResult
}

/**
 * Stable, credential-free owner for a durable multipart upload.
 *
 * The value is intentionally limited to opaque server resource identifiers. Platform stores add
 * an account digest before persisting it, so uploads from different accounts can never collide.
 */
data class DurableUploadScope(
    val feature: String,
    val resourceId: String,
) {
    init {
        require(feature.length in 1..32 && feature.all { it.isLetterOrDigit() || it == '-' }) {
            "The durable upload feature is invalid."
        }
        require(resourceId.length in 1..96 && resourceId.all { it.isLetterOrDigit() || it == '-' }) {
            "The durable upload resource is invalid."
        }
    }
}

enum class DurableUploadState {
    Queued,
    Uploading,
    Completed,
    Failed,
    OutcomeUnknown,
}

/** In-flight multipart writes are never replayed after process recovery. */
fun DurableUploadState.afterProcessRecovery(): DurableUploadState =
    if (this == DurableUploadState.Uploading) DurableUploadState.OutcomeUnknown else this

data class DurableUploadStatus(
    val id: String,
    val scope: DurableUploadScope,
    val displayName: String,
    val state: DurableUploadState,
    val message: String? = null,
) {
    init {
        require(id.length in 16..96 && id.all { it.isLetterOrDigit() || it == '-' }) {
            "The durable upload id is invalid."
        }
        require(displayName == sanitizeUploadFilename(displayName)) {
            "The durable upload filename is not safe."
        }
        require(message == null || message.length <= MAX_DURABLE_UPLOAD_MESSAGE_CHARACTERS) {
            "The durable upload message is too long."
        }
    }
}

sealed interface DurableUploadEnqueueResult {
    data class Queued(val status: DurableUploadStatus) : DurableUploadEnqueueResult
    data class Completed(val status: DurableUploadStatus) : DurableUploadEnqueueResult
    data class Rejected(val reason: String) : DurableUploadEnqueueResult
}

data class MultipartTextField(
    val name: String,
    val value: String,
) {
    init {
        require(name.isMultipartToken()) { "The multipart field name is invalid." }
        require('\u0000' !in value) { "Multipart text fields cannot contain null bytes." }
        require(value.encodeToByteArray().size <= MAX_MULTIPART_TEXT_FIELD_BYTES) {
            "The multipart text field is too large."
        }
    }
}

/**
 * Typed multipart payload carried by a same-origin dynamic API request.
 *
 * This retains the picker capability as structured data until the platform streaming executor
 * resolves it. It deliberately cannot contain raw bytes, a local path, a URI, or arbitrary
 * multipart headers.
 */
data class NextcloudMultipartBody(
    val file: LocalUploadFile,
    val fileFieldName: String,
    val textFields: List<MultipartTextField> = emptyList(),
    val maximumFileBytes: Long = DEFAULT_LOCAL_UPLOAD_LIMIT_BYTES,
) {
    fun requireSafe(): NextcloudMultipartBody {
        require(fileFieldName.isMultipartToken()) { "The multipart file field name is invalid." }
        require(textFields.size <= MAX_MULTIPART_TEXT_FIELDS) {
            "The multipart request has too many text fields."
        }
        require(textFields.map(MultipartTextField::name).distinct().size == textFields.size) {
            "Multipart text field names must be unique."
        }
        require(textFields.none { it.name == fileFieldName }) {
            "The multipart file field must be distinct from text fields."
        }
        require(maximumFileBytes in 1..MAX_LOCAL_UPLOAD_LIMIT_BYTES) {
            "The local upload limit is outside the allowed range."
        }
        require(file.sizeBytes == null || file.sizeBytes <= maximumFileBytes) {
            "The selected file is larger than the allowed upload limit."
        }
        return this
    }

    fun toUploadRequest(request: NextcloudApiRequest): NextcloudMultipartUploadRequest {
        val safeRequest = request.requireSafe()
        require(safeRequest.multipartBody === this || safeRequest.multipartBody == this) {
            "The multipart body does not belong to this request."
        }
        return NextcloudMultipartUploadRequest(
            method = safeRequest.method,
            relativePath = safeRequest.relativePath,
            file = file,
            queryParameters = safeRequest.queryParameters,
            fileFieldName = fileFieldName,
            textFields = textFields,
            ocsApiRequest = safeRequest.ocsApiRequest,
            maximumFileBytes = maximumFileBytes,
            maximumResponseBytes = safeRequest.maximumResponseBytes,
        ).requireSafe()
    }
}

/**
 * Dedicated request for one user-selected file and a bounded set of text fields.
 *
 * The platform service resolves [file.selectionId] to the exact picker result and streams it.
 * Protocol adapters cannot provide a path, URI, arbitrary header, authentication value, or origin.
 */
data class NextcloudMultipartUploadRequest(
    val method: NextcloudApiMethod,
    val relativePath: String,
    val file: LocalUploadFile,
    val queryParameters: Map<String, String> = emptyMap(),
    val fileFieldName: String = "file",
    val textFields: List<MultipartTextField> = emptyList(),
    val ocsApiRequest: Boolean = false,
    val maximumFileBytes: Long = DEFAULT_LOCAL_UPLOAD_LIMIT_BYTES,
    val maximumResponseBytes: Long = DEFAULT_DYNAMIC_API_RESPONSE_LIMIT_BYTES,
) {
    fun requireSafe(): NextcloudMultipartUploadRequest {
        require(method in MULTIPART_UPLOAD_METHODS) {
            "Multipart uploads require POST, PUT, or PATCH."
        }
        require(fileFieldName.isMultipartToken()) { "The multipart file field name is invalid." }
        require(textFields.size <= MAX_MULTIPART_TEXT_FIELDS) {
            "The multipart request has too many text fields."
        }
        require(textFields.map(MultipartTextField::name).distinct().size == textFields.size) {
            "Multipart text field names must be unique."
        }
        require(textFields.none { it.name == fileFieldName }) {
            "The multipart file field must be distinct from text fields."
        }
        require(maximumFileBytes in 1..MAX_LOCAL_UPLOAD_LIMIT_BYTES) {
            "The local upload limit is outside the allowed range."
        }
        require(file.sizeBytes == null || file.sizeBytes <= maximumFileBytes) {
            "The selected file is larger than the allowed upload limit."
        }
        NextcloudApiRequest(
            method = method,
            relativePath = relativePath,
            queryParameters = queryParameters,
            ocsApiRequest = ocsApiRequest,
            maximumResponseBytes = maximumResponseBytes,
        ).requireSafe()
        return this
    }
}

@ConsistentCopyVisibility
data class PreparedMultipartUpload internal constructor(
    val contentType: String,
    val prefix: ByteArray,
    val suffix: ByteArray,
    val declaredFileBytes: Long?,
    val maximumFileBytes: Long,
) {
    val contentLength: Long?
        get() = declaredFileBytes?.let { fileBytes ->
            prefix.size.toLong().safeAdd(fileBytes).safeAdd(suffix.size.toLong())
        }
}

fun localUploadFile(
    selectionId: String,
    displayName: String,
    mimeType: String?,
    sizeBytes: Long?,
): LocalUploadFile = LocalUploadFile(
    selectionId = selectionId,
    displayName = sanitizeUploadFilename(displayName),
    mimeType = mimeType?.trim()?.lowercase()?.takeIf(String::isNotBlank),
    sizeBytes = sizeBytes,
)

/**
 * Encodes picker metadata for a generated form without ever exposing the platform path or URI.
 *
 * The length-prefixed representation is intentionally private to this runtime and strictly
 * decoded. Manually entered paths, content URIs, malformed values, and trailing data cannot become
 * upload capabilities.
 */
internal fun encodeDynamicLocalUploadSelection(file: LocalUploadFile): String = buildString {
    append(DYNAMIC_UPLOAD_SELECTION_PREFIX)
    listOf(
        file.selectionId,
        file.displayName,
        file.mimeType.orEmpty(),
        file.sizeBytes?.toString().orEmpty(),
    ).forEach { value ->
        append(value.length)
        append(':')
        append(value)
    }
}.also { encoded ->
    require(encoded.length <= MAX_DYNAMIC_UPLOAD_SELECTION_CHARACTERS) {
        "The local upload selection metadata is too large."
    }
}

internal fun decodeDynamicLocalUploadSelection(value: String): LocalUploadFile {
    require(value.length <= MAX_DYNAMIC_UPLOAD_SELECTION_CHARACTERS) {
        "The local upload selection metadata is too large."
    }
    require(value.startsWith(DYNAMIC_UPLOAD_SELECTION_PREFIX)) {
        "Choose a file with the native file picker."
    }
    var offset = DYNAMIC_UPLOAD_SELECTION_PREFIX.length
    fun component(): String {
        val delimiter = value.indexOf(':', offset)
        require(delimiter in (offset + 1)..minOf(offset + 4, value.lastIndex)) {
            "The local upload selection metadata is invalid."
        }
        val lengthText = value.substring(offset, delimiter)
        require(lengthText.all(Char::isDigit) && (lengthText == "0" || !lengthText.startsWith('0'))) {
            "The local upload selection metadata is invalid."
        }
        val length = lengthText.toIntOrNull()
            ?: throw IllegalArgumentException("The local upload selection metadata is invalid.")
        val start = delimiter + 1
        val end = start + length
        require(end >= start && end <= value.length) {
            "The local upload selection metadata is invalid."
        }
        offset = end
        return value.substring(start, end)
    }
    val selectionId = component()
    val displayName = component()
    val mimeType = component().takeIf(String::isNotEmpty)
    val sizeText = component()
    val size = sizeText.takeIf(String::isNotEmpty)?.toLongOrNull()
    require(sizeText.isEmpty() || size != null) {
        "The local upload selection metadata is invalid."
    }
    require(offset == value.length) { "The local upload selection metadata is invalid." }
    return LocalUploadFile(selectionId, displayName, mimeType, size)
}

fun requireSafeUploadPickerRequest(
    acceptedMimeTypes: List<String>,
    maximumBytes: Long,
): List<String> {
    require(maximumBytes in 1..MAX_LOCAL_UPLOAD_LIMIT_BYTES) {
        "The local upload selection limit is outside the allowed range."
    }
    val normalized = acceptedMimeTypes.map(String::trim).map(String::lowercase).distinct()
    require(normalized.isNotEmpty() && normalized.size <= MAX_ACCEPTED_UPLOAD_MIME_TYPES) {
        "The local upload MIME filter is invalid."
    }
    require(normalized.all(String::isSafeUploadMimeFilter)) {
        "The local upload MIME filter is invalid."
    }
    return normalized
}

fun isAcceptedUploadMimeType(
    mimeType: String?,
    acceptedMimeTypes: List<String>,
): Boolean {
    val accepted = requireSafeUploadPickerRequest(acceptedMimeTypes, DEFAULT_LOCAL_UPLOAD_LIMIT_BYTES)
    val actual = mimeType?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return true
    if (!actual.isSafeMultipartMimeType()) return false
    val actualType = actual.substringBefore('/')
    return accepted.any { filter ->
        filter == "*/*" || filter == actual || filter == "$actualType/*"
    }
}

fun sanitizeUploadFilename(value: String): String {
    val leaf = value.replace('\\', '/').substringAfterLast('/').trim()
    val printable = buildString {
        leaf.forEach { character ->
            if (!character.isISOControl()) append(character)
        }
    }.trim().trimEnd('.')
    val truncated = printable.ifBlank { DEFAULT_UPLOAD_FILENAME }.take(MAX_UPLOAD_FILENAME_CHARACTERS)
    return truncated.dropLastWhile { it.code in 0xd800..0xdbff }.ifBlank { DEFAULT_UPLOAD_FILENAME }
}

fun prepareMultipartUpload(
    request: NextcloudMultipartUploadRequest,
    boundary: String,
): PreparedMultipartUpload {
    val safeRequest = request.requireSafe()
    require(boundary.length in 16..70 && boundary.all(Char::isMultipartBoundaryCharacter)) {
        "The multipart boundary is invalid."
    }
    val prefix = buildString {
        safeRequest.textFields.forEach { field ->
            append("--")
            append(boundary)
            append(CRLF)
            append("Content-Disposition: form-data; name=\"")
            append(field.name)
            append('"')
            append(CRLF)
            append("Content-Type: text/plain; charset=utf-8")
            append(CRLF)
            append(CRLF)
            append(field.value)
            append(CRLF)
        }
        append("--")
        append(boundary)
        append(CRLF)
        append("Content-Disposition: form-data; name=\"")
        append(safeRequest.fileFieldName)
        append("\"; filename=\"")
        append(safeRequest.file.displayName.asciiFilenameFallback())
        append("\"; filename*=UTF-8''")
        append(safeRequest.file.displayName.encodeRfc5987())
        append(CRLF)
        append("Content-Type: ")
        append(safeRequest.file.mimeType ?: DEFAULT_UPLOAD_MIME_TYPE)
        append(CRLF)
        append(CRLF)
    }.encodeToByteArray()
    val suffix = "$CRLF--$boundary--$CRLF".encodeToByteArray()
    return PreparedMultipartUpload(
        contentType = "multipart/form-data; boundary=$boundary",
        prefix = prefix,
        suffix = suffix,
        declaredFileBytes = safeRequest.file.sizeBytes,
        maximumFileBytes = safeRequest.maximumFileBytes,
    )
}

/**
 * Streams a prepared body without buffering the selected file.
 *
 * The source returns the same values as an InputStream read: a positive count or -1 at EOF.
 * A changed file, a dishonest metadata size, or an unknown stream crossing the byte limit aborts
 * before the multipart terminator is written.
 */
fun writePreparedMultipartUpload(
    upload: PreparedMultipartUpload,
    readFile: (ByteArray) -> Int,
    write: (ByteArray, Int, Int) -> Unit,
) {
    write(upload.prefix, 0, upload.prefix.size)
    val buffer = ByteArray(MULTIPART_STREAM_BUFFER_BYTES)
    var fileBytes = 0L
    while (true) {
        val read = readFile(buffer)
        if (read != -1 && read !in 1..buffer.size) {
            throw LocalUploadSourceValidationException(
                "The local upload stream returned an invalid read count.",
            )
        }
        if (read == -1) break
        fileBytes = fileBytes.safeAdd(read.toLong())
        if (fileBytes > upload.maximumFileBytes) {
            throw LocalUploadSourceValidationException(
                "The selected file exceeded the allowed upload limit while reading.",
            )
        }
        upload.declaredFileBytes?.let { declared ->
            if (fileBytes > declared) {
                throw LocalUploadSourceValidationException(
                    "The selected file changed after it was chosen.",
                )
            }
        }
        write(buffer, 0, read)
    }
    if (upload.declaredFileBytes != null && fileBytes != upload.declaredFileBytes) {
        throw LocalUploadSourceValidationException(
            "The selected file changed after it was chosen.",
        )
    }
    write(upload.suffix, 0, upload.suffix.size)
}

class LocalUploadSourceValidationException(message: String) : IllegalArgumentException(message)

private fun String.isSafeMultipartMimeType(): Boolean {
    if (length !in 3..160 || count { it == '/' } != 1) return false
    val type = substringBefore('/')
    val subtype = substringAfter('/')
    return type.isNotBlank() && subtype.isNotBlank() &&
        type.all(Char::isSafeMimeTokenCharacter) &&
        subtype.all(Char::isSafeMimeTokenCharacter)
}

private fun String.isSafeUploadMimeFilter(): Boolean {
    if (this == "*/*") return true
    if (length !in 3..160 || any { it.isWhitespace() || it.isISOControl() || it == ';' }) return false
    if (count { it == '/' } != 1) return false
    val type = substringBefore('/')
    val subtype = substringAfter('/')
    if (type.isBlank() || subtype.isBlank() || type == "*") return false
    return type.all(Char::isSafeMimeTokenCharacter) &&
        (subtype == "*" || subtype.all(Char::isSafeMimeTokenCharacter))
}

private fun Char.isSafeMimeTokenCharacter(): Boolean =
    isLetterOrDigit() || this in MIME_TOKEN_PUNCTUATION

private fun String.isMultipartToken(): Boolean =
    length in 1..64 && all { character ->
        character.isLetterOrDigit() || character in MULTIPART_TOKEN_PUNCTUATION
    }

private fun Char.isMultipartBoundaryCharacter(): Boolean =
    isLetterOrDigit() || this == '-' || this == '_'

private fun String.asciiFilenameFallback(): String = buildString {
    this@asciiFilenameFallback.forEach { character ->
        append(
            when {
                character.code in 0x21..0x7e && character != '"' && character != '\\' -> character
                character == ' ' -> character
                else -> '_'
            },
        )
    }
}.ifBlank { DEFAULT_UPLOAD_FILENAME }

private fun String.encodeRfc5987(): String = buildString {
    for (byte in encodeToByteArray()) {
        val value = byte.toInt() and 0xff
        val allowed = value in 'a'.code..'z'.code ||
            value in 'A'.code..'Z'.code ||
            value in '0'.code..'9'.code ||
            value == '-'.code || value == '.'.code || value == '_'.code || value == '~'.code
        if (allowed) {
            append(value.toChar())
        } else {
            append('%')
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
}

private fun Long.safeAdd(other: Long): Long {
    require(other >= 0L && this <= Long.MAX_VALUE - other) { "The multipart body size overflowed." }
    return this + other
}

/**
 * Streaming uploads are limited only by the signed byte count used by Kotlin and the platform.
 * This is a representation boundary, not a product file-size policy.
 */
const val DEFAULT_LOCAL_UPLOAD_LIMIT_BYTES = Long.MAX_VALUE
const val MAX_DURABLE_UPLOAD_MESSAGE_CHARACTERS = 240
const val MAX_LOCAL_UPLOAD_LIMIT_BYTES = Long.MAX_VALUE

private val MULTIPART_UPLOAD_METHODS = setOf(
    NextcloudApiMethod.POST,
    NextcloudApiMethod.PUT,
    NextcloudApiMethod.PATCH,
)
private val MULTIPART_TOKEN_PUNCTUATION = setOf('!', '#', '$', '%', '&', '\'', '+', '-', '.', '^', '_', '`', '|', '~')
private val MIME_TOKEN_PUNCTUATION = setOf('!', '#', '$', '&', '+', '-', '.', '^', '_')
private const val MAX_ACCEPTED_UPLOAD_MIME_TYPES = 16
private const val MAX_MULTIPART_TEXT_FIELDS = 16
private const val MAX_MULTIPART_TEXT_FIELD_BYTES = 16 * 1024
private const val MAX_DYNAMIC_UPLOAD_SELECTION_CHARACTERS = 512
private const val MAX_UPLOAD_FILENAME_CHARACTERS = 180
private const val MULTIPART_STREAM_BUFFER_BYTES = 32 * 1024
private const val DEFAULT_UPLOAD_FILENAME = "upload.bin"
private const val DEFAULT_UPLOAD_MIME_TYPE = "application/octet-stream"
private const val DYNAMIC_UPLOAD_SELECTION_PREFIX = "ncn-upload-v1:"
private const val CRLF = "\r\n"
private const val HEX_DIGITS = "0123456789ABCDEF"
