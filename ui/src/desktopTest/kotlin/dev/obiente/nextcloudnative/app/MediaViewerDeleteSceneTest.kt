package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The media viewer's delete runs in the account's shared delete owner, like a Files delete. */
class MediaViewerDeleteSceneTest {
    private val alice = NextcloudSession("https://media-delete.invalid", "alice", "synthetic")
    private val bob = NextcloudSession("https://media-delete.invalid", "bob", "synthetic")
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
    fun `a confirmed delete removes the item, and incomplete local bookkeeping is reported first`() {
        for ((followUp, notice) in listOf(
            FileMutationLocalFollowUp.Completed to null,
            FileMutationLocalFollowUp.StillRunning to "Deleted photo.jpg. Local file status is still updating in the background.",
        )) {
            val events = mutableListOf<String>()
            val fake = FakeMediaServices(delete = { NextcloudFileMutationResult(null, null, followUp) })
            nativeSceneTest(800, 700, content = { Host(fake, events) }) {
                click("Delete")
                settleUntil { events.isNotEmpty() || (notice != null && has(notice)) }

                if (notice == null) {
                    assertEquals(listOf("removed"), events)
                } else {
                    assertTrue(has(notice))
                    assertEquals(emptyList(), events, "The viewer does not move on as if everything finished.")
                    click("Close")
                    assertEquals(listOf("removed"), events)
                }
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
            Host(fake, events, owned.account(alice.accountId).deletes)
        }) {
            click("Delete")
            val fence = assertNotNull(registry).account(alice.accountId)

            assertEquals(FileWriteBlock.DeleteRunning, fence.writeBlock("Photos/photo.jpg"))
            assertEquals(FileWriteBlock.DeleteRunning, fence.writeBlock("Photos"), "Its folder waits too.")
            assertNull(assertNotNull(registry).account(bob.accountId).writeBlock("Photos/photo.jpg"))
            val files = FileDeleteDialogState(fence.deletes).apply { open(photo) }
            assertNull(files.begin(), "A Files delete of the same item must wait.")

            release.complete(Unit)
            settleUntil { events.isNotEmpty() }
            assertEquals(listOf("removed"), events)
            assertNull(fence.writeBlock("Photos/photo.jpg"))
        }
    }

    @Test
    fun `a stale version keeps the item, offers no immediate retry, and shows the reason`() {
        val events = mutableListOf<String>()
        val fake = FakeMediaServices(delete = { throw fileOperationException(412) })
        nativeSceneTest(800, 700, content = { Host(fake, events) }) {
            click("Delete")
            settleUntil { has("The file or destination changed. Refresh and try again.") }

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
        nativeSceneTest(800, 700, content = { Host(fake, events) }) {
            click("Delete")
            settleUntil { has(unconfirmedDeleteMessage("photo.jpg")) }

            click("Delete")
            assertEquals(listOf("delete", "list:Photos"), fake.calls, "The unknown result is verified, never resent.")
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun `closing the viewer mid-delete keeps the block, and a later viewer applies the outcome`() {
        val registry = FileWriteFenceRegistry(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val deletes = registry.account(alice.accountId).deletes
        val release = CompletableDeferred<Unit>()
        val fake = FakeMediaServices(delete = { release.await(); NextcloudFileMutationResult(null, null) })
        val firstEvents = mutableListOf<String>()
        nativeSceneTest(800, 700, content = { Host(fake, firstEvents, deletes) }) {
            click("Delete")
        }

        assertEquals(FileWriteBlock.DeleteRunning, registry.account(alice.accountId).writeBlock("Photos/photo.jpg"))
        release.complete(Unit)
        runBlocking { withTimeout(5_000L) { while (deletes.completionFor(deletes.keyFor(photo)) == null) delay(10L) } }
        assertNull(registry.account(alice.accountId).writeBlock("Photos/photo.jpg"), "The verified delete lifts it.")
        assertTrue(firstEvents.isEmpty())

        val laterEvents = mutableListOf<String>()
        nativeSceneTest(800, 700, content = { Host(fake, laterEvents, deletes, open = false) }) {
            settleUntil { laterEvents.isNotEmpty() }
            assertEquals(listOf("removed"), laterEvents, "A recreated viewer applies the pending outcome.")
        }
    }

    @Composable
    private fun Host(
        fake: FakeMediaServices,
        events: MutableList<String>,
        deletes: FileDeleteCoordinator = rememberFileDeletesForTests(alice),
        open: Boolean = true,
    ) = MediaDeleteHost(photo, alice, "alice", fake.services, deletes, open, onClose = { events += "closed" }) {
        events += "removed"
    }

    private class FakeMediaServices(
        private val delete: suspend () -> NextcloudFileMutationResult,
        private val listing: suspend () -> NextcloudFileListing = { error("The folder must not be read.") },
    ) {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Suppress("UNCHECKED_CAST")
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
                kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            }
        }
    }
}
