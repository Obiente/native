package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileMutationLocalFollowUp
import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.invalidateAfterConfirmedFileMutation
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

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
