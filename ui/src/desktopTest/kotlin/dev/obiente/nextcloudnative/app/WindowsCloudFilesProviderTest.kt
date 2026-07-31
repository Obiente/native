package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsCloudFilesProviderTest {
    @Test
    fun `native layouts match 64 bit cfapi structures`() {
        assertEquals(
            WindowsCloudNativeLayoutSizes(
                registration = 72,
                policies = 24,
                fileSystemMetadata = 48,
                placeholder = 88,
                callbackInfo = 152,
            ),
            windowsCloudNativeLayoutSizes(),
        )
    }

    @Test
    fun `placeholder identities round trip and reject tampering`() {
        val identity = fixtureIdentity(size = 9_217L)
        val encoded = WindowsCloudFileIdentityCodec.encode(identity)

        assertEquals(identity, WindowsCloudFileIdentityCodec.decode(encoded))
        assertTrue(encoded.size <= 4_096)

        val tampered = encoded.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }
        assertFailsWith<IllegalArgumentException> { WindowsCloudFileIdentityCodec.decode(tampered) }
    }

    @Test
    fun `hydration planning aligns random reads and ends exactly at eof`() {
        val ranges = planWindowsCloudHydration(
            requiredOffset = 4_321L,
            requiredLength = 20_000L,
            fileSize = 19_111L,
            maximumChunkBytes = 8_192,
        )

        assertEquals(4_096L, ranges.first().offset)
        assertEquals(19_111L, ranges.last().offset + ranges.last().length)
        assertTrue(ranges.dropLast(1).all { it.length % 4_096 == 0 })
        assertTrue(ranges.all { it.offset % 4_096L == 0L })
        val interior = planWindowsCloudHydration(5_001L, 1L, 30_000L)
        assertEquals(8_192L, interior.single().offset + interior.single().length)
    }

    @Test
    fun `fetch callback transfers exact generation in aligned chunks`() {
        val root = createTempDirectory("windows-cloud-provider-")
        val bytes = ByteArray(12_345) { index -> (index % 251).toByte() }
        val backend = FakeBackend(bytes)
        val api = FakeApi(expectedTransfers = 1)
        val provider = WindowsCloudFilesProvider(root, backend, api)
        val identity = fixtureIdentity(size = bytes.size.toLong())
        val info = callbackInfo(identity)

        provider.fetchData(info, requiredOffset = 4_500L, requiredLength = 7_845L)

        assertTrue(api.awaitTransfers())
        assertEquals(listOf(4_096L), api.transfers.map { it.first })
        assertContentEquals(bytes.copyOfRange(4_096, bytes.size), api.transfers.flatMap { it.second.asIterable() }.toByteArray())
        provider.close()
    }

    @Test
    fun `new ordinary local file uploads before conversion to placeholder`() {
        val root = createTempDirectory("windows-cloud-local-")
        val local = root.resolve("Notes/new.txt")
        local.parent.toFile().mkdirs()
        local.writeBytes("offline edit".encodeToByteArray())
        val backend = FakeBackend("remote".encodeToByteArray())
        val api = FakeApi(expectedConversions = 1)
        val provider = WindowsCloudFilesProvider(root, backend, api)

        provider.localEntryChanged(local)

        assertTrue(api.awaitConversions())
        assertEquals("Notes/new.txt", backend.lastUploadedPath)
        assertEquals(WindowsCloudPlaceholderState.InSync, api.placeholderState(local))
        provider.close()
    }

    private fun fixtureIdentity(size: Long) = WindowsCloudFileIdentity(
        accountId = "account-01",
        path = "Photos/example.raf",
        remoteRevision = "\"etag-01\"",
        size = size,
        directory = false,
    )

    private fun callbackInfo(identity: WindowsCloudFileIdentity) = WindowsCloudCallbackInfo(
        connectionKey = 10L,
        transferKey = 20L,
        requestKey = 30L,
        normalizedPath = "C:\\Nextcloud Native\\Photos\\example.raf",
        fileIdentity = WindowsCloudFileIdentityCodec.encode(identity),
        fileSize = identity.size,
        priorityHint = 12,
    )

    private class FakeBackend(private val source: ByteArray) : WindowsCloudFilesBackend {
        override val accountId: String = "account-01"
        var lastUploadedPath: String? = null

        override fun resolve(path: String): WindowsCloudFileIdentity? = null
        override fun list(path: String): List<WindowsCloudFileIdentity> = emptyList()

        override fun open(identity: WindowsCloudFileIdentity): WindowsCloudFileReadHandle =
            object : WindowsCloudFileReadHandle {
                override val size: Long = source.size.toLong()
                override fun read(offset: Long, length: Int): ByteArray =
                    source.copyOfRange(offset.toInt(), offset.toInt() + length)
                override fun close() = Unit
            }

        override fun upload(
            path: String,
            localFile: File,
            expectedRemoteRevision: String?,
        ): WindowsCloudFileIdentity {
            lastUploadedPath = path
            return WindowsCloudFileIdentity(accountId, path, "\"uploaded\"", localFile.length(), false)
        }

        override fun createDirectory(path: String): WindowsCloudFileIdentity =
            WindowsCloudFileIdentity(accountId, path, "\"directory\"", 0L, true)

        override fun delete(identity: WindowsCloudFileIdentity) = Unit
        override fun move(identity: WindowsCloudFileIdentity, destinationPath: String): WindowsCloudFileIdentity =
            identity.copy(path = destinationPath)
    }

    private class FakeApi(
        expectedTransfers: Int = 0,
        expectedConversions: Int = 0,
    ) : WindowsCloudFilesApi {
        private val transferLatch = CountDownLatch(expectedTransfers)
        private val conversionLatch = CountDownLatch(expectedConversions)
        private val states = HashMap<Path, WindowsCloudPlaceholderState>()
        val transfers = mutableListOf<Pair<Long, ByteArray>>()

        override fun registerSyncRoot(root: Path, syncRootIdentity: ByteArray) = Unit
        override fun connect(root: Path, callbacks: WindowsCloudFilesCallbacks): Long = 1L
        override fun disconnect(connectionKey: Long) = Unit
        override fun createPlaceholders(baseDirectory: Path, placeholders: List<WindowsCloudPlaceholder>) = Unit
        override fun transferData(info: WindowsCloudCallbackInfo, offset: Long, bytes: ByteArray) {
            synchronized(transfers) { transfers += offset to bytes.copyOf() }
            transferLatch.countDown()
        }
        override fun failData(info: WindowsCloudCallbackInfo, offset: Long, length: Long, message: String) = Unit
        override fun completePlaceholderFetch(info: WindowsCloudCallbackInfo, placeholders: List<WindowsCloudPlaceholder>) = Unit
        override fun failPlaceholderFetch(info: WindowsCloudCallbackInfo) = Unit
        override fun acknowledgeDelete(info: WindowsCloudCallbackInfo, accepted: Boolean) = Unit
        override fun acknowledgeRename(info: WindowsCloudCallbackInfo, accepted: Boolean) = Unit
        override fun placeholderState(path: Path): WindowsCloudPlaceholderState =
            states[path] ?: WindowsCloudPlaceholderState.Absent
        override fun allocatedBytes(path: Path): Long = if (states[path] == WindowsCloudPlaceholderState.InSync) {
            path.toFile().length()
        } else {
            0L
        }
        override fun lastAccessedAtEpochMillis(path: Path): Long = 1L
        override fun isPinned(path: Path): Boolean = false
        override fun updatePlaceholder(path: Path, placeholder: WindowsCloudPlaceholder) {
            states[path] = WindowsCloudPlaceholderState.InSync
        }
        override fun convertToPlaceholder(path: Path, placeholder: WindowsCloudPlaceholder) {
            states[path] = WindowsCloudPlaceholderState.Dirty
        }
        override fun markInSync(path: Path) {
            states[path] = WindowsCloudPlaceholderState.InSync
            conversionLatch.countDown()
        }
        override fun dehydrate(path: Path): Long = 0L
        override fun close() = Unit

        fun awaitTransfers(): Boolean = transferLatch.await(5, TimeUnit.SECONDS)
        fun awaitConversions(): Boolean = conversionLatch.await(5, TimeUnit.SECONDS)
    }
}
