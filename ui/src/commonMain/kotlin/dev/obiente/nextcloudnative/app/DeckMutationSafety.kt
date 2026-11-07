package dev.obiente.nextcloudnative.app

internal fun isAmbiguousDeckMutationFailure(responseStatus: Int?, failure: Throwable? = null): Boolean =
    failure !is BoardMembershipWriteBlocked && (responseStatus == null || responseStatus in 200..299 || responseStatus >= 500)

internal fun requiresDeckBatchMutationReconciliation(
    confirmedWrites: Int,
    failedResponseStatus: Int?,
    failure: Throwable? = null,
): Boolean {
    require(confirmedWrites >= 0) { "Confirmed writes cannot be negative." }
    return confirmedWrites > 0 || isAmbiguousDeckMutationFailure(failedResponseStatus, failure)
}

internal fun DeckCard.hasSameAuthoritativeRevision(other: DeckCard): Boolean =
    if (id != other.id || boardId != other.boardId || stackId != other.stackId) {
        false
    } else if (etag != null && other.etag != null) {
        etag == other.etag
    } else {
        title == other.title &&
            descriptionMarkdown == other.descriptionMarkdown &&
            ownerId == other.ownerId &&
            color == other.color &&
            order == other.order &&
            dueAt == other.dueAt &&
            startAt == other.startAt &&
            completedAt == other.completedAt &&
            archived == other.archived
    }

internal fun DeckBoard.hasSameAuthoritativeRevision(other: DeckBoard): Boolean {
    if (id != other.id) return false
    if (etag != null && other.etag != null) return etag == other.etag
    return title == other.title &&
        color == other.color &&
        archived == other.archived &&
        lastModified == other.lastModified
}
