package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class AndroidDocumentProviderReadAccessTest {
    private val original = NextcloudSession("https://cloud.example.test", "alice", "original-password")
    private val originalIncarnation = incarnation("1")
    private val replacementIncarnation = incarnation("2")

    @Test
    fun openedFileLeaseBlocksRemovalUntilTheDescriptorReleasesIt() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val lease = readLease(guard, lifetimeGuard)
        var removalEntered = false
        val removal = async(Dispatchers.Default) {
            withAndroidAccountRemovalLease(
                NextcloudDocumentIds.accountKey(original),
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
    fun removalOwnsDocumentMutationsBeforeWaitingForAnOpenDescriptor() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val openDescriptor = readLease(guard, lifetimeGuard)
        val removalEntered = CompletableDeferred<Unit>()
        val finishRemoval = CompletableDeferred<Unit>()
        var currentSession: NextcloudSession? = original
        val accountIdentity = NextcloudDocumentIds.accountKey(original)
        val removal = async(Dispatchers.Default) {
            withAndroidAccountRemovalLease(accountIdentity, guard, lifetimeGuard) {
                currentSession = null
                removalEntered.complete(Unit)
                finishRemoval.await()
            }
        }
        withTimeout(1_000L) {
            while (guard.tryWithAccount(accountIdentity, unavailable = { false }) { true }) yield()
        }

        var mutationEntered = false
        val mutation = async(Dispatchers.Default) {
            runCatching {
                acquireAndroidDocumentMutationAccountLease(original, { currentSession }, guard).use {
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
    fun fileOpenWaitingForRemovalRejectsTheReplacementIncarnation() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val lifetimeGuard = AndroidAccountRemovalLifetimeGuard()
        val removalEntered = CompletableDeferred<Unit>()
        val finishRemoval = CompletableDeferred<Unit>()
        var currentIncarnation = originalIncarnation
        val removal = async {
            withAndroidAccountRemovalLease(
                NextcloudDocumentIds.accountKey(original),
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
                NextcloudDocumentIds.accountKey(original),
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
                NextcloudDocumentIds.accountKey(original),
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
