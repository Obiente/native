package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class AndroidDocumentProviderReadAccessTest {
    private val original = NextcloudSession("https://cloud.example.test", "alice", "original-password")
    private val originalIncarnation = incarnation("1")
    private val replacementIncarnation = incarnation("2")

    @Test
    fun openedFileLeaseBlocksRemovalUntilTheDescriptorReleasesIt() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val lease = readLease(guard)
        var removalEntered = false
        val removal = async(Dispatchers.Default) {
            guard.withAccount(NextcloudDocumentIds.accountKey(original)) { removalEntered = true }
        }
        yield()

        assertFalse(removalEntered)
        lease.close()
        removal.await()
        assertTrue(removalEntered)
    }

    @Test
    fun fileOpenWaitingForRemovalRejectsTheReplacementIncarnation() = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val removalEntered = CompletableDeferred<Unit>()
        val finishRemoval = CompletableDeferred<Unit>()
        var currentIncarnation = originalIncarnation
        val removal = async {
            guard.withAccount(NextcloudDocumentIds.accountKey(original)) {
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
            ) {
                searchEntered.complete(Unit)
                runBlocking { finishSearch.await() }
            }
        }
        searchEntered.await()
        val removal = async {
            guard.withAccount(NextcloudDocumentIds.accountKey(original)) { removalEntered = true }
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

        assertFailsWith<IllegalStateException> {
            withAndroidDocumentProviderReadAccess(
                original,
                originalIncarnation,
                { original },
                { originalIncarnation },
                guard,
            ) { error("synthetic read failure") }
        }

        guard.withAccount(NextcloudDocumentIds.accountKey(original)) {}
    }

    private fun readLease(guard: AndroidAccountOperationGuard) = acquireAndroidDocumentProviderReadLease(
        original,
        originalIncarnation,
        { original },
        { originalIncarnation },
        guard,
    )

    private fun incarnation(digit: String) = NextcloudDocumentIncarnation.Versioned(digit.repeat(32))
}
