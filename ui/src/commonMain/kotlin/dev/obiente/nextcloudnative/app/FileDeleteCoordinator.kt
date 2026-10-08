package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** One remote resource in one account. */
internal data class FileDeleteResourceKey(val account: NextcloudAccountId, val path: String) {
    val parentPath: String get() = path.substringBeforeLast('/', missingDelimiterValue = "")

    fun overlaps(other: FileDeleteResourceKey): Boolean = account == other.account && (
        path == other.path || path.startsWith("${other.path}/") || other.path.startsWith("$path/")
        )
}

/** Account-scoped Files deletes that have not reached a verified outcome. */
internal class FileDeleteInFlight {
    private val pending = mutableStateMapOf<FileDeleteResourceKey, Long>()
    private var nextToken = 0L

    operator fun contains(key: FileDeleteResourceKey): Boolean = key in pending

    /** True when an unverified delete targets [key], one of its folders, or one of its children. */
    fun blocks(key: FileDeleteResourceKey): Boolean = pending.keys.any { it.overlaps(key) }

    fun claim(key: FileDeleteResourceKey): Long? {
        if (blocks(key)) return null
        return (++nextToken).also { pending[key] = it }
    }

    fun release(key: FileDeleteResourceKey, token: Long) {
        if (pending[key] == token) pending.remove(key)
    }
}

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
 * Owns Files deletes for one account session. A delete and its verification outlive the dialog and
 * the Files screen, so leaving either never cancels the request or releases the resource without
 * an outcome. Finished outcomes wait until a Files screen consumes them. Only [close], at account
 * session teardown, cancels unfinished work.
 *
 * An outcome that must not be retried as-is (stale version, throttling, rejected credentials or
 * permission) stays recorded per resource until a server listing of its folder starts after it.
 */
internal class FileDeleteCoordinator(val account: NextcloudAccountId, parent: CoroutineScope) {
    private class RetryBlock(val message: String, val recordedAt: Long)

    val inFlight = FileDeleteInFlight()
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val completions = mutableStateListOf<FileDeleteCompletion>()
    private val retryBlocks = mutableStateMapOf<FileDeleteResourceKey, RetryBlock>()
    private var sequence = 0L

    val nextCompletion: FileDeleteCompletion? get() = completions.firstOrNull()

    /** Other writes to [file] must wait while a delete of it, a parent, or a child is unverified. */
    fun blocksWrites(file: NextcloudFile): Boolean = inFlight.blocks(keyFor(file))

    /** Why a write that creates or changes [path] must wait, or null when it may proceed. */
    fun writeBlockedMessage(path: String): String? =
        WRITE_BLOCKED_BY_DELETE.takeIf { inFlight.blocks(FileDeleteResourceKey(account, path)) }

    /** Why another delete of [file] must wait for a fresh folder listing, or null. */
    fun retryBlockFor(file: NextcloudFile): String? = retryBlocks[keyFor(file)]?.message

    fun keyFor(file: NextcloudFile) = FileDeleteResourceKey(account, file.path)

    /**
     * Reads the folder at [path]. A server listing lifts the retry blocks of its items that were
     * recorded before the read started, never ones recorded while it ran.
     */
    suspend fun readFolder(path: String, read: suspend () -> NextcloudFileListing): NextcloudFileListing {
        val startedAfter = sequence
        val listing = read()
        if (listing.source == NextcloudFileListingSource.Network) {
            retryBlocks.filter { (key, block) -> key.parentPath == path && block.recordedAt <= startedAfter }
                .keys.forEach(retryBlocks::remove)
        }
        return listing
    }

    fun start(
        request: FileDeleteRequest,
        delete: suspend (NextcloudFileMutation.Delete) -> NextcloudFileMutationResult,
        readFolder: suspend (String) -> NextcloudFileListing,
    ): Job = scope.launch {
        try {
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
            // Reject even a non-cooperative service's completion after session teardown.
            ensureActive()
            when (val message = outcome.retryBlockMessage()) {
                null -> retryBlocks.remove(request.key)
                else -> retryBlocks[request.key] = RetryBlock(message, ++sequence)
            }
            completions += FileDeleteCompletion(request, outcome)
        } finally {
            inFlight.release(request.key, request.token)
        }
    }

    /** Claims [completion] for one screen; false when another screen already applied it. */
    fun consume(completion: FileDeleteCompletion): Boolean = completions.remove(completion)

    fun close() = job.cancel()
}

internal const val WRITE_BLOCKED_BY_DELETE = "Wait until the delete of this item or its folder finishes."

@Composable
internal fun rememberFileDeleteCoordinator(session: NextcloudSession): FileDeleteCoordinator {
    val parent = rememberCoroutineScope()
    val coordinator = remember(session, parent) { FileDeleteCoordinator(session.accountId, parent) }
    DisposableEffect(coordinator) { onDispose(coordinator::close) }
    return coordinator
}

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
