package dev.obiente.nextcloudnative.app

internal fun isAmbiguousDeckMutationFailure(responseStatus: Int?): Boolean =
    responseStatus == null || responseStatus in 200..299 || responseStatus >= 500

internal fun DeckCard.hasSameAuthoritativeRevision(other: DeckCard): Boolean =
    etag == null || other.etag == null || etag == other.etag
