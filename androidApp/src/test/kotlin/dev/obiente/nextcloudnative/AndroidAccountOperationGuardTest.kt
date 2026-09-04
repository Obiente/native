package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun fileSyncPairCreationWaitsForRemovalAndRejectsTheReauthenticatedSession() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val original = NextcloudSession("https://cloud.example.test", "alice", "original-password")
        val replacement = original.copy(appPassword = "replacement-password")
        val removalEntered = CompletableDeferred<Unit>()
        val releaseRemoval = CompletableDeferred<Unit>()
        var current = original
        var pairCreated = false
        val removal = async {
            guard.withAccount(NextcloudDocumentIds.accountKey(original)) {
                current = replacement
                removalEntered.complete(Unit)
                releaseRemoval.await()
            }
        }
        removalEntered.await()

        val result = async {
            guard.withExactAccountSession(
                expectedSession = original,
                resolveSession = { current },
                unavailable = { "rejected" },
            ) {
                pairCreated = true
                "created"
            }
        }
        yield()

        assertFalse(result.isCompleted)
        releaseRemoval.complete(Unit)
        removal.await()
        assertEquals("rejected", result.await())
        assertFalse(pairCreated)
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
    fun directDocumentMutationLeaseRejectsReauthenticatedSessionAndReleasesTheGuard() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val original = NextcloudSession("https://cloud.example.test", "alice", "original-password")

        assertFailsWith<FileNotFoundException> {
            acquireAndroidDocumentMutationAccountLease(
                session = original,
                loadCurrentSession = { original.copy(appPassword = "replacement-password") },
                guard = guard,
            )
        }

        withTimeout(1_000L) {
            guard.withAccount(NextcloudDocumentIds.accountKey(original)) { }
        }
    }

    @Test
    fun failedWritebackSetupReleasesItsPathAndAccountLease() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val descriptorLease = guard.acquireBlocking("account-a")
        var pathReleased = false

        releaseAndroidDocumentWritebackSetup(descriptorLease) { pathReleased = true }

        withTimeout(1_000L) {
            guard.withAccount("account-a") { }
        }
        assertTrue(pathReleased)
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

    @Test
    fun uploadCreationWaitsForRemovalAndRejectsAReplacementCredential() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val original = NextcloudSession("https://cloud.example.test", "alice", "old-password")
        val replacement = original.copy(appPassword = "new-password")
        val accountIdentity = NextcloudDocumentIds.accountKey(original)
        val removalEntered = CompletableDeferred<Unit>()
        val releaseRemoval = CompletableDeferred<Unit>()
        var currentSession: NextcloudSession? = original
        var uploadCreated = false
        val removal = async {
            guard.withAccount(accountIdentity) {
                currentSession = replacement
                removalEntered.complete(Unit)
                releaseRemoval.await()
            }
        }
        removalEntered.await()

        val upload = async {
            guard.withExactAccountSession(
                expectedSession = original,
                resolveSession = { currentSession },
                unavailable = { false },
            ) {
                uploadCreated = true
                true
            }
        }
        yield()
        assertFalse(upload.isCompleted)

        releaseRemoval.complete(Unit)
        removal.await()
        assertFalse(upload.await())
        assertFalse(uploadCreated)
    }

    @Test
    fun authenticatedMutationWaitsForSelectionAndRejectsTheStaleSession() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val original = NextcloudSession("https://cloud.example.test", "alice", "old-password")
        val replacement = NextcloudSession("https://other.example.test", "bob", "new-password")
        val selectionEntered = CompletableDeferred<Unit>()
        val releaseSelection = CompletableDeferred<Unit>()
        var current = original
        var requestSent = false
        val selection = async {
            guard.withAccounts(
                listOf(NextcloudDocumentIds.accountKey(original), NextcloudDocumentIds.accountKey(replacement)),
            ) {
                current = replacement
                selectionEntered.complete(Unit)
                releaseSelection.await()
            }
        }
        selectionEntered.await()

        val mutation = async {
            runCatching {
                guard.withAuthenticatedMutationSession(original, { current }) {
                    requestSent = true
                }
            }
        }
        yield()

        assertFalse(mutation.isCompleted)
        releaseSelection.complete(Unit)
        selection.await()
        assertTrue(mutation.await().isFailure)
        assertFalse(requestSent)
    }
}
