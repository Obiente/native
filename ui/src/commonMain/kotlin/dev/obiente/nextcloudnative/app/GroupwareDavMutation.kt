package dev.obiente.nextcloudnative.app

enum class GroupwareDavMutation {
    Create,
    Update,
    Delete,
}

/**
 * A client-error response normally proves that the server refused the mutation. Timeouts and
 * non-standard client-closed responses remain ambiguous because an intermediary can emit them
 * after forwarding the request. Redirects and server errors are ambiguous for the same reason.
 */
internal fun groupwareMutationResponseProvesRejection(status: Int): Boolean =
    status in 400..499 && status != 408 && status != 499

internal fun groupwareDeleteResponseProvesAbsence(status: Int): Boolean = status == 404 || status == 410

data class GroupwareDavMutationSpec(
    val kind: GroupwareDavKind,
    val mutation: GroupwareDavMutation,
    val objectHref: String,
    val etag: String? = null,
    val content: String? = null,
)

/**
 * Builds conflict-safe DAV writes without executing them. Updates and deletes require an ETag;
 * creates use If-None-Match so an opaque server resource can never be overwritten accidentally.
 */
fun GroupwareDavMutationSpec.toGroupwareDavRequest(): GroupwareDavRequest {
    val href = objectHref.requireSafeDavHref()
    require(!href.endsWith('/')) { "A DAV mutation must target an object, not a collection." }
    if (mutation == GroupwareDavMutation.Create) {
        val expectedSuffix = if (kind == GroupwareDavKind.Contact) ".vcf" else ".ics"
        require(href.endsWith(expectedSuffix, ignoreCase = true)) {
            "The new DAV object extension does not match its content kind."
        }
    }
    // Discovered object hrefs are opaque identifiers; updates and deletes must preserve them.
    val safeEtag = etag?.takeIf {
        it.isNotBlank() && it.length <= MAX_DAV_ETAG_LENGTH && it.none(Char::isISOControl)
    }
    val headers = when (mutation) {
        GroupwareDavMutation.Create -> {
            require(etag == null) { "A new DAV object cannot carry an existing ETag." }
            mapOf("If-None-Match" to "*")
        }
        GroupwareDavMutation.Update, GroupwareDavMutation.Delete -> {
            require(safeEtag != null) { "An ETag is required for conflict-safe DAV changes." }
            mapOf("If-Match" to safeEtag)
        }
    }
    val body = when (mutation) {
        GroupwareDavMutation.Delete -> {
            require(content == null) { "A DAV delete request cannot include object content." }
            null
        }
        GroupwareDavMutation.Create, GroupwareDavMutation.Update -> {
            val value = requireNotNull(content) { "DAV object content is required." }
            require(value.encodeToByteArray().size <= MAX_DAV_OBJECT_BYTES && '\u0000' !in value) {
                "The DAV object content is invalid or too large."
            }
            val requiredMarkers = when (kind) {
                GroupwareDavKind.Contact -> listOf("BEGIN:VCARD", "END:VCARD")
                GroupwareDavKind.Event -> listOf("BEGIN:VCALENDAR", "BEGIN:VEVENT", "END:VEVENT", "END:VCALENDAR")
                GroupwareDavKind.Task -> listOf("BEGIN:VCALENDAR", "BEGIN:VTODO", "END:VTODO", "END:VCALENDAR")
            }
            require(requiredMarkers.all { marker -> marker in value.uppercase() }) {
                "The DAV object content does not match its declared kind."
            }
            value.encodeToByteArray()
        }
    }
    return GroupwareDavRequest(
        method = when (mutation) {
            GroupwareDavMutation.Create, GroupwareDavMutation.Update -> "PUT"
            GroupwareDavMutation.Delete -> "DELETE"
        },
        relativePath = href,
        contentType = body?.let {
            if (kind == GroupwareDavKind.Contact) "text/vcard; charset=utf-8" else "text/calendar; charset=utf-8"
        },
        body = body,
        headers = headers,
        maximumResponseBytes = DAV_MUTATION_RESPONSE_BYTES,
    )
}

internal inline fun <T> prepareGroupwareDavMutation(
    onInvalid: () -> Unit,
    prepare: () -> T,
): T? = try {
    prepare()
} catch (_: IllegalArgumentException) {
    onInvalid()
    null
}

private const val MAX_DAV_ETAG_LENGTH = 1_024
private const val MAX_DAV_OBJECT_BYTES = 4 * 1024 * 1024
private const val DAV_MUTATION_RESPONSE_BYTES = 256L * 1024L
