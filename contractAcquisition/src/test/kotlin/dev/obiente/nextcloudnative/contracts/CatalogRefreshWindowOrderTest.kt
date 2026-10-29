package dev.obiente.nextcloudnative.contracts

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Overlapping explicit refreshes of one catalog start at most one download per window. */
class CatalogRefreshWindowOrderTest {
    @Test
    fun `a refresh window cannot be moved back by a caller that read the clock earlier`() {
        val inClock = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val calls = AtomicInteger()
        val throttle = CatalogRefreshThrottle(intervalMillis = 60_000L, nowMillis = {
            if (calls.getAndIncrement() == 0) {
                inClock.countDown()
                resume.await(WAIT_SECONDS, TimeUnit.SECONDS)
                100L
            } else {
                101L
            }
        })
        var first = false
        var second = false
        val slow = thread { first = throttle.tryRefresh(URL) }
        assertTrue(inClock.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val fast = thread { second = throttle.tryRefresh(URL) }
        // Give the second caller a chance to overtake; it must not be able to finish first.
        fast.join(OVERTAKE_MILLIS)
        resume.countDown()
        slow.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        fast.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertFalse(slow.isAlive || fast.isAlive)
        assertEquals(1, listOf(first, second).count { it }, "Only one refresh may start in one window.")
    }

    private companion object {
        const val URL = "https://fixture.invalid/api/v1/platform/34.0.3/apps.json"
        const val WAIT_SECONDS = 10L
        const val OVERTAKE_MILLIS = 500L
    }
}
