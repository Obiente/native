package dev.obiente.nextcloudnative.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaImageDecoderTest {
    @Test
    fun `cancelled queued decode never allocates a native image`() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val owner = MediaImageDecoder(dispatcher, maximumConcurrentDecodes = 1)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                owner.decode { started.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            }
            var staleDecodes = 0
            val stale = async(start = CoroutineStart.UNDISPATCHED) {
                owner.decode { staleDecodes += 1 }
            }
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS))
                stale.cancelAndJoin()
            } finally { release.countDown() }
            first.await()
            assertEquals(0, staleDecodes)
        }
    }

    @Test
    fun `decoder uses worker threads and bounds concurrent native allocations`() = runBlocking {
        Executors.newFixedThreadPool(4).asCoroutineDispatcher().use { dispatcher ->
            val owner = MediaImageDecoder(dispatcher, maximumConcurrentDecodes = 2)
            val caller = Thread.currentThread()
            val started = CountDownLatch(2)
            val release = CountDownLatch(1)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val work = List(8) {
                async {
                    owner.decode {
                        assertTrue(Thread.currentThread() !== caller)
                        val concurrent = active.incrementAndGet()
                        maximum.accumulateAndGet(concurrent, ::maxOf)
                        started.countDown()
                        try {
                            assertTrue(release.await(10, TimeUnit.SECONDS))
                        } finally { active.decrementAndGet() }
                    }
                }
            }
            try {
                kotlinx.coroutines.withContext(dispatcher) {
                    assertTrue(started.await(10, TimeUnit.SECONDS))
                    assertEquals(2, active.get())
                }
            } finally { release.countDown() }
            work.awaitAll()
            assertEquals(2, maximum.get())
        }
    }
}
