package dev.obiente.nextcloudnative.app

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A video range session is an account resource: it opens only for committed UI and always closes. */
class NativeVideoRangeSourceOwnerSceneTest {
    private val session = NextcloudSession("https://range-owner.invalid", "alice", "synthetic")
    private val video = NextcloudFile(
        path = "Photos/clip.mp4",
        name = "clip.mp4",
        isDirectory = false,
        mimeType = "video/mp4",
        size = 4_096L,
        lastModified = null,
        fileId = 41L,
        hasPreview = true,
        etag = "current-etag",
    )

    @Test
    fun `a needed source opens after commit and closes when the viewer leaves`() {
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val services = services(opens, closes)
        val plan = NativeVideoCompatibilityRangePlan.WholeFile(video)
        var shown by mutableStateOf(true)
        var observed: NativeVideoRangeSourceSlot? = null
        nativeSceneTest(400, 300, content = {
            if (shown) {
                observed = rememberNativeVideoRangeSource(plan, needed = true, attempt = 0, session, "alice", services)
            }
            Text("host")
        }) {
            settleUntil { observed is NativeVideoRangeSourceSlot.Open }
            assertTrue(observed is NativeVideoRangeSourceSlot.Open)
            assertEquals(4_096L, observed?.sourceOrNull?.size)
            assertEquals(1, opens.get())
            assertEquals(0, closes.get())

            shown = false
            settleUntil { closes.get() == 1 }
            assertEquals(1, closes.get(), "Leaving the viewer closes the session.")
        }
    }

    @Test
    fun `an unneeded or unsafe source is unavailable without opening anything`() {
        val opens = AtomicInteger()
        val services = services(opens, AtomicInteger())
        val unsafe = NativeVideoCompatibilityRangePlan.WholeFile(video.copy(size = null))
        val slots = mutableListOf<NativeVideoRangeSourceSlot>()
        nativeSceneTest(400, 300, content = {
            slots += rememberNativeVideoRangeSource(
                NativeVideoCompatibilityRangePlan.WholeFile(video), needed = false, attempt = 0, session, "alice", services,
            )
            slots += rememberNativeVideoRangeSource(unsafe, needed = true, attempt = 0, session, "alice", services)
            Text("host")
        }) {
            settleUntil { slots.takeLast(2).all { it == NativeVideoRangeSourceSlot.Unavailable } }
            assertEquals(NativeVideoRangeSourceSlot.Unavailable, slots[slots.size - 2])
            assertEquals(NativeVideoRangeSourceSlot.Unavailable, slots.last(), "A source without a size cannot be pinned.")
            assertEquals(0, opens.get())
        }
    }

    private fun services(opens: AtomicInteger, closes: AtomicInteger): NextcloudPlatformServices =
        Proxy.newProxyInstance(
            NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "SyntheticRangeServices"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                "openFileRangeSession" -> {
                    opens.incrementAndGet()
                    NextcloudFileRangeSession(
                        size = args!![3] as Long,
                        readBlock = { _, length -> ByteArray(length) },
                        closeBlock = { closes.incrementAndGet() },
                    )
                }
                else -> error("Unexpected synthetic range operation: ${method.name}")
            }
        } as NextcloudPlatformServices
}
