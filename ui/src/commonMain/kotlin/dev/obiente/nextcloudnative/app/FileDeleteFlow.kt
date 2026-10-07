package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The verified result of one ETag-guarded Files delete. */
internal sealed interface FileDeleteOutcome {
    /**
     * The server confirmed the delete, or a fresh server listing showed the item is gone after
     * an unknown result. [localFollowUp] is null when the local update was not reported.
     */
    data class Deleted(
        val confirmedByRead: Boolean,
        val localFollowUp: FileMutationLocalFollowUp?,
    ) : FileDeleteOutcome

    /** The server reported that the item no longer exists. */
    data object AlreadyGone : FileDeleteOutcome

    /** The item is verified to be unchanged on the server, so trying again is safe. */
    data class NotDeleted(val message: String) : FileDeleteOutcome

    /** The outcome is unknown or the item changed. The folder must be refreshed before another try. */
    data class RefreshRequired(val message: String) : FileDeleteOutcome
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
        val result = delete()
        return FileDeleteOutcome.Deleted(confirmedByRead = false, localFollowUp = result.localFollowUp)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure
    }
    if (failure is NextcloudFileOperationException) {
        when (failure.error) {
            NextcloudFileOperationError.NotFound -> return FileDeleteOutcome.AlreadyGone
            NextcloudFileOperationError.ServerFailure -> Unit
            NextcloudFileOperationError.AuthenticationRequired,
            NextcloudFileOperationError.PermissionDenied,
            NextcloudFileOperationError.Conflict,
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
        ?: return FileDeleteOutcome.Deleted(confirmedByRead = true, localFollowUp = null)
    return if (current.isDirectory == target.isDirectory && current.etag == expectedEtag) {
        FileDeleteOutcome.NotDeleted("The delete did not finish. ${target.name} is still on the server.")
    } else {
        FileDeleteOutcome.RefreshRequired(
            "${target.name} changed on the server. Refresh the folder before deleting it.",
        )
    }
}

internal data class FileDeleteRequest(
    val generation: Long,
    val file: NextcloudFile,
    val expectedEtag: String,
) {
    val parentPath: String get() = file.path.substringBeforeLast('/', missingDelimiterValue = "")
}

/** Screen effects of a finished delete. They apply even after its dialog was closed. */
internal data class FileDeleteScreenEffect(val notice: String?, val reloadFolder: Boolean)

/**
 * Main-thread owner of the Files delete dialog. Closing or replacing the dialog invalidates its
 * pending completion, which can then only report a screen notice and refresh the folder.
 */
internal class FileDeleteDialogState {
    var target by mutableStateOf<NextcloudFile?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var retryBlocked by mutableStateOf(false)
        private set
    private var generation = 0L

    fun open(file: NextcloudFile) = reset(file)

    /** The delete continues if it is running; its completion no longer changes this dialog. */
    fun dismiss() = reset(null)

    fun begin(): FileDeleteRequest? {
        val file = target ?: return null
        if (running || retryBlocked) return null
        val etag = file.etag?.takeIf(String::isNotBlank) ?: run {
            error = "Refresh the folder before deleting this item."
            return null
        }
        running = true
        error = null
        return FileDeleteRequest(generation, file, etag)
    }

    fun complete(request: FileDeleteRequest, outcome: FileDeleteOutcome): FileDeleteScreenEffect {
        val current = request.generation == generation
        if (current) {
            running = false
            when (outcome) {
                is FileDeleteOutcome.Deleted, FileDeleteOutcome.AlreadyGone -> reset(null)
                is FileDeleteOutcome.NotDeleted -> error = outcome.message
                is FileDeleteOutcome.RefreshRequired -> {
                    error = outcome.message
                    retryBlocked = true
                }
            }
        }
        return outcome.screenEffect(request.file.name, dialogShowsResult = current)
    }

    /** Releases a request that ended without an outcome, such as a cancelled screen scope. */
    fun abandon(request: FileDeleteRequest) {
        if (request.generation == generation) running = false
    }

    private fun reset(file: NextcloudFile?) {
        generation += 1
        target = file
        running = false
        error = null
        retryBlocked = false
    }
}

/**
 * Runs one delete for [request]. Cancellation propagates without changing the dialog beyond
 * releasing its running state.
 */
internal suspend fun FileDeleteDialogState.runDelete(
    request: FileDeleteRequest,
    delete: suspend (NextcloudFileMutation.Delete) -> NextcloudFileMutationResult,
    readFolder: suspend (String) -> NextcloudFileListing,
): FileDeleteScreenEffect = try {
    val mutation = NextcloudFileMutation.Delete(
        request.file.path,
        request.expectedEtag,
        sourceIsDirectory = request.file.isDirectory,
    )
    val outcome = deleteFileWithVerifiedOutcome(
        target = request.file,
        expectedEtag = request.expectedEtag,
        delete = { delete(mutation) },
        readParent = { readFolder(request.parentPath) },
    )
    // Reject even a non-cooperative service's completion after the caller is cancelled.
    currentCoroutineContext().ensureActive()
    complete(request, outcome)
} finally {
    abandon(request)
}

private fun FileDeleteOutcome.screenEffect(name: String, dialogShowsResult: Boolean): FileDeleteScreenEffect =
    when (this) {
        is FileDeleteOutcome.Deleted -> FileDeleteScreenEffect(
            notice = when {
                confirmedByRead -> "Deleted $name. The server response was interrupted, so the folder was checked."
                localFollowUp == FileMutationLocalFollowUp.StillRunning ->
                    "Deleted $name. Local file status is still updating in the background."
                localFollowUp == FileMutationLocalFollowUp.Failed ->
                    "Deleted $name. Local file status could not be updated; refresh if it still appears."
                else -> "Deleted $name"
            },
            reloadFolder = true,
        )
        FileDeleteOutcome.AlreadyGone -> FileDeleteScreenEffect("$name is no longer on the server.", true)
        is FileDeleteOutcome.NotDeleted -> FileDeleteScreenEffect(
            notice = if (dialogShowsResult) null else "$name was not deleted. $message",
            reloadFolder = false,
        )
        is FileDeleteOutcome.RefreshRequired -> FileDeleteScreenEffect(
            notice = if (dialogShowsResult) null else message,
            reloadFolder = true,
        )
    }
