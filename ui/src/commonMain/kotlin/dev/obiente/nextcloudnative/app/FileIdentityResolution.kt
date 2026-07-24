package dev.obiente.nextcloudnative.app

/**
 * Bounded DAV SEARCH used to turn Memories' stable file IDs back into authoritative Files paths.
 *
 * Memories deliberately returns lightweight timeline rows without the original path. File
 * mutations, external handoff, and editing must never reuse a synthetic gallery path, so native
 * media surfaces resolve IDs through the authenticated Files tree before enabling those actions.
 */
data class FilesByIdDavSearchRequest(
    val method: String,
    val relativePath: String,
    val contentType: String,
    val body: ByteArray,
)

fun filesByIdDavSearchRequest(
    userId: String,
    fileIds: Collection<Long>,
): FilesByIdDavSearchRequest {
    require(userId.isNotBlank() && userId.length <= MAX_FILE_IDENTITY_USER_LENGTH) {
        "The authenticated user ID is unavailable."
    }
    require('/' !in userId && '\\' !in userId && userId.none(Char::isISOControl)) {
        "The authenticated user ID is invalid."
    }
    val ids = fileIds.distinct()
    require(ids.isNotEmpty() && ids.size <= MAX_FILE_IDENTITY_SEARCH_BATCH && ids.all { it > 0L }) {
        "The file identity search batch is invalid."
    }
    val filters = ids.joinToString("") { fileId ->
        "<d:eq><d:prop><oc:fileid/></d:prop><d:literal>$fileId</d:literal></d:eq>"
    }
    val body = """
        <?xml version="1.0" encoding="UTF-8"?>
        <d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
          <d:basicsearch>
            <d:select><d:prop>
              <d:displayname/><d:getcontenttype/><d:getlastmodified/><d:getcontentlength/><d:getetag/>
              <d:resourcetype/><oc:fileid/><oc:size/><oc:permissions/><oc:checksums/><nc:has-preview/>
            </d:prop></d:select>
            <d:from><d:scope><d:href>/files/${userId.escapeFileIdentityXml()}</d:href><d:depth>0</d:depth></d:scope></d:from>
            <d:where><d:or>$filters</d:or></d:where>
          </d:basicsearch>
        </d:searchrequest>
    """.trimIndent()
    return FilesByIdDavSearchRequest(
        method = "SEARCH",
        relativePath = "/remote.php/dav/",
        contentType = "application/xml; charset=utf-8",
        body = body.encodeToByteArray(),
    )
}

private fun String.escapeFileIdentityXml(): String = buildString(length) {
    for (character in this@escapeFileIdentityXml) {
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&apos;"
                else -> character
            },
        )
    }
}

const val MAX_FILE_IDENTITY_SEARCH_BATCH = 100
private const val MAX_FILE_IDENTITY_USER_LENGTH = 255
