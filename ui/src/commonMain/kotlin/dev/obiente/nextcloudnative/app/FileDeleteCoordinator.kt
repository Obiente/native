package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class FileDeleteRequest(
    val key: FileDeleteResourceKey,
    val token: Long,
    val file: NextcloudFile,
    val expectedEtag: String,
) {
    val parentPath: String get() = file.path.substringBeforeLast('/', missingDelimiterValue = "")
}

internal data class FileDeleteCompletion(val request: FileDeleteRequest, val outcome: FileDeleteOutcome)

/**
 * Owns Files deletes for one account in this process; [AccountFileWriteFence] holds the only
 * instance. A delete and its verification run under the process-level owner's scope, so leaving
 * the dialog or the Files screen, or recreating the activity, never cancels the request or
 * releases the resource without an outcome. Compositions start requests and observe state.
 * Finished outcomes wait until a Files screen consumes them. Only [close], when the account is
 * signed out or removed, cancels unfinished work.
 *
 * The resulting write blocks live in [fence], so every surface of the account sees them. An
 * outcome that must not be retried as-is (stale version, throttling, rejected credentials or
 * permission) stays recorded per resource until a server listing of its folder starts after it,
 * and until then blocks every other write to that item, its folders, and its contents. Work
 * cancelled after the request may have been sent records such a block instead of releasing it.
 */
internal class FileDeleteCoordinator(val fence: AccountFileWriteFence, parent: CoroutineScope) : FileWriteFence by fence {
    val account: NextcloudAccountId get() = fence.account
    val inFlight: FileDeleteInFlight get() = fence.inFlight
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val completions = mutableStateListOf<FileDeleteCompletion>()

    val nextCompletion: FileDeleteCompletion? get() = completions.firstOrNull()

    /**
     * Why other writes to [file] must wait: a delete of it, a parent, or a child is unverified, or
     * such a delete finished with an outcome that left the item's state unknown or stale.
     */
    fun writeBlock(file: NextcloudFile): FileWriteBlock? = fence.writeBlock(file.path)

    fun blocksWrites(file: NextcloudFile): Boolean = writeBlock(file) != null

    /** Why a write that creates or changes [path] must wait, or null when it may proceed. */
    fun writeBlockFor(path: String): FileWriteBlock? = fence.writeBlock(path)

    fun writeBlockedMessage(path: String): String? = fence.writeBlock(path)?.message

    fun refreshBlockFor(path: String): FileWriteBlock.RefreshRequired? = fence.refreshBlockFor(path)

    /**
     * Applies [completion] to [dialog]. When the outcome asks for a folder reload but the item's
     * folder is not [shownFolder], that folder is read here, through [readFolder], so the screen's
     * own reload is not the only way to lift its retry block. A failed read leaves the block; the
     * message then tells the user which folder to open.
     */
    fun applyCompletion(
        completion: FileDeleteCompletion,
        dialog: FileDeleteDialogState,
        shownFolder: String,
        read: suspend (String) -> NextcloudFileListing,
    ): FileDeleteScreenEffect {
        val effect = dialog.complete(completion)
        val folder = completion.request.parentPath
        if (effect.reloadFolder && folder != shownFolder && fence.hasRetryBlock(completion.request.key)) {
            scope.launch {
                try {
                    readFolder(folder) { read(folder) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Best effort: the block stays until the user opens or refreshes the folder.
                }
            }
        }
        return effect
    }

    /** Why another delete of [file] must wait for a fresh folder listing, or null. */
    fun retryBlockFor(file: NextcloudFile): String? = fence.retryBlockMessage(keyFor(file))

    fun keyFor(file: NextcloudFile) = FileDeleteResourceKey(account, file.path)

    /** See [AccountFileWriteFence.readFolder]: only a listing started after an outcome lifts it. */
    suspend fun readFolder(path: String, read: suspend () -> NextcloudFileListing): NextcloudFileListing =
        fence.readFolder(path, read)

    fun start(
        request: FileDeleteRequest,
        delete: suspend (NextcloudFileMutation.Delete) -> NextcloudFileMutationResult,
        readFolder: suspend (String) -> NextcloudFileListing,
    ): Job = scope.launch(start = CoroutineStart.ATOMIC) {
        // Atomic start runs the body, and with it the release below, even when cancelled before it began.
        var maySendRequest = false
        var outcomeRecorded = false
        try {
            val mutation = NextcloudFileMutation.Delete(
                request.file.path,
                request.expectedEtag,
                sourceIsDirectory = request.file.isDirectory,
            )
            val outcome = deleteFileWithVerifiedOutcome(
                target = request.file,
                expectedEtag = request.expectedEtag,
                delete = {
                    currentCoroutineContext().ensureActive()
                    maySendRequest = true
                    delete(mutation)
                },
                readParent = { readFolder(request.parentPath) },
            )
            // Reject even a non-cooperative service's completion after the account was removed.
            ensureActive()
            fence.recordOutcome(request.key, outcome.retryBlockMessage())
            outcomeRecorded = true
            completions += FileDeleteCompletion(request, outcome)
        } finally {
            // Before the request could be sent, the item is unchanged. Afterwards the server state is
            // unknown, so another write must wait for a fresh listing of the item's folder.
            if (maySendRequest && !outcomeRecorded) fence.recordOutcome(request.key, unconfirmedDeleteMessage(request.file.name))
            inFlight.release(request.key, request.token)
        }
    }

    /** The oldest unconsumed outcome for [key], for a screen that shows only that item. */
    fun completionFor(key: FileDeleteResourceKey): FileDeleteCompletion? = completions.firstOrNull { it.request.key == key }

    /** The oldest unconsumed outcome for any of [paths], for a screen that shows a list of items. */
    fun completionFor(paths: Set<String>): FileDeleteCompletion? = completions.firstOrNull { it.request.key.path in paths }

    /** Claims [completion] for one screen; false when another screen already applied it. */
    fun consume(completion: FileDeleteCompletion): Boolean = completions.remove(completion)

    fun close() = job.cancel()
}

internal fun unconfirmedDeleteMessage(name: String) =
    "Could not confirm whether $name was deleted. Refresh the folder before trying again."

/** The account's process-level delete owner. Leaving the composition neither cancels nor releases it. */
@Composable
internal fun rememberFileDeleteCoordinator(session: NextcloudSession, fences: FileWriteFenceRegistry): FileDeleteCoordinator =
    remember(session, fences) { fences.account(session.accountId).deletes }


/**
 * Main-thread owner of one Files screen's delete dialog. A closed or replaced dialog no longer
 * receives its request's outcome, unless a reopened dialog shows the same version of that item.
 */
internal class FileDeleteDialogState(private val deletes: FileDeleteCoordinator) {
    var target by mutableStateOf<NextcloudFile?>(null)
        private set
    private var localError by mutableStateOf<String?>(null)
    private var shownVersionBlocked by mutableStateOf(false)
    private var attachedToken: Long? = null

    val error: String? get() = localError ?: target?.let(deletes::retryBlockFor)

    /** A retry block survives reopening the dialog; a refresh lifts it for the item's new version. */
    val retryBlocked: Boolean get() = shownVersionBlocked || target?.let(deletes::retryBlockFor) != null

    /** True while a delete of the shown item is unverified, including one from a closed dialog. */
    val running: Boolean
        get() = target?.let { deletes.keyFor(it) in deletes.inFlight } == true

    fun open(file: NextcloudFile) = reset(file)

    /** The delete continues if it is running and still blocks other writes to the item. */
    fun dismiss() = reset(null)

    fun begin(): FileDeleteRequest? {
        val file = target ?: return null
        if (running || retryBlocked) return null
        val etag = file.etag?.takeIf(String::isNotBlank) ?: run {
            localError = "Refresh the folder before deleting this item."
            return null
        }
        val key = deletes.keyFor(file)
        // A parent or child whose delete left its state unknown must be refreshed first.
        deletes.refreshBlockFor(file.path)?.let {
            localError = it.message
            return null
        }
        val token = deletes.inFlight.claim(key) ?: run {
            localError = "Another delete in this location is still finishing. Try again when it completes."
            return null
        }
        localError = null
        attachedToken = token
        return FileDeleteRequest(key, token, file, etag)
    }

    fun complete(completion: FileDeleteCompletion): FileDeleteScreenEffect {
        val (request, outcome) = completion
        val shown = target
        val current = request.token == attachedToken ||
            (shown != null && deletes.keyFor(shown) == request.key && shown.etag == request.expectedEtag)
        if (current) {
            when (outcome) {
                is FileDeleteOutcome.Deleted, is FileDeleteOutcome.NoLongerAtLocation -> reset(null)
                is FileDeleteOutcome.NotDeleted -> localError = outcome.message
                else -> {
                    // The shown version stays blocked even after a refresh lifts the resource block.
                    localError = outcome.retryBlockMessage()
                    shownVersionBlocked = true
                }
            }
        }
        return outcome.screenEffect(request.file.name, dialogShowsResult = current)
    }

    private fun reset(file: NextcloudFile?) {
        attachedToken = null
        target = file
        localError = null
        shownVersionBlocked = false
    }
}
