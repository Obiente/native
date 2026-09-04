package dev.obiente.nextcloudnative

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class AndroidAccountOperationGuardTest {
    @Test
    fun staleSyncSessionIsRejectedAfterAnAccountTransition() {
        val previous = dev.obiente.nextcloudnative.app.NextcloudSession(
            "https://first.example.test",
            "alice",
            "old-password",
        )
        val replacement = dev.obiente.nextcloudnative.app.NextcloudSession(
            "https://second.example.test",
            "bob",
            "new-password",
        )

        assertTrue(
            androidAccountOperationSessionIsCurrent(
                NextcloudDocumentIds.accountKey(previous),
                previous.copy(appPassword = "rotated-password"),
            ),
        )
        assertFalse(
            androidAccountOperationSessionIsCurrent(
                NextcloudDocumentIds.accountKey(previous),
                replacement,
            ),
        )
        assertTrue(androidDocumentWritebackSessionIsCurrent(previous, previous))
        assertFalse(
            androidDocumentWritebackSessionIsCurrent(
                previous,
                previous.copy(appPassword = "rotated-password"),
            ),
        )
    }

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

    @Test
    fun writableDescriptorLeaseBlocksAccountTransitionUntilClose() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val descriptorLease = guard.acquireBlocking("account-a")
        var transitionEntered = false

        val transition = async {
            guard.withAccount("account-a") { transitionEntered = true }
        }
        yield()

        assertFalse(transitionEntered)
        descriptorLease.close()
        transition.await()
        assertTrue(transitionEntered)
    }

    @Test
    fun replacementTransitionWaitsForBothAffectedAccounts() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val retainedWorkEntered = CompletableDeferred<Unit>()
        val releaseRetainedWork = CompletableDeferred<Unit>()
        var transitionEntered = false

        val retainedWork = async {
            guard.withAccount("account-b") {
                retainedWorkEntered.complete(Unit)
                releaseRetainedWork.await()
            }
        }
        retainedWorkEntered.await()
        val transition = async {
            guard.withAccounts(listOf("account-b", "account-a")) { transitionEntered = true }
        }
        yield()

        assertFalse(transitionEntered)
        releaseRetainedWork.complete(Unit)
        retainedWork.await()
        transition.await()
        assertTrue(transitionEntered)
    }

    @Test
    fun retainedOfflineWorkRevalidatesItsSessionAfterAccountRemoval() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val removalCommitted = CompletableDeferred<Unit>()
        val releaseRemoval = CompletableDeferred<Unit>()
        var sessionAvailable = true
        val removal = async {
            guard.withAccount("account-a") {
                sessionAvailable = false
                removalCommitted.complete(Unit)
                releaseRemoval.await()
            }
        }
        removalCommitted.await()

        val offlineSessionAvailable = async {
            guard.withAccount("account-a") { sessionAvailable }
        }
        yield()
        assertFalse(offlineSessionAvailable.isCompleted)

        releaseRemoval.complete(Unit)
        removal.await()
        assertFalse(offlineSessionAvailable.await())
    }

    @Test
    fun accountSessionResolutionWaitsForRemovalAndSkipsTheStaleOperation() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val session = dev.obiente.nextcloudnative.app.NextcloudSession(
            "https://first.example.test",
            "alice",
            "old-password",
        )
        val accountIdentity = NextcloudDocumentIds.accountKey(session)
        val removalCommitted = CompletableDeferred<Unit>()
        val releaseRemoval = CompletableDeferred<Unit>()
        var sessionAvailable = true
        var operationRan = false
        val removal = async {
            guard.withAccount(accountIdentity) {
                sessionAvailable = false
                removalCommitted.complete(Unit)
                releaseRemoval.await()
            }
        }
        removalCommitted.await()

        val cleanup = async {
            guard.withAccountSession(
                accountId = accountIdentity,
                resolveSession = { session.takeIf { sessionAvailable } },
                unavailable = { "unavailable" },
            ) {
                operationRan = true
                "deleted"
            }
        }
        yield()
        assertFalse(cleanup.isCompleted)

        releaseRemoval.complete(Unit)
        removal.await()
        assertEquals("unavailable", cleanup.await())
        assertFalse(operationRan)
    }
}
