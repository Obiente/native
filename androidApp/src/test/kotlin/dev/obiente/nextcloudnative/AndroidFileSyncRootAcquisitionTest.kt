package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncLocalRoot
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

class AndroidFileSyncRootAcquisitionTest {
    private val root = FileSyncLocalRoot("content://example.documents/tree/folder", "Folder", "opaque-id")

    @Test
    fun acquisitionUsesIoAndResultDeliveryUsesMain() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "synthetic-io") }.asCoroutineDispatcher().use { io ->
            Executors.newSingleThreadExecutor { Thread(it, "synthetic-main") }.asCoroutineDispatcher().use { main ->
                val owner = CoroutineScope(SupervisorJob() + io)
                val ready = CompletableDeferred<CancellableContinuation<FileSyncLocalRoot?>>()
                val consumer = async(main) {
                    val result = suspendCancellableCoroutine<FileSyncLocalRoot?> { ready.complete(it) }
                    result to Thread.currentThread().name.substringBefore(" @coroutine#")
                }
                var acquiredOn = ""
                try {
                    acquireFileSyncRootForDelivery(owner, ready.await(), {
                        acquiredOn = Thread.currentThread().name.substringBefore(" @coroutine#")
                        root
                    }, { error("Delivered root must remain owned") }, main).join()
                    val result = consumer.await()
                    assertEquals(root, result.first)
                    assertEquals("synthetic-main", result.second)
                    assertEquals("synthetic-io", acquiredOn)
                } finally {
                    owner.cancel()
                }
            }
        }
    }

    @Test
    fun cancellationBeforeMainDeliveryReclaimsOnIo() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "synthetic-cleanup-io") }.asCoroutineDispatcher().use { io ->
            val owner = CoroutineScope(SupervisorJob() + io)
            val main = PausedDispatcher()
            lateinit var continuation: CancellableContinuation<FileSyncLocalRoot?>
            val consumer = async(start = CoroutineStart.UNDISPATCHED) {
                suspendCancellableCoroutine<FileSyncLocalRoot?> { continuation = it }
            }
            val cleaned = CompletableDeferred<Pair<String, String>>()
            try {
                val acquisition = acquireFileSyncRootForDelivery(owner, continuation, { root }, {
                    cleaned.complete(it to Thread.currentThread().name.substringBefore(" @coroutine#"))
                }, main)
                withTimeout(15_000) { main.awaitTask() }
                consumer.cancel()
                main.runAll()
                acquisition.join()
                assertEquals(root.savedStateId to "synthetic-cleanup-io", withTimeout(15_000) { cleaned.await() })
            } finally {
                owner.cancel()
            }
        }
    }

    @Test
    fun ownerCancellationAfterAcquisitionStillReclaimsTheGrant() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val owner = CoroutineScope(SupervisorJob() + io)
            val main = PausedDispatcher()
            lateinit var continuation: CancellableContinuation<FileSyncLocalRoot?>
            val consumer = async(start = CoroutineStart.UNDISPATCHED) {
                suspendCancellableCoroutine<FileSyncLocalRoot?> { continuation = it }
            }
            val cleaned = CompletableDeferred<String>()
            val acquisition = acquireFileSyncRootForDelivery(owner, continuation, { root }, {
                cleaned.complete(it)
            }, main)
            withTimeout(15_000) { main.awaitTask() }
            owner.cancel()
            main.runAll()
            acquisition.join()
            assertEquals(root.savedStateId, withTimeout(15_000) { cleaned.await() })
            assertTrue(consumer.isCancelled)
        }
    }

    @Test
    fun cancelledRequestDoesNotAcquireAnyGrant() = runBlocking {
        val owner = CoroutineScope(coroutineContext + SupervisorJob())
        lateinit var continuation: CancellableContinuation<FileSyncLocalRoot?>
        val consumer = async(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine<FileSyncLocalRoot?> { continuation = it }
        }
        consumer.cancel()
        var acquired = false
        acquireFileSyncRootForDelivery(owner, continuation, { acquired = true; root }, {}, PausedDispatcher()).join()
        assertFalse(acquired)
        owner.cancel()
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        private val queued = CompletableDeferred<Unit>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block); queued.complete(Unit) }
        suspend fun awaitTask() = queued.await()
        fun runAll() { while (true) (tasks.poll() ?: return).run() }
    }
}
