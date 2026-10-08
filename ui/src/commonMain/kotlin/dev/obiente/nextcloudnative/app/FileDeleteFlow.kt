package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

/** The verified result of one ETag-guarded Files delete. */
internal sealed interface FileDeleteOutcome {
    /** The server confirmed the delete. [localFollowUp] reports the local bookkeeping separately. */
    data class Deleted(val localFollowUp: FileMutationLocalFollowUp) : FileDeleteOutcome

    /**
     * The item is no longer at its path. That is not proof of deletion: after an unknown result
     * ([afterUnknownResult]) another client may have moved or renamed it instead.
     */
    data class NoLongerAtLocation(val afterUnknownResult: Boolean) : FileDeleteOutcome

    /** The item is verified to be unchanged on the server, so trying again is safe. */
    data class NotDeleted(val message: String) : FileDeleteOutcome

    /**
     * The outcome is unknown, or the item changed so its version precondition is stale. The folder
     * must be refreshed before another try.
     */
    data class RefreshRequired(val message: String) : FileDeleteOutcome

    /**
     * The server rate-limited the request without changing anything. File mutations do not expose
     * Retry-After, so no delay is invented: writes stay blocked and no extra read is started.
     */
    data class Throttled(val message: String) : FileDeleteOutcome
}

/**
 * Deletes [target] once and never retries. A result without a definitive server answer is resolved
 * by reading the parent folder from the server, never by sending the delete again.
 */
internal suspend fun deleteFileWithVerifiedOutcome(
    target: NextcloudFile,
    expectedEtag: String,
    delete: suspend () -> NextcloudFileMutationResult,
    readParent: suspend () -> NextcloudFileListing,
): FileDeleteOutcome {
    val failure = try {
        return FileDeleteOutcome.Deleted(delete().localFollowUp)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure
    }
    if (failure is NextcloudFileOperationException) {
        when (failure.error) {
            NextcloudFileOperationError.NotFound -> return FileDeleteOutcome.NoLongerAtLocation(afterUnknownResult = false)
            NextcloudFileOperationError.ServerFailure -> Unit
            // Resending the same ETag precondition cannot succeed.
            NextcloudFileOperationError.Conflict -> return FileDeleteOutcome.RefreshRequired(
                failure.message ?: "${target.name} changed on the server. Refresh the folder before deleting it.",
            )
            NextcloudFileOperationError.Throttled -> return FileDeleteOutcome.Throttled(
                failure.message ?: "The server is limiting requests. Wait a while, then try again.",
            )
            NextcloudFileOperationError.AuthenticationRequired,
            NextcloudFileOperationError.PermissionDenied,
            NextcloudFileOperationError.Locked,
            NextcloudFileOperationError.InsufficientStorage,
            -> return FileDeleteOutcome.NotDeleted(failure.message ?: "Could not delete ${target.name}.")
        }
    }
    return verifyFileDeletePostcondition(target, expectedEtag, readParent)
}

private suspend fun verifyFileDeletePostcondition(
    target: NextcloudFile,
    expectedEtag: String,
    readParent: suspend () -> NextcloudFileListing,
): FileDeleteOutcome {
    val unconfirmed = FileDeleteOutcome.RefreshRequired(
        "Could not confirm whether ${target.name} was deleted. Refresh the folder before trying again.",
    )
    val listing = try {
        readParent()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return unconfirmed
    }
    if (listing.source != NextcloudFileListingSource.Network) return unconfirmed
    val current = listing.files.firstOrNull { it.path == target.path }
    if (current == null) {
        // The stable file ID can only be reconciled within the listing already read.
        val renamedInPlace = target.fileId != null && listing.files.any { it.fileId == target.fileId }
        return if (renamedInPlace) {
            FileDeleteOutcome.RefreshRequired("${target.name} was renamed on the server. Refresh the folder.")
        } else {
            FileDeleteOutcome.NoLongerAtLocation(afterUnknownResult = true)
        }
    }
    return if (current.isDirectory == target.isDirectory && current.etag == expectedEtag) {
        FileDeleteOutcome.NotDeleted("The delete did not finish. ${target.name} is still on the server.")
    } else {
        FileDeleteOutcome.RefreshRequired(
            "${target.name} changed on the server. Refresh the folder before deleting it.",
        )
    }
}

/** Screen effects of a finished delete. They apply even after its dialog was closed. */
internal data class FileDeleteScreenEffect(val notice: String?, val reloadFolder: Boolean)

internal fun FileDeleteOutcome.screenEffect(name: String, dialogShowsResult: Boolean): FileDeleteScreenEffect =
    when (this) {
        is FileDeleteOutcome.Deleted -> FileDeleteScreenEffect(
            notice = when (localFollowUp) {
                FileMutationLocalFollowUp.StillRunning ->
                    "Deleted $name. Local file status is still updating in the background."
                FileMutationLocalFollowUp.Failed ->
                    "Deleted $name. Local file status could not be updated; refresh if it still appears."
                FileMutationLocalFollowUp.Completed -> "Deleted $name"
            },
            reloadFolder = true,
        )
        is FileDeleteOutcome.NoLongerAtLocation -> FileDeleteScreenEffect(
            notice = if (afterUnknownResult) {
                "$name is no longer in this folder. The delete response was interrupted, so it may " +
                    "have been deleted, moved, or renamed."
            } else {
                "$name is no longer in this folder."
            },
            reloadFolder = true,
        )
        is FileDeleteOutcome.NotDeleted -> FileDeleteScreenEffect(
            notice = if (dialogShowsResult) null else "$name was not deleted. $message",
            reloadFolder = false,
        )
        is FileDeleteOutcome.RefreshRequired -> FileDeleteScreenEffect(
            notice = if (dialogShowsResult) null else message,
            reloadFolder = true,
        )
        // A folder reload is another request; leave it to the user after the rate limit.
        is FileDeleteOutcome.Throttled -> FileDeleteScreenEffect(
            notice = if (dialogShowsResult) null else "$name was not deleted. $message",
            reloadFolder = false,
        )
    }
