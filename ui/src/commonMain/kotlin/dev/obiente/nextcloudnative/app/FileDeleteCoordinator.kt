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
 */
internal class FileDeleteCoordinator(val account: NextcloudAccountId, parent: CoroutineScope) {
    val inFlight = FileDeleteInFlight()
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val completions = mutableStateListOf<FileDeleteCompletion>()

    val nextCompletion: FileDeleteCompletion? get() = completions.firstOrNull()

    /** Other writes to [file] must wait while a delete of it, a parent, or a child is unverified. */
    fun blocksWrites(file: NextcloudFile): Boolean = inFlight.blocks(keyFor(file))

    fun keyFor(file: NextcloudFile) = FileDeleteResourceKey(account, file.path)

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
            completions += FileDeleteCompletion(request, outcome)
        } finally {
            inFlight.release(request.key, request.token)
        }
    }

    /** Claims [completion] for one screen; false when another screen already applied it. */
    fun consume(completion: FileDeleteCompletion): Boolean = completions.remove(completion)

    fun close() = job.cancel()
}

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
    var error by mutableStateOf<String?>(null)
        private set
    var retryBlocked by mutableStateOf(false)
        private set
    private var attachedToken: Long? = null

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
            error = "Refresh the folder before deleting this item."
            return null
        }
        val key = deletes.keyFor(file)
        val token = deletes.inFlight.claim(key) ?: run {
            error = "Another delete in this location is still finishing. Try again when it completes."
            return null
        }
        error = null
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
                is FileDeleteOutcome.NotDeleted -> error = outcome.message
                is FileDeleteOutcome.RefreshRequired -> blockRetry(outcome.message)
                is FileDeleteOutcome.Throttled -> blockRetry(outcome.message)
            }
        }
        return outcome.screenEffect(request.file.name, dialogShowsResult = current)
    }

    private fun blockRetry(message: String) {
        error = message
        retryBlocked = true
    }

    private fun reset(file: NextcloudFile?) {
        attachedToken = null
        target = file
        error = null
        retryBlocked = false
    }
}
