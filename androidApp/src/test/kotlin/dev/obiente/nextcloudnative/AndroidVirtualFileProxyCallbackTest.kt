package dev.obiente.nextcloudnative

import android.os.OperationCanceledException
import dev.obiente.nextcloudnative.app.NextcloudFileRangeSession
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class AndroidVirtualFileProxyCallbackTest {
    @Test
    fun `ambiguous writeback metadata preserves retained user bytes and blocks discovery`() {
        val root = Files.createTempDirectory("document-writeback-recovery-").toFile()
        val staging = root.resolve("writeback-retained.stage").apply {
            writeText("retained user edit")
        }
        val manifest = root.resolve("${staging.name}.json").apply {
            writeText("{\"version\":2}")
        }

        try {
            assertEquals(0, cleanupIncompleteAndroidDocumentWritebacks(root))
            assertTrue(staging.isFile)
            assertEquals("retained user edit", staging.readText())
            assertTrue(manifest.isFile)

            assertFailsWith<IllegalStateException> {
                requireInspectableAndroidDocumentWritebackRecovery(root)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `writeback recovery releases ownership and rethrows cancellation`() {
        val cancellation = CancellationException("recovery cancelled")
        var releases = 0

        val thrown = assertFailsWith<CancellationException> {
            handleAndroidDocumentWritebackRecoveryFailure(cancellation) {
                releases += 1
            }
        }

        assertSame(cancellation, thrown)
        assertEquals(1, releases)
    }

    @Test
    fun `writeback reconciliation compares remote bytes without replacing the retained stage`() {
        val staging = Files.createTempFile("writeback-compare-", ".stage").toFile().apply {
            writeText("retained edit")
        }
        try {
            val matching = AndroidDocumentStagingComparator(staging)
            matching.write("retained edit".encodeToByteArray())
            assertTrue(matching.matches(staging.length()))
            matching.close()

            val different = AndroidDocumentStagingComparator(staging)
            different.write("remote change".encodeToByteArray())
            assertFalse(different.matches("remote change".length.toLong()))
            different.close()

            assertEquals("retained edit", staging.readText())
        } finally {
            staging.delete()
        }
    }

    @Test
    fun `active child writeback blocks parent and exact path mutations`() {
        assertTrue(
            androidDocumentWritebackPathBlocksMutation(
                "Projects/Active/notes.txt",
                "Projects/Active",
            ),
        )
        assertTrue(
            androidDocumentWritebackPathBlocksMutation(
                "Projects/Active/notes.txt",
                "Projects/Active/notes.txt",
            ),
        )
        assertFalse(
            androidDocumentWritebackPathBlocksMutation(
                "Projects/Active/notes.txt",
                "Projects/Archive",
            ),
        )
    }

    @Test
    fun `process restored writeback blocks destructive mutations until recovery`() {
        assertTrue(
            androidDocumentWritebacksBlockMutation(
                emptySequence(),
                sequenceOf("Projects/Active/notes.txt"),
                "Projects/Active",
            ),
        )
        assertTrue(
            androidDocumentWritebacksBlockMutation(
                emptySequence(),
                sequenceOf("Projects/Active/notes.txt"),
                "Projects/Active/notes.txt",
            ),
        )
        assertFalse(
            androidDocumentWritebacksBlockMutation(
                emptySequence(),
                sequenceOf("Projects/Active/notes.txt"),
                "Projects/Archive",
            ),
        )
        assertTrue(
            androidDocumentMutationPathsOverlap(
                setOf("Projects/Active"),
                setOf("Projects/Active/notes.txt"),
            ),
        )
        assertFalse(
            androidDocumentMutationPathsOverlap(
                setOf("Projects/Active"),
                setOf("Projects/Archive"),
            ),
        )
    }

    @Test
    fun `writable proxy capacity preserves the free space reserve`() {
        assertTrue(androidDocumentWriteFitsCapacity(40L, 50L, 110L, reserveBytes = 100L))
        assertFalse(androidDocumentWriteFitsCapacity(40L, 51L, 110L, reserveBytes = 100L))
        assertFalse(
            androidDocumentWriteFitsCapacity(
                currentBytes = Long.MAX_VALUE,
                writeEnd = Long.MIN_VALUE,
                availableBytes = Long.MAX_VALUE,
            ),
        )
        assertTrue(
            androidDocumentWriteFitsCapacity(
                currentBytes = 3L * 1024L * 1024L * 1024L,
                writeEnd = 3L * 1024L * 1024L * 1024L + 1L,
                availableBytes = 1024L,
                reserveBytes = 512L,
            ),
        )
    }

    @Test
    fun `writable proxy rejects offset overflow before changing staged bytes`() {
        val staging = Files.createTempFile("writable-proxy-", ".stage").toFile().apply {
            writeText("retained")
        }
        var releaseFailure: Throwable? = null
        val callback = AndroidWritableFileProxyCallback(staging) { failure -> releaseFailure = failure }

        assertFailsWith<android.system.ErrnoException> {
            callback.onWrite(Long.MAX_VALUE, 1, byteArrayOf(1))
        }
        assertEquals("retained", staging.readText())

        callback.onRelease()
        assertEquals(null, releaseFailure)
        staging.delete()
    }

    @Test
    fun `hydration capacity preserves the configured free space reserve`() {
        assertTrue(androidHydrationFitsCapacity(sizeBytes = 40L, availableBytes = 140L, reserveBytes = 100L))
        assertFalse(androidHydrationFitsCapacity(sizeBytes = 41L, availableBytes = 140L, reserveBytes = 100L))
        assertFalse(androidHydrationFitsCapacity(sizeBytes = Long.MAX_VALUE, availableBytes = Long.MAX_VALUE, reserveBytes = 1L))
    }

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
            publishCompleteHydration = { file ->
                published = file.readBytes()
                true
            },
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
    fun `uncached large file reuses bounded aligned range blocks`() {
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
            publishCompleteHydration = { true },
            blockSizeBytes = 4,
        )

        val destination = ByteArray(3)
        assertEquals(3, callback.onRead(6L, destination.size, destination))
        assertContentEquals("ghi".encodeToByteArray(), destination)
        assertEquals(listOf(4L to 4, 8L to 2), ranges)

        val repeated = ByteArray(2)
        assertEquals(2, callback.onRead(7L, repeated.size, repeated))
        assertContentEquals("hi".encodeToByteArray(), repeated)
        assertEquals(listOf(4L to 4, 8L to 2), ranges)
        callback.onRelease()
    }

    @Test
    fun `revoked external access cancels future range reads and releases once`() {
        var allowed = true
        var closed = 0
        var released = 0
        val callback = AndroidVirtualFileProxyCallback(
            source = NextcloudFileRangeSession(
                size = 4L,
                readBlock = { _, length -> ByteArray(length) },
                closeBlock = { closed += 1 },
            ),
            staging = null,
            publishCompleteHydration = { true },
            accessAllowed = { allowed },
            onReleased = { released += 1 },
        )

        allowed = false
        assertFailsWith<OperationCanceledException> {
            callback.onRead(0L, 1, ByteArray(1))
        }
        callback.cancel()
        assertEquals(1, released)
        callback.onRelease()
        callback.onRelease()

        assertEquals(1, closed)
        assertEquals(1, released)
    }

    @Test
    fun `revoked external access cancels exact local cache reads and releases once`() {
        val content = Files.createTempFile("local-handoff-proxy-", ".bin").toFile().apply {
            writeText("cached bytes")
        }
        var allowed = true
        var released = 0
        val callback = AndroidLocalFileProxyCallback(
            content = content,
            accessAllowed = { allowed },
            onReleased = { released += 1 },
        )
        try {
            val destination = ByteArray(6)
            assertEquals(6, callback.onRead(0L, destination.size, destination))
            assertContentEquals("cached".encodeToByteArray(), destination)

            allowed = false
            callback.cancel()
            assertFailsWith<OperationCanceledException> {
                callback.onRead(0L, 1, ByteArray(1))
            }
            callback.onRelease()
            callback.onRelease()
            assertEquals(1, released)
        } finally {
            callback.onRelease()
            content.delete()
        }
    }

    @Test
    fun `failed publication leaves staging disposable on release`() {
        val bytes = "abcd".encodeToByteArray()
        val staging = Files.createTempFile("virtual-proxy-failed-", ".part").toFile()
        val callback = AndroidVirtualFileProxyCallback(
            source = NextcloudFileRangeSession(
                size = bytes.size.toLong(),
                readBlock = { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length) },
            ),
            staging = staging,
            publishCompleteHydration = { false },
            blockSizeBytes = 4,
        )

        assertEquals(4, callback.onRead(0L, 4, ByteArray(4)))
        callback.onRelease()

        assertTrue(!staging.exists())
    }
}
