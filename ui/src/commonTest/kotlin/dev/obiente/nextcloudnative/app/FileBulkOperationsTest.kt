package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileBulkOperationsTest {
    private val accountId = deriveNextcloudAccountId("https://cloud.example.test", "synthetic-user")

    private fun file(
        path: String,
        etag: String? = "e",
        favorite: Boolean = false,
        directory: Boolean = false,
        checksums: List<String> = emptyList(),
    ) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = directory,
        mimeType = if (directory) null else "text/plain",
        size = 1,
        lastModified = null,
        fileId = path.hashCode().toLong().coerceAtLeast(1),
        hasPreview = false,
        etag = etag,
        favorite = favorite,
        checksums = checksums,
    )

    /** Synthetic folder tree keyed by folder path, holding item names. */
    private class FakeFiles {
        val folders = mutableMapOf("Docs" to mutableSetOf("a.txt", "b.txt", "c.txt"), "Archive" to mutableSetOf("b.txt"))
        val failures = mutableMapOf<String, Exception>()
        val appliedThenUnknown = mutableSetOf<String>()
        val mutations = mutableListOf<NextcloudFileMutation>()
        val favorites = mutableListOf<Pair<String, Boolean>>()
        /** Sizes by item path for entries that differ from the default one-byte file. */
        val sizes = mutableMapOf<String, Long>()
        /** Content checksums by item path; an entry without one has none. */
        val checksums = mutableMapOf<String, List<String>>()

        fun mutate(mutation: NextcloudFileMutation): NextcloudFileMutationResult {
            mutations += mutation
            failures[mutation.sourcePath]?.let { throw it }
            val parent = mutation.sourcePath.substringBeforeLast('/')
            val name = mutation.sourcePath.substringAfterLast('/')
            when (mutation) {
                is NextcloudFileMutation.Move -> {
                    if (name in folders.getValue(mutation.destinationDirectoryPath)) {
                        throw NextcloudFileOperationException(NextcloudFileOperationError.Conflict, 412, "exists")
                    }
                    folders.getValue(parent) -= name
                    folders.getValue(mutation.destinationDirectoryPath) += name
                }
                is NextcloudFileMutation.Copy -> folders.getValue(mutation.destinationDirectoryPath) += name
                is NextcloudFileMutation.Delete -> folders.getValue(parent) -= name
                is NextcloudFileMutation.Rename -> error("unused")
            }
            if (mutation.sourcePath in appliedThenUnknown) {
                throw FileMutationOutcomeUnknownException("interrupted", null, FileMutationLocalFollowUp.Completed)
            }
            return NextcloudFileMutationResult(null, "e2")
        }

        fun listing(folder: String) = NextcloudFileListing(
            folders.getValue(folder).map { name ->
                NextcloudFile(
                    "$folder/$name", name, false, "text/plain", sizes["$folder/$name"] ?: 1, null, 1, false,
                    etag = "e", checksums = checksums["$folder/$name"].orEmpty(),
                )
            },
            NextcloudFileListingSource.Network,
        )
    }

    private suspend fun runner(fake: FakeFiles): FileBulkOperationRunner {
        val deletes = FileWriteFenceRegistry(CoroutineScope(coroutineContext.minusKey(Job))).account(accountId).deletes
        return FileBulkOperationRunner(
            deletes = deletes,
            mutate = fake::mutate,
            setFavorite = { file, favorite ->
                fake.favorites += file.path to favorite
                FileMutationLocalFollowUp.Completed
            },
            readFolder = { folder -> fake.listing(folder) },
        )
    }

    @Test
    fun moveReportsConflictsWithoutOverwritingAndContinues(): Unit = runBlocking {
        val fake = FakeFiles()
        val report = runner(fake).transfer(listOf(file("Docs/a.txt"), file("Docs/b.txt"), file("Docs/c.txt")), "Archive", copy = false)
        assertEquals(listOf("Docs/a.txt", "Docs/c.txt"), report.completed.map { it.itemKey })
        val conflict = report.failed.single()
        assertEquals("Docs/b.txt", conflict.itemKey)
        assertTrue(conflict.reason!!.contains("Nothing was overwritten"))
        assertEquals(false, conflict.retrySafe)
        assertTrue(fake.mutations.all { it.expectedEtag == "e" })
        assertEquals("2 of 3 items moved to Archive. 1 failed.", report.summary())
    }

    @Test
    fun unknownMoveIsVerifiedByReadingBothFolders(): Unit = runBlocking {
        val fake = FakeFiles().apply { appliedThenUnknown += "Docs/a.txt" }
        val report = runner(fake).transfer(listOf(file("Docs/a.txt")), "Archive", copy = false)
        assertEquals(BulkItemOutcome.Completed, report.results.single().outcome)
    }

    @Test
    fun unknownMoveThatDidNotHappenIsRetrySafe(): Unit = runBlocking {
        val fake = FakeFiles().apply { failures["Docs/a.txt"] = IllegalStateException("reset") }
        val result = runner(fake).transfer(listOf(file("Docs/a.txt")), "Archive", copy = false).results.single()
        assertEquals(BulkItemOutcome.Failed, result.outcome)
        assertTrue(result.retrySafe)
    }

    @Test
    fun unknownCopyIsVerifiedAgainstTheMatchingDestinationFile(): Unit = runBlocking {
        val fake = FakeFiles().apply {
            appliedThenUnknown += "Docs/a.txt"
            checksums["Archive/a.txt"] = listOf("sha256:AB12")
        }
        val source = file("Docs/a.txt", checksums = listOf("SHA256:ab12"))
        val result = runner(fake).transfer(listOf(source), "Archive", copy = true).results.single()
        assertEquals(BulkItemOutcome.Completed, result.outcome)
    }

    @Test
    fun unknownCopyIsNotConfirmedBySizeAndTypeAlone(): Unit = runBlocking {
        val fake = FakeFiles().apply { failures["Docs/b.txt"] = IllegalStateException("reset") }
        // Archive already holds another b.txt with the same size and type.
        val result = runner(fake).transfer(listOf(file("Docs/b.txt")), "Archive", copy = true).results.single()
        assertEquals(BulkItemOutcome.Unconfirmed, result.outcome)
    }

    @Test
    fun unknownCopyIsNotConfirmedByAFileWithAnotherChecksum(): Unit = runBlocking {
        val fake = FakeFiles().apply {
            failures["Docs/b.txt"] = IllegalStateException("reset")
            checksums["Archive/b.txt"] = listOf("SHA256:ffff")
        }
        val source = file("Docs/b.txt", checksums = listOf("SHA256:ab12"))
        val result = runner(fake).transfer(listOf(source), "Archive", copy = true).results.single()
        assertEquals(BulkItemOutcome.Failed, result.outcome)
        assertEquals(false, result.retrySafe)
    }

    @Test
    fun unknownCopyIsNotConfirmedByADifferentFileWithTheSameName(): Unit = runBlocking {
        val fake = FakeFiles().apply {
            sizes["Archive/b.txt"] = 99
            failures["Docs/b.txt"] = IllegalStateException("reset")
        }
        val result = runner(fake).transfer(listOf(file("Docs/b.txt")), "Archive", copy = true).results.single()
        assertEquals(BulkItemOutcome.Failed, result.outcome)
        assertEquals(false, result.retrySafe)
        assertTrue(result.reason!!.contains("different item"))
    }

    @Test
    fun itemsWithoutVersionOrAlreadyThereAreSkipped(): Unit = runBlocking {
        val fake = FakeFiles()
        val report = runner(fake).transfer(listOf(file("Docs/a.txt", etag = null), file("Archive/b.txt")), "Archive", copy = true)
        assertEquals(listOf(BulkItemOutcome.Skipped, BulkItemOutcome.Skipped), report.results.map { it.outcome })
        assertTrue(fake.mutations.isEmpty())
    }

    @Test
    fun folderCannotBeMovedIntoItself(): Unit = runBlocking {
        val fake = FakeFiles().apply { folders["Docs/sub"] = mutableSetOf() }
        val result = runner(fake).transfer(listOf(file("Docs", directory = true)), "Docs/sub", copy = false).results.single()
        assertEquals(BulkItemOutcome.Failed, result.outcome)
        assertTrue(fake.mutations.isEmpty())
    }

    @Test
    fun deleteUsesVerifiedOutcomesAndPermissionFailuresBlockRetry(): Unit = runBlocking {
        val fake = FakeFiles().apply {
            failures["Docs/b.txt"] = NextcloudFileOperationException(NextcloudFileOperationError.PermissionDenied, 403, "Not allowed")
        }
        val runner = runner(fake)
        val report = runner.delete(listOf(file("Docs/a.txt"), file("Docs/b.txt")))
        assertEquals(BulkItemOutcome.Completed, report.results[0].outcome)
        assertEquals(BulkItemOutcome.Failed, report.results[1].outcome)
        assertEquals(false, report.results[1].retrySafe)
        // The recorded block keeps another bulk run from repeating it before a refresh.
        val again = runner.delete(listOf(file("Docs/b.txt"))).results.single()
        assertEquals(BulkItemOutcome.Skipped, again.outcome)
        assertNotNull(again.reason)
        assertEquals(2, fake.mutations.size)
    }

    @Test
    fun favoriteSkipsItemsAlreadyInTheRequestedState(): Unit = runBlocking {
        val fake = FakeFiles()
        val report = runner(fake).favorite(listOf(file("Docs/a.txt", favorite = true), file("Docs/b.txt")), favorite = true)
        assertEquals(listOf(BulkItemOutcome.AlreadyDone, BulkItemOutcome.Completed), report.results.map { it.outcome })
        assertEquals(listOf("Docs/b.txt" to true), fake.favorites)
        assertTrue(report.fullySucceeded)
    }
}
