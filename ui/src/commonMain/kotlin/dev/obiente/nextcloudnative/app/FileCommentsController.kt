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
) {
    var state by mutableStateOf(FileCommentsState())
        private set

    private var loadJob: Job? = null
    private var readSequence = 0L
    private var appliedSequence = 0L

    fun start() {
        if (loadJob == null && state.comments == null) refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        state = state.copy(loading = true)
        loadJob = scope.launch {
            try {
                val read = readFirstPage()
                state = state.copy(failure = (read as? CommentsRead.Failed)?.kind)
                if (read is CommentsRead.Loaded) {
                    state = state.copy(postUnconfirmed = false, unconfirmedIds = emptySet())
                    if (read.page.comments.any(NextcloudFileComment::unread)) markRead()
                }
            } finally {
                state = state.copy(loading = false)
            }
        }
    }

    fun loadMore() {
        val loaded = state.comments ?: return
        if (!state.mayHaveMore || state.loadingMore) return
        state = state.copy(loadingMore = true)
        scope.launch {
            try {
                when (val read = read(fileCommentsListRequest(fileId, offset = loaded.size))) {
                    is CommentsRead.Loaded -> {
                        val known = state.comments.orEmpty().mapTo(HashSet()) { it.id }
                        state = state.copy(
                            comments = state.comments.orEmpty() + read.page.comments.filterNot { it.id in known },
                            mayHaveMore = read.page.mayHaveMore,
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
            val outcome = sendFilesDavWrite(execute, fileCommentCreateRequest(fileId, message))
            val notice = when (outcome) {
                is FilesDavWriteOutcome.Confirmed -> {
                    readFirstPage()
                    onPosted()
                    null
                }
                is FilesDavWriteOutcome.Rejected -> "Couldn't post the comment. ${commentFailureReason(outcome.kind)}"
                FilesDavWriteOutcome.Unknown -> when (val read = readFirstPage()) {
                    is CommentsRead.Loaded -> if (read.page.comments.any { it.id !in knownIds && it.isAuthoredBy(userId) && it.message.trim() == message }) {
                        onPosted()
                        null
                    } else {
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
                    replace(comment.copy(message = message))
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
                    is CommentsRead.Loaded -> if (read.page.comments.none { it.id == id }) null else "The comment was not deleted. You can try again."
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
            state = state.copy(comments = read.page.comments, mayHaveMore = read.page.mayHaveMore, unavailable = false, failure = null)
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
