package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** Memories gallery videos resolve to their Files original before anything is streamed. */
class MediaViewerVideoSceneTest {
    private val alice = NextcloudSession("https://media-video.invalid", "alice", "synthetic")
    private val galleryVideo = NextcloudFile(
        path = "memories/collections/timeline/20000/41",
        name = "clip.mp4",
        isDirectory = false,
        mimeType = "video/mp4",
        size = null,
        lastModified = null,
        fileId = 41L,
        hasPreview = true,
        etag = "gallery-etag",
        originalAccessAllowed = false,
        davPathAuthoritative = false,
        memoriesRenderAllowed = true,
        mediaDurationSeconds = 12,
    )
    private val filesVideo = galleryVideo.copy(
        path = "Photos/clip.mp4",
        size = 4_096L,
        etag = "current-etag",
        originalAccessAllowed = true,
        davPathAuthoritative = true,
        memoriesRenderAllowed = false,
    )

    @Test
    fun `a gallery video plays from its resolved Files original`() {
        val fake = FakeVideoServices(resolve = { mapOf(41L to filesVideo) })
        nativeSceneTest(800, 700, content = { Viewer(fake, listOf(galleryVideo)) }) {
            settleUntil { isEnabled("Play video") }
            click("Play video")
            settleUntil { fake.calls.any { it.startsWith("handoff:") } }

            assertEquals(listOf("resolve:[41]", "handoff:OpenWith:Photos/clip.mp4"), fake.calls.filterNot { it == "preview" })
            assertTrue(!has("Preparing video..."))
        }
    }

    @Test
    fun `a gallery video outside the account's Files stays preview-only and explains why`() {
        val fake = FakeVideoServices(resolve = { emptyMap() })
        nativeSceneTest(800, 700, content = { Viewer(fake, listOf(galleryVideo)) }) {
            settleUntil { has("Only a preview is available. This video is not in your Files.") }
            assertTrue(!isEnabled("Play video"))
            click("Play video")

            assertEquals(listOf("resolve:[41]"), fake.calls.filterNot { it == "preview" })
        }
    }

    @Test
    fun `a failed lookup can be retried without reopening the viewer`() {
        var attempts = 0
        val fake = FakeVideoServices(resolve = {
            attempts += 1
            if (attempts == 1) error("synthetic offline") else mapOf(41L to filesVideo)
        })
        nativeSceneTest(800, 700, content = { Viewer(fake, listOf(galleryVideo)) }) {
            settleUntil { has("Couldn't find this video's original file.") }
            click("Retry")
            settleUntil { isEnabled("Play video") }

            assertEquals(2, attempts)
            assertTrue(!has("Couldn't find this video's original file."))
        }
    }

    @Test
    fun `tapping the photo hides and restores the viewer controls`() {
        val photo = filesVideo.copy(path = "Photos/one.png", name = "one.png", mimeType = "image/png", fileId = 7L)
        val fake = FakeVideoServices(resolve = { error("A photo needs no lookup.") })
        nativeSceneTest(800, 700, content = { Viewer(fake, listOf(photo)) }) {
            settleUntil { has("Zoom in") }
            val center = Offset(400f, 400f)

            click(center)
            settleUntil { !has("Close preview") }
            assertTrue(!has("Close preview") && !has("Zoom in"), "Header and zoom bar hide together.")

            click(center)
            settleUntil { has("Close preview") }
            assertTrue(has("Zoom in"))
        }
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    @Test
    fun `swiping the fitted photo moves to the neighbor`() {
        val first = filesVideo.copy(path = "Photos/one.png", name = "one.png", mimeType = "image/png", fileId = 7L)
        val second = first.copy(path = "Photos/two.png", name = "two.png", fileId = 8L)
        val fake = FakeVideoServices(resolve = { error("A photo needs no lookup.") })
        nativeSceneTest(800, 700, content = { Viewer(fake, listOf(first, second)) }) {
            settleUntil { has("one.png") && has("Zoom in") }
            scene.sendPointerEvent(PointerEventType.Press, Offset(600f, 400f))
            for (step in 1..8) {
                scene.sendPointerEvent(PointerEventType.Move, Offset(600f - step * 50f, 402f))
                settle()
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(200f, 402f))
            settleUntil { has("two.png") }

            assertTrue(has("two.png"), "A clear leftward swipe shows the next item.")
        }
    }

    private fun NativeSceneTestDriver.isEnabled(label: String): Boolean {
        val target = node(label) ?: return false
        var current: androidx.compose.ui.semantics.SemanticsNode? = target
        while (current != null) {
            if (current.config.getOrNull(SemanticsProperties.Disabled) != null) return false
            current = current.parent
        }
        return true
    }

    @Composable
    private fun Viewer(fake: FakeVideoServices, media: List<NextcloudFile>) {
        var selected by remember { mutableStateOf(media.first()) }
        NextcloudMediaViewer(
            media = media,
            selected = selected,
            session = alice,
            userId = "alice",
            services = fake.services,
            fileDeletes = rememberFileDeletesForTests(alice),
            taggingAvailable = false,
            memoriesLivePhotoCapability = MemoriesLivePhotoCapability.NotAdvertised,
            sharingCapabilities = NextcloudFileSharingCapabilities(),
            onSelect = { selected = it },
            onSourceRemoved = {},
            onItemRemoved = {},
            onClose = {},
        )
    }

    private class FakeVideoServices(private val resolve: suspend () -> Map<Long, NextcloudFile>) {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val preview: ByteArray = Bitmap().run {
            allocN32Pixels(16, 16)
            erase(0xFF336699.toInt())
            Image.makeFromBitmap(this).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

        val services = Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "SyntheticMediaVideoServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                "getExternalFileHandoffSupport" -> ExternalFileHandoffSupport.Unsupported("synthetic")
                "resolveFilesById" -> {
                    calls += "resolve:${args!![2]}"
                    suspendInto(args.last()) { resolve() }
                }
                "loadPreview" -> {
                    calls += "preview"
                    suspendInto(args!!.last()) { preview }
                }
                "loadNativeMediaPreview" -> suspendInto(args!!.last()) { null }
                "handoffFileToExternalApp" -> {
                    val file = args!![2] as NextcloudFile
                    val action = args[3] as ExternalFileHandoffAction
                    calls += "handoff:$action:${file.path}"
                    suspendInto(args.last()) { ExternalFileHandoffResult.Launched(action) }
                }
                else -> suspendInto(assertNotNull(args).last()) {
                    error("Unexpected synthetic media operation: ${method.name}")
                }
            }
        } as NextcloudPlatformServices

        /** Runs [operation] for a proxied suspend call; a thrown failure resumes the caller instead of the proxy wrapping it. */
        @Suppress("UNCHECKED_CAST")
        private fun suspendInto(continuation: Any, operation: suspend () -> Any?): Any? {
            val caller = continuation as Continuation<Any?>
            return try {
                operation.startCoroutineUninterceptedOrReturn(caller)
            } catch (failure: Exception) {
                caller.intercepted().resumeWith(Result.failure(failure))
                COROUTINE_SUSPENDED
            }
        }
    }
}
