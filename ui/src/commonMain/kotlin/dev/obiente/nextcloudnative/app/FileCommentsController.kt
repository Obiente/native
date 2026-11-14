package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class FileCommentsState(
    /** Newest first. Null until the first page loads. */
    val comments: List<NextcloudFileComment>? = null,
    val mayHaveMore: Boolean = false,
    /** Server entries read so far, including hidden ones; the offset of the next page. */
    val serverOffset: Int = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** Comments are not available for this file (comments app disabled or no access). */
    val unavailable: Boolean = false,
    val failure: FilesDavFailureKind? = null,
    val posting: Boolean = false,
    val pendingIds: Set<Long> = emptySet(),
    /** A post whose result is unknown and could not be verified; posting stays off until a refresh. */
    val postUnconfirmed: Boolean = false,
    val unconfirmedIds: Set<Long> = emptySet(),
    val confirmDeleteId: Long? = null,
    val notice: String? = null,
) {
    val canPost: Boolean get() = comments != null && !unavailable && !posting && !postUnconfirmed

    fun canChange(comment: NextcloudFileComment, userId: String): Boolean =
        comment.isAuthoredBy(userId) && comment.id !in pendingIds && comment.id !in unconfirmedIds
}

/**
 * Owns the comment thread of one file for one account. Writes are never retried. A post with an
 * unknown result is checked against a fresh first page: a matching comment by this user that
 * was not listed before counts as posted; otherwise the draft is kept for the user to decide.
 */
class FileCommentsController(
    private val scope: CoroutineScope,
    private val userId: String,
    private val fileId: Long,
    private val execute: suspend (FilesDavRequest) -> NextcloudApiResponse,
    /** Without a store, a post with an unknown result is remembered only by this controller. */
    private val postAttempts: FileCommentPostAttemptStore? = null,
) {
    var state by mutableStateOf(FileCommentsState())
        private set

    private var loadJob: Job? = null
    private var readSequence = 0L
    private var appliedSequence = 0L

    /** The post found on record when this thread opened; its result was never learned. */
    private var restoredPost: String? = null

    fun start() {
        if (loadJob != null || state.comments != null) return
        val store = postAttempts
        if (store == null) {
            refresh()
            return
        }
        state = state.copy(loading = true)
        loadJob = scope.launch {
            try {
                restoredPost = store.load()
                if (restoredPost != null) state = state.copy(postUnconfirmed = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Persistence fault boundary: an unreadable record may be a comment already posted.
                state = state.copy(
                    postUnconfirmed = true,
                    notice = "An earlier comment saved on this device could not be read. Check the comments, then refresh before posting.",
                )
            }
            load(byRequest = false)
        }
    }

    /** Reads the thread again. Asking for it is also the decision to post again after an unknown result. */
    fun refresh() {
        loadJob?.cancel()
        state = state.copy(loading = true)
        loadJob = scope.launch { load(byRequest = true) }
    }

    private suspend fun load(byRequest: Boolean) {
        try {
            val read = readFirstPage()
            state = state.copy(failure = (read as? CommentsRead.Failed)?.kind)
            if (read is CommentsRead.Loaded) {
                val restored = restoredPost
                val found = restored != null && read.page.comments.any { it.isAuthoredBy(userId) && it.message.trim() == restored }
                if (byRequest || restored == null || found) {
                    restoredPost = null
                    state = state.copy(postUnconfirmed = false, unconfirmedIds = emptySet())
                    settlePost()
                } else {
                    // Opening the thread is not that decision: the earlier post stays unresolved.
                    state = state.copy(
                        unconfirmedIds = emptySet(),
                        notice = "An earlier comment may already have been posted. Check the comments, then refresh before posting again.",
                    )
                }
                if (read.page.comments.any(NextcloudFileComment::unread)) markRead()
            }
        } finally {
            state = state.copy(loading = false)
        }
    }

    /** Makes the post durable before its request can leave the device, or reports that it could not. */
    private suspend fun recordPost(message: String): Boolean {
        val store = postAttempts ?: return true
        return try {
            store.save(message)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    /** The post was confirmed, refused, or shown not to exist; its record is dropped. */
    private suspend fun settlePost() {
        val store = postAttempts ?: return
        try {
            store.clear()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A record left behind only asks for one more refresh; it never posts anything.
        }
    }

    fun loadMore() {
        if (state.comments == null) return
        if (!state.mayHaveMore || state.loadingMore) return
        val offset = state.serverOffset
        // The first page this offset continues. A refresh replaces it, and with it this offset.
        val firstPage = appliedSequence
        state = state.copy(loadingMore = true)
        scope.launch {
            try {
                when (val read = read(fileCommentsListRequest(fileId, offset = offset))) {
                    is CommentsRead.Loaded -> if (firstPage == appliedSequence) {
                        val known = state.comments.orEmpty().mapTo(HashSet()) { it.id }
                        state = state.copy(
                            comments = state.comments.orEmpty() + read.page.comments.filterNot { it.id in known },
                            mayHaveMore = read.page.mayHaveMore,
                            serverOffset = offset + read.page.serverEntries,
                        )
                    }
                    is CommentsRead.Failed -> state = state.copy(notice = "Couldn't load older comments. ${commentFailureReason(read.kind)}")
                }
            } finally {
                state = state.copy(loadingMore = false)
            }
        }
    }

    /** Posts [draft]; [onPosted] runs only after the server confirmed or a read verified it. */
    fun post(draft: String, onPosted: () -> Unit) {
        if (!state.canPost || fileCommentDraftProblem(draft) != null) return
        val message = draft.trim()
        val knownIds = state.comments.orEmpty().mapTo(HashSet()) { it.id }
        state = state.copy(posting = true, notice = null)
        scope.launch {
            if (!recordPost(message)) {
                state = state.copy(posting = false, notice = "This device could not keep a record of the comment, so it was not posted.")
                return@launch
            }
            val outcome = sendFilesDavWrite(execute, fileCommentCreateRequest(fileId, message))
            val notice = when (outcome) {
                is FilesDavWriteOutcome.Confirmed -> {
                    settlePost()
                    readFirstPage()
                    onPosted()
                    null
                }
                is FilesDavWriteOutcome.Rejected -> {
                    settlePost()
                    "Couldn't post the comment. ${commentFailureReason(outcome.kind)}"
                }
                FilesDavWriteOutcome.Unknown -> when (val read = readFirstPage()) {
                    is CommentsRead.Loaded -> if (read.page.comments.any { it.id !in knownIds && it.isAuthoredBy(userId) && it.message.trim() == message }) {
                        settlePost()
                        onPosted()
                        null
                    } else if (read.page.mayHaveMore || read.page.skippedEntries > 0) {
                        // The comment may be beyond this page or among entries that could not be read.
                        state = state.copy(postUnconfirmed = true)
                        "Couldn't confirm whether the comment was posted. Refresh before sending it again."
                    } else {
                        settlePost()
                        "The comment was not posted. Your draft is kept; you can send it again."
                    }
                    is CommentsRead.Failed -> {
                        state = state.copy(postUnconfirmed = true)
                        "Couldn't confirm whether the comment was posted. Refresh before sending it again."
                    }
                }
            }
            state = state.copy(posting = false, notice = notice)
        }
    }

    /** Saves an edit; [onSaved] runs only after the server applied it. */
    fun edit(comment: NextcloudFileComment, draft: String, onSaved: () -> Unit) {
        if (!state.canChange(comment, userId) || fileCommentDraftProblem(draft) != null) return
        val message = draft.trim()
        if (message == comment.message.trim()) {
            onSaved()
            return
        }
        state = state.copy(pendingIds = state.pendingIds + comment.id, notice = null)
        scope.launch {
            val outcome = sendFilesDavWrite(execute, fileCommentEditRequest(fileId, comment.id, message))
            val notice = when {
                outcome is FilesDavWriteOutcome.Confirmed && filesDavPropertyUpdateApplied(outcome.response) -> {
                    // The saved text is shown as written; the next read brings back mention names.
                    replace(comment.copy(message = message, displayMessage = message))
                    onSaved()
                    null
                }
                outcome is FilesDavWriteOutcome.Confirmed -> "The server did not save the edit."
                outcome is FilesDavWriteOutcome.Rejected -> "Couldn't save the edit. ${commentFailureReason(outcome.kind)}"
                else -> {
                    // Editing to the same text is idempotent, but the result is still verified before reuse.
                    when (val read = readFirstPage()) {
                        is CommentsRead.Loaded -> if (read.page.comments.any { it.id == comment.id && it.message.trim() == message }) {
                            onSaved()
                            null
                        } else {
                            "The edit was not saved. You can try again."
                        }
                        is CommentsRead.Failed -> {
                            state = state.copy(unconfirmedIds = state.unconfirmedIds + comment.id)
                            "Couldn't confirm whether the edit was saved. Refresh before trying again."
                        }
                    }
                }
            }
            state = state.copy(pendingIds = state.pendingIds - comment.id, notice = notice)
        }
    }

    fun requestDelete(comment: NextcloudFileComment) {
        if (state.canChange(comment, userId)) state = state.copy(confirmDeleteId = comment.id)
    }

    fun cancelDelete() {
        state = state.copy(confirmDeleteId = null)
    }

    fun confirmDelete() {
        val id = state.confirmDeleteId ?: return
        state = state.copy(confirmDeleteId = null)
        val comment = state.comments?.firstOrNull { it.id == id }?.takeIf { state.canChange(it, userId) } ?: return
        state = state.copy(pendingIds = state.pendingIds + id, notice = null)
        scope.launch {
            val outcome = sendFilesDavWrite(execute, fileCommentDeleteRequest(fileId, comment.id))
            val notice = when {
                outcome is FilesDavWriteOutcome.Confirmed ||
                    (outcome as? FilesDavWriteOutcome.Rejected)?.kind == FilesDavFailureKind.NotFound -> {
                    state = state.copy(comments = state.comments?.filterNot { it.id == id })
                    null
                }
                outcome is FilesDavWriteOutcome.Rejected -> "Couldn't delete the comment. ${commentFailureReason(outcome.kind)}"
                else -> when (val read = readFirstPage()) {
                    is CommentsRead.Loaded -> when {
                        read.page.comments.any { it.id == id } -> "The comment was not deleted. You can try again."
                        // An older comment is not on the first page whether or not it still exists.
                        read.page.mayHaveMore -> {
                            state = state.copy(unconfirmedIds = state.unconfirmedIds + id)
                            "Couldn't confirm whether the comment was deleted. Load older comments to check before trying again."
                        }
                        else -> null
                    }
                    is CommentsRead.Failed -> {
                        state = state.copy(unconfirmedIds = state.unconfirmedIds + id)
                        "Couldn't confirm whether the comment was deleted. Refresh before trying again."
                    }
                }
            }
            state = state.copy(pendingIds = state.pendingIds - id, notice = notice)
        }
    }

    fun dismissNotice() {
        state = state.copy(notice = null)
    }

    private suspend fun markRead() {
        // Best effort: an unread marker that fails to update changes nothing else.
        sendFilesDavWrite(execute, fileCommentsMarkReadRequest(fileId))
    }

    private fun replace(comment: NextcloudFileComment) {
        state = state.copy(comments = state.comments?.map { if (it.id == comment.id) comment else it })
    }

    private suspend fun readFirstPage(): CommentsRead {
        val sequence = ++readSequence
        val read = read(fileCommentsListRequest(fileId, offset = 0))
        if (read is CommentsRead.Loaded && sequence > appliedSequence) {
            appliedSequence = sequence
            state = state.copy(
                comments = read.page.comments,
                mayHaveMore = read.page.mayHaveMore,
                serverOffset = read.page.serverEntries,
                unavailable = false,
                failure = null,
            )
        } else if (read is CommentsRead.Failed && read.kind in UNAVAILABLE_KINDS && state.comments == null) {
            state = state.copy(unavailable = true, comments = emptyList())
        }
        return read
    }

    private suspend fun read(request: FilesDavRequest): CommentsRead = try {
        val response = execute(request)
        filesDavFailureForStatus(response.status)?.let { CommentsRead.Failed(it) }
            ?: CommentsRead.Loaded(parseFileCommentsPage(response, fileId, FILE_COMMENTS_PAGE_SIZE))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: FilesDavProtocolException) {
        CommentsRead.Failed(FilesDavFailureKind.Malformed)
    } catch (_: NextcloudResponseTooLargeException) {
        CommentsRead.Failed(FilesDavFailureKind.Oversized)
    } catch (_: Exception) {
        CommentsRead.Failed(FilesDavFailureKind.Offline)
    }
}

private sealed interface CommentsRead {
    data class Loaded(val page: FileCommentsPage) : CommentsRead
    data class Failed(val kind: FilesDavFailureKind) : CommentsRead
}

private val UNAVAILABLE_KINDS = setOf(FilesDavFailureKind.NotFound, FilesDavFailureKind.Unavailable)

internal fun commentFailureReason(kind: FilesDavFailureKind): String = when (kind) {
    FilesDavFailureKind.Unauthorized -> "Sign in to this account again."
    FilesDavFailureKind.Forbidden -> "Only the author can change a comment, and some files do not allow comments."
    FilesDavFailureKind.NotFound -> "The file or comment no longer exists."
    FilesDavFailureKind.Throttled -> "The server asked to slow down. Wait a moment."
    FilesDavFailureKind.Offline -> "Check your connection and try again."
    FilesDavFailureKind.Malformed, FilesDavFailureKind.Oversized -> "The server sent an unexpected response."
    FilesDavFailureKind.Unavailable -> "Comments are not available on this server."
    else -> "The server could not complete it. Try again later."
}
