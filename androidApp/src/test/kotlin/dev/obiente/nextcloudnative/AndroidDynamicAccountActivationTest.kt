package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DynamicApiRequestCoalescer
import dev.obiente.nextcloudnative.app.NextcloudApiResponse
import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class AndroidDynamicAccountActivationTest {
    @Test
    fun currentCredentialSaveReopensBothDynamicCaches() = runBlocking {
        val session = NextcloudSession("https://cloud.example.test", "alice", "password")
        val cacheAccountId = NextcloudDocumentIds.cacheAccountId(session)
        val coalescer = DynamicApiRequestCoalescer<NextcloudApiResponse>()
        coalescer.fenceAccount(cacheAccountId) {}
        var activatedMemoryAccount: String? = null
        val activation = AndroidDynamicAccountActivation(
            coalescer = coalescer,
            activateMemory = { activatedMemoryAccount = it },
        )

        activation.afterCredentialSave(session)

        assertEquals(session.accountId.storageKey, activatedMemoryAccount)
        assertEquals(
            200,
            coalescer.execute(cacheAccountId, "GET /status", load = {
                NextcloudApiResponse(200, byteArrayOf(), null, null)
            }).status,
        )
    }

}
