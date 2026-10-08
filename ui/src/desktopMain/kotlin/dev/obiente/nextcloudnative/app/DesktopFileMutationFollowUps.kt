package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local cache and virtual-file bookkeeping after a remote file mutation.
 *
 * The remote result is authoritative and must reach the caller even when this bookkeeping stalls,
 * for example while a virtual-file provider holds its monitor across platform calls. A monitor wait
 * can be neither cancelled nor interrupted, so the work never runs on the caller's coroutine.
 *
 * One drain worker refreshes one remote path at a time in submission order. Pending refreshes are
 * coalesced per account and path, keeping only the newest refresh, and at most [maximumPending]
 * paths wait behind a stalled refresh. A path beyond that bound is reported as a local failure
 * instead of growing the queue.
 */
internal class DesktopFileMutationFollowUps(
    private val scope: CoroutineScope,
    private val completionWaitMillis: Long = DEFAULT_FILE_MUTATION_FOLLOW_UP_WAIT_MILLIS,
    private val maximumPending: Int = MAX_PENDING_FILE_MUTATION_FOLLOW_UPS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val recordDiagnostic: (accountId: String, SupportDiagnosticEventDraft) -> Unit = { _, _ -> },
) {
    private data class Key(val accountId: String, val path: String)

    private class Pending(
        var refresh: () -> Throwable?,
        val waiters: MutableList<CompletableDeferred<FileMutationLocalFollowUp>> = mutableListOf(),
    )

    private val lock = Any()
    private val pending = LinkedHashMap<Key, Pending>()
    private var draining = false

    init {
        require(completionWaitMillis > 0L)
        require(maximumPending > 0)
    }

    /** Paths waiting behind the running refresh. Bounded by [maximumPending]. */
    internal val pendingCount: Int get() = synchronized(lock) { pending.size }

    /**
     * Refreshes [paths] after the server confirmed a mutation and waits at most
     * [completionWaitMillis]. [refresh] returns the first local failure it recovered from.
     */
    suspend fun afterConfirmedMutation(
        accountId: String,
        paths: List<String>,
        refresh: (path: String) -> Throwable?,
    ): FileMutationLocalFollowUp {
        val waiters = paths.distinct().map { path ->
            val waiter = CompletableDeferred<FileMutationLocalFollowUp>()
            if (!submit(Key(accountId, path), { refresh(path) }, waiter)) {
                waiter.complete(FileMutationLocalFollowUp.Failed)
            }
            waiter
        }
        val followUp = try {
            withTimeoutOrNull(completionWaitMillis) { waiters.awaitAll() }
                ?.let { results -> results.firstOrNull { it != FileMutationLocalFollowUp.Completed } }
                ?: if (waiters.all { it.isCompleted }) FileMutationLocalFollowUp.Completed else FileMutationLocalFollowUp.StillRunning
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            FileMutationLocalFollowUp.Failed
        } finally {
            synchronized(lock) { pending.values.forEach { entry -> entry.waiters.removeAll(waiters) } }
        }
        if (followUp == FileMutationLocalFollowUp.StillRunning) {
            recordOutcome(accountId, "still-running", "FILE_MUTATION_LOCAL_FOLLOW_UP_DELAYED")
        }
        return followUp
    }

    /** Queues a refresh after an unknown remote result without delaying the caller's failure. */
    fun afterUnknownResult(accountId: String, paths: List<String>, refresh: (path: String) -> Throwable?) {
        paths.distinct().forEach { path -> submit(Key(accountId, path), { refresh(path) }, waiter = null) }
    }

    private fun submit(
        key: Key,
        refresh: () -> Throwable?,
        waiter: CompletableDeferred<FileMutationLocalFollowUp>?,
    ): Boolean {
        val accepted = synchronized(lock) {
            if (!scope.isActive) return@synchronized false
            val entry = pending[key]
            when {
                entry != null -> entry.refresh = refresh
                pending.size >= maximumPending -> return@synchronized false
                else -> pending[key] = Pending(refresh)
            }
            waiter?.let { requireNotNull(pending[key]).waiters += it }
            if (!draining) {
                draining = true
                startDrain()
            }
            true
        }
        if (!accepted) recordOutcome(key.accountId, "dropped", "FILE_MUTATION_LOCAL_FOLLOW_UP_DROPPED")
        return accepted
    }

    private fun startDrain() {
        scope.launch(dispatcher) { drain() }.invokeOnCompletion { cause ->
            if (cause == null) return@invokeOnCompletion
            // The service scope closed; nothing queued can run any more.
            val abandoned = synchronized(lock) {
                draining = false
                pending.values.flatMap { it.waiters }.also { pending.clear() }
            }
            abandoned.forEach { it.complete(FileMutationLocalFollowUp.Failed) }
        }
    }

    private fun drain() {
        while (true) {
            val (key, entry) = synchronized(lock) {
                val next = pending.entries.firstOrNull()
                if (next == null) {
                    draining = false
                    return
                }
                pending.remove(next.key)
                next.key to next.value
            }
            // Worker fault boundary: refreshes are blocking and do not suspend, so they cannot
            // observe cancellation; an unexpected failure is reported instead of stopping the drain.
            val failure = try {
                entry.refresh()
            } catch (failure: Exception) {
                failure
            }
            val result = if (failure == null) {
                FileMutationLocalFollowUp.Completed
            } else {
                recordOutcome(key.accountId, "failed", "FILE_MUTATION_LOCAL_FOLLOW_UP_FAILED", failure)
                FileMutationLocalFollowUp.Failed
            }
            synchronized(lock) { entry.waiters.toList() }.forEach { it.complete(result) }
        }
    }

    private fun recordOutcome(accountId: String, outcome: String, code: String, failure: Throwable? = null) {
        try {
            recordDiagnostic(
                accountId,
                SupportDiagnosticEventDraft(
                    severity = SupportDiagnosticSeverity.Warning,
                    component = SupportDiagnosticComponent.Files,
                    operation = "files.mutation-local-follow-up",
                    outcome = outcome,
                    code = code,
                    exception = failure?.toSupportDiagnosticExceptionDraft(),
                ),
            )
        } catch (diagnosticFailure: Exception) {
            // Diagnostics must never change the reported mutation result.
            failure?.addSuppressed(diagnosticFailure)
        }
    }
}

/**
 * How long a caller waits for local bookkeeping before reporting a server-confirmed change.
 * Ordinary cache invalidation takes milliseconds; anything slower is reported as still running.
 */
internal const val DEFAULT_FILE_MUTATION_FOLLOW_UP_WAIT_MILLIS = 3_000L

/** Distinct remote paths that may wait behind one stalled local refresh. */
internal const val MAX_PENDING_FILE_MUTATION_FOLLOW_UPS = 256
