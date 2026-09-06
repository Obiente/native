package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class AndroidDocumentProviderReadAccessTest {
    private val original = NextcloudSession("https://cloud.example.test", "alice", "original-password")
    private val originalIncarnation = incarnation("1")
    private val replacementIncarnation = incarnation("2")

    @Test
    fun cachedDocumentContentUsesARevocableProxyCallback() {
        val content = Files.createTempFile("document-cache-proxy-", ".bin").toFile().apply {
            writeText("cached bytes")
        }
        var leaseReleased = 0
        val callback = androidDocumentAccountLeasedContentCallback(
            content,
            AndroidAccountOperationLease { leaseReleased += 1 },
        )
        try {
            val bytes = ByteArray(6)
            assertEquals(6, callback.onRead(0L, bytes.size, bytes))
            assertEquals("cached", bytes.decodeToString())
            callback.onRelease()
            callback.onRelease()
            assertEquals(1, leaseReleased)
        } finally {
            callback.onRelease()
            content.delete()
        }
    }

    @Test
    fun failedCachedDocumentProxySetupReleasesItsAccountLease() {
        var leaseReleased = 0

        assertFailsWith<FileNotFoundException> {
            androidDocumentAccountLeasedContentCallback(
                java.io.File("missing-document-cache-${System.nanoTime()}"),
                AndroidAccountOperationLease { leaseReleased += 1 },
            )
        }

        assertEquals(1, leaseReleased)
    }

    @Test
    fun openedFileLeaseBlocksRemovalUntilTheDescriptorReleasesIt() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val lease = readLease(guard, lifetimeGuard)
        var removalEntered = false
        val removal = async(Dispatchers.Default) {
            withAndroidAccountRemovalLease(
                original,
                guard,
                lifetimeGuard,
            ) { removalEntered = true }
        }
        yield()

        assertFalse(removalEntered)
        lease.close()
        removal.await()
        assertTrue(removalEntered)
    }

    @Test
    fun removalFencesDocumentMutationsBeforeWaitingForAnOpenDescriptor() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val openDescriptor = readLease(guard, lifetimeGuard)
        val removalEntered = CompletableDeferred<Unit>()
        val finishRemoval = CompletableDeferred<Unit>()
        var currentSession: NextcloudSession? = original
        val removal = async(start = CoroutineStart.UNDISPATCHED) {
            withAndroidAccountRemovalLease(original, guard, lifetimeGuard) {
                currentSession = null
                removalEntered.complete(Unit)
                finishRemoval.await()
            }
        }
        assertFalse(removal.isCompleted)

        var mutationEntered = false
        val mutation = async(Dispatchers.Default) {
            runCatching {
                acquireAndroidDocumentMutationAccountLease(
                    original,
                    { currentSession },
                    guard,
                    lifetimeGuard,
                ).use {
                    mutationEntered = true
                }
            }
        }
        yield()
        assertFalse(mutation.isCompleted)
        assertFalse(mutationEntered)

        openDescriptor.close()
        removalEntered.await()
        assertFalse(mutation.isCompleted)
        finishRemoval.complete(Unit)
        removal.await()

        assertIs<FileNotFoundException>(mutation.await().exceptionOrNull())
        assertFalse(mutationEntered)
    }

    @Test
    fun writableDescriptorCommitRejectsCredentialsRotatedAfterOpen() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val openDescriptor = readLease(guard, lifetimeGuard)
        var currentSession: NextcloudSession? = original
        var commitEntered = false

        withTimeout(1_000L) {
            guard.withAccount(NextcloudDocumentIds.accountKey(original)) {
                currentSession = original.copy(appPassword = "replacement-password")
            }
        }

        val failure = assertFailsWith<FileNotFoundException> {
            withAndroidDocumentWritebackCommitWhileLifetimeLeaseHeld(
                expectedSession = original,
                loadCurrentSession = { currentSession },
                guard = guard,
            ) {
                commitEntered = true
            }
        }

        assertEquals(
            "The active Nextcloud account changed before the document writeback could commit.",
            failure.message,
        )
        assertFalse(commitEntered)
        openDescriptor.close()
        withTimeout(1_000L) {
            withAndroidAccountRemovalLease(
                original,
                guard,
                lifetimeGuard,
            ) {}
        }
    }

    @Test
    fun writableDescriptorCommitCanFinishWhileRemovalWaitsForItsLifetimeLease() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val openDescriptor = readLease(guard, lifetimeGuard)
        var currentSession: NextcloudSession? = original
        var commitEntered = false
        val removal = async(start = CoroutineStart.UNDISPATCHED) {
            withAndroidAccountRemovalLease(
                original,
                guard,
                lifetimeGuard,
            ) { currentSession = null }
        }
        assertFalse(removal.isCompleted)

        try {
            withTimeout(1_000L) {
                async(Dispatchers.Default) {
                    withAndroidDocumentWritebackCommitWhileLifetimeLeaseHeld(
                        expectedSession = original,
                        loadCurrentSession = { currentSession },
                        guard = guard,
                    ) { commitEntered = true }
                }.await()
            }
        } finally {
            openDescriptor.close()
        }
        removal.await()

        assertTrue(commitEntered)
        assertEquals(null, currentSession)
    }

    @Test
    fun cancelledRemovalWaitDoesNotBlockDescriptorCommit() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val openDescriptor = readLease(guard, lifetimeGuard)
        var commitEntered = false
        val removal = launch(start = CoroutineStart.UNDISPATCHED) {
            withAndroidAccountRemovalLease(
                original,
                guard,
                lifetimeGuard,
            ) { error("Cancelled removal must not enter") }
        }
        assertFalse(removal.isCompleted)

        removal.cancelAndJoin()
        withAndroidDocumentWritebackCommitWhileLifetimeLeaseHeld(
            expectedSession = original,
            loadCurrentSession = { original },
            guard = guard,
        ) { commitEntered = true }
        openDescriptor.close()

        assertTrue(commitEntered)
        withTimeout(1_000L) {
            withAndroidAccountRemovalLease(
                original,
                guard,
                lifetimeGuard,
            ) {}
        }
    }

    @Test
    fun cancelledCredentialResetWaitDoesNotBlockNewDocumentReads() = runBlocking {
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val openDescriptor = lifetimeGuard.acquireReadBlocking(original.accountId.storageKey)
        val reset = launch(start = CoroutineStart.UNDISPATCHED) {
            lifetimeGuard.withCredentialReset(emptyList()) {
                error("Cancelled credential reset must not enter")
            }
        }
        assertFalse(reset.isCompleted)

        reset.cancelAndJoin()
        val laterRead = withTimeout(1_000L) {
            async(Dispatchers.Default) {
                lifetimeGuard.acquireReadBlocking(original.accountId.storageKey)
            }.await()
        }

        laterRead.close()
        openDescriptor.close()
    }

    @Test
    fun fileOpenWaitingForRemovalRejectsTheReplacementIncarnation() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val removalEntered = CompletableDeferred<Unit>()
        val finishRemoval = CompletableDeferred<Unit>()
        var currentIncarnation = originalIncarnation
        val removal = async {
            withAndroidAccountRemovalLease(
                original,
                guard,
                lifetimeGuard,
            ) {
                currentIncarnation = replacementIncarnation
                removalEntered.complete(Unit)
                finishRemoval.await()
            }
        }
        removalEntered.await()
        val open = async(Dispatchers.Default) {
            runCatching {
                acquireAndroidDocumentProviderReadLease(
                    original,
                    originalIncarnation,
                    { original },
                    { currentIncarnation },
                    guard,
                    lifetimeGuard,
                )
            }
        }
        yield()
        assertFalse(open.isCompleted)

        finishRemoval.complete(Unit)
        removal.await()
        val result = open.await()
        result.getOrNull()?.close()
        assertIs<FileNotFoundException>(result.exceptionOrNull())
        guard.withAccount(NextcloudDocumentIds.accountKey(original)) {}
    }

    @Test
    fun searchKeepsRemovalBlockedThroughTheAuthenticatedRead() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val searchEntered = CompletableDeferred<Unit>()
        val finishSearch = CompletableDeferred<Unit>()
        var removalEntered = false
        val search = async(Dispatchers.Default) {
            withAndroidDocumentProviderReadAccess(
                original,
                originalIncarnation,
                { original },
                { originalIncarnation },
                guard,
                lifetimeGuard,
            ) {
                searchEntered.complete(Unit)
                runBlocking { finishSearch.await() }
            }
        }
        searchEntered.await()
        val removal = async {
            withAndroidAccountRemovalLease(
                original,
                guard,
                lifetimeGuard,
            ) { removalEntered = true }
        }
        yield()

        assertFalse(removalEntered)
        finishSearch.complete(Unit)
        search.await()
        removal.await()
        assertTrue(removalEntered)
    }

    @Test
    fun failedReadReleasesTheAccountLease() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()

        assertFailsWith<IllegalStateException> {
            withAndroidDocumentProviderReadAccess(
                original,
                originalIncarnation,
                { original },
                { originalIncarnation },
                guard,
                lifetimeGuard,
            ) { error("synthetic read failure") }
        }

        withTimeout(1_000L) {
            withAndroidAccountRemovalLease(
                original,
                guard,
                lifetimeGuard,
            ) {}
        }
    }

    @Test
    fun openedFileLeaseDoesNotBlockAccountSelection() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val lease = readLease(guard, lifetimeGuard)
        var selectionEntered = false

        withTimeout(1_000L) {
            guard.withAccounts(listOf(NextcloudDocumentIds.accountKey(original), "another-account")) {
                selectionEntered = true
            }
        }

        assertTrue(selectionEntered)
        lease.close()
    }

    @Test
    fun canonicallyEquivalentRemovalWaitsForTheOriginalDescriptorLease() = runBlocking {
        val equivalent = original.copy(serverUrl = "HTTPS://CLOUD.EXAMPLE.TEST:443///")
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val lease = readLease(guard, lifetimeGuard)
        var removalEntered = false

        assertEquals(original.accountId, equivalent.accountId)
        assertNotEquals(NextcloudDocumentIds.accountKey(original), NextcloudDocumentIds.accountKey(equivalent))
        val removal = async(Dispatchers.Default) {
            withAndroidAccountRemovalLease(equivalent, guard, lifetimeGuard) {
                removalEntered = true
            }
        }
        yield()

        assertFalse(removalEntered)
        lease.close()
        removal.await()
        assertTrue(removalEntered)
    }

    @Test
    fun readValidationLoadsTheIncarnationByCanonicalAccountIdentity() {
        var loadedIdentity: String? = null

        val lease = acquireAndroidDocumentProviderReadLease(
            original,
            originalIncarnation,
            { original },
            { accountIdentity ->
                loadedIdentity = accountIdentity
                originalIncarnation
            },
            AndroidAccountOperationGuard(),
            AndroidAccountRemovalLifetimeGuard(),
        )
        lease.close()

        assertEquals(original.accountId.storageKey, loadedIdentity)
        assertNotEquals(NextcloudDocumentIds.accountKey(original), loadedIdentity)
    }

    private fun readLease(
        guard: AndroidAccountOperationGuard,
        lifetimeGuard: AndroidAccountRemovalLifetimeGuard,
    ) = acquireAndroidDocumentProviderReadLease(
        original,
        originalIncarnation,
        { original },
        { originalIncarnation },
        guard,
        lifetimeGuard,
    )

    private fun incarnation(digit: String) = NextcloudDocumentIncarnation.Versioned(digit.repeat(32))
}
