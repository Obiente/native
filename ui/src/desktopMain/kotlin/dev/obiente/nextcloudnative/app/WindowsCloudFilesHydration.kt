package dev.obiente.nextcloudnative.app

import java.util.concurrent.CancellationException

internal const val WINDOWS_CLOUD_ALIGNMENT = 4 * 1024L

/** Bytes handed to CfExecute at a time; small enough that an opening app sees data early. */
internal const val WINDOWS_CLOUD_HYDRATION_CHUNK_BYTES = 1024 * 1024

/** Read access to one pinned remote file generation for hydration. */
internal interface WindowsCloudFileReadHandle : AutoCloseable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray

    /**
     * Delivers a range in order as [chunkBytes] chunks. Backends that can stream serve it with one
     * request; this fallback costs one request per chunk.
     */
    fun stream(offset: Long, length: Long, chunkBytes: Int, onChunk: (offset: Long, bytes: ByteArray) -> Unit) {
        var position = 0L
        while (position < length) {
            val chunk = minOf(chunkBytes.toLong(), length - position).toInt()
            onChunk(offset + position, read(offset + position, chunk))
            position += chunk
        }
    }
}

internal data class WindowsCloudHydrationRange(val offset: Long, val length: Int) {
    init {
        require(offset >= 0L && offset % WINDOWS_CLOUD_ALIGNMENT == 0L)
        require(length > 0)
    }
}

/** Produces CfExecute-compatible chunks; only the final range may end unaligned at EOF. */
internal fun planWindowsCloudHydration(
    requiredOffset: Long,
    requiredLength: Long,
    fileSize: Long,
    maximumChunkBytes: Int = WINDOWS_CLOUD_HYDRATION_CHUNK_BYTES,
): List<WindowsCloudHydrationRange> {
    require(requiredOffset >= 0L && requiredLength > 0L && fileSize > 0L)
    require(
        maximumChunkBytes.toLong() >= WINDOWS_CLOUD_ALIGNMENT &&
            maximumChunkBytes.toLong() % WINDOWS_CLOUD_ALIGNMENT == 0L,
    )
    val requiredEnd = minOf(fileSize, Math.addExact(requiredOffset, requiredLength))
    require(requiredOffset < requiredEnd)
    val transferEnd = if (requiredEnd == fileSize) {
        fileSize
    } else {
        minOf(fileSize, Math.addExact(requiredEnd, WINDOWS_CLOUD_ALIGNMENT - 1L) / WINDOWS_CLOUD_ALIGNMENT * WINDOWS_CLOUD_ALIGNMENT)
    }
    var cursor = requiredOffset - requiredOffset % WINDOWS_CLOUD_ALIGNMENT
    return buildList {
        while (cursor < transferEnd) {
            val remaining = transferEnd - cursor
            val length = minOf(maximumChunkBytes.toLong(), remaining).toInt()
            add(WindowsCloudHydrationRange(cursor, length))
            cursor += length
        }
    }
}

/**
 * Serves one FETCH_DATA request. Windows asks for a whole file when an application opens it, so
 * the aligned span is fetched as one streamed range rather than one server request per chunk,
 * and each chunk is transferred as soon as it arrives. Cancellation is checked between chunks
 * and surfaces as [CancellationException]; any other mismatch fails the request.
 */
internal fun hydrateWindowsCloudRange(
    source: WindowsCloudFileReadHandle,
    requiredOffset: Long,
    requiredLength: Long,
    cancelled: () -> Boolean,
    transfer: (offset: Long, bytes: ByteArray) -> Unit,
) {
    val plan = planWindowsCloudHydration(requiredOffset, requiredLength, source.size)
    val start = plan.first().offset
    val end = plan.last().let { it.offset + it.length }
    var next = 0
    source.stream(start, end - start, WINDOWS_CLOUD_HYDRATION_CHUNK_BYTES) { offset, bytes ->
        if (cancelled()) throw CancellationException("Windows cancelled the hydration request.")
        val expected = plan.getOrNull(next++)
        check(expected != null && offset == expected.offset && bytes.size == expected.length) {
            "The remote file returned an incomplete range."
        }
        transfer(offset, bytes)
    }
    check(next == plan.size) { "The remote file returned an incomplete range." }
}
