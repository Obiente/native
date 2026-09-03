package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class AndroidAccountOperationGuardTest {
    @Test
    fun sameAccountRemovalWaitsForTheUploadLease() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val uploadEntered = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        var removalEntered = false

        val upload = async {
            guard.withAccount("account-a") {
                uploadEntered.complete(Unit)
                releaseUpload.await()
            }
        }
        uploadEntered.await()
        val removal = async {
            guard.withAccount("account-a") { removalEntered = true }
        }
        yield()

        assertFalse(removalEntered)
        releaseUpload.complete(Unit)
        upload.await()
        removal.await()
        assertTrue(removalEntered)
    }

    @Test
    fun differentAccountsKeepIndependentOperationLeases() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val uploadEntered = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        var otherAccountEntered = false

        val upload = async {
            guard.withAccount("account-a") {
                uploadEntered.complete(Unit)
                releaseUpload.await()
            }
        }
        uploadEntered.await()
        guard.withAccount("account-b") { otherAccountEntered = true }

        assertTrue(otherAccountEntered)
        releaseUpload.complete(Unit)
        upload.await()
    }
}
