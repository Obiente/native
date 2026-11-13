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
            val destinationEntries = readEntries(destination)
            pendingVerification.forEach { file ->
                val sourceEntries = if (copy) null else readEntries(file.path.substringBeforeLast('/', missingDelimiterValue = ""))
                results[file.path] = verifyTransfer(file, copy, destinationEntries, sourceEntries)
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

    /** Entries in [folder] by name from a fresh server listing, or null when it could not be read. */
    private suspend fun readEntries(folder: String): Map<String, NextcloudFile>? = try {
        deletes.readFolder(folder) { readFolder(folder) }
            .takeIf { it.source == NextcloudFileListingSource.Network }
            ?.files?.associateBy { it.name }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun verifyTransfer(
        file: NextcloudFile,
        copy: Boolean,
        destinationEntries: Map<String, NextcloudFile>?,
        sourceEntries: Map<String, NextcloudFile>?,
    ): BulkItemResult {
        val destinationEntry = destinationEntries?.get(file.name)
        val atDestination = destinationEntries?.let { destinationEntry != null }
        val atSource = sourceEntries?.containsKey(file.name)
        return when {
            // A copy never overwrites, so an entry with this name may have been there before the
            // request. Only an entry that matches the source counts as the copy.
            copy && destinationEntry != null -> when (copyEvidence(file, destinationEntry)) {
                CopyEvidence.Matches -> BulkItemResult(file.path, file.name, BulkItemOutcome.Completed, "Verified in the destination folder.")
                CopyEvidence.Different -> BulkItemResult(
                    file.path,
                    file.name,
                    BulkItemOutcome.Failed,
                    "A different item with this name is already in the destination folder.",
                    retrySafe = false,
                )
                CopyEvidence.Inconclusive -> BulkItemResult(file.path, file.name, BulkItemOutcome.Unconfirmed, "Refresh both folders before trying again.")
            }
            !copy && atDestination == true && atSource == false -> BulkItemResult(file.path, file.name, BulkItemOutcome.Completed, "Verified in the destination folder.")
            atDestination == false && (copy || atSource == true) ->
                BulkItemResult(file.path, file.name, BulkItemOutcome.Failed, "Verified unchanged.", retrySafe = true)
            else -> BulkItemResult(file.path, file.name, BulkItemOutcome.Unconfirmed, "Refresh both folders before trying again.")
        }
    }
}

private enum class CopyEvidence { Matches, Different, Inconclusive }

/**
 * Compares a destination entry with the copied source. Only a content checksum both entries carry
 * proves that the destination holds the copy: a folder, or a file that merely has the same size
 * and type, cannot be told apart from an item that was already there, so it stays inconclusive.
 */
private fun copyEvidence(source: NextcloudFile, destination: NextcloudFile): CopyEvidence {
    if (source.isDirectory != destination.isDirectory) return CopyEvidence.Different
    if (source.isDirectory) return CopyEvidence.Inconclusive
    val sourceSize = source.size ?: return CopyEvidence.Inconclusive
    val destinationSize = destination.size ?: return CopyEvidence.Inconclusive
    if (sourceSize != destinationSize) return CopyEvidence.Different
    val sourceType = source.mimeType
    val destinationType = destination.mimeType
    if (sourceType != null && destinationType != null && !sourceType.equals(destinationType, ignoreCase = true)) {
        return CopyEvidence.Different
    }
    val sourceSums = source.checksums.byAlgorithm()
    val destinationSums = destination.checksums.byAlgorithm()
    val shared = sourceSums.keys.intersect(destinationSums.keys)
    return when {
        shared.isEmpty() -> CopyEvidence.Inconclusive
        shared.all { algorithm -> sourceSums[algorithm] == destinationSums[algorithm] } -> CopyEvidence.Matches
        else -> CopyEvidence.Different
    }
}

/** DAV checksums such as `SHA256:<hex>`, keyed by algorithm, compared without regard to case. */
private fun List<String>.byAlgorithm(): Map<String, String> = mapNotNull { checksum ->
    val separator = checksum.indexOf(':')
    if (separator <= 0 || separator == checksum.lastIndex) return@mapNotNull null
    checksum.substring(0, separator).trim().lowercase() to checksum.substring(separator + 1).trim().lowercase()
}.toMap()

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
