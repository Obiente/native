package dev.obiente.nextcloudnative.app

import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsCloudFilesHydrationTest {
    private val content = ByteArray(5 * WINDOWS_CLOUD_HYDRATION_CHUNK_BYTES + 12_345) { (it % 251).toByte() }

    /** Records each backend request; [streaming] backends answer a whole range at once. */
    private inner class RecordingHandle(private val streaming: Boolean) : WindowsCloudFileReadHandle {
        val requests = mutableListOf<Pair<Long, Long>>()
        var shortenChunk: Int? = null
        override val size = content.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            requests += offset to length.toLong()
            return content.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        override fun stream(offset: Long, length: Long, chunkBytes: Int, onChunk: (Long, ByteArray) -> Unit) {
            if (!streaming) return super.stream(offset, length, chunkBytes, onChunk)
            requests += offset to length
            var position = 0L
            var index = 0
            while (position < length) {
                val chunk = minOf(chunkBytes.toLong(), length - position).toInt()
                val start = (offset + position).toInt()
                val delivered = if (shortenChunk == index++) chunk - 1 else chunk
                onChunk(offset + position, content.copyOfRange(start, start + delivered))
                position += chunk
            }
        }
        override fun close() = Unit
    }

    private fun hydrate(
        handle: WindowsCloudFileReadHandle,
        offset: Long = 0L,
        length: Long = content.size.toLong(),
        cancelled: () -> Boolean = { false },
    ): List<Pair<Long, ByteArray>> = buildList {
        hydrateWindowsCloudRange(handle, offset, length, cancelled) { at, bytes -> add(at to bytes) }
    }

    @Test
    fun `a whole-file request is served with one streamed backend request`() {
        val handle = RecordingHandle(streaming = true)

        val transfers = hydrate(handle)

        assertEquals(listOf(0L to content.size.toLong()), handle.requests)
        assertEquals(6, transfers.size)
        assertTrue(transfers.dropLast(1).all { (_, bytes) -> bytes.size == WINDOWS_CLOUD_HYDRATION_CHUNK_BYTES })
        assertTrue(transfers.all { (offset, _) -> offset % WINDOWS_CLOUD_ALIGNMENT == 0L })
        assertContentEquals(content, transfers.flatMap { it.second.asIterable() }.toByteArray())
    }

    @Test
    fun `an interior request streams only its aligned span`() {
        val handle = RecordingHandle(streaming = true)

        val transfers = hydrate(handle, offset = 2_000_123L, length = 10L)

        assertEquals(listOf(1_998_848L to 4_096L), handle.requests)
        assertEquals(listOf(1_998_848L), transfers.map { it.first })
    }

    @Test
    fun `a backend without streaming still transfers the exact bytes`() {
        val handle = RecordingHandle(streaming = false)

        val transfers = hydrate(handle)

        assertEquals(6, handle.requests.size)
        assertContentEquals(content, transfers.flatMap { it.second.asIterable() }.toByteArray())
    }

    @Test
    fun `cancellation stops the stream before the next chunk is transferred`() {
        val handle = RecordingHandle(streaming = true)
        var transferred = 0

        assertFailsWith<CancellationException> {
            hydrateWindowsCloudRange(handle, 0L, content.size.toLong(), { transferred >= 2 }) { _, _ -> transferred++ }
        }

        assertEquals(2, transferred)
    }

    @Test
    fun `a short chunk fails the request without transferring it`() {
        val handle = RecordingHandle(streaming = true).apply { shortenChunk = 1 }
        val transfers = mutableListOf<Long>()

        assertFailsWith<IllegalStateException> {
            hydrateWindowsCloudRange(handle, 0L, content.size.toLong(), { false }) { offset, _ -> transfers += offset }
        }

        assertEquals(listOf(0L), transfers)
    }
}
