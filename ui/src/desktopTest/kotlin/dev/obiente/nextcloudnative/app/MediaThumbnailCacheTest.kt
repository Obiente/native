package dev.obiente.nextcloudnative.app

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MediaThumbnailCacheTest {
    @Test
    fun `cancelling a thumbnail producer does not cancel another waiting tile`() = runBlocking {
        val cache = MediaThumbnailCache(AccountPrivateMemoryGate())
        val release = CompletableDeferred<Unit>()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            cache.load(key()) { release.await(); ImageBitmap(8, 8) }
        }
        val current = ImageBitmap(8, 8)
        val remaining = async(start = CoroutineStart.UNDISPATCHED) { cache.load(key()) { current } }
        cancelled.cancelAndJoin()
        assertSame(current, remaining.await())
    }

    @Test
    fun `concurrent thumbnails share one decode and warm requests reuse the image`() = runBlocking {
        val cache = MediaThumbnailCache(AccountPrivateMemoryGate())
        val image = ImageBitmap(8, 8)
        val release = CompletableDeferred<Unit>()
        var decodes = 0
        val key = key()
        val requests = List(16) {
            async(start = CoroutineStart.UNDISPATCHED) {
                cache.load(key) { decodes += 1; release.await(); image }
            }
        }
        release.complete(Unit)
        requests.awaitAll().forEach { assertSame(image, it) }
        repeat(100) { assertSame(image, cache.load(key) { error("Unexpected warm decode") }) }
        assertEquals(1, decodes)
    }

    @Test
    fun `budget eviction drops cache ownership without invalidating mounted images`() = runBlocking {
        val cache = MediaThumbnailCache(AccountPrivateMemoryGate(), maximumBytes = 512)
        val first = ImageBitmap(8, 8)
        cache.load(key()) { first }
        cache.load(key().copy(preview = key().preview.copy(width = 9))) { ImageBitmap(8, 8) }
        var reloads = 0
        cache.load(key()) { reloads += 1; ImageBitmap(8, 8) }
        assertEquals(1, reloads)
        assertEquals(8, first.width)
        first.readPixels(IntArray(64))
    }

    @Test
    fun `retired producer cannot replace a reactivated account image`() = runBlocking {
        val gate = AccountPrivateMemoryGate()
        val cache = MediaThumbnailCache(gate)
        val key = key()
        val release = CompletableDeferred<Unit>()
        val stale = async(start = CoroutineStart.UNDISPATCHED) {
            cache.load(key) { release.await(); ImageBitmap(8, 8) }
        }
        val staleWaiter = async(start = CoroutineStart.UNDISPATCHED) {
            cache.load(key) { ImageBitmap(8, 8) }
        }
        gate.retireAccount(key.preview.account) { cache.purgeRetiredAccount(key.preview.account) }
        gate.activateAccount(key.preview.account)
        val current = ImageBitmap(8, 8)
        assertSame(current, cache.load(key) { current })
        release.complete(Unit)
        stale.await()
        staleWaiter.await()
        assertSame(current, cache.load(key) { error("Unexpected stale publication") })
    }

    private fun key(): MediaThumbnailKey = MediaThumbnailKey(
        PreviewCacheKey("synthetic-account", "media-thumbnail-platform-v1", 1L, "etag", 8, 8),
        NextcloudFile(
            path = "Photos/test.jpg", name = "test.jpg", isDirectory = false,
            fileId = 1L, etag = "etag", mimeType = "image/jpeg",
            size = 1L, lastModified = null, hasPreview = true,
        ),
    )
}
