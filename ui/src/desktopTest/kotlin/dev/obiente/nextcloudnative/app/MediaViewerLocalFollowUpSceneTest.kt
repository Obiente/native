package dev.obiente.nextcloudnative.app

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaViewerLocalFollowUpSceneTest {
    private val session = NextcloudSession("https://media-scene.invalid", "alice", "synthetic")
    private val photo = NextcloudFile(
        path = "Photos/photo.jpg",
        name = "photo.jpg",
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 1,
        lastModified = null,
        fileId = 7L,
        hasPreview = true,
        etag = "v1",
    )

    @Test
    fun `delete with incomplete local bookkeeping is reported before the viewer moves on`() {
        val events = mutableListOf<String>()
        nativeSceneTest(800, 700, content = { dialog(MediaViewerAction.Delete, FileMutationLocalFollowUp.StillRunning, events) }) {
            click("Delete")

            assertTrue(has("Deleted photo.jpg. Local file status is still updating in the background."))
            assertEquals(emptyList(), events, "The viewer must not move on as if everything finished.")
            click("Close")
            assertEquals(listOf("removed"), events)
        }
    }

    @Test
    fun `move whose local bookkeeping failed is reported, then removes the source`() {
        val events = mutableListOf<String>()
        nativeSceneTest(800, 700, content = { dialog(MediaViewerAction.Move, FileMutationLocalFollowUp.Failed, events) }) {
            replaceText("photo.jpg", "moved.jpg")
            click("Move")

            assertTrue(has("Moved photo.jpg. Local file status could not be updated; refresh the folder if it looks out of date."))
            assertEquals(emptyList(), events)
            click("Close")
            assertEquals(listOf("removed"), events)
        }
    }

    @Test
    fun `completed local bookkeeping finishes without an extra step`() {
        for ((action, button, expected) in listOf(
            Triple(MediaViewerAction.Delete, "Delete", "removed"),
            Triple(MediaViewerAction.Move, "Move", "removed"),
            Triple(MediaViewerAction.Copy, "Copy", "dismissed"),
        )) {
            val events = mutableListOf<String>()
            nativeSceneTest(800, 700, content = { dialog(action, FileMutationLocalFollowUp.Completed, events) }) {
                if (action != MediaViewerAction.Delete) replaceText("photo.jpg", "other.jpg")
                click(button)

                assertEquals(listOf(expected), events)
                assertFalse(has("Saved on the server"))
            }
        }
    }

    @Test
    fun `every media change is refused with the typed reason while a covering delete is unverified`() {
        for (action in listOf(
            MediaViewerAction.Move, MediaViewerAction.Copy, MediaViewerAction.Delete,
            MediaViewerAction.ShareNextcloud, MediaViewerAction.AddToAlbum,
        )) {
            val events = mutableListOf<String>()
            val fence = FileWriteFence { path -> FileWriteBlock.DeleteRunning.takeIf { path.startsWith("Photos/") } }
            nativeSceneTest(800, 700, content = { dialog(action, FileMutationLocalFollowUp.Completed, events, fence) }) {
                assertTrue(has("This file cannot be changed yet"), "$action must be refused.")
                assertTrue(has(FileWriteBlock.DeleteRunning.message))
                click("Close")
                assertEquals(listOf("dismissed"), events, "Nothing was sent for $action.")
            }
        }
    }

    @Test
    fun `a move into a folder that needs a refresh is refused before anything is sent`() {
        val events = mutableListOf<String>()
        val block = FileWriteBlock.RefreshRequired("Archive")
        val fence = FileWriteFence { path -> block.takeIf { path == "Archive" || path.startsWith("Archive/") } }
        nativeSceneTest(800, 700, content = { dialog(MediaViewerAction.Move, FileMutationLocalFollowUp.Completed, events, fence) }) {
            replaceText("Photos", "Archive")
            click("Move")

            assertTrue(has("Open or refresh your Nextcloud root before changing Archive or anything inside it."))
            assertEquals(emptyList(), events)
        }
    }

    @androidx.compose.runtime.Composable
    private fun dialog(
        action: MediaViewerAction,
        followUp: FileMutationLocalFollowUp,
        events: MutableList<String>,
        fence: FileWriteFence = FileWriteFence.None,
    ) =
        MediaViewerActionDialog(
            action = action,
            file = photo,
            session = session,
            userId = "alice",
            services = services(followUp),
            writeFence = fence,
            sharingCapabilities = NextcloudFileSharingCapabilities(),
            onDismiss = { events += "dismissed" },
            onSourceRemoved = { events += "removed" },
            onMutationRunningChanged = {},
        )

    private fun services(followUp: FileMutationLocalFollowUp): NextcloudPlatformServices =
        Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "SyntheticMediaServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                // The server confirmed the change; only the local bookkeeping differs.
                "executeFileMutation" -> NextcloudFileMutationResult(null, "v2", followUp)
                "getExternalFileHandoffSupport" -> ExternalFileHandoffSupport.Unsupported("synthetic")
                else -> error("Unexpected synthetic media operation: ${method.name}")
            }
        } as NextcloudPlatformServices
}
