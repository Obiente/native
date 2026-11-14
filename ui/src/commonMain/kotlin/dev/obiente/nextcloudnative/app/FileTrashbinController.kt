package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class TrashbinAction { Restore, DeletePermanently }

/** A destructive request waiting for its separate confirmation. */
sealed interface TrashbinConfirmation {
    data class DeleteItems(val keys: Set<String>) : TrashbinConfirmation
    data object Empty : TrashbinConfirmation
}

data class FileTrashbinState(
    val availability: TrashbinAvailability = TrashbinAvailability.Unknown,
    /** Null until the first successful listing. */
    val items: List<NextcloudTrashItem>? = null,
    val truncated: Boolean = false,
    val skippedEntries: Int = 0,
    val refreshing: Boolean = false,
    val failure: FilesDavFailureKind? = null,
    val selectedKeys: Set<String> = emptySet(),
    val pendingKeys: Set<String> = emptySet(),
    val emptying: Boolean = false,
    /** Items whose last write had an unknown result; writes stay off until a later listing. */
    val unconfirmedKeys: Set<String> = emptySet(),
    val emptyUnconfirmed: Boolean = false,
    val confirmation: TrashbinConfirmation? = null,
    val report: BulkOperationReport? = null,
    val notice: String? = null,
) {
    val permanentDeleteAllowed: Boolean
        get() = (availability as? TrashbinAvailability.Available)?.permanentDeleteAllowed == true

    private val writesSettled: Boolean get() = !emptying && !emptyUnconfirmed

    fun item(key: String): NextcloudTrashItem? = items?.firstOrNull { it.key == key }

    fun canChange(item: NextcloudTrashItem): Boolean =
        availability is TrashbinAvailability.Available && writesSettled &&
            item.key !in pendingKeys && item.key !in unconfirmedKeys

    val canEmpty: Boolean
        get() = permanentDeleteAllowed && writesSettled && pendingKeys.isEmpty() &&
            unconfirmedKeys.isEmpty() && !items.isNullOrEmpty()

    val selectionActive: Boolean get() = selectedKeys.isNotEmpty()
}

/**
 * Owns one account's trash bin view: availability, listing, restore, permanent delete, and
 * emptying, including multi-item batches.
 *
 * Writes are never retried automatically. Each batch runs its items one at a time, then one fresh
 * listing verifies the postcondition (the item left the trash) for every item. An item whose
 * request had an unknown result and that the verification listing still shows, or that could
 * not be verified, stays write-blocked until a listing started after it settles its state.
 */
class FileTrashbinController(
    private val scope: CoroutineScope,
    private val userId: String,
    private val execute: suspend (FilesDavRequest) -> NextcloudApiResponse,
    private val readCapabilities: suspend () -> NextcloudApiResponse,
    /** Called after any restore was confirmed so Files listings can refresh. */
    private val onItemsRestored: (List<NextcloudTrashItem>) -> Unit = {},
) {
    var state by mutableStateOf(FileTrashbinState())
        private set

    private var readSequence = 0L
    private var appliedSequence = 0L
    private var refreshJob: Job? = null
    private val unconfirmedSince = mutableMapOf<String, Long>()
    private var emptyUnconfirmedSince: Long? = null

    fun start() {
        if (refreshJob == null && state.items == null) refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        state = state.copy(refreshing = true)
        refreshJob = scope.launch {
            try {
                val read = read()
                state = state.copy(failure = (read as? TrashRead.Failed)?.kind)
            } finally {
                state = state.copy(refreshing = false)
            }
        }
    }

    fun toggleSelection(key: String) {
        val item = state.item(key) ?: return
        val selected = state.selectedKeys
        state = state.copy(selectedKeys = if (key in selected) selected - key else selected + item.key)
    }

    fun selectAll() {
        state = state.copy(selectedKeys = state.items.orEmpty().filter(state::canChange).mapTo(LinkedHashSet()) { it.key })
    }

    fun clearSelection() {
        state = state.copy(selectedKeys = emptySet())
    }

    fun dismissReport() {
        state = state.copy(report = null, notice = null)
    }

    fun restore(keys: Set<String>) {
        val targets = keys.mapNotNull(state::item).filter(state::canChange)
        if (targets.isNotEmpty()) runBatch(TrashbinAction.Restore, targets)
    }

    fun requestDelete(keys: Set<String>) {
        if (!state.permanentDeleteAllowed) return
        val targets = keys.mapNotNull(state::item).filter(state::canChange)
        if (targets.isNotEmpty()) {
            state = state.copy(confirmation = TrashbinConfirmation.DeleteItems(targets.mapTo(LinkedHashSet()) { it.key }))
        }
    }

    fun requestEmpty() {
        if (state.canEmpty) state = state.copy(confirmation = TrashbinConfirmation.Empty)
    }

    fun cancelConfirmation() {
        state = state.copy(confirmation = null)
    }

    fun confirm() {
        val confirmation = state.confirmation ?: return
        state = state.copy(confirmation = null)
        when (confirmation) {
            is TrashbinConfirmation.DeleteItems -> {
                val targets = confirmation.keys.mapNotNull(state::item).filter(state::canChange)
                if (targets.isNotEmpty() && state.permanentDeleteAllowed) runBatch(TrashbinAction.DeletePermanently, targets)
            }
            TrashbinConfirmation.Empty -> if (state.canEmpty) performEmpty()
        }
    }

    private fun runBatch(action: TrashbinAction, targets: List<NextcloudTrashItem>) {
        val keys = targets.mapTo(LinkedHashSet()) { it.key }
        state = state.copy(pendingKeys = state.pendingKeys + keys, report = null, notice = null)
        scope.launch {
            val outcomes = LinkedHashMap<String, FilesDavWriteOutcome>()
            for (item in targets) {
                val request = when (action) {
                    TrashbinAction.Restore -> trashbinRestoreRequest(userId, item)
                    TrashbinAction.DeletePermanently -> trashbinDeleteRequest(userId, item)
                }
                outcomes[item.key] = sendFilesDavWrite(execute, request)
            }
            val verificationStart = readSequence + 1
            val verification = read()
            val stillListed = verification as? TrashRead.Loaded
            val results = targets.map { item ->
                trashItemResult(action, item, outcomes[item.key], stillListed, verificationStart)
            }
            val report = BulkOperationReport(
                verb = if (action == TrashbinAction.Restore) "restored" else "deleted permanently",
                results = results,
            )
            state = state.copy(
                pendingKeys = state.pendingKeys - keys,
                selectedKeys = state.selectedKeys - report.completed.mapTo(HashSet()) { it.itemKey },
                unconfirmedKeys = unconfirmedSince.keys.toSet(),
                report = report,
            )
            if (action == TrashbinAction.Restore) {
                val restored = targets.filter { item -> report.completed.any { it.itemKey == item.key } }
                if (restored.isNotEmpty()) onItemsRestored(restored)
            }
        }
    }

    private fun trashItemResult(
        action: TrashbinAction,
        item: NextcloudTrashItem,
        outcome: FilesDavWriteOutcome?,
        stillListed: TrashRead.Loaded?,
        verificationStart: Long,
    ): BulkItemResult {
        // A truncated listing can omit an item that is still in the trash bin, so only a complete
        // listing proves that the item left it.
        val gone = when {
            stillListed == null -> null
            item.key in stillListed.keys -> false
            stillListed.complete -> true
            else -> null
        }
        return when (outcome) {
            null -> BulkItemResult(item.key, item.name, BulkItemOutcome.Skipped, "Not attempted.", retrySafe = true)
            is FilesDavWriteOutcome.Confirmed -> if (gone == false) {
                BulkItemResult(item.key, item.name, BulkItemOutcome.Failed, "The server accepted the request but still lists the item.", retrySafe = true)
            } else {
                BulkItemResult(item.key, item.name, BulkItemOutcome.Completed)
            }
            is FilesDavWriteOutcome.Rejected -> when {
                // The item is already gone, which is the requested postcondition for a delete.
                outcome.kind == FilesDavFailureKind.NotFound && action == TrashbinAction.DeletePermanently ->
                    BulkItemResult(item.key, item.name, BulkItemOutcome.AlreadyDone)
                else -> BulkItemResult(
                    item.key,
                    item.name,
                    BulkItemOutcome.Failed,
                    trashFailureReason(action, outcome.kind),
                    retrySafe = outcome.kind.isRetrySafeForTrash(),
                )
            }
            FilesDavWriteOutcome.Unknown -> when (gone) {
                true -> BulkItemResult(item.key, item.name, BulkItemOutcome.Completed)
                // Still listed by a read that started after the request finished: nothing happened.
                false -> BulkItemResult(item.key, item.name, BulkItemOutcome.Failed, "The connection failed before the server confirmed it.", retrySafe = true)
                null -> {
                    unconfirmedSince[item.key] = verificationStart
                    BulkItemResult(item.key, item.name, BulkItemOutcome.Unconfirmed, "The connection failed and the result could not be checked.")
                }
            }
        }
    }

    private fun performEmpty() {
        state = state.copy(emptying = true, report = null, notice = null)
        scope.launch {
            val outcome = sendFilesDavWrite(execute, trashbinEmptyRequest(userId))
            val verificationStart = readSequence + 1
            val verification = read()
            val remaining = (verification as? TrashRead.Loaded)?.keys
            val notice = when (outcome) {
                is FilesDavWriteOutcome.Confirmed -> when {
                    remaining == null -> "The trash bin was emptied. Refresh to update the list."
                    remaining.isEmpty() -> "The trash bin is empty."
                    else -> "The server emptied the trash bin, but ${itemCount(remaining.size)} are still listed."
                }
                is FilesDavWriteOutcome.Rejected -> "Couldn't empty the trash bin. ${trashFailureReason(TrashbinAction.DeletePermanently, outcome.kind)}"
                FilesDavWriteOutcome.Unknown -> when {
                    remaining?.isEmpty() == true -> "The trash bin is empty."
                    remaining != null -> "The trash bin was not emptied. You can try again."
                    else -> {
                        emptyUnconfirmedSince = verificationStart
                        "Couldn't confirm whether the trash bin was emptied. Refresh before trying again."
                    }
                }
            }
            state = state.copy(emptying = false, emptyUnconfirmed = emptyUnconfirmedSince != null, notice = notice)
        }
    }

    private suspend fun read(): TrashRead {
        val sequence = ++readSequence
        if (state.availability == TrashbinAvailability.Unknown) {
            val availability = try {
                val response = readCapabilities()
                filesDavFailureForStatus(response.status)?.let { return TrashRead.Failed(it) }
                parseTrashbinAvailability(response)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: FilesDavProtocolException) {
                return TrashRead.Failed(FilesDavFailureKind.Malformed)
            } catch (_: Exception) {
                return TrashRead.Failed(FilesDavFailureKind.Offline)
            }
            state = state.copy(availability = availability)
            if (availability !is TrashbinAvailability.Available) {
                state = state.copy(items = emptyList(), selectedKeys = emptySet())
                return TrashRead.Loaded(emptySet(), complete = true)
            }
        }
        val listing = try {
            val response = execute(trashbinListRequest(userId))
            filesDavFailureForStatus(response.status)?.let { return TrashRead.Failed(it) }
            parseTrashbinListing(response, userId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FilesDavProtocolException) {
            return TrashRead.Failed(FilesDavFailureKind.Malformed)
        } catch (_: NextcloudResponseTooLargeException) {
            return TrashRead.Failed(FilesDavFailureKind.Oversized)
        } catch (_: IllegalArgumentException) {
            return TrashRead.Failed(FilesDavFailureKind.Malformed)
        } catch (_: Exception) {
            return TrashRead.Failed(FilesDavFailureKind.Offline)
        }
        val keys = listing.items.mapTo(HashSet()) { it.key }
        if (sequence > appliedSequence) {
            appliedSequence = sequence
            unconfirmedSince.entries.removeAll { (_, since) -> since <= sequence }
            if (emptyUnconfirmedSince?.let { it <= sequence } == true) emptyUnconfirmedSince = null
            state = state.copy(
                items = listing.items,
                truncated = listing.truncated,
                skippedEntries = listing.skippedEntries,
                failure = null,
                selectedKeys = state.selectedKeys.filterTo(LinkedHashSet()) { it in keys },
                unconfirmedKeys = unconfirmedSince.keys.toSet(),
                emptyUnconfirmed = emptyUnconfirmedSince != null,
            )
        }
        // An entry that could not be read may be the item being checked, so it proves nothing either.
        return TrashRead.Loaded(keys, complete = !listing.truncated && listing.skippedEntries == 0)
    }
}

private sealed interface TrashRead {
    /** [complete] is false when the server listed more items than are kept or readable, so absence proves nothing. */
    data class Loaded(val keys: Set<String>, val complete: Boolean) : TrashRead
    data class Failed(val kind: FilesDavFailureKind) : TrashRead
}

private fun FilesDavFailureKind.isRetrySafeForTrash(): Boolean = when (this) {
    FilesDavFailureKind.Offline,
    FilesDavFailureKind.Throttled,
    FilesDavFailureKind.Unavailable,
    FilesDavFailureKind.InsufficientStorage,
    FilesDavFailureKind.Server,
    -> true
    else -> false
}

internal fun trashFailureReason(action: TrashbinAction, kind: FilesDavFailureKind): String = when (kind) {
    FilesDavFailureKind.Unauthorized -> "Sign in to this account again."
    FilesDavFailureKind.Forbidden -> if (action == TrashbinAction.DeletePermanently) {
        "Your administrator does not allow deleting items from the trash bin."
    } else {
        "You do not have permission to restore this item."
    }
    FilesDavFailureKind.NotFound -> "The item is no longer in the trash bin. Refresh the list."
    FilesDavFailureKind.Conflict, FilesDavFailureKind.PreconditionFailed -> "The item changed on the server. Refresh and try again."
    FilesDavFailureKind.InsufficientStorage -> "There is not enough free storage to restore it."
    FilesDavFailureKind.Throttled -> "The server asked to slow down. Wait a moment and try again."
    FilesDavFailureKind.Unavailable -> "The trash bin is not available on this server right now."
    FilesDavFailureKind.Offline -> "Check your connection and try again."
    FilesDavFailureKind.Malformed, FilesDavFailureKind.Oversized -> "The server sent an unexpected response."
    FilesDavFailureKind.Server -> "The server could not complete it. Try again later."
}

fun trashListFailureTitle(kind: FilesDavFailureKind): String = when (kind) {
    FilesDavFailureKind.Offline -> "Can't reach your server"
    FilesDavFailureKind.Unauthorized -> "Sign-in expired"
    FilesDavFailureKind.Forbidden -> "The trash bin is not available to this account"
    FilesDavFailureKind.Oversized -> "The trash bin is too large to show"
    FilesDavFailureKind.Malformed -> "The server sent an unexpected response"
    FilesDavFailureKind.Throttled -> "The server asked to slow down"
    else -> "The trash bin could not be loaded"
}
