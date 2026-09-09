package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class DynamicApiRequestCoalescerTest {
    @Test
    fun `identical concurrent reads execute once while accounts remain isolated`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<String>()
        val release = CompletableDeferred<Unit>()
        var loads = 0

        val sameAccount = List(4) {
            async(start = CoroutineStart.UNDISPATCHED) {
                coalescer.execute("account-a", "GET items", load = {
                    loads += 1
                    release.await()
                    "shared"
                })
            }
        }
        val otherAccount = async(start = CoroutineStart.UNDISPATCHED) {
            coalescer.execute("account-b", "GET items", load = {
                loads += 1
                "other"
            })
        }
        release.complete(Unit)

        assertEquals(listOf("shared", "shared", "shared", "shared"), sameAccount.awaitAll())
        assertEquals("other", otherAccount.await())
        assertEquals(2, loads)
    }

    @Test
    fun `read crossing a mutation is retried and only new generation is committed`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<String>()
        val firstReadStarted = CompletableDeferred<Unit>()
        val finishFirstRead = CompletableDeferred<Unit>()
        val committed = mutableListOf<String>()
        var loadNumber = 0

        val read = async {
            coalescer.execute(
                accountId = "account-a",
                requestIdentity = "GET items",
                load = {
                    loadNumber += 1
                    if (loadNumber == 1) {
                        firstReadStarted.complete(Unit)
                        finishFirstRead.await()
                        "old"
                    } else {
                        "new"
                    }
                },
                commit = committed::add,
            )
        }
        firstReadStarted.await()
        coalescer.invalidateAccount("account-a") { committed.clear() }
        finishFirstRead.complete(Unit)

        assertEquals("new", read.await())
        assertEquals(2, loadNumber)
        assertEquals(listOf("new"), committed)
    }

    @Test
    fun `account removal fence terminates already entered reads without committing`() = runBlocking {
        supervisorScope {
            val coalescer = DynamicApiRequestCoalescer<String>()
            val readStarted = CompletableDeferred<Unit>()
            val finishRead = CompletableDeferred<Unit>()
            val committed = mutableListOf<String>()
            var loads = 0

            val owner = async {
                coalescer.execute("account-a", "GET items", load = {
                    loads += 1
                    readStarted.complete(Unit)
                    finishRead.await()
                    "removed-account-data"
                }, commit = committed::add)
            }
            readStarted.await()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                coalescer.execute("account-a", "GET items", load = { fail("must coalesce") })
            }
            coalescer.fenceAccount("account-a") { committed.clear() }
            finishRead.complete(Unit)

            assertFailsWith<DynamicReadAccountFencedException> { owner.await() }
            assertFailsWith<DynamicReadAccountFencedException> { waiter.await() }
            assertEquals(1, loads)
            assertEquals(emptyList(), committed)
        }
    }

    @Test
    fun `read invoked after an account removal fence stays closed until activation`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<String>()

        coalescer.fenceAccount("account-a") {}

        assertFailsWith<DynamicReadAccountFencedException> {
            coalescer.execute("account-a", "GET items", load = { fail("must remain closed") })
        }
        coalescer.activateAccount("account-a")
        assertEquals(
            "re-added-account-data",
            coalescer.execute("account-a", "GET items", load = { "re-added-account-data" }),
        )
    }

    @Test
    fun `readding an account does not let its stale read commit`() = runBlocking {
        supervisorScope {
            val coalescer = DynamicApiRequestCoalescer<String>()
            val readStarted = CompletableDeferred<Unit>()
            val finishRead = CompletableDeferred<Unit>()
            val committed = mutableListOf<String>()
            val stale = async {
                coalescer.execute("account-a", "GET items", load = {
                    readStarted.complete(Unit)
                    finishRead.await()
                    "removed-account-data"
                }, commit = committed::add)
            }

            readStarted.await()
            coalescer.fenceAccount("account-a") { committed.clear() }
            coalescer.activateAccount("account-a")
            val replacement = async {
                coalescer.execute("account-a", "GET items", load = { "replacement-account-data" }, commit = committed::add)
            }
            finishRead.complete(Unit)

            assertFailsWith<DynamicReadAccountFencedException> { stale.await() }
            assertEquals("replacement-account-data", replacement.await())
            assertEquals(listOf("replacement-account-data"), committed)
        }
    }

    @Test
    fun `displaced owner and waiter remain fenced after replacement finishes and account reopens`() = runBlocking {
        assertDisplacedOwnerRemainsFenced(failLoad = false)
    }

    @Test
    fun `displaced failed owner cannot retry old credentials after account reopens`() = runBlocking {
        assertDisplacedOwnerRemainsFenced(failLoad = true)
    }

    private suspend fun assertDisplacedOwnerRemainsFenced(failLoad: Boolean) = supervisorScope {
        val coalescer = DynamicApiRequestCoalescer<String>()
        val finishOldRead = CompletableDeferred<Unit>()
        val committed = mutableListOf<String>()
        var oldCredentialLoads = 0
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            coalescer.execute("account-a", "GET items", load = {
                oldCredentialLoads += 1
                finishOldRead.await()
                if (failLoad) error("retired credential transport failed")
                "retired account data"
            }, commit = committed::add)
        }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            coalescer.execute("account-a", "GET items", load = { fail("must not retry retired waiter") })
        }
        coalescer.invalidateAccount("account-a") { committed.clear() }
        assertEquals("replacement", coalescer.execute("account-a", "GET items", load = { "replacement" }))
        assertEquals(2, coalescer.activeReadCount())

        coalescer.fenceAccount("account-a") { committed.clear() }
        coalescer.activateAccount("account-a")
        assertEquals("new credentials", coalescer.execute("account-a", "GET items", load = { "new credentials" }))
        finishOldRead.complete(Unit)

        assertFailsWith<DynamicReadAccountFencedException> { owner.await() }
        assertFailsWith<DynamicReadAccountFencedException> { waiter.await() }
        assertEquals(1, oldCredentialLoads)
        assertEquals(emptyList(), committed)
        assertEquals(0, coalescer.activeReadCount())
    }

    @Test
    fun `queued invalidated waiter stays fenced after its owner finishes and account reopens`() = runBlocking {
        supervisorScope {
            val coalescer = DynamicApiRequestCoalescer<String>()
            val dispatcher = QueuedReadDispatcher()
            val finishFirstLoad = CompletableDeferred<Unit>()
            var ownerLoads = 0
            var retiredWaiterLoads = 0
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                coalescer.execute("account-a", "GET items", load = {
                    ownerLoads += 1
                    if (ownerLoads == 1) finishFirstLoad.await()
                    "owner-$ownerLoads"
                })
            }
            val waiter = async(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                coalescer.execute("account-a", "GET items", load = {
                    retiredWaiterLoads += 1
                    "retired waiter credentials"
                })
            }
            try {
                assertEquals(2, coalescer.activeReadCount())
                coalescer.invalidateRequest("account-a", "GET items") {}
                finishFirstLoad.complete(Unit)
                assertEquals("owner-2", owner.await())
                assertEquals(1, coalescer.activeReadCount())
                assertEquals(1, dispatcher.pendingCount)
                assertEquals(0, coalescer.retainedRequestGenerationCount())

                coalescer.fenceAccount("account-a") {}
                coalescer.activateAccount("account-a")
                dispatcher.runAll()

                assertFailsWith<DynamicReadAccountFencedException> { waiter.await() }
                assertEquals(0, retiredWaiterLoads)
                assertEquals(0, coalescer.activeReadCount())
                assertEquals("new credentials", coalescer.execute("account-a", "GET items", load = { "new credentials" }))
            } finally {
                owner.cancel()
                waiter.cancel()
                dispatcher.runAll()
            }
        }
    }

    @Test
    fun `queued successful waiter cannot deliver retired data after account reopens`() = runBlocking {
        supervisorScope {
            val coalescer = DynamicApiRequestCoalescer<String>()
            val dispatcher = QueuedReadDispatcher()
            val finishLoad = CompletableDeferred<Unit>()
            val owner = async(start = CoroutineStart.UNDISPATCHED) {
                coalescer.execute("account-a", "GET items", load = {
                    finishLoad.await()
                    "retired account data"
                })
            }
            val waiter = async(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                coalescer.execute("account-a", "GET items", load = { fail("must not reload retired waiter") })
            }
            try {
                finishLoad.complete(Unit)
                assertEquals("retired account data", owner.await())
                assertEquals(1, coalescer.activeReadCount())
                assertEquals(1, dispatcher.pendingCount)

                coalescer.fenceAccount("account-a") {}
                coalescer.activateAccount("account-a")
                dispatcher.runAll()

                assertFailsWith<DynamicReadAccountFencedException> { waiter.await() }
                assertEquals(0, coalescer.activeReadCount())
                assertEquals("new credentials", coalescer.execute("account-a", "GET items", load = { "new credentials" }))
            } finally {
                owner.cancel()
                waiter.cancel()
                dispatcher.runAll()
            }
        }
    }

    @Test
    fun `displaced cancellation retires only its own owner and preserves another account`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<String>()
        val otherRelease = CompletableDeferred<Unit>()
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            coalescer.execute("account-a", "GET items", load = { awaitCancellation() })
        }
        coalescer.invalidateAccount("account-a") {}
        assertEquals("replacement", coalescer.execute("account-a", "GET items", load = { "replacement" }))
        val other = async(start = CoroutineStart.UNDISPATCHED) {
            coalescer.execute("account-b", "GET items", load = { otherRelease.await(); "other" })
        }
        coalescer.fenceAccount("account-a") {}
        cancelled.cancelAndJoin()
        assertEquals(1, coalescer.activeReadCount())
        otherRelease.complete(Unit)
        assertEquals("other", other.await())
        assertEquals(0, coalescer.activeReadCount())
    }

    @Test
    fun `failed commit retires its active owner`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<String>()
        assertFailsWith<IllegalStateException> {
            coalescer.execute("account-a", "GET items", load = { "loaded" }, commit = { error("cache failed") })
        }
        assertEquals(0, coalescer.activeReadCount())
        assertEquals("fresh", coalescer.execute("account-a", "GET items", load = { "fresh" }))
        assertEquals(0, coalescer.activeReadCount())
    }

    @Test
    fun `cancelled owner remains cancelled and releases its in flight entry`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<String>()
        var loads = 0
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            coalescer.execute("account-a", "GET items", load = {
                loads += 1
                awaitCancellation()
            })
        }

        cancelled.cancelAndJoin()

        assertEquals("fresh", coalescer.execute("account-a", "GET items", load = { "fresh" }))
        assertEquals(1, loads)
    }

    @Test
    fun `request invalidation retries only the matching in flight read`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<String>()
        val firstTargetReadStarted = CompletableDeferred<Unit>()
        val finishInitialReads = CompletableDeferred<Unit>()
        var targetLoads = 0
        var unrelatedLoads = 0

        val targetRead = async {
            coalescer.execute("account-a", "GET target", load = {
                targetLoads += 1
                if (targetLoads == 1) {
                    firstTargetReadStarted.complete(Unit)
                    finishInitialReads.await()
                    "stale"
                } else {
                    "fresh"
                }
            })
        }
        val unrelatedRead = async(start = CoroutineStart.UNDISPATCHED) {
            coalescer.execute("account-a", "GET unrelated", load = {
                unrelatedLoads += 1
                finishInitialReads.await()
                "unrelated"
            })
        }
        firstTargetReadStarted.await()
        coalescer.invalidateRequest("account-a", "GET target") {}
        finishInitialReads.complete(Unit)

        assertEquals("fresh", targetRead.await())
        assertEquals("unrelated", unrelatedRead.await())
        assertEquals(2, targetLoads)
        assertEquals(1, unrelatedLoads)
        assertEquals(0, coalescer.retainedRequestGenerationCount())
    }

    @Test
    fun `idle request invalidation churn does not retain generations`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<String>()

        repeat(2_000) { index ->
            coalescer.invalidateRequest("account-a", "GET card/$index") {}
            assertEquals(
                "fresh-$index",
                coalescer.execute(
                    accountId = "account-a",
                    requestIdentity = "GET card/$index",
                    load = { "fresh-$index" },
                ),
            )
        }

        assertEquals(0, coalescer.retainedRequestGenerationCount())
    }

    @Test
    fun `failed invalidated read retries and retires its generation`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<String>()
        val firstReadStarted = CompletableDeferred<Unit>()
        val failFirstRead = CompletableDeferred<Unit>()
        var loads = 0

        val read = async {
            coalescer.execute(
                accountId = "account-a",
                requestIdentity = "GET card/7",
                load = {
                    loads += 1
                    if (loads == 1) {
                        firstReadStarted.complete(Unit)
                        failFirstRead.await()
                        error("stale transport failure")
                    }
                    "fresh"
                },
            )
        }
        firstReadStarted.await()
        coalescer.invalidateRequest("account-a", "GET card/7") {}
        failFirstRead.complete(Unit)

        assertEquals("fresh", read.await())
        assertEquals(2, loads)
        assertEquals(0, coalescer.retainedRequestGenerationCount())
    }

    @Test
    fun `non invalidated read failure remains visible and retains no generation`() = runBlocking {
        val coalescer = DynamicApiRequestCoalescer<String>()

        try {
            coalescer.execute(
                accountId = "account-a",
                requestIdentity = "GET card/8",
                load = { error("network failed") },
            )
            fail("Expected the read failure to remain visible.")
        } catch (failure: IllegalStateException) {
            assertEquals("network failed", failure.message)
        }

        assertEquals(0, coalescer.retainedRequestGenerationCount())
    }

    private class QueuedReadDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        val pendingCount: Int get() = pending.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.addLast(block)
        }

        fun runAll() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }
}
