package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private data class InFlight<T>(
        val accountGeneration: Long,
        val requestGeneration: Long,
        val result: CompletableDeferred<T>,
    )

    private val mutex = Mutex()
    private val accountGenerations = mutableMapOf<String, Long>()
    private val requestGenerations = mutableMapOf<Key, Long>()
    private val inFlight = mutableMapOf<Key, InFlight<T>>()

    suspend fun execute(
        accountId: String,
        requestIdentity: String,
        load: suspend () -> T,
        commit: (T) -> Unit = {},
    ): T {
        while (true) {
            val key = Key(accountId, requestIdentity)
            var owner = false
            val entry = mutex.withLock {
                inFlight[key] ?: InFlight<T>(
                    accountGeneration = accountGenerations[accountId] ?: 0L,
                    requestGeneration = requestGenerations[key] ?: 0L,
                    result = CompletableDeferred<T>(),
                ).also {
                    inFlight[key] = it
                    owner = true
                }
            }
            if (!owner) {
                try {
                    return entry.result.await()
                } catch (_: DynamicReadInvalidatedException) {
                    continue
                }
            }

            val loaded = try {
                load()
            } catch (failure: Throwable) {
                val invalidated = mutex.withLock {
                    val entryWasInvalidated =
                        (accountGenerations[accountId] ?: 0L) != entry.accountGeneration ||
                            (requestGenerations[key] ?: 0L) != entry.requestGeneration
                    inFlight.remove(key, entry)
                    if (entryWasInvalidated) {
                        entry.result.completeExceptionally(DynamicReadInvalidatedException())
                    } else {
                        entry.result.completeExceptionally(failure)
                        retireRequestGenerationIfIdle(key, entry.requestGeneration)
                    }
                    entryWasInvalidated
                }
                if (invalidated) continue
                throw failure
            }
            val accepted = mutex.withLock {
                if (
                    (accountGenerations[accountId] ?: 0L) != entry.accountGeneration ||
                    (requestGenerations[key] ?: 0L) != entry.requestGeneration
                ) {
                    inFlight.remove(key, entry)
                    entry.result.completeExceptionally(DynamicReadInvalidatedException())
                    false
                } else {
                    try {
                        commit(loaded)
                        inFlight.remove(key, entry)
                        entry.result.complete(loaded)
                        retireRequestGenerationIfIdle(key, entry.requestGeneration)
                        true
                    } catch (failure: Throwable) {
                        inFlight.remove(key, entry)
                        entry.result.completeExceptionally(failure)
                        retireRequestGenerationIfIdle(key, entry.requestGeneration)
                        throw failure
                    }
                }
            }
            if (accepted) return loaded
        }
    }

    suspend fun invalidateAccount(accountId: String, invalidate: () -> Unit) {
        mutex.withLock {
            accountGenerations[accountId] = (accountGenerations[accountId] ?: 0L) + 1L
            requestGenerations.keys.removeAll { it.accountId == accountId }
            invalidate()
        }
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

    private fun retireRequestGenerationIfIdle(key: Key, generation: Long) {
        if (key !in inFlight && requestGenerations[key] == generation) {
            requestGenerations.remove(key)
        }
    }
}

private class DynamicReadInvalidatedException : Exception()
