package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFileRangeSession
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidVirtualFileProxyCallbackTest {
    @Test
    fun `seekable reads hydrate aligned blocks and publish only a complete generation`() {
        val sourceBytes = "0123456789".encodeToByteArray()
        val ranges = mutableListOf<Pair<Long, Int>>()
        var closed = false
        val source = NextcloudFileRangeSession(
            size = sourceBytes.size.toLong(),
            readBlock = { offset, length ->
                ranges += offset to length
                sourceBytes.copyOfRange(offset.toInt(), offset.toInt() + length)
            },
            closeBlock = { closed = true },
        )
        val staging = Files.createTempFile("virtual-proxy-", ".part").toFile()
        var published: ByteArray? = null
        val callback = AndroidVirtualFileProxyCallback(
            source = source,
            staging = staging,
            blockSizeBytes = 4,
            publishCompleteHydration = { file -> published = file.readBytes() },
        )

        val middle = ByteArray(2)
        assertEquals(2, callback.onRead(5L, middle.size, middle))
        assertContentEquals("56".encodeToByteArray(), middle)
        assertEquals(null, published)

        val start = ByteArray(4)
        assertEquals(4, callback.onRead(0L, start.size, start))
        assertContentEquals("0123".encodeToByteArray(), start)
        assertEquals(null, published)

        val end = ByteArray(2)
        assertEquals(2, callback.onRead(8L, end.size, end))
        assertContentEquals("89".encodeToByteArray(), end)
        assertContentEquals(sourceBytes, published)
        assertEquals(listOf(4L to 4, 0L to 4, 8L to 2), ranges)

        callback.onRelease()
        assertTrue(closed)
    }

    @Test
    fun `uncached large file reads only the exact requested range`() {
        val sourceBytes = "abcdefghij".encodeToByteArray()
        val ranges = mutableListOf<Pair<Long, Int>>()
        val callback = AndroidVirtualFileProxyCallback(
            source = NextcloudFileRangeSession(
                size = sourceBytes.size.toLong(),
                readBlock = { offset, length ->
                    ranges += offset to length
                    sourceBytes.copyOfRange(offset.toInt(), offset.toInt() + length)
                },
            ),
            staging = null,
            publishCompleteHydration = {},
            blockSizeBytes = 4,
        )

        val destination = ByteArray(3)
        assertEquals(3, callback.onRead(6L, destination.size, destination))
        assertContentEquals("ghi".encodeToByteArray(), destination)
        assertEquals(listOf(6L to 3), ranges)
        callback.onRelease()
    }
}
