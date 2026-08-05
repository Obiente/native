package dev.obiente.nextcloudnative.app

private const val MAX_FILE_SEARCH_QUERY_CHARS = 180

fun buildFileSearchDavRequest(
    userId: String,
    scopePath: String,
    query: String,
    maximumResults: Int,
): String {
    val safeUserId = userId.trim().also {
        require(it.isNotEmpty()) { "A Nextcloud user is required for file search." }
        require('/' !in it && '\\' !in it) { "The Nextcloud user is invalid." }
    }
    val safeScope = requireSafeFilePath(scopePath, allowRoot = true)
    val safeQuery = query.trim().also {
        require(it.length in 2..MAX_FILE_SEARCH_QUERY_CHARS) {
            "Enter at least 2 characters to search all files."
        }
    }
    require(maximumResults in 1..500) { "File search can return between 1 and 500 results." }
    val scope = buildString {
        append("/files/")
        append(safeUserId.encodeFileDavPathSegment())
        if (safeScope.isNotEmpty()) {
            append('/')
            append(safeScope.split('/').joinToString("/") { segment -> segment.encodeFileDavPathSegment() })
        }
    }
    val literal = "%${safeQuery.escapeFileDavLikeLiteral().escapeFileDavXml()}%"
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
          <d:basicsearch>
            <d:select><d:prop>
              <d:displayname/><d:getcontenttype/><d:getlastmodified/><d:getcontentlength/><d:getetag/>
              <d:resourcetype/><oc:fileid/><oc:size/><oc:permissions/><oc:favorite/>
              <oc:owner-id/><oc:owner-display-name/><oc:comments-unread/><nc:has-preview/>
            </d:prop></d:select>
            <d:from><d:scope><d:href>$scope</d:href><d:depth>infinity</d:depth></d:scope></d:from>
            <d:where><d:like><d:prop><d:displayname/></d:prop><d:literal>$literal</d:literal></d:like></d:where>
            <d:orderby><d:order><d:prop><d:displayname/></d:prop><d:ascending/></d:order></d:orderby>
            <d:limit><d:nresults>$maximumResults</d:nresults></d:limit>
          </d:basicsearch>
        </d:searchrequest>
    """.trimIndent()
}

fun buildFileFavoritePropPatch(favorite: Boolean): String = """
    <?xml version="1.0" encoding="UTF-8"?>
    <d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
      <d:set><d:prop><oc:favorite>${if (favorite) 1 else 0}</oc:favorite></d:prop></d:set>
    </d:propertyupdate>
""".trimIndent()

fun buildFavoriteFilesDavReport(): String = """
    <?xml version="1.0" encoding="UTF-8"?>
    <oc:filter-files xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
      <d:prop>
        <d:displayname/><d:getcontenttype/><d:getlastmodified/><d:getcontentlength/><d:getetag/>
        <d:resourcetype/><oc:fileid/><oc:size/><oc:permissions/><oc:favorite/>
        <oc:owner-id/><oc:owner-display-name/><oc:comments-unread/><nc:has-preview/>
      </d:prop>
      <oc:filter-rules><oc:favorite>1</oc:favorite></oc:filter-rules>
    </oc:filter-files>
""".trimIndent()

fun parseDavStatusCode(statusLine: String): Int? {
    val tokens = statusLine.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (tokens.size < 2 || !tokens[0].startsWith("HTTP/", ignoreCase = true)) return null
    return tokens[1].takeIf { token -> token.length == 3 && token.all(Char::isDigit) }?.toIntOrNull()
}

private fun String.encodeFileDavPathSegment(): String = buildString {
    for (byte in this@encodeFileDavPathSegment.encodeToByteArray()) {
        val value = byte.toInt() and 0xFF
        val character = value.toChar()
        if (
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character == '-' || character == '.' || character == '_' || character == '~'
        ) {
            append(character)
        } else {
            append('%')
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }
}

private fun String.escapeFileDavLikeLiteral(): String = buildString(length) {
    for (character in this@escapeFileDavLikeLiteral) {
        if (character == '%' || character == '_') append('\\')
        append(character)
    }
}

private fun String.escapeFileDavXml(): String = buildString(length) {
    for (character in this@escapeFileDavXml) {
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

private const val HEX_DIGITS = "0123456789ABCDEF"
