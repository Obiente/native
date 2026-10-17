package dev.obiente.nextcloudnative.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopVirtualFileLockOrderTest {
    @Test
    fun hydrationActivationAndRefreshHoldingProviderLockDoNotDeadlock() {
        val guard = DesktopAccountOperationGuard()
        val providerLock = Any()
        val activationEntered = CountDownLatch(1)
        val refreshAttemptingProviderLock = CountDownLatch(1)
        val refreshBlocked = AtomicBoolean(false)
        val hydrationAccepted = AtomicBoolean(false)
        val refreshAccepted = AtomicBoolean(false)

        // Post-mutation refresh: holds the provider lock while it schedules hydration.
        val refresh = thread(start = false, isDaemon = true, name = "retained-folder-refresh") {
            refreshAttemptingProviderLock.countDown()
            synchronized(providerLock) {
                refreshAccepted.set(guard.tryActivateResource { true })
            }
        }

        // Hydration scheduling: activation check, then registration under the provider lock.
        val hydration = daemonThread("hydration-activation") {
            hydrationAccepted.set(
                guard.tryActivateVirtualFileResource(
                    providerLock,
                    sessionMatches = {
                        activationEntered.countDown()
                        assertTrue(refreshAttemptingProviderLock.await(WAIT_SECONDS, TimeUnit.SECONDS))
                        // Continue only once the refresh is parked on a monitor: on the provider lock
                        // with the documented order, or on the activation monitor with the old one.
                        refreshBlocked.set(awaitBlocked(refresh))
                        true
                    },
                    register = { true },
                ),
            )
        }
        assertTrue(activationEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        refresh.start()

        hydration.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        refresh.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        assertFalse(hydration.isAlive, "Hydration activation deadlocked against the provider lock.")
        assertFalse(refresh.isAlive, "Retained-folder refresh deadlocked against the activation monitor.")
        assertTrue(refreshBlocked.get())
        assertTrue(hydrationAccepted.get())
        assertTrue(refreshAccepted.get())
    }

    @Test
    fun virtualFileActivationSkipsRegistrationDuringAccountMutation() {
        val guard = DesktopAccountOperationGuard()
        val providerLock = Any()
        val registrations = mutableListOf<String>()
        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val mutation = daemonThread("account-mutation") {
            runBlocking {
                guard.serialize {
                    mutationEntered.countDown()
                    releaseMutation.await(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        }
        assertTrue(mutationEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))

        val blocked = guard.tryActivateVirtualFileResource(
            providerLock,
            sessionMatches = { true },
            register = { registrations.add("during-mutation") },
        )
        releaseMutation.countDown()
        mutation.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        val mismatched = guard.tryActivateVirtualFileResource(
            providerLock,
            sessionMatches = { false },
            register = { registrations.add("other-session") },
        )
        val accepted = guard.tryActivateVirtualFileResource(
            providerLock,
            sessionMatches = { Thread.holdsLock(providerLock) },
            register = { registrations.add("idle") },
        )

        assertFalse(mutation.isAlive)
        assertFalse(blocked)
        assertFalse(mismatched)
        assertTrue(accepted)
        assertEquals(listOf("idle"), registrations)
    }

    private fun daemonThread(name: String, block: () -> Unit): Thread =
        thread(isDaemon = true, name = name, block = block)

    private fun awaitBlocked(target: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (target.state == Thread.State.BLOCKED) return true
            Thread.sleep(1)
        }
        return false
    }

    private companion object {
        const val WAIT_SECONDS = 5L
    }
}
