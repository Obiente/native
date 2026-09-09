package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coalesces identical authenticated reads without allowing account or request-generation reuse.
 *
 * The commit callback runs under the same lock as [invalidateAccount] and [invalidateRequest].
 * A response that began before an invalidation is therefore either committed before the cache is
 * cleared, or discarded and retried afterward. This prevents a late GET from repopulating the
 * disk cache with stale data.
 */
class DynamicApiRequestCoalescer<T> {
    private data class Key(val accountId: String, val requestIdentity: String)

    private class ReadLifetime {
        var fenced = false
    }

    private class InFlight<T>(
        val accountGeneration: Long,
        val requestGeneration: Long,
        val result: CompletableDeferred<T>,
        val lifetime: ReadLifetime,
    )

    private val mutex = Mutex()
    private val accountGenerations = mutableMapOf<String, Long>()
    private val closedAccounts = mutableSetOf<String>()
    private val activeReads = mutableMapOf<String, MutableSet<ReadLifetime>>()
    private val requestGenerations = mutableMapOf<Key, Long>()
    private val inFlight = mutableMapOf<Key, InFlight<T>>()

    suspend fun execute(
        accountId: String,
        requestIdentity: String,
        load: suspend () -> T,
        commit: (T) -> Unit = {},
    ): T {
        val lifetime = mutex.withLock {
            if (accountId in closedAccounts) throw DynamicReadAccountFencedException()
            ReadLifetime().also { activeReads.getOrPut(accountId, ::mutableSetOf).add(it) }
        }
        try {
            return executeReads(accountId, requestIdentity, load, commit, lifetime)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val reads = activeReads[accountId]
                    reads?.remove(lifetime)
                    if (reads?.isEmpty() == true) activeReads.remove(accountId)
                }
            }
        }
    }

    private suspend fun executeReads(
        accountId: String,
        requestIdentity: String,
        load: suspend () -> T,
        commit: (T) -> Unit,
        lifetime: ReadLifetime,
    ): T {
        while (true) {
            val key = Key(accountId, requestIdentity)
            var owner = false
            val entry = mutex.withLock {
                if (lifetime.fenced || accountId in closedAccounts) throw DynamicReadAccountFencedException()
                val accountGeneration = accountGenerations[accountId] ?: 0L
                inFlight[key]?.takeIf { current -> current.accountGeneration == accountGeneration } ?: InFlight<T>(
                    accountGeneration = accountGeneration,
                    requestGeneration = requestGenerations[key] ?: 0L,
                    result = CompletableDeferred<T>(),
                    lifetime = lifetime,
                ).also {
                    inFlight[key] = it
                    owner = true
                }
            }
            if (!owner) {
                try {
                    val loaded = entry.result.await()
                    return mutex.withLock {
                        if (lifetime.fenced) throw DynamicReadAccountFencedException()
                        loaded
                    }
                } catch (_: DynamicReadInvalidatedException) {
                    continue
                }
            }

            try {
                val loaded = try {
                    load()
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    val invalidation = mutex.withLock {
                        val cause = invalidationCause(accountId, key, entry)
                        inFlight.remove(key, entry)
                        entry.result.completeExceptionally(
                            if (cause == InvalidationCause.None) failure else cause.exception(),
                        )
                        cause
                    }
                    if (invalidation == InvalidationCause.Invalidated) continue
                    if (invalidation == InvalidationCause.Fenced) throw DynamicReadAccountFencedException()
                    throw failure
                }
                val invalidation = mutex.withLock {
                    val cause = invalidationCause(accountId, key, entry)
                    if (cause != InvalidationCause.None) {
                        entry.result.completeExceptionally(cause.exception())
                    } else {
                        commit(loaded)
                        entry.result.complete(loaded)
                    }
                    inFlight.remove(key, entry)
                    cause
                }
                if (invalidation == InvalidationCause.None) return loaded
                if (invalidation == InvalidationCause.Fenced) throw DynamicReadAccountFencedException()
            } catch (failure: Throwable) {
                entry.result.completeExceptionally(failure)
                throw failure
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        inFlight.remove(key, entry)
                        retireRequestGenerationIfIdle(key, entry.requestGeneration)
                    }
                }
            }
        }
    }

    suspend fun invalidateAccount(accountId: String, invalidate: () -> Unit) {
        mutex.withLock {
            accountGenerations[accountId] = (accountGenerations[accountId] ?: 0L) + 1L
            requestGenerations.keys.removeAll { it.accountId == accountId }
            invalidate()
        }
    }

    /**
     * Invalidates an account and terminates reads that entered before the fence.
     * The account remains closed until credential activation explicitly reopens it.
     */
    suspend fun fenceAccount(accountId: String, invalidate: () -> Unit) {
        mutex.withLock {
            val generation = (accountGenerations[accountId] ?: 0L) + 1L
            accountGenerations[accountId] = generation
            closedAccounts += accountId
            // Keep waiters and retry gaps fenced even after their old deduplication slot retires.
            activeReads[accountId]?.forEach { it.fenced = true }
            requestGenerations.keys.removeAll { it.accountId == accountId }
            invalidate()
        }
    }

    /** Reopens reads only after the caller has persisted the exact account credentials. */
    suspend fun activateAccount(accountId: String) {
        mutex.withLock { closedAccounts.remove(accountId) }
    }

    suspend fun invalidateRequest(
        accountId: String,
        requestIdentity: String,
        invalidate: () -> Unit,
    ) {
        val key = Key(accountId, requestIdentity)
        mutex.withLock {
            if (key in inFlight) {
                requestGenerations[key] = (requestGenerations[key] ?: 0L) + 1L
            } else {
                requestGenerations.remove(key)
            }
            invalidate()
        }
    }

    internal suspend fun retainedRequestGenerationCount(): Int =
        mutex.withLock { requestGenerations.size }

    internal suspend fun activeReadCount(): Int =
        mutex.withLock { activeReads.values.sumOf { it.size } }

    private fun retireRequestGenerationIfIdle(key: Key, generation: Long) {
        if (key !in inFlight && requestGenerations[key] == generation) {
            requestGenerations.remove(key)
        }
    }

    private fun invalidationCause(accountId: String, key: Key, entry: InFlight<T>): InvalidationCause {
        if (entry.lifetime.fenced) return InvalidationCause.Fenced
        val accountGeneration = accountGenerations[accountId] ?: 0L
        if (accountGeneration != entry.accountGeneration) {
            return InvalidationCause.Invalidated
        }
        return if ((requestGenerations[key] ?: 0L) != entry.requestGeneration) {
            InvalidationCause.Invalidated
        } else {
            InvalidationCause.None
        }
    }

    private enum class InvalidationCause {
        None,
        Invalidated,
        Fenced;

        fun exception(): Exception = when (this) {
            None -> error("A current dynamic read has no invalidation failure.")
            Invalidated -> DynamicReadInvalidatedException()
            Fenced -> DynamicReadAccountFencedException()
        }
    }
}

private class DynamicReadInvalidatedException : Exception()

internal class DynamicReadAccountFencedException : Exception("The account was removed while this read was running.")
