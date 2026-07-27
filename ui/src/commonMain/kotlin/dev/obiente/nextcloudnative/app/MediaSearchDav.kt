package dev.obiente.nextcloudnative.app

const val MAXIMUM_MEDIA_SEARCH_RESULTS = 80

fun mediaSearchDavRequestBody(
    userId: String,
    maximumResults: Int = MAXIMUM_MEDIA_SEARCH_RESULTS,
): String {
    require(userId.isNotBlank())
    require(maximumResults in 1..MAXIMUM_MEDIA_SEARCH_RESULTS)

    val rawFileNameFilters = rawPhotoFileNameSearchPatterns().joinToString("\n") { pattern ->
        """
            <d:like caseless="yes">
              <d:prop><d:displayname/></d:prop><d:literal>$pattern</d:literal>
            </d:like>
        """.trimIndent()
    }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
          <d:basicsearch>
            <d:select><d:prop>
              <d:displayname/><d:resourcetype/><d:getcontenttype/><d:getlastmodified/><d:getcontentlength/><d:getetag/>
              <oc:fileid/><oc:size/><oc:permissions/><nc:has-preview/>
            </d:prop></d:select>
            <d:from><d:scope><d:href>/files/${escapeMediaSearchXml(userId)}</d:href><d:depth>infinity</d:depth></d:scope></d:from>
            <d:where><d:or>
              <d:like><d:prop><d:getcontenttype/></d:prop><d:literal>image/%</d:literal></d:like>
              <d:like><d:prop><d:getcontenttype/></d:prop><d:literal>video/%</d:literal></d:like>
              $rawFileNameFilters
            </d:or></d:where>
            <d:orderby><d:order><d:prop><d:getlastmodified/></d:prop><d:descending/></d:order></d:orderby>
            <d:limit><d:nresults>$maximumResults</d:nresults></d:limit>
          </d:basicsearch>
        </d:searchrequest>
    """.trimIndent()
}

private fun escapeMediaSearchXml(value: String): String = buildString(value.length) {
    value.forEach { character ->
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
