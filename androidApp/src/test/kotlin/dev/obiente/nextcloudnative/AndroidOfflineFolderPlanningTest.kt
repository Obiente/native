package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.FileOfflineAvailability
import dev.obiente.nextcloudnative.app.FileOfflineKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidOfflineFolderPlanningTest {
    @Test
    fun traversesDeterministicallyAndAccountsEveryFileByte() {
        val listings = mapOf(
            "Vault" to listOf(directory("Vault/Work"), file("Vault/root.md", 12, "\"root\"")),
            "Vault/Work" to listOf(file("Vault/Work/todo.md", 30, "\"todo\"")),
        )

        val inventory = planAndroidOfflineFolder(directory("Vault")) { listings[it].orEmpty() }

        assertEquals(listOf("Vault", "Vault/Work"), inventory.directories.map { it.path })
        assertEquals(listOf("Vault/Work/todo.md", "Vault/root.md"), inventory.files.map { it.path })
        assertEquals(42, inventory.totalBytes)
    }

    @Test
    fun defaultOfflinePlanAcceptsFilesBeyondTheFormerAggregateBudget() {
        val size = 12L * 1024L * 1024L * 1024L
        val inventory = planAndroidOfflineFolder(directory("Vault")) {
            listOf(file("Vault/archive.bin", size, "\"archive\""))
        }

        assertEquals(size, inventory.totalBytes)
        assertEquals(size, inventory.files.single().size)
    }

    @Test
    fun rejectsEscapingDuplicateAndUnversionedResponses() {
        assertFailsWith<IllegalArgumentException> {
            planAndroidOfflineFolder(directory("Vault")) {
                listOf(file("Elsewhere/private.md", 1, "\"etag\""))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            planAndroidOfflineFolder(directory("Vault")) {
                listOf(file("Vault/a.md", 1, "\"etag\""), file("Vault/a.md", 1, "\"etag\""))
            }
        }
        val unversioned = assertFailsWith<IllegalArgumentException> {
            planAndroidOfflineFolder(directory("Vault")) {
                listOf(file("Vault/a.md", 1, null))
            }
        }
        assertTrue(unversioned.message.orEmpty().contains("version"))
    }

    @Test
    fun enforcesDepthEntryAndAggregateByteBudgets() {
        assertFailsWith<IllegalStateException> {
            planAndroidOfflineFolder(
                root = directory("Vault"),
                limits = AndroidOfflineFolderLimits(maximumDepth = 1),
            ) { path ->
                when (path) {
                    "Vault" -> listOf(directory("Vault/A"))
                    else -> listOf(file("Vault/A/deep.md", 1, "\"deep\""))
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            planAndroidOfflineFolder(
                root = directory("Vault"),
                limits = AndroidOfflineFolderLimits(maximumEntries = 2),
            ) {
                listOf(file("Vault/a", 1, "\"a\""), file("Vault/b", 1, "\"b\""))
            }
        }
        val tooLarge = assertFailsWith<IllegalArgumentException> {
            planAndroidOfflineFolder(
                root = directory("Vault"),
                limits = AndroidOfflineFolderLimits(maximumTotalBytes = 10),
            ) {
                listOf(file("Vault/a", 6, "\"a\""), file("Vault/b", 5, "\"b\""))
            }
        }
        assertTrue(tooLarge.message.orEmpty().contains("budget"))
    }

    @Test
    fun folderPinCreatesPerFileEtagJobsAndUnpinHonorsDirectPins() {
        val inventory = planAndroidOfflineFolder(directory("Vault")) {
            listOf(file("Vault/note.md", 9, "\"note-v1\""))
        }
        val pinned = planAndroidOfflineFolderPin(
            current = AndroidFileOfflinePersistedState(),
            accountId = "account-a",
            inventory = inventory,
            nowEpochMillis = 10,
            localGenerationExists = { _, _ -> false },
        )
        val key = FileOfflineKey("account-a", "Vault/note.md")

        assertEquals("\"note-v1\"", pinned.queue.jobs.single().expectedRemoteEtag)
        assertEquals(FileOfflineAvailability.Queued, pinned.queue.availability(key))
        assertEquals(listOf("Vault/note.md"), pinned.folders.roots.single().filePaths)

        val retained = planAndroidOfflineFolderUnpin(
            current = pinned.copy(folders = pinned.folders.copy(directPins = setOf(key))),
            accountId = "account-a",
            rootPath = "Vault",
            nowEpochMillis = 20,
            localGenerationExists = { _, _ -> false },
        )
        assertTrue(retained.folders.roots.isEmpty())
        assertEquals(FileOfflineAvailability.Queued, retained.queue.availability(key))

        val removed = planAndroidOfflineFolderUnpin(
            current = pinned,
            accountId = "account-a",
            rootPath = "Vault",
            nowEpochMillis = 20,
            localGenerationExists = { _, _ -> false },
        )
        assertTrue(removed.folders.roots.isEmpty())
        assertEquals(FileOfflineAvailability.OnlineOnly, removed.queue.availability(key))
    }

    @Test
    fun storedTreeSynthesizesOnlyTheAncestorsNeededForOfflineBrowsing() {
        val root = AndroidOfflineFolderRoot(
            accountId = "account-a",
            rootPath = "Documents/Projects/Native",
            rootDisplayName = "Native",
            directories = listOf(
                AndroidOfflineDirectory(
                    path = "Documents/Projects/Native",
                    displayName = "Native",
                    remoteEtag = "\"native\"",
                    lastModified = null,
                    fileId = 9,
                ),
                AndroidOfflineDirectory(
                    path = "Documents/Projects/Native/Design",
                    displayName = "Design",
                    remoteEtag = "\"design\"",
                    lastModified = null,
                    fileId = 10,
                ),
            ),
            filePaths = emptyList(),
        )

        val directories = AndroidOfflineFolderState(roots = listOf(root))
            .offlineDirectories("account-a")

        assertEquals(
            setOf(
                "Documents",
                "Documents/Projects",
                "Documents/Projects/Native",
                "Documents/Projects/Native/Design",
            ),
            directories.keys,
        )
        assertTrue(AndroidOfflineFolderState(roots = listOf(root)).offlineDirectories("other").isEmpty())
    }

    private fun directory(path: String) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = true,
        mimeType = null,
        size = null,
        lastModified = "now",
        fileId = path.hashCode().toLong(),
        hasPreview = false,
        etag = "\"dir-$path\"",
    )

    private fun file(path: String, size: Long, etag: String?) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = "text/markdown",
        size = size,
        lastModified = "now",
        fileId = path.hashCode().toLong(),
        hasPreview = false,
        etag = etag,
    )
}
