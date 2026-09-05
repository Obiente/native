package dev.obiente.nextcloudnative.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxVirtualMutationGateTest {
    @Test
    fun `quiescence blocks new mutations and drains an active callback`() {
        val gate = LinuxVirtualMutationGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val mutation = workers.submit {
                gate.begin()
                try {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                } finally {
                    gate.end()
                }
            }
            check(entered.await(5, TimeUnit.SECONDS))
            val quiescence = workers.submit<Boolean> { gate.tryQuiesce { true } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (gate.isAcceptingNewOperations() && System.nanoTime() < deadline) Thread.yield()

            assertFalse(gate.isAcceptingNewOperations())
            assertFailsWith<LinuxVirtualFileSystemException> { gate.begin() }
            assertFalse(quiescence.isDone)
            release.countDown()
            mutation.get(5, TimeUnit.SECONDS)
            assertTrue(quiescence.get(5, TimeUnit.SECONDS))

            gate.resume()
            gate.begin()
            gate.end()
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `failed quiescence reopens automatically so an unreleased writer can close`() {
        var hasOpenWriteHandle = true
        val lifecycle = LinuxVirtualWriteLifecycle(
            hasOpenWriteHandles = { hasOpenWriteHandle },
            hasPendingCreatedFiles = { false },
        )

        assertFalse(lifecycle.tryQuiesce())
        assertTrue(lifecycle.beginRelease())
        hasOpenWriteHandle = false
        lifecycle.endOperation()
        assertTrue(lifecycle.tryQuiesce())
        assertFailsWith<LinuxVirtualFileSystemException> { lifecycle.beginMutation() }
        lifecycle.resume()
    }

    @Test
    fun `quiescence drains final pending file close through a read alias release`() {
        val closeStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        var hasOpenWriteHandle = true
        var hasPendingCreatedFile = true
        val lifecycle = LinuxVirtualWriteLifecycle(
            hasOpenWriteHandles = { hasOpenWriteHandle },
            hasPendingCreatedFiles = { hasPendingCreatedFile },
        )
        val workers = Executors.newFixedThreadPool(2)
        try {
            val release = workers.submit {
                check(lifecycle.beginRelease())
                try {
                    closeStarted.countDown()
                    check(allowClose.await(5, TimeUnit.SECONDS))
                    hasOpenWriteHandle = false
                    hasPendingCreatedFile = false
                } finally {
                    lifecycle.endOperation()
                }
            }
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            val quiescence = workers.submit<Boolean> { lifecycle.tryQuiesce() }
            assertFalse(quiescence.isDone)

            allowClose.countDown()
            release.get(5, TimeUnit.SECONDS)
            assertTrue(quiescence.get(5, TimeUnit.SECONDS))
            assertFailsWith<LinuxVirtualFileSystemException> { lifecycle.beginMutation() }
            lifecycle.resume()
        } finally {
            allowClose.countDown()
            workers.shutdownNow()
        }
    }
}
