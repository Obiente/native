package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

/*
 * Typed same-origin requests for the Files-adjacent Nextcloud DAV collections: the trash bin,
 * file comments, and system tag relations. Each request is validated against an allowlisted
 * DAV root before it reaches the account's no-redirect DAV transport, so feature code cannot
 * address the Files tree, another user's trash bin, or an arbitrary server path through it.
 *
 * Official protocol references:
 * https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/trashbin.html
 * https://github.com/nextcloud/server/tree/master/apps/dav/lib/Comments
 * https://github.com/nextcloud/server/tree/master/apps/dav/lib/SystemTag
 */

enum class FilesDavMethod { PROPFIND, REPORT, PROPPATCH, POST, PUT, MOVE, DELETE }

/** DAV roots reachable through [FilesDavRequest]. Paths below a root are validated per feature. */
enum class FilesDavRoot(val segment: String) {
    Trashbin("trashbin"),
    Comments("comments"),
    SystemTagRelations("systemtags-relations"),
    SystemTags("systemtags"),
}

data class FilesDavRequest(
    val method: FilesDavMethod,
    val relativePath: String,
    val destinationRelativePath: String? = null,
    val depth: Int? = null,
    val contentType: String? = null,
    val body: ByteArray? = null,
    val overwrite: Boolean? = null,
    val maximumResponseBytes: Long = DEFAULT_FILES_DAV_RESPONSE_BYTES,
) {
    val root: FilesDavRoot = requireNotNull(relativePath.filesDavRoot()) {
        "The DAV path is outside the reviewed Files collections."
    }

    init {
        require(relativePath.isSafeFilesDavPath()) { "The DAV path is unsafe." }
        require(destinationRelativePath == null || destinationRelativePath.isSafeFilesDavPath()) {
            "The DAV destination is unsafe."
        }
        require((destinationRelativePath != null) == (method == FilesDavMethod.MOVE)) {
            "Only MOVE carries a destination."
        }
        require(destinationRelativePath == null || destinationRelativePath.filesDavRoot() == root) {
            "A DAV move cannot leave its collection root."
        }
        require(overwrite == null || method == FilesDavMethod.MOVE) { "Only MOVE carries an overwrite flag." }
        require(depth == null || (method in DEPTH_METHODS && depth in 0..1)) { "The DAV depth is invalid." }
        require(body == null || method in BODY_METHODS) { "This DAV method does not carry a body." }
        require((body == null) == (contentType == null)) { "A DAV body needs exactly one content type." }
        require(body == null || body.size <= MAX_FILES_DAV_REQUEST_BYTES) { "The DAV request body is too large." }
        require(maximumResponseBytes in 1..MAX_FILES_DAV_RESPONSE_BYTES) { "The DAV response bound is invalid." }
    }

    override fun equals(other: Any?): Boolean = other is FilesDavRequest &&
        method == other.method && relativePath == other.relativePath &&
        destinationRelativePath == other.destinationRelativePath && depth == other.depth &&
        contentType == other.contentType && overwrite == other.overwrite &&
        maximumResponseBytes == other.maximumResponseBytes &&
        (body?.contentEquals(other.body) ?: (other.body == null))

    override fun hashCode(): Int = listOf(method, relativePath, destinationRelativePath, depth, contentType)
        .hashCode() * 31 + (body?.contentHashCode() ?: 0)

    /** Maps onto the account DAV transport; the destination is rebuilt against the account origin. */
    internal fun toAccountDavRequest(serverUrl: String): GroupwareDavRequest = GroupwareDavRequest(
        method = method.name,
        relativePath = relativePath,
        depth = depth,
        contentType = contentType,
        body = body,
        headers = buildMap {
            put("Accept", if (method == FilesDavMethod.POST) "application/json, */*" else "application/xml, */*")
            destinationRelativePath?.let { put("Destination", serverUrl.trimEnd('/') + it) }
            overwrite?.let { put("Overwrite", if (it) "T" else "F") }
        },
        maximumResponseBytes = maximumResponseBytes,
    )
}

/** Sends a validated Files-adjacent DAV request through the account's no-redirect DAV transport. */
suspend fun NextcloudPlatformServices.executeFilesDav(
    session: NextcloudSession,
    request: FilesDavRequest,
): NextcloudApiResponse = executeGroupwareDav(session, request.toAccountDavRequest(session.serverUrl))

/** Typed failure classes for Files-adjacent DAV reads and writes. UI copy derives from these. */
enum class FilesDavFailureKind {
    Offline,
    Unauthorized,
    Forbidden,
    NotFound,
    Conflict,
    PreconditionFailed,
    InsufficientStorage,
    Throttled,
    Unavailable,
    Server,
    Malformed,
    Oversized,
}

fun filesDavFailureForStatus(status: Int): FilesDavFailureKind? = when (status) {
    in 200..299 -> null
    401 -> FilesDavFailureKind.Unauthorized
    403 -> FilesDavFailureKind.Forbidden
    404, 410 -> FilesDavFailureKind.NotFound
    405, 501 -> FilesDavFailureKind.Unavailable
    409 -> FilesDavFailureKind.Conflict
    412 -> FilesDavFailureKind.PreconditionFailed
    423 -> FilesDavFailureKind.Conflict
    429 -> FilesDavFailureKind.Throttled
    503 -> FilesDavFailureKind.Unavailable
    507 -> FilesDavFailureKind.InsufficientStorage
    else -> FilesDavFailureKind.Server
}

/** Result of one DAV write. [Unknown] means the request may have taken effect. */
sealed interface FilesDavWriteOutcome {
    data class Confirmed(val response: NextcloudApiResponse) : FilesDavWriteOutcome
    data class Rejected(val kind: FilesDavFailureKind, val status: Int) : FilesDavWriteOutcome
    data object Unknown : FilesDavWriteOutcome
}

/**
 * Sends one write and classifies it without retrying. A transport exception or a gateway/server
 * failure after the request may have arrived is [FilesDavWriteOutcome.Unknown]; the caller must
 * verify the postcondition by reading before offering the action again.
 */
suspend fun sendFilesDavWrite(
    execute: suspend (FilesDavRequest) -> NextcloudApiResponse,
    request: FilesDavRequest,
): FilesDavWriteOutcome = try {
    val response = execute(request)
    when {
        response.status in 200..299 -> FilesDavWriteOutcome.Confirmed(response)
        response.status >= 500 && response.status != 501 && response.status != 507 -> FilesDavWriteOutcome.Unknown
        else -> FilesDavWriteOutcome.Rejected(
            filesDavFailureForStatus(response.status) ?: FilesDavFailureKind.Server,
            response.status,
        )
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    // Transport boundary: the request may or may not have reached the server.
    FilesDavWriteOutcome.Unknown
}

/** One `d:response` of a multistatus body with the properties of its successful propstat. */
internal data class FilesDavResponseRecord(
    val href: String,
    /** Inner XML keyed by lowercase local property name, taken only from 2xx propstat blocks. */
    val properties: Map<String, String>,
) {
    fun text(localName: String): String? = properties[localName.lowercase()]
        ?.decodeXmlEntities()
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    fun has(localName: String): Boolean = properties.containsKey(localName.lowercase())
}

class FilesDavProtocolException(message: String) : IllegalStateException(message)

/** Parses a bounded multistatus body. Malformed or oversized input fails closed. */
internal fun parseFilesDavMultistatus(response: NextcloudApiResponse, maximumRecords: Int): List<FilesDavResponseRecord> {
    if (response.status != 207) throw FilesDavProtocolException("The server did not return a DAV multistatus.")
    val body = response.body.decodeToString()
    if (!body.containsXmlElement("multistatus")) throw FilesDavProtocolException("The DAV response is malformed.")
    val responses = body.xmlElements("response")
    if (responses.size > maximumRecords + 1) throw FilesDavProtocolException("The DAV response has too many entries.")
    return responses.map { element ->
        val href = element.xmlText("href")?.decodeXmlEntities()?.trim()
            ?: throw FilesDavProtocolException("A DAV response entry has no address.")
        val properties = linkedMapOf<String, String>()
        element.xmlElements("propstat").forEach { propstat ->
            val status = propstat.xmlText("status")?.let(::parseDavStatusCode)
            if (status != null && status in 200..299) {
                val prop = propstat.xmlElements("prop").firstOrNull() ?: return@forEach
                prop.directChildElements().forEach { (name, inner) -> properties.putIfAbsent(name, inner) }
            }
        }
        FilesDavResponseRecord(href, properties)
    }
}

/** Direct children of an element as lowercase local name to inner XML; self-closing children map to "". */
private fun String.directChildElements(): List<Pair<String, String>> {
    val openEnd = indexOf('>').takeIf { it >= 0 } ?: return emptyList()
    val closeStart = lastIndexOf("</").takeIf { it > openEnd } ?: return emptyList()
    val inner = substring(openEnd + 1, closeStart)
    val children = mutableListOf<Pair<String, String>>()
    var cursor = 0
    while (cursor < inner.length) {
        val opening = inner.indexOf('<', cursor)
        if (opening < 0) break
        if (inner.getOrNull(opening + 1) in listOf('/', '!', '?')) {
            cursor = inner.indexOf('>', opening).takeIf { it >= 0 }?.plus(1) ?: break
            continue
        }
        val nameEnd = inner.indexOfAny(charArrayOf(' ', '\t', '\r', '\n', '>', '/'), opening + 1)
        if (nameEnd < 0) break
        val qualifiedName = inner.substring(opening + 1, nameEnd)
        val tagEnd = inner.indexOf('>', nameEnd)
        if (tagEnd < 0) break
        val localName = qualifiedName.substringAfter(':').lowercase()
        if (inner[tagEnd - 1] == '/') {
            children += localName to ""
            cursor = tagEnd + 1
            continue
        }
        val closing = inner.findMatchingClose(qualifiedName, tagEnd + 1) ?: break
        children += localName to inner.substring(tagEnd + 1, closing)
        cursor = inner.indexOf('>', closing).takeIf { it >= 0 }?.plus(1) ?: break
    }
    return children
}

/** Index of the closing tag that balances an opening [qualifiedName] tag, accounting for nesting. */
private fun String.findMatchingClose(qualifiedName: String, from: Int): Int? {
    var depth = 1
    var cursor = from
    while (cursor < length) {
        val next = indexOf('<', cursor)
        if (next < 0) return null
        val isClose = getOrNull(next + 1) == '/'
        val nameStart = if (isClose) next + 2 else next + 1
        val nameEnd = indexOfAny(charArrayOf(' ', '\t', '\r', '\n', '>', '/'), nameStart)
        if (nameEnd < 0) return null
        val tagEnd = indexOf('>', nameEnd).takeIf { it >= 0 } ?: return null
        if (substring(nameStart, nameEnd) == qualifiedName) {
            if (isClose) {
                depth--
                if (depth == 0) return next
            } else if (this[tagEnd - 1] != '/') {
                depth++
            }
        }
        cursor = tagEnd + 1
    }
    return null
}

/** Encodes one DAV path segment. */
internal fun encodeFilesDavSegment(value: String): String = encodeUrlComponent(value)

/** Whether [value] is a single already-encoded DAV segment that cannot change the addressed path. */
internal fun isSafeEncodedFilesDavSegment(value: String): Boolean {
    if (value.isEmpty() || value.length > MAX_FILES_DAV_SEGMENT_LENGTH || value == "." || value == "..") return false
    val normalized = value.lowercase()
    if (listOf("%2f", "%5c", "%00", "%2e%2e").any(normalized::contains)) return false
    var index = 0
    while (index < value.length) {
        val character = value[index]
        when {
            character == '%' -> {
                if (index + 2 >= value.length || !value[index + 1].isHex() || !value[index + 2].isHex()) return false
                index += 3
                continue
            }
            character.isLetterOrDigit() && character.code < 128 -> Unit
            character in "-._~!$&'()*+,;=:@" -> Unit
            else -> return false
        }
        index++
    }
    return true
}

/** Path segments of an absolute DAV href below `/remote.php/dav/`, or null when it is elsewhere. */
internal fun filesDavHrefSegments(href: String): List<String>? {
    val path = if ("://" in href) "/" + href.substringAfter("://").substringAfter('/', "") else href
    val marker = path.indexOf(FILES_DAV_ROOT)
    if (marker < 0) return null
    return path.substring(marker + FILES_DAV_ROOT.length).trimEnd('/').split('/').filter(String::isNotEmpty)
}

private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun String.filesDavRoot(): FilesDavRoot? {
    if (!startsWith(FILES_DAV_ROOT)) return null
    val first = removePrefix(FILES_DAV_ROOT).substringBefore('/')
    return FilesDavRoot.entries.firstOrNull { it.segment == first }
}

private fun String.isSafeFilesDavPath(): Boolean {
    if (!startsWith(FILES_DAV_ROOT) || length > MAX_FILES_DAV_PATH_LENGTH) return false
    if (any { it.isISOControl() || it.isWhitespace() || it == '\\' || it == '#' || it == '?' }) return false
    val segments = removePrefix(FILES_DAV_ROOT).trimEnd('/').split('/')
    return segments.isNotEmpty() && segments.all(::isSafeEncodedFilesDavSegment)
}

internal const val FILES_DAV_ROOT = "/remote.php/dav/"
internal const val DEFAULT_FILES_DAV_RESPONSE_BYTES = 2L * 1024L * 1024L
private const val MAX_FILES_DAV_RESPONSE_BYTES = 8L * 1024L * 1024L
private const val MAX_FILES_DAV_REQUEST_BYTES = 64 * 1024
private const val MAX_FILES_DAV_PATH_LENGTH = 4_096
private const val MAX_FILES_DAV_SEGMENT_LENGTH = 1_024
private val DEPTH_METHODS = setOf(FilesDavMethod.PROPFIND, FilesDavMethod.REPORT)
private val BODY_METHODS = setOf(FilesDavMethod.PROPFIND, FilesDavMethod.REPORT, FilesDavMethod.PROPPATCH, FilesDavMethod.POST, FilesDavMethod.PUT)
