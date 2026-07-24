package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Conflict-safe WebDAV mutations. All source versions are mandatory by design. */
sealed interface NextcloudFileMutation {
    val sourcePath: String
    val expectedEtag: String
    val sourceIsDirectory: Boolean

    data class Rename(
        override val sourcePath: String,
        val newName: String,
        override val expectedEtag: String,
        override val sourceIsDirectory: Boolean = false,
    ) : NextcloudFileMutation

    data class Move(
        override val sourcePath: String,
        val destinationDirectoryPath: String,
        val destinationName: String? = null,
        override val expectedEtag: String,
        override val sourceIsDirectory: Boolean = false,
    ) : NextcloudFileMutation

    data class Copy(
        override val sourcePath: String,
        val destinationDirectoryPath: String,
        val destinationName: String? = null,
        override val expectedEtag: String,
        override val sourceIsDirectory: Boolean = false,
    ) : NextcloudFileMutation

    data class Delete(
        override val sourcePath: String,
        override val expectedEtag: String,
        override val sourceIsDirectory: Boolean = false,
    ) : NextcloudFileMutation
}

data class FileWebDavMutationSpec(
    val method: String,
    val sourcePath: String,
    val destinationPath: String?,
    val expectedEtag: String,
    val sourceIsDirectory: Boolean,
    val overwrite: Boolean,
)

/**
 * Nextcloud evaluates collection entity tags through WebDAV's `If` header. Ordinary resources use
 * HTTP `If-Match`. Keeping this distinction in the common planner prevents Android and desktop
 * transports from silently diverging.
 */
fun FileWebDavMutationSpec.conflictConditionHeaders(): Map<String, String> =
    if (sourceIsDirectory) mapOf("If" to "($expectedEtag)")
    else mapOf("If-Match" to expectedEtag)

data class NextcloudFileMutationResult(
    val destinationPath: String?,
    val etag: String?,
)

enum class NextcloudFileOperationError {
    AuthenticationRequired,
    PermissionDenied,
    NotFound,
    Conflict,
    Locked,
    InsufficientStorage,
    ServerFailure,
}

class NextcloudFileOperationException(
    val error: NextcloudFileOperationError,
    val status: Int,
    message: String,
) : Exception(message)

fun NextcloudFileMutation.toWebDavMutationSpec(): FileWebDavMutationSpec {
    val safeSource = requireSafeFilePath(sourcePath, allowRoot = false)
    val safeEtag = expectedEtag.trim().also {
        require(it.isNotEmpty()) { "A current ETag is required for file changes." }
        require('\r' !in it && '\n' !in it) { "The file ETag is invalid." }
    }
    val destination = when (this) {
        is NextcloudFileMutation.Rename -> {
            val parent = safeSource.substringBeforeLast('/', missingDelimiterValue = "")
            joinFilePath(parent, requireSafeFileName(newName))
        }
        is NextcloudFileMutation.Move -> destinationPath(safeSource, destinationDirectoryPath, destinationName)
        is NextcloudFileMutation.Copy -> destinationPath(safeSource, destinationDirectoryPath, destinationName)
        is NextcloudFileMutation.Delete -> null
    }
    if (destination != null) {
        require(destination != safeSource) { "The destination must be different from the source." }
        require(!destination.startsWith("$safeSource/")) {
            "A folder cannot be moved or copied inside itself."
        }
    }
    return FileWebDavMutationSpec(
        method = when (this) {
            is NextcloudFileMutation.Rename, is NextcloudFileMutation.Move -> "MOVE"
            is NextcloudFileMutation.Copy -> "COPY"
            is NextcloudFileMutation.Delete -> "DELETE"
        },
        sourcePath = safeSource,
        destinationPath = destination,
        expectedEtag = safeEtag,
        sourceIsDirectory = sourceIsDirectory,
        overwrite = false,
    )
}

fun fileOperationException(status: Int): NextcloudFileOperationException {
    val error = when (status) {
        401 -> NextcloudFileOperationError.AuthenticationRequired
        403 -> NextcloudFileOperationError.PermissionDenied
        404 -> NextcloudFileOperationError.NotFound
        405, 409, 412 -> NextcloudFileOperationError.Conflict
        423 -> NextcloudFileOperationError.Locked
        507 -> NextcloudFileOperationError.InsufficientStorage
        else -> NextcloudFileOperationError.ServerFailure
    }
    val message = when (error) {
        NextcloudFileOperationError.AuthenticationRequired -> "Sign in again before changing this file."
        NextcloudFileOperationError.PermissionDenied -> "You do not have permission to change this file."
        NextcloudFileOperationError.NotFound -> "The file no longer exists on the server."
        NextcloudFileOperationError.Conflict -> "The file or destination changed. Refresh and try again."
        NextcloudFileOperationError.Locked -> "The file is locked by another operation."
        NextcloudFileOperationError.InsufficientStorage -> "The server does not have enough free storage."
        NextcloudFileOperationError.ServerFailure -> "The file operation failed (HTTP $status)."
    }
    return NextcloudFileOperationException(error, status, message)
}

enum class FileShareTarget(val wireValue: Int) {
    User(0),
    Group(1),
    PublicLink(3),
}

data class FileSharePermissions(
    val read: Boolean = true,
    val update: Boolean = false,
    val create: Boolean = false,
    val delete: Boolean = false,
    val reshare: Boolean = false,
) {
    val mask: Int
        get() = (if (read) 1 else 0) or
            (if (update) 2 else 0) or
            (if (create) 4 else 0) or
            (if (delete) 8 else 0) or
            (if (reshare) 16 else 0)
}

data class CreateFileShareRequest(
    val path: String,
    val target: FileShareTarget,
    val shareWith: String? = null,
    val permissions: FileSharePermissions = FileSharePermissions(),
)

data class NextcloudFileShare(
    val id: String,
    val url: String?,
    val token: String?,
    val shareType: Int?,
    val shareWith: String? = null,
    val displayName: String? = null,
    val permissions: Int? = null,
)

fun CreateFileShareRequest.toNextcloudApiRequest(): NextcloudApiRequest {
    val safePath = requireSafeFilePath(path, allowRoot = false)
    val recipient = shareWith?.trim()?.takeIf(String::isNotEmpty)
    require(safePath.encodeToByteArray().size <= MAX_FILE_SHARE_PATH_BYTES) {
        "The shared file path is too long."
    }
    when (target) {
        FileShareTarget.PublicLink -> require(recipient == null) { "Public links cannot name a recipient." }
        FileShareTarget.User, FileShareTarget.Group -> require(recipient != null) { "A share recipient is required." }
    }
    require(recipient == null || recipient.length <= MAX_FILE_SHARE_RECIPIENT_LENGTH &&
        recipient.none(Char::isISOControl)
    ) { "The share recipient is invalid or too long." }
    require(permissions.mask != 0) { "At least one share permission is required." }
    val fields = buildList {
        add("path" to "/$safePath")
        add("shareType" to target.wireValue.toString())
        add("permissions" to permissions.mask.toString())
        recipient?.let { add("shareWith" to it) }
    }
    val body = fields.joinToString("&") { (name, value) ->
        "${encodeFormComponent(name)}=${encodeFormComponent(value)}"
    }.encodeToByteArray()
    require(body.size <= MAX_FILE_SHARE_REQUEST_BYTES) { "The share request is too large." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.POST,
        relativePath = "/ocs/v2.php/apps/files_sharing/api/v1/shares",
        queryParameters = mapOf("format" to "json"),
        contentType = "application/x-www-form-urlencoded; charset=utf-8",
        body = body,
        ocsApiRequest = true,
        maximumResponseBytes = MAX_FILE_SHARE_RESPONSE_BYTES,
    )
}

suspend fun NextcloudPlatformServices.createFileShare(
    session: NextcloudSession,
    request: CreateFileShareRequest,
): NextcloudFileShare = parseNextcloudFileShareResponse(
    executeNextcloudApi(session, request.toNextcloudApiRequest()),
)

fun parseNextcloudFileShareResponse(response: NextcloudApiResponse): NextcloudFileShare {
    if (response.status !in 200..299) throw fileOperationException(response.status)
    val root = runCatching { shareJson.parseToJsonElement(response.body.decodeToString()) as? JsonObject }
        .getOrNull() ?: error("The share response is not valid JSON.")
    val ocs = root["ocs"] as? JsonObject ?: error("The share response is missing OCS data.")
    val meta = ocs["meta"] as? JsonObject
    val ocsStatus = meta?.primitive("statuscode")?.intOrNull
    if (ocsStatus != null && ocsStatus !in setOf(100, 200)) {
        val message = meta.primitive("message")?.contentOrNull
            ?.boundedFileShareText(MAX_FILE_SHARE_ERROR_LENGTH)
            ?: "Nextcloud rejected the share."
        throw NextcloudFileOperationException(NextcloudFileOperationError.ServerFailure, ocsStatus, message)
    }
    val data = ocs["data"] as? JsonObject ?: error("The share response has no share record.")
    val id = data.primitive("id")?.contentOrNull
        ?.takeIf { it.isNotBlank() && it.length <= MAX_FILE_SHARE_ID_LENGTH && it.none(Char::isISOControl) }
        ?: error("The share response has no identifier.")
    return NextcloudFileShare(
        id = id,
        url = data.primitive("url")?.contentOrNull?.takeIf {
            it.isNotBlank() && it.length <= MAX_FILE_SHARE_URL_LENGTH && it.none(Char::isISOControl)
        },
        token = data.primitive("token")?.contentOrNull?.takeIf {
            it.isNotBlank() && it.length <= MAX_FILE_SHARE_TOKEN_LENGTH && it.none(Char::isISOControl)
        },
        shareType = data.primitive("share_type")?.intOrNull,
        shareWith = data.primitive("share_with")?.contentOrNull?.boundedFileShareText(
            MAX_FILE_SHARE_RECIPIENT_LENGTH,
        ),
        displayName = data.primitive("share_with_displayname")?.contentOrNull?.boundedFileShareText(
            MAX_FILE_SHARE_RECIPIENT_LENGTH,
        ),
        permissions = data.primitive("permissions")?.intOrNull?.takeIf { it in 1..31 },
    )
}

private fun destinationPath(source: String, directory: String, requestedName: String?): String {
    val safeDirectory = requireSafeFilePath(directory, allowRoot = true)
    val name = requestedName?.let(::requireSafeFileName) ?: source.substringAfterLast('/')
    return joinFilePath(safeDirectory, name)
}

internal fun requireSafeFilePath(path: String, allowRoot: Boolean): String {
    require('\u0000' !in path && '\\' !in path) { "The file path contains invalid characters." }
    require(!path.startsWith('/') && !path.endsWith('/')) { "File paths must be relative and normalized." }
    if (path.isEmpty()) {
        require(allowRoot) { "The account root cannot be changed." }
        return path
    }
    require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
        "The file path contains an invalid segment."
    }
    return path
}

private fun requireSafeFileName(name: String): String {
    require(name.isNotBlank() && name == name.trim()) { "Enter a valid file name." }
    require(name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }) {
        "The file name contains invalid characters."
    }
    return name
}

private fun joinFilePath(directory: String, name: String): String = if (directory.isEmpty()) name else "$directory/$name"

private fun encodeFormComponent(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xff
        val unreserved = unsigned in 'a'.code..'z'.code || unsigned in 'A'.code..'Z'.code ||
            unsigned in '0'.code..'9'.code || unsigned == '-'.code || unsigned == '.'.code ||
            unsigned == '_'.code || unsigned == '~'.code
        if (unreserved) append(unsigned.toChar()) else {
            append('%')
            append(FILE_HEX_DIGITS[unsigned ushr 4])
            append(FILE_HEX_DIGITS[unsigned and 0x0f])
        }
    }
}

private fun String.boundedFileShareText(maxLength: Int): String? {
    val text = buildString(minOf(length, maxLength)) {
        var pendingSpace = false
        for (character in this@boundedFileShareText) {
            if (character.isISOControl() || character.isWhitespace()) {
                pendingSpace = isNotEmpty()
            } else {
                if (pendingSpace && length < maxLength) append(' ')
                if (length >= maxLength) break
                append(character)
                pendingSpace = false
            }
        }
    }.trim()
    return text.takeIf(String::isNotBlank)
}

private fun JsonObject.primitive(name: String): JsonPrimitive? = get(name) as? JsonPrimitive

private val shareJson = Json { ignoreUnknownKeys = true }
private const val FILE_HEX_DIGITS = "0123456789ABCDEF"
private const val MAX_FILE_SHARE_PATH_BYTES = 4_096
private const val MAX_FILE_SHARE_RECIPIENT_LENGTH = 255
private const val MAX_FILE_SHARE_REQUEST_BYTES = 16 * 1024
private const val MAX_FILE_SHARE_RESPONSE_BYTES = 256L * 1024L
private const val MAX_FILE_SHARE_ID_LENGTH = 256
private const val MAX_FILE_SHARE_URL_LENGTH = 8_192
private const val MAX_FILE_SHARE_TOKEN_LENGTH = 2_048
private const val MAX_FILE_SHARE_ERROR_LENGTH = 320
