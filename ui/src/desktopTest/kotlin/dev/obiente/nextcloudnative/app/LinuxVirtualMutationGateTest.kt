package dev.obiente.nextcloudnative.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import jnr.ffi.Runtime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.serce.jnrfuse.ErrorCodes
import ru.serce.jnrfuse.struct.FuseFileInfo

class LinuxVirtualMutationGateTest {
    @Test
    fun `quiescence blocks new mutations and drains an active callback`() {
        val gate = LinuxVirtualMutationGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val mutation = workers.submit {
                gate.begin()
                try {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                } finally {
                    gate.end()
                }
            }
            check(entered.await(5, TimeUnit.SECONDS))
            val quiescence = workers.submit<Boolean> { gate.tryQuiesce { true } }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (gate.isAcceptingNewOperations() && System.nanoTime() < deadline) Thread.yield()

            assertFalse(gate.isAcceptingNewOperations())
            assertFailsWith<LinuxVirtualFileSystemException> { gate.begin() }
            assertFalse(quiescence.isDone)
            release.countDown()
            mutation.get(5, TimeUnit.SECONDS)
            assertTrue(quiescence.get(5, TimeUnit.SECONDS))

            gate.resume()
            gate.begin()
            gate.end()
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `failed quiescence reopens automatically so an unreleased writer can close`() {
        val fileSystem = LinuxNextcloudVirtualFileSystem(QuiescenceBackend())
        val fileInfo = FuseFileInfo.of(Runtime.getSystemRuntime().memoryManager.allocateDirect(256)).apply {
            flags.set(1L)
        }

        assertEquals(0, fileSystem.open("/draft.txt", fileInfo))
        assertFalse(fileSystem.quiesceWrites())
        assertEquals(0, fileSystem.release("/draft.txt", fileInfo))
        assertTrue(fileSystem.quiesceWrites())
        assertEquals(-ErrorCodes.EBUSY(), fileSystem.mkdir("/still-blocked", 0L))
        fileSystem.resumeWrites()
    }

    @Test
    fun `quiescence drains final pending file close through a read alias release`() {
        val closeStarted = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val fileSystem = LinuxNextcloudVirtualFileSystem(
            QuiescenceBackend {
                closeStarted.countDown()
                check(allowClose.await(5, TimeUnit.SECONDS))
            },
        )
        val runtime = Runtime.getSystemRuntime()
        val writer = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val reader = FuseFileInfo.of(runtime.memoryManager.allocateDirect(256))
        val workers = Executors.newFixedThreadPool(2)
        try {
            assertEquals(0, fileSystem.create("/pending.txt", 0L, writer))
            assertEquals(0, fileSystem.open("/pending.txt", reader))
            assertEquals(0, fileSystem.release("/pending.txt", writer))

            val release = workers.submit<Int> { fileSystem.release("/pending.txt", reader) }
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
            val quiescence = workers.submit<Boolean> { fileSystem.quiesceWrites() }
            assertFalse(quiescence.isDone)

            allowClose.countDown()
            assertEquals(0, release.get(5, TimeUnit.SECONDS))
            assertTrue(quiescence.get(5, TimeUnit.SECONDS))
            assertEquals(-ErrorCodes.EBUSY(), fileSystem.open("/draft.txt", reader))
            fileSystem.resumeWrites()
        } finally {
            allowClose.countDown()
            workers.shutdownNow()
        }
    }
}

private class QuiescenceBackend(
    private val onWriteClose: () -> Unit = {},
) : LinuxVirtualFileBackend {
    private val file = LinuxVirtualFileNode("draft.txt", "draft.txt", false, 5L, "etag")

    override fun resolve(path: String): LinuxVirtualFileNode? = when (path.trim('/')) {
        "" -> LinuxVirtualFileNode("", "", true, 0L, "root")
        "draft.txt" -> file
        else -> null
    }

    override fun list(path: String): List<LinuxVirtualFileNode> =
        if (path.trim('/').isEmpty()) listOf(file) else emptyList()

    override fun open(node: LinuxVirtualFileNode): LinuxVirtualFileReadHandle = error("Not used")
    override fun openWrite(path: String, existing: LinuxVirtualFileNode?, truncate: Boolean) =
        object : LinuxVirtualFileWriteHandle {
            override val size: Long = 5L
            override fun read(offset: Long, length: Int) = ByteArray(length)
            override fun write(offset: Long, bytes: ByteArray) = bytes.size
            override fun truncate(size: Long) = Unit
            override fun flush() = Unit
            override fun close() = onWriteClose()
        }

    override fun createDirectory(path: String) = Unit
    override fun delete(node: LinuxVirtualFileNode) = Unit
    override fun move(node: LinuxVirtualFileNode, destinationPath: String) = Unit
    override fun moveReplacing(
        node: LinuxVirtualFileNode,
        destination: LinuxVirtualFileNode,
        destinationPath: String,
    ) = Unit
    override fun close() = Unit
}
