package dev.obiente.nextcloudnative.app

/**
 * Transport-neutral request for Nextcloud's official versions DAV collection.
 *
 * Authentication and the account origin are deliberately absent. Platform transports attach the
 * active account, keep the request same-origin, parse DAV XML, and enforce [maximumResponseBytes].
 */
data class FileVersionDavRequest(
    val method: String,
    val relativePath: String,
    val depth: Int? = null,
    val contentType: String? = null,
    val body: ByteArray? = null,
    val headers: Map<String, String> = emptyMap(),
    val maximumResponseBytes: Long,
)

data class FileVersionByteRange(
    val start: Long,
    val endInclusive: Long,
) {
    init {
        require(start >= 0L && endInclusive >= start) { "The version byte range is invalid." }
        require(endInclusive - start + 1L <= MAX_FILE_VERSION_IN_MEMORY_BYTES) {
            "The version byte range is too large."
        }
    }
}

/** Raw properties from one successful DAV response element. */
data class FileVersionDavRecord(
    val href: String,
    val contentLength: String?,
    val lastModified: String?,
    val etag: String?,
    val author: String?,
    val label: String?,
)

/**
 * Account-scoped historical generation of a Nextcloud file.
 *
 * [id] is the opaque numeric generation returned in the DAV href. It is not a path and must not be
 * decoded or combined with a different file identity.
 */
data class NextcloudFileVersion(
    val fileId: Long,
    val id: String,
    val sizeBytes: Long?,
    val lastModified: String?,
    val etag: String?,
    val author: String?,
    val label: String?,
) {
    init {
        require(fileId > 0L) { "The version file ID is invalid." }
        require(id.isSafeVersionId()) { "The file version ID is invalid." }
        require(sizeBytes == null || sizeBytes >= 0L) { "The file version size is invalid." }
        require(lastModified.isSafeOptionalDavValue(MAX_FILE_VERSION_DATE_LENGTH)) {
            "The file version date is invalid."
        }
        require(etag.isSafeOptionalDavValue(MAX_FILE_VERSION_ETAG_LENGTH)) {
            "The file version ETag is invalid."
        }
        require(author.isSafeOptionalDavValue(MAX_FILE_VERSION_AUTHOR_LENGTH)) {
            "The file version author is invalid."
        }
        require(label.isSafeOptionalDavValue(MAX_FILE_VERSION_LABEL_LENGTH)) {
            "The file version label is invalid."
        }
    }

}

data class FileVersionHistory(
    val fileId: Long,
    val versions: List<NextcloudFileVersion>,
) {
    init {
        require(fileId > 0L) { "The version file ID is invalid." }
        require(versions.size <= MAX_FILE_VERSION_RECORDS) { "The server returned too many file versions." }
        require(versions.all { it.fileId == fileId }) { "The version response mixed file identities." }
        require(versions.map(NextcloudFileVersion::id).distinct().size == versions.size) {
            "The version response contains duplicate generations."
        }
    }
}

enum class FileVersionAvailability {
    Available,
    AppDisabled,
    DavUnavailable,
}

/**
 * Versions availability is based on the enabled-app inventory plus a DAV reachability probe.
 *
 * Some valid Nextcloud installations do not publish a `files_versions` capability object even
 * while the app and its DAV endpoint are enabled. Treating the capability object as mandatory
 * would hide working history. Conversely, an app identifier alone cannot prove that DAV is
 * reachable through the current proxy, so both signals are required.
 */
fun fileVersionAvailability(
    enabledAppIds: Collection<String>,
    davReachable: Boolean,
): FileVersionAvailability = when {
    enabledAppIds.none { it == FILE_VERSIONS_APP_ID } -> FileVersionAvailability.AppDisabled
    !davReachable -> FileVersionAvailability.DavUnavailable
    else -> FileVersionAvailability.Available
}

fun fileVersionHistoryRequest(userId: String, fileId: Long): FileVersionDavRequest =
    FileVersionDavRequest(
        method = "PROPFIND",
        relativePath = fileVersionCollectionPath(userId, fileId),
        depth = 1,
        contentType = FILE_VERSION_DAV_XML_CONTENT_TYPE,
        body = FILE_VERSION_PROPFIND_BODY.encodeToByteArray(),
        maximumResponseBytes = FILE_VERSION_HISTORY_RESPONSE_BYTES,
    )

/**
 * Builds a bounded historical-content read. This never restores or modifies the current file.
 *
 * A range is optional because small text/image versions can be previewed without downloading an
 * unbounded historical blob. Full exports must still use a streaming platform transport.
 */
fun fileVersionContentRequest(
    userId: String,
    fileId: Long,
    versionId: String,
    range: FileVersionByteRange? = null,
): FileVersionDavRequest {
    require(versionId.isSafeVersionId()) { "The file version ID is invalid." }
    return FileVersionDavRequest(
        method = "GET",
        relativePath = "${fileVersionCollectionPath(userId, fileId)}/$versionId",
        headers = range?.let { mapOf("Range" to "bytes=${it.start}-${it.endInclusive}") }.orEmpty(),
        maximumResponseBytes = range?.let { it.endInclusive - it.start + 1L }
            ?: MAX_FILE_VERSION_PREVIEW_BYTES,
    )
}

/**
 * Plans an explicitly bounded download of a historical generation.
 *
 * A range is sent for unknown or larger objects. Known objects that fit the limit use a normal GET
 * because some DAV stacks do not implement ranged historical reads. In both cases platform
 * transports enforce [maximumResponseBytes] while reading, so stale metadata or a server that
 * ignores Range cannot turn preview/export into an unbounded in-memory response.
 */
fun boundedFileVersionContentRequest(
    userId: String,
    fileId: Long,
    versionId: String,
    maximumBytes: Long,
    expectedSizeBytes: Long? = null,
): FileVersionDavRequest {
    require(maximumBytes in 1L..MAX_FILE_VERSION_IN_MEMORY_BYTES) {
        "The historical file in-memory read limit is invalid."
    }
    require(expectedSizeBytes == null || expectedSizeBytes >= 0L) {
        "The historical file size is invalid."
    }
    if (expectedSizeBytes != null && expectedSizeBytes <= maximumBytes) {
        return fileVersionContentRequest(
            userId = userId,
            fileId = fileId,
            versionId = versionId,
        ).copy(maximumResponseBytes = maximumBytes)
    }
    return fileVersionContentRequest(
        userId = userId,
        fileId = fileId,
        versionId = versionId,
        range = FileVersionByteRange(0L, maximumBytes - 1L),
    )
}

/**
 * Plans Nextcloud's official DAV rollback operation.
 *
 * The server exposes a special restore collection. Moving the exact historical generation into
 * its `target` child asks the versions app to replace the current file while preserving all path
 * and ownership decisions on the server. Platform transports must turn [destinationRelativePath]
 * into a same-origin absolute `Destination` header.
 */
data class FileVersionRestoreRequest(
    val method: String,
    val relativePath: String,
    val destinationRelativePath: String,
    val headers: Map<String, String>,
    val maximumResponseBytes: Long,
)

fun fileVersionRestoreRequest(
    userId: String,
    file: NextcloudFile,
    version: NextcloudFileVersion,
): FileVersionRestoreRequest {
    val fileId = requireRestorableFileVersion(file, version)
    return FileVersionRestoreRequest(
        method = "MOVE",
        relativePath = "${fileVersionCollectionPath(userId, fileId)}/${version.id}",
        destinationRelativePath = "${fileVersionRootPath(userId)}/restore/target",
        headers = mapOf("Overwrite" to "T"),
        maximumResponseBytes = FILE_VERSION_RESTORE_RESPONSE_BYTES,
    )
}

/** Validates that a follow-up historical read still belongs to the selected Files record. */
fun requireMatchingFileVersion(
    file: NextcloudFile,
    version: NextcloudFileVersion,
): Long {
    require(!file.isDirectory) { "Folders do not have downloadable file generations." }
    require(file.originalAccessAllowed) { "Downloading this file is not allowed." }
    val fileId = requireNotNull(file.fileId) { "The file has no stable server identity." }
    require(fileId > 0L && version.fileId == fileId) {
        "The historical generation belongs to a different file."
    }
    return fileId
}

/** Applies the update-permission and identity checks used before exposing rollback. */
fun requireRestorableFileVersion(
    file: NextcloudFile,
    version: NextcloudFileVersion,
): Long {
    require(!file.isDirectory) { "Folders do not have restorable file generations." }
    require(file.originalAccessAllowed) { "Restoring this file is not allowed." }
    require(file.permissions?.contains('W') == true) {
        "Write permission is required to restore a file version."
    }
    val fileId = requireNotNull(file.fileId) { "The file has no stable server identity." }
    require(fileId > 0L && version.fileId == fileId) {
        "The historical generation belongs to a different file."
    }
    return fileId
}

/**
 * Distinct exported name for historical bytes, so the copy cannot be mistaken for the current
 * server object. The original extension remains intact for native viewers.
 */
fun historicalFileCopyName(fileName: String, versionId: String): String {
    require(versionId.isSafeVersionId()) { "The file version ID is invalid." }
    val cleanName = sanitizeExternalFileName(fileName)
    val extensionIndex = cleanName.lastIndexOf('.').takeIf { it in 1 until cleanName.lastIndex }
    val stem = extensionIndex?.let(cleanName::substring)?.let { cleanName.removeSuffix(it) } ?: cleanName
    val extension = extensionIndex?.let(cleanName::substring).orEmpty()
    return sanitizeExternalFileName("$stem-version-$versionId$extension")
}

/**
 * Converts parsed DAV response records into a newest-first history.
 *
 * The collection response itself is ignored. Every child must stay directly beneath the expected
 * file collection and use a numeric generation ID, so a malicious or malformed href cannot become
 * a follow-up download target. DAV hrefs are compared as encoded paths; percent-escape hex digits
 * are canonicalized to uppercase, but reserved bytes are never decoded into path separators.
 */
fun normalizeFileVersionHistory(
    userId: String,
    fileId: Long,
    records: List<FileVersionDavRecord>,
): FileVersionHistory {
    require(records.size <= MAX_FILE_VERSION_RECORDS + 1) { "The server returned too many file versions." }
    val collectionPath = fileVersionCollectionPath(userId, fileId)
    val versions = records.mapNotNull { record ->
        val href = record.href.trimEnd('/').canonicalFileVersionHref()
        if (href == collectionPath) return@mapNotNull null
        require(href.startsWith("$collectionPath/")) { "The version response contains an unexpected DAV href." }
        val id = href.removePrefix("$collectionPath/")
        require('/' !in id && id.isSafeVersionId()) { "The version response contains an invalid generation href." }
        NextcloudFileVersion(
            fileId = fileId,
            id = id,
            sizeBytes = record.contentLength?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
                ?: record.contentLength?.let { error("The file version size is invalid.") },
            lastModified = record.lastModified.cleanedDavValue(),
            etag = record.etag.cleanedDavValue(),
            author = record.author.cleanedDavValue(),
            label = record.label.cleanedDavValue(),
        )
    }.sortedWith(
        compareByDescending<NextcloudFileVersion> { it.id.length }
            .thenByDescending(NextcloudFileVersion::id),
    )
    return FileVersionHistory(fileId, versions)
}

private fun fileVersionCollectionPath(userId: String, fileId: Long): String {
    require(fileId > 0L) { "The version file ID is invalid." }
    return "${fileVersionRootPath(userId)}/versions/$fileId"
}

private fun fileVersionRootPath(userId: String): String =
    "/remote.php/dav/versions/${userId.toSafeDavSegment()}"

private fun String.toSafeDavSegment(): String {
    require(isNotBlank() && length <= MAX_FILE_VERSION_USER_ID_LENGTH && none(Char::isISOControl)) {
        "The DAV user ID is invalid."
    }
    return encodeToByteArray().joinToString(separator = "") { byte ->
        val value = byte.toInt() and 0xff
        val character = value.toChar()
        if (value < 0x80 && (character.isLetterOrDigit() || character in DAV_SEGMENT_SAFE_CHARACTERS)) {
            character.toString()
        } else {
            "%${HEX[value ushr 4]}${HEX[value and 0x0f]}"
        }
    }
}

private fun String?.cleanedDavValue(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun String.canonicalFileVersionHref(): String {
    require(
        startsWith('/') && length <= MAX_FILE_VERSION_HREF_LENGTH &&
            none { it.isISOControl() || it == '\\' || it == '?' || it == '#' },
    ) { "The version response contains an invalid DAV href." }
    return buildString(length) {
        var index = 0
        while (index < this@canonicalFileVersionHref.length) {
            val character = this@canonicalFileVersionHref[index]
            if (character != '%') {
                append(character)
                index += 1
                continue
            }
            require(index + 2 < this@canonicalFileVersionHref.length) {
                "The version response contains an invalid DAV href."
            }
            val first = this@canonicalFileVersionHref[index + 1]
            val second = this@canonicalFileVersionHref[index + 2]
            require(first.isHexDigit() && second.isHexDigit()) {
                "The version response contains an invalid DAV href."
            }
            append('%')
            append(first.uppercaseChar())
            append(second.uppercaseChar())
            index += 3
        }
    }
}

private fun Char.isHexDigit(): Boolean = isDigit() || lowercaseChar() in 'a'..'f'

private fun String?.isSafeOptionalDavValue(maximumLength: Int): Boolean =
    this == null || length <= maximumLength && none(Char::isISOControl)

private fun String.isSafeVersionId(): Boolean =
    length in 1..MAX_FILE_VERSION_ID_LENGTH && all(Char::isDigit) && first() != '0'

private const val FILE_VERSION_DAV_XML_CONTENT_TYPE = "application/xml; charset=utf-8"
private const val FILE_VERSION_HISTORY_RESPONSE_BYTES = 2L * 1024L * 1024L
private const val FILE_VERSION_RESTORE_RESPONSE_BYTES = 64L * 1024L
const val MAX_FILE_VERSION_PREVIEW_BYTES = 16L * 1024L * 1024L
const val MAX_FILE_VERSION_IN_MEMORY_BYTES = 64L * 1024L * 1024L
private const val MAX_FILE_VERSION_RECORDS = 2_000
private const val MAX_FILE_VERSION_USER_ID_LENGTH = 320
private const val MAX_FILE_VERSION_HREF_LENGTH = 4_096
private const val MAX_FILE_VERSION_ID_LENGTH = 32
private const val MAX_FILE_VERSION_DATE_LENGTH = 128
private const val MAX_FILE_VERSION_ETAG_LENGTH = 1_024
private const val MAX_FILE_VERSION_AUTHOR_LENGTH = 320
private const val MAX_FILE_VERSION_LABEL_LENGTH = 512
private const val FILE_VERSIONS_APP_ID = "files_versions"
private const val HEX = "0123456789ABCDEF"
private val DAV_SEGMENT_SAFE_CHARACTERS = setOf('-', '.', '_', '~')

private val FILE_VERSION_PROPFIND_BODY = """
    <?xml version="1.0" encoding="UTF-8"?>
    <d:propfind xmlns:d="DAV:" xmlns:nc="http://nextcloud.org/ns">
      <d:prop>
        <d:resourcetype />
        <d:getcontentlength />
        <d:getlastmodified />
        <d:getetag />
        <nc:version-author />
        <nc:version-label />
      </d:prop>
    </d:propfind>
""".trimIndent()
