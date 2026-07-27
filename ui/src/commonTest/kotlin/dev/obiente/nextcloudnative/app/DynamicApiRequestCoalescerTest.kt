package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
