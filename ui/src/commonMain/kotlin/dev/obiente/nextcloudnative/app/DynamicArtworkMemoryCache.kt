package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Account/app-owned decoded-artwork cache.
 *
 * The owner scopes an instance with `remember(server, login, app)`. Keys therefore never contain
 * credentials or cross account boundaries. Entries are charged by decoded memory rather than
 * count, identical reads share one deferred result, and bounded negative entries suppress repeated
 * 404/invalid-image work without becoming permanent.
 */
internal class DynamicArtworkMemoryCache<T : Any>(
    private val maximumBytes: Long,
    private val sizeOf: (T) -> Long,
    private val negativeTtl: Duration = 30.seconds,
    private val maximumNegativeEntries: Int = 256,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    init {
        require(maximumBytes > 0)
        require(maximumNegativeEntries > 0)
        require(negativeTtl.isPositive())
    }

    private data class Entry<T>(val value: T, val bytes: Long)

    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry<T>>()
    private val leastRecentlyUsed = mutableListOf<String>()
    private val negative = mutableMapOf<String, TimeMark>()
    private val negativeOrder = mutableListOf<String>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<T?>>()
    private var decodedBytes = 0L

    suspend fun getOrLoad(key: String, load: suspend () -> T?): T? {
        require(key.isNotBlank() && key.length <= 2_048)
        var owner = false
        val pending = mutex.withLock {
            entries[key]?.let { entry ->
                touch(key)
                return entry.value
            }
            negative[key]?.let { failedAt ->
                if (failedAt.elapsedNow() < negativeTtl) return null
                negative.remove(key)
                negativeOrder.remove(key)
            }
            inFlight[key] ?: CompletableDeferred<T?>().also {
                inFlight[key] = it
                owner = true
            }
        }
        if (!owner) return pending.await()

        val loaded = try {
            load()
        } catch (cancelled: CancellationException) {
            mutex.withLock {
                inFlight.remove(key, pending)
                pending.completeExceptionally(cancelled)
            }
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val accepted = runCatching {
            loaded?.let { value ->
                val bytes = sizeOf(value)
                value to bytes
            }?.takeIf { (_, bytes) -> bytes in 1..maximumBytes }
        }.getOrNull()
        mutex.withLock {
            if (accepted == null) {
                rememberNegative(key)
            } else {
                put(key, accepted.first, accepted.second)
            }
            inFlight.remove(key, pending)
            pending.complete(accepted?.first)
        }
        return accepted?.first
    }

    internal suspend fun snapshot(): DynamicArtworkCacheSnapshot = mutex.withLock {
        DynamicArtworkCacheSnapshot(
            entryCount = entries.size,
            decodedBytes = decodedBytes,
            negativeCount = negative.size,
            inFlightCount = inFlight.size,
            keysLeastToMostRecent = leastRecentlyUsed.toList(),
        )
    }

    private fun put(key: String, value: T, bytes: Long) {
        entries.remove(key)?.let { previous -> decodedBytes -= previous.bytes }
        leastRecentlyUsed.remove(key)
        negative.remove(key)
        negativeOrder.remove(key)
        entries[key] = Entry(value, bytes)
        leastRecentlyUsed += key
        decodedBytes += bytes
        while (decodedBytes > maximumBytes && leastRecentlyUsed.isNotEmpty()) {
            val evictedKey = leastRecentlyUsed.removeAt(0)
            entries.remove(evictedKey)?.let { evicted -> decodedBytes -= evicted.bytes }
        }
    }

    private fun touch(key: String) {
        leastRecentlyUsed.remove(key)
        leastRecentlyUsed += key
    }

    private fun rememberNegative(key: String) {
        negative[key] = timeSource.markNow()
        negativeOrder.remove(key)
        negativeOrder += key
        while (negativeOrder.size > maximumNegativeEntries) {
            negative.remove(negativeOrder.removeAt(0))
        }
    }
}

internal data class DynamicArtworkCacheSnapshot(
    val entryCount: Int,
    val decodedBytes: Long,
    val negativeCount: Int,
    val inFlightCount: Int,
    val keysLeastToMostRecent: List<String>,
)
