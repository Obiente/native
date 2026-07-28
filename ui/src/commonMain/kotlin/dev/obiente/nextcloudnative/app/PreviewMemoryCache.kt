package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

/**
 * Small process-local LRU for previews. It removes repeated network and decode work while browsing
 * between grids and viewers. Persistent encrypted/offline caching remains a separate repository
 * concern because it needs account lifecycle, quotas, and platform storage policies.
 */
internal object PreviewMemoryCache {
    private const val MAX_BYTES = 24 * 1024 * 1024
    private val entries = linkedMapOf<PreviewCacheKey, ByteArray>()
    private var bytes = 0

    fun get(key: PreviewCacheKey): ByteArray? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    fun put(key: PreviewCacheKey, value: ByteArray) {
        if (value.size > MAX_BYTES) return
        entries.remove(key)?.let { bytes -= it.size }
        entries[key] = value
        bytes += value.size
        while (bytes > MAX_BYTES && entries.isNotEmpty()) {
            val oldestKey = entries.keys.first()
            bytes -= entries.remove(oldestKey)?.size ?: 0
        }
    }
}

internal data class PreviewCacheKey(
    val account: String,
    val variant: String,
    val fileId: Long,
    val etag: String?,
    val width: Int,
    val height: Int,
)

internal suspend fun NextcloudPlatformServices.loadPreviewCached(
    session: NextcloudSession,
    file: NextcloudFile,
    width: Int = DEFAULT_PREVIEW_DIMENSION,
    height: Int = DEFAULT_PREVIEW_DIMENSION,
): ByteArray {
    val fileId = requireNotNull(file.fileId) { "This item does not provide a preview file ID." }
    val safeWidth = boundedPreviewDimension(width)
    val safeHeight = boundedPreviewDimension(height)
    val key = PreviewCacheKey(
        account = previewCacheAccount(session),
        variant = "core-preview",
        fileId = fileId,
        etag = file.etag,
        width = safeWidth,
        height = safeHeight,
    )
    return PreviewMemoryCache.get(key) ?: loadPreview(session, fileId, safeWidth, safeHeight)
        .also { PreviewMemoryCache.put(key, it) }
}

internal suspend fun NextcloudPlatformServices.loadMemoriesDecodableImageCached(
    session: NextcloudSession,
    file: NextcloudFile,
): ByteArray {
    val fileId = requireNotNull(file.fileId) {
        "This item does not provide a stable Memories render file ID."
    }
    require(file.isPhotoMedia() && file.canUseMemoriesDecodableRender()) {
        "This item cannot use the Memories image renderer."
    }
    val key = PreviewCacheKey(
        account = previewCacheAccount(session),
        variant = "memories-decodable",
        fileId = fileId,
        etag = file.etag,
        width = 0,
        height = 0,
    )
    return PreviewMemoryCache.get(key) ?: memoriesDecodableImageResponseBytes(
        executeNextcloudApi(
            session,
            memoriesPhotoDecodableApiRequest(
                fileId = fileId,
                etag = file.etag,
                maximumResponseBytes = MAX_RAW_DISPLAY_PREVIEW_BYTES.toLong(),
            ),
        ),
    ).also { PreviewMemoryCache.put(key, it) }
}

/**
 * Loads and decodes the first usable media thumbnail without assuming that `hasPreview` means the
 * server has a working preview provider for this exact format.
 */
internal suspend fun <T> NextcloudPlatformServices.loadMediaThumbnailDecoded(
    session: NextcloudSession,
    file: NextcloudFile,
    width: Int = DEFAULT_PREVIEW_DIMENSION,
    height: Int = DEFAULT_PREVIEW_DIMENSION,
    decode: (MediaDisplayPayload) -> T?,
): T? = try {
    loadMediaDisplayPayload(
        file = file,
        loadCorePreview = {
            loadPreviewCached(session, file, width = width, height = height)
        },
        loadMemoriesRawRender = {
            loadMemoriesDecodableImageCached(session, file)
        },
        loadNativeRender = {
            loadNativeMediaPreview(
                session = session,
                file = file,
                maximumDimension = maxOf(width, height),
            )
        },
        loadFileRange = { offset, length, expectedEtag ->
            when {
                file.canUseEmbeddedRafPreviewFromMemories() -> downloadMemoriesFileRange(
                    session = session,
                    fileId = requireNotNull(file.fileId),
                    offset = offset,
                    length = length,
                    expectedEtag = expectedEtag,
                    expectedSourceSize = requireNotNull(file.size),
                )
                else -> error("This grid item has no bounded original stream.")
            }
        },
        decode = decode,
    ).value
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

internal suspend fun NextcloudPlatformServices.loadPersonCoverCached(
    session: NextcloudSession,
    person: NextcloudPerson,
): ByteArray {
    val coverId = requireNotNull(person.coverFileId) { "This person does not have a selected cover." }
    val key = PreviewCacheKey(
        account = previewCacheAccount(session),
        variant = "memories-person-${person.backend}-${person.id}",
        fileId = coverId,
        etag = person.coverEtag,
        width = 384,
        height = 384,
    )
    return PreviewMemoryCache.get(key) ?: loadPersonCover(session, person)
        .also { PreviewMemoryCache.put(key, it) }
}

private fun previewCacheAccount(session: NextcloudSession): String {
    return previewCacheDigest(session)
}

internal expect fun previewCacheDigest(session: NextcloudSession): String
