package dev.obiente.nextcloudnative.app

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred

/** The media viewer's delete runs in the account's shared delete owner, like a Files delete. */
class MediaViewerDeleteSceneTest {
    private val alice = NextcloudSession("https://media-delete.invalid", "alice", "synthetic")
    private val bob = NextcloudSession("https://media-delete.invalid", "bob", "synthetic")
    private val first = photo("first.jpg", 7L)
    private val second = photo("second.jpg", 8L)
    private val third = photo("third.jpg", 9L)

    @Test
    fun `a confirmed delete removes the item, and incomplete local bookkeeping is reported first`() {
        for ((followUp, notice) in listOf(
            FileMutationLocalFollowUp.Completed to null,
            FileMutationLocalFollowUp.StillRunning to "Deleted first.jpg. Local file status is still updating in the background.",
        )) {
            val events = mutableListOf<String>()
            val fake = FakeMediaServices(delete = { NextcloudFileMutationResult(null, null, followUp) })
            nativeSceneTest(800, 700, content = { Viewer(fake, listOf(first, second), events) }) {
                click("Delete")
                settleUntil { events.isNotEmpty() || (notice != null && has(notice)) }

                if (notice != null) {
                    assertTrue(has(notice))
                    assertEquals(emptyList(), events, "The viewer does not move on as if everything finished.")
                    click("Close")
                }
                assertEquals(listOf("removed:first.jpg"), events)
                assertTrue(has("showing second.jpg of 1"), "The next item is selected.")
                assertEquals(listOf("delete"), fake.calls)
            }
        }
    }

    @Test
    fun `a running viewer delete blocks Files writes for the same account until it is verified`() {
        val events = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val fake = FakeMediaServices(delete = { release.await(); NextcloudFileMutationResult(null, null) })
        var registry: FileWriteFenceRegistry? = null
        nativeSceneTest(800, 700, content = {
            val scope = rememberCoroutineScope()
            val owned = remember { FileWriteFenceRegistry(scope).also { registry = it } }
            Viewer(fake, listOf(first, second), events, owned.account(alice.accountId).deletes)
        }) {
            click("Delete")
            val fence = assertNotNull(registry).account(alice.accountId)

            assertEquals(FileWriteBlock.DeleteRunning, fence.writeBlock("Photos/first.jpg"))
            assertEquals(FileWriteBlock.DeleteRunning, fence.writeBlock("Photos"), "Its folder waits too.")
            assertNull(fence.writeBlock("Photos/second.jpg"), "A sibling is unaffected.")
            assertNull(assertNotNull(registry).account(bob.accountId).writeBlock("Photos/first.jpg"))
            val files = FileDeleteDialogState(fence.deletes).apply { open(first) }
            assertNull(files.begin(), "A Files delete of the same item must wait.")

            release.complete(Unit)
            settleUntil { events.isNotEmpty() }
            assertEquals(listOf("removed:first.jpg"), events)
            assertNull(fence.writeBlock("Photos/first.jpg"))
        }
    }

    @Test
    fun `a stale version keeps the item, offers no immediate retry, and shows the reason`() {
        val events = mutableListOf<String>()
        val fake = FakeMediaServices(delete = { throw fileOperationException(412) })
        nativeSceneTest(800, 700, content = { Viewer(fake, listOf(first, second), events) }) {
            click("Delete")
            settleUntil { has("The file or destination changed. Refresh and try again.") }

            assertTrue(has("The file or destination changed. Refresh and try again."))
            click("Delete")
            assertEquals(listOf("delete"), fake.calls, "The stale precondition is not sent again.")
            assertTrue(events.isEmpty(), "The item stays in the viewer.")
        }
    }

    @Test
    fun `an unknown result that cannot be verified keeps the item and blocks a retry`() {
        val events = mutableListOf<String>()
        val fake = FakeMediaServices(
            delete = { throw IllegalStateException("response interrupted") },
            listing = { throw IllegalStateException("offline") },
        )
        nativeSceneTest(800, 700, content = { Viewer(fake, listOf(first, second), events) }) {
            click("Delete")
            settleUntil { has(unconfirmedDeleteMessage("first.jpg")) }

            assertTrue(has(unconfirmedDeleteMessage("first.jpg")))
            click("Delete")
            assertEquals(listOf("delete", "list:Photos"), fake.calls, "The unknown result is verified, never resent.")
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `a confirmed delete applies after the dialog closed and the user moved to another item`() {
        val events = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val fake = FakeMediaServices(delete = { release.await(); NextcloudFileMutationResult(null, null) })
        nativeSceneTest(800, 700, content = { Viewer(fake, listOf(first, second, third), events) }) {
            click("Delete")
            click("Close")
            click("Next")
            assertTrue(has("showing second.jpg of 3"))

            release.complete(Unit)
            settleUntil { events.contains("removed:first.jpg") }

            assertEquals(listOf("closed", "removed:first.jpg"), events)
            assertTrue(has("showing second.jpg of 2"), "The selection stays on the item the user moved to.")
        }
    }

    @Test
    fun `a non-confirmed outcome for an item the user moved away from keeps it and shows the reason on return`() {
        val events = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val fake = FakeMediaServices(delete = { release.await(); throw fileOperationException(412) })
        nativeSceneTest(800, 700, content = { Viewer(fake, listOf(first, second), events) }) {
            click("Delete")
            click("Close")
            click("Next")
            release.complete(Unit)
            settleUntil { fake.calls.isNotEmpty() }
            repeat(3) { settle() }

            assertTrue(has("showing second.jpg of 2"), "The item is kept.")
            assertFalse(has("first.jpg was not deleted"), "Its reason waits until it is shown.")
            click("Previous")
            settleUntil { has("first.jpg was not deleted") }
            assertTrue(has("The file or destination changed. Refresh and try again."))
            assertEquals(listOf("closed"), events)
        }
    }

    @Test
    fun `deleting the last item closes the viewer`() {
        val events = mutableListOf<String>()
        val fake = FakeMediaServices(delete = { NextcloudFileMutationResult(null, null) })
        nativeSceneTest(800, 700, content = { Viewer(fake, listOf(first), events) }) {
            click("Delete")
            settleUntil { events.isNotEmpty() }

            assertEquals(listOf("removed:first.jpg", "viewer closed"), events)
        }
    }

    @Test
    fun `closing the viewer mid-delete keeps the block, and a later viewer applies the outcome`() =
        withTestDeleteOwnerThread { registry ->
            val deletes = registry.account(alice.accountId).deletes
            val release = CompletableDeferred<Unit>()
            val fake = FakeMediaServices(delete = { release.await(); NextcloudFileMutationResult(null, null) })
            val firstEvents = mutableListOf<String>()
            nativeSceneTest(800, 700, content = { Viewer(fake, listOf(first, second), firstEvents, deletes) }) {
                click("Delete")
            }

            assertEquals(FileWriteBlock.DeleteRunning, deletes.fence.writeBlock("Photos/first.jpg"))
            release.complete(Unit)
            // The owner publishes the outcome, then releases the in-flight claim; wait for both.
            awaitTestCondition("the verified delete records its outcome and lifts the block") {
                deletes.completionFor(deletes.keyFor(first)) != null && deletes.fence.writeBlock("Photos/first.jpg") == null
            }
            assertTrue(firstEvents.isEmpty())

            val laterEvents = mutableListOf<String>()
            nativeSceneTest(800, 700, content = { Viewer(fake, listOf(first, second), laterEvents, deletes, open = false) }) {
                settleUntil { laterEvents.isNotEmpty() }
                assertEquals(listOf("removed:first.jpg"), laterEvents, "A recreated viewer applies the pending outcome.")
            }
        }

    @Test
    fun `the selection rule picks the next item, then the previous one, and closes on the last`() {
        val items = listOf(first, second, third)
        fun removeFrom(removed: NextcloudFile, selected: NextcloudFile): List<String> {
            val events = mutableListOf<String>()
            mediaViewerRemove(removed, items, selected, { events += "hide:$it" }, { events += "select:${it.name}" }) { events += "close" }
            return events
        }

        assertEquals(listOf("hide:Photos/third.jpg"), removeFrom(third, first), "Another item is only hidden.")
        assertEquals(listOf("hide:Photos/second.jpg", "select:third.jpg"), removeFrom(second, second))
        assertEquals(listOf("hide:Photos/third.jpg", "select:second.jpg"), removeFrom(third, third))
        val events = mutableListOf<String>()
        mediaViewerRemove(first, listOf(first), first, { events += "hide" }, { events += "select" }) { events += "close" }
        assertEquals(listOf("close"), events)
        assertEquals(listOf(second, third), mediaViewerItems(items, second, listOf("Photos/first.jpg")))
        assertEquals(listOf(first), mediaViewerItems(items, first, listOf("Photos/first.jpg")), "A shown item is never hidden from itself.")
    }

    /** A minimal viewer: the shared delete host, the viewer's list rule, and next/previous selection. */
    @Composable
    private fun Viewer(
        fake: FakeMediaServices,
        media: List<NextcloudFile>,
        events: MutableList<String>,
        deletes: FileDeleteCoordinator = rememberFileDeletesForTests(alice),
        open: Boolean = true,
    ) {
        var selected by remember { mutableStateOf(media.first()) }
        var deleteOpen by remember { mutableStateOf(open) }
        val removedPaths = remember { mutableStateListOf<String>() }
        val items = mediaViewerItems(media, selected, removedPaths)
        val index = items.indexOfFirst { it.path == selected.path }
        Column {
            Text("showing ${selected.name} of ${items.size}")
            TextButton(onClick = { items.getOrNull(index + 1)?.let { selected = it } }) { Text("Next") }
            TextButton(onClick = { items.getOrNull(index - 1)?.let { selected = it } }) { Text("Previous") }
        }
        MediaDeleteHost(items, selected, alice, "alice", fake.services, deletes, deleteOpen, onClose = {
            deleteOpen = false
            events += "closed"
        }) { removed ->
            events += "removed:${removed.name}"
            mediaViewerRemove(removed, items, selected, removedPaths::add, { selected = it }) { events += "viewer closed" }
        }
    }

    private fun photo(name: String, id: Long) = NextcloudFile(
        path = "Photos/$name",
        name = name,
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 1,
        lastModified = null,
        fileId = id,
        hasPreview = true,
        etag = "v1",
    )

    private class FakeMediaServices(
        private val delete: suspend () -> NextcloudFileMutationResult,
        private val listing: suspend () -> NextcloudFileListing = { error("The folder must not be read.") },
    ) {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val services = Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "SyntheticMediaDeleteServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                "executeFileMutation" -> {
                    calls += "delete"
                    suspendInto(args!!.last()) { delete() }
                }
                "listFilesWithSource" -> {
                    calls += "list:${args!![2]}"
                    suspendInto(args.last()) { listing() }
                }
                else -> error("Unexpected synthetic media operation: ${method.name}")
            }
        } as NextcloudPlatformServices

        /** Runs [operation] for a proxied suspend call; a thrown failure resumes the caller instead of the proxy wrapping it. */
        @Suppress("UNCHECKED_CAST")
        private fun suspendInto(continuation: Any, operation: suspend () -> Any?): Any? {
            val caller = continuation as Continuation<Any?>
            return try {
                operation.startCoroutineUninterceptedOrReturn(caller)
            } catch (failure: Exception) {
                // Dispatched, so the caller resumes after this call has returned suspended.
                caller.intercepted().resumeWith(Result.failure(failure))
                COROUTINE_SUSPENDED
            }
        }
    }
}
