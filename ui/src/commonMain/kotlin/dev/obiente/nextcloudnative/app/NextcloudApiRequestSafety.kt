package dev.obiente.nextcloudnative.app

/**
 * Validates a dynamic request before any platform transport sends it: an account-relative path,
 * safe query names, a bounded response, and only the bounded entity-tag preconditions as headers.
 */
fun NextcloudApiRequest.requireSafe(): NextcloudApiRequest {
    require(relativePath.startsWith('/') && !relativePath.startsWith("//")) {
        "Dynamic API paths must be relative to the connected Nextcloud server."
    }
    require(relativePath.none { it == '\\' || it == '?' || it == '#' || it.isWhitespace() }) {
        "Dynamic API paths cannot contain a query, fragment, backslash, or whitespace."
    }
    require(relativePath.split('/').none { segment ->
        val decodedDots = segment.replace("%2e", ".", ignoreCase = true)
        decodedDots == "." || decodedDots == ".."
    }) { "Dynamic API paths cannot traverse directories." }
    require(queryParameters.keys.all { key ->
        key.isNotBlank() && key.none { it.isWhitespace() || it == '&' || it == '=' || it == '#' }
    }) { "Dynamic API query parameter names are invalid." }
    val decodableImage = relativePath.matches(Regex("^/index\\.php/apps/memories/api/image/decodable/[1-9][0-9]*$"))
    val maximumAllowedResponse = if (decodableImage) MAX_IN_MEMORY_FILE_CONTENT_BYTES else MAX_DYNAMIC_API_RESPONSE_LIMIT_BYTES
    require(maximumResponseBytes in 1..maximumAllowedResponse) {
        "Dynamic API response limit is outside the allowed range."
    }
    require(contentType == null || contentType.length <= 160) { "Dynamic API content type is invalid." }
    require(
        listOfNotNull(ifMatch, ifNoneMatch).all { it.length <= 520 && it.none(Char::isISOControl) } &&
            (ifNoneMatch == null || method == NextcloudApiMethod.GET),
    ) { "The precondition is invalid; only a read can carry If-None-Match." }
    require(idempotencyKey == null || (method != NextcloudApiMethod.GET && idempotencyKey.length in 16..128 && idempotencyKey.all { it.isLetterOrDigit() || it == '-' })) {
        "The idempotency key is invalid."
    }
    multipartBody?.let { multipart ->
        require(method in setOf(NextcloudApiMethod.POST, NextcloudApiMethod.PUT, NextcloudApiMethod.PATCH)) {
            "Multipart uploads require POST, PUT, or PATCH."
        }
        require(body == null && contentType == null) {
            "A typed multipart body cannot be combined with a raw request body or content type."
        }
        multipart.requireSafe()
    }
    return this
}

fun buildNextcloudApiUrl(serverUrl: String, request: NextcloudApiRequest): String {
    request.requireSafe()
    val base = serverUrl.trimEnd('/') + request.relativePath
    if (request.queryParameters.isEmpty()) return base
    val query = request.queryParameters.toSortedMap().entries.joinToString("&") { (key, value) ->
        "${encodeUrlComponent(key)}=${encodeUrlComponent(value)}"
    }
    return "$base?$query"
}
