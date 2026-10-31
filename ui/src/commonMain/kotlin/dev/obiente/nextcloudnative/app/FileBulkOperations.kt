package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class FileBulkAction { AddFavorite, RemoveFavorite, Move, Copy, Delete }

/**
 * Runs one Files action over several selected items as a partial-success operation.
 *
 * Items run one at a time and are never retried. Every request keeps the item's ETag
 * precondition, and moves and copies never overwrite (`Overwrite: F`). Deletes go through the
 * account's delete fence exactly like a single delete: an in-flight claim per item, a verified
 * outcome, and a recorded retry block for outcomes that must not be repeated before a refresh.
 * An unknown move or copy result is checked by reading the source and destination folders.
 */
internal class FileBulkOperationRunner(
    private val deletes: FileDeleteCoordinator,
    private val mutate: suspend (NextcloudFileMutation) -> NextcloudFileMutationResult,
    private val setFavorite: suspend (NextcloudFile, Boolean) -> FileMutationLocalFollowUp,
    private val readFolder: suspend (String) -> NextcloudFileListing,
) {
    suspend fun favorite(files: List<NextcloudFile>, favorite: Boolean): BulkOperationReport =
        BulkOperationReport(
            verb = if (favorite) "added to favorites" else "removed from favorites",
            results = files.map { file ->
                currentCoroutineContext().ensureActive()
                blockedResult(file) ?: when {
                    file.favorite == favorite -> BulkItemResult(file.path, file.name, BulkItemOutcome.AlreadyDone)
                    else -> try {
                        setFavorite(file, favorite)
                        BulkItemResult(file.path, file.name, BulkItemOutcome.Completed)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: FileMutationOutcomeUnknownException) {
                        BulkItemResult(file.path, file.name, BulkItemOutcome.Unconfirmed, "The connection was interrupted.")
                    } catch (failure: Exception) {
                        // Favorites are a PROPPATCH of one property, so repeating it cannot duplicate anything.
                        BulkItemResult(file.path, file.name, BulkItemOutcome.Failed, failure.boundedReason(), retrySafe = failure.isRetrySafeFileFailure())
                    }
                }
            },
        )

    suspend fun transfer(files: List<NextcloudFile>, destinationFolder: String, copy: Boolean): BulkOperationReport {
        val destination = requireSafeFilePath(destinationFolder, allowRoot = true)
        val pendingVerification = mutableListOf<NextcloudFile>()
        val results = LinkedHashMap<String, BulkItemResult>()
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val parent = file.path.substringBeforeLast('/', missingDelimiterValue = "")
            val precheck = blockedResult(file) ?: when {
                deletes.newChildBlock(destination) != null -> BulkItemResult(file.path, file.name, BulkItemOutcome.Skipped, deletes.newChildBlock(destination)?.message)
                parent == destination -> BulkItemResult(file.path, file.name, BulkItemOutcome.Skipped, "It is already in this folder.")
                destination == file.path || destination.startsWith("${file.path}/") ->
                    BulkItemResult(file.path, file.name, BulkItemOutcome.Failed, "A folder cannot be placed inside itself.")
                else -> null
            }
            if (precheck != null) {
                results[file.path] = precheck
                continue
            }
            val etag = file.etag!!
            val mutation = if (copy) {
                NextcloudFileMutation.Copy(file.path, destination, null, etag, file.isDirectory)
            } else {
                NextcloudFileMutation.Move(file.path, destination, null, etag, file.isDirectory)
            }
            results[file.path] = try {
                mutate(mutation)
                BulkItemResult(file.path, file.name, BulkItemOutcome.Completed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: FileMutationNotSentException) {
                BulkItemResult(file.path, file.name, BulkItemOutcome.Failed, "Nothing was sent.", retrySafe = true)
            } catch (failure: NextcloudFileOperationException) {
                if (failure.error == NextcloudFileOperationError.ServerFailure) {
                    pendingVerification += file
                    BulkItemResult(file.path, file.name, BulkItemOutcome.Unconfirmed, "The server failed while handling it.")
                } else {
                    BulkItemResult(file.path, file.name, BulkItemOutcome.Failed, transferFailureReason(failure), retrySafe = false)
                }
            } catch (_: Exception) {
                pendingVerification += file
                BulkItemResult(file.path, file.name, BulkItemOutcome.Unconfirmed, "The connection was interrupted.")
            }
        }
        if (pendingVerification.isNotEmpty()) {
            val destinationNames = readNames(destination)
            pendingVerification.forEach { file ->
                val sourceNames = if (copy) null else readNames(file.path.substringBeforeLast('/', missingDelimiterValue = ""))
                results[file.path] = verifyTransfer(file, copy, destinationNames, sourceNames)
            }
        }
        val folderLabel = if (destination.isEmpty()) "All files" else destination
        return BulkOperationReport(if (copy) "copied to $folderLabel" else "moved to $folderLabel", files.mapNotNull { results[it.path] })
    }

    suspend fun delete(files: List<NextcloudFile>): BulkOperationReport =
        BulkOperationReport("deleted", files.map { file -> deleteOne(file) })

    private suspend fun deleteOne(file: NextcloudFile): BulkItemResult {
        currentCoroutineContext().ensureActive()
        blockedResult(file)?.let { return it }
        val key = deletes.keyFor(file)
        val token = deletes.inFlight.claim(key)
            ?: return BulkItemResult(file.path, file.name, BulkItemOutcome.Skipped, "Another delete of this item or its folder is running.")
        var maySend = false
        var recorded = false
        try {
            val etag = file.etag!!
            val parent = file.path.substringBeforeLast('/', missingDelimiterValue = "")
            val outcome = deleteFileWithVerifiedOutcome(
                target = file,
                expectedEtag = etag,
                delete = {
                    currentCoroutineContext().ensureActive()
                    maySend = true
                    mutate(NextcloudFileMutation.Delete(file.path, etag, file.isDirectory))
                },
                readParent = { deletes.readFolder(parent) { readFolder(parent) } },
            )
            deletes.fence.recordOutcome(key, outcome.retryBlockMessage())
            recorded = true
            return when (outcome) {
                is FileDeleteOutcome.Deleted -> BulkItemResult(file.path, file.name, BulkItemOutcome.Completed)
                is FileDeleteOutcome.NoLongerAtLocation -> if (outcome.afterUnknownResult) {
                    BulkItemResult(file.path, file.name, BulkItemOutcome.Completed, "It is no longer in its folder.")
                } else {
                    BulkItemResult(file.path, file.name, BulkItemOutcome.AlreadyDone)
                }
                is FileDeleteOutcome.NotDeleted -> BulkItemResult(file.path, file.name, BulkItemOutcome.Failed, outcome.message, retrySafe = true)
                is FileDeleteOutcome.RefreshRequired -> BulkItemResult(file.path, file.name, BulkItemOutcome.Failed, outcome.message)
                is FileDeleteOutcome.Throttled -> BulkItemResult(file.path, file.name, BulkItemOutcome.Failed, outcome.message)
                is FileDeleteOutcome.NotPermitted -> BulkItemResult(file.path, file.name, BulkItemOutcome.Failed, outcome.message)
            }
        } finally {
            if (maySend && !recorded) deletes.fence.recordOutcome(key, unconfirmedDeleteMessage(file.name))
            deletes.inFlight.release(key, token)
        }
    }

    private fun blockedResult(file: NextcloudFile): BulkItemResult? {
        deletes.writeBlock(file)?.let { block ->
            return BulkItemResult(file.path, file.name, BulkItemOutcome.Skipped, block.message)
        }
        if (file.etag.isNullOrBlank()) {
            return BulkItemResult(file.path, file.name, BulkItemOutcome.Skipped, "Refresh the folder before changing this item.")
        }
        return null
    }

    /** Names in [folder] from a fresh server listing, or null when it could not be read. */
    private suspend fun readNames(folder: String): Set<String>? = try {
        deletes.readFolder(folder) { readFolder(folder) }
            .takeIf { it.source == NextcloudFileListingSource.Network }
            ?.files?.mapTo(HashSet()) { it.name }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun verifyTransfer(file: NextcloudFile, copy: Boolean, destinationNames: Set<String>?, sourceNames: Set<String>?): BulkItemResult {
        val atDestination = destinationNames?.contains(file.name)
        val atSource = sourceNames?.contains(file.name)
        return when {
            copy && atDestination == true -> BulkItemResult(file.path, file.name, BulkItemOutcome.Completed, "Verified in the destination folder.")
            !copy && atDestination == true && atSource == false -> BulkItemResult(file.path, file.name, BulkItemOutcome.Completed, "Verified in the destination folder.")
            atDestination == false && (copy || atSource == true) ->
                BulkItemResult(file.path, file.name, BulkItemOutcome.Failed, "Verified unchanged.", retrySafe = true)
            else -> BulkItemResult(file.path, file.name, BulkItemOutcome.Unconfirmed, "Refresh both folders before trying again.")
        }
    }
}

private fun transferFailureReason(failure: NextcloudFileOperationException): String = when (failure.error) {
    NextcloudFileOperationError.Conflict -> "An item with this name already exists there, or it changed. Nothing was overwritten."
    NextcloudFileOperationError.NotFound -> "It no longer exists. Refresh the folder."
    NextcloudFileOperationError.PermissionDenied -> "You do not have permission."
    NextcloudFileOperationError.Locked -> "It is locked."
    NextcloudFileOperationError.InsufficientStorage -> "There is not enough storage."
    NextcloudFileOperationError.Throttled -> "The server asked to slow down."
    NextcloudFileOperationError.AuthenticationRequired -> "Sign in to this account again."
    NextcloudFileOperationError.ServerFailure -> "The server failed while handling it."
}

private fun Exception.isRetrySafeFileFailure(): Boolean = when (this) {
    is FileMutationNotSentException -> true
    is NextcloudFileOperationException -> error in setOf(
        NextcloudFileOperationError.ServerFailure,
        NextcloudFileOperationError.Throttled,
        NextcloudFileOperationError.Locked,
    )
    else -> false
}

private fun Exception.boundedReason(): String = (message ?: "It could not be changed.").take(200)
