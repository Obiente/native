package dev.obiente.nextcloudnative.app

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Retains immutable display images. Eviction drops ownership without closing/recycling a bitmap
 * that a mounted Compose image may still use. Native resources follow ImageBitmap's lifetime.
 */
internal class MediaThumbnailCache(
    private val gate: AccountPrivateMemoryGate = sharedAccountPrivateMemoryGate,
    private val maximumBytes: Long = 24L * 1024L * 1024L,
) {
    private val entries = linkedMapOf<MediaThumbnailKey, ImageBitmap>()
    private val pending = mutableMapOf<Pair<MediaThumbnailKey, Long>, Pending>()
    private var bytes = 0L

    private class Pending(val mutex: Mutex = Mutex(), var users: Int = 0, var result: ImageBitmap? = null)

    init { require(maximumBytes > 0L) }

    suspend fun load(key: MediaThumbnailKey, fetch: suspend () -> ImageBitmap?): ImageBitmap? {
        val account = key.preview.account
        // A retired account must not start authenticated work or publish a late result.
        val producer = gate.producer(account) ?: return null
        val requestKey = key to producer.incarnation
        val request = gate.withLock {
            pending.getOrPut(requestKey) { Pending() }.also { it.users += 1 }
        }
        try {
            return request.mutex.withLock {
                // One locked read, so retirement cannot be mistaken for a cache miss that fetches.
                val cached = gate.read(producer, ThumbnailLookup.Retired) {
                    (request.result ?: entries.remove(key)?.also { entries[key] = it })
                        ?.let(ThumbnailLookup::Hit) ?: ThumbnailLookup.Miss
                }
                when (cached) {
                    ThumbnailLookup.Retired -> return@withLock null
                    is ThumbnailLookup.Hit -> return@withLock cached.image
                    ThumbnailLookup.Miss -> Unit
                }
                fetch()?.let { image -> gate.read(producer, null) { image } }?.also { image ->
                    request.result = image
                    val weight = image.weight()
                    if (weight <= maximumBytes) gate.mutate(account, producer) {
                        entries.remove(key)?.let { bytes -= it.weight() }
                        entries[key] = image
                        bytes += weight
                        while (bytes > maximumBytes || entries.size > 256) {
                            bytes -= checkNotNull(entries.remove(entries.keys.first())).weight()
                        }
                    }
                }
            }
        } finally {
            gate.withLock {
                request.users -= 1
                if (request.users == 0) pending.remove(requestKey)
            }
        }
    }

    internal fun purgeRetiredAccount(account: String) {
        entries.keys.filter { it.preview.account == account }.forEach { key ->
            bytes -= checkNotNull(entries.remove(key)).weight()
        }
    }

    // Budget for up to eight bytes per pixel, including wide-color native storage.
    private fun ImageBitmap.weight(): Long = width.toLong() * height.toLong() * 8L
}

private sealed interface ThumbnailLookup {
    data object Retired : ThumbnailLookup
    data object Miss : ThumbnailLookup
    data class Hit(val image: ImageBitmap) : ThumbnailLookup
}

internal data class MediaThumbnailKey(val preview: PreviewCacheKey, val source: NextcloudFile)

internal val sharedMediaThumbnailCache = MediaThumbnailCache()

/** One decoder and orientation policy for every thumbnail, separate from full-size viewer images. */
internal suspend fun NextcloudPlatformServices.loadMediaThumbnailImage(
    session: NextcloudSession,
    file: NextcloudFile,
    userId: String? = null,
    width: Int = DEFAULT_PREVIEW_DIMENSION,
    height: Int = DEFAULT_PREVIEW_DIMENSION,
): ImageBitmap? {
    val safeWidth = boundedPreviewDimension(width)
    val safeHeight = boundedPreviewDimension(height)
    val load: suspend () -> ImageBitmap? = {
        loadMediaThumbnailDecoded(session, file, userId, safeWidth, safeHeight) { payload ->
            decodePlatformImageSampled(
                payload.bytes, maxOf(safeWidth, safeHeight), payload.kind.orientationPolicy(),
            )?.image
        }
    }
    val fileId = file.fileId ?: return load()
    val key = previewCacheKeyOrNull(
        previewCacheDigest(session), "media-thumbnail-platform-v1", fileId, file.etag, safeWidth, safeHeight,
    ) ?: return load()
    return sharedMediaThumbnailCache.load(MediaThumbnailKey(key, file), load)
}
