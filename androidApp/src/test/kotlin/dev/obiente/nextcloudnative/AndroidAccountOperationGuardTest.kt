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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class AndroidAccountOperationGuardTest {
    @Test
    fun accountRemovalWaitsForCrossingDeckDraftSaveThenDeletesIt() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val credentialMutations = Mutex()
        val session = NextcloudSession("https://cloud.example.test", "alice", "password")
        val saveEntered = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        var current: NextcloudSession? = session
        var draftExists = false
        val save = async {
            withAndroidAccountPrivateStatePublication(
                expectedSession = session,
                credentialMutationMutex = credentialMutations,
                guard = guard,
                resolveSession = { current },
                unavailable = { false },
            ) {
                saveEntered.complete(Unit)
                releaseSave.await()
                draftExists = true
                true
            }
        }
        saveEntered.await()
        val removal = async {
            credentialMutations.withLock {
                guard.withAccount(NextcloudDocumentIds.accountKey(session)) {
                    current = null
                    draftExists = false
                }
            }
        }
        yield()

        assertFalse(removal.isCompleted)
        releaseSave.complete(Unit)
        assertTrue(save.await())
        removal.await()

        assertFalse(draftExists)
    }

    @Test
    fun removalInThePostSaveGapCannotReopenDynamicReads() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val credentialMutations = Mutex()
        val persisted = NextcloudSession("https://cloud.example.test", "alice", "saved-password")
        val saveReturned = CompletableDeferred<Unit>()
        val continueAfterSave = CompletableDeferred<Unit>()
        var current: NextcloudSession? = persisted
        var activatedAccountId: String? = null
        val save = async {
            saveReturned.complete(Unit)
            continueAfterSave.await()
            activateAndroidDynamicReadsAfterCredentialSave(
                persistedSession = persisted,
                credentialMutationMutex = credentialMutations,
                guard = guard,
                resolveSession = { current },
                activate = { activatedAccountId = it },
            )
        }
        saveReturned.await()

        credentialMutations.withLock {
            guard.withAccount(NextcloudDocumentIds.accountKey(persisted)) { current = null }
        }
        continueAfterSave.complete(Unit)
        save.await()

        assertEquals(null, activatedAccountId)
    }

    @Test
    fun lateDurableWriterCannotPublishAfterRemovalAndCredentialReplacement() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val credentialMutations = Mutex()
        val original = NextcloudSession("https://cloud.example.test", "alice", "old-password")
        val replacement = original.copy(appPassword = "replacement-password")
        val removalEntered = CompletableDeferred<Unit>()
        val releaseRemoval = CompletableDeferred<Unit>()
        var current: NextcloudSession? = original
        var durablePublished = false
        val removal = async {
            credentialMutations.withLock {
                guard.withAccount(NextcloudDocumentIds.accountKey(original)) {
                    current = replacement
                    removalEntered.complete(Unit)
                    releaseRemoval.await()
                }
            }
        }
        removalEntered.await()

        val staleWriter = async {
            withAndroidAccountPrivateStatePublication(
                expectedSession = original,
                credentialMutationMutex = credentialMutations,
                guard = guard,
                resolveSession = { current },
                unavailable = { false },
            ) {
                durablePublished = true
                true
            }
        }
        yield()
        assertFalse(staleWriter.isCompleted)
        releaseRemoval.complete(Unit)
        removal.await()

        assertFalse(staleWriter.await())
        assertFalse(durablePublished)
        assertTrue(
            withAndroidAccountPrivateStatePublication(
                expectedSession = replacement,
                credentialMutationMutex = credentialMutations,
                guard = guard,
                resolveSession = { current },
                unavailable = { false },
                publish = { true },
            ),
        )
    }

    @Test
    fun latePendingWriterCannotPublishAfterRemovalAndReadd() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val credentialMutations = Mutex()
        val original = NextcloudSession("https://cloud.example.test", "alice", "old-password")
        val readded = original.copy(appPassword = "new-password")
        val removalEntered = CompletableDeferred<Unit>()
        val releaseRemoval = CompletableDeferred<Unit>()
        var current: NextcloudSession? = original
        var pendingPublished = false
        val removal = async {
            credentialMutations.withLock {
                guard.withAccount(NextcloudDocumentIds.accountKey(original)) {
                    current = readded
                    removalEntered.complete(Unit)
                    releaseRemoval.await()
                }
            }
        }
        removalEntered.await()

        val staleWriter = async {
            withAndroidAccountPrivateStatePublication(
                expectedSession = original,
                credentialMutationMutex = credentialMutations,
                guard = guard,
                resolveSession = { current },
                unavailable = { false },
            ) {
                pendingPublished = true
                true
            }
        }
        yield()
        releaseRemoval.complete(Unit)
        removal.await()

        assertFalse(staleWriter.await())
        assertFalse(pendingPublished)
        assertTrue(
            withAndroidAccountPrivateStatePublication(
                expectedSession = readded,
                credentialMutationMutex = credentialMutations,
                guard = guard,
                resolveSession = { current },
                unavailable = { false },
                publish = { true },
            ),
        )
    }

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
    fun remoteRevocationKeepsMutationsBlockedUntilLocalRemovalCommits() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val remoteRevoked = CompletableDeferred<Unit>()
        val allowLocalRemoval = CompletableDeferred<Unit>()
        var localRemovalCommitted = false
        var mutationObservedCommittedRemoval = false

        val removal = async {
            revokeAndroidSessionWithAccountLease(
                accountIdentity = "account-a",
                guard = guard,
                preflight = {},
                revoke = { remoteRevoked.complete(Unit) },
                removeLocalAccount = {
                    allowLocalRemoval.await()
                    localRemovalCommitted = true
                },
            )
        }
        remoteRevoked.await()
        val mutation = async {
            guard.withAccount("account-a") {
                mutationObservedCommittedRemoval = localRemovalCommitted
            }
        }
        yield()

        assertFalse(mutation.isCompleted)
        allowLocalRemoval.complete(Unit)
        removal.await()
        mutation.await()
        assertTrue(mutationObservedCommittedRemoval)
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
    fun writableDescriptorLeaseRejectsAccountRemovalWithoutWaitingForClose() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val session = NextcloudSession("https://cloud.example.test", "alice", "password")
        val accountIdentity = NextcloudDocumentIds.accountKey(session)
        val descriptorLease = acquireAndroidDocumentMutationAccountLease(session, { session }, guard)
        var removalEntered = false

        val failure = try {
            assertFailsWith<IllegalStateException> {
                withTimeout(1_000L) {
                    withAndroidAccountRemovalLease(accountIdentity, guard) {
                        removalEntered = true
                    }
                }
            }
        } finally {
            descriptorLease.close()
        }

        assertEquals(
            "Finish or discard pending document changes before removing this account.",
            failure.message,
        )
        assertFalse(removalEntered)
        withTimeout(1_000L) {
            withAndroidAccountRemovalLease(accountIdentity, guard) { removalEntered = true }
        }
        assertTrue(removalEntered)
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
    fun incomingShareRestoreRejectsARetainedCredentialAfterAnotherAccountBecomesActive() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val retained = NextcloudSession("https://first.example.test", "alice", "old-password")
        val active = NextcloudSession("https://second.example.test", "bob", "new-password")
        var restored = false

        val accepted = restoreIncomingShareForActiveSession(
            guard = guard,
            expectedSession = retained,
            resolveActiveSession = { active },
            unavailable = { false },
        ) {
            restored = true
            true
        }

        assertFalse(accepted)
        assertFalse(restored)
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

    @Test
    fun textFileCreationWaitsForSelectionAndRejectsTheStaleSession() = runBlocking {
        assertCreateMutationWaitsForAccountTransition(
            transition = { NextcloudSession("https://other.example.test", "bob", "new-password") },
            method = "PUT",
        )
    }

    @Test
    fun directoryCreationWaitsForRemovalAndRejectsTheStaleSession() = runBlocking {
        assertCreateMutationWaitsForAccountTransition(transition = { null }, method = "MKCOL")
    }

    private suspend fun assertCreateMutationWaitsForAccountTransition(
        transition: () -> NextcloudSession?,
        method: String,
    ) = coroutineScope {
        val guard = AndroidAccountOperationGuard()
        val original = NextcloudSession("https://cloud.example.test", "alice", "old-password")
        val transitionEntered = CompletableDeferred<Unit>()
        val releaseTransition = CompletableDeferred<Unit>()
        var current: NextcloudSession? = original
        var requestMethod: String? = null
        val transitionJob = async {
            guard.withAccount(NextcloudDocumentIds.accountKey(original)) {
                current = transition()
                transitionEntered.complete(Unit)
                releaseTransition.await()
            }
        }
        transitionEntered.await()
        val mutation = async {
            runCatching {
                withAndroidAuthenticatedFileMutation(
                    accountMutationLeaseHeld = false,
                    expectedSession = original,
                    resolveSession = { current },
                    guard = guard,
                ) { _, _ ->
                    requestMethod = method
                }
            }
        }
        yield()

        assertFalse(mutation.isCompleted)
        releaseTransition.complete(Unit)
        transitionJob.await()
        assertTrue(mutation.await().isFailure)
        assertEquals(null, requestMethod)
    }

    @Test
    fun authenticatedFileMutationPropagatesItsHeldLeaseToTheRequestBoundary() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val session = NextcloudSession("https://cloud.example.test", "alice", "fixture-password")
        var requestObservedSerializedLease = false

        withAndroidAuthenticatedFileMutation(
            accountMutationLeaseHeld = false,
            expectedSession = session,
            resolveSession = { session },
            guard = guard,
        ) { _, accountMutationSerialized ->
            requestObservedSerializedLease = accountMutationSerialized
            val nestedLeaseAvailable = guard.tryWithAccount(
                NextcloudDocumentIds.accountKey(session),
                unavailable = { false },
                action = { true },
            )
            assertFalse(nestedLeaseAvailable)
        }

        assertTrue(requestObservedSerializedLease)
    }
}
