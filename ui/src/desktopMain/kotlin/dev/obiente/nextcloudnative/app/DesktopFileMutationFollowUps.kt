package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * instead of growing the queue, both after a confirmed change and after an unknown result.
 */
internal class DesktopFileMutationFollowUps(
    private val scope: CoroutineScope,
    private val completionWaitMillis: Long = DEFAULT_FILE_MUTATION_FOLLOW_UP_WAIT_MILLIS,
    private val maximumPending: Int = MAX_PENDING_FILE_MUTATION_FOLLOW_UPS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val recordDiagnostic: (accountId: String, SupportDiagnosticEventDraft) -> Unit = { _, _ -> },
) {
    private data class Key(val accountId: String, val path: String)

    /** One caller's view of one path. The first result wins and stays readable after a timeout. */
    private class Waiter {
        private val value = AtomicReference<FileMutationLocalFollowUp?>(null)
        val done = CompletableDeferred<Unit>()
        val result: FileMutationLocalFollowUp? get() = value.get()

        fun finish(result: FileMutationLocalFollowUp) {
            if (value.compareAndSet(null, result)) done.complete(Unit)
        }
    }

    private class Pending(var refresh: () -> Throwable?, val waiters: MutableList<Waiter> = mutableListOf())

    private val lock = Any()
    private val pending = LinkedHashMap<Key, Pending>()
    private var running: Pair<Key, Pending>? = null
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
            Waiter().also { waiter ->
                if (!submit(Key(accountId, path), { refresh(path) }, waiter)) waiter.finish(FileMutationLocalFollowUp.Failed)
            }
        }
        try {
            withTimeoutOrNull(completionWaitMillis) { waiters.forEach { it.done.await() } }
        } finally {
            synchronized(lock) { pending.values.forEach { entry -> entry.waiters.removeAll(waiters) } }
        }
        // A failed or dropped path is known now; it must not be hidden behind one still running.
        val results = waiters.map(Waiter::result)
        val followUp = when {
            FileMutationLocalFollowUp.Failed in results -> FileMutationLocalFollowUp.Failed
            results.all { it == FileMutationLocalFollowUp.Completed } -> FileMutationLocalFollowUp.Completed
            else -> FileMutationLocalFollowUp.StillRunning
        }
        if (followUp == FileMutationLocalFollowUp.StillRunning) {
            recordOutcome(accountId, "still-running", "FILE_MUTATION_LOCAL_FOLLOW_UP_DELAYED")
        }
        return followUp
    }

    /**
     * Queues a refresh after an unknown remote result without delaying the caller's failure.
     * Returns false when a path could not be queued, so the caller must not imply local recovery.
     */
    fun afterUnknownResult(accountId: String, paths: List<String>, refresh: (path: String) -> Throwable?): Boolean =
        paths.distinct().map { path -> submit(Key(accountId, path), { refresh(path) }, waiter = null) }.all { it }

    /**
     * Runs [mutation], which reports an interrupted exchange through its callback. When the
     * reconciliation for that unknown result cannot be queued, the failure says so explicitly.
     */
    inline fun <T> reconcilingUnknownResult(
        accountId: String,
        paths: List<String>,
        noinline refresh: (path: String) -> Throwable?,
        mutation: (onUnknownResult: () -> Unit) -> T,
    ): T {
        var queued = true
        try {
            return mutation { queued = afterUnknownResult(accountId, paths, refresh) }
        } catch (unknown: DesktopFileSyncAmbiguousMutationException) {
            if (queued) throw unknown
            throw DesktopFileMutationReconciliationDroppedException(unknown)
        }
    }

    /**
     * Drops [accountId]'s queued refreshes at sign-out or account removal; their waiters and those
     * of its running refresh see a local failure. A running refresh cannot be interrupted, and its
     * late completion no longer changes that result. Every refresh also carries the file cache
     * incarnation it was created under, which teardown retires, so it cannot touch or recreate the
     * removed account's state.
     */
    fun abandonAccount(accountId: String) {
        val abandoned = synchronized(lock) {
            val keys = pending.keys.filter { it.accountId == accountId }
            val current = running?.takeIf { (key, _) -> key.accountId == accountId }?.second?.waiters.orEmpty()
            current.toList() + keys.flatMap { key -> requireNotNull(pending.remove(key)).waiters }
        }
        abandoned.forEach { it.finish(FileMutationLocalFollowUp.Failed) }
    }

    private fun submit(
        key: Key,
        refresh: () -> Throwable?,
        waiter: Waiter?,
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
        scope.launch(dispatcher) { drain { isActive } }.invokeOnCompletion { cause ->
            if (cause != null) abandonQueue()
        }
    }

    /** The service scope closed; nothing queued may run any more. */
    private fun abandonQueue() {
        val abandoned = synchronized(lock) {
            draining = false
            pending.values.flatMap { it.waiters }.also { pending.clear() }
        }
        abandoned.forEach { it.finish(FileMutationLocalFollowUp.Failed) }
    }

    private fun drain(active: () -> Boolean) {
        while (true) {
            // A refresh cannot observe cancellation while it blocks, so check before each one.
            if (!active()) return abandonQueue()
            val (key, entry) = synchronized(lock) {
                val next = pending.entries.firstOrNull()
                if (next == null) {
                    draining = false
                    return
                }
                pending.remove(next.key)
                (next.key to next.value).also { running = it }
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
            synchronized(lock) {
                running = null
                entry.waiters.toList()
            }.forEach { it.finish(result) }
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

/** The mutation's server result is unknown, and its local reconciliation could not be queued. */
internal class DesktopFileMutationReconciliationDroppedException(cause: IOException) : IOException(
    "The server response was interrupted, and local file status could not be scheduled for an update. " +
        "Refresh the folder before trying again.",
    cause,
)

/**
 * How long a caller waits for local bookkeeping before reporting a server-confirmed change.
 * Ordinary cache invalidation takes milliseconds; anything slower is reported as still running.
 */
internal const val DEFAULT_FILE_MUTATION_FOLLOW_UP_WAIT_MILLIS = 3_000L

/** Distinct remote paths that may wait behind one stalled local refresh. */
internal const val MAX_PENDING_FILE_MUTATION_FOLLOW_UPS = 256
