package dev.obiente.nextcloudnative

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
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
            remoteFolderPickerOperations(services(mutableListOf()), session, "alice", FileWriteFenceRegistry().account(session.accountId)),
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
        assertNull(incomingShareUploadBlock(FileWriteFenceRegistry().account(session.accountId), "Inbox/Old"))
    }

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
