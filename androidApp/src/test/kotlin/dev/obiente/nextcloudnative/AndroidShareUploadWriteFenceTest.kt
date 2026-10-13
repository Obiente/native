package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileMutationNotSentException
import dev.obiente.nextcloudnative.app.FileWriteBlock
import dev.obiente.nextcloudnative.app.FileWriteBlockedException
import dev.obiente.nextcloudnative.app.FileWriteFence
import dev.obiente.nextcloudnative.app.FileWriteFenceRegistry
import dev.obiente.nextcloudnative.app.NextcloudPlatformServices
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.RemoteFolderSelectionAccess
import dev.obiente.nextcloudnative.app.incomingShareUploadBlock
import dev.obiente.nextcloudnative.app.remoteFolderPickerOperations
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

class AndroidShareUploadWriteFenceTest {
    private val session = NextcloudSession("https://share-fence.example.test", "alice", "fixture-password")
    private val block = FileWriteBlock.RefreshRequired("Inbox/Old")
    private val fence = FileWriteFence { path -> block.takeIf { path == "Inbox/Old" || path.startsWith("Inbox/Old/") } }

    @Test
    fun blockedDestinationIsRefusedBeforeFolderPermissionsAreInspected(): Unit = runBlocking {
        val inspected = mutableListOf<String>()
        val operations = androidIncomingShareFolderPickerOperations(
            remoteFolderPickerOperations(services(mutableListOf()), session, "alice", fence),
        ) { path -> inspected += path; access(canCreateFiles = true) }

        assertEquals(RemoteFolderSelectionAccess.Denied(block.message), operations.selectionAccess("Inbox/Old"))
        assertTrue(inspected.isEmpty(), "A blocked destination makes no permission request.")
        assertEquals(RemoteFolderSelectionAccess.Allowed, operations.selectionAccess("Inbox"))
        assertEquals(listOf("Inbox"), inspected)
    }

    @Test
    fun newFolderInsideABlockedFolderIsNotCreated(): Unit = runBlocking {
        val calls = mutableListOf<String>()
        val operations = androidIncomingShareFolderPickerOperations(
            remoteFolderPickerOperations(services(calls), session, "alice", fence),
        ) { access(canCreateDirectories = true) }

        assertFailsWith<FileWriteBlockedException> { operations.createDirectoryIfAbsent("Inbox/Old/Photos") }
        operations.createDirectoryIfAbsent("Inbox/Photos")

        assertEquals(listOf("createDirectoryIfAbsent:Inbox/Photos"), calls)
    }

    @Test
    fun folderPermissionsStillApplyWhenTheFenceAllows(): Unit = runBlocking {
        val operations = androidIncomingShareFolderPickerOperations(
            remoteFolderPickerOperations(services(mutableListOf()), session, "alice", FileWriteFenceRegistry(CoroutineScope(Job())).account(session.accountId)),
        ) { access(canCreateFiles = false, canCreateDirectories = false) }

        assertEquals(
            RemoteFolderSelectionAccess.Denied("This Nextcloud folder is read-only for this account."),
            operations.selectionAccess("Inbox"),
        )
        assertFailsWith<IllegalArgumentException> { operations.createDirectoryIfAbsent("Inbox/New") }
    }

    @Test
    fun queueingIntoABlockedFolderIsRefusedAndAnUnblockedAccountIsNot() {
        assertEquals(block, incomingShareUploadBlock(fence, "Inbox/Old"))
        assertNull(incomingShareUploadBlock(fence, "Inbox"))
        assertNull(incomingShareUploadBlock(FileWriteFenceRegistry(CoroutineScope(Job())).account(session.accountId), "Inbox/Old"))
    }

    @Test
    fun aBlockBetweenFilesStopsBeforeTheNextFileAndAResumedRunNeverResendsFinishedFiles(): Unit = runBlocking {
        val published = mutableListOf<Int>()
        var deleteStarted = false

        val (paused, allPublished) = uploadIncomingShareFilesUntilBlocked(threeFiles(), { deleteStarted }) { current, index ->
            published += index
            deleteStarted = true
            current.published(index)
        }

        assertFalse(allPublished)
        assertEquals(listOf(0), published, "The next file is not sent once the delete started.")
        assertEquals(1, paused.completedFiles, "The finished file stays completed.")

        val (finished, resumedAll) = uploadIncomingShareFilesUntilBlocked(paused, { false }) { current, index ->
            published += index
            current.published(index)
        }

        assertTrue(resumedAll)
        assertEquals(listOf(0, 1, 2), published, "The resumed run starts at the first unfinished file.")
        assertEquals(3, finished.completedFiles)
    }

    @Test
    fun aBlockBeforeTheFirstFileSendsNothing(): Unit = runBlocking {
        val (unchanged, allPublished) = uploadIncomingShareFilesUntilBlocked(threeFiles(), { true }) { _, _ ->
            error("No file may be sent while the destination is blocked.")
        }

        assertFalse(allPublished)
        assertEquals(0, unchanged.completedFiles)
    }

    @Test
    fun anAccountChangeBeforeSendingIsReportedAsNotSent(): Unit = runBlocking {
        val other = NextcloudSession("https://share-fence.example.test", "bob", "fixture-password")
        var sent = false

        assertFailsWith<FileMutationNotSentException> {
            AndroidAccountOperationGuard().withAuthenticatedMutationSession(session, { other }) { sent = true }
        }
        assertFalse(sent)
    }

    private fun threeFiles() = AndroidIncomingShareRequest(
        id = "01234567-89ab-cdef-0123-456789abcdef",
        files = listOf("first.txt", "second.txt", "third.txt").mapIndexed { index, name ->
            AndroidIncomingShareFile(
                id = "1111111$index-2222-3333-4444-555555555555",
                displayName = name,
                mimeType = "text/plain",
                sizeBytes = 5,
                stagedName = "staged-$index",
            )
        },
        state = AndroidIncomingShareState.Uploading,
        accountId = "account-1",
        destinationPath = "Inbox",
    )

    private fun AndroidIncomingShareRequest.published(index: Int) =
        copy(completedFiles = index + 1, uploadedNames = uploadedNames + files[index].displayName)

    private fun access(canCreateFiles: Boolean = false, canCreateDirectories: Boolean = false) =
        DocumentDirectoryAccess(canCreateFiles = canCreateFiles, canCreateDirectories = canCreateDirectories)

    private fun services(calls: MutableList<String>): NextcloudPlatformServices =
        Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "SyntheticShareUploadServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                "createDirectoryIfAbsent" -> {
                    calls += "createDirectoryIfAbsent:${args!![2]}"
                    true
                }
                else -> error("Unexpected synthetic operation: ${method.name}")
            }
        } as NextcloudPlatformServices
}
