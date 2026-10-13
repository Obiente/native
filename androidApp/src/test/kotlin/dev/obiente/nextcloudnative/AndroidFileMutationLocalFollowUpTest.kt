package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileMutationLocalFollowUp
import dev.obiente.nextcloudnative.app.FileMutationOutcomeUnknownException
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.invalidateAfterConfirmedFileMutation
import dev.obiente.nextcloudnative.app.reportingUnknownFileMutationResult
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class AndroidFileMutationLocalFollowUpTest {
    @Test
    fun invalidatedListingsReportCompletedLocalBookkeeping() = withCache { root, cache ->
        val events = mutableListOf<SupportDiagnosticEventDraft>()
        cache.storeListing(ACCOUNT, "Documents", listOf(file("Documents/report.txt")), nowEpochMillis = 1)

        val followUp = invalidateAfterConfirmedFileMutation(listOf("Documents/report.txt", "Archive/report.txt"), events::add) { path ->
            cache.invalidate(ACCOUNT, path)
        }

        assertEquals(FileMutationLocalFollowUp.Completed, followUp)
        assertNull(AndroidFileReadCache(root).cachedListing(ACCOUNT, "Documents"), "The parent listing is invalidated.")
        assertTrue(events.isEmpty())
    }

    @Test
    fun aFailedCacheInvalidationIsReportedWithItsCauseInsteadOfSuccess() = withCache { root, cache ->
        val events = mutableListOf<SupportDiagnosticEventDraft>()
        // A file where the account's cache directory belongs makes every index write fail.
        File(root, ACCOUNT).writeText("blocked")
        val attempted = mutableListOf<String>()

        val followUp = invalidateAfterConfirmedFileMutation(listOf("Documents/a.txt", "Archive/a.txt"), events::add) { path ->
            attempted += path
            cache.invalidate(ACCOUNT, path)
        }

        assertEquals(FileMutationLocalFollowUp.Failed, followUp)
        assertEquals(listOf("Documents/a.txt", "Archive/a.txt"), attempted, "Every path is still attempted.")
        assertEquals(2, events.size)
        assertTrue(events.all { it.code == "FILE_MUTATION_LOCAL_FOLLOW_UP_FAILED" && it.outcome == "failed" })
        assertTrue(events.all { it.exception != null }, "The typed cause is kept for diagnostics.")
    }

    @Test
    fun cancellationIsNotReportedAsALocalFailure() {
        val events = mutableListOf<SupportDiagnosticEventDraft>()

        assertFailsWith<CancellationException> {
            invalidateAfterConfirmedFileMutation(listOf("a"), events::add) { throw CancellationException("closed") }
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun aFailingDiagnosticDoesNotHideTheLocalFailure() {
        val followUp = invalidateAfterConfirmedFileMutation(listOf("a"), { error("diagnostics unavailable") }) {
            error("cache unavailable")
        }

        assertEquals(FileMutationLocalFollowUp.Failed, followUp)
    }

    @Test
    fun anInterruptedRequestReportsItsCacheInvalidationWithTheUnknownResult() = withCache { root, cache -> runBlocking {
        val events = mutableListOf<SupportDiagnosticEventDraft>()
        fun invalidate() = invalidateAfterConfirmedFileMutation(listOf("Documents/a.txt"), events::add) { path ->
            cache.invalidate(ACCOUNT, path)
        }

        val completed = assertIs<FileMutationOutcomeUnknownException>(failureOf {
            reportingUnknownFileMutationResult(::invalidate) { throw java.io.IOException("connection reset") }
        })
        // A file where the account's cache directory belongs makes the next invalidation fail.
        File(root, ACCOUNT).deleteRecursively()
        File(root, ACCOUNT).writeText("blocked")
        val failed = assertIs<FileMutationOutcomeUnknownException>(failureOf {
            reportingUnknownFileMutationResult(::invalidate) { throw java.io.IOException("connection reset") }
        })

        assertEquals(FileMutationLocalFollowUp.Completed, completed.localFollowUp)
        assertIs<java.io.IOException>(completed.cause)
        assertEquals(FileMutationLocalFollowUp.Failed, failed.localFollowUp, "A failed invalidation is not reported as complete.")
    } }

    @Test
    fun aDefinitiveFailureOrCancellationIsNotAnUnknownResult(): Unit = runBlocking {
        var invalidated = false
        val invalidate = { invalidated = true; FileMutationLocalFollowUp.Completed }

        assertIs<IllegalArgumentException>(failureOf { reportingUnknownFileMutationResult(invalidate) { throw IllegalArgumentException("bad") } })
        assertIs<CancellationException>(failureOf { reportingUnknownFileMutationResult(invalidate) { throw CancellationException("closed") } })
        assertEquals(7, reportingUnknownFileMutationResult(invalidate) { 7 })
        assertFalse(invalidated)
    }

    /** The failure [block] ends with; a suspend call cannot run inside `assertFailsWith`. */
    private suspend fun failureOf(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (failure: Throwable) {
            failure
        }

    private fun withCache(block: (File, AndroidFileReadCache) -> Unit) {
        val root = Files.createTempDirectory("android-file-mutation-follow-up").toFile()
        try {
            block(root, AndroidFileReadCache(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun file(path: String) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = "text/plain",
        size = 1,
        lastModified = null,
        fileId = 7L,
        hasPreview = false,
        etag = "\"e\"",
    )

    private companion object {
        const val ACCOUNT = "cccccccccccccccccccccccccccccccc"
    }
}
