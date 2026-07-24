package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
