package dev.obiente.nextcloudnative.app

internal fun isAmbiguousDeckMutationFailure(responseStatus: Int?): Boolean =
    responseStatus == null || responseStatus in 200..299 || responseStatus >= 500

internal fun DeckCard.hasSameAuthoritativeRevision(other: DeckCard): Boolean =
    etag == null || other.etag == null || etag == other.etag

internal fun DeckBoard.hasSameAuthoritativeRevision(other: DeckBoard): Boolean {
    if (id != other.id) return false
    if (etag != null && other.etag != null) return etag == other.etag
    return title == other.title &&
        color == other.color &&
        archived == other.archived &&
        lastModified == other.lastModified
}
