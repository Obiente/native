package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coalesces identical authenticated reads without allowing account or mutation-generation reuse.
 *
 * The commit callback runs under the same lock as [invalidateAccount]. A response that began
 * before a write is therefore either committed before the write clears it, or discarded and
 * retried after the write. This prevents a late GET from repopulating the disk cache with
 * pre-mutation data.
 */
class DynamicApiRequestCoalescer<T> {
    private data class Key(val accountId: String, val requestIdentity: String)

    private data class InFlight<T>(
        val generation: Long,
        val result: CompletableDeferred<T>,
    )

    private val mutex = Mutex()
    private val generations = mutableMapOf<String, Long>()
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
                    generation = generations[accountId] ?: 0L,
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
                mutex.withLock {
                    inFlight.remove(key, entry)
                    entry.result.completeExceptionally(failure)
                }
                throw failure
            }
            val accepted = mutex.withLock {
                if ((generations[accountId] ?: 0L) != entry.generation) {
                    inFlight.remove(key, entry)
                    entry.result.completeExceptionally(DynamicReadInvalidatedException())
                    false
                } else {
                    try {
                        commit(loaded)
                        inFlight.remove(key, entry)
                        entry.result.complete(loaded)
                        true
                    } catch (failure: Throwable) {
                        inFlight.remove(key, entry)
                        entry.result.completeExceptionally(failure)
                        throw failure
                    }
                }
            }
            if (accepted) return loaded
        }
    }

    suspend fun invalidateAccount(accountId: String, invalidate: () -> Unit) {
        mutex.withLock {
            generations[accountId] = (generations[accountId] ?: 0L) + 1L
            invalidate()
        }
    }
}

private class DynamicReadInvalidatedException : Exception()
